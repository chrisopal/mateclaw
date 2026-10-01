package vip.mate.presales;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Flux;
import vip.mate.agent.AgentService;
import vip.mate.agent.execution.ProjectExecutionRevalidatorDispatcher;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.repository.AgentMapper;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.repository.ModelConfigMapper;
import vip.mate.llm.service.ModelCapabilityService;
import vip.mate.llm.service.ModelConfigService;
import vip.mate.llm.service.ModelProviderService;
import vip.mate.semantic.support.SemanticHttpFixture;
import vip.mate.semantic.web.SemanticApiException;
import vip.mate.workspace.conversation.ConversationService;
import vip.mate.workspace.core.service.ProjectAuthorityFence;
import vip.mate.workspace.core.service.ProjectSourceAccess;

/**
 * Real policy/runtime and Spring result transaction; only external agent/chat boundaries are
 * doubles.
 */
@Import({
    PresalesRuntimeTransactionIntegrationTest.Boundaries.class,
    PresalesAccess.class,
    PresalesService.class,
    PresalesArtifactRenderer.class,
    PresalesEmployeeRuntime.class,
    PresalesExecutionRevalidationProvider.class,
    PresalesContextProvider.class,
    ProjectSourceAccess.class,
    ProjectExecutionRevalidatorDispatcher.class,
    ModelConfigService.class
})
@TestPropertySource(
        properties = {
            "mateclaw.presales.enabled=true",
            "spring.datasource.url=jdbc:h2:mem:presales_runtime_transaction;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"
        })
class PresalesRuntimeTransactionIntegrationTest extends SemanticHttpFixture {
    @DynamicPropertySource
    static void optionalDisposableMySql(DynamicPropertyRegistry properties) throws Exception {
        String url = System.getenv("MATECLAW_ACCEPTANCE_JDBC_URL");
        if (url == null || url.isBlank()) return;
        var match =
                java.util.regex.Pattern.compile(
                                "^jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/(mateclaw_aq_acceptance_[a-z0-9]+)(?:\\?.*)?$")
                        .matcher(url);
        if (!match.matches())
            throw new IllegalArgumentException(
                    "Acceptance requires a disposable loopback database");
        String user = Objects.requireNonNull(System.getenv("MATECLAW_ACCEPTANCE_JDBC_USER"));
        String password =
                Objects.requireNonNull(System.getenv("MATECLAW_ACCEPTANCE_JDBC_PASSWORD"));
        try (var connection = DriverManager.getConnection(url, user, password);
                var tables =
                        connection
                                .getMetaData()
                                .getTables(
                                        connection.getCatalog(),
                                        null,
                                        "%",
                                        new String[] {"TABLE", "VIEW"})) {
            if (!match.group(1).equals(connection.getCatalog()) || tables.next())
                throw new IllegalArgumentException(
                        "Acceptance database must be empty before Flyway migration");
        }
        properties.add("spring.datasource.url", () -> url);
        properties.add("spring.datasource.username", () -> user);
        properties.add("spring.datasource.password", () -> password);
        properties.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        properties.add("spring.flyway.locations", () -> "classpath:db/migration/mysql");
    }

    @TestConfiguration
    @MapperScan("vip.mate.llm.repository")
    static class Boundaries {
        @Bean
        ObservedFence fence(JdbcTemplate jdbc, SqlSessionTemplate sessions, DataSource dataSource) {
            return new ObservedFence(jdbc, sessions, dataSource);
        }
    }

    static class ObservedFence extends ProjectAuthorityFence {
        final DataSource dataSource;
        volatile CountDownLatch entered;
        final AtomicReference<Object> resource = new AtomicReference<>();
        volatile Integer isolation;

        ObservedFence(JdbcTemplate jdbc, SqlSessionTemplate sessions, DataSource dataSource) {
            super(jdbc, sessions);
            this.dataSource = dataSource;
        }

        @Override
        public boolean lockForResult(
                String workspaceId,
                Collection<String> actorIds,
                String employeeId,
                String modelConfigId,
                Collection<Source> sources) {
            resource.set(TransactionSynchronizationManager.getResource(dataSource));
            isolation = TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
            if (entered != null) entered.countDown();
            return super.lockForResult(workspaceId, actorIds, employeeId, modelConfigId, sources);
        }
    }

    enum InvalidExecution {
        ACTOR_DISABLED,
        EMPLOYEE_DISABLED,
        MODEL_CHANGED,
        VIEWER,
        CANCELLED,
        PROJECT_CHANGED
    }

    enum Revocation {
        WORKSPACE,
        MEMBER
    }

    @Autowired PresalesService service;
    @Autowired PresalesEmployeeRuntime runtime;
    @Autowired PresalesContextProvider contexts;
    @MockBean AgentService agents;
    @MockBean ConversationService conversations;
    @MockBean ModelCapabilityService capabilities;
    @MockBean ModelProviderService providers;
    @Autowired AgentMapper agentMapper;
    @Autowired ModelConfigMapper modelMapper;
    @Autowired ObservedFence fence;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired DataSource dataSource;
    private String actor, actorName, agentId;
    private Long modelId;
    private ObjectNode project, task, snapshot;

    @BeforeEach
    void prepareRuntime() {
        reset(agents, conversations);
        fence.entered = null;
        fence.resource.set(null);
        actor =
                jdbc.queryForObject(
                                "SELECT user_id FROM mate_workspace_member WHERE workspace_id=? AND role='member' AND deleted=0",
                                Long.class,
                                workspace)
                        .toString();
        actorName = auth.findById(Long.valueOf(actor)).getUsername();
        authenticate();
        var model = new ModelConfigEntity();
        model.setName("runtime-" + UUID.randomUUID());
        model.setModelName(model.getName());
        model.setProvider("acceptance-double");
        model.setEnabled(true);
        model.setIsDefault(false);
        model.setDeleted(0);
        modelMapper.insert(model);
        modelId = model.getId();
        var employee = new AgentEntity();
        employee.setName("runtime-" + UUID.randomUUID());
        employee.setWorkspaceId(Long.valueOf(workspace));
        employee.setEnabled(true);
        employee.setDeleted(0);
        employee.setRuntimeType("native");
        employee.setModelName(model.getModelName());
        agentMapper.insert(employee);
        agentId = employee.getId().toString();
        // Employee lookup remains a real mapper read; the graph/model network is the test boundary.
        when(agents.getAgent(anyLong()))
                .thenAnswer(call -> agentMapper.selectById((Long) call.getArgument(0)));
        when(agents.chatStructuredStream(
                        anyLong(), anyString(), anyString(), anyString(), isNull(), any(), any()))
                .thenReturn(
                        Flux.just(
                                AgentService.StreamDelta.finalAnswer(
                                        "{\"schemaVersion\":1,\"needsHumanReview\":true,\"items\":[],\"unknowns\":[],\"assumptions\":[]}",
                                        true)));
        project =
                service.create(
                        workspace,
                        new PresalesDtos.Create(
                                "Acceptance",
                                "Fixture",
                                null,
                                agentId,
                                null,
                                null,
                                0,
                                UUID.randomUUID().toString()));
        snapshot = contexts.snapshot(workspace, project, "S1", "Clarify scope");
        snapshot.put("projectVersion", project.path("version").asInt() + 1);
        var pin = runtime.pin(workspace, agentId, "S1");
        String run = UUID.randomUUID().toString();
        task =
                json.createObjectNode()
                        .put("runId", run)
                        .put("operationId", UUID.randomUUID().toString())
                        .put("status", "RUNNING")
                        .put("authority", "UNTRUSTED_DRAFT")
                        .put("agentId", agentId)
                        .put("skill", "S1")
                        .put(
                                "conversationId",
                                "presales:"
                                        + workspace
                                        + ":"
                                        + project.path("id").asText()
                                        + ":"
                                        + run)
                        .put("modelConfigId", pin.modelConfigId())
                        .put("configDigest", pin.configDigest())
                        .put("skillName", pin.skillName())
                        .put("skillDigest", pin.skillDigest());
        task.set("contextSnapshot", snapshot);
        project =
                service.command(
                        workspace,
                        project.path("id").asText(),
                        new PresalesDtos.Command(
                                project.path("version").asInt(),
                                UUID.randomUUID().toString(),
                                "SAVE_AI_TASK",
                                task));
        task = ((ObjectNode) project.path("tasks").get(0)).deepCopy();
    }

    @AfterEach
    void clearIdentity() {
        SecurityContextHolder.clearContext();
        fence.entered = null;
    }

    private void authenticate() {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(actorName, "unused", List.of()));
    }

    private ObjectNode execute() {
        return runtime.execute(
                workspace,
                actor,
                agentId,
                task.path("conversationId").asText(),
                PresalesModelAdapter.instructions("S1"),
                task,
                snapshot);
    }

    private PresalesDtos.Command resultCommand() {
        var candidate = task.deepCopy().put("status", "SUCCEEDED");
        candidate.set("result", execute());
        return new PresalesDtos.Command(
                project.path("version").asInt(),
                UUID.randomUUID().toString(),
                "SAVE_AI_TASK",
                candidate);
    }

    private void noExternalCall() {
        verify(agents, never())
                .chatStructuredStream(
                        anyLong(), anyString(), anyString(), anyString(), isNull(), any(), any());
        verifyNoInteractions(conversations);
    }

    private void unchanged(int receipts, int revisions) throws Exception {
        var stored =
                json.readTree(
                        jdbc.queryForObject(
                                "SELECT body_json FROM mate_presales_project WHERE id=?",
                                String.class,
                                project.path("id").asText()));
        assertEquals(project, stored);
        assertEquals(receipts, receiptCount());
        assertEquals(revisions, revisionCount());
    }

    private int receiptCount() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM mate_presales_operation WHERE workspace_id=? AND actor_id=?",
                Integer.class,
                workspace,
                actor);
    }

    private int revisionCount() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM mate_presales_revision WHERE project_id=?",
                Integer.class,
                project.path("id").asText());
    }

    @ParameterizedTest
    @EnumSource(InvalidExecution.class)
    void invalidExecutionIsRejectedBeforeExternalCalls(InvalidExecution invalid) throws Exception {
        invalidate(invalid);
        int receipts = receiptCount(), revisions = revisionCount();
        var error = assertThrows(SemanticApiException.class, this::execute);
        rejectedAs(error, invalid, false);
        noExternalCall();
        unchanged(receipts, revisions);
    }

    private void invalidate(InvalidExecution invalid) {
        switch (invalid) {
            case ACTOR_DISABLED ->
                    jdbc.update("UPDATE mate_user SET enabled=FALSE WHERE id=?", actor);
            case EMPLOYEE_DISABLED ->
                    jdbc.update("UPDATE mate_agent SET enabled=FALSE WHERE id=?", agentId);
            case MODEL_CHANGED ->
                    jdbc.update(
                            "UPDATE mate_model_config SET temperature=0.123 WHERE id=?", modelId);
            case VIEWER ->
                    jdbc.update(
                            "UPDATE mate_workspace_member SET role='viewer' WHERE workspace_id=? AND user_id=?",
                            workspace,
                            actor);
            case CANCELLED, PROJECT_CHANGED -> {
                project =
                        service.command(
                                workspace,
                                project.path("id").asText(),
                                new PresalesDtos.Command(
                                        project.path("version").asInt(),
                                        UUID.randomUUID().toString(),
                                        invalid == InvalidExecution.CANCELLED
                                                ? "CANCEL_AI_TASK"
                                                : "UPDATE_PROJECT",
                                        invalid == InvalidExecution.CANCELLED
                                                ? json.createObjectNode()
                                                        .put("taskId", task.path("id").asText())
                                                : json.createObjectNode().put("goal", "Changed")));
            }
        }
    }

    private void rejectedAs(
            SemanticApiException error, InvalidExecution invalid, boolean resultPhase) {
        assertEquals(
                invalid == InvalidExecution.ACTOR_DISABLED
                        ? 401
                        : invalid == InvalidExecution.VIEWER ? 403 : 409,
                error.status());
        assertEquals(
                switch (invalid) {
                    case ACTOR_DISABLED -> "UNAUTHENTICATED";
                    case EMPLOYEE_DISABLED -> "EMPLOYEE_UNAVAILABLE";
                    case MODEL_CHANGED -> "EXECUTION_PIN_CHANGED";
                    case VIEWER -> "FORBIDDEN";
                    case CANCELLED, PROJECT_CHANGED ->
                            resultPhase ? "VERSION_CONFLICT" : "PROJECT_CHANGED_DURING_GENERATION";
                },
                error.code());
    }

    @ParameterizedTest
    @EnumSource(InvalidExecution.class)
    void resultProducedBeforeAuthorityOrTaskChangedCannotOverwriteDurableState(
            InvalidExecution invalid) throws Exception {
        var command = resultCommand();
        invalidate(invalid);
        int receipts = receiptCount(), revisions = revisionCount();
        var error =
                assertThrows(
                        SemanticApiException.class,
                        () ->
                                service.saveEmployeeTask(
                                        workspace, project.path("id").asText(), command));
        rejectedAs(error, invalid, true);
        unchanged(receipts, revisions);
        verify(agents, times(1))
                .chatStructuredStream(
                        anyLong(), anyString(), anyString(), anyString(), isNull(), any(), any());
    }

    @Test
    void resultCommitsInIndependentReadCommittedTransactionDespiteCallerRollback() {
        assertTrue(AopUtils.isAopProxy(service));
        var command = resultCommand();
        var outerResource = new AtomicReference<Object>();
        var outer = new TransactionTemplate(transactionManager);
        outer.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
        assertThrows(
                IllegalStateException.class,
                () ->
                        outer.executeWithoutResult(
                                status -> {
                                    outerResource.set(
                                            TransactionSynchronizationManager.getResource(
                                                    dataSource));
                                    jdbc.update(
                                            "UPDATE mate_workspace SET description='caller-only' WHERE id=?",
                                            otherWorkspace);
                                    var accepted =
                                            service.saveEmployeeTask(
                                                    workspace,
                                                    project.path("id").asText(),
                                                    command);
                                    assertEquals(
                                            "SUCCEEDED",
                                            accepted.path("tasks").get(0).path("status").asText());
                                    assertNotSame(outerResource.get(), fence.resource.get());
                                    assertEquals(
                                            TransactionDefinition.ISOLATION_READ_COMMITTED,
                                            fence.isolation);
                                    throw new IllegalStateException("Roll back caller only");
                                }));
        assertNotEquals(
                "caller-only",
                jdbc.queryForObject(
                        "SELECT description FROM mate_workspace WHERE id=?",
                        String.class,
                        otherWorkspace));
        var accepted = service.get(workspace, project.path("id").asText());
        assertEquals(project.path("version").asInt() + 1, accepted.path("version").asInt());
        assertEquals("SUCCEEDED", accepted.path("tasks").get(0).path("status").asText());
        assertEquals(
                command.payload().path("result"), accepted.path("tasks").get(0).path("result"));
        verify(agents, times(1))
                .chatStructuredStream(
                        anyLong(), anyString(), anyString(), anyString(), isNull(), any(), any());
    }

    @ParameterizedTest
    @EnumSource(Revocation.class)
    void realRuntimeRejectsRevocationCommittedWhileResultWaitedForAuthority(Revocation revocation)
            throws Exception {
        var command = resultCommand();
        int receipts = receiptCount(), revisions = revisionCount();
        var changed = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        fence.entered = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var writer =
                    executor.submit(
                            () ->
                                    new TransactionTemplate(transactionManager)
                                            .executeWithoutResult(
                                                    status -> {
                                                        if (revocation == Revocation.WORKSPACE)
                                                            jdbc.update(
                                                                    "UPDATE mate_workspace SET deleted=1 WHERE id=?",
                                                                    workspace);
                                                        else
                                                            jdbc.update(
                                                                    "UPDATE mate_workspace_member SET role='viewer' WHERE workspace_id=? AND user_id=?",
                                                                    workspace,
                                                                    actor);
                                                        changed.countDown();
                                                        await(release);
                                                    }));
            assertTrue(changed.await(10, TimeUnit.SECONDS));
            var accepted =
                    executor.submit(
                            () -> {
                                authenticate();
                                try {
                                    var error =
                                            assertThrows(
                                                    SemanticApiException.class,
                                                    () ->
                                                            service.saveEmployeeTask(
                                                                    workspace,
                                                                    project.path("id").asText(),
                                                                    command));
                                    assertEquals(
                                            revocation == Revocation.WORKSPACE ? 404 : 403,
                                            error.status());
                                    assertEquals(
                                            revocation == Revocation.WORKSPACE
                                                    ? "NOT_FOUND"
                                                    : "FORBIDDEN",
                                            error.code());
                                } finally {
                                    SecurityContextHolder.clearContext();
                                }
                            });
            try {
                assertTrue(fence.entered.await(10, TimeUnit.SECONDS));
                assertThrows(
                        TimeoutException.class, () -> accepted.get(200, TimeUnit.MILLISECONDS));
            } finally {
                release.countDown();
            }
            writer.get(10, TimeUnit.SECONDS);
            accepted.get(10, TimeUnit.SECONDS);
            unchanged(receipts, revisions);
            verify(agents, times(1))
                    .chatStructuredStream(
                            anyLong(),
                            anyString(),
                            anyString(),
                            anyString(),
                            isNull(),
                            any(),
                            any());
        } finally {
            release.countDown();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(error);
        }
    }
}

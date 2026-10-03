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
import org.junit.jupiter.params.provider.ValueSource;
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
    vip.mate.presales.repository.PresalesProjectRepository.class,
    vip.mate.presales.repository.PresalesArtifactRepository.class,
    PresalesSourceAuthorization.class,
    vip.mate.wiki.service.WikiSourceReadService.class,
    vip.mate.wiki.repository.WikiSourceReadRepository.class,
    vip.mate.semantic.source.SourceGovernanceReadService.class,
    vip.mate.semantic.source.repository.SourceGovernanceReadRepository.class,
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
        assertListingVersion(project);
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

    @Test
    void revokedEmployeeCanBeReboundUsingOnlyRepairMetadataAndEligibleCurrentSourceScope() {
        long kbId = com.baomidou.mybatisplus.core.toolkit.IdWorker.getId();
        jdbc.update(
                "INSERT INTO mate_wiki_knowledge_base(id,name,workspace_id,create_time,update_time,deleted) VALUES(?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)",
                kbId,
                "Repair fixture",
                Long.valueOf(workspace));
        var kb = new vip.mate.wiki.model.WikiKnowledgeBaseEntity();
        kb.setId(kbId);
        kb.setWorkspaceId(Long.valueOf(workspace));
        kb.setDeleted(0);
        when(wikiKnowledgeBases.getById(kbId)).thenReturn(kb);
        jdbc.update(
                "INSERT INTO mate_agent_wiki_kb(id,agent_id,kb_id,enabled,deleted) VALUES(?,?,?,TRUE,0)",
                com.baomidou.mybatisplus.core.toolkit.IdWorker.getId(),
                Long.valueOf(agentId),
                kbId);
        var bound =
                service.command(
                        workspace,
                        project.path("id").asText(),
                        new PresalesDtos.Command(
                                project.path("version").asInt(),
                                UUID.randomUUID().toString(),
                                "BIND_MATERIAL",
                                json.createObjectNode()
                                        .put("kbId", Long.toString(kbId))
                                        .put("role", "PROJECT")));
        jdbc.update("UPDATE mate_agent SET enabled=FALSE WHERE id=?", Long.valueOf(agentId));
        assertEquals(
                403,
                assertThrows(
                                SemanticApiException.class,
                                () -> service.get(workspace, project.path("id").asText()))
                        .status());
        var repair = service.repairContext(workspace, project.path("id").asText());
        assertTrue(repair.path("sourceAccessRestricted").asBoolean());
        assertTrue(repair.path("materials").isEmpty());
        assertTrue(repair.path("tasks").isEmpty());
        var replacement = new AgentEntity();
        replacement.setName("Replacement fixture");
        replacement.setWorkspaceId(Long.valueOf(workspace));
        replacement.setEnabled(false);
        replacement.setDeleted(0);
        replacement.setRuntimeType("native");
        replacement.setWikiDisabled(false);
        agentMapper.insert(replacement);
        jdbc.update(
                "INSERT INTO mate_agent_wiki_kb(id,agent_id,kb_id,enabled,deleted) VALUES(?,?,?,TRUE,0)",
                com.baomidou.mybatisplus.core.toolkit.IdWorker.getId(),
                replacement.getId(),
                kbId);
        String operation = UUID.randomUUID().toString();
        var command =
                new PresalesDtos.Command(
                        repair.path("version").asInt(),
                        operation,
                        "UPDATE_PROJECT",
                        json.createObjectNode().put("agentId", replacement.getId().toString()));
        assertEquals(
                409,
                assertThrows(
                                SemanticApiException.class,
                                () ->
                                        service.command(
                                                workspace, project.path("id").asText(), command))
                        .status());
        assertEquals(
                bound.path("version").asInt(),
                service.repairContext(workspace, project.path("id").asText())
                        .path("version")
                        .asInt());
        replacement.setEnabled(true);
        agentMapper.updateById(replacement);
        var restored = service.command(workspace, project.path("id").asText(), command);
        assertFalse(restored.path("sourceAccessRestricted").asBoolean());
        assertEquals(replacement.getId().toString(), restored.path("agentId").asText());
        assertEquals(1, restored.path("materials").size());
        assertEquals(restored, service.get(workspace, project.path("id").asText()));
        assertEquals(restored, service.command(workspace, project.path("id").asText(), command));
        String receipt =
                jdbc.queryForObject(
                        "SELECT response_json FROM mate_presales_operation WHERE operation_id=?",
                        String.class,
                        operation);
        jdbc.update(
                "UPDATE mate_agent_wiki_kb SET enabled=FALSE WHERE agent_id=? AND kb_id=?",
                replacement.getId(),
                kbId);
        var replay = service.command(workspace, project.path("id").asText(), command);
        assertTrue(replay.path("sourceAccessRestricted").asBoolean());
        assertTrue(replay.path("materials").isEmpty());
        assertTrue(replay.path("tasks").isEmpty());
        assertEquals(
                receipt,
                jdbc.queryForObject(
                        "SELECT response_json FROM mate_presales_operation WHERE operation_id=?",
                        String.class,
                        operation));
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

    private void assertListingVersion(ObjectNode current) {
        var page = service.list(workspace, null, null, null, null, 1, 100);
        var listed =
                page.items().stream()
                        .filter(p -> p.path("id").asText().equals(current.path("id").asText()))
                        .findFirst()
                        .orElseThrow();
        assertEquals(current.path("version"), listed.path("version"));
        assertEquals(current.path("goal"), listed.path("goal"));
        assertFalse(listed.has("tasks"));
        assertFalse(listed.has("requirements"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUCCEEDED", "FAILED", "CANCELLED", "DRAFT"})
    void lateFailureCannotOverwriteTerminalTask(String status) throws Exception {
        var live = task.deepCopy().put("status", status);
        live.put("finishedAt", "already-finished");
        live.set("result", json.createObjectNode().put("original", true));
        replaceDurableTask(live);
        assertFailureRejectedWithoutWrite("FAILED");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "runId", "operationId", "agentId", "skill", "modelConfigId", "configDigest",
                "skillName", "skillDigest", "presentationDigest", "conversationId",
                        "contextSnapshot", "extension"
            })
    void failureCannotOverwriteChangedRunIdentity(String field) throws Exception {
        var live = task.deepCopy();
        if ("contextSnapshot".equals(field))
            live.withObject("contextSnapshot").put("projectVersion", 999);
        else live.put(field, "replacement");
        replaceDurableTask(live);
        assertFailureRejectedWithoutWrite("FAILED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"RUNNING", "DRAFT"})
    void employeeBoundaryRejectsNonTerminalCandidate(String status) {
        assertFailureRejectedWithoutWrite(status);
    }

    @ParameterizedTest
    @ValueSource(strings = {"error", "rejectedOutput"})
    void successIdentityStillRejectsAddedFailureDiagnostics(String field) {
        var candidate = task.deepCopy().put("status", "SUCCEEDED");
        candidate.set("result", json.createObjectNode().put("schemaVersion", 1));
        candidate.put(field, "injected");
        var before =
                jdbc.queryForObject(
                        "SELECT body_json FROM mate_presales_project WHERE id=?",
                        String.class,
                        project.path("id").asText());
        int receipts = receiptCount(), revisions = revisionCount();
        var error =
                assertThrows(
                        SemanticApiException.class,
                        () ->
                                service.saveEmployeeTask(
                                        workspace,
                                        project.path("id").asText(),
                                        new PresalesDtos.Command(
                                                project.path("version").asInt(),
                                                UUID.randomUUID().toString(),
                                                "SAVE_AI_TASK",
                                                candidate)));
        assertEquals(409, error.status());
        assertEquals("TASK_SCOPE_CHANGED", error.code());
        assertEquals(
                before,
                jdbc.queryForObject(
                        "SELECT body_json FROM mate_presales_project WHERE id=?",
                        String.class,
                        project.path("id").asText()));
        assertEquals(receipts, receiptCount());
        assertEquals(revisions, revisionCount());
        noExternalCall();
    }

    @Test
    void matchingRunningFailureRetainsDiagnosticsAfterUnrelatedProjectEdit() {
        project =
                service.command(
                        workspace,
                        project.path("id").asText(),
                        new PresalesDtos.Command(
                                project.path("version").asInt(),
                                UUID.randomUUID().toString(),
                                "UPDATE_PROJECT",
                                json.createObjectNode().put("goal", "New user goal")));
        int receipts = receiptCount(), revisions = revisionCount();
        var candidate =
                task.deepCopy()
                        .put("status", "FAILED")
                        .put("error", "PROJECT_CHANGED_DURING_GENERATION")
                        .put("finishedAt", "finished");
        candidate.set("rejectedOutput", json.createObjectNode().put("reason", "diagnostic"));
        var saved =
                service.saveEmployeeTask(
                        workspace,
                        project.path("id").asText(),
                        new PresalesDtos.Command(
                                project.path("version").asInt(),
                                UUID.randomUUID().toString(),
                                "SAVE_AI_TASK",
                                candidate));
        assertEquals(project.path("version").asInt() + 1, saved.path("version").asInt());
        assertEquals("New user goal", saved.path("goal").asText());
        assertEquals("FAILED", saved.path("tasks").get(0).path("status").asText());
        assertEquals(
                candidate.path("rejectedOutput"),
                saved.path("tasks").get(0).path("rejectedOutput"));
        assertTrue(saved.path("tasks").get(0).path("result").isMissingNode());
        assertEquals(receipts + 1, receiptCount());
        assertEquals(revisions + 1, revisionCount());
        assertListingVersion(saved);
        noExternalCall();
    }

    private void replaceDurableTask(ObjectNode live) throws Exception {
        project = project.deepCopy();
        project.withArray("tasks").set(0, live);
        project.put("version", project.path("version").asInt() + 1);
        assertEquals(
                1,
                jdbc.update(
                        "UPDATE mate_presales_project SET body_json=?,version=? WHERE id=? AND workspace_id=?",
                        json.writeValueAsString(project),
                        project.path("version").asInt(),
                        project.path("id").asText(),
                        workspace));
    }

    private void assertFailureRejectedWithoutWrite(String candidateStatus) {
        String before =
                jdbc.queryForObject(
                        "SELECT body_json FROM mate_presales_project WHERE id=?",
                        String.class,
                        project.path("id").asText());
        int receipts = receiptCount(), revisions = revisionCount();
        var candidate =
                task.deepCopy()
                        .put("status", candidateStatus)
                        .put("error", "LATE_FAILURE")
                        .put("finishedAt", "late");
        var error =
                assertThrows(
                        SemanticApiException.class,
                        () ->
                                service.saveEmployeeTask(
                                        workspace,
                                        project.path("id").asText(),
                                        new PresalesDtos.Command(
                                                project.path("version").asInt(),
                                                UUID.randomUUID().toString(),
                                                "SAVE_AI_TASK",
                                                candidate)));
        assertEquals(409, error.status());
        assertEquals("TASK_SCOPE_CHANGED", error.code());
        assertEquals(
                before,
                jdbc.queryForObject(
                        "SELECT body_json FROM mate_presales_project WHERE id=?",
                        String.class,
                        project.path("id").asText()));
        assertEquals(receipts, receiptCount());
        assertEquals(revisions, revisionCount());
        noExternalCall();
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
        assertListingVersion(accepted);
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

package vip.mate.presales;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import vip.mate.presales.repository.PresalesArtifactRepository;
import vip.mate.presales.repository.PresalesProjectRepository;
import vip.mate.presales.repository.PresalesRenderTaskRepository;
import vip.mate.presales.repository.PresalesRenderTaskRepository.RenderTask;
import vip.mate.semantic.config.SemanticProperties;
import vip.mate.semantic.graph.GraphApplicationService;
import vip.mate.semantic.statement.StatementApplicationService;
import vip.mate.semantic.web.SemanticApiException;
import vip.mate.wiki.service.WikiKnowledgeBaseService;
import vip.mate.workspace.core.service.ProjectAuthorityFence;

/** Real H2 state, authority row locks and Spring transactions around the CREATE_RELEASE route. */
class PresalesRenderLifecycleTest {
    private static final String WORKSPACE = "101";
    private static final String ACTOR = "201";
    private static final String PROJECT = "project";
    private static final String OPERATION = "render-operation";
    private ObjectMapper json;
    private FailingJdbcTemplate jdbc;
    private DataSource dataSource;
    private PresalesRenderTaskRepository tasks;

    private boolean external;
    private boolean ownsTables;

    @BeforeEach
    void database() throws Exception {
        String url = System.getenv("MATECLAW_RENDER_JDBC_URL");
        external = url != null && !url.isBlank();
        DataSource source;
        if (external) {
            assertTrue(
                    url.matches(
                            "jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/mateclaw_aq_acceptance_[a-z0-9]+(?:\\?.*)?"));
            source =
                    new org.springframework.jdbc.datasource.DriverManagerDataSource(
                            url,
                            System.getenv("MATECLAW_ACCEPTANCE_JDBC_USER"),
                            System.getenv("MATECLAW_ACCEPTANCE_JDBC_PASSWORD"));
            try (var connection = source.getConnection();
                    var tables =
                            connection
                                    .getMetaData()
                                    .getTables(
                                            connection.getCatalog(),
                                            null,
                                            "%",
                                            new String[] {"TABLE", "VIEW"})) {
                assertFalse(tables.next(), "Disposable render acceptance database must be empty");
            }
        } else {
            var h2 = new org.h2.jdbcx.JdbcDataSource();
            h2.setURL(
                    "jdbc:h2:mem:presales_render_lifecycle_"
                            + UUID.randomUUID()
                            + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
            source = h2;
        }
        dataSource = source;
        ownsTables = true;
        String migration = "db/migration/" + (external ? "mysql" : "h2") + "/";
        new ResourceDatabasePopulator(
                        new ClassPathResource(migration + "V211__presales_projects.sql"),
                        new ClassPathResource(migration + "V217__presales_listing_projection.sql"),
                        new ClassPathResource(
                                migration + "V219__presales_versioned_request_hash.sql"),
                        new ClassPathResource(
                                migration + "V220__presales_project_revision_capacity.sql"),
                        new ClassPathResource(migration + "V221__presales_render_tasks.sql"),
                        new ClassPathResource(migration + "V223__presales_object_storage.sql"))
                .execute(source);
        jdbc = new FailingJdbcTemplate(source);
        jdbc.execute("CREATE TABLE mate_user(id BIGINT PRIMARY KEY)");
        jdbc.execute("CREATE TABLE mate_workspace(id BIGINT PRIMARY KEY)");
        jdbc.execute(
                "CREATE TABLE mate_workspace_member(id BIGINT PRIMARY KEY,workspace_id BIGINT,user_id BIGINT,deleted INTEGER)");
        jdbc.update("INSERT INTO mate_user(id) VALUES(?)", Long.parseLong(ACTOR));
        jdbc.update("INSERT INTO mate_workspace(id) VALUES(?)", Long.parseLong(WORKSPACE));
        jdbc.update(
                "INSERT INTO mate_workspace_member VALUES(1,?,?,0)",
                Long.parseLong(WORKSPACE),
                Long.parseLong(ACTOR));
        json = new ObjectMapper();
        tasks = new PresalesRenderTaskRepository(jdbc);
        insertProject(PROJECT);
    }

    @AfterEach
    void close() throws java.sql.SQLException {
        if (!ownsTables) return;
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement()) {
            if (!external) statement.execute("SHUTDOWN");
            else
                for (String table :
                        List.of(
                                "mate_presales_object_revision",
                                "mate_presales_object",
                                "mate_presales_render_task",
                                "mate_presales_artifact",
                                "mate_presales_revision",
                                "mate_presales_operation",
                                "mate_presales_project",
                                "mate_workspace_member",
                                "mate_workspace",
                                "mate_user")) statement.execute("DROP TABLE IF EXISTS " + table);
        }
    }

    @Test
    void commitsInputBeforeRenderingAndAcceptsFilesProjectRevisionAndReceiptTogether()
            throws Exception {
        var renderer =
                new RecordingRenderer(
                        document -> {
                            assertOutsideTransaction();
                            assertEquals("RUNNING", task().status());
                            assertEquals(1, task().attemptNo());
                            assertEquals(1, storedProject().path("version").asInt());
                            assertEquals(0, count("mate_presales_artifact"));
                            assertEquals(0, count("mate_presales_revision"));
                            assertEquals(0, count("mate_presales_operation"));
                            return bytes("accepted");
                        });
        var result = service(renderer).command(WORKSPACE, PROJECT, release(OPERATION));
        assertEquals(1, renderer.calls.get());
        assertEquals(2, result.path("version").asInt());
        assertEquals(1, result.path("releases").size());
        assertEquals("PENDING", result.path("releases").get(0).path("status").asText());
        assertEquals(result, storedProject());
        assertEquals(result.toString(), receipt());
        assertEquals("SUCCEEDED", task().status());
        assertEquals(1, count("mate_presales_render_task"));
        assertEquals(1, count("mate_presales_revision"));
        assertEquals(1, count("mate_presales_operation"));
        assertStoredBytes(bytes("accepted"));
        assertEquals(
                result,
                json.readTree(
                        jdbc.queryForObject(
                                "SELECT body_json FROM mate_presales_revision WHERE project_id=? AND version=2",
                                String.class,
                                PROJECT)));
    }

    @Test
    void lostResponseReplaysExactReceiptAndStoredBytesAcrossServiceInstances() {
        var renderer = new RecordingRenderer(document -> bytes("original Ω\n"));
        // The caller discards its first response, just as a disconnected HTTP caller would.
        service(renderer).command(WORKSPACE, PROJECT, release(OPERATION));
        String savedResponse = receipt();
        var savedFiles = artifactRows();
        var savedTask = task();
        var replacement =
                new RecordingRenderer(
                        document -> {
                            fail("A committed operation must replay without rendering again");
                            return Map.of();
                        });
        var replay = service(replacement).command(WORKSPACE, PROJECT, release(OPERATION));
        assertEquals(savedResponse, replay.toString());
        assertEquals(savedFiles, artifactRows());
        assertEquals(savedTask, task());
        assertEquals(1, renderer.calls.get());
        assertEquals(0, replacement.calls.get());
        assertEquals(1, count("mate_presales_revision"));
        assertEquals(1, count("mate_presales_operation"));
        assertStoredBytes(bytes("original Ω\n"));
    }

    @Test
    void failedRendererRetriesFrozenInputInNewServiceWithNewAttempt() throws Exception {
        String before = projectBody();
        var expectedFailure = new IllegalStateException("synthetic renderer failure");
        var failedRenderer =
                new RecordingRenderer(
                        document -> {
                            assertOutsideTransaction();
                            assertEquals("RUNNING", task().status());
                            throw expectedFailure;
                        });
        assertSame(
                expectedFailure,
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                service(failedRenderer)
                                        .command(WORKSPACE, PROJECT, release(OPERATION))));
        var failed = task();
        assertEquals("FAILED", failed.status());
        assertEquals(1, failed.attemptNo());
        assertUnaccepted(before);
        var retryRenderer =
                new RecordingRenderer(
                        document -> {
                            assertOutsideTransaction();
                            assertEquals(failed.inputJson(), task().inputJson());
                            assertEquals(failed.taskId(), task().taskId());
                            assertNotEquals(failed.attemptId(), task().attemptId());
                            assertEquals(2, task().attemptNo());
                            assertEquals("RUNNING", task().status());
                            assertEquals(failedRenderer.lastDocument, document);
                            return bytes("retry");
                        });
        var response = service(retryRenderer).command(WORKSPACE, PROJECT, release(OPERATION));
        assertEquals(2, response.path("version").asInt());
        assertEquals("SUCCEEDED", task().status());
        assertEquals(2, task().attemptNo());
        assertEquals(failed.inputJson(), task().inputJson());
        assertEquals(failed.createdAt(), task().createdAt());
        assertEquals(1, count("mate_presales_render_task"));
        assertStoredBytes(bytes("retry"));
    }

    @Test
    void activeReservationRejectsSameKeyChangesAcrossProjectsAndCommandKinds() throws Exception {
        insertProject("other-project");
        var blocked = new BlockingRenderer("first");
        var firstService = service(blocked);
        var contender =
                service(
                        new RecordingRenderer(
                                document -> {
                                    fail("Rejected operation must never render");
                                    return Map.of();
                                }));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first =
                    executor.submit(
                            () -> firstService.command(WORKSPACE, PROJECT, release(OPERATION)));
            try {
                assertTrue(blocked.entered.await(10, TimeUnit.SECONDS));
                var reserved = task();
                assertCode(
                        "RENDER_IN_PROGRESS",
                        () -> contender.command(WORKSPACE, PROJECT, release(OPERATION)));
                var changed = release(OPERATION);
                changed.payload().put("purpose", "different");
                assertCode(
                        "OPERATION_CONFLICT", () -> contender.command(WORKSPACE, PROJECT, changed));
                assertCode(
                        "OPERATION_CONFLICT",
                        () -> contender.command(WORKSPACE, "other-project", release(OPERATION)));
                assertCode(
                        "OPERATION_CONFLICT",
                        () -> contender.command(WORKSPACE, PROJECT, update(OPERATION)));
                assertCode(
                        "OPERATION_CONFLICT",
                        () -> contender.command(WORKSPACE, "other-project", update(OPERATION)));
                assertCode(
                        "OPERATION_CONFLICT", () -> contender.create(WORKSPACE, create(OPERATION)));
                assertEquals(reserved, task());
                assertEquals(2, count("mate_presales_project"));
                assertEquals(0, count("mate_presales_operation"));
                assertEquals(0, count("mate_presales_revision"));
                assertEquals(0, count("mate_presales_artifact"));
            } finally {
                blocked.release.countDown();
            }
            assertEquals(2, first.get(10, TimeUnit.SECONDS).path("version").asInt());
        }
    }

    @Test
    void simultaneousDifferentProjectReservationsHaveOnlyOneRendererAndOneReceipt()
            throws Exception {
        insertProject("other-project");
        var renderer = new BlockingRenderer("winner");
        var firstService = service(renderer);
        var secondService = service(renderer);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var rejected = new CountDownLatch(1);
        var rejection = new AtomicReference<SemanticApiException>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first =
                    executor.submit(
                            () ->
                                    racingRelease(
                                            firstService,
                                            PROJECT,
                                            ready,
                                            start,
                                            rejected,
                                            rejection));
            var second =
                    executor.submit(
                            () ->
                                    racingRelease(
                                            secondService,
                                            "other-project",
                                            ready,
                                            start,
                                            rejected,
                                            rejection));
            try {
                assertTrue(ready.await(10, TimeUnit.SECONDS));
                start.countDown();
                assertTrue(renderer.entered.await(10, TimeUnit.SECONDS));
                assertTrue(
                        rejected.await(10, TimeUnit.SECONDS),
                        "The competing namespace reservation did not finish");
                assertEquals("OPERATION_CONFLICT", rejection.get().code());
                assertEquals(1, count("mate_presales_render_task"));
                assertEquals(0, count("mate_presales_operation"));
                assertEquals(0, count("mate_presales_artifact"));
            } finally {
                start.countDown();
                renderer.release.countDown();
            }
            var firstResult = first.get(10, TimeUnit.SECONDS);
            var secondResult = second.get(10, TimeUnit.SECONDS);
            assertTrue((firstResult == null) != (secondResult == null));
            var winner = firstResult != null ? firstResult : secondResult;
            assertEquals(winner.path("id").asText(), task().projectId());
            assertEquals("SUCCEEDED", task().status());
            assertEquals(1, task().attemptNo());
            assertEquals(1, count("mate_presales_operation"));
            assertEquals(1, count("mate_presales_revision"));
            assertEquals(2, count("mate_presales_artifact"));
        }
    }

    private ObjectNode racingRelease(
            PresalesService service,
            String projectId,
            CountDownLatch ready,
            CountDownLatch start,
            CountDownLatch rejected,
            AtomicReference<SemanticApiException> rejection)
            throws Exception {
        ready.countDown();
        assertTrue(start.await(10, TimeUnit.SECONDS));
        try {
            return service.command(WORKSPACE, projectId, release(OPERATION));
        } catch (SemanticApiException failure) {
            assertTrue(rejection.compareAndSet(null, failure), "Both reservations failed");
            rejected.countDown();
            return null;
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void existingOrdinaryOrCreateReceiptCannotBeReusedForRendering(boolean createFirst) {
        insertProject("other-project");
        var renderer =
                new RecordingRenderer(
                        document -> {
                            fail("A conflicting receipt must be rejected before rendering");
                            return Map.of();
                        });
        var service = service(renderer);
        if (createFirst) service.create(WORKSPACE, create(OPERATION));
        else service.command(WORKSPACE, "other-project", update(OPERATION));
        String oldReceipt = receipt();
        assertCode(
                "OPERATION_CONFLICT",
                () -> service.command(WORKSPACE, PROJECT, release(OPERATION)));
        assertEquals(oldReceipt, receipt());
        assertEquals(0, count("mate_presales_render_task"));
        assertEquals(0, renderer.calls.get());
        assertEquals(0, count("mate_presales_artifact"));
    }

    @Test
    void expiredAttemptCannotOverwriteOrFailRunningReplacement() throws Exception {
        String before = projectBody();
        var oldRenderer = new BlockingRenderer("old-result");
        var newRenderer = new BlockingRenderer("new-result");
        var oldService = service(oldRenderer);
        var newService = service(newRenderer);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var oldRequest =
                    executor.submit(
                            () -> oldService.command(WORKSPACE, PROJECT, release(OPERATION)));
            try {
                assertTrue(oldRenderer.entered.await(10, TimeUnit.SECONDS));
                var oldTask = task();
                jdbc.update(
                        "UPDATE mate_presales_render_task SET lease_until=? WHERE task_id=?",
                        LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1),
                        oldTask.taskId());
                var newRequest =
                        executor.submit(
                                () -> newService.command(WORKSPACE, PROJECT, release(OPERATION)));
                try {
                    assertTrue(newRenderer.entered.await(10, TimeUnit.SECONDS));
                    var replacement = task();
                    assertEquals(oldTask.taskId(), replacement.taskId());
                    assertEquals(oldTask.inputJson(), replacement.inputJson());
                    assertNotEquals(oldTask.attemptId(), replacement.attemptId());
                    assertEquals(2, replacement.attemptNo());
                    oldRenderer.release.countDown();
                    var failed =
                            assertThrows(
                                    java.util.concurrent.ExecutionException.class,
                                    () -> oldRequest.get(10, TimeUnit.SECONDS));
                    assertEquals(
                            "RENDER_ATTEMPT_EXPIRED",
                            assertInstanceOf(SemanticApiException.class, failed.getCause()).code());
                    assertEquals(
                            replacement,
                            task(),
                            "Late failure cleanup must not change the active attempt");
                    assertEquals("RUNNING", task().status());
                    assertUnaccepted(before);
                } finally {
                    newRenderer.release.countDown();
                }
                assertEquals(2, newRequest.get(10, TimeUnit.SECONDS).path("version").asInt());
                assertEquals("SUCCEEDED", task().status());
                assertEquals(2, task().attemptNo());
                assertEquals(1, count("mate_presales_revision"));
                assertEquals(1, count("mate_presales_operation"));
                assertStoredBytes(bytes("new-result"));
            } finally {
                oldRenderer.release.countDown();
                newRenderer.release.countDown();
            }
        }
    }

    @Test
    void ordinaryCommandStillRollsBackWithItsCallingTransaction() {
        String before = projectBody();
        var renderer =
                new RecordingRenderer(
                        document -> {
                            fail("An ordinary command must not render");
                            return Map.of();
                        });
        var service = service(renderer);
        var caller = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        caller.executeWithoutResult(
                status -> {
                    var updated = service.command(WORKSPACE, PROJECT, update(OPERATION));
                    assertEquals(2, updated.path("version").asInt());
                    assertEquals("changed", storedProject().path("name").asText());
                    assertEquals(1, count("mate_presales_revision"));
                    assertEquals(1, count("mate_presales_operation"));
                    status.setRollbackOnly();
                });
        assertUnaccepted(before);
        assertEquals(0, count("mate_presales_render_task"));
        assertEquals(0, renderer.calls.get());
    }

    @ParameterizedTest
    @EnumSource(WritePoint.class)
    void everyAcceptanceWriteRollsBackTogetherAndLeavesDurableFailure(WritePoint point)
            throws Exception {
        String before = projectBody();
        var renderer =
                new RecordingRenderer(
                        document -> {
                            assertEquals("RUNNING", task().status());
                            jdbc.failOnceAfter(point);
                            return bytes("must-rollback");
                        });
        var failure =
                assertThrows(
                        DataAccessResourceFailureException.class,
                        () -> service(renderer).command(WORKSPACE, PROJECT, release(OPERATION)));
        assertEquals("injected after " + point, failure.getMessage());
        assertTrue(jdbc.injected.get(), "The intended write boundary must actually execute");
        assertEquals(1, renderer.calls.get());
        assertUnaccepted(before);
        assertEquals("FAILED", task().status());
        assertEquals(1, task().attemptNo());
        assertEquals(1, count("mate_presales_render_task"));
        assertEquals(project(PROJECT), json.readTree(task().inputJson()).path("project"));
    }

    private void assertUnaccepted(String before) {
        assertEquals(before, projectBody());
        assertEquals(0, count("mate_presales_artifact"));
        assertEquals(0, count("mate_presales_revision"));
        assertEquals(0, count("mate_presales_operation"));
    }

    private void assertOutsideTransaction() {
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertNull(TransactionSynchronizationManager.getResource(dataSource));
    }

    private RenderTask task() {
        return tasks.findOperation(WORKSPACE, ACTOR, OPERATION, false).orElseThrow();
    }

    private String projectBody() {
        return jdbc.queryForObject(
                "SELECT body_json FROM mate_presales_project WHERE id=?", String.class, PROJECT);
    }

    private ObjectNode storedProject() {
        try {
            return (ObjectNode) json.readTree(projectBody());
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private String receipt() {
        return jdbc.queryForObject(
                "SELECT response_json FROM mate_presales_operation WHERE workspace_id=? AND actor_id=? AND operation_id=?",
                String.class,
                WORKSPACE,
                ACTOR,
                OPERATION);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private List<Map<String, Object>> artifactRows() {
        return jdbc.queryForList(
                "SELECT project_id,release_id,filename,digest,content_base64 FROM mate_presales_artifact ORDER BY filename");
    }

    private void assertStoredBytes(Map<String, byte[]> expected) {
        assertEquals(expected.size(), count("mate_presales_artifact"));
        for (var entry : expected.entrySet()) {
            var row =
                    jdbc.queryForMap(
                            "SELECT digest,content_base64 FROM mate_presales_artifact WHERE project_id=? AND filename=?",
                            PROJECT,
                            entry.getKey());
            assertArrayEquals(
                    entry.getValue(),
                    Base64.getDecoder().decode((String) row.get("content_base64")));
            assertEquals(PresalesArtifactRenderer.digest(entry.getValue()), row.get("digest"));
        }
    }

    private static void assertCode(
            String expected, org.junit.jupiter.api.function.Executable action) {
        assertEquals(expected, assertThrows(SemanticApiException.class, action).code());
    }

    private PresalesDtos.Command release(String operation) {
        return new PresalesDtos.Command(
                1L,
                operation,
                "CREATE_RELEASE",
                json.createObjectNode().put("solutionId", "solution").put("purpose", "candidate"));
    }

    private PresalesDtos.Command update(String operation) {
        return new PresalesDtos.Command(
                1L, operation, "UPDATE_PROJECT", json.createObjectNode().put("name", "changed"));
    }

    private PresalesDtos.Create create(String operation) {
        return new PresalesDtos.Create(
                "new project", "customer", ACTOR, null, "industry", "goal", 0L, operation);
    }

    @SuppressWarnings("unchecked")
    private PresalesService service(PresalesArtifactRenderer renderer) {
        var access = mock(PresalesAccess.class);
        when(access.require(WORKSPACE, "member")).thenReturn(ACTOR);
        when(access.owner(WORKSPACE, ACTOR, ACTOR)).thenReturn(ACTOR);
        var semantic = new SemanticProperties();
        semantic.setEnabled(true);
        ObjectProvider<GraphApplicationService> graphs = mock(ObjectProvider.class);
        when(graphs.getIfAvailable()).thenReturn(mock(GraphApplicationService.class));
        ObjectProvider<StatementApplicationService> statements = mock(ObjectProvider.class);
        when(statements.getIfAvailable()).thenReturn(mock(StatementApplicationService.class));
        var manager = new DataSourceTransactionManager(dataSource);
        var target =
                new PresalesService(
                        new PresalesArtifactRepository(jdbc),
                        new PresalesProjectRepository(jdbc),
                        json,
                        access,
                        mock(WikiKnowledgeBaseService.class),
                        graphs,
                        statements,
                        semantic,
                        mock(ObjectProvider.class),
                        renderer,
                        mock(ObjectProvider.class),
                        new ProjectAuthorityFence(jdbc, mock(SqlSessionTemplate.class)),
                        mock(PresalesSourceAuthorization.class),
                        tasks,
                        manager);
        var proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(
                new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        return (PresalesService) proxy.getProxy();
    }

    private void insertProject(String id) {
        jdbc.update(
                "INSERT INTO mate_presales_project(id,workspace_id,version,name,status,body_json) VALUES(?,?,?,?,?,?)",
                id,
                WORKSPACE,
                1L,
                "case",
                "ACTIVE",
                project(id).toString());
    }

    private ObjectNode project(String id) {
        ObjectNode p = json.createObjectNode();
        p.put("id", id)
                .put("workspaceId", WORKSPACE)
                .put("name", "case")
                .put("agentId", "")
                .put("version", 1)
                .put("status", "ACTIVE");
        for (String key :
                new String[] {
                    "materials", "requirements", "clarifications", "baselines", "fitGaps", "cases",
                    "solutions", "reviews", "reviewDrafts", "releases", "tasks", "contextCards"
                }) p.putArray(key);
        p.withArray("baselines").addObject().put("id", "baseline").putArray("references");
        var solution =
                p.withArray("solutions")
                        .addObject()
                        .put("id", "solution")
                        .put("title", "Solution")
                        .put("authorId", "writer")
                        .put("baselineId", "baseline")
                        .put("provisional", false);
        solution.putObject("coverage").putArray("responses");
        solution.putArray("sections").addObject().put("title", "Scope").put("text", "Frozen Ω");
        solution.putArray("fitGapRefs");
        p.withArray("reviews")
                .addObject()
                .put("id", "review")
                .put("kind", "HUMAN_REVIEW")
                .put("authority", "VERIFIED")
                .put("authorId", "reviewer")
                .put("solutionId", "solution")
                .putArray("issues");
        return p;
    }

    private static Map<String, byte[]> bytes(String value) {
        var files = new LinkedHashMap<String, byte[]>();
        files.put("solution.md", value.getBytes(StandardCharsets.UTF_8));
        files.put("solution.docx", (value + "\u0000\u00ff").getBytes(StandardCharsets.UTF_8));
        return files;
    }

    private static class RecordingRenderer extends PresalesArtifactRenderer {
        private final AtomicInteger calls = new AtomicInteger();
        private final Function<Document, Map<String, byte[]>> render;
        private volatile Document lastDocument;

        private RecordingRenderer(Function<Document, Map<String, byte[]>> render) {
            this.render = render;
        }

        @Override
        public Map<String, byte[]> render(Document document) {
            calls.incrementAndGet();
            lastDocument = document;
            return render.apply(document);
        }
    }

    private final class BlockingRenderer extends PresalesArtifactRenderer {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final String output;

        private BlockingRenderer(String output) {
            this.output = output;
        }

        @Override
        public Map<String, byte[]> render(Document document) {
            assertOutsideTransaction();
            entered.countDown();
            try {
                assertTrue(release.await(20, TimeUnit.SECONDS), "Renderer was not released");
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new AssertionError(failure);
            }
            return bytes(output);
        }
    }

    enum WritePoint {
        ARTIFACT("INSERT INTO mate_presales_artifact"),
        PROJECT("UPDATE mate_presales_project"),
        REVISION("INSERT INTO mate_presales_revision"),
        RECEIPT("INSERT INTO mate_presales_operation"),
        TASK_SUCCESS("UPDATE mate_presales_render_task SET status=");

        private final String sqlPrefix;

        WritePoint(String sqlPrefix) {
            this.sqlPrefix = sqlPrefix;
        }
    }

    private static final class FailingJdbcTemplate extends JdbcTemplate {
        private final AtomicBoolean injected = new AtomicBoolean();
        private volatile WritePoint failurePoint;

        private FailingJdbcTemplate(DataSource source) {
            super(source);
        }

        private void failOnceAfter(WritePoint point) {
            failurePoint = point;
            injected.set(false);
        }

        @Override
        public int update(String sql, Object... args) {
            int changed = super.update(sql, args);
            WritePoint point = failurePoint;
            if (point != null
                    && sql.startsWith(point.sqlPrefix)
                    && (point != WritePoint.TASK_SUCCESS || "SUCCEEDED".equals(args[0]))
                    && injected.compareAndSet(false, true)) {
                throw new DataAccessResourceFailureException("injected after " + point);
            }
            return changed;
        }
    }
}

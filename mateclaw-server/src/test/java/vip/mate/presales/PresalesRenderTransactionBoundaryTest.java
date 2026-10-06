package vip.mate.presales;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import vip.mate.presales.repository.PresalesArtifactRepository;
import vip.mate.presales.repository.PresalesProjectRepository;
import vip.mate.semantic.config.SemanticProperties;
import vip.mate.semantic.graph.GraphApplicationService;
import vip.mate.semantic.statement.StatementApplicationService;
import vip.mate.semantic.web.SemanticApiException;
import vip.mate.wiki.service.WikiKnowledgeBaseService;
import vip.mate.workspace.core.service.ProjectAuthorityFence;

/**
 * RED characterization for AQ05: rendering must not hold the command's project lock or
 * transaction-bound connection. This deliberately exercises the real CREATE_RELEASE service route
 * and an independent H2 connection, rather than a mocked renderer-only unit.
 */
class PresalesRenderTransactionBoundaryTest {
    private static final String WORKSPACE = "1";
    private static final String PROJECT = "project";

    private ObjectMapper json;
    private JdbcTemplate jdbc;
    private DataSource dataSource;
    private TransactionTemplate transaction;
    private PresalesAccess access;
    private PresalesSourceAuthorization sourceAuthorization;
    private ProjectAuthorityFence authorityFence;

    @BeforeEach
    void database() {
        var source = new org.h2.jdbcx.JdbcDataSource();
        source.setURL(
                "jdbc:h2:mem:presales_render_boundary_"
                        + UUID.randomUUID()
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        dataSource = source;
        new ResourceDatabasePopulator(
                        new ClassPathResource("db/migration/h2/V211__presales_projects.sql"),
                        new ClassPathResource(
                                "db/migration/h2/V217__presales_listing_projection.sql"),
                        new ClassPathResource(
                                "db/migration/h2/V219__presales_versioned_request_hash.sql"),
                        new ClassPathResource(
                                "db/migration/h2/V220__presales_project_revision_capacity.sql"),
                        new ClassPathResource("db/migration/h2/V221__presales_render_tasks.sql"))
                .execute(source);
        jdbc = new JdbcTemplate(source);
        json = new ObjectMapper();
        jdbc.execute("CREATE TABLE mate_user(id BIGINT PRIMARY KEY)");
        jdbc.execute("CREATE TABLE mate_workspace(id BIGINT PRIMARY KEY)");
        jdbc.execute(
                "CREATE TABLE mate_workspace_member(id BIGINT PRIMARY KEY,workspace_id BIGINT,user_id BIGINT,deleted INT)");
        jdbc.execute(
                "CREATE TABLE mate_semantic_graph(id VARCHAR(64) PRIMARY KEY,workspace_id BIGINT)");
        jdbc.execute(
                "CREATE TABLE mate_wiki_knowledge_base(id BIGINT PRIMARY KEY,workspace_id BIGINT,deleted INT)");
        jdbc.execute(
                "CREATE TABLE mate_wiki_raw_material(id BIGINT PRIMARY KEY,kb_id BIGINT,deleted INT,extracted_text TEXT,original_content TEXT)");
        jdbc.update("INSERT INTO mate_user VALUES(9)");
        jdbc.update("INSERT INTO mate_workspace VALUES(1)");
        jdbc.update("INSERT INTO mate_workspace_member VALUES(1,1,9,0)");
        jdbc.update("INSERT INTO mate_wiki_knowledge_base VALUES(20,1,0)");
        jdbc.update("INSERT INTO mate_wiki_raw_material VALUES(30,20,0,'original source','')");
        access = mock(PresalesAccess.class);
        when(access.require(WORKSPACE, "member")).thenReturn("9");
        authorityFence =
                new ProjectAuthorityFence(jdbc, mock(org.mybatis.spring.SqlSessionTemplate.class));
        var wiki = mock(WikiKnowledgeBaseService.class);
        var kb = new vip.mate.wiki.model.WikiKnowledgeBaseEntity();
        kb.setId(20L);
        kb.setWorkspaceId(1L);
        kb.setDeleted(0);
        when(wiki.getById(20L)).thenReturn(kb);
        var governance = mock(vip.mate.semantic.source.SourceGovernanceReadService.class);
        when(governance.availableEvidenceSource(WORKSPACE, "graph", "20", "evidence"))
                .thenReturn(java.util.Optional.of("30"));
        jdbc.update("INSERT INTO mate_semantic_graph VALUES('graph',1)");
        sourceAuthorization =
                new PresalesSourceAuthorization(
                        json,
                        wiki,
                        new vip.mate.workspace.core.service.ProjectSourceAccess(jdbc),
                        new vip.mate.wiki.service.WikiSourceReadService(
                                new vip.mate.wiki.repository.WikiSourceReadRepository(jdbc)),
                        governance);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        jdbc.update(
                "INSERT INTO mate_presales_project(id,workspace_id,version,name,status,body_json) VALUES(?,?,?,?,?,?)",
                PROJECT,
                WORKSPACE,
                1L,
                "case",
                "ACTIVE",
                project().toString());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void createReleaseRendersWithoutOuterTransactionOrProjectLock(boolean outerTransaction)
            throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var renderer = new BlockingRenderer(entered, release, dataSource);
        var service = service(renderer);
        var command =
                new PresalesDtos.Command(
                        1L,
                        "render-operation",
                        "CREATE_RELEASE",
                        json.createObjectNode()
                                .put("solutionId", "solution")
                                .put("purpose", "candidate"));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<ObjectNode> rendering =
                    executor.submit(
                            () ->
                                    outerTransaction
                                            ? transaction.execute(
                                                    status ->
                                                            service.command(
                                                                    WORKSPACE, PROJECT, command))
                                            : service.command(WORKSPACE, PROJECT, command));
            Future<ObjectNode> editing = null;
            boolean editCompletedDuringRender = false;
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS), "renderer was not reached");
                editing =
                        executor.submit(
                                () ->
                                        service.command(
                                                WORKSPACE,
                                                PROJECT,
                                                new PresalesDtos.Command(
                                                        1L,
                                                        "edit-operation",
                                                        "UPDATE_PROJECT",
                                                        json.createObjectNode()
                                                                .put(
                                                                        "name",
                                                                        "edited-during-render"))));
                try {
                    editing.get(2, TimeUnit.SECONDS);
                    editCompletedDuringRender = true;
                } catch (java.util.concurrent.TimeoutException blocked) {
                    // Record the old project's render-held lock without hanging the executor.
                }
            } finally {
                release.countDown();
            }
            Throwable renderFailure = failure(rendering);
            Throwable editFailure =
                    editing == null ? new AssertionError("edit not started") : failure(editing);
            boolean concurrentEdit = editCompletedDuringRender;
            ObjectNode stored =
                    (ObjectNode)
                            json.readTree(
                                    jdbc.queryForObject(
                                            "SELECT body_json FROM mate_presales_project WHERE id=?",
                                            String.class,
                                            PROJECT));
            assertAll(
                    () ->
                            assertFalse(
                                    renderer.transactionActive,
                                    "renderer inherited a database transaction"),
                    () ->
                            assertNull(
                                    renderer.transactionResource,
                                    "renderer inherited a transaction-bound connection"),
                    () ->
                            assertTrue(
                                    concurrentEdit,
                                    "project editing remained blocked during rendering"),
                    () -> assertNull(editFailure, "concurrent editing must succeed"),
                    () ->
                            assertEquals(
                                    "VERSION_CONFLICT",
                                    assertInstanceOf(SemanticApiException.class, renderFailure)
                                            .code()),
                    () -> assertEquals(2, stored.path("version").asInt()),
                    () -> assertEquals("edited-during-render", stored.path("name").asText()),
                    () -> assertTrue(stored.path("releases").isEmpty()),
                    () ->
                            assertEquals(
                                    0,
                                    jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM mate_presales_artifact",
                                            Integer.class)),
                    () ->
                            assertEquals(
                                    0,
                                    jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM mate_presales_operation WHERE operation_id='render-operation'",
                                            Integer.class)),
                    () ->
                            assertEquals(
                                    1,
                                    jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM mate_presales_revision WHERE action='UPDATE_PROJECT'",
                                            Integer.class)));
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "source-text,RENDER_INPUT_CHANGED",
        "source-revoked,SOURCE_UNAVAILABLE",
        "actor,FORBIDDEN",
        "review,INDEPENDENT_REVIEW_REQUIRED",
        "ppt,RENDER_INPUT_CHANGED",
        "historical-fit,RENDER_INPUT_CHANGED",
        "historical-scalar-graph,EXECUTION_AUTHORITY_CHANGED"
    })
    void dependencyChangeDuringRenderingRejectsAllCandidateWrites(String change, String code)
            throws Exception {
        ObjectNode p = project();
        if (change.startsWith("source"))
            p.withArray("clarifications")
                    .addObject()
                    .put("id", "question")
                    .putArray("sourceRefs")
                    .add("30");
        if (change.startsWith("historical-")) {
            var snapshot =
                    p.withArray("releases")
                            .addObject()
                            .put("id", "previous")
                            .putObject("handoffSnapshot");
            snapshot.putArray("materials").addObject().put("kbId", "20").put("graphId", "graph");
            snapshot.putObject("baseline").putArray("references");
            snapshot.putArray("sourceRefs");
            snapshot.putArray("fitGaps")
                    .addObject()
                    .put("status", "FIT")
                    .put("graphId", "graph")
                    .putArray("evidenceIds")
                    .add("evidence");
        }
        if (change.equals("historical-scalar-graph")) {
            ObjectNode snapshot = (ObjectNode) p.path("releases").get(0).path("handoffSnapshot");
            snapshot.withArray("fitGaps").removeAll();
            snapshot.withArray("sourceRefs").add("30");
        }
        if (change.equals("ppt")) {
            ((ObjectNode) p.path("solutions").get(0))
                    .putObject("presentation")
                    .put("artifactId", "slides");
            insertPpt("first");
        }
        jdbc.update(
                "UPDATE mate_presales_project SET body_json=? WHERE id=?", p.toString(), PROJECT);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var service = service(new BlockingRenderer(entered, release, dataSource));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var pending =
                    executor.submit(
                            () ->
                                    service.command(
                                            WORKSPACE,
                                            PROJECT,
                                            new PresalesDtos.Command(
                                                    1L,
                                                    "dependency-operation",
                                                    "CREATE_RELEASE",
                                                    json.createObjectNode()
                                                            .put("solutionId", "solution"))));
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                switch (change) {
                    case "source-text", "historical-fit" ->
                            jdbc.update(
                                    "UPDATE mate_wiki_raw_material SET extracted_text='changed' WHERE id=30");
                    case "source-revoked" ->
                            jdbc.update("UPDATE mate_wiki_raw_material SET deleted=1 WHERE id=30");
                    case "historical-scalar-graph" ->
                            jdbc.update("DELETE FROM mate_semantic_graph WHERE id='graph'");
                    case "actor" ->
                            doThrow(new SemanticApiException(403, "FORBIDDEN", "revoked"))
                                    .when(access)
                                    .requireLockedActor(WORKSPACE, "9", "member");
                    case "review" -> {
                        p.withArray("reviews").removeAll();
                        jdbc.update(
                                "UPDATE mate_presales_project SET body_json=? WHERE id=?",
                                p.toString(),
                                PROJECT);
                    }
                    case "ppt" -> {
                        byte[] bytes = "replacement".getBytes(StandardCharsets.UTF_8);
                        jdbc.update(
                                "UPDATE mate_presales_artifact SET digest=?,content_base64=? WHERE release_id='slides'",
                                PresalesArtifactRenderer.digest(bytes),
                                java.util.Base64.getEncoder().encodeToString(bytes));
                    }
                }
            } finally {
                release.countDown();
            }
            assertEquals(
                    code, assertInstanceOf(SemanticApiException.class, failure(pending)).code());
            assertEquals(
                    1L,
                    jdbc.queryForObject(
                            "SELECT version FROM mate_presales_project WHERE id=?",
                            Long.class,
                            PROJECT));
            assertEquals(
                    0,
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM mate_presales_artifact WHERE release_id<>'slides'",
                            Integer.class));
            assertEquals(
                    0,
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM mate_presales_revision", Integer.class));
            assertEquals(
                    0,
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM mate_presales_operation", Integer.class));
            assertEquals(
                    "FAILED",
                    jdbc.queryForObject(
                            "SELECT status FROM mate_presales_render_task", String.class));
        }
    }

    private void insertPpt(String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        new PresalesArtifactRepository(jdbc)
                .insert(
                        PROJECT,
                        "slides",
                        "solution.pptx",
                        PresalesArtifactRenderer.digest(bytes),
                        java.util.Base64.getEncoder().encodeToString(bytes));
    }

    private static Throwable failure(Future<?> future) throws Exception {
        try {
            future.get(10, TimeUnit.SECONDS);
            return null;
        } catch (java.util.concurrent.ExecutionException failed) {
            return failed.getCause();
        }
    }

    private PresalesService service(PresalesArtifactRenderer renderer) {
        var semantic = new SemanticProperties();
        semantic.setEnabled(true);
        ObjectProvider<GraphApplicationService> graphs = mock(ObjectProvider.class);
        when(graphs.getIfAvailable()).thenReturn(mock(GraphApplicationService.class));
        ObjectProvider<StatementApplicationService> statements = mock(ObjectProvider.class);
        when(statements.getIfAvailable()).thenReturn(mock(StatementApplicationService.class));
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
                        authorityFence,
                        sourceAuthorization,
                        new vip.mate.presales.repository.PresalesRenderTaskRepository(jdbc),
                        new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                                jdbc.getDataSource()));
        var proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(
                new TransactionInterceptor(
                        new DataSourceTransactionManager(dataSource),
                        new AnnotationTransactionAttributeSource()));
        return (PresalesService) proxy.getProxy();
    }

    private ObjectNode project() {
        ObjectNode p = json.createObjectNode();
        p.put("id", PROJECT)
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
        p.withArray("solutions")
                .addObject()
                .put("id", "solution")
                .put("title", "Solution")
                .put("authorId", "writer")
                .put("baselineId", "baseline")
                .put("provisional", false)
                .putObject("coverage")
                .putArray("responses");
        ObjectNode solution = (ObjectNode) p.withArray("solutions").get(0);
        solution.putArray("sections").addObject().put("title", "Scope").put("text", "Frozen");
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

    private static final class BlockingRenderer extends PresalesArtifactRenderer {
        private final CountDownLatch entered;
        private final CountDownLatch release;
        private final DataSource dataSource;
        private volatile boolean transactionActive;
        private volatile Object transactionResource;

        private BlockingRenderer(
                CountDownLatch entered, CountDownLatch release, DataSource dataSource) {
            this.entered = entered;
            this.release = release;
            this.dataSource = dataSource;
        }

        @Override
        public Map<String, byte[]> renderWithoutSlides(Document document) {
            return render(document);
        }

        @Override
        public Map<String, byte[]> render(Document document) {
            transactionActive = TransactionSynchronizationManager.isActualTransactionActive();
            transactionResource = TransactionSynchronizationManager.getResource(dataSource);
            entered.countDown();
            try {
                assertTrue(release.await(10, TimeUnit.SECONDS), "render release timed out");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
            var files = new LinkedHashMap<String, byte[]>();
            files.put("solution.md", "rendered".getBytes(StandardCharsets.UTF_8));
            return files;
        }
    }
}

package vip.mate.presales;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import vip.mate.auth.model.UserEntity;
import vip.mate.auth.service.AuthService;
import vip.mate.semantic.security.SemanticPrincipalResolver;
import vip.mate.semantic.web.SemanticApiException;
import vip.mate.workspace.core.repository.WorkspaceMapper;
import vip.mate.workspace.core.repository.WorkspaceMemberMapper;
import vip.mate.workspace.core.service.ProjectAuthorityFence;

/** Real JDBC/MyBatis interleavings; external runs require an empty, disposable local database. */
class ProjectAuthorityFenceDatabaseTest {
    enum Authority {
        ACTOR("mate_user", "enabled=FALSE", "CASE WHEN enabled THEN 0 ELSE 1 END"),
        WORKSPACE("mate_workspace", "deleted=1", "deleted"),
        MEMBER(
                "mate_workspace_member",
                "role='viewer'",
                "CASE WHEN role='member' THEN 0 ELSE 1 END"),
        EMPLOYEE("mate_agent", "enabled=FALSE", "CASE WHEN enabled THEN 0 ELSE 1 END"),
        MODEL("mate_model_config", "enabled=FALSE", "CASE WHEN enabled THEN 0 ELSE 1 END"),
        KB("mate_wiki_knowledge_base", "deleted=1", "deleted"),
        RAW("mate_wiki_raw_material", "deleted=1", "deleted"),
        GRAPH("mate_semantic_graph", "archived=TRUE", "CASE WHEN archived THEN 1 ELSE 0 END");

        final String table;
        final String update;
        final String state;

        Authority(String table, String update, String state) {
            this.table = table;
            this.update = update;
            this.state = state;
        }
    }

    private static final List<String> TABLES =
            List.of(
                    "mate_user",
                    "mate_workspace",
                    "mate_workspace_member",
                    "mate_agent",
                    "mate_model_config",
                    "mate_wiki_knowledge_base",
                    "mate_wiki_raw_material",
                    "mate_semantic_graph",
                    "mate_presales_project",
                    "mate_presales_operation",
                    "mate_presales_revision");
    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private SqlSessionTemplate sessions;
    private ProjectAuthorityFence fence;
    private PresalesAccess access;
    private boolean ownsTables;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        String url = System.getenv("MATECLAW_ACCEPTANCE_JDBC_URL");
        boolean external = url != null && !url.isBlank();
        if (external) {
            assertTrue(
                    url.matches(
                            "jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/mateclaw_aq_acceptance_[a-z0-9]+(?:\\?.*)?"),
                    "External acceptance requires a disposable loopback MySQL database");
        } else {
            url = "jdbc:h2:mem:authority_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        }
        var dataSource =
                new DriverManagerDataSource(
                        url,
                        external ? System.getenv("MATECLAW_ACCEPTANCE_JDBC_USER") : "sa",
                        external ? System.getenv("MATECLAW_ACCEPTANCE_JDBC_PASSWORD") : "");
        jdbc = new JdbcTemplate(dataSource);
        try (var connection = dataSource.getConnection();
                var tables =
                        connection
                                .getMetaData()
                                .getTables(
                                        connection.getCatalog(),
                                        null,
                                        "%",
                                        new String[] {"TABLE"})) {
            while (tables.next()) {
                if (!"INFORMATION_SCHEMA".equalsIgnoreCase(tables.getString("TABLE_SCHEM")))
                    fail("Acceptance database must be empty before fixture creation");
            }
        }
        ownsTables = true;
        jdbc.execute("CREATE TABLE mate_user(id BIGINT PRIMARY KEY, enabled BOOLEAN)");
        jdbc.execute(
                "CREATE TABLE mate_workspace(id BIGINT PRIMARY KEY, name VARCHAR(100), slug VARCHAR(100), description VARCHAR(100), owner_id BIGINT, base_path VARCHAR(100), settings_json VARCHAR(100), create_time TIMESTAMP, update_time TIMESTAMP, deleted INT)");
        jdbc.execute(
                "CREATE TABLE mate_workspace_member(id BIGINT PRIMARY KEY, workspace_id BIGINT, user_id BIGINT, role VARCHAR(20), create_time TIMESTAMP, update_time TIMESTAMP, deleted INT)");
        jdbc.execute(
                "CREATE TABLE mate_agent(id BIGINT PRIMARY KEY, workspace_id BIGINT, enabled BOOLEAN)");
        jdbc.execute("CREATE TABLE mate_model_config(id BIGINT PRIMARY KEY, enabled BOOLEAN)");
        jdbc.execute(
                "CREATE TABLE mate_wiki_knowledge_base(id BIGINT PRIMARY KEY, workspace_id BIGINT, deleted INT)");
        jdbc.execute(
                "CREATE TABLE mate_wiki_raw_material(id BIGINT PRIMARY KEY, kb_id BIGINT, deleted INT)");
        jdbc.execute(
                "CREATE TABLE mate_semantic_graph(id VARCHAR(64) PRIMARY KEY, workspace_id BIGINT, archived BOOLEAN)");
        jdbc.execute(
                "CREATE TABLE mate_presales_project(id VARCHAR(64) PRIMARY KEY, workspace_id VARCHAR(64), version INT, name VARCHAR(100), status VARCHAR(20), body_json TEXT)");
        jdbc.execute(
                "CREATE TABLE mate_presales_operation(workspace_id VARCHAR(64), actor_id VARCHAR(64), operation_id VARCHAR(64), request_hash VARCHAR(100), response_json TEXT)");
        jdbc.execute(
                "CREATE TABLE mate_presales_revision(project_id VARCHAR(64), version INT, actor_id VARCHAR(64), action VARCHAR(64), body_json TEXT, created_at TIMESTAMP)");
        jdbc.update("INSERT INTO mate_user VALUES(9,TRUE)");
        jdbc.update("INSERT INTO mate_workspace(id,deleted) VALUES(1,0)");
        jdbc.update(
                "INSERT INTO mate_workspace_member(id,workspace_id,user_id,role,deleted) VALUES(19,1,9,'member',0)");
        jdbc.update("INSERT INTO mate_agent VALUES(7,1,TRUE)");
        jdbc.update("INSERT INTO mate_model_config VALUES(17,TRUE)");
        jdbc.update("INSERT INTO mate_wiki_knowledge_base VALUES(3,1,0)");
        jdbc.update("INSERT INTO mate_wiki_raw_material VALUES(33,3,0)");
        jdbc.update("INSERT INTO mate_semantic_graph VALUES('g',1,FALSE)");
        jdbc.update(
                "INSERT INTO mate_presales_project VALUES('p','1',2,'case','ACTIVE',?)",
                project().toString());
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(WorkspaceMapper.class);
        configuration.addMapper(WorkspaceMemberMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        sessions = new SqlSessionTemplate(factory.getObject());
        fence = new ProjectAuthorityFence(jdbc, sessions);
        var user = new UserEntity();
        user.setId(9L);
        user.setEnabled(true);
        user.setDeleted(0);
        user.setRole("user");
        var principals = mock(SemanticPrincipalResolver.class);
        when(principals.require()).thenReturn(user);
        var auth = mock(AuthService.class);
        when(auth.findById(9L)).thenReturn(user);
        access =
                new PresalesAccess(
                        principals,
                        auth,
                        sessions.getMapper(WorkspaceMapper.class),
                        sessions.getMapper(WorkspaceMemberMapper.class));
    }

    @AfterEach
    void tearDown() {
        if (ownsTables) for (String table : TABLES) jdbc.execute("DROP TABLE IF EXISTS " + table);
    }

    private boolean lockAuthority() {
        return fence.lockForResult(
                "1",
                List.of("9"),
                "7",
                "17",
                List.of(new ProjectAuthorityFence.Source("3", "33", "g")));
    }

    private int state(Authority authority) {
        return jdbc.queryForObject(
                "SELECT " + authority.state + " FROM " + authority.table, Integer.class);
    }

    private ObjectNode project() {
        var project =
                json.createObjectNode()
                        .put("id", "p")
                        .put("workspaceId", "1")
                        .put("version", 2)
                        .put("name", "case")
                        .put("status", "ACTIVE");
        project.putArray("tasks")
                .addObject()
                .put("id", "t")
                .put("status", "RUNNING")
                .put("authority", "UNTRUSTED_DRAFT")
                .put("skill", "S1")
                .put("agentId", "7")
                .put("modelConfigId", "17")
                .putObject("contextSnapshot")
                .put("caseRef", "p")
                .put("actorId", "9")
                .put("projectVersion", 2);
        return project;
    }

    @SuppressWarnings("unchecked")
    private PresalesService resultService(CountDownLatch initialAccessCompleted) {
        var runtime = mock(PresalesEmployeeRuntime.class);
        var employees = (ObjectProvider<PresalesEmployeeRuntime>) mock(ObjectProvider.class);
        when(employees.getIfAvailable()).thenReturn(runtime);
        when(employees.getObject()).thenReturn(runtime);
        org.mockito.Mockito.doAnswer(
                        call -> {
                            access.requireActor("1", "9", "member");
                            return null;
                        })
                .when(runtime)
                .revalidate(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
        var observedFence = org.mockito.Mockito.spy(fence);
        org.mockito.Mockito.doAnswer(
                        call -> {
                            initialAccessCompleted.countDown();
                            return call.callRealMethod();
                        })
                .when(observedFence)
                .lockForResult(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
        return new PresalesService(
                jdbc,
                json,
                access,
                mock(vip.mate.wiki.service.WikiKnowledgeBaseService.class),
                mock(ObjectProvider.class),
                mock(ObjectProvider.class),
                mock(vip.mate.semantic.config.SemanticProperties.class),
                mock(ObjectProvider.class),
                mock(PresalesArtifactRenderer.class),
                employees,
                observedFence,
                new vip.mate.workspace.core.service.ProjectSourceAccess(jdbc));
    }

    @ParameterizedTest
    @EnumSource(Authority.class)
    void resultFirstBlocksRevocationUntilResultTransactionCompletes(Authority authority)
            throws Exception {
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var result =
                    executor.submit(
                            () ->
                                    transaction.executeWithoutResult(
                                            status -> {
                                                assertTrue(lockAuthority());
                                                assertEquals(0, state(authority));
                                                locked.countDown();
                                                await(release);
                                            }));
            assertTrue(locked.await(10, TimeUnit.SECONDS));
            var started = new CountDownLatch(1);
            var revoked =
                    executor.submit(
                            () ->
                                    transaction.executeWithoutResult(
                                            status -> {
                                                started.countDown();
                                                jdbc.update(
                                                        "UPDATE "
                                                                + authority.table
                                                                + " SET "
                                                                + authority.update);
                                            }));
            assertTrue(started.await(10, TimeUnit.SECONDS));
            try {
                assertThrows(TimeoutException.class, () -> revoked.get(200, TimeUnit.MILLISECONDS));
            } finally {
                release.countDown();
            }
            result.get(10, TimeUnit.SECONDS);
            revoked.get(10, TimeUnit.SECONDS);
            assertEquals(1, state(authority));
        } finally {
            release.countDown();
        }
    }

    @ParameterizedTest
    @EnumSource(Authority.class)
    void revocationFirstIsVisibleAfterWaitingForAuthorityLock(Authority authority)
            throws Exception {
        revocationFirst(authority, false);
    }

    @ParameterizedTest
    @EnumSource(
            value = Authority.class,
            names = {"WORKSPACE", "MEMBER"})
    void postLockAccessRejectsRevocationDespiteEarlierMyBatisRead(Authority authority)
            throws Exception {
        revocationFirst(authority, true);
    }

    @Test
    void graphFirstWriterDoesNotCompeteWithResultForKnowledgeBaseWhileHoldingGraph()
            throws Exception {
        var graphLocked = new CountDownLatch(1);
        var releaseGraph = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var writer =
                    executor.submit(
                            () ->
                                    transaction.executeWithoutResult(
                                            status -> {
                                                jdbc.queryForList(
                                                        "SELECT id FROM mate_semantic_graph WHERE id='g' FOR UPDATE");
                                                graphLocked.countDown();
                                                await(releaseGraph);
                                                // SourceChangeService.proposeFactRevision locks
                                                // graph before this joined read.
                                                jdbc.queryForList(
                                                        "SELECT r.id FROM mate_wiki_raw_material r JOIN mate_wiki_knowledge_base k ON k.id=r.kb_id WHERE r.id=33 FOR UPDATE");
                                            }));
            assertTrue(graphLocked.await(10, TimeUnit.SECONDS));
            var started = new CountDownLatch(1);
            var observedJdbc =
                    new JdbcTemplate(jdbc.getDataSource()) {
                        @Override
                        public java.util.List<java.util.Map<String, Object>> queryForList(
                                String sql, Object... args) {
                            if (sql.startsWith("SELECT id FROM mate_semantic_graph"))
                                started.countDown();
                            return super.queryForList(sql, args);
                        }
                    };
            var observedFence = new ProjectAuthorityFence(observedJdbc, sessions);
            var result =
                    executor.submit(
                            () ->
                                    transaction.executeWithoutResult(
                                            status -> {
                                                assertTrue(
                                                        observedFence.lockForResult(
                                                                "1",
                                                                List.of("9"),
                                                                "7",
                                                                "17",
                                                                List.of(
                                                                        new ProjectAuthorityFence
                                                                                .Source(
                                                                                "3", "33", "g"))));
                                            }));
            assertTrue(started.await(10, TimeUnit.SECONDS));
            try {
                assertThrows(TimeoutException.class, () -> result.get(200, TimeUnit.MILLISECONDS));
                // A result waiting on graph must not already hold the writer's KB/raw rows.
                transaction.executeWithoutResult(
                        status ->
                                jdbc.queryForList(
                                        "SELECT id FROM mate_wiki_knowledge_base WHERE id=3 FOR UPDATE NOWAIT"));
            } finally {
                releaseGraph.countDown();
            }
            writer.get(10, TimeUnit.SECONDS);
            result.get(10, TimeUnit.SECONDS);
        } finally {
            releaseGraph.countDown();
        }
    }

    private void revocationFirst(Authority authority, boolean useAccess) throws Exception {
        var changed = new CountDownLatch(1);
        var read = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var revoked =
                    executor.submit(
                            () ->
                                    transaction.executeWithoutResult(
                                            status -> {
                                                jdbc.update(
                                                        "UPDATE "
                                                                + authority.table
                                                                + " SET "
                                                                + authority.update);
                                                changed.countDown();
                                                await(release);
                                            }));
            assertTrue(changed.await(10, TimeUnit.SECONDS));
            var result =
                    executor.submit(
                            () ->
                                    transaction.executeWithoutResult(
                                            status -> {
                                                if (useAccess) {
                                                    var service = resultService(read);
                                                    var candidate =
                                                            ((ObjectNode)
                                                                            project()
                                                                                    .path("tasks")
                                                                                    .get(0))
                                                                    .deepCopy();
                                                    var candidateResult =
                                                            candidate
                                                                    .put("status", "SUCCEEDED")
                                                                    .putObject("result")
                                                                    .put("schemaVersion", 1)
                                                                    .put("needsHumanReview", true);
                                                    candidateResult.putArray("assumptions");
                                                    candidateResult.putArray("unknowns");
                                                    candidateResult
                                                            .putArray("items")
                                                            .addObject()
                                                            .put("kind", "CLARIFICATION")
                                                            .put("title", "Question")
                                                            .put("text", "Confirm scope")
                                                            .put("originKind", "AI_SUGGESTION")
                                                            .putArray("sourceRefs");
                                                    var error =
                                                            assertThrows(
                                                                    SemanticApiException.class,
                                                                    () ->
                                                                            service
                                                                                    .saveEmployeeTask(
                                                                                            "1",
                                                                                            "p",
                                                                                            new PresalesDtos
                                                                                                    .Command(
                                                                                                    2,
                                                                                                    "finish",
                                                                                                    "SAVE_AI_TASK",
                                                                                                    candidate)));
                                                    assertEquals(
                                                            authority == Authority.MEMBER
                                                                    ? 403
                                                                    : 404,
                                                            error.status());
                                                } else {
                                                    assertEquals(0, state(authority));
                                                    read.countDown();
                                                    assertTrue(lockAuthority());
                                                    assertEquals(1, state(authority));
                                                }
                                            }));
            assertTrue(read.await(10, TimeUnit.SECONDS));
            try {
                assertThrows(TimeoutException.class, () -> result.get(200, TimeUnit.MILLISECONDS));
            } finally {
                release.countDown();
            }
            revoked.get(10, TimeUnit.SECONDS);
            result.get(10, TimeUnit.SECONDS);
            if (useAccess) {
                assertEquals(
                        2,
                        jdbc.queryForObject(
                                "SELECT version FROM mate_presales_project WHERE id='p'",
                                Integer.class));
                assertEquals(
                        "RUNNING",
                        json.readTree(
                                        jdbc.queryForObject(
                                                "SELECT body_json FROM mate_presales_project WHERE id='p'",
                                                String.class))
                                .path("tasks")
                                .get(0)
                                .path("status")
                                .asText());
                assertEquals(
                        0,
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM mate_presales_operation", Integer.class));
                assertEquals(
                        0,
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM mate_presales_revision", Integer.class));
            }
        } finally {
            release.countDown();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS), "Transaction interleaving timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
}

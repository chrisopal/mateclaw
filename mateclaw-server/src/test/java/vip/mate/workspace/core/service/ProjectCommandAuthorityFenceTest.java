package vip.mate.workspace.core.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Human command acceptance locks real authority without inventing an employee or model. */
class ProjectCommandAuthorityFenceTest {
    enum Authority {
        ACTOR("mate_user"),
        WORKSPACE("mate_workspace"),
        MEMBER("mate_workspace_member"),
        EMPLOYEE("mate_agent"),
        GRAPH("mate_semantic_graph"),
        KB("mate_wiki_knowledge_base"),
        RAW("mate_wiki_raw_material");
        final String table;

        Authority(String table) {
            this.table = table;
        }
    }

    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private SqlSessionTemplate sessions;
    private ProjectAuthorityFence fence;

    @BeforeEach
    void setUp() {
        var dataSource =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:command_fence_"
                                + UUID.randomUUID()
                                + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
                        "sa",
                        "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE mate_user(id BIGINT PRIMARY KEY, changed INT)");
        jdbc.execute("CREATE TABLE mate_workspace(id BIGINT PRIMARY KEY, changed INT)");
        jdbc.execute(
                "CREATE TABLE mate_workspace_member(id BIGINT PRIMARY KEY, workspace_id BIGINT, user_id BIGINT, deleted INT, changed INT)");
        jdbc.execute(
                "CREATE TABLE mate_agent(id BIGINT PRIMARY KEY, workspace_id BIGINT, changed INT)");
        jdbc.execute("CREATE TABLE mate_model_config(id BIGINT PRIMARY KEY, changed INT)");
        jdbc.execute(
                "CREATE TABLE mate_semantic_graph(id VARCHAR(64) PRIMARY KEY, workspace_id BIGINT, changed INT)");
        jdbc.execute(
                "CREATE TABLE mate_wiki_knowledge_base(id BIGINT PRIMARY KEY, workspace_id BIGINT, changed INT)");
        jdbc.execute(
                "CREATE TABLE mate_wiki_raw_material(id BIGINT PRIMARY KEY, kb_id BIGINT, changed INT)");
        jdbc.update("INSERT INTO mate_user VALUES(9,0)");
        jdbc.update("INSERT INTO mate_workspace VALUES(1,0)");
        jdbc.update("INSERT INTO mate_workspace_member VALUES(19,1,9,0,0)");
        jdbc.update("INSERT INTO mate_agent VALUES(7,1,0)");
        jdbc.update("INSERT INTO mate_model_config VALUES(17,0)");
        jdbc.update("INSERT INTO mate_semantic_graph VALUES('g',1,0)");
        jdbc.update("INSERT INTO mate_wiki_knowledge_base VALUES(3,1,0)");
        jdbc.update("INSERT INTO mate_wiki_raw_material VALUES(33,3,0)");
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        sessions = mock(SqlSessionTemplate.class);
        fence = new ProjectAuthorityFence(jdbc, sessions);
    }

    private boolean command(boolean employee) {
        return fence.lockForCommand(
                "1",
                List.of("9"),
                employee ? "7" : "",
                List.of("3"),
                List.of("g"),
                List.of(new ProjectAuthorityFence.Source("3", "33", "g")));
    }

    @Test
    void humanCommandDoesNotQueryEmployeeOrModelTables() {
        jdbc.execute("DROP TABLE mate_agent");
        jdbc.execute("DROP TABLE mate_model_config");
        transaction.executeWithoutResult(status -> assertTrue(command(false)));
        verify(sessions).clearCache();
    }

    @Test
    void knowledgeBaseAndGraphBindingsDoNotRequireInventedRawMaterial() {
        jdbc.execute("DROP TABLE mate_wiki_raw_material");
        transaction.executeWithoutResult(
                status ->
                        assertTrue(
                                fence.lockForCommand(
                                        "1",
                                        List.of("9"),
                                        null,
                                        List.of("3"),
                                        List.of("g"),
                                        List.of())));
        verify(sessions).clearCache();
    }

    @Test
    void transactionAndRealActorAreRequired() {
        assertThrows(IllegalStateException.class, () -> command(false));
        transaction.executeWithoutResult(
                status -> {
                    assertThrows(
                            IllegalArgumentException.class,
                            () ->
                                    fence.lockForCommand(
                                            "1", List.of(), "", List.of(), List.of(), List.of()));
                    assertThrows(
                            IllegalArgumentException.class,
                            () ->
                                    fence.lockForCommand(
                                            "1",
                                            List.of("not-an-actor"),
                                            "",
                                            List.of(),
                                            List.of(),
                                            List.of()));
                });
        verifyNoInteractions(sessions);
    }

    @Test
    void aiResultStillRequiresActualEmployeeAndModel() {
        transaction.executeWithoutResult(
                status -> {
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> fence.lockForResult("1", List.of("9"), "", "17", List.of()));
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> fence.lockForResult("1", List.of("9"), "7", "", List.of()));
                    assertTrue(fence.lockForResult("1", List.of("9"), "7", "17", List.of()));
                });
        verify(sessions).clearCache();
    }

    @ParameterizedTest
    @EnumSource(
            value = Authority.class,
            names = {"MEMBER"},
            mode = EnumSource.Mode.EXCLUDE)
    void missingIdentityOrSourceCannotBeAccepted(Authority authority) {
        jdbc.update("DELETE FROM " + authority.table);
        transaction.executeWithoutResult(status -> assertFalse(command(true)));
        verifyNoInteractions(sessions);
    }

    @Test
    void sourceInAnotherWorkspaceOrKnowledgeBaseCannotBeLocked() {
        jdbc.update("UPDATE mate_semantic_graph SET workspace_id=2");
        transaction.executeWithoutResult(status -> assertFalse(command(false)));
        jdbc.update("UPDATE mate_semantic_graph SET workspace_id=1");
        jdbc.update("UPDATE mate_wiki_knowledge_base SET workspace_id=2");
        transaction.executeWithoutResult(status -> assertFalse(command(false)));
        jdbc.update("UPDATE mate_wiki_knowledge_base SET workspace_id=1");
        jdbc.update("UPDATE mate_wiki_raw_material SET kb_id=4");
        transaction.executeWithoutResult(status -> assertFalse(command(false)));
        verifyNoInteractions(sessions);
    }

    @ParameterizedTest
    @EnumSource(Authority.class)
    void acceptanceHoldsAuthorityUntilItsTransactionEnds(Authority authority) throws Exception {
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var accepted =
                    executor.submit(
                            () ->
                                    transaction.executeWithoutResult(
                                            status -> {
                                                assertTrue(command(true));
                                                locked.countDown();
                                                await(release);
                                                assertEquals(
                                                        0,
                                                        jdbc.queryForObject(
                                                                "SELECT changed FROM "
                                                                        + authority.table,
                                                                Integer.class));
                                            }));
            try {
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
                                                                    + " SET changed=1");
                                                }));
                assertTrue(started.await(10, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> revoked.get(150, TimeUnit.MILLISECONDS));
                release.countDown();
                accepted.get(10, TimeUnit.SECONDS);
                revoked.get(10, TimeUnit.SECONDS);
                assertEquals(
                        1,
                        jdbc.queryForObject(
                                "SELECT changed FROM " + authority.table, Integer.class));
            } finally {
                release.countDown();
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Authority.class)
    void revocationThatWinsLocksIsVisibleToPostLockRevalidation(Authority authority)
            throws Exception {
        var changed = new CountDownLatch(1);
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
                                                                + " SET changed=1");
                                                changed.countDown();
                                                await(release);
                                            }));
            try {
                assertTrue(changed.await(10, TimeUnit.SECONDS));
                var started = new CountDownLatch(1);
                var accepted =
                        executor.submit(
                                () ->
                                        transaction.execute(
                                                status -> {
                                                    assertEquals(
                                                            0,
                                                            jdbc.queryForObject(
                                                                    "SELECT changed FROM "
                                                                            + authority.table,
                                                                    Integer.class));
                                                    started.countDown();
                                                    assertTrue(command(true));
                                                    return jdbc.queryForObject(
                                                            "SELECT changed FROM "
                                                                    + authority.table,
                                                            Integer.class);
                                                }));
                assertTrue(started.await(10, TimeUnit.SECONDS));
                assertThrows(
                        TimeoutException.class, () -> accepted.get(150, TimeUnit.MILLISECONDS));
                release.countDown();
                revoked.get(10, TimeUnit.SECONDS);
                assertEquals(1, accepted.get(10, TimeUnit.SECONDS));
                verify(sessions).clearCache();
            } finally {
                release.countDown();
            }
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
}

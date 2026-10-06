package vip.mate.presales;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import vip.mate.presales.repository.PresalesRenderTaskRepository;
import vip.mate.presales.repository.PresalesRenderTaskRepository.RenderTask;

class PresalesRenderTaskRepositoryTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 6, 10, 0, 0, 123456000);
    private static final String INPUT = "{\"snapshot\":\"原始输入 Ω\",\"version\":9007199254740993}";
    private JdbcTemplate jdbc;
    private PresalesRenderTaskRepository tasks;
    private TransactionTemplate transaction;

    @BeforeEach
    void database() {
        var source = new org.h2.jdbcx.JdbcDataSource();
        source.setURL(
                "jdbc:h2:mem:render_tasks_"
                        + UUID.randomUUID()
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(
                        new ClassPathResource("db/migration/h2/V211__presales_projects.sql"),
                        new ClassPathResource("db/migration/h2/V221__presales_render_tasks.sql"))
                .execute(source);
        jdbc = new JdbcTemplate(source);
        tasks = new PresalesRenderTaskRepository(jdbc);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
    }

    @AfterEach
    void close() throws java.sql.SQLException {
        try (var connection = jdbc.getDataSource().getConnection();
                var statement = connection.createStatement()) {
            statement.execute("SHUTDOWN");
        }
    }

    @Test
    void roundTripsExactInputsAndScopesOperationLookup() {
        var task = task("task", "workspace", "actor", "operation");
        tasks.insertTask(task);
        assertEquals(task, tasks.findTask("task", false).orElseThrow());
        transaction.executeWithoutResult(
                status -> {
                    assertEquals(task, tasks.findTask("task", true).orElseThrow());
                    assertEquals(
                            task,
                            tasks.findOperation("workspace", "actor", "operation", true)
                                    .orElseThrow());
                });
        assertEquals(
                task, tasks.findOperation("workspace", "actor", "operation", false).orElseThrow());
        assertTrue(tasks.findTask("missing", false).isEmpty());
        assertTrue(tasks.findOperation("other", "actor", "operation", false).isEmpty());
        assertTrue(tasks.findOperation("workspace", "other", "operation", false).isEmpty());
        assertTrue(tasks.findOperation("workspace", "actor", "other", false).isEmpty());
    }

    @Test
    void operationReservationIsUniqueWithinWorkspaceAndActorAcrossProjects() {
        tasks.insertTask(task("first", "workspace", "actor", "operation"));
        assertThrows(
                DataIntegrityViolationException.class,
                () -> tasks.insertTask(task("second", "workspace", "actor", "operation")));
        assertThrows(
                DataIntegrityViolationException.class,
                () -> tasks.insertTask(task("first", "other", "actor", "operation")));
        tasks.insertTask(task("other-workspace", "other", "actor", "operation"));
        tasks.insertTask(task("other-actor", "workspace", "other", "operation"));
        tasks.insertTask(task("other-operation", "workspace", "actor", "other"));
        assertEquals(
                4,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM mate_presales_render_task", Integer.class));
    }

    @Test
    void failedAttemptCanBeRetriedExactlyOnceWithoutRewritingInput() {
        var original = task("task", "workspace", "actor", "operation");
        tasks.insertTask(original);
        assertEquals(1, tasks.finish("task", "task-attempt", "FAILED", NOW.plusSeconds(1)));
        var retryAt = NOW.plusSeconds(2);
        assertEquals(
                1, tasks.claim("task", "task-attempt", "retry", retryAt, retryAt.plusMinutes(2)));
        assertEquals(
                0, tasks.claim("task", "task-attempt", "loser", retryAt, retryAt.plusMinutes(2)));
        var retry = tasks.findTask("task", false).orElseThrow();
        assertEquals("retry", retry.attemptId());
        assertEquals(2, retry.attemptNo());
        assertEquals("RUNNING", retry.status());
        assertEquals(retryAt.plusMinutes(2), retry.leaseUntil());
        assertEquals(retryAt, retry.updatedAt());
        assertImmutableInput(original, retry);
        assertEquals(0, tasks.finish("task", "task-attempt", "FAILED", retryAt));
        assertEquals(0, tasks.finish("task", "task-attempt", "SUCCEEDED", retryAt));
        assertEquals(1, tasks.finish("task", "retry", "SUCCEEDED", retryAt));
        assertEquals(0, tasks.finish("task", "retry", "FAILED", retryAt));
        assertEquals(
                0,
                tasks.claim("task", "retry", "third", retryAt.plusHours(1), retryAt.plusHours(2)));
        assertImmutableInput(original, tasks.findTask("task", false).orElseThrow());
    }

    @Test
    void runningAttemptCanOnlyBeReclaimedAtOrAfterLeaseExpiry() {
        var original = task("task", "workspace", "actor", "operation");
        tasks.insertTask(original);
        var expiry = original.leaseUntil();
        assertEquals(
                0,
                tasks.claim(
                        "task",
                        "task-attempt",
                        "early",
                        expiry.minusNanos(1000),
                        expiry.plusMinutes(1)));
        assertEquals(0, tasks.finish("task", "task-attempt", "SUCCEEDED", expiry));
        assertEquals(
                1, tasks.claim("task", "task-attempt", "reclaimed", expiry, expiry.plusMinutes(1)));
        assertEquals(
                0,
                tasks.claim("task", "task-attempt", "competitor", expiry, expiry.plusMinutes(1)));
        assertEquals(0, tasks.finish("task", "task-attempt", "SUCCEEDED", expiry));
        assertEquals(0, tasks.finish("task", "task-attempt", "FAILED", expiry));
        assertEquals(1, tasks.finish("task", "reclaimed", "SUCCEEDED", expiry.plusSeconds(1)));
        var completed = tasks.findTask("task", false).orElseThrow();
        assertEquals("SUCCEEDED", completed.status());
        assertEquals(2, completed.attemptNo());
        assertImmutableInput(original, completed);
    }

    @Test
    void concurrentReclaimersHaveExactlyOneWinner() throws Exception {
        var original = task("task", "workspace", "actor", "operation");
        tasks.insertTask(original);
        var expiry = original.leaseUntil();
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first =
                    executor.submit(
                            () -> {
                                ready.countDown();
                                assertTrue(start.await(5, TimeUnit.SECONDS));
                                return transaction.execute(
                                        status ->
                                                tasks.claim(
                                                        "task",
                                                        "task-attempt",
                                                        "first",
                                                        expiry,
                                                        expiry.plusMinutes(1)));
                            });
            var second =
                    executor.submit(
                            () -> {
                                ready.countDown();
                                assertTrue(start.await(5, TimeUnit.SECONDS));
                                return transaction.execute(
                                        status ->
                                                tasks.claim(
                                                        "task",
                                                        "task-attempt",
                                                        "second",
                                                        expiry,
                                                        expiry.plusMinutes(1)));
                            });
            try {
                assertTrue(ready.await(5, TimeUnit.SECONDS));
            } finally {
                start.countDown();
            }
            assertEquals(1, first.get(10, TimeUnit.SECONDS) + second.get(10, TimeUnit.SECONDS));
        }
        var winner = tasks.findTask("task", false).orElseThrow();
        assertTrue(winner.attemptId().equals("first") || winner.attemptId().equals("second"));
        assertEquals(2, winner.attemptNo());
        assertImmutableInput(original, winner);
    }

    @Test
    void rejectsNonterminalCompletionAndInvalidClaimIdentityOrLease() {
        tasks.insertTask(task("task", "workspace", "actor", "operation"));
        assertThrows(
                IllegalArgumentException.class,
                () -> tasks.finish("task", "task-attempt", "RUNNING", NOW));
        assertThrows(
                IllegalArgumentException.class,
                () -> tasks.claim("task", "task-attempt", "task-attempt", NOW, NOW.plusMinutes(1)));
        assertThrows(
                IllegalArgumentException.class,
                () -> tasks.claim("task", "task-attempt", "retry", NOW, NOW));
        assertEquals(0, tasks.finish("missing", "attempt", "FAILED", NOW));
        assertEquals("RUNNING", tasks.findTask("task", false).orElseThrow().status());
    }

    @Test
    void insertedReservationRollsBackWithCaller() {
        transaction.executeWithoutResult(
                status -> {
                    tasks.insertTask(task("task", "workspace", "actor", "operation"));
                    status.setRollbackOnly();
                });
        assertTrue(tasks.findTask("task", false).isEmpty());
        assertTrue(tasks.findOperation("workspace", "actor", "operation", false).isEmpty());
    }

    @Test
    void claimAndCompletionRollbackWithReceiptFailure() {
        var original = task("task", "workspace", "actor", "operation");
        tasks.insertTask(original);
        var expiry = original.leaseUntil();
        transaction.executeWithoutResult(
                status -> {
                    assertEquals(
                            1,
                            tasks.claim(
                                    "task",
                                    "task-attempt",
                                    "retry",
                                    expiry,
                                    expiry.plusMinutes(1)));
                    status.setRollbackOnly();
                });
        assertEquals(original, tasks.findTask("task", false).orElseThrow());
        assertThrows(
                DataIntegrityViolationException.class,
                () ->
                        transaction.executeWithoutResult(
                                status -> {
                                    assertEquals(
                                            1,
                                            tasks.finish("task", "task-attempt", "SUCCEEDED", NOW));
                                    insertReceipt();
                                    insertReceipt();
                                }));
        assertEquals(original, tasks.findTask("task", false).orElseThrow());
        assertEquals(
                0,
                jdbc.queryForObject("SELECT COUNT(*) FROM mate_presales_operation", Integer.class));
    }

    private void insertReceipt() {
        jdbc.update(
                "INSERT INTO mate_presales_operation(workspace_id,actor_id,operation_id,request_hash,response_json) VALUES(?,?,?,?,?)",
                "workspace",
                "actor",
                "operation",
                "hash",
                "{\"version\":2}");
    }

    private static RenderTask task(String id, String workspace, String actor, String operation) {
        return new RenderTask(
                id,
                workspace,
                actor,
                operation,
                id + "-project",
                9007199254740993L,
                "v2:" + "a".repeat(64),
                id + "-attempt",
                1,
                "RUNNING",
                INPUT,
                NOW.plusMinutes(1),
                NOW,
                NOW);
    }

    private static void assertImmutableInput(RenderTask expected, RenderTask actual) {
        assertEquals(expected.taskId(), actual.taskId());
        assertEquals(expected.workspaceId(), actual.workspaceId());
        assertEquals(expected.actorId(), actual.actorId());
        assertEquals(expected.operationId(), actual.operationId());
        assertEquals(expected.projectId(), actual.projectId());
        assertEquals(expected.expectedVersion(), actual.expectedVersion());
        assertEquals(expected.requestHash(), actual.requestHash());
        assertEquals(expected.inputJson(), actual.inputJson());
        assertEquals(expected.createdAt(), actual.createdAt());
    }
}

package vip.mate.presales;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import vip.mate.presales.repository.PresalesProjectRepository;
import vip.mate.presales.repository.PresalesProjectRepository.ProjectRow;

class PresalesProjectRepositoryTest {
    private JdbcTemplate jdbc;
    private PresalesProjectRepository repository;
    private TransactionTemplate transaction;
    private final String scope = "90071992547409999", id = "90071992547409998";
    private final String body = "{\"unchanged\":\"  Ω\\n客户原文  \"}";

    @BeforeEach
    void database() {
        var source = new org.h2.jdbcx.JdbcDataSource();
        source.setURL(
                "jdbc:h2:mem:project_repository_"
                        + UUID.randomUUID()
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000");
        new ResourceDatabasePopulator(
                        new ClassPathResource("db/migration/h2/V211__presales_projects.sql"),
                        new ClassPathResource(
                                "db/migration/h2/V217__presales_listing_projection.sql"))
                .execute(source);
        jdbc = new JdbcTemplate(source);
        repository = new PresalesProjectRepository(jdbc);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
    }

    @AfterEach
    void close() {
        jdbc.execute("SHUTDOWN");
    }

    private ProjectRow row(
            String projectId, String workspace, int version, String name, String json) {
        return new ProjectRow(projectId, workspace, version, name, "ACTIVE", json);
    }

    @Test
    void readsExactBytesWithWorkspaceFilteringAndOriginalOrdering() {
        repository.insert(row(id, scope, 1, "B", body));
        repository.insert(row("90071992547409997", scope, 1, "A", "  { \"legacy\": true }  "));
        repository.insert(row("90071992547409996", "other", 1, "A", "private"));
        assertEquals(body, repository.findBody(scope, id, false).orElseThrow());
        assertTrue(repository.findBody("other", id, false).isEmpty());
        assertTrue(repository.findBody(scope, "missing", true).isEmpty());
        assertEquals(List.of("  { \"legacy\": true }  ", body), repository.listBodies(scope));
    }

    @Test
    void comparesWorkspaceAndVersionBeforeUpdatingMetadataAndBytes() {
        repository.insert(row(id, scope, 1, "B", body));
        assertEquals(0, repository.update(row(id, "other", 2, "Changed", "new"), 1));
        assertEquals(0, repository.update(row(id, scope, 2, "Changed", "new"), 0));
        assertEquals(body, repository.findBody(scope, id, false).orElseThrow());
        assertEquals(1, repository.update(row(id, scope, 2, "Changed", "new"), 1));
        assertEquals(0, repository.update(row(id, scope, 3, "Stale", "stale"), 1));
        assertEquals("new", repository.findBody(scope, id, false).orElseThrow());
        assertEquals(
                2,
                jdbc.queryForObject(
                        "SELECT version FROM mate_presales_project WHERE id=?", Integer.class, id));
        assertEquals(
                "Changed",
                jdbc.queryForObject(
                        "SELECT name FROM mate_presales_project WHERE id=?", String.class, id));
    }

    @Test
    void keepsReceiptIdentityAndRevisionBytesWithoutJsonReencoding() {
        repository.insertReceipt(scope, "actor", "operation", "hash", body);
        assertEquals(
                new PresalesProjectRepository.OperationReceipt("hash", body),
                repository.findReceipt(scope, "actor", "operation").orElseThrow());
        assertTrue(repository.findReceipt("other", "actor", "operation").isEmpty());
        assertTrue(repository.findReceipt(scope, "other", "operation").isEmpty());
        assertTrue(repository.findReceipt(scope, "actor", "other").isEmpty());
        LocalDateTime time = LocalDateTime.of(2026, 10, 2, 0, 0);
        repository.insertRevision(id, 7, "actor", "UPDATE_PROJECT", body, time);
        assertEquals(
                body,
                jdbc.queryForObject(
                        "SELECT body_json FROM mate_presales_revision WHERE project_id=? AND version=?",
                        String.class,
                        id,
                        7));
        assertEquals(
                time,
                jdbc.queryForObject(
                        "SELECT created_at FROM mate_presales_revision WHERE project_id=? AND version=?",
                        LocalDateTime.class,
                        id,
                        7));
    }

    @Test
    void participatesInTheCallerTransactionWithoutIndependentCommits() {
        var failure = new IllegalStateException("rollback marker");
        assertSame(
                failure,
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                transaction.execute(
                                        status -> {
                                            repository.insert(row(id, scope, 1, "B", body));
                                            repository.insertRevision(
                                                    id,
                                                    1,
                                                    "actor",
                                                    "CREATE",
                                                    body,
                                                    LocalDateTime.now());
                                            repository.insertReceipt(
                                                    scope, "actor", "operation", "hash", body);
                                            throw failure;
                                        })));
        assertTrue(repository.findBody(scope, id, false).isEmpty());
        assertTrue(repository.findReceipt(scope, "actor", "operation").isEmpty());
        assertEquals(
                0,
                jdbc.queryForObject("SELECT COUNT(*) FROM mate_presales_revision", Integer.class));
    }

    @Test
    void lockedReadsHoldTheRowUntilTheCallerTransactionEnds() throws Exception {
        repository.insert(row(id, scope, 1, "B", body));
        var started = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var writer = new java.util.concurrent.atomic.AtomicReference<Future<Integer>>();
            transaction.execute(
                    status -> {
                        assertEquals(body, repository.findBody(scope, id, true).orElseThrow());
                        writer.set(
                                executor.submit(
                                        () ->
                                                transaction.execute(
                                                        other -> {
                                                            started.countDown();
                                                            return repository.update(
                                                                    row(
                                                                            id, scope, 2, "Changed",
                                                                            "new"),
                                                                    1);
                                                        })));
                        try {
                            assertTrue(started.await(2, TimeUnit.SECONDS));
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(interrupted);
                        }
                        assertThrows(
                                TimeoutException.class,
                                () -> writer.get().get(200, TimeUnit.MILLISECONDS));
                        return null;
                    });
            assertEquals(1, writer.get().get(3, TimeUnit.SECONDS));
            assertEquals("new", repository.findBody(scope, id, false).orElseThrow());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
        }
    }
}

package vip.mate.presales;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDateTime;
import java.util.UUID;
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

class PresalesObjectStorageTest {
    private final ObjectMapper json = new ObjectMapper();
    private JdbcTemplate jdbc;
    private PresalesProjectRepository repository;
    private TransactionTemplate tx;

    @BeforeEach
    void database() {
        var source = new org.h2.jdbcx.JdbcDataSource();
        source.setURL("jdbc:h2:mem:objects_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(
                        new ClassPathResource("db/migration/h2/V211__presales_projects.sql"),
                        new ClassPathResource(
                                "db/migration/h2/V217__presales_listing_projection.sql"),
                        new ClassPathResource("db/migration/h2/V223__presales_object_storage.sql"))
                .execute(source);
        jdbc = new JdbcTemplate(source);
        repository = new PresalesProjectRepository(jdbc);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
    }

    @AfterEach
    void close() throws Exception {
        try (var connection = jdbc.getDataSource().getConnection();
                var sql = connection.createStatement()) {
            sql.execute("SHUTDOWN");
        }
    }

    private ObjectNode project() throws Exception {
        return (ObjectNode)
                json.readTree(
                        """
                {"storageVersion":2,"id":"p","workspaceId":"w","version":1,"name":"项目","status":"ACTIVE",
                 "materials":[{"id":"m1","version":1,"text":"原始证据"},{"id":"m2","version":7,"text":"unchanged"}],
                 "requirements":[],"tasks":[],"solutions":[{"id":"s1","version":3,"title":"方案",
                   "sections":[{"title":"one","text":"section one"},{"title":"two","text":"section two"}]}]}
                """);
    }

    private ProjectRow row(ObjectNode p) {
        return new ProjectRow(
                "p",
                "w",
                p.path("version").longValue(),
                p.path("name").asText(),
                "ACTIVE",
                p.toString());
    }

    private void insert(ObjectNode p) {
        tx.executeWithoutResult(status -> repository.insert(row(p)));
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private ObjectNode read() throws Exception {
        return (ObjectNode) json.readTree(repository.findBody("w", "p", false).orElseThrow());
    }

    @Test
    void separatesObjectsAndSectionsAndAllReadPathsRoundTrip() throws Exception {
        var p = project();
        insert(p);
        assertEquals(p, read());
        assertEquals(p, json.readTree(repository.listBodies("w").getFirst()));
        assertEquals(p, json.readTree(repository.listRuntimeRows().getFirst().bodyJson()));
        assertEquals(
                p, json.readTree(repository.findRuntimeRow("w", "p").orElseThrow().bodyJson()));
        assertTrue(repository.findBody("other", "p", false).isEmpty());
        String metadata =
                jdbc.queryForObject("SELECT body_json FROM mate_presales_project", String.class);
        assertFalse(metadata.contains("原始证据"));
        assertFalse(metadata.contains("section one"));
        assertFalse(json.readTree(metadata).has("materials"));
        assertEquals(5, count("mate_presales_object"));
        assertEquals(5, count("mate_presales_object_revision"));
        String solution =
                jdbc.queryForObject(
                        "SELECT body_json FROM mate_presales_object_revision WHERE object_kind='solutions'",
                        String.class);
        assertFalse(solution.contains("section one"));
    }

    @Test
    void onlyChangedObjectAppendsStorageRevisionWithoutChangingPublicVersion() throws Exception {
        var p = project();
        insert(p);
        ((ObjectNode) p.path("materials").get(0))
                .put("text", "changed without business version bump");
        p.put("version", 2);
        tx.executeWithoutResult(status -> assertEquals(1, repository.update(row(p), 1)));
        assertEquals(6, count("mate_presales_object_revision"));
        assertEquals(1, read().path("materials").get(0).path("version").intValue());
        p.put("version", 3).put("name", "renamed");
        tx.executeWithoutResult(status -> assertEquals(1, repository.update(row(p), 2)));
        assertEquals(6, count("mate_presales_object_revision"));
    }

    @Test
    void sectionChangesAreIndependentAndReceiptsAndRevisionsStayPinned() throws Exception {
        var original = project();
        tx.executeWithoutResult(
                status -> {
                    repository.insert(row(original));
                    repository.insertRevision(
                            "p", 1, "a", "CREATE", original.toString(), LocalDateTime.now());
                    repository.insertReceipt("w", "a", "op", "hash", original.toString());
                });
        var changed = original.deepCopy();
        ((ObjectNode) changed.path("solutions").get(0).path("sections").get(0))
                .put("text", "new section");
        changed.put("version", 2);
        tx.executeWithoutResult(
                status ->
                        assertEquals(
                                1,
                                repository.updateRuntimeBody(
                                        "w", "p", 1, 2, changed.toString(), null)));
        assertEquals(changed, read());
        assertEquals(7, count("mate_presales_object_revision"));
        assertEquals(
                original,
                json.readTree(repository.findReceipt("w", "a", "op").orElseThrow().responseJson()));
        assertEquals(original, json.readTree(repository.findRevision("w", "p", 1).orElseThrow()));
        assertFalse(
                jdbc.queryForObject(
                                "SELECT response_json FROM mate_presales_operation", String.class)
                        .contains("section one"));
        assertFalse(
                jdbc.queryForObject("SELECT body_json FROM mate_presales_revision", String.class)
                        .contains("原始证据"));
    }

    @Test
    void staleCasNeverAppendsObjectsAndMissingPinnedRevisionFailsClosed() throws Exception {
        var p = project();
        insert(p);
        p.put("version", 2);
        ((ObjectNode) p.path("materials").get(0)).put("text", "stale");
        tx.executeWithoutResult(status -> assertEquals(0, repository.update(row(p), 0)));
        assertEquals(5, count("mate_presales_object_revision"));
        assertEquals("原始证据", read().path("materials").get(0).path("text").asText());
        jdbc.update(
                "DELETE FROM mate_presales_object_revision WHERE object_kind='materials' AND object_id='m1'");
        assertThrows(IllegalStateException.class, () -> repository.findBody("w", "p", false));
    }

    @Test
    void rollbackCoversMetadataCurrentRevisionAndReceipt() throws Exception {
        var original = project();
        insert(original);
        var changed = original.deepCopy();
        changed.put("version", 2);
        ((ObjectNode) changed.path("materials").get(0)).put("text", "rollback");
        assertThrows(
                IllegalStateException.class,
                () ->
                        tx.executeWithoutResult(
                                status -> {
                                    assertEquals(1, repository.update(row(changed), 1));
                                    repository.insertRevision(
                                            "p",
                                            2,
                                            "a",
                                            "SAVE",
                                            changed.toString(),
                                            LocalDateTime.now());
                                    repository.insertReceipt(
                                            "w", "a", "op", "hash", changed.toString());
                                    throw new IllegalStateException("rollback");
                                }));
        assertEquals(original, read());
        assertEquals(5, count("mate_presales_object_revision"));
        assertEquals(0, count("mate_presales_revision"));
        assertEquals(0, count("mate_presales_operation"));
        assertThrows(IllegalStateException.class, () -> repository.update(row(changed), 1));
    }

    @Test
    void concurrentCasWritersCannotOverwriteOrLeaveOrphanRevisions() throws Exception {
        var original = project();
        insert(original);
        var first = original.deepCopy().put("version", 2);
        var second = original.deepCopy().put("version", 2);
        ((ObjectNode) first.path("materials").get(0)).put("text", "first");
        ((ObjectNode) second.path("materials").get(0)).put("text", "second");
        var start = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var a =
                    pool.submit(
                            () -> {
                                start.await();
                                return tx.execute(status -> repository.update(row(first), 1));
                            });
            var b =
                    pool.submit(
                            () -> {
                                start.await();
                                return tx.execute(status -> repository.update(row(second), 1));
                            });
            start.countDown();
            int acceptedA = a.get(5, java.util.concurrent.TimeUnit.SECONDS);
            int acceptedB = b.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(1, acceptedA + acceptedB);
            assertEquals(acceptedA == 1 ? first : second, read());
            assertEquals(6, count("mate_presales_object_revision"));
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS));
        }
    }

    @Test
    void removalAndReadditionKeepHistoricalRefsWithoutReusingRevisionNumbers() throws Exception {
        var original = project();
        tx.executeWithoutResult(
                status -> {
                    repository.insert(row(original));
                    repository.insertReceipt("w", "a", "op", "hash", original.toString());
                });
        var changed = original.deepCopy().put("version", 2);
        changed.withArray("materials").remove(0);
        tx.executeWithoutResult(status -> assertEquals(1, repository.update(row(changed), 1)));
        assertEquals(4, count("mate_presales_object"));
        assertEquals(
                original,
                json.readTree(repository.findReceipt("w", "a", "op").orElseThrow().responseJson()));
        changed.withArray("materials").insert(0, original.path("materials").get(0).deepCopy());
        changed.put("version", 3);
        tx.executeWithoutResult(status -> assertEquals(1, repository.update(row(changed), 2)));
        assertEquals(
                2L,
                jdbc.queryForObject(
                        "SELECT storage_revision FROM mate_presales_object WHERE object_kind='materials' AND object_id='m1'",
                        Long.class));
        assertEquals(changed, read());
        var downgraded = changed.deepCopy();
        downgraded.remove("storageVersion");
        assertThrows(
                IllegalStateException.class,
                () -> tx.executeWithoutResult(status -> repository.update(row(downgraded), 3)));
        assertEquals(changed, read());
    }

    @Test
    void invalidInsertRollsBackAllObjectsAndCorruptAggregateMirrorNeverFallsBack()
            throws Exception {
        var p = project();
        p.withArray("materials").add(p.path("materials").get(0).deepCopy());
        assertThrows(IllegalStateException.class, () -> insert(p));
        assertEquals(0, count("mate_presales_project"));
        assertEquals(0, count("mate_presales_object_revision"));
        assertEquals(0, count("mate_presales_object"));
        var valid = project();
        insert(valid);
        jdbc.update("UPDATE mate_presales_project SET body_json=? WHERE id='p'", valid.toString());
        assertThrows(IllegalStateException.class, () -> repository.findBody("w", "p", false));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"w", "other"})
    void queryIdentityCannotBeReplacedByCorruptSnapshotIdentity(String foreignScope)
            throws Exception {
        var original = project();
        var foreign = project().put("id", "foreign").put("workspaceId", foreignScope);
        tx.executeWithoutResult(
                status -> {
                    repository.insert(row(original));
                    repository.insertReceipt("w", "a", "op", "hash", original.toString());
                    repository.insert(
                            new ProjectRow(
                                    "foreign",
                                    foreignScope,
                                    1,
                                    "foreign",
                                    "ACTIVE",
                                    foreign.toString()));
                });
        String foreignManifest =
                jdbc.queryForObject(
                        "SELECT body_json FROM mate_presales_project WHERE id='foreign'",
                        String.class);
        jdbc.update("UPDATE mate_presales_project SET body_json=? WHERE id='p'", foreignManifest);
        assertThrows(IllegalStateException.class, () -> repository.findBody("w", "p", false));
        assertThrows(IllegalStateException.class, () -> repository.listBodies("w"));
        assertThrows(IllegalStateException.class, () -> repository.findRuntimeRow("w", "p"));
        if (!"w".equals(foreignScope)) {
            jdbc.update(
                    "UPDATE mate_presales_operation SET response_json=? WHERE operation_id='op'",
                    foreignManifest);
            assertThrows(IllegalStateException.class, () -> repository.findReceipt("w", "a", "op"));
        }
    }

    private PresalesProjectRepository runtimeRepository() {
        var proxy = new org.springframework.aop.framework.ProxyFactory(repository);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(
                new org.springframework.transaction.interceptor.TransactionInterceptor(
                        new DataSourceTransactionManager(jdbc.getDataSource()),
                        new org.springframework.transaction.annotation
                                .AnnotationTransactionAttributeSource()));
        return (PresalesProjectRepository) proxy.getProxy();
    }

    @Test
    void backgroundRecoveryOwnsShortTransactionForV2ObjectsWithoutCallerTransaction()
            throws Exception {
        var p = project();
        p.withArray("tasks")
                .addObject()
                .put("id", "t")
                .put("runId", "run")
                .put("status", "RUNNING")
                .put("queuedAt", "2000-01-01T00:00:00Z");
        insert(p);
        var runtime = runtimeRepository();
        var coordinator = new PresalesGenerationCoordinator(null, null, null, json, runtime, null);
        assertFalse(
                org.springframework.transaction.support.TransactionSynchronizationManager
                        .isActualTransactionActive());
        coordinator.recoverStaleTasks();
        var recovered = read();
        assertEquals("FAILED", recovered.path("tasks").get(0).path("status").asText());
        assertEquals(
                "INTERRUPTED_BY_RESTART", recovered.path("tasks").get(0).path("error").asText());
        assertEquals(2, recovered.path("version").asInt());
        assertEquals(p.path("materials"), recovered.path("materials"));
        assertEquals(p.path("solutions"), recovered.path("solutions"));
        assertEquals(7, count("mate_presales_object_revision"));
        assertFalse(
                org.springframework.transaction.support.TransactionSynchronizationManager
                        .isActualTransactionActive());
        coordinator.recoverStaleTasks();
        assertEquals(recovered, read());
        assertEquals(7, count("mate_presales_object_revision"));
    }

    @Test
    void actorLossFallbackOwnsShortTransactionAndPreservesConcurrentMetadataEdit()
            throws Exception {
        var p = project();
        var task =
                p.withArray("tasks")
                        .addObject()
                        .put("id", "t")
                        .put("runId", "run")
                        .put("status", "RUNNING")
                        .put("agentId", "employee")
                        .put("conversationId", "conversation");
        task.putObject("contextSnapshot").put("projectVersion", 1);
        insert(p);
        var runtime = runtimeRepository();
        var service = org.mockito.Mockito.mock(PresalesService.class);
        var model = org.mockito.Mockito.mock(PresalesEmployeeRuntime.class);
        var contexts = org.mockito.Mockito.mock(PresalesContextProvider.class);
        var lost = new java.util.concurrent.atomic.AtomicBoolean();
        org.mockito.Mockito.when(service.get("w", "p"))
                .thenAnswer(
                        call -> {
                            if (lost.get())
                                throw new vip.mate.semantic.web.SemanticApiException(
                                        403, "ACTOR_DISABLED", "actor lost");
                            return read();
                        });
        org.mockito.Mockito.when(
                        service.find(
                                org.mockito.ArgumentMatchers.any(),
                                org.mockito.ArgumentMatchers.eq("tasks"),
                                org.mockito.ArgumentMatchers.eq("t")))
                .thenAnswer(
                        call ->
                                (ObjectNode)
                                        ((ObjectNode) call.getArgument(0)).path("tasks").get(0));
        org.mockito.Mockito.when(
                        model.instructionsForTask(
                                org.mockito.ArgumentMatchers.anyString(),
                                org.mockito.ArgumentMatchers.anyString(),
                                org.mockito.ArgumentMatchers.any(),
                                org.mockito.ArgumentMatchers.any()))
                .thenReturn("instructions");
        org.mockito.Mockito.when(
                        model.execute(
                                org.mockito.ArgumentMatchers.anyString(),
                                org.mockito.ArgumentMatchers.anyString(),
                                org.mockito.ArgumentMatchers.anyString(),
                                org.mockito.ArgumentMatchers.anyString(),
                                org.mockito.ArgumentMatchers.anyString(),
                                org.mockito.ArgumentMatchers.any(),
                                org.mockito.ArgumentMatchers.any()))
                .thenAnswer(
                        call -> {
                            assertFalse(
                                    org.springframework.transaction.support
                                            .TransactionSynchronizationManager
                                            .isActualTransactionActive());
                            var current =
                                    read().put("version", 2).put("goal", "concurrent metadata");
                            assertEquals(
                                    1,
                                    runtime.updateRuntimeBody(
                                            "w", "p", 1, 2, current.toString(), null));
                            lost.set(true);
                            throw new vip.mate.semantic.web.SemanticApiException(
                                    422, "MODEL_FORMAT", "invalid output");
                        });
        var coordinator =
                new PresalesGenerationCoordinator(service, contexts, model, json, runtime, null);
        coordinator.process(
                new PresalesGenerationCoordinator.Submission(
                        "w",
                        "actor",
                        "p",
                        "op",
                        "S1",
                        "goal",
                        task.deepCopy(),
                        (ObjectNode) task.path("contextSnapshot").deepCopy(),
                        1));
        var failed = read();
        assertEquals("FAILED", failed.path("tasks").get(0).path("status").asText());
        assertEquals(
                "TERMINAL_PERSISTENCE_FAILED", failed.path("tasks").get(0).path("error").asText());
        assertFalse(failed.path("tasks").get(0).has("result"));
        assertEquals("concurrent metadata", failed.path("goal").asText());
        assertEquals(3, failed.path("version").asInt());
        assertEquals(p.path("materials"), failed.path("materials"));
        assertEquals(p.path("solutions"), failed.path("solutions"));
        assertEquals(7, count("mate_presales_object_revision"));
        assertFalse(
                org.springframework.transaction.support.TransactionSynchronizationManager
                        .isActualTransactionActive());
        org.mockito.Mockito.verify(service, org.mockito.Mockito.never())
                .saveEmployeeTask(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any());
    }

    @Test
    void runtimeTransactionRollsBackObjectsOnMetadataFailureAndJoinsExistingTransaction()
            throws Exception {
        var p = project();
        insert(p);
        var changed = p.deepCopy().put("version", 2).put("goal", "reject-runtime");
        ((ObjectNode) changed.path("materials").get(0)).put("text", "must roll back");
        var runtime = runtimeRepository();
        jdbc.execute(
                "ALTER TABLE mate_presales_project ADD CONSTRAINT reject_runtime CHECK (body_json NOT LIKE '%reject-runtime%')");
        assertThrows(
                org.springframework.dao.DataIntegrityViolationException.class,
                () -> runtime.updateRuntimeBody("w", "p", 1, 2, changed.toString(), null));
        assertEquals(p, read());
        assertEquals(5, count("mate_presales_object_revision"));
        jdbc.execute("ALTER TABLE mate_presales_project DROP CONSTRAINT reject_runtime");
        assertThrows(
                IllegalStateException.class,
                () ->
                        tx.executeWithoutResult(
                                status -> {
                                    assertEquals(
                                            1,
                                            runtime.updateRuntimeBody(
                                                    "w", "p", 1, 2, changed.toString(), null));
                                    throw new IllegalStateException("outer rollback");
                                }));
        assertEquals(p, read());
        assertEquals(5, count("mate_presales_object_revision"));
    }
}

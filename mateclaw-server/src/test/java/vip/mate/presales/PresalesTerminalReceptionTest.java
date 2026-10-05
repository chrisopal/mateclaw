package vip.mate.presales;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import vip.mate.presales.repository.PresalesProjectRepository;
import vip.mate.semantic.web.SemanticApiException;

/** Actual H2 fallback/recovery writes; deterministic external model and actor boundaries. */
class PresalesTerminalReceptionTest {
    private final ObjectMapper json = new ObjectMapper();
    private JdbcTemplate storage;
    private ObjectNode accepted;
    private PresalesService service;
    private PresalesEmployeeRuntime model;
    private PresalesContextProvider contexts;
    private String otherWorkspaceBody;
    private AtomicBoolean actorLost;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void directWritersRejectBodyVersionMismatchWithoutRepairingIt(boolean recovery)
            throws Exception {
        for (String raw : new String[] {"4294967298", "2.5", "null", "3"}) {
            var p = accepted.deepCopy();
            p.set("version", json.readTree(raw));
            storage.update(
                    "UPDATE mate_presales_project SET version=2,body_json=? WHERE id='p' AND workspace_id='w'",
                    json.writeValueAsString(p));
            actorLost.set(false);
            String before = body();
            if (recovery) coordinator(storage).recoverStaleTasks();
            else runActorLossFallback();
            assertEquals(before, body());
            assertEquals(
                    2,
                    storage.queryForObject(
                            "SELECT version FROM mate_presales_project WHERE id='p' AND workspace_id='w'",
                            Integer.class));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {Integer.MAX_VALUE, 0, -1})
    void actorLossFallbackNeverWrapsOrRepairsInvalidVersion(int version) throws Exception {
        store(current().put("version", version));
        String before = body();
        runActorLossFallback();
        assertEquals(before, body());
        assertEquals(
                version,
                storage.queryForObject(
                        "SELECT version FROM mate_presales_project WHERE id='p' AND workspace_id='w'",
                        Integer.class));
    }

    @ParameterizedTest
    @ValueSource(ints = {Integer.MAX_VALUE, 0, -1})
    void recoverySkipsExhaustedOrInvalidVersionAndContinuesOtherProjects(int version)
            throws Exception {
        store(current().put("version", version));
        String before = body();
        var healthy = accepted.deepCopy().put("id", "healthy");
        storage.update(
                "INSERT INTO mate_presales_project(id,workspace_id,version,name,status,body_json) VALUES(?,?,?,?,?,?)",
                "healthy",
                "w",
                2,
                "healthy",
                "ACTIVE",
                json.writeValueAsString(healthy));
        assertDoesNotThrow(() -> coordinator(storage).recoverStaleTasks());
        assertEquals(before, body());
        assertEquals(
                version,
                storage.queryForObject(
                        "SELECT version FROM mate_presales_project WHERE id='p' AND workspace_id='w'",
                        Integer.class));
        var restored =
                json.readTree(
                        storage.queryForObject(
                                "SELECT body_json FROM mate_presales_project WHERE id='healthy' AND workspace_id='w'",
                                String.class));
        assertEquals(3, restored.path("version").intValue());
        assertEquals("FAILED", restored.path("tasks").get(0).path("status").asText());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void lastRuntimeVersionCanStillPersistTerminalState(boolean recovery) throws Exception {
        store(current().put("version", Integer.MAX_VALUE - 1));
        if (recovery) coordinator(storage).recoverStaleTasks();
        else runActorLossFallback();
        assertEquals(Integer.MAX_VALUE, current().path("version").intValue());
        assertEquals("FAILED", current().path("tasks").get(0).path("status").asText());
        assertRuntimeListing();
    }

    private void runActorLossFallback() throws Exception {
        loseActorAfterModelStarts();
        doAnswer(
                        call -> {
                            actorLost.set(true);
                            throw new SemanticApiException(422, "MODEL_FORMAT", "invalid result");
                        })
                .when(model)
                .execute(
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        any(),
                        any());
        coordinator(storage).process(submission());
        verify(service, never()).saveEmployeeTask(anyString(), anyString(), any());
    }

    @BeforeEach
    void prepare() throws Exception {
        var source =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:terminal_"
                                + UUID.randomUUID()
                                + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        storage = new JdbcTemplate(source);
        storage.execute(
                "CREATE TABLE mate_presales_project(id VARCHAR(100),workspace_id VARCHAR(100),"
                        + "version INT,name VARCHAR(100),status VARCHAR(100),body_json CLOB,PRIMARY KEY(id,workspace_id))");
        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
                        new org.springframework.core.io.ClassPathResource(
                                "db/migration/h2/V217__presales_listing_projection.sql"))
                .execute(source);
        accepted =
                (ObjectNode)
                        json.readTree(
                                """
                {"id":"p","version":2,"name":"original","status":"ACTIVE",
                 "unknown":{"keep":[1,2]},"requirements":[{"id":"r","title":"keep"}],
                 "tasks":[{"id":"t","runId":"run-old","operationId":"op-old","status":"RUNNING",
                 "contextSnapshot":{"projectVersion":2,"sourceRefs":[{"id":"source-old"}]},
                 "extension":{"unchanged":true}}]}
                """);
        storage.update(
                "INSERT INTO mate_presales_project(id,workspace_id,version,name,status,body_json) VALUES(?,?,?,?,?,?)",
                "p",
                "w",
                2,
                "original",
                "ACTIVE",
                json.writeValueAsString(accepted));
        var other = accepted.deepCopy().put("version", 7);
        ((ObjectNode) other.path("tasks").get(0))
                .put("status", "SUCCEEDED")
                .put("runId", "other-run");
        otherWorkspaceBody = json.writeValueAsString(other);
        storage.update(
                "INSERT INTO mate_presales_project(id,workspace_id,version,name,status,body_json) VALUES(?,?,?,?,?,?)",
                "p",
                "w-other",
                7,
                "other",
                "ACTIVE",
                otherWorkspaceBody);
        actorLost = new AtomicBoolean();
        service = mock(PresalesService.class);
        model = mock(PresalesEmployeeRuntime.class);
        contexts = mock(PresalesContextProvider.class);
        when(service.get("w", "p")).thenAnswer(call -> current());
        when(service.find(any(), eq("tasks"), eq("t")))
                .thenAnswer(
                        call ->
                                (ObjectNode)
                                        ((ObjectNode) call.getArgument(0)).path("tasks").get(0));
    }

    @AfterEach
    void otherWorkspaceRemainsByteIdentical() {
        assertEquals(
                otherWorkspaceBody,
                storage.queryForObject(
                        "SELECT body_json FROM mate_presales_project WHERE id='p' AND workspace_id='w-other'",
                        String.class));
        assertEquals(
                7,
                storage.queryForObject(
                        "SELECT version FROM mate_presales_project WHERE id='p' AND workspace_id='w-other'",
                        Integer.class));
    }

    private void assertRuntimeListing() throws Exception {
        var access = mock(PresalesAccess.class);
        when(access.require("w", "viewer")).thenReturn("viewer");
        var listingService =
                new PresalesProjectQueryService(
                        new PresalesProjectRepository(storage), json, access);
        var page = listingService.list("w", null, null, null, null, 1, 20);
        var summary = page.items().getFirst();
        assertEquals(1, page.total());
        assertEquals(current().path("version"), summary.path("version"));
        assertEquals(current().path("unknown"), summary.path("unknown"));
        assertFalse(summary.has("tasks"));
        assertFalse(summary.has("requirements"));
        assertEquals("REQUIREMENTS", summary.path("stage").asText());
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<PresalesPresentationHook> hooks() {
        return mock(ObjectProvider.class);
    }

    private PresalesGenerationCoordinator coordinator(JdbcTemplate jdbc) {
        return new PresalesGenerationCoordinator(
                service, contexts, model, json, new PresalesProjectRepository(jdbc), hooks());
    }

    private PresalesGenerationCoordinator.Submission submission() {
        var task = (ObjectNode) accepted.path("tasks").get(0).deepCopy();
        return new PresalesGenerationCoordinator.Submission(
                "w",
                "actor",
                "p",
                "op-old",
                "S1",
                "goal",
                task,
                (ObjectNode) task.path("contextSnapshot").deepCopy(),
                2);
    }

    private String body() {
        return storage.queryForObject(
                "SELECT body_json FROM mate_presales_project WHERE id='p' AND workspace_id='w'",
                String.class);
    }

    private ObjectNode current() throws Exception {
        return (ObjectNode) json.readTree(body());
    }

    private void store(ObjectNode project) throws Exception {
        storage.update(
                "UPDATE mate_presales_project SET body_json=?,version=? WHERE id='p' AND workspace_id='w'",
                json.writeValueAsString(project),
                project.path("version").asInt());
    }

    private void change(String kind) throws Exception {
        var changed = current();
        changed.put("version", 3);
        var task = (ObjectNode) changed.path("tasks").get(0);
        if ("REPLACED".equals(kind)) task.put("runId", "run-new");
        else if ("SNAPSHOT_CHANGED".equals(kind))
            ((ObjectNode) task.path("contextSnapshot")).put("projectVersion", 999);
        else task.put("status", kind);
        store(changed);
    }

    private void loseActorAfterModelStarts() throws Exception {
        doAnswer(
                        call -> {
                            if (actorLost.get())
                                throw new SemanticApiException(403, "ACTOR_DISABLED", "actor lost");
                            return current();
                        })
                .when(service)
                .get("w", "p");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"SUCCEEDED", "FAILED", "CANCELLED", "DRAFT", "REPLACED", "SNAPSHOT_CHANGED"})
    void lateFailureDoesNotSubmitTerminalOrReplacedRun(String kind) throws Exception {
        var before = new AtomicReference<String>();
        when(model.execute(
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        any(),
                        any()))
                .thenAnswer(
                        call -> {
                            change(kind);
                            before.set(body());
                            throw new SemanticApiException(422, "MODEL_FORMAT", "invalid result");
                        });
        coordinator(storage).process(submission());
        verify(service, never()).saveEmployeeTask(anyString(), anyString(), any());
        assertEquals(before.get(), body());
        verifyNoInteractions(contexts);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"SUCCEEDED", "FAILED", "CANCELLED", "DRAFT", "REPLACED", "SNAPSHOT_CHANGED"})
    void actorLossFallbackDoesNotOverwriteTerminalOrReplacedRun(String kind) throws Exception {
        loseActorAfterModelStarts();
        var before = new AtomicReference<String>();
        when(model.execute(
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        any(),
                        any()))
                .thenAnswer(
                        call -> {
                            actorLost.set(true);
                            change(kind);
                            before.set(body());
                            throw new SemanticApiException(422, "MODEL_FORMAT", "invalid result");
                        });
        coordinator(storage).process(submission());
        assertEquals(before.get(), body());
        assertEquals(
                3,
                storage.queryForObject(
                        "SELECT version FROM mate_presales_project WHERE id='p' AND workspace_id='w'",
                        Integer.class));
        verify(service, never()).saveEmployeeTask(anyString(), anyString(), any());
        verifyNoInteractions(contexts);
    }

    @Test
    void replacedRunBeforeStartNeverCallsModel() throws Exception {
        change("REPLACED");
        var before = body();
        coordinator(storage).process(submission());
        verifyNoInteractions(model, contexts);
        verify(service, never()).saveEmployeeTask(anyString(), anyString(), any());
        assertEquals(before, body());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void matchingRunningFallbackRetainsUnrelatedFieldsAndVersionDrift(boolean drift)
            throws Exception {
        loseActorAfterModelStarts();
        when(model.execute(
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        any(),
                        any()))
                .thenAnswer(
                        call -> {
                            actorLost.set(true);
                            if (drift) {
                                var changed = current();
                                changed.put("version", 3).put("goal", "new goal");
                                store(changed);
                            }
                            throw new SemanticApiException(422, "MODEL_FORMAT", "invalid result");
                        });
        coordinator(storage).process(submission());
        var saved = current();
        assertEquals(drift ? 4 : 3, saved.path("version").asInt());
        assertEquals("FAILED", saved.path("tasks").get(0).path("status").asText());
        assertEquals(
                drift ? "PROJECT_CHANGED_DURING_GENERATION" : "TERMINAL_PERSISTENCE_FAILED",
                saved.path("tasks").get(0).path("error").asText());
        assertTrue(saved.path("tasks").get(0).path("result").isMissingNode());
        assertEquals(accepted.path("requirements"), saved.path("requirements"));
        assertEquals(accepted.path("unknown"), saved.path("unknown"));
        assertEquals(
                accepted.path("tasks").get(0).path("extension"),
                saved.path("tasks").get(0).path("extension"));
        if (drift) assertEquals("new goal", saved.path("goal").asText());
        assertRuntimeListing();
        assertEquals(
                "original",
                storage.queryForObject(
                        "SELECT name FROM mate_presales_project WHERE id='p' AND workspace_id='w'",
                        String.class));
        verify(service, never()).saveEmployeeTask(anyString(), anyString(), any());
        verifyNoInteractions(contexts);
    }

    @Test
    void fallbackCasConflictRereadsReplacementWithoutSecondWrite() throws Exception {
        loseActorAfterModelStarts();
        when(model.execute(
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        any(),
                        any()))
                .thenAnswer(
                        call -> {
                            actorLost.set(true);
                            throw new SemanticApiException(422, "MODEL_FORMAT", "invalid result");
                        });
        var runtimeJdbc = spy(new JdbcTemplate(storage.getDataSource()));
        var conflict = new AtomicBoolean();
        var replacement = new AtomicReference<String>();
        doAnswer(
                        call -> {
                            if (conflict.compareAndSet(false, true)) {
                                change("REPLACED");
                                replacement.set(body());
                                return 0;
                            }
                            return call.callRealMethod();
                        })
                .when(runtimeJdbc)
                .update(
                        contains("UPDATE mate_presales_project SET body_json=?"),
                        any(Object[].class));
        coordinator(runtimeJdbc).process(submission());
        assertTrue(conflict.get());
        assertEquals(replacement.get(), body());
        assertEquals(3, current().path("version").asInt());
    }

    @Test
    void cancellationReservationAfterModelFailureStopsActorLossFallback() throws Exception {
        loseActorAfterModelStarts();
        var coordinator = coordinator(storage);
        var before = body();
        when(model.execute(
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        any(),
                        any()))
                .thenAnswer(
                        call -> {
                            actorLost.set(true);
                            coordinator.requestCancellation("w", "p", "t");
                            throw new SemanticApiException(422, "MODEL_FORMAT", "invalid result");
                        });
        coordinator.process(submission());
        assertEquals(before, body());
        verify(service, never()).saveEmployeeTask(anyString(), anyString(), any());
    }

    @Test
    void cancellationReservationDuringFallbackCasStopsRetryWrite() throws Exception {
        loseActorAfterModelStarts();
        when(model.execute(
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        any(),
                        any()))
                .thenAnswer(
                        call -> {
                            actorLost.set(true);
                            throw new SemanticApiException(422, "MODEL_FORMAT", "invalid result");
                        });
        var runtimeJdbc = spy(new JdbcTemplate(storage.getDataSource()));
        var coordinator = coordinator(runtimeJdbc);
        var before = body();
        var conflict = new AtomicBoolean();
        doAnswer(
                        call -> {
                            if (conflict.compareAndSet(false, true)) {
                                coordinator.requestCancellation("w", "p", "t");
                                return 0;
                            }
                            return call.callRealMethod();
                        })
                .when(runtimeJdbc)
                .update(
                        contains("UPDATE mate_presales_project SET body_json=?"),
                        any(Object[].class));
        coordinator.process(submission());
        assertTrue(conflict.get());
        assertEquals(before, body());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void missingProjectOrTaskIsNeverResurrected(boolean missingProject) throws Exception {
        if (missingProject)
            storage.update("DELETE FROM mate_presales_project WHERE id='p' AND workspace_id='w'");
        else {
            var changed = current();
            changed.withArray("tasks").removeAll();
            store(changed);
        }
        doAnswer(
                        call -> {
                            if (storage.queryForObject(
                                            "SELECT COUNT(*) FROM mate_presales_project WHERE id='p' AND workspace_id='w'",
                                            Integer.class)
                                    == 0)
                                throw new SemanticApiException(404, "NOT_FOUND", "project missing");
                            return current();
                        })
                .when(service)
                .get("w", "p");
        doAnswer(
                        call -> {
                            var tasks = ((ObjectNode) call.getArgument(0)).path("tasks");
                            if (tasks.isEmpty())
                                throw new SemanticApiException(404, "NOT_FOUND", "task missing");
                            return (ObjectNode) tasks.get(0);
                        })
                .when(service)
                .find(any(), eq("tasks"), eq("t"));
        var before = missingProject ? null : body();
        assertDoesNotThrow(() -> coordinator(storage).process(submission()));
        verifyNoInteractions(model, contexts);
        verify(service, never()).saveEmployeeTask(anyString(), anyString(), any());
        if (missingProject)
            assertEquals(
                    0,
                    storage.queryForObject(
                            "SELECT COUNT(*) FROM mate_presales_project WHERE id='p' AND workspace_id='w'",
                            Integer.class));
        else assertEquals(before, body());
    }

    @Test
    void matchingFailureFallbackDiscardsPreexistingRunningResult() throws Exception {
        var changed = current();
        ((ObjectNode) changed.path("tasks").get(0))
                .set("result", json.createObjectNode().put("obsolete", true));
        store(changed);
        accepted = changed.deepCopy();
        loseActorAfterModelStarts();
        when(model.execute(
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        any(),
                        any()))
                .thenAnswer(
                        call -> {
                            actorLost.set(true);
                            throw new SemanticApiException(422, "MODEL_FORMAT", "invalid result");
                        });
        coordinator(storage).process(submission());
        assertEquals("FAILED", current().path("tasks").get(0).path("status").asText());
        assertTrue(current().path("tasks").get(0).path("result").isMissingNode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"EDIT", "CANCELLED", "SUCCEEDED", "REPLACED", "DELETED"})
    void restartRecoveryRechecksCurrentStateAfterConcurrentEdit(String change) throws Exception {
        var projects = spy(new PresalesProjectRepository(storage));
        var firstWrite = new AtomicBoolean(true);
        var concurrentBody = new AtomicReference<String>();
        doAnswer(
                        call -> {
                            if (firstWrite.getAndSet(false)) {
                                if ("DELETED".equals(change)) {
                                    storage.update(
                                            "DELETE FROM mate_presales_project WHERE id='p' AND workspace_id='w'");
                                } else {
                                    var latest =
                                            current()
                                                    .put("version", 3)
                                                    .put("concurrentEdit", "retain");
                                    var task = (ObjectNode) latest.path("tasks").get(0);
                                    if ("REPLACED".equals(change)) {
                                        task.put("runId", "new-run")
                                                .put("operationId", "new-op")
                                                .put(
                                                        "queuedAt",
                                                        java.time.Instant.now()
                                                                .plusSeconds(10)
                                                                .toString());
                                    } else if (!"EDIT".equals(change)) {
                                        task.put("status", change);
                                    }
                                    store(latest);
                                    concurrentBody.set(body());
                                }
                            }
                            return call.callRealMethod();
                        })
                .when(projects)
                .updateRuntimeBody(eq("w"), eq("p"), anyInt(), anyInt(), anyString(), any());
        var coordinator =
                new PresalesGenerationCoordinator(
                        service, contexts, model, json, projects, hooks());

        coordinator.recoverStaleTasks();

        if ("EDIT".equals(change)) {
            var recovered = current();
            assertEquals(4, recovered.path("version").intValue());
            assertEquals("retain", recovered.path("concurrentEdit").asText());
            assertEquals("FAILED", recovered.path("tasks").get(0).path("status").asText());
            assertEquals(
                    "INTERRUPTED_BY_RESTART",
                    recovered.path("tasks").get(0).path("error").asText());
            assertEquals(accepted.path("requirements"), recovered.path("requirements"));
            assertRuntimeListing();
        } else if ("DELETED".equals(change)) {
            assertTrue(projects.findRuntimeRow("w", "p").isEmpty());
        } else {
            assertEquals(concurrentBody.get(), body());
        }
        verify(projects, times("EDIT".equals(change) ? 2 : 1))
                .updateRuntimeBody(eq("w"), eq("p"), anyInt(), anyInt(), anyString(), any());
        verifyNoInteractions(model, contexts, service);
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 3})
    void recoveryRetriesAreBoundedAndDoNotBlockOtherProjects(int conflicts) throws Exception {
        var healthy = accepted.deepCopy().put("id", "healthy");
        storage.update(
                "INSERT INTO mate_presales_project(id,workspace_id,version,name,status,body_json) VALUES(?,?,?,?,?,?)",
                "healthy",
                "w",
                2,
                "healthy",
                "ACTIVE",
                json.writeValueAsString(healthy));
        var projects = spy(new PresalesProjectRepository(storage));
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(
                        call -> {
                            int attempt = attempts.incrementAndGet();
                            if (attempt <= conflicts) {
                                var latest = current();
                                latest.put("version", latest.path("version").intValue() + 1)
                                        .put("concurrentEdit", attempt);
                                store(latest);
                            }
                            return call.callRealMethod();
                        })
                .when(projects)
                .updateRuntimeBody(eq("w"), eq("p"), anyInt(), anyInt(), anyString(), any());
        new PresalesGenerationCoordinator(service, contexts, model, json, projects, hooks())
                .recoverStaleTasks();

        assertEquals(3, attempts.get());
        var latest = current();
        assertEquals(conflicts, latest.path("concurrentEdit").intValue());
        assertEquals(5, latest.path("version").intValue());
        assertEquals(
                conflicts == 2 ? "FAILED" : "RUNNING",
                latest.path("tasks").get(0).path("status").asText());
        var restored =
                json.readTree(projects.findRuntimeRow("w", "healthy").orElseThrow().bodyJson());
        assertEquals(3, restored.path("version").intValue());
        assertEquals("FAILED", restored.path("tasks").get(0).path("status").asText());
        verifyNoInteractions(model, contexts, service);
    }

    @Test
    void restartRecoveryUsesRealScopedCasAndRetainsOtherCollections() throws Exception {
        coordinator(storage).recoverStaleTasks();
        var recovered = current();
        assertEquals(3, recovered.path("version").asInt());
        assertEquals("FAILED", recovered.path("tasks").get(0).path("status").asText());
        assertEquals(
                "INTERRUPTED_BY_RESTART", recovered.path("tasks").get(0).path("error").asText());
        assertTrue(recovered.path("tasks").get(0).path("finishedAt").isTextual());
        assertEquals(accepted.path("requirements"), recovered.path("requirements"));
        assertEquals(accepted.path("unknown"), recovered.path("unknown"));
        assertRuntimeListing();
        assertEquals(
                "original",
                storage.queryForObject(
                        "SELECT name FROM mate_presales_project WHERE id='p' AND workspace_id='w'",
                        String.class));
        verifyNoInteractions(model, contexts, service);
    }
}

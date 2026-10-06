package vip.mate.presales;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import vip.mate.semantic.support.SemanticHttpFixture;

/** Public task-save authority, using real HTTP authentication, transactions, and H2 storage. */
@Import({
    PresalesAccess.class,
    PresalesService.class,
    vip.mate.presales.repository.PresalesRenderTaskRepository.class,
    vip.mate.presales.repository.PresalesProjectRepository.class,
    vip.mate.presales.repository.PresalesArtifactRepository.class,
    PresalesSourceAuthorization.class,
    vip.mate.wiki.service.WikiSourceReadService.class,
    vip.mate.wiki.repository.WikiSourceReadRepository.class,
    vip.mate.semantic.source.SourceGovernanceReadService.class,
    vip.mate.semantic.source.repository.SourceGovernanceReadRepository.class,
    PresalesController.class,
    PresalesSourceQueryService.class,
    PresalesProjectQueryService.class,
    PresalesGenerationController.class,
    PresalesGenerationService.class,
    PresalesExceptionHandler.class,
    PresalesArtifactRenderer.class,
    vip.mate.workspace.core.service.ProjectSourceAccess.class,
    vip.mate.workspace.core.service.ProjectAuthorityFence.class
})
@TestPropertySource(properties = "mateclaw.presales.enabled=true")
class PresalesTaskCreationAuthorityTest extends SemanticHttpFixture {
    @MockitoBean PresalesEmployeeRuntime model;
    @MockitoBean PresalesContextProvider contexts;
    @MockitoBean PresalesGenerationCoordinator coordinator;

    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    vip.mate.presales.repository.PresalesProjectRepository packages;

    @ParameterizedTest
    @ValueSource(strings = {"run", "pin", "queue", "package", "running"})
    void publicSaveCannotCreateServerExecutionDeclarations(String declaration) throws Exception {
        ObjectNode project = project();
        ObjectNode payload = manualDraft();
        switch (declaration) {
            case "run" -> {
                payload.put("runId", "client-run");
                payload.put("conversationId", "presales:client-conversation");
            }
            case "pin" -> {
                payload.put("modelConfigId", "17").put("configDigest", "client-config");
                payload.put("skillName", "presales-customer-context-analysis");
                payload.put("skillDigest", "client-skill");
                payload.put("presentationDigest", "client-presentation");
            }
            case "queue" -> {
                payload.put("queueState", "QUEUED").put("queuedAt", "2026-10-06T00:00:00Z");
                payload.put("operationId", "client-start").put("requestHash", "client-hash");
            }
            case "package" -> {
                // These untyped claims must never establish provenance for runtime bytes.
                payload.put("serverCreated", true);
                payload.putObject("skillPackage").put("SKILL.md", "client instructions");
            }
            case "running" -> payload.put("status", "RUNNING");
            default -> throw new AssertionError(declaration);
        }
        assertRejectedWithoutWrites(project, payload);
    }

    @Test
    void publicSaveCannotCloneAnActuallyQueuedEnvelopeAsANewTask() throws Exception {
        ObjectNode project = queuedProject();
        ObjectNode clone = task(project).deepCopy();
        clone.remove(List.of("id", "version", "authorId", "createdAt"));
        clearInvocations(model, contexts, coordinator);
        assertRejectedWithoutWrites(project, clone);
    }

    @ParameterizedTest
    @CsvSource({
        "generated,RUNNING",
        "generated,SUCCEEDED",
        "generated,FAILED",
        "generated,CANCELLED",
        "legacy,RUNNING",
        "legacy,SUCCEEDED",
        "legacy,FAILED",
        "legacy,CANCELLED"
    })
    void publicSaveCannotStripExistingExecutionIdentity(String origin, String status)
            throws Exception {
        ObjectNode project = queuedProject();
        ObjectNode existing = task(project);
        if (origin.equals("legacy")) {
            // A synthetic historical record lacks current pins/run id but still has its
            // original conversation, context, employee and submission identity.
            existing.remove(
                    List.of(
                            "runId",
                            "modelConfigId",
                            "configDigest",
                            "skillName",
                            "skillDigest",
                            "presentationDigest",
                            "queueState",
                            "queuedAt"));
            existing.put("legacyExtension", "preserve history");
        }
        existing.put("status", status);
        if (!status.equals("RUNNING")) existing.put("finishedAt", "2026-10-06T00:01:00Z");
        if (status.equals("SUCCEEDED")) existing.putObject("result").put("summary", "old result");
        persistHistoricalFixture(project);

        ObjectNode replacement = manualDraft().put("id", existing.path("id").asText());
        assertFalse(replacement.has("runId"));
        assertFalse(replacement.has("contextSnapshot"));
        clearInvocations(model, contexts, coordinator);
        assertRejectedWithoutWrites(project, replacement);
    }

    @Test
    void publicSaveCannotOverwriteAnExistingRunWhileRetainingTheOtherIdentityFields()
            throws Exception {
        ObjectNode project = queuedProject();
        ObjectNode replacement = task(project).deepCopy().put("runId", "replacement-run");
        replacement.put("skillDigest", "replacement-skill");
        clearInvocations(model, contexts, coordinator);
        assertRejectedWithoutWrites(project, replacement);
    }

    @Test
    void ordinaryManualDraftCanBeCreatedAndEditedAndRemainsUntrusted() throws Exception {
        ObjectNode project = project();
        ObjectNode draft = manualDraft();
        draft.putObject("result").put("summary", "manual analysis");
        ObjectNode untrustedContext =
                draft.putObject("contextSnapshot")
                        .put("workspaceId", workspace)
                        .put("caseRef", project.path("id").asText())
                        .put("note", "User-supplied context remains untrusted");
        String createOperation = operation();
        ObjectNode created = accepted(save(project, draft, createOperation));
        assertEquals(project.path("version").longValue() + 1, created.path("version").longValue());
        assertEquals("UNTRUSTED_DRAFT", task(created).path("authority").asText());
        assertEquals("manual analysis", task(created).path("result").path("summary").asText());
        assertEquals(untrustedContext, task(created).path("contextSnapshot"));
        assertStored(created, createOperation);

        ObjectNode edited = manualDraft().put("id", task(created).path("id").asText());
        edited.putObject("result").put("summary", "revised manual analysis");
        edited.set("contextSnapshot", untrustedContext.deepCopy());
        String editOperation = operation();
        ObjectNode saved = accepted(save(created, edited, editOperation));
        assertEquals(1, saved.path("tasks").size());
        assertEquals(task(created).path("id"), task(saved).path("id"));
        assertEquals(2, task(saved).path("version").intValue());
        assertEquals("DRAFT", task(saved).path("status").asText());
        assertEquals("UNTRUSTED_DRAFT", task(saved).path("authority").asText());
        assertEquals(
                "revised manual analysis", task(saved).path("result").path("summary").asText());
        assertEquals(untrustedContext, task(saved).path("contextSnapshot"));
        assertFalse(task(saved).has("runId"));
        assertFalse(task(saved).has("skillDigest"));
        assertStored(saved, editOperation);
        verifyNoInteractions(model, contexts, coordinator);
    }

    @Test
    void dedicatedCancelStillWorksAndRetainsTheQueuedExecutionIdentity() throws Exception {
        ObjectNode project = queuedProject();
        ObjectNode original = task(project).deepCopy();
        String projectId = project.path("id").asText();
        String taskId = original.path("id").asText();
        String operation = operation();
        clearInvocations(model, contexts, coordinator);
        ObjectNode cancelled =
                accepted(
                        postJson(
                                "/projects/" + projectId + "/tasks/" + taskId + "/cancel",
                                Map.of("operationId", operation)));
        assertEquals(
                project.path("version").longValue() + 1, cancelled.path("version").longValue());
        assertEquals("CANCELLED", task(cancelled).path("status").asText());
        ObjectNode expected = original.deepCopy().put("status", "CANCELLED");
        assertEquals(expected, task(cancelled));
        assertStored(cancelled, operation);
        verify(coordinator).requestCancellation(workspace, projectId, taskId);
        verifyNoMoreInteractions(coordinator);
        verifyNoInteractions(model, contexts);
    }

    @Test
    void generationPersistsOriginalPackageAndReplayDoesNotRecapture() throws Exception {
        ObjectNode project = queuedProject();
        ObjectNode task = task(project);
        String body =
                jdbc.queryForObject(
                        "SELECT body_json FROM mate_presales_task_package WHERE task_id=?",
                        String.class,
                        task.path("id").asText());
        var original = json.readValue(body, PresalesTaskPackage.class);
        assertEquals(PresalesTaskPackageFixtures.original("S1"), original);
        assertEquals(original.digest(), task.path("packageDigest").asText());
        assertEquals(original.skillDigest(), task.path("skillDigest").asText());
        clearInvocations(model, contexts, coordinator);
        var replay =
                accepted(
                        postJson(
                                "/projects/" + project.path("id").asText() + "/generate",
                                Map.of(
                                        "expectedVersion",
                                        project.path("version").longValue() - 1,
                                        "operationId",
                                        task.path("operationId").asText(),
                                        "skill",
                                        "S1",
                                        "taskGoal",
                                        "Analyze fixture")));
        assertEquals(project, replay);
        assertEquals(
                body,
                jdbc.queryForObject(
                        "SELECT body_json FROM mate_presales_task_package WHERE task_id=?",
                        String.class,
                        task.path("id").asText()));
        verifyNoInteractions(model, contexts, coordinator);
    }

    @Test
    void packageInsertionFailureRollsBackProjectRevisionReceiptAndDoesNotEnqueue()
            throws Exception {
        ObjectNode project = project();
        prepareQueue(project);
        String projectId = project.path("id").asText();
        var before = durableState(projectId);
        doThrow(new IllegalStateException("Synthetic package insert failure"))
                .when(packages)
                .insertTaskPackage(any());
        try {
            var response =
                    postJson(
                            "/projects/" + projectId + "/generate",
                            Map.of(
                                    "expectedVersion",
                                    project.path("version").longValue(),
                                    "operationId",
                                    operation(),
                                    "skill",
                                    "S1",
                                    "taskGoal",
                                    "Analyze fixture"));
            assertEquals(500, response.getStatus());
        } catch (jakarta.servlet.ServletException failed) {
            assertInstanceOf(IllegalStateException.class, failed.getCause());
        }
        assertEquals(before, durableState(projectId));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM mate_presales_task_package WHERE project_id=?",
                        Integer.class,
                        projectId));
        verify(packages).insertTaskPackage(any());
        verifyNoInteractions(coordinator);
    }

    @org.springframework.beans.factory.annotation.Autowired
    org.springframework.transaction.PlatformTransactionManager transactions;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void generationWaitsForOuterCommitAndSuppressesRollback(boolean rollback) throws Exception {
        ObjectNode project = project();
        prepareQueue(project);
        String projectId = project.path("id").asText();
        var before = durableState(projectId);
        doAnswer(
                        call -> {
                            assertNotNull(
                                    org.springframework.security.core.context.SecurityContextHolder
                                            .getContext()
                                            .getAuthentication());
                            assertEquals(
                                    1,
                                    jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM mate_presales_task_package WHERE project_id=?",
                                            Integer.class,
                                            projectId));
                            return null;
                        })
                .when(coordinator)
                .enqueue(any());
        new org.springframework.transaction.support.TransactionTemplate(transactions)
                .execute(
                        status -> {
                            try {
                                accepted(
                                        postJson(
                                                "/projects/" + projectId + "/generate",
                                                Map.of(
                                                        "expectedVersion",
                                                        project.path("version").longValue(),
                                                        "operationId",
                                                        operation(),
                                                        "skill",
                                                        "S1",
                                                        "taskGoal",
                                                        "Analyze fixture")));
                                verifyNoInteractions(coordinator);
                                if (rollback) status.setRollbackOnly();
                                return null;
                            } catch (Exception failure) {
                                throw new IllegalStateException(failure);
                            }
                        });
        if (rollback) {
            verifyNoInteractions(coordinator);
            assertEquals(before, durableState(projectId));
            assertEquals(
                    0,
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM mate_presales_task_package WHERE project_id=?",
                            Integer.class,
                            projectId));
        } else verify(coordinator).enqueue(any());
    }

    private ObjectNode project() throws Exception {
        return accepted(
                postJson(
                        "/projects",
                        Map.of(
                                "name",
                                "Task authority regression",
                                "customer",
                                "Synthetic fixture",
                                "expectedVersion",
                                0,
                                "operationId",
                                operation())));
    }

    private ObjectNode queuedProject() throws Exception {
        ObjectNode project = project();
        prepareQueue(project);
        ObjectNode queued =
                accepted(
                        postJson(
                                "/projects/" + project.path("id").asText() + "/generate",
                                Map.of(
                                        "expectedVersion",
                                        project.path("version").longValue(),
                                        "operationId",
                                        operation(),
                                        "skill",
                                        "S1",
                                        "taskGoal",
                                        "Analyze fixture")));
        assertEquals("RUNNING", task(queued).path("status").asText());
        assertFalse(task(queued).path("runId").asText().isBlank());
        assertEquals(
                queued.path("version"),
                task(queued).path("contextSnapshot").path("projectVersion"));
        verify(coordinator).enqueue(any(PresalesGenerationCoordinator.Submission.class));
        return queued;
    }

    private void prepareQueue(ObjectNode project) {
        var employee = new vip.mate.agent.model.AgentEntity();
        employee.setId(7L);
        employee.setName("Fixture employee");
        when(model.require(anyString(), anyString())).thenReturn(employee);
        when(model.capture(workspace, "7", "S1"))
                .thenReturn(PresalesTaskPackageFixtures.capture("S1", "17", "fixture-config"));
        when(contexts.snapshot(eq(workspace), any(), eq("S1"), anyString()))
                .thenAnswer(
                        call ->
                                json.createObjectNode()
                                        .put("workspaceId", workspace)
                                        .put("caseRef", project.path("id").asText())
                                        .put("actorId", project.path("createdBy").asText()));
    }

    private ObjectNode manualDraft() {
        return json.createObjectNode()
                .put("skill", "S1")
                .put("status", "DRAFT")
                .put("taskGoal", "Manual draft");
    }

    private ObjectNode task(ObjectNode project) {
        assertEquals(1, project.path("tasks").size());
        return (ObjectNode) project.path("tasks").get(0);
    }

    private void persistHistoricalFixture(ObjectNode project) {
        // Fixture-only import: do not bootstrap protected history through the vulnerable API.
        assertEquals(
                1,
                jdbc.update(
                        "UPDATE mate_presales_project SET body_json=? WHERE id=? AND workspace_id=?",
                        project.toString(),
                        project.path("id").asText(),
                        workspace));
    }

    private void assertRejectedWithoutWrites(ObjectNode project, ObjectNode payload)
            throws Exception {
        String id = project.path("id").asText();
        DurableState before = durableState(id);
        String operation = operation();
        MockHttpServletResponse response = save(project, payload, operation);
        DurableState after = durableState(id);
        assertAll(
                () ->
                        assertTrue(
                                response.getStatus() == 400 || response.getStatus() == 409,
                                "Public SAVE_AI_TASK must reject authority claims; actual HTTP "
                                        + response.getStatus()),
                () ->
                        assertEquals(
                                before,
                                after,
                                "Rejected save must not change project/version/revisions/receipts"),
                () ->
                        assertEquals(
                                0,
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM mate_presales_operation WHERE workspace_id=? AND operation_id=?",
                                        Integer.class,
                                        workspace,
                                        operation)),
                () -> verifyNoInteractions(model, contexts, coordinator));
    }

    private MockHttpServletResponse save(ObjectNode project, ObjectNode payload, String operation)
            throws Exception {
        return postJson(
                "/projects/" + project.path("id").asText() + "/commands",
                Map.of(
                        "expectedVersion",
                        project.path("version").longValue(),
                        "operationId",
                        operation,
                        "action",
                        "SAVE_AI_TASK",
                        "payload",
                        payload));
    }

    private MockHttpServletResponse postJson(String path, Object body) throws Exception {
        ObjectNode request = json.valueToTree(body);
        // Host Long serialization is string-based; the public version contract requires JSON
        // numbers.
        if (body instanceof Map<?, ?> fields
                && fields.get("expectedVersion") instanceof Number version)
            request.put("expectedVersion", version.longValue());
        return mvc.perform(
                        post("/api/v1/presales" + path)
                                .header("Authorization", tokens.get("member"))
                                .header("X-Workspace-Id", workspace)
                                .contentType("application/json")
                                .content(json.writeValueAsBytes(request)))
                .andReturn()
                .getResponse();
    }

    private ObjectNode accepted(MockHttpServletResponse response) throws Exception {
        assertEquals(200, response.getStatus());
        return (ObjectNode) json.readTree(response.getContentAsString()).path("data");
    }

    private record DurableState(
            String body, Long version, List<String> revisions, List<String> receipts) {}

    private DurableState durableState(String id) {
        return new DurableState(
                jdbc.queryForObject(
                        "SELECT body_json FROM mate_presales_project WHERE id=? AND workspace_id=?",
                        String.class,
                        id,
                        workspace),
                jdbc.queryForObject(
                        "SELECT version FROM mate_presales_project WHERE id=? AND workspace_id=?",
                        Long.class,
                        id,
                        workspace),
                jdbc.queryForList(
                        "SELECT body_json FROM mate_presales_revision WHERE project_id=? ORDER BY version",
                        String.class,
                        id),
                jdbc.queryForList(
                        "SELECT response_json FROM mate_presales_operation WHERE workspace_id=? ORDER BY actor_id,operation_id",
                        String.class,
                        workspace));
    }

    private void assertStored(ObjectNode project, String operation) throws Exception {
        String id = project.path("id").asText();
        DurableState state = durableState(id);
        assertEquals(project, json.readTree(state.body()));
        assertEquals(project.path("version").longValue(), state.version().longValue());
        JsonNode revision =
                json.readTree(
                        jdbc.queryForObject(
                                "SELECT body_json FROM mate_presales_revision WHERE project_id=? AND version=?",
                                String.class,
                                id,
                                state.version()));
        JsonNode receipt =
                json.readTree(
                        jdbc.queryForObject(
                                "SELECT response_json FROM mate_presales_operation WHERE workspace_id=? AND operation_id=?",
                                String.class,
                                workspace,
                                operation));
        assertEquals(project, revision);
        assertEquals(project, receipt);
    }

    private String operation() {
        return UUID.randomUUID().toString();
    }
}

package vip.mate.presales;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import vip.mate.semantic.support.SemanticHttpFixture;
import vip.mate.semantic.web.SemanticApiException;

@Import({
    PresalesAccess.class,
    PresalesService.class,
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
class PresalesVersionInputContractTest extends SemanticHttpFixture {
    @MockitoBean PresalesEmployeeRuntime model;
    @MockitoBean PresalesContextProvider contexts;
    @MockitoBean PresalesGenerationCoordinator coordinator;

    @ParameterizedTest
    @ValueSource(strings = {"Generate", "Generate?"})
    void generationIsCommittedBeforeEnqueueAndOriginalRequestReplaysWithoutAnotherRun(String goal)
            throws Exception {
        generationRoundTrip(goal, 1);
    }

    @ParameterizedTest
    @ValueSource(longs = {2147483646L, 2147483647L, 2147483648L, 9007199254740989L})
    void wideGenerationPreservesDurableTaskIdentity(long version) throws Exception {
        generationRoundTrip("Wide generation", version);
    }

    private void generationRoundTrip(String goal, long version) throws Exception {
        var p = (ObjectNode) project();
        seedVersion(p, version);
        String projectId = p.path("id").asText();
        String operation = UUID.randomUUID().toString();
        var body =
                json.createObjectNode()
                        .put("expectedVersion", version)
                        .put("operationId", operation)
                        .put("skill", "S1")
                        .put("taskGoal", goal);
        var employee = new vip.mate.agent.model.AgentEntity();
        employee.setId(7L);
        employee.setName("Employee");
        when(model.require(anyString(), anyString())).thenReturn(employee);
        when(model.pin(workspace, "7", "S1"))
                .thenReturn(
                        new PresalesEmployeeRuntime.Pin(
                                "17", "config", "presales-customer-context-analysis", "skill"));
        when(contexts.snapshot(eq(workspace), any(), eq("S1"), eq(goal)))
                .thenReturn(json.createObjectNode());
        doAnswer(
                        call -> {
                            assertFalse(
                                    org.springframework.transaction.support
                                            .TransactionSynchronizationManager
                                            .isActualTransactionActive());
                            PresalesGenerationCoordinator.Submission submission =
                                    call.getArgument(0);
                            assertEquals(projectId, submission.projectId());
                            // Independent connection proves durable visibility, not just the
                            // request's transaction.
                            var ds = java.util.Objects.requireNonNull(jdbc.getDataSource());
                            try (var connection = ds.getConnection();
                                    var query =
                                            connection.prepareStatement(
                                                    "SELECT version,body_json FROM mate_presales_project WHERE id=?")) {
                                query.setString(1, projectId);
                                try (var rows = query.executeQuery()) {
                                    assertTrue(rows.next());
                                    assertEquals(version + 1, rows.getLong(1));
                                    var task =
                                            json.readTree(rows.getString(2)).path("tasks").get(0);
                                    assertEquals(submission.task(), task);
                                    assertEquals(
                                            submission.snapshot(), task.path("contextSnapshot"));
                                    assertEquals(version + 1, submission.acceptedVersion());
                                    assertEquals(operation, task.path("operationId").asText());
                                    assertEquals("RUNNING", task.path("status").asText());
                                    assertEquals(
                                            version + 1,
                                            task.path("contextSnapshot")
                                                    .path("projectVersion")
                                                    .longValue());
                                }
                            }
                            assertEquals(
                                    1,
                                    jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM mate_presales_operation WHERE workspace_id=? AND operation_id=?",
                                            Integer.class,
                                            workspace,
                                            operation + ":start"));
                            return null;
                        })
                .when(coordinator)
                .enqueue(any());
        var accepted = api("POST", path(p) + "/generate", "member", body, 200).path("data");
        var persisted = facts();
        assertEquals(
                accepted, api("POST", path(p) + "/generate", "member", body, 200).path("data"));
        body.put("taskGoal", "Changed");
        error(api("POST", path(p) + "/generate", "member", body, 409), "OPERATION_CONFLICT");
        assertEquals(persisted, facts());
        verify(coordinator, times(1)).enqueue(any());
        verify(model, times(1)).require(anyString(), anyString());
    }

    @Test
    void historicalGenerationByteWriterHashWithQuestionMarkReplaysWithoutEnqueue()
            throws Exception {
        var p = (ObjectNode) project();
        var original =
                new PresalesDtos.Generate(1L, "historical-generation", "S1", "What is required?");
        String archivedHash =
                java.util.HexFormat.of()
                        .formatHex(
                                java.security.MessageDigest.getInstance("SHA-256")
                                        .digest(json.writeValueAsBytes(original)));
        p.put("version", 2);
        p.withArray("tasks")
                .addObject()
                .put("id", "historical-task")
                .put("operationId", original.operationId())
                .put("requestHash", archivedHash)
                .put("taskGoal", original.taskGoal())
                .put("status", "RUNNING");
        jdbc.update(
                "UPDATE mate_presales_project SET version=2,body_json=? WHERE id=?",
                json.writeValueAsString(p),
                p.path("id").asText());
        var body = (ObjectNode) json.valueToTree(original);
        var before = facts();
        assertEquals(p, api("POST", path(p) + "/generate", "member", body, 200).path("data"));
        assertEquals(before, facts());
        error(
                api(
                        "POST",
                        path(p) + "/generate",
                        "member",
                        body.deepCopy().put("taskGoal", "Different?"),
                        409),
                "OPERATION_CONFLICT");
        assertEquals(before, facts());
        verifyNoInteractions(coordinator, model, contexts);
    }

    @Test
    void projectWriteCrossesOldLimitAndReplaysTheExactReceipt() throws Exception {
        var p = (ObjectNode) project();
        seedVersion(p, Integer.MAX_VALUE);
        var body = commandBody().put("expectedVersion", Integer.MAX_VALUE);
        var saved = api("POST", path(p) + "/commands", "member", body, 200).path("data");
        assertEquals(2147483648L, saved.path("version").longValue());
        var after = facts();
        assertEquals(saved, api("POST", path(p) + "/commands", "member", body, 200).path("data"));
        assertEquals(after, facts());
        body.put("expectedVersion", 2147483648L).put("operationId", UUID.randomUUID().toString());
        var next = api("POST", path(p) + "/commands", "member", body, 200).path("data");
        assertEquals(2147483649L, next.path("version").longValue());
        assertEquals(
                2147483649L,
                jdbc.queryForObject(
                        "SELECT MAX(version) FROM mate_presales_revision WHERE project_id=?",
                        Long.class,
                        p.path("id").asText()));
    }

    @Test
    void lastProjectWriteAndReceiptReplayRemainValidButFurtherWritesAreRejected() throws Exception {
        var p = (ObjectNode) project();
        seedVersion(p, PresalesProjectRevision.MAX_VALUE - 1);
        var body = commandBody().put("expectedVersion", PresalesProjectRevision.MAX_VALUE - 1);
        var saved = api("POST", path(p) + "/commands", "member", body, 200).path("data");
        assertEquals(PresalesProjectRevision.MAX_VALUE, saved.path("version").longValue());
        var before = facts();
        assertEquals(saved, api("POST", path(p) + "/commands", "member", body, 200).path("data"));
        assertEquals(before, facts());
        body.put("expectedVersion", PresalesProjectRevision.MAX_VALUE)
                .put("operationId", UUID.randomUUID().toString());
        error(api("POST", path(p) + "/commands", "member", body, 409), "VERSION_EXHAUSTED");
        assertEquals(before, facts());
    }

    @ParameterizedTest
    @ValueSource(strings = {"4294967297", "1.5", "\"1.5\"", "true", "null"})
    void invalidStoredProjectVersionCannotAliasTheExpectedVersion(String raw) throws Exception {
        var p = (ObjectNode) project();
        p.set("version", json.readTree(raw));
        jdbc.update(
                "UPDATE mate_presales_project SET body_json=? WHERE id=?",
                p.toString(),
                p.path("id").asText());
        var before = facts();
        error(api("POST", path(p) + "/commands", "member", commandBody(), 409), "VERSION_CONFLICT");
        assertEquals(before, facts());
    }

    @ParameterizedTest
    @ValueSource(longs = {PresalesProjectRevision.MAX_VALUE, PresalesProjectRevision.MAX_VALUE - 1})
    void generationNeedsRoomForStartAndFinishBeforeCallingExecutionDependencies(long version)
            throws Exception {
        var p = (ObjectNode) project();
        seedVersion(p, version);
        when(model.require(anyString(), anyString()))
                .thenThrow(
                        new SemanticApiException(
                                409, "EMPLOYEE_UNAVAILABLE", "Must not reach execution"));
        var body =
                json.createObjectNode()
                        .put("expectedVersion", version)
                        .put("operationId", UUID.randomUUID().toString())
                        .put("skill", "S1")
                        .put("taskGoal", "Version limit");
        var before = facts();
        error(api("POST", path(p) + "/generate", "member", body, 409), "VERSION_EXHAUSTED");
        assertEquals(before, facts());
        verifyNoInteractions(model, contexts, coordinator);
    }

    @Test
    void itemExhaustionRollsBackMutableAndImmutableCommandsWithoutReceipts() throws Exception {
        for (String action : List.of("SAVE_REQUIREMENT", "SAVE_SOLUTION")) {
            var p = (ObjectNode) project();
            String collection = action.equals("SAVE_REQUIREMENT") ? "requirements" : "solutions";
            p.withArray(collection)
                    .addObject()
                    .put("id", "old")
                    .put("version", Integer.MAX_VALUE)
                    .put("title", "Original");
            seedVersion(p, 1);
            var body = commandBody().put("action", action);
            var value = body.putObject("payload").put("id", "old").put("title", "Changed");
            if (action.equals("SAVE_SOLUTION"))
                value.putArray("sections").addObject().put("title", "T").put("text", "Body");
            var before = facts();
            error(api("POST", path(p) + "/commands", "member", body, 409), "VERSION_EXHAUSTED");
            assertEquals(before, facts());
        }
    }

    private void seedVersion(ObjectNode p, long version) {
        p.put("version", version);
        jdbc.update(
                "UPDATE mate_presales_project SET version=?,body_json=? WHERE id=?",
                version,
                p.toString(),
                p.path("id").asText());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.5", "0.0", "\"0\"", "\"\"", "true", "{}", "[]", "9007199254740992"})
    void createRejectsNonIntegerVersionWithoutWrites(String raw) throws Exception {
        var body = createBody();
        body.set("expectedVersion", json.readTree(raw));
        var before = facts();
        malformed(api("POST", "/projects", "member", body, 400));
        assertEquals(before, facts());
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.5", "1.0", "\"1\"", "\"\"", "true", "{}", "[]", "9007199254740992"})
    void commandRejectsNonIntegerVersionWithoutWrites(String raw) throws Exception {
        var project = project();
        var body = commandBody();
        body.set("expectedVersion", json.readTree(raw));
        var before = facts();
        malformed(api("POST", path(project) + "/commands", "member", body, 400));
        assertEquals(before, facts());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "1.5",
                "1.0",
                "\"1\"",
                "\"\"",
                "true",
                "{}",
                "[]",
                "9007199254740992",
                "9223372036854775808",
                "-9007199254740992"
            })
    void patchRejectsNonIntegerAndWrappedVersionsWithoutWrites(String raw) throws Exception {
        var project = project();
        var body = patchBody();
        body.set("expectedVersion", json.readTree(raw));
        var before = facts();
        malformed(api("PATCH", path(project), "member", body, 400));
        assertEquals(before, facts());
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.5", "1.0", "\"1\"", "\"\"", "true", "{}", "[]", "9007199254740992"})
    void generationRejectsNonIntegerBeforeExecutionDependencies(String raw) throws Exception {
        var project = project();
        var body =
                json.createObjectNode()
                        .put("operationId", UUID.randomUUID().toString())
                        .put("skill", "S1")
                        .put("taskGoal", "Version contract");
        body.set("expectedVersion", json.readTree(raw));
        when(model.require(anyString(), anyString()))
                .thenThrow(
                        new SemanticApiException(
                                409, "EMPLOYEE_UNAVAILABLE", "Must not reach execution"));
        var before = facts();
        malformed(api("POST", path(project) + "/generate", "member", body, 400));
        assertEquals(before, facts());
        verifyNoInteractions(model, contexts, coordinator);
    }

    @Test
    void validCreateRetainsExactReplayAndRoleRejection() throws Exception {
        var body = createBody();
        var first = api("POST", "/projects", "member", body, 200).path("data");
        var before = facts();
        assertEquals(first, api("POST", "/projects", "member", body, 200).path("data"));
        assertEquals(before, facts());
        api("POST", "/projects", "viewer", createBody(), 403);
        assertEquals(before, facts());
    }

    @Test
    void validCommandAndPatchRetainReplayConflictAndOriginalPayloadHash() throws Exception {
        for (String method : List.of("POST", "PATCH")) {
            var project = project();
            String route = path(project) + (method.equals("POST") ? "/commands" : "");
            var body = method.equals("POST") ? commandBody() : patchBody();
            var originalCommand =
                    json.createObjectNode()
                            .put("expectedVersion", 1)
                            .put("operationId", body.path("operationId").asText())
                            .put("action", "UPDATE_PROJECT");
            originalCommand.set("payload", method.equals("POST") ? body.path("payload") : body);
            String expectedHash =
                    PresalesRequestHashV2.digest(
                            json.writeValueAsString(
                                    List.of(project.path("id").asText(), originalCommand)));
            var first = api(method, route, "member", body, 200).path("data");
            assertEquals(
                    expectedHash,
                    jdbc.queryForObject(
                            "SELECT request_hash FROM mate_presales_operation WHERE workspace_id=? AND operation_id=?",
                            String.class,
                            workspace,
                            body.path("operationId").asText()));
            assertEquals(2, first.path("version").asInt());
            assertEquals("Updated", first.path("name").asText());
            var before = facts();
            assertEquals(first, api(method, route, "member", body, 200).path("data"));
            assertEquals(before, facts());
            var changed = body.deepCopy();
            var payload = method.equals("POST") ? (ObjectNode) changed.path("payload") : changed;
            payload.putObject("opaqueExtension").put("value", "different");
            error(api(method, route, "member", changed, 409), "OPERATION_CONFLICT");
            body.put("operationId", UUID.randomUUID().toString());
            error(api(method, route, "member", body, 409), "VERSION_CONFLICT");
            body.put("expectedVersion", 2);
            api(method, route, "viewer", body, 403);
            assertEquals(before, facts());
        }
    }

    @Test
    void absentAndNullCommandVersionsStayRequiredAndNegativeIntegersStayConflicts()
            throws Exception {
        for (String method : List.of("POST", "PATCH")) {
            var project = project();
            String route = path(project) + (method.equals("POST") ? "/commands" : "");
            var body = method.equals("POST") ? commandBody() : patchBody();
            var before = facts();
            body.remove("expectedVersion");
            var missing = api(method, route, "member", body, 400);
            error(missing, "INVALID_REQUEST");
            assertEquals("expectedVersion required", missing.path("msg").asText());
            body.putNull("expectedVersion");
            var nil = api(method, route, "member", body, 400);
            assertEquals(missing, nil);
            body.put("expectedVersion", -1);
            error(api(method, route, "member", body, 409), "VERSION_CONFLICT");
            assertEquals(before, facts());
        }
    }

    @Test
    void legalDtoWireAndIntegerBoundsRemainExact() throws Exception {
        for (int version : List.of(0, 1, -1, Integer.MIN_VALUE, Integer.MAX_VALUE)) {
            String command =
                    "{\"expectedVersion\":"
                            + version
                            + ",\"operationId\":\"same\",\"action\":\"UPDATE_PROJECT\",\"payload\":{\"name\":\"N\",\"opaque\":null}}";
            assertEquals(
                    command,
                    json.writeValueAsString(json.readValue(command, PresalesDtos.Command.class)));
            String generate =
                    "{\"expectedVersion\":"
                            + version
                            + ",\"operationId\":\"same\",\"skill\":\"S1\",\"taskGoal\":\"G\"}";
            assertEquals(
                    generate,
                    json.writeValueAsString(json.readValue(generate, PresalesDtos.Generate.class)));
        }
        String create =
                "{\"name\":\"N\",\"customer\":\"C\",\"ownerId\":null,\"agentId\":null,\"industry\":null,\"goal\":null,\"expectedVersion\":0,\"operationId\":\"same\"}";
        assertEquals(
                create, json.writeValueAsString(json.readValue(create, PresalesDtos.Create.class)));
    }

    private ObjectNode createBody() {
        return json.createObjectNode()
                .put("name", "Version input")
                .put("customer", "C")
                .put("expectedVersion", 0)
                .put("operationId", UUID.randomUUID().toString());
    }

    private JsonNode project() throws Exception {
        return api("POST", "/projects", "member", createBody(), 200).path("data");
    }

    private String path(JsonNode project) {
        return "/projects/" + project.path("id").asText();
    }

    private ObjectNode patchBody() {
        var body =
                json.createObjectNode()
                        .put("expectedVersion", 1)
                        .put("operationId", UUID.randomUUID().toString())
                        .put("name", "Updated");
        body.putObject("opaqueExtension").put("value", "original");
        return body;
    }

    private ObjectNode commandBody() {
        var body =
                json.createObjectNode()
                        .put("expectedVersion", 1)
                        .put("operationId", UUID.randomUUID().toString())
                        .put("action", "UPDATE_PROJECT");
        var payload = body.putObject("payload").put("name", "Updated");
        payload.putObject("opaqueExtension").put("value", "original");
        return body;
    }

    private List<?> facts() {
        return List.of(
                jdbc.queryForList(
                        "SELECT id,version,body_json FROM mate_presales_project WHERE workspace_id=? ORDER BY id",
                        workspace),
                jdbc.queryForList(
                        "SELECT r.project_id,r.version,r.body_json FROM mate_presales_revision r JOIN mate_presales_project p ON p.id=r.project_id WHERE p.workspace_id=? ORDER BY r.project_id,r.version",
                        workspace),
                jdbc.queryForList(
                        "SELECT operation_id,request_hash,response_json FROM mate_presales_operation WHERE workspace_id=? ORDER BY operation_id",
                        workspace));
    }

    private JsonNode api(String method, String path, String role, ObjectNode body, int status)
            throws Exception {
        var request =
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(
                                org.springframework.http.HttpMethod.valueOf(method),
                                "/api/v1/presales" + path)
                        .header("Authorization", tokens.get(role))
                        .header("X-Workspace-Id", workspace)
                        .contentType("application/json")
                        .content(json.writeValueAsString(body));
        var response = mvc.perform(request).andReturn().getResponse();
        assertEquals(status, response.getStatus(), response.getContentAsString());
        return json.readTree(response.getContentAsString());
    }

    private void malformed(JsonNode response) {
        error(response, "INVALID_REQUEST");
        assertEquals("Malformed request", response.path("msg").asText());
    }

    private void error(JsonNode response, String code) {
        assertEquals(code, response.path("data").path("code").asText());
    }
}

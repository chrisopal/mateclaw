package vip.mate.presales;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import vip.mate.semantic.statement.StatementApplicationService;
import vip.mate.semantic.support.SemanticHttpFixture;

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
    PresalesExceptionHandler.class,
    PresalesArtifactRenderer.class,
    vip.mate.workspace.core.service.ProjectSourceAccess.class,
    vip.mate.workspace.core.service.ProjectAuthorityFence.class
})
@TestPropertySource(properties = "mateclaw.presales.enabled=true")
class PresalesRequestHashReplayTest extends SemanticHttpFixture {
    private JsonNode api(String method, String route, String role, ObjectNode body, int status)
            throws Exception {
        var request =
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(
                                org.springframework.http.HttpMethod.valueOf(method),
                                "/api/v1/presales" + route)
                        .header("Authorization", tokens.get(role))
                        .header("X-Workspace-Id", workspace)
                        .contentType("application/json;charset=UTF-16BE");
        // UTF-16 JSON uses Jackson's reader parser: UTF-8's field-name parser rejects isolated
        // surrogate property names before the Service. ASCII escapes preserve exact input units.
        if (body != null)
            request.content(
                    json.writer()
                            .with(com.fasterxml.jackson.core.json.JsonWriteFeature.ESCAPE_NON_ASCII)
                            .writeValueAsString(body)
                            .getBytes(java.nio.charset.StandardCharsets.UTF_16BE));
        var response = mvc.perform(request).andReturn().getResponse();
        assertEquals(status, response.getStatus(), response.getContentAsString());
        return json.readTree(response.getContentAsString());
    }

    private ObjectNode create(String value) {
        return json.createObjectNode()
                .put("name", value)
                .put("customer", "Customer")
                .put("expectedVersion", 0)
                .put("operationId", UUID.randomUUID().toString());
    }

    private ObjectNode project() throws Exception {
        return (ObjectNode) api("POST", "/projects", "member", create("Project"), 200).path("data");
    }

    private ObjectNode command(String method, String value, boolean property) {
        var body =
                json.createObjectNode()
                        .put("expectedVersion", 1)
                        .put("operationId", UUID.randomUUID().toString());
        ObjectNode payload = body;
        if (method.equals("POST")) {
            body.put("action", "UPDATE_PROJECT");
            payload = body.putObject("payload");
        }
        payload.put("name", "Updated");
        payload.putObject("unknownExtension")
                .put(property ? value : "value", property ? "fixed" : value);
        return body;
    }

    private String envelope(ObjectNode body, String projectId, String method) throws Exception {
        if (projectId == null)
            return json.writeValueAsString(json.treeToValue(body, PresalesDtos.Create.class));
        var command =
                method.equals("POST")
                        ? json.treeToValue(body, PresalesDtos.Command.class)
                        : new PresalesDtos.Command(
                                body.path("expectedVersion").longValue(),
                                body.path("operationId").asText(),
                                "UPDATE_PROJECT",
                                body);
        return json.writeValueAsString(List.of(projectId, command));
    }

    private String route(String method, ObjectNode project) {
        return "/projects/"
                + project.path("id").asText()
                + (method.equals("POST") ? "/commands" : "");
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
                        "SELECT actor_id,operation_id,request_hash,response_json FROM mate_presales_operation WHERE workspace_id=? ORDER BY actor_id,operation_id",
                        workspace));
    }

    private void error(JsonNode response, String code) {
        assertEquals(code, response.path("data").path("code").asText());
    }

    private void versioned(ObjectNode body) {
        String stored =
                jdbc.queryForObject(
                        "SELECT request_hash FROM mate_presales_operation WHERE workspace_id=? AND operation_id=?",
                        String.class,
                        workspace,
                        body.path("operationId").asText());
        assertNotNull(stored);
        assertEquals(67, stored.length());
        assertTrue(stored.matches("v2:[0-9a-f]{64}"));
    }

    // A trusted synthetic archive supplies BOTH the exact original envelope and response.
    // Never reconstruct an original request from current project/receipt data.
    private void seed(
            ObjectNode original, String projectId, String method, ObjectNode response, String hash)
            throws Exception {
        // The project was created by this member, independent of the archived request.
        String actor =
                jdbc.queryForObject(
                        "SELECT actor_id FROM mate_presales_operation WHERE workspace_id=? LIMIT 1",
                        String.class,
                        workspace);
        jdbc.update(
                "INSERT INTO mate_presales_operation(workspace_id,actor_id,operation_id,request_hash,response_json) VALUES(?,?,?,?,?)",
                workspace,
                actor,
                original.path("operationId").asText(),
                hash == null
                        ? StatementApplicationService.hash(envelope(original, projectId, method))
                        : hash,
                json.writeValueAsString(response));
    }

    @Test
    void createRejectsEveryDistinctCollidingInputAndReplaysSameRequestWithoutWrites()
            throws Exception {
        for (String original : List.of("x\ud800y", "x\udc00y", "x?y")) {
            var body = create(original);
            var accepted = api("POST", "/projects", "member", body, 200).path("data");
            var before = facts();
            assertEquals(accepted, api("POST", "/projects", "member", body, 200).path("data"));
            assertEquals(before, facts());
            for (String different : List.of("x\ud800y", "x\udc00y", "x?y")) {
                if (original.equals(different)) continue;
                error(
                        api(
                                "POST",
                                "/projects",
                                "member",
                                body.deepCopy().put("name", different),
                                409),
                        "OPERATION_CONFLICT");
                assertEquals(before, facts());
            }
            versioned(body);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PATCH"})
    void commandRejectsCollidingUnknownValuesAndPropertyNames(String method) throws Exception {
        for (boolean property : List.of(false, true)) {
            for (String original : List.of("x\ud800y", "x\udc00y", "x?y")) {
                var project = project();
                var body = command(method, original, property);
                var accepted =
                        api(method, route(method, project), "member", body, 200).path("data");
                var before = facts();
                assertEquals(
                        accepted,
                        api(method, route(method, project), "member", body, 200).path("data"));
                assertEquals(before, facts());
                for (String different : List.of("x\ud800y", "x\udc00y", "x?y")) {
                    if (original.equals(different)) continue;
                    var changed =
                            command(method, different, property)
                                    .put("operationId", body.path("operationId").asText());
                    error(
                            api(method, route(method, project), "member", changed, 409),
                            "OPERATION_CONFLICT");
                    assertEquals(before, facts());
                }
                versioned(body);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PATCH"})
    void versionedHashesRetainNullOrderVersionAndProjectBoundaries(String method) throws Exception {
        var project = project();
        var other = project();
        var body = command(method, "original", false);
        ObjectNode payload = method.equals("POST") ? (ObjectNode) body.path("payload") : body;
        payload.putNull("nullableExtension");
        payload.put("lastExtension", "last");
        api(method, route(method, project), "member", body, 200);
        var before = facts();
        var absent = body.deepCopy();
        (method.equals("POST") ? (ObjectNode) absent.path("payload") : absent)
                .remove("nullableExtension");
        var reordered = body.deepCopy();
        ObjectNode reorderedPayload =
                method.equals("POST") ? (ObjectNode) reordered.path("payload") : reordered;
        reorderedPayload.remove("nullableExtension");
        reorderedPayload.putNull("nullableExtension");
        for (var changed : List.of(absent, reordered, body.deepCopy().put("expectedVersion", 2))) {
            error(
                    api(method, route(method, project), "member", changed, 409),
                    "OPERATION_CONFLICT");
            assertEquals(before, facts());
        }
        error(api(method, route(method, other), "member", body, 409), "OPERATION_CONFLICT");
        assertEquals(before, facts());
    }

    @ParameterizedTest
    @ValueSource(strings = {"CREATE", "POST", "PATCH"})
    void legacyAmbiguousMatchingHashesFailClosedButDifferentHashesStillConflict(String method)
            throws Exception {
        var project = project();
        String id = method.equals("CREATE") ? null : project.path("id").asText();
        String route = id == null ? "/projects" : route(method, project);
        String verb = id == null ? "POST" : method;
        for (String original :
                List.of("x\ud800y", "x\udc00y", "x?y", "x\ud800\ud800y", "x\ud800\udc00\udc00y")) {
            var body = id == null ? create(original) : command(method, original, true);
            seed(
                    body,
                    id,
                    method,
                    json.createObjectNode().put("id", "synthetic-archive").put("version", 1),
                    null);
            var before = facts();
            error(api(verb, route, "member", body, 409), "OPERATION_REPLAY_UNVERIFIABLE");
            assertEquals(before, facts());
            if (original.length() == 3) {
                for (String different : List.of("x\ud800y", "x\udc00y", "x?y")) {
                    var changed = id == null ? create(different) : command(method, different, true);
                    changed.put("operationId", body.path("operationId").asText());
                    error(
                            api(verb, route, "member", changed, 409),
                            "OPERATION_REPLAY_UNVERIFIABLE");
                    assertEquals(before, facts());
                }
            }
            var changed = body.deepCopy();
            if (id == null) changed.put("name", "different");
            else if (method.equals("POST"))
                ((ObjectNode) changed.path("payload")).put("name", "different");
            else changed.put("name", "different");
            error(api(verb, route, "member", changed, 409), "OPERATION_CONFLICT");
            assertEquals(before, facts());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"CREATE", "POST", "PATCH"})
    void legacySafeUnicodeAndLiteralEscapesReplayWithoutRewritingStoredBytes(String method)
            throws Exception {
        var project = project();
        String id = method.equals("CREATE") ? null : project.path("id").asText();
        String route = id == null ? "/projects" : route(method, project);
        for (String value :
                List.of(
                        "ordinary",
                        "emoji\ud83d\ude00",
                        "literal\\ud800",
                        "replacement\ufffd",
                        "fullwidth？")) {
            var body = id == null ? create(value) : command(method, value, true);
            var response =
                    json.createObjectNode()
                            .put("id", "synthetic-archive")
                            .put("version", 1)
                            .put("name", "Archived response");
            seed(body, id, method, response, null);
            var before = facts();
            assertEquals(
                    response,
                    api(id == null ? "POST" : method, route, "member", body, 200).path("data"));
            assertEquals(before, facts());
            api(id == null ? "POST" : method, route, "viewer", body, 403);
            assertEquals(before, facts());
        }
    }

    @Test
    void legacyReplayKeepsSourceAuthorizationAndRepairResponseFiltering() throws Exception {
        var project = project();
        String id = project.path("id").asText();
        project.withArray("materials")
                .addObject()
                .put("id", "revoked-binding")
                .put("kbId", "1001")
                .put("role", "PROJECT");
        jdbc.update(
                "UPDATE mate_presales_project SET body_json=? WHERE id=?", project.toString(), id);
        org.mockito.Mockito.when(wikiKnowledgeBases.getById(1001L)).thenReturn(null);
        var blocked = command("POST", "safe", false);
        seed(blocked, id, "POST", json.createObjectNode().put("id", id), "unknown-format");
        var before = facts();
        error(api("POST", route("POST", project), "member", blocked, 403), "MATERIAL_UNAVAILABLE");
        assertEquals(before, facts());
        var repair =
                json.createObjectNode()
                        .put("expectedVersion", 1)
                        .put("operationId", UUID.randomUUID().toString())
                        .put("action", "UNBIND_MATERIAL");
        repair.putObject("payload").put("id", "revoked-binding");
        var archived = project.deepCopy();
        archived.withArray("requirements")
                .addObject()
                .put("id", "secret")
                .put("title", "Confidential source");
        seed(repair, id, "POST", archived, null);
        before = facts();
        var response = api("POST", route("POST", project), "member", repair, 200).path("data");
        assertTrue(response.path("sourceAccessRestricted").asBoolean());
        assertTrue(response.path("requirements").isEmpty());
        assertTrue(response.path("materials").isEmpty());
        assertFalse(response.toString().contains("Confidential source"));
        assertEquals(before, facts());
    }

    @Test
    void legacyReceiptRemainsScopedToItsActorAndWorkspace() throws Exception {
        project();
        var original = create("Safe archived create");
        seed(original, null, "CREATE", json.createObjectNode().put("id", "private-archive"), null);
        var admin = api("POST", "/projects", "admin", original, 200).path("data");
        assertNotEquals("private-archive", admin.path("id").asText());
        String firstWorkspace = workspace;
        try {
            workspace = otherWorkspace;
            var elsewhere = api("POST", "/projects", "owner", original, 200).path("data");
            assertNotEquals("private-archive", elsewhere.path("id").asText());
            assertNotEquals(admin.path("id").asText(), elsewhere.path("id").asText());
            assertEquals(otherWorkspace, elsewhere.path("workspaceId").asText());
        } finally {
            workspace = firstWorkspace;
        }
        assertEquals(
                "private-archive",
                api("POST", "/projects", "member", original, 200).path("data").path("id").asText());
    }

    @ParameterizedTest
    @ValueSource(strings = {"CREATE", "POST", "PATCH"})
    void unsupportedStoredHashFormatsBecomeExplicitConflict(String method) throws Exception {
        var project = project();
        String id = method.equals("CREATE") ? null : project.path("id").asText();
        for (String hash :
                List.of(
                        "v3:" + "a".repeat(64),
                        "v2:" + "A".repeat(64),
                        "A".repeat(64),
                        "v2:abc",
                        "")) {
            var body = id == null ? create("safe") : command(method, "safe", false);
            seed(body, id, method, json.createObjectNode().put("id", "synthetic-archive"), hash);
            var before = facts();
            error(
                    api(
                            id == null ? "POST" : method,
                            id == null ? "/projects" : route(method, project),
                            "member",
                            body,
                            409),
                    "OPERATION_REPLAY_UNVERIFIABLE");
            assertEquals(before, facts());
        }
    }
}

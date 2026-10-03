package vip.mate.presales;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import vip.mate.semantic.support.SemanticHttpFixture;

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
    PresalesExceptionHandler.class,
    PresalesArtifactRenderer.class,
    vip.mate.workspace.core.service.ProjectSourceAccess.class,
    vip.mate.workspace.core.service.ProjectAuthorityFence.class
})
@TestPropertySource(properties = "mateclaw.presales.enabled=true")
class PresalesCommandPayloadContractTest extends SemanticHttpFixture {
    private JsonNode api(String method, String path, String role, Object body, int status)
            throws Exception {
        var r =
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(
                                org.springframework.http.HttpMethod.valueOf(method),
                                "/api/v1/presales" + path)
                        .header("Authorization", tokens.get(role))
                        .header("X-Workspace-Id", workspace)
                        .contentType("application/json");
        if (body != null) r.content(json.writeValueAsString(body));
        var response = mvc.perform(r).andReturn().getResponse();
        assertEquals(status, response.getStatus(), response.getContentAsString());
        return json.readTree(response.getContentAsString());
    }

    private ObjectNode project() throws Exception {
        return (ObjectNode)
                api(
                                "POST",
                                "/projects",
                                "member",
                                Map.of(
                                        "name",
                                        "command types",
                                        "customer",
                                        "customer",
                                        "expectedVersion",
                                        0,
                                        "operationId",
                                        UUID.randomUUID().toString()),
                                200)
                        .path("data");
    }

    private ObjectNode cmd(Integer version, String operation, String action, ObjectNode payload) {
        var r = json.createObjectNode().put("operationId", operation);
        if (version == null) r.putNull("expectedVersion");
        else r.put("expectedVersion", version);
        if (action == null) r.putNull("action");
        else r.put("action", action);
        r.set("payload", payload);
        return r;
    }

    private JsonNode command(ObjectNode p, ObjectNode c, String role, int status) throws Exception {
        return api("POST", "/projects/" + p.path("id").asText() + "/commands", role, c, status);
    }

    private String stored(ObjectNode p) {
        return jdbc.queryForObject(
                "SELECT body_json FROM mate_presales_project WHERE id=?",
                String.class,
                p.path("id").asText());
    }

    private void error(JsonNode r, String code, String message) {
        assertEquals(code, r.path("data").path("code").asText());
        if (message != null) assertEquals(message, r.path("msg").asText());
    }

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> malformed() {
        return java.util.stream.Stream.of(
                org.junit.jupiter.params.provider.Arguments.of(
                        "UPDATE_PROJECT", "{\"name\":123}", "name"),
                org.junit.jupiter.params.provider.Arguments.of(
                        "UPDATE_PROJECT", "{\"customer\":false}", "customer"),
                org.junit.jupiter.params.provider.Arguments.of(
                        "UPDATE_PROJECT", "{\"agentId\":123}", "agentId"),
                org.junit.jupiter.params.provider.Arguments.of(
                        "UPDATE_PROJECT", "{\"ownerId\":123}", "ownerId"),
                org.junit.jupiter.params.provider.Arguments.of(
                        "BIND_MATERIAL", "{\"kbId\":1001}", "kbId"),
                org.junit.jupiter.params.provider.Arguments.of(
                        "SAVE_REQUIREMENT", "{\"title\":123}", "title"),
                org.junit.jupiter.params.provider.Arguments.of(
                        "SAVE_REQUIREMENT",
                        "{\"title\":\"valid\",\"description\":false}",
                        "description"),
                org.junit.jupiter.params.provider.Arguments.of(
                        "SAVE_REQUIREMENT",
                        "{\"title\":\"valid\",\"statementRevision\":1.5}",
                        "statementRevision"),
                org.junit.jupiter.params.provider.Arguments.of(
                        "SAVE_CLARIFICATION", "{\"question\":false}", "question"),
                org.junit.jupiter.params.provider.Arguments.of(
                        "SAVE_CLARIFICATION",
                        "{\"question\":\"valid\",\"requirementId\":123}",
                        "requirementId"),
                org.junit.jupiter.params.provider.Arguments.of(
                        "UNBIND_MATERIAL", "{\"id\":123}", "id"),
                org.junit.jupiter.params.provider.Arguments.of(
                        "CANCEL_AI_TASK", "{\"taskId\":123}", "taskId"),
                org.junit.jupiter.params.provider.Arguments.of(
                        "SAVE_AI_TASK", "{\"skill\":123}", "skill"),
                org.junit.jupiter.params.provider.Arguments.of(
                        "SAVE_AI_TASK", "{\"result\":[]}", "result"),
                org.junit.jupiter.params.provider.Arguments.of(
                        "SAVE_AI_TASK", "{\"contextSnapshot\":\"wrong\"}", "contextSnapshot"),
                org.junit.jupiter.params.provider.Arguments.of(
                        "SAVE_CONTEXT", "{\"text\":123}", "text"),
                org.junit.jupiter.params.provider.Arguments.of(
                        "SAVE_CONTEXT", "{\"sourceRefs\":[123]}", "sourceRefs[0]"),
                org.junit.jupiter.params.provider.Arguments.of(
                        "SAVE_REVIEW", "{\"solutionId\":123}", "solutionId"),
                org.junit.jupiter.params.provider.Arguments.of(
                        "SAVE_REVIEW",
                        "{\"issues\":[{\"description\":false}]}",
                        "issues[0].description"),
                org.junit.jupiter.params.provider.Arguments.of(
                        "CREATE_RELEASE", "{\"solutionId\":123}", "solutionId"),
                org.junit.jupiter.params.provider.Arguments.of(
                        "APPROVE_RELEASE", "{\"releaseId\":123}", "releaseId"),
                org.junit.jupiter.params.provider.Arguments.of(
                        "PUBLISH_RELEASE", "{\"releaseId\":123}", "releaseId"),
                org.junit.jupiter.params.provider.Arguments.of(
                        "APPROVE_BASELINE", "{\"reason\":123}", "reason"),
                org.junit.jupiter.params.provider.Arguments.of(
                        "SAVE_FIT_GAP", "{\"requirementId\":123}", "requirementId"),
                org.junit.jupiter.params.provider.Arguments.of(
                        "SAVE_FIT_GAP", "{\"evidenceIds\":[false]}", "evidenceIds[0]"),
                org.junit.jupiter.params.provider.Arguments.of(
                        "SAVE_SOLUTION", "{\"title\":123}", "title"),
                org.junit.jupiter.params.provider.Arguments.of(
                        "SAVE_SOLUTION", "{\"sections\":[{\"title\":false}]}", "sections[0].title"),
                org.junit.jupiter.params.provider.Arguments.of(
                        "SAVE_SOLUTION",
                        "{\"sections\":[{\"requirementRefs\":{\"key\":\"x\"}}]}",
                        "sections[0].requirementRefs"),
                org.junit.jupiter.params.provider.Arguments.of(
                        "SAVE_SOLUTION",
                        "{\"requirementResponses\":[{\"requirementId\":123}]}",
                        "requirementResponses[0].requirementId"),
                org.junit.jupiter.params.provider.Arguments.of(
                        "SAVE_SOLUTION", "{\"baselineVersion\":\"1\"}", "baselineVersion"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("malformed")
    void malformedWritesAreRejectedWithoutBodyRevisionOrReceipt(
            String action, String payload, String field) throws Exception {
        var p = project();
        String before = stored(p);
        String operation = UUID.randomUUID().toString();
        error(
                command(
                        p,
                        cmd(1, operation, action, (ObjectNode) json.readTree(payload)),
                        "admin",
                        400),
                "INVALID_REQUEST",
                "Invalid payload field: " + field);
        assertEquals(before, stored(p));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM mate_presales_revision WHERE project_id=?",
                        Integer.class,
                        p.path("id").asText()));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM mate_presales_operation WHERE workspace_id=? AND operation_id=?",
                        Integer.class,
                        workspace,
                        operation));
    }

    @Test
    void authorizationReplayCasAndArchiveStillPrecedeShapeValidation() throws Exception {
        var p = project();
        var payload = json.createObjectNode().put("name", 123);
        String operation = UUID.randomUUID().toString();
        var c = cmd(1, operation, "UPDATE_PROJECT", payload);
        command(p, c, "viewer", 403);
        error(
                command(p, cmd(0, operation, "UPDATE_PROJECT", payload), "member", 409),
                "VERSION_CONFLICT",
                null);
        // A historical matching receipt is replayed without validating or re-writing its request.
        String hash =
                vip.mate.semantic.statement.StatementApplicationService.hash(
                        json.writeValueAsString(
                                List.of(
                                        p.path("id").asText(),
                                        json.treeToValue(c, PresalesDtos.Command.class))));
        jdbc.update(
                "INSERT INTO mate_presales_operation(workspace_id,actor_id,operation_id,request_hash,response_json) VALUES(?,?,?,?,?)",
                workspace,
                p.path("createdBy").asText(),
                operation,
                hash,
                p.toString());
        assertEquals(p, command(p, c, "member", 200).path("data"));
        payload.put("name", 456);
        error(command(p, c, "member", 409), "OPERATION_CONFLICT", null);
        p =
                (ObjectNode)
                        command(
                                        p,
                                        cmd(
                                                1,
                                                UUID.randomUUID().toString(),
                                                "ARCHIVE",
                                                json.createObjectNode()),
                                        "member",
                                        200)
                                .path("data");
        error(
                command(
                        p,
                        cmd(2, UUID.randomUUID().toString(), "UPDATE_PROJECT", payload),
                        "member",
                        409),
                "PROJECT_ARCHIVED",
                null);
    }

    @Test
    void revokedSourceStillPrecedesMalformedPayloadAndRepairIsNotWidened() throws Exception {
        var p = project();
        p.withArray("materials")
                .addObject()
                .put("id", "opaque-binding")
                .put("kbId", "1001")
                .put("role", "PROJECT");
        jdbc.update(
                "UPDATE mate_presales_project SET body_json=? WHERE id=?",
                p.toString(),
                p.path("id").asText());
        when(wikiKnowledgeBases.getById(1001L)).thenReturn(null);
        error(
                command(
                        p,
                        cmd(
                                1,
                                UUID.randomUUID().toString(),
                                "UPDATE_PROJECT",
                                json.createObjectNode().put("name", 123)),
                        "member",
                        403),
                "MATERIAL_UNAVAILABLE",
                null);
        error(
                command(
                        p,
                        cmd(
                                1,
                                UUID.randomUUID().toString(),
                                "UNBIND_MATERIAL",
                                json.createObjectNode().put("id", 123)),
                        "member",
                        403),
                "MATERIAL_UNAVAILABLE",
                null);
        var response =
                command(
                                p,
                                cmd(
                                        1,
                                        UUID.randomUUID().toString(),
                                        "UNBIND_MATERIAL",
                                        json.createObjectNode().put("id", "opaque-binding")),
                                "member",
                                200)
                        .path("data");
        assertTrue(response.path("materials").isEmpty());
    }

    @Test
    void validLegacyDefaultsUnknownExtensionsAndStringRevisionAreNotNormalized() throws Exception {
        var p = project();
        var payload = json.createObjectNode().put("title", "原文 Ω").put("statementRevision", "01");
        payload.putNull("scope").putNull("priority");
        payload.putObject("extension").put("id", 123).putNull("raw");
        String before = payload.toString();
        var c = cmd(1, UUID.randomUUID().toString(), "SAVE_REQUIREMENT", payload);
        p = (ObjectNode) command(p, c, "member", 200).path("data");
        var item = p.path("requirements").get(0);
        assertEquals("UNKNOWN", item.path("scope").asText());
        assertEquals("MEDIUM", item.path("priority").asText());
        assertEquals("01", item.path("statementRevision").asText());
        assertTrue(item.path("statementRevision").isTextual());
        assertEquals(payload.path("extension"), item.path("extension"));
        assertEquals(before, payload.toString());
        var task = json.createObjectNode().putNull("status");
        task.putObject("result").put("schemaVersion", "1").put("needsHumanReview", "true");
        p =
                (ObjectNode)
                        command(
                                        p,
                                        cmd(2, UUID.randomUUID().toString(), "SAVE_AI_TASK", task),
                                        "member",
                                        200)
                                .path("data");
        assertEquals("DRAFT", p.path("tasks").get(0).path("status").asText());
        assertEquals(task.path("result"), p.path("tasks").get(0).path("result"));
        assertEquals(p.toString(), stored(p));
    }
}

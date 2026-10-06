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
class PresalesCommandKindContractTest extends SemanticHttpFixture {
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

    @Test
    void unknownCommandsRetainRawTextAndErrorOrder() throws Exception {
        var p = project();
        String before = stored(p);
        for (String raw : Arrays.asList(null, "", "unknown", "archive", " ARCHIVE ", "UNKNOWN")) {
            var c = cmd(1, UUID.randomUUID().toString(), raw, json.createObjectNode());
            error(
                    command(p, c, "member", 400),
                    "INVALID_REQUEST",
                    "Unsupported command: " + Objects.toString(raw, ""));
        }
        error(
                command(p, cmd(0, UUID.randomUUID().toString(), "unknown", null), "member", 409),
                "VERSION_CONFLICT",
                null);
        error(
                command(p, cmd(null, UUID.randomUUID().toString(), "unknown", null), "member", 400),
                "INVALID_REQUEST",
                "expectedVersion required");
        command(p, cmd(1, UUID.randomUUID().toString(), "unknown", null), "viewer", 403);
        assertEquals(before, stored(p));
    }

    @Test
    void approvalCommandsKeepAdminBoundaryAndUnknownCannotBecomeAdminCommand() throws Exception {
        var p = project();
        String before = stored(p);
        for (String raw : List.of("APPROVE_BASELINE", "APPROVE_RELEASE", "PUBLISH_RELEASE"))
            command(
                    p,
                    cmd(1, UUID.randomUUID().toString(), raw, json.createObjectNode()),
                    "member",
                    403);
        error(
                command(
                        p,
                        cmd(
                                1,
                                UUID.randomUUID().toString(),
                                "approve_release",
                                json.createObjectNode()),
                        "member",
                        400),
                "INVALID_REQUEST",
                "Unsupported command: approve_release");
        assertEquals(before, stored(p));
    }

    @Test
    void knownCommandKeepsExactWireHashReplayAndPayloadConflict() throws Exception {
        var p = project();
        var payload =
                json.createObjectNode()
                        .put("name", "new name")
                        .put("legacyExtension", "retained in hash");
        String op = UUID.randomUUID().toString();
        var c = cmd(1, op, "UPDATE_PROJECT", payload);
        var wire = json.treeToValue(c, PresalesDtos.Command.class);
        String expected =
                PresalesRequestHashV2.digest(
                        json.writeValueAsString(List.of(p.path("id").asText(), wire)));
        var accepted = command(p, c, "member", 200).path("data");
        assertEquals(
                expected,
                jdbc.queryForObject(
                        "SELECT request_hash FROM mate_presales_operation WHERE workspace_id=? AND operation_id=?",
                        String.class,
                        workspace,
                        op));
        assertEquals(accepted, command(p, c, "member", 200).path("data"));
        payload.put("legacyExtension", "changed");
        error(command(p, c, "member", 409), "OPERATION_CONFLICT", null);
        payload.put("legacyExtension", "retained in hash");
        c.put("action", "unknown");
        error(command(p, c, "member", 409), "OPERATION_CONFLICT", null);
        assertEquals(accepted.toString(), stored(p));
    }

    @Test
    void revokedMaterialAllowsOnlyExactRepairShapeAndUnknownStillFailsSourceAuth()
            throws Exception {
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
        var payload = json.createObjectNode().put("id", "opaque-binding");
        error(
                command(p, cmd(1, UUID.randomUUID().toString(), "unknown", payload), "member", 403),
                "MATERIAL_UNAVAILABLE",
                null);
        payload.put("extra", "do not widen repair");
        error(
                command(
                        p,
                        cmd(1, UUID.randomUUID().toString(), "UNBIND_MATERIAL", payload),
                        "member",
                        403),
                "MATERIAL_UNAVAILABLE",
                null);
        payload.remove("extra");
        var result =
                command(
                                p,
                                cmd(1, UUID.randomUUID().toString(), "UNBIND_MATERIAL", payload),
                                "member",
                                200)
                        .path("data");
        assertEquals(2, result.path("version").asInt());
        assertTrue(result.path("materials").isEmpty());
    }
}

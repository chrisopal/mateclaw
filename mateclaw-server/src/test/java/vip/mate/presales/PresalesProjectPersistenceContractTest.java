package vip.mate.presales;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import vip.mate.semantic.support.SemanticHttpFixture;

@Import({
    PresalesAccess.class,
    PresalesService.class,
    vip.mate.presales.repository.PresalesProjectRepository.class,
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
class PresalesProjectPersistenceContractTest extends SemanticHttpFixture {
    private JsonNode api(String method, String path, String scope, Object body, int status)
            throws Exception {
        var request =
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(
                                org.springframework.http.HttpMethod.valueOf(method),
                                "/api/v1/presales" + path)
                        .contentType("application/json")
                        .header("Authorization", tokens.get("member"))
                        .header("X-Workspace-Id", scope);
        if (body != null) request.content(json.writeValueAsString(body));
        var response = mvc.perform(request).andReturn().getResponse();
        assertEquals(status, response.getStatus(), response.getContentAsString());
        return response.getContentAsString().isBlank()
                ? json.nullNode()
                : json.readTree(response.getContentAsString()).path("data");
    }

    private Map<String, Object> createRequest(String operation) {
        return Map.of(
                "name",
                "  精确项目 Ω  ",
                "customer",
                "  Customer\n原文  ",
                "expectedVersion",
                0,
                "operationId",
                operation);
    }

    private String body(String id) {
        return jdbc.queryForObject(
                "SELECT body_json FROM mate_presales_project WHERE id=? AND workspace_id=?",
                String.class,
                id,
                workspace);
    }

    private int count(String table, String key, String value) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE " + key + "=?", Integer.class, value);
    }

    private void assertStored(JsonNode response, String operation) throws Exception {
        String projectId = response.path("id").asText(), stored = body(projectId);
        assertEquals(response, json.readTree(stored));
        assertEquals(
                stored,
                jdbc.queryForObject(
                        "SELECT body_json FROM mate_presales_revision WHERE project_id=? AND version=?",
                        String.class,
                        projectId,
                        response.path("version").asInt()));
        assertEquals(
                stored,
                jdbc.queryForObject(
                        "SELECT response_json FROM mate_presales_operation WHERE workspace_id=? AND operation_id=?",
                        String.class,
                        workspace,
                        operation));
    }

    @Test
    void writesExactBodyRevisionAndReceiptAndKeepsUnknownHistory() throws Exception {
        String createOperation = UUID.randomUUID().toString();
        JsonNode created = api("POST", "/projects", workspace, createRequest(createOperation), 200);
        assertStored(created, createOperation);
        String id = created.path("id").asText();
        var historical = (com.fasterxml.jackson.databind.node.ObjectNode) created.deepCopy();
        historical.putObject("legacyExtension").put("unchanged", "  Ω\n客户原文  ");
        jdbc.update(
                "UPDATE mate_presales_project SET body_json=? WHERE id=?",
                json.writeValueAsString(historical),
                id);
        String operation = UUID.randomUUID().toString();
        JsonNode changed =
                api(
                        "POST",
                        "/projects/" + id + "/commands",
                        workspace,
                        Map.of(
                                "action",
                                "UPDATE_PROJECT",
                                "payload",
                                Map.of("goal", "  precise goal  "),
                                "expectedVersion",
                                1,
                                "operationId",
                                operation),
                        200);
        assertEquals(2, changed.path("version").asInt());
        assertEquals("  precise goal  ", changed.path("goal").asText());
        assertEquals(historical.path("legacyExtension"), changed.path("legacyExtension"));
        assertStored(changed, operation);
        assertEquals(changed, api("GET", "/projects/" + id, workspace, null, 200));
    }

    @Test
    void replaysWithoutNewWritesAndRejectsDifferentInputAndWorkspace() throws Exception {
        String operation = UUID.randomUUID().toString();
        var request = createRequest(operation);
        JsonNode created = api("POST", "/projects", workspace, request, 200);
        String id = created.path("id").asText(), stored = body(id);
        assertEquals(created, api("POST", "/projects", workspace, request, 200));
        assertEquals(1, count("mate_presales_revision", "project_id", id));
        assertEquals(1, count("mate_presales_operation", "operation_id", operation));
        var changed = new java.util.HashMap<>(request);
        changed.put("name", "Different input");
        api("POST", "/projects", workspace, changed, 409);
        api("GET", "/projects/" + id, otherWorkspace, null, 403);
        workspaces.addMember(
                Long.valueOf(otherWorkspace),
                Long.valueOf(created.path("createdBy").asText()),
                "viewer");
        api("GET", "/projects/" + id, otherWorkspace, null, 404);
        api(
                "POST",
                "/projects/" + id + "/commands",
                workspace,
                Map.of(
                        "action",
                        "UPDATE_PROJECT",
                        "payload",
                        Map.of("goal", "stale"),
                        "expectedVersion",
                        0,
                        "operationId",
                        UUID.randomUUID().toString()),
                409);
        assertEquals(stored, body(id));
        assertEquals(1, count("mate_presales_revision", "project_id", id));
        assertEquals(1, count("mate_presales_operation", "operation_id", operation));
    }

    @Test
    void rollsBackProjectAndReceiptWhenRevisionWriteFails() throws Exception {
        JsonNode created =
                api(
                        "POST",
                        "/projects",
                        workspace,
                        createRequest(UUID.randomUUID().toString()),
                        200);
        String id = created.path("id").asText(),
                original = body(id),
                operation = UUID.randomUUID().toString();
        jdbc.execute(
                "ALTER TABLE mate_presales_revision ADD CONSTRAINT project_contract_rollback CHECK (body_json NOT LIKE '%reject-contract-write%')");
        try {
            var failure =
                    assertThrows(
                            jakarta.servlet.ServletException.class,
                            () ->
                                    api(
                                            "POST",
                                            "/projects/" + id + "/commands",
                                            workspace,
                                            Map.of(
                                                    "action",
                                                    "UPDATE_PROJECT",
                                                    "payload",
                                                    Map.of("goal", "reject-contract-write"),
                                                    "expectedVersion",
                                                    1,
                                                    "operationId",
                                                    operation),
                                            200));
            assertInstanceOf(
                    org.springframework.dao.DataIntegrityViolationException.class,
                    failure.getCause());
            assertTrue(
                    failure.getCause()
                            .getMessage()
                            .toLowerCase(java.util.Locale.ROOT)
                            .contains("project_contract_rollback"));
            assertEquals(original, body(id));
            assertEquals(1, count("mate_presales_revision", "project_id", id));
            assertEquals(0, count("mate_presales_operation", "operation_id", operation));
        } finally {
            jdbc.execute(
                    "ALTER TABLE mate_presales_revision DROP CONSTRAINT project_contract_rollback");
        }
    }

    @Test
    void doesNotTranslateAnExistingJsonNullBodyIntoMissingProject() throws Exception {
        var created =
                api(
                        "POST",
                        "/projects",
                        workspace,
                        createRequest(UUID.randomUUID().toString()),
                        200);
        String projectId = created.path("id").asText();
        jdbc.update("UPDATE mate_presales_project SET body_json=? WHERE id=?", "null", projectId);
        var request =
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                "/api/v1/presales/projects/" + projectId)
                        .header("Authorization", tokens.get("member"))
                        .header("X-Workspace-Id", workspace);
        var failure =
                assertThrows(jakarta.servlet.ServletException.class, () -> mvc.perform(request));
        assertInstanceOf(IllegalStateException.class, failure.getCause());
        assertInstanceOf(ClassCastException.class, failure.getCause().getCause());
    }
}

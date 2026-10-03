package vip.mate.presales;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.Test;
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
    PresalesExceptionHandler.class,
    PresalesArtifactRenderer.class,
    vip.mate.workspace.core.service.ProjectSourceAccess.class,
    vip.mate.workspace.core.service.ProjectAuthorityFence.class
})
@TestPropertySource(properties = "mateclaw.presales.enabled=true")
class PresalesQueryDtoContractTest extends SemanticHttpFixture {
    @MockitoBean vip.mate.semantic.graph.GraphApplicationService graphs;
    @MockitoBean vip.mate.semantic.statement.StatementApplicationService statements;

    @org.springframework.beans.factory.annotation.Autowired
    vip.mate.semantic.config.SemanticProperties semantic;

    private JsonNode api(String path, String role, String scope, int status) throws Exception {
        var r =
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                        "/api/v1/presales" + path);
        if (role != null) r.header("Authorization", tokens.get(role));
        if (scope != null) r.header("X-Workspace-Id", scope);
        var response = mvc.perform(r).andReturn().getResponse();
        assertEquals(status, response.getStatus(), response.getContentAsString());
        return json.readTree(response.getContentAsString()).path("data");
    }

    private vip.mate.wiki.model.WikiKnowledgeBaseEntity kb(long id, String name) {
        var kb = new vip.mate.wiki.model.WikiKnowledgeBaseEntity();
        kb.setId(id);
        kb.setName(name);
        kb.setWorkspaceId(Long.valueOf(workspace));
        kb.setDeleted(0);
        when(wikiKnowledgeBases.getById(id)).thenReturn(kb);
        return kb;
    }

    private vip.mate.semantic.web.GraphDtos.Binding binding(String g, String rev, boolean enabled) {
        return new vip.mate.semantic.web.GraphDtos.Binding(
                g, workspace, "unused", rev, 1, enabled, 1, true, java.time.Instant.EPOCH);
    }

    @Test
    void capabilitiesKeepRolesAndExactBooleans() throws Exception {
        for (String role : List.of("viewer", "member", "admin", "owner", "global")) {
            var expected =
                    json.createObjectNode()
                            .put("enabled", true)
                            .put("semanticEnabled", true)
                            .put("canWrite", !role.equals("viewer"))
                            .put("canApprove", Set.of("admin", "owner", "global").contains(role));
            assertEquals(expected, api("/capabilities", role, workspace, 200));
        }
        api("/capabilities", "member", otherWorkspace, 403);
        api("/capabilities", "member", null, 400);
    }

    @Test
    void sourcesKeepConditionalFieldsNullsAndErrorPropagation() throws Exception {
        var a = kb(9007199254740993001L, null);
        var b = kb(9007199254740993002L, "disabled");
        var c = kb(9007199254740993003L, "missing");
        when(wikiKnowledgeBases.listByWorkspace(Long.valueOf(workspace)))
                .thenReturn(List.of(a, b, c));
        when(graphs.get(workspace, a.getId().toString())).thenReturn(binding("g", null, true));
        when(graphs.get(workspace, b.getId().toString())).thenReturn(binding("hidden", "r", false));
        when(graphs.get(workspace, c.getId().toString()))
                .thenThrow(new SemanticApiException(404, "NOT_FOUND", "missing"));
        var expected = json.createArrayNode();
        expected.addObject()
                .put("kbId", a.getId().toString())
                .putNull("name")
                .put("graphId", "g")
                .putNull("ontologyRevisionId");
        expected.addObject().put("kbId", b.getId().toString()).put("name", "disabled");
        expected.addObject().put("kbId", c.getId().toString()).put("name", "missing");
        assertEquals(expected, api("/sources", "viewer", workspace, 200));
        doThrow(new SemanticApiException(403, "FORBIDDEN", "revoked"))
                .when(graphs)
                .get(workspace, c.getId().toString());
        assertEquals("FORBIDDEN", api("/sources", "viewer", workspace, 403).path("code").asText());
    }

    @Test
    void disabledSemanticKeepsSourceLimitAndSkipsGraphCalls() throws Exception {
        var rows = new ArrayList<vip.mate.wiki.model.WikiKnowledgeBaseEntity>();
        for (int i = 0; i < 201; i++) rows.add(kb(1000L + i, "kb-" + i));
        when(wikiKnowledgeBases.listByWorkspace(Long.valueOf(workspace))).thenReturn(rows);
        semantic.setEnabled(false);
        try {
            var result = api("/sources", "member", workspace, 200);
            assertEquals(200, result.size());
            assertEquals("1199", result.get(199).path("kbId").asText());
            assertEquals(2, result.get(0).size());
            verifyNoInteractions(graphs);
            assertFalse(
                    api("/capabilities", "member", workspace, 200)
                            .path("semanticEnabled")
                            .asBoolean());
        } finally {
            semantic.setEnabled(true);
        }
    }

    private ObjectNode project(String... gs) throws Exception {
        var r =
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                                "/api/v1/presales/projects")
                        .header("Authorization", tokens.get("member"))
                        .header("X-Workspace-Id", workspace)
                        .contentType("application/json")
                        .content(
                                json.writeValueAsString(
                                        Map.of(
                                                "name",
                                                "dto",
                                                "customer",
                                                "customer",
                                                "expectedVersion",
                                                0,
                                                "operationId",
                                                UUID.randomUUID().toString())));
        var response = mvc.perform(r).andReturn().getResponse();
        assertEquals(200, response.getStatus(), response.getContentAsString());
        var p = (ObjectNode) json.readTree(response.getContentAsString()).path("data");
        kb(1001, "source");
        for (String g : gs)
            p.withArray("materials")
                    .addObject()
                    .put("id", UUID.randomUUID().toString())
                    .put("kbId", "1001")
                    .put("graphId", g);
        jdbc.update(
                "UPDATE mate_presales_project SET body_json=? WHERE id=?",
                p.toString(),
                p.path("id").asText());
        return p;
    }

    private vip.mate.semantic.web.StatementDtos.StatementView fact(
            String id, int rev, List<String> evidence) {
        var assertion =
                vip.mate.semantic.core.fact.AssertionPayload.classAssertion(
                        "ClassAssertion(<urn:C> <urn:s>)",
                        "urn:s",
                        "<urn:C>",
                        Set.of("urn:C", "urn:s"));
        return new vip.mate.semantic.web.StatementDtos.StatementView(
                id,
                "upstream",
                rev,
                "9007199254740993004",
                "s",
                assertion,
                "CURRENT",
                null,
                null,
                "ACCEPTED",
                "SUPPORTED",
                evidence,
                "actor",
                java.time.Instant.EPOCH);
    }

    @Test
    void trustedFactsKeepProjectionNullsOrderAndDeduplicateGraphs() throws Exception {
        var p = project("", "g", "g", "g2");
        when(statements.trusted(workspace, "g"))
                .thenReturn(List.of(fact("9007199254740993005", 3, List.of("e2", "e1"))));
        when(statements.trusted(workspace, "g2")).thenReturn(List.of(fact("second", 4, null)));
        var expected = json.createArrayNode();
        expected.addObject()
                .put("id", "9007199254740993005")
                .put("revision", 3)
                .put("graphId", "g")
                .put("ontologyRevisionId", "9007199254740993004")
                .put("label", "ClassAssertion(<urn:C> <urn:s>)")
                .putArray("evidenceIds")
                .add("e2")
                .add("e1");
        expected.addObject()
                .put("id", "second")
                .put("revision", 4)
                .put("graphId", "g2")
                .put("ontologyRevisionId", "9007199254740993004")
                .put("label", "ClassAssertion(<urn:C> <urn:s>)")
                .putNull("evidenceIds");
        assertEquals(
                expected,
                api(
                        "/projects/" + p.path("id").asText() + "/statements",
                        "viewer",
                        workspace,
                        200));
        verify(statements, times(1)).trusted(workspace, "g");
        verify(statements, never()).trusted(workspace, "");
    }

    @Test
    void trustedFactsStopAt500AndAuthorizeSourcesBeforeRead() throws Exception {
        var p = project("g", "late");
        var path = "/projects/" + p.path("id").asText() + "/statements";
        when(statements.trusted(workspace, "g"))
                .thenReturn(Collections.nCopies(501, fact("same", 2, List.of())));
        assertEquals(500, api(path, "viewer", workspace, 200).size());
        verify(statements, never()).trusted(workspace, "late");
        clearInvocations(statements);
        when(wikiKnowledgeBases.getById(1001L)).thenReturn(null);
        assertEquals(
                "MATERIAL_UNAVAILABLE", api(path, "viewer", workspace, 403).path("code").asText());
        verifyNoInteractions(statements);
    }
}

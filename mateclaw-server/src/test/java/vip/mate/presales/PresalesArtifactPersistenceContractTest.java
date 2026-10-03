package vip.mate.presales;

import static org.junit.jupiter.api.Assertions.*;

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
class PresalesArtifactPersistenceContractTest extends SemanticHttpFixture {
    private static final byte[] BYTES = new byte[] {0, 1, -1, 34, 10, 0, 127};
    private static final String SOLUTION = "9007199254740993001";
    private static final String RELEASE = "9007199254740993002";
    private static final String PRESENTATION = "9007199254740993003";

    private org.springframework.mock.web.MockHttpServletResponse artifactRequest(
            String method, String path, String role, String scope, Object body) throws Exception {
        var request =
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(
                                org.springframework.http.HttpMethod.valueOf(method),
                                "/api/v1/presales" + path)
                        .contentType("application/json")
                        .header("Authorization", tokens.get(role))
                        .header("X-Workspace-Id", scope);
        if (body != null) request.content(json.writeValueAsString(body));
        return mvc.perform(request).andReturn().getResponse();
    }

    private com.fasterxml.jackson.databind.node.ObjectNode fixture() throws Exception {
        var response =
                artifactRequest(
                        "POST",
                        "/projects",
                        "member",
                        workspace,
                        Map.of(
                                "name",
                                "Artifact contract",
                                "customer",
                                "Contract customer",
                                "expectedVersion",
                                0,
                                "operationId",
                                UUID.randomUUID().toString()));
        assertEquals(200, response.getStatus(), response.getContentAsString());
        var p =
                (com.fasterxml.jackson.databind.node.ObjectNode)
                        json.readTree(response.getContentAsString()).path("data");
        p.withArray("baselines").addObject().put("id", "baseline").putArray("references");
        var solution =
                p.withArray("solutions")
                        .addObject()
                        .put("id", SOLUTION)
                        .put("title", "Frozen solution")
                        .put("authorId", "original-author")
                        .put("baselineId", "baseline")
                        .put("provisional", false);
        solution.putArray("sections");
        solution.putArray("fitGapRefs");
        p.withArray("reviews")
                .addObject()
                .put("id", "review")
                .put("kind", "HUMAN_REVIEW")
                .put("authority", "HUMAN_REVIEW")
                .put("solutionId", SOLUTION)
                .put("authorId", "independent-reviewer")
                .putArray("issues");
        var release =
                p.withArray("releases")
                        .addObject()
                        .put("id", RELEASE)
                        .put("solutionId", SOLUTION)
                        .put("status", "PUBLISHED");
        release.putArray("files")
                .addObject()
                .put("filename", "solution.pptx")
                .put("sha256", PresalesArtifactRenderer.digest(BYTES));
        persist(p);
        return p;
    }

    private void persist(com.fasterxml.jackson.databind.node.ObjectNode p) throws Exception {
        jdbc.update(
                "UPDATE mate_presales_project SET body_json=? WHERE id=?",
                json.writeValueAsString(p),
                p.path("id").asText());
    }

    private void store(String project, String release, String filename, byte[] bytes) {
        jdbc.update(
                "INSERT INTO mate_presales_artifact(project_id,release_id,filename,digest,content_base64) VALUES(?,?,?,?,?)",
                project,
                release,
                filename,
                PresalesArtifactRenderer.digest(bytes),
                java.util.Base64.getEncoder().encodeToString(bytes));
    }

    @Test
    void publishedAndPreviewReadsPreserveBytesAndAuthorization() throws Exception {
        var p = fixture();
        String id = p.path("id").asText();
        store(id, RELEASE, "solution.pptx", BYTES);
        String download = "/projects/" + id + "/releases/" + RELEASE + "/files/solution.pptx";
        var published = artifactRequest("GET", download, "viewer", workspace, null);
        assertEquals(200, published.getStatus(), published.getContentAsString());
        assertArrayEquals(BYTES, published.getContentAsByteArray());
        String preview = "/projects/" + id + "/releases/" + RELEASE + "/preview/solution.pptx";
        assertEquals(403, artifactRequest("GET", preview, "member", workspace, null).getStatus());
        var result = artifactRequest("GET", preview, "owner", workspace, null);
        assertEquals(200, result.getStatus());
        assertArrayEquals(BYTES, result.getContentAsByteArray());
        assertEquals(
                404, artifactRequest("GET", download, "owner", otherWorkspace, null).getStatus());
        ((com.fasterxml.jackson.databind.node.ObjectNode) p.path("releases").get(0))
                .put("status", "PENDING");
        persist(p);
        var pending = artifactRequest("GET", download, "viewer", workspace, null);
        assertEquals(403, pending.getStatus());
        assertTrue(pending.getContentAsString().contains("RELEASE_NOT_PUBLISHED"));
    }

    @Test
    void storedPresentationReadsRetainExactBytesAndIntegrityErrors() throws Exception {
        var p = fixture();
        String id = p.path("id").asText();
        ((com.fasterxml.jackson.databind.node.ObjectNode) p.path("solutions").get(0))
                .putObject("presentation")
                .put("artifactId", PRESENTATION)
                .put("sha256", PresalesArtifactRenderer.digest(BYTES));
        persist(p);
        store(id, PRESENTATION, "solution.pptx", BYTES);
        String path = "/projects/" + id + "/solutions/" + SOLUTION + "/draft/solution.pptx";
        var result = artifactRequest("GET", path, "viewer", workspace, null);
        assertEquals(200, result.getStatus(), result.getContentAsString());
        assertArrayEquals(BYTES, result.getContentAsByteArray());
        jdbc.update(
                "UPDATE mate_presales_artifact SET digest=? WHERE project_id=? AND release_id=?",
                "tampered",
                id,
                PRESENTATION);
        var corrupt = artifactRequest("GET", path, "viewer", workspace, null);
        assertEquals(409, corrupt.getStatus());
        assertTrue(corrupt.getContentAsString().contains("ARTIFACT_DIGEST_MISMATCH"));
        jdbc.update(
                "DELETE FROM mate_presales_artifact WHERE project_id=? AND release_id=?",
                id,
                PRESENTATION);
        var missing = artifactRequest("GET", path, "viewer", workspace, null);
        assertEquals(404, missing.getStatus());
        assertTrue(missing.getContentAsString().contains("Presentation artifact not found"));
    }

    @Test
    void candidateWritesRollBackWithProjectRevisionFailure() throws Exception {
        var p = fixture();
        String id = p.path("id").asText();
        String before =
                jdbc.queryForObject(
                        "SELECT body_json FROM mate_presales_project WHERE id=?", String.class, id);
        String operation = UUID.randomUUID().toString();
        jdbc.execute(
                "ALTER TABLE mate_presales_revision ADD CONSTRAINT artifact_contract_rollback CHECK (action <> 'CREATE_RELEASE')");
        try {
            var failure =
                    assertThrows(
                            jakarta.servlet.ServletException.class,
                            () ->
                                    artifactRequest(
                                            "POST",
                                            "/projects/" + id + "/commands",
                                            "owner",
                                            workspace,
                                            Map.of(
                                                    "action",
                                                    "CREATE_RELEASE",
                                                    "expectedVersion",
                                                    1,
                                                    "operationId",
                                                    operation,
                                                    "payload",
                                                    Map.of("solutionId", SOLUTION))));
            assertInstanceOf(
                    org.springframework.dao.DataIntegrityViolationException.class,
                    failure.getCause());
            assertTrue(
                    failure.getCause()
                            .getMessage()
                            .toLowerCase(java.util.Locale.ROOT)
                            .contains("artifact_contract_rollback"));
            assertEquals(
                    before,
                    jdbc.queryForObject(
                            "SELECT body_json FROM mate_presales_project WHERE id=?",
                            String.class,
                            id));
            assertEquals(
                    0,
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM mate_presales_artifact WHERE project_id=?",
                            Integer.class,
                            id));
            assertEquals(
                    0,
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM mate_presales_operation WHERE operation_id=?",
                            Integer.class,
                            operation));
        } finally {
            jdbc.execute(
                    "ALTER TABLE mate_presales_revision DROP CONSTRAINT artifact_contract_rollback");
        }
    }
}

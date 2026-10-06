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
class PresalesHandoffReadContractTest extends SemanticHttpFixture {
    private static final String RELEASE = "9007199254740993111";
    private static final byte[] BYTES = new byte[] {0, -1, 10, 34, 0};

    private org.springframework.mock.web.MockHttpServletResponse handoffRequest(
            String project, String release, String scope) throws Exception {
        String path =
                "/api/v1/presales/projects/"
                        + project
                        + (release == null ? "/handoff" : "/releases/" + release + "/handoff");
        return mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                        path)
                                .header("Authorization", tokens.get("viewer"))
                                .header("X-Workspace-Id", scope))
                .andReturn()
                .getResponse();
    }

    private com.fasterxml.jackson.databind.node.ObjectNode fixture() throws Exception {
        var created =
                mvc.perform(
                                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                        .post("/api/v1/presales/projects")
                                        .contentType("application/json")
                                        .header("Authorization", tokens.get("member"))
                                        .header("X-Workspace-Id", workspace)
                                        .content(
                                                json.writeValueAsString(
                                                        Map.of(
                                                                "name",
                                                                "Handoff contract",
                                                                "customer",
                                                                "Synthetic customer",
                                                                "expectedVersion",
                                                                0,
                                                                "operationId",
                                                                UUID.randomUUID().toString()))))
                        .andReturn()
                        .getResponse();
        assertEquals(200, created.getStatus());
        var p =
                (com.fasterxml.jackson.databind.node.ObjectNode)
                        json.readTree(created.getContentAsString()).path("data");
        var baseline = p.withArray("baselines").addObject().put("id", "baseline");
        baseline.putArray("references");
        var solution =
                p.withArray("solutions")
                        .addObject()
                        .put("id", "solution")
                        .put("baselineId", "baseline")
                        .put("authorId", "author")
                        .put("provisional", false);
        solution.putArray("sections");
        solution.putArray("fitGapRefs");
        solution.putObject("coverage").putArray("responses");
        p.withArray("reviews")
                .addObject()
                .put("id", "review")
                .put("kind", "HUMAN_REVIEW")
                .put("authority", "HUMAN_REVIEW")
                .put("solutionId", "solution")
                .put("authorId", "reviewer")
                .putArray("issues");
        var release =
                p.withArray("releases")
                        .addObject()
                        .put("id", RELEASE)
                        .put("solutionId", "solution")
                        .put("baselineId", "baseline")
                        .put("status", "PUBLISHED");
        release.putArray("files")
                .addObject()
                .put("filename", "solution.md")
                .put("sha256", PresalesArtifactRenderer.digest(BYTES));
        var frozen =
                json.createObjectNode()
                        .put("schemaVersion", 1)
                        .put("engagementId", p.path("id").asText())
                        .put("caseRef", p.path("id").asText())
                        .put("workspaceId", workspace)
                        .put("customerConfirmationStatus", "UNCONFIRMED")
                        .put("accessPolicy", "WORKSPACE_REAUTHORIZE_ON_READ")
                        .put("historicalClarificationsAvailable", true)
                        .put("extension", "frozen marker");
        frozen.set("baseline", baseline.deepCopy());
        frozen.set("solution", solution.deepCopy());
        frozen.set("release", release.deepCopy());
        frozen.putArray("fitGaps");
        frozen.putArray("risksAndUnknowns");
        frozen.putArray("materials");
        frozen.putArray("sourceRefs");
        frozen.putArray("clarifications")
                .addObject()
                .put("id", "old-question")
                .put("question", "Frozen question");
        release.set("handoffSnapshot", frozen);
        persist(p);
        jdbc.update(
                "INSERT INTO mate_presales_artifact(project_id,release_id,filename,digest,content_base64) VALUES(?,?,?,?,?)",
                p.path("id").asText(),
                RELEASE,
                "solution.md",
                PresalesArtifactRenderer.digest(BYTES),
                java.util.Base64.getEncoder().encodeToString(BYTES));
        return p;
    }

    private void persist(com.fasterxml.jackson.databind.node.ObjectNode p) throws Exception {
        jdbc.update(
                "UPDATE mate_presales_project SET body_json=? WHERE id=?",
                json.writeValueAsString(p),
                p.path("id").asText());
    }

    private com.fasterxml.jackson.databind.JsonNode body(
            org.springframework.mock.web.MockHttpServletResponse response, int status)
            throws Exception {
        assertEquals(status, response.getStatus(), response.getContentAsString());
        return json.readTree(response.getContentAsString()).path("data");
    }

    private String facts(com.fasterxml.jackson.databind.node.ObjectNode p) throws Exception {
        String id = p.path("id").asText();
        return json.writeValueAsString(
                java.util.List.of(
                        jdbc.queryForMap(
                                "SELECT body_json,version FROM mate_presales_project WHERE id=?",
                                id),
                        jdbc.queryForList(
                                "SELECT release_id,filename,digest,content_base64 FROM mate_presales_artifact WHERE project_id=? ORDER BY release_id,filename",
                                id)));
    }

    @Test
    void latestUsesTheFrozenReleaseAfterCurrentProjectChanges() throws Exception {
        var p = fixture();
        String id = p.path("id").asText();
        var exact = body(handoffRequest(id, RELEASE, workspace), 200);
        p.withArray("clarifications")
                .addObject()
                .put("id", "new-question")
                .put("question", "After publication");
        ((com.fasterxml.jackson.databind.node.ObjectNode) p.path("solutions").get(0))
                .put("title", "Changed current title");
        p.withArray("baselines").addObject().put("id", "new-baseline").putArray("references");
        persist(p);
        String before = facts(p);
        var latest = body(handoffRequest(id, null, workspace), 200);
        assertEquals(json.writeValueAsString(exact), json.writeValueAsString(latest));
        assertEquals("frozen marker", latest.path("extension").asText());
        assertEquals(1, latest.path("clarifications").size());
        assertEquals(before, facts(p));
    }

    @Test
    void latestChoosesTheLastPublishedReleaseWithoutUsingPendingCandidates() throws Exception {
        var p = fixture();
        String id = p.path("id").asText();
        var second =
                ((com.fasterxml.jackson.databind.node.ObjectNode) p.path("releases").get(0))
                        .deepCopy();
        second.put("id", "published-second");
        ((com.fasterxml.jackson.databind.node.ObjectNode) second.path("handoffSnapshot"))
                .put("extension", "second frozen marker");
        ((com.fasterxml.jackson.databind.node.ObjectNode)
                        second.path("handoffSnapshot").path("release"))
                .put("id", "published-second");
        p.withArray("releases").add(second);
        p.withArray("releases").addObject().put("id", "pending-third").put("status", "PENDING");
        persist(p);
        jdbc.update(
                "INSERT INTO mate_presales_artifact(project_id,release_id,filename,digest,content_base64) VALUES(?,?,?,?,?)",
                id,
                "published-second",
                "solution.md",
                PresalesArtifactRenderer.digest(BYTES),
                java.util.Base64.getEncoder().encodeToString(BYTES));
        String before = facts(p);
        var latest = body(handoffRequest(id, null, workspace), 200);
        assertEquals("published-second", latest.path("releaseId").asText());
        assertEquals(body(handoffRequest(id, "published-second", workspace), 200), latest);
        assertEquals(before, facts(p));
    }

    @Test
    void latestMissingFrozenSnapshotCannotRebuildHistoryFromCurrentProject() throws Exception {
        var p = fixture();
        ((com.fasterxml.jackson.databind.node.ObjectNode) p.path("releases").get(0))
                .remove("handoffSnapshot");
        persist(p);
        String before = facts(p);
        for (String release : java.util.Arrays.asList(null, RELEASE)) {
            var response = handoffRequest(p.path("id").asText(), release, workspace);
            assertEquals(409, response.getStatus());
            assertTrue(response.getContentAsString().contains("HISTORICAL_SNAPSHOT_UNAVAILABLE"));
        }
        assertEquals(before, facts(p));
    }

    @Test
    void latestNonObjectFrozenSnapshotCannotRebuildHistoryFromCurrentProject() throws Exception {
        var p = fixture();
        ((com.fasterxml.jackson.databind.node.ObjectNode) p.path("releases").get(0))
                .put("handoffSnapshot", "invalid");
        persist(p);
        String before = facts(p);
        for (String release : java.util.Arrays.asList(null, RELEASE)) {
            var response = handoffRequest(p.path("id").asText(), release, workspace);
            assertEquals(409, response.getStatus());
            assertTrue(response.getContentAsString().contains("HISTORICAL_SNAPSHOT_UNAVAILABLE"));
        }
        assertEquals(before, facts(p));
    }

    @Test
    void noPublishedReleaseRetainsLatestAndExactStatusErrors() throws Exception {
        var p = fixture();
        ((com.fasterxml.jackson.databind.node.ObjectNode) p.path("releases").get(0))
                .put("status", "PENDING");
        persist(p);
        String before = facts(p);
        for (String release : java.util.Arrays.asList(null, RELEASE)) {
            var response = handoffRequest(p.path("id").asText(), release, workspace);
            assertEquals(409, response.getStatus());
            assertTrue(response.getContentAsString().contains("PUBLISHED_RELEASE_REQUIRED"));
        }
        assertEquals(404, handoffRequest(p.path("id").asText(), "missing", workspace).getStatus());
        assertEquals(before, facts(p));
    }

    @Test
    void latestAndExactValidateStoredBytesBeforeMissingSnapshotError() throws Exception {
        var p = fixture();
        ((com.fasterxml.jackson.databind.node.ObjectNode) p.path("releases").get(0))
                .remove("handoffSnapshot");
        persist(p);
        jdbc.update(
                "UPDATE mate_presales_artifact SET digest='invalid' WHERE project_id=?",
                p.path("id").asText());
        String before = facts(p);
        for (String release : java.util.Arrays.asList(null, RELEASE)) {
            var response = handoffRequest(p.path("id").asText(), release, workspace);
            assertEquals(409, response.getStatus());
            assertTrue(response.getContentAsString().contains("ARTIFACT_DIGEST_MISMATCH"));
        }
        assertEquals(before, facts(p));
    }

    @Test
    void latestAndExactReauthorizeWorkspaceBeforeExposingFrozenContent() throws Exception {
        var p = fixture();
        String before = facts(p);
        for (String release : java.util.Arrays.asList(null, RELEASE))
            assertEquals(
                    403,
                    handoffRequest(p.path("id").asText(), release, otherWorkspace).getStatus());
        assertEquals(before, facts(p));
    }

    @org.springframework.beans.factory.annotation.Autowired
    vip.mate.semantic.config.SemanticProperties semantic;

    @Test
    void historicalReadsMatchExactRouteWhenSemanticMutationsAreDisabled() throws Exception {
        var p = fixture();
        String before = facts(p);
        boolean wasEnabled = semantic.isEnabled();
        try {
            semantic.setEnabled(false);
            var exact = body(handoffRequest(p.path("id").asText(), RELEASE, workspace), 200);
            assertEquals(exact, body(handoffRequest(p.path("id").asText(), null, workspace), 200));
            var command =
                    mvc.perform(
                                    org.springframework.test.web.servlet.request
                                            .MockMvcRequestBuilders.post(
                                                    "/api/v1/presales/projects/"
                                                            + p.path("id").asText()
                                                            + "/commands")
                                            .contentType("application/json")
                                            .header("Authorization", tokens.get("owner"))
                                            .header("X-Workspace-Id", workspace)
                                            .content(
                                                    json.writeValueAsString(
                                                            Map.of(
                                                                    "action",
                                                                    "CREATE_RELEASE",
                                                                    "expectedVersion",
                                                                    1,
                                                                    "operationId",
                                                                    UUID.randomUUID().toString(),
                                                                    "payload",
                                                                    Map.of(
                                                                            "solutionId",
                                                                            "solution")))))
                            .andReturn()
                            .getResponse();
            assertEquals(409, command.getStatus());
            assertTrue(command.getContentAsString().contains("SEMANTIC_DISABLED"));
            assertEquals(before, facts(p));
        } finally {
            semantic.setEnabled(wasEnabled);
        }
    }
}

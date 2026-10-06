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
    void publishedReadRejectsContentAndStoredDigestChangedTogether() throws Exception {
        var p = fixture();
        String id = p.path("id").asText();
        byte[] replacement = new byte[] {7, 0, -1, 9};
        store(id, RELEASE, "solution.pptx", replacement);
        var result =
                artifactRequest(
                        "GET",
                        "/projects/" + id + "/releases/" + RELEASE + "/files/solution.pptx",
                        "viewer",
                        workspace,
                        null);
        assertEquals(
                409,
                result.getStatus(),
                "A self-consistent replacement must not replace frozen bytes");
        assertTrue(result.getContentAsString().contains("ARTIFACT_DIGEST_MISMATCH"));
        assertArrayEquals(
                replacement,
                java.util.Base64.getDecoder()
                        .decode(
                                jdbc.queryForObject(
                                        "SELECT content_base64 FROM mate_presales_artifact WHERE project_id=? AND release_id=? AND filename=?",
                                        String.class,
                                        id,
                                        RELEASE,
                                        "solution.pptx")),
                "Failed reads must not repair stored bytes");
    }

    @Test
    void publishedReadRejectsAStoredFileOutsideItsFrozenManifest() throws Exception {
        var p = fixture();
        String id = p.path("id").asText();
        store(id, RELEASE, "undeclared.md", BYTES);
        var result =
                artifactRequest(
                        "GET",
                        "/projects/" + id + "/releases/" + RELEASE + "/files/undeclared.md",
                        "viewer",
                        workspace,
                        null);
        assertEquals(404, result.getStatus());
        assertTrue(result.getContentAsString().contains("NOT_FOUND"));
    }

    @Test
    void publishedReadCannotTreatAnEmptyFrozenDigestAsPermissionToSkipIntegrity() throws Exception {
        var p = fixture();
        String id = p.path("id").asText();
        ((com.fasterxml.jackson.databind.node.ObjectNode)
                        p.path("releases").get(0).path("files").get(0))
                .put("sha256", "");
        persist(p);
        store(id, RELEASE, "solution.pptx", BYTES);
        var result =
                artifactRequest(
                        "GET",
                        "/projects/" + id + "/releases/" + RELEASE + "/files/solution.pptx",
                        "viewer",
                        workspace,
                        null);
        assertEquals(409, result.getStatus());
        assertTrue(result.getContentAsString().contains("ARTIFACT_DIGEST_MISMATCH"));
    }

    @Test
    void previewCannotReadAStoredFileOutsideItsFrozenManifest() throws Exception {
        var p = fixture();
        String id = p.path("id").asText();
        store(id, RELEASE, "solution.pptx", BYTES);
        store(id, RELEASE, "undeclared.md", BYTES);
        var result =
                artifactRequest(
                        "GET",
                        "/projects/" + id + "/releases/" + RELEASE + "/preview/undeclared.md",
                        "owner",
                        workspace,
                        null);
        assertEquals(404, result.getStatus());
        assertTrue(result.getContentAsString().contains("NOT_FOUND"));
    }

    @Test
    void declaredPublishedFileWithoutStoredBytesKeepsNotFound() throws Exception {
        var p = fixture();
        var result =
                artifactRequest(
                        "GET",
                        "/projects/"
                                + p.path("id").asText()
                                + "/releases/"
                                + RELEASE
                                + "/files/solution.pptx",
                        "viewer",
                        workspace,
                        null);
        assertEquals(404, result.getStatus());
        assertTrue(result.getContentAsString().contains("NOT_FOUND"));
    }

    @Test
    void duplicatePublishedManifestEntriesCannotChooseAnArbitraryDigest() throws Exception {
        var p = fixture();
        String id = p.path("id").asText();
        var files =
                (com.fasterxml.jackson.databind.node.ArrayNode)
                        p.path("releases").get(0).path("files");
        files.add(files.get(0).deepCopy());
        persist(p);
        store(id, RELEASE, "solution.pptx", BYTES);
        var result =
                artifactRequest(
                        "GET",
                        "/projects/" + id + "/releases/" + RELEASE + "/files/solution.pptx",
                        "viewer",
                        workspace,
                        null);
        assertEquals(409, result.getStatus());
        assertTrue(result.getContentAsString().contains("ARTIFACT_MISSING"));
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

    @org.springframework.beans.factory.annotation.Autowired
    vip.mate.semantic.config.SemanticProperties semantic;

    private com.fasterxml.jackson.databind.node.ObjectNode frozenFixture() throws Exception {
        var p = fixture();
        var release = (com.fasterxml.jackson.databind.node.ObjectNode) p.path("releases").get(0);
        release.put("baselineId", "baseline");
        var frozen = release.putObject("handoffSnapshot");
        frozen.put("schemaVersion", 1)
                .put("workspaceId", workspace)
                .put("engagementId", p.path("id").asText())
                .put("caseRef", p.path("id").asText());
        frozen.set("baseline", p.path("baselines").get(0).deepCopy());
        frozen.set("solution", p.path("solutions").get(0).deepCopy());
        var frozenRelease = release.deepCopy();
        frozenRelease.remove("handoffSnapshot");
        frozen.set("release", frozenRelease);
        frozen.putArray("materials");
        frozen.putArray("sourceRefs");
        frozen.putArray("fitGaps");
        store(p.path("id").asText(), RELEASE, "solution.pptx", BYTES);
        persist(p);
        return p;
    }

    private String readFacts(com.fasterxml.jackson.databind.node.ObjectNode p) throws Exception {
        return json.writeValueAsString(
                java.util.List.of(
                        jdbc.queryForMap(
                                "SELECT body_json,version FROM mate_presales_project WHERE id=?",
                                p.path("id").asText()),
                        jdbc.queryForList(
                                "SELECT release_id,filename,digest,content_base64 FROM mate_presales_artifact WHERE project_id=? ORDER BY release_id,filename",
                                p.path("id").asText())));
    }

    private void assertFrozenReads(
            com.fasterxml.jackson.databind.node.ObjectNode p, int expected, String error)
            throws Exception {
        for (String route : java.util.List.of("files", "preview")) {
            var r =
                    artifactRequest(
                            "GET",
                            "/projects/"
                                    + p.path("id").asText()
                                    + "/releases/"
                                    + RELEASE
                                    + "/"
                                    + route
                                    + "/solution.pptx",
                            route.equals("files") ? "viewer" : "owner",
                            workspace,
                            null);
            assertEquals(expected, r.getStatus(), route + ": " + r.getContentAsString());
            if (expected == 200) assertArrayEquals(BYTES, r.getContentAsByteArray());
            else assertTrue(r.getContentAsString().contains(error), r.getContentAsString());
        }
    }

    @Test
    void publishedFrozenBytesRemainReadableAfterNewBaselineAndRequirements() throws Exception {
        var p = frozenFixture();
        p.withArray("baselines").addObject().put("id", "new-baseline").putArray("references");
        p.withArray("requirements")
                .addObject()
                .put("id", "new-requirement")
                .put("version", 1)
                .put("scope", "IN");
        persist(p);
        String before = readFacts(p);
        assertFrozenReads(p, 200, null);
        assertEquals(before, readFacts(p));
    }

    @Test
    void publishedFrozenBytesRemainReadableWhenNewPublicationIsDisabled() throws Exception {
        var p = frozenFixture();
        String before = readFacts(p);
        boolean enabled = semantic.isEnabled();
        try {
            semantic.setEnabled(false);
            assertFrozenReads(p, 200, null);
            var r =
                    artifactRequest(
                            "POST",
                            "/projects/" + p.path("id").asText() + "/commands",
                            "owner",
                            workspace,
                            Map.of(
                                    "action",
                                    "CREATE_RELEASE",
                                    "expectedVersion",
                                    p.path("version").asInt(),
                                    "operationId",
                                    UUID.randomUUID().toString(),
                                    "payload",
                                    Map.of("solutionId", SOLUTION)));
            assertEquals(409, r.getStatus());
            assertTrue(r.getContentAsString().contains("SEMANTIC_DISABLED"));
            assertEquals(before, readFacts(p));
        } finally {
            semantic.setEnabled(enabled);
        }
    }

    @Test
    void pendingAndApprovedPreviewRetainCurrentPublicationGate() throws Exception {
        var p = frozenFixture();
        boolean enabled = semantic.isEnabled();
        try {
            semantic.setEnabled(false);
            for (String state : java.util.List.of("PENDING", "APPROVED")) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) p.path("releases").get(0))
                        .put("status", state);
                persist(p);
                String before = readFacts(p);
                var preview =
                        artifactRequest(
                                "GET",
                                "/projects/"
                                        + p.path("id").asText()
                                        + "/releases/"
                                        + RELEASE
                                        + "/preview/solution.pptx",
                                "owner",
                                workspace,
                                null);
                assertEquals(409, preview.getStatus());
                assertTrue(preview.getContentAsString().contains("SEMANTIC_DISABLED"));
                var download =
                        artifactRequest(
                                "GET",
                                "/projects/"
                                        + p.path("id").asText()
                                        + "/releases/"
                                        + RELEASE
                                        + "/files/solution.pptx",
                                "viewer",
                                workspace,
                                null);
                assertEquals(403, download.getStatus());
                assertTrue(download.getContentAsString().contains("RELEASE_NOT_PUBLISHED"));
                assertEquals(before, readFacts(p));
            }
        } finally {
            semantic.setEnabled(enabled);
        }
    }

    @Test
    void legacyOrInvalidSnapshotClaimsCannotBypassCurrentPublicationGate() throws Exception {
        var p = frozenFixture();
        var release = (com.fasterxml.jackson.databind.node.ObjectNode) p.path("releases").get(0);
        var valid = release.path("handoffSnapshot").deepCopy();
        boolean enabled = semantic.isEnabled();
        try {
            semantic.setEnabled(false);
            for (String defect :
                    java.util.List.of(
                            "absent",
                            "non-object",
                            "partial",
                            "schema",
                            "scope",
                            "project",
                            "release",
                            "baseline",
                            "sources",
                            "nested-sources")) {
                var frozen = (com.fasterxml.jackson.databind.node.ObjectNode) valid.deepCopy();
                switch (defect) {
                    case "absent" -> release.remove("handoffSnapshot");
                    case "non-object" -> release.put("handoffSnapshot", "unknown");
                    case "partial" -> {
                        var partial = json.createObjectNode();
                        partial.putArray("materials");
                        release.set("handoffSnapshot", partial);
                    }
                    case "schema" -> {
                        frozen.put("schemaVersion", 2);
                        release.set("handoffSnapshot", frozen);
                    }
                    case "scope" -> {
                        frozen.put("workspaceId", otherWorkspace);
                        release.set("handoffSnapshot", frozen);
                    }
                    case "project" -> {
                        frozen.put("engagementId", "other-project");
                        release.set("handoffSnapshot", frozen);
                    }
                    case "release" -> {
                        ((com.fasterxml.jackson.databind.node.ObjectNode) frozen.path("release"))
                                .put("id", "other-release");
                        release.set("handoffSnapshot", frozen);
                    }
                    case "baseline" -> {
                        ((com.fasterxml.jackson.databind.node.ObjectNode) frozen.path("baseline"))
                                .put("id", "other-baseline");
                        release.set("handoffSnapshot", frozen);
                    }
                    case "sources" -> {
                        frozen.put("sourceRefs", "unknown");
                        release.set("handoffSnapshot", frozen);
                    }
                    case "nested-sources" -> {
                        ((com.fasterxml.jackson.databind.node.ObjectNode) frozen.path("baseline"))
                                .withArray("references")
                                .addObject()
                                .put("graphId", "synthetic")
                                .put("sources", "unknown");
                        release.set("handoffSnapshot", frozen);
                    }
                }
                persist(p);
                String before = readFacts(p);
                assertFrozenReads(p, 409, "SEMANTIC_DISABLED");
                assertEquals(before, readFacts(p));
            }
        } finally {
            semantic.setEnabled(enabled);
        }
    }

    @Test
    void frozenPublishedPreviewStillRequiresAdmin() throws Exception {
        var p = frozenFixture();
        String before = readFacts(p);
        for (String role : java.util.List.of("viewer", "member")) {
            var r =
                    artifactRequest(
                            "GET",
                            "/projects/"
                                    + p.path("id").asText()
                                    + "/releases/"
                                    + RELEASE
                                    + "/preview/solution.pptx",
                            role,
                            workspace,
                            null);
            assertEquals(403, r.getStatus());
        }
        assertFrozenReads(p, 200, null);
        assertEquals(before, readFacts(p));
    }

    @Test
    void frozenPublishedDigestChecksRemainActiveWhenSemanticMutationsAreDisabled()
            throws Exception {
        var p = frozenFixture();
        boolean enabled = semantic.isEnabled();
        jdbc.update(
                "UPDATE mate_presales_artifact SET digest='corrupt' WHERE project_id=?",
                p.path("id").asText());
        String before = readFacts(p);
        try {
            semantic.setEnabled(false);
            assertFrozenReads(p, 409, "ARTIFACT_DIGEST_MISMATCH");
            assertEquals(before, readFacts(p));
        } finally {
            semantic.setEnabled(enabled);
        }
    }
}

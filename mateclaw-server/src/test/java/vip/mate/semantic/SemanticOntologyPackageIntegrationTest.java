package vip.mate.semantic;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import vip.mate.semantic.statement.repository.GovernanceRecordMapper;
import vip.mate.semantic.support.SemanticHttpFixture;

import java.util.*;

class SemanticOntologyPackageIntegrationTest extends SemanticHttpFixture {
    @MockitoSpyBean GovernanceRecordMapper governance;

    @Test
    void standardDocumentWithoutItsImportLockCannotCreatePartialOntology() throws Exception {
        var content = Map.of("packageFormatVersion", 2, "name", "unlocked import", "description", "synthetic",
                "document", owlDocument("Ontology(<urn:test:unlocked> Import(<https://unresolved.invalid/ontology>) Declaration(Class(<urn:test:C>)))"));
        int before = count();
        call("POST", "/ontology-packages/preview", "member", workspace, content, 422);
        String operation = "unlocked-" + UUID.randomUUID();
        call("POST", "/ontology-packages/import", "member", workspace,
                Map.of("package", content, "expectedDigest", "0".repeat(64), "operationId", operation, "name", "unlocked import"), 422);
        assertEquals(before, count());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM mate_semantic_command_record WHERE operation_id=?", Integer.class, operation));
    }

    @Test
    void failedGovernanceWriteRollsBackEntireImportAndAllowsRetry() throws Exception {
        var pack = pack();
        var preview = call("POST", "/ontology-packages/preview", "member", workspace, pack, 200);
        String op = "rollback-" + UUID.randomUUID();
        int before = count();
        var body =
                Map.of(
                        "package",
                        pack,
                        "expectedDigest",
                        preview.path("digest").asText(),
                        "operationId",
                        op,
                        "name",
                        "回滚模板");
        doThrow(new org.springframework.dao.DataIntegrityViolationException("synthetic failure"))
                .when(governance)
                .insert(
                        anyString(),
                        anyLong(),
                        anyString(),
                        anyString(),
                        eq("IMPORT_ONTOLOGY_PACKAGE"),
                        anyString(),
                        anyString(),
                        any());
        try {
            call("POST", "/ontology-packages/import", "member", workspace, body, 500);
        } finally {
            reset(governance);
        }
        assertEquals(before, count());
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM mate_semantic_command_record WHERE operation_id=?",
                        Integer.class,
                        op));
        call("GET", "/ontology-package-imports/" + op, "member", workspace, null, 404);
        var imported = call("POST", "/ontology-packages/import", "member", workspace, body, 200);
        assertEquals(before + 1, count());
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM mate_semantic_ontology_revision WHERE ontology_id=?",
                        Integer.class,
                        imported.path("ontologyId").asText()));
    }

    @Test
    void concurrentImportIsIdempotentAndPackageCanBeClonedAcrossAuthorizedWorkspaces()
            throws Exception {
        var pack = pack();
        var preview = call("POST", "/ontology-packages/preview", "member", workspace, pack, 200);
        var body =
                Map.of(
                        "package",
                        pack,
                        "expectedDigest",
                        preview.path("digest").asText(),
                        "operationId",
                        "concurrent-" + UUID.randomUUID(),
                        "name",
                        "共享模板");
        int before = count();
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var latch = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<com.fasterxml.jackson.databind.JsonNode> command =
                    () -> {
                        latch.await();
                        return call(
                                "POST",
                                "/ontology-packages/import",
                                "member",
                                workspace,
                                body,
                                200);
                    };
            var one = pool.submit(command);
            var two = pool.submit(command);
            latch.countDown();
            assertEquals(
                    one.get(10, java.util.concurrent.TimeUnit.SECONDS),
                    two.get(10, java.util.concurrent.TimeUnit.SECONDS));
        }
        assertEquals(before + 1, count());
        var other = call("POST", "/ontology-packages/import", "owner", otherWorkspace, body, 200);
        assertEquals(
                Long.valueOf(otherWorkspace),
                jdbc.queryForObject(
                        "SELECT workspace_id FROM mate_semantic_ontology WHERE id=?",
                        Long.class,
                        other.path("ontologyId").asText()));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM mate_semantic_graph WHERE workspace_id=?",
                        Integer.class,
                        Long.valueOf(otherWorkspace)));
    }

    @Test
    void previewImportReplayAndExportDoNotCopyBusinessData() throws Exception {
        var pack = pack();
        int before = count();
        var preview = call("POST", "/ontology-packages/preview", "member", workspace, pack, 200);
        assertEquals(before, count());
        assertTrue(preview.path("axiomCount").isNumber());
        String op = "package-" + UUID.randomUUID();
        var body =
                Map.of(
                        "package",
                        pack,
                        "expectedDigest",
                        preview.path("digest").asText(),
                        "operationId",
                        op,
                        "name",
                        "复用设备本体");
        var result = call("POST", "/ontology-packages/import", "member", workspace, body, 200);
        assertEquals(before + 1, count());
        assertEquals(
                result, call("POST", "/ontology-packages/import", "member", workspace, body, 200));
        assertEquals(
                result,
                call("GET", "/ontology-package-imports/" + op, "member", workspace, null, 200));
        call("GET", "/ontology-package-imports/" + op, "viewer", workspace, null, 403);
        call("GET", "/ontology-package-imports/" + op, "owner", otherWorkspace, null, 404);
        var changed = new LinkedHashMap<String, Object>(body);
        changed.put("name", "different");
        call("POST", "/ontology-packages/import", "member", workspace, changed, 409);
        changed.put("operationId", op + "2");
        changed.put("expectedDigest", "wrong");
        call("POST", "/ontology-packages/import", "member", workspace, changed, 409);
        String id = result.path("ontologyId").asText();
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM mate_semantic_graph g JOIN"
                            + " mate_semantic_ontology_revision r ON r.id=g.ontology_revision_id"
                            + " WHERE r.ontology_id=?",
                        Integer.class,
                        id));
        var published =
                publish(id, result.path("draft").path("draftVersion").asLong(), "publish-" + op);
        var exported =
                call(
                        "GET",
                        "/ontologies/"
                                + id
                                + "/revisions/"
                                + published.path("id").asText()
                                + "/package",
                        "viewer",
                        workspace,
                        null,
                        200);
        assertFalse(exported.has("workspaceId"));
        assertFalse(exported.has("facts"));
        assertEquals(2, exported.path("packageFormatVersion").asInt());
        assertEquals("owl-document-v1",exported.path("document").path("modelSchema").asText());
        call("POST", "/ontology-packages/preview", "member", workspace, exported, 200);
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM mate_semantic_governance_record WHERE ontology_id=?"
                                + " AND action='IMPORT_ONTOLOGY_PACKAGE'",
                        Integer.class,
                        id));
    }

    @Test
    void strictJsonAndBoundedUploadsRejectBeforeAnyWrite() throws Exception {
        int before = count();
        String data = json.writeValueAsString(pack());
        assertEquals(400, raw("{\"packageFormatVersion\":1," + data.substring(1)).getStatus());
        assertEquals(400, raw(data + " {}").getStatus());
        var numeric = new LinkedHashMap<>(pack());
        numeric.put("name", 42);
        call("POST", "/ontology-packages/preview", "member", workspace, numeric, 400);
        assertEquals(413, raw(" ".repeat(1024 * 1024 + 1)).getStatus());
        var bad = new LinkedHashMap<>(pack());
        bad.put("remoteUrl", "https://invalid.example/model");
        call("POST", "/ontology-packages/preview", "member", workspace, bad, 400);
        assertEquals(before, count());
    }

    @Test
    void exactDocumentDigestAndIndependentClonesPreserveIdentity() throws Exception {
        var original=json.valueToTree(pack()); var changed=original.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode)changed.path("document"))
            .put("documentText",changed.path("document").path("documentText").asText()+"\n");
        var first=call("POST","/ontology-packages/preview","member",workspace,original,200);
        var second=call("POST","/ontology-packages/preview","member",workspace,changed,200);
        assertNotEquals(first.path("digest"),second.path("digest"),"package identity preserves exact source bytes");
        String op=UUID.randomUUID().toString();
        var body=new LinkedHashMap<String,Object>(Map.of("package",original,"expectedDigest",first.path("digest").asText(),"operationId",op,"name","First"));
        var imported=call("POST","/ontology-packages/import","member",workspace,body,200);
        body.put("package",changed);
        call("POST","/ontology-packages/import","member",workspace,body,409);
        body.put("package",original);body.put("operationId",UUID.randomUUID().toString());body.put("name","Second");
        var clone=call("POST","/ontology-packages/import","member",workspace,body,200);
        assertNotEquals(imported.path("ontologyId"),clone.path("ontologyId"));
    }
    private Map<String,Object> pack(){return Map.of("packageFormatVersion",2,"name","设备😀模板","description","合成验证模型","document",definition());}

    private int count() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM mate_semantic_ontology WHERE workspace_id=?",
                Integer.class,
                Long.valueOf(workspace));
    }

    private MockHttpServletResponse raw(String body) throws Exception {
        return mvc.perform(
                        post("/api/v1/semantic/ontology-packages/preview")
                                .header("Authorization", tokens.get("member"))
                                .header("X-Workspace-Id", workspace)
                                .contentType("application/json")
                                .content(body))
                .andReturn()
                .getResponse();
    }
}

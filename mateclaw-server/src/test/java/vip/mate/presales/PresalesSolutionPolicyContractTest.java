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
class PresalesSolutionPolicyContractTest extends SemanticHttpFixture {
    @ParameterizedTest
    @ValueSource(strings = {"4294967297", "8589934593"})
    void wrappedBaselineRevisionCannotMatchVersionOne(String raw) throws Exception {
        var p = project();
        p.withArray("baselines")
                .addObject()
                .put("id", "base")
                .put("version", 1)
                .putArray("references");
        seed(p);
        var d = draft().put("baselineId", "base");
        d.set("baselineVersion", json.readTree(raw));
        var before = revisionFacts(p);
        rejected(p, d, 409, "BASELINE_STALE", "Solution baseline version is stale");
        assertEquals(before, revisionFacts(p));
    }

    @ParameterizedTest
    @ValueSource(strings = {"4294967297", "1.5", "\"1.5\"", "null", "true", "{}", "[]", "0", "-1"})
    void malformedStoredBaselineRevisionCannotBeCapturedAsValid(String raw) throws Exception {
        var p = project();
        var baseline = p.withArray("baselines").addObject().put("id", "base");
        baseline.set("version", json.readTree(raw));
        baseline.putArray("references");
        seed(p);
        var before = revisionFacts(p);
        rejected(
                p,
                draft().put("baselineId", "base"),
                409,
                "BASELINE_STALE",
                "Solution baseline version is stale");
        assertEquals(before, revisionFacts(p));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, Integer.MAX_VALUE})
    void exactBaselineRevisionKeepsValidIntegerWire(int revision) throws Exception {
        var p = project();
        p.withArray("baselines")
                .addObject()
                .put("id", "base")
                .put("version", revision)
                .putArray("references");
        seed(p);
        var d = draft().put("baselineId", "base").put("baselineVersion", revision);
        var solution = save(p, d, 200).path("data").path("solutions").get(0);
        assertTrue(solution.path("baselineVersion").isIntegralNumber());
        assertEquals(revision, solution.path("baselineVersion").intValue());
        assertEquals("keep", solution.path("legacyExtension").asText());
    }

    private List<?> revisionFacts(ObjectNode p) {
        String id = p.path("id").asText();
        return List.of(
                jdbc.queryForList(
                        "SELECT version,body_json FROM mate_presales_project WHERE id=?", id),
                jdbc.queryForList(
                        "SELECT version,body_json FROM mate_presales_revision WHERE project_id=? ORDER BY version",
                        id),
                jdbc.queryForList(
                        "SELECT operation_id,request_hash,response_json FROM mate_presales_operation WHERE workspace_id=? ORDER BY operation_id",
                        workspace));
    }

    @Test
    void historicalIntegerTextBaselineIsCapturedAsIntegerWithoutRewritingHistory()
            throws Exception {
        var p = project();
        p.withArray("baselines")
                .addObject()
                .put("id", "base")
                .put("version", " +01 ")
                .putArray("references");
        seed(p);
        var saved = save(p, draft().put("baselineId", "base"), 200).path("data");
        assertEquals(p.path("baselines"), saved.path("baselines"));
        assertTrue(saved.path("solutions").get(0).path("baselineVersion").isIntegralNumber());
        assertEquals(1, saved.path("solutions").get(0).path("baselineVersion").intValue());
    }

    private JsonNode api(String method, String path, Object body, int status) throws Exception {
        var request =
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(
                                org.springframework.http.HttpMethod.valueOf(method),
                                "/api/v1/presales" + path)
                        .header("Authorization", tokens.get("member"))
                        .header("X-Workspace-Id", workspace)
                        .contentType("application/json");
        if (body != null) request.content(json.writeValueAsString(body));
        var response = mvc.perform(request).andReturn().getResponse();
        assertEquals(status, response.getStatus(), response.getContentAsString());
        return json.readTree(response.getContentAsString());
    }

    private ObjectNode project() throws Exception {
        return (ObjectNode)
                api(
                                "POST",
                                "/projects",
                                Map.of(
                                        "name",
                                        "policy-" + UUID.randomUUID(),
                                        "customer",
                                        "customer",
                                        "expectedVersion",
                                        0,
                                        "operationId",
                                        UUID.randomUUID().toString()),
                                200)
                        .path("data");
    }

    private void seed(ObjectNode p) {
        jdbc.update(
                "UPDATE mate_presales_project SET body_json=? WHERE id=?",
                p.toString(),
                p.path("id").asText());
    }

    private String stored(ObjectNode p) {
        return jdbc.queryForObject(
                "SELECT body_json FROM mate_presales_project WHERE id=?",
                String.class,
                p.path("id").asText());
    }

    private ObjectNode draft() {
        var d = json.createObjectNode().put("title", "方案").put("legacyExtension", "keep");
        d.putArray("sections")
                .addObject()
                .put("title", "章节")
                .put("text", "正文")
                .putArray("requirementRefs");
        return d;
    }

    private JsonNode save(ObjectNode p, ObjectNode draft, int status) throws Exception {
        return api(
                "POST",
                "/projects/" + p.path("id").asText() + "/commands",
                Map.of(
                        "expectedVersion",
                        p.path("version").asInt(),
                        "operationId",
                        UUID.randomUUID().toString(),
                        "action",
                        "SAVE_SOLUTION",
                        "payload",
                        draft),
                status);
    }

    private void rejected(ObjectNode p, ObjectNode d, int status, String code, String message)
            throws Exception {
        String before = stored(p);
        var r = save(p, d, status);
        assertEquals(code, r.path("data").path("code").asText());
        if (message != null) assertEquals(message, r.path("msg").asText());
        assertEquals(before, stored(p));
    }

    @Test
    void provisionalDraftCoverageAndImmutableReplacementKeepWire() throws Exception {
        var p = project();
        p.withArray("requirements").addObject().put("id", "9007199254740993001").put("scope", "IN");
        p.withArray("requirements").addObject().put("id", "out").put("scope", "OUT");
        p.withArray("requirements").addObject().put("id", "unknown").put("scope", "UNKNOWN");
        p.withArray("fitGaps")
                .addObject()
                .put("requirementId", "9007199254740993001")
                .put("id", "fit-old");
        p.withArray("fitGaps").addObject().put("requirementId", "out").put("id", "fit-out");
        p.withArray("fitGaps")
                .addObject()
                .put("requirementId", "9007199254740993001")
                .put("id", "fit-new");
        seed(p);
        var d = draft();
        ((ObjectNode) d.withArray("sections").get(0))
                .withArray("requirementRefs")
                .add("9007199254740993001");
        d.putArray("requirementResponses")
                .addObject()
                .put("requirementId", "9007199254740993001")
                .put("status", "PARTIAL")
                .put("reason", "边界明确");
        d.withArray("requirementResponses")
                .addObject()
                .put("requirementId", "out")
                .put("status", "EXCLUDED")
                .put("reason", "不在范围");
        d.put("baselineVersion", 99);
        var saved = (ObjectNode) save(p, d, 200).path("data");
        var solution = saved.path("solutions").get(0);
        assertTrue(solution.path("provisional").asBoolean());
        assertFalse(solution.has("baselineVersion"));
        assertEquals("keep", solution.path("legacyExtension").asText());
        assertEquals("100.0", solution.path("coverage").path("percentage").asText());
        assertEquals(1, solution.path("coverage").path("totalIn").asInt());
        assertEquals(1, solution.path("coverage").path("handledIn").asInt());
        assertEquals(
                "unknown",
                solution.path("coverage").path("responses").get(2).path("requirementId").asText());
        assertEquals(
                "UNHANDLED",
                solution.path("coverage").path("responses").get(2).path("status").asText());
        assertEquals(json.valueToTree(List.of("fit-new", "fit-out")), solution.path("fitGapRefs"));
        var again = d.deepCopy().put("id", solution.path("id").asText());
        var next = save(saved, again, 200).path("data").path("solutions");
        assertEquals(2, next.size());
        assertEquals(solution, next.get(0));
        assertEquals(solution.path("id").asText(), next.get(1).path("previousId").asText());
        assertNotEquals(solution.path("id").asText(), next.get(1).path("id").asText());
        assertEquals(2, next.get(1).path("version").asInt());
    }

    @Test
    void exactBaselineAndSourceInventoryArePreserved() throws Exception {
        long kb = Long.parseLong(workspace);
        jdbc.update(
                "INSERT INTO mate_wiki_knowledge_base(id,name,workspace_id,create_time,update_time,deleted) VALUES(?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)",
                kb,
                "policy-source",
                Long.parseLong(workspace));
        for (long raw : List.of(kb + 1, kb + 2))
            jdbc.update(
                    "INSERT INTO mate_wiki_raw_material(id,kb_id,title,original_content,create_time,update_time,deleted) VALUES(?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)",
                    raw,
                    kb,
                    "source",
                    "source text");
        var p = project();
        var b = p.withArray("baselines").addObject().put("id", "base").put("version", 3);
        b.putArray("references")
                .addObject()
                .put("requirementId", "r")
                .put("scope", "IN")
                .putArray("sources")
                .addObject()
                .put("sourceRef", String.valueOf(kb + 1));
        p.withArray("tasks")
                .addObject()
                .putObject("contextSnapshot")
                .putArray("sources")
                .addObject()
                .put("sourceRef", String.valueOf(kb + 2));
        seed(p);
        var d = draft().put("baselineId", "base");
        d.putArray("sourceRefs").add(String.valueOf(kb + 2));
        ((ObjectNode) d.withArray("sections").get(0))
                .putArray("sourceRefs")
                .add(String.valueOf(kb + 1));
        var saved = save(p, d, 200).path("data").path("solutions").get(0);
        assertFalse(saved.path("provisional").asBoolean());
        assertEquals(3, saved.path("baselineVersion").asInt());
        assertEquals(0, saved.path("coverage").path("handledIn").asInt());
        assertEquals(
                "UNHANDLED",
                saved.path("coverage").path("responses").get(0).path("status").asText());
        assertEquals(d.path("sourceRefs"), saved.path("sourceRefs"));
    }

    @Test
    void validationOrderAndErrorsDoNotWriteProject() throws Exception {
        var p = project();
        var d = draft().put("title", "").put("presentation", true).put("baselineId", "missing");
        rejected(p, d, 400, "INVALID_REQUEST", null);
        d = draft().put("presentation", true).put("baselineId", "missing");
        rejected(p, d, 422, "PRESENTATION_METADATA_UNTRUSTED", null);
        d = draft().put("baselineId", "missing");
        d.putArray("sourceRefs").add("unknown");
        rejected(p, d, 404, "NOT_FOUND", null);
        var b = p.withArray("baselines").addObject().put("id", "base").put("version", 3);
        b.putArray("references");
        seed(p);
        d = draft().put("baselineId", "base").put("baselineVersion", 2);
        d.putArray("sourceRefs").add("unknown");
        rejected(p, d, 409, "BASELINE_STALE", null);
        d = draft();
        d.putArray("sourceRefs").add("unknown");
        rejected(p, d, 422, "INVALID_SOURCE_REFERENCE", null);
        d = draft();
        d.put("sourceRefs", "wrong");
        rejected(p, d, 422, "MODEL_FORMAT", null);
    }

    @Test
    void invalidCoverageRejectsWhileEmptyScopeKeepsNullPercentage() throws Exception {
        var p = project();
        var d = draft();
        var empty = save(p, d, 200).path("data");
        assertFalse(empty.path("solutions").get(0).path("coverage").path("applicable").asBoolean());
        assertTrue(empty.path("solutions").get(0).path("coverage").path("percentage").isNull());
        p = (ObjectNode) empty;
        p.withArray("requirements").addObject().put("id", "r").put("scope", "IN");
        seed(p);
        d = draft();
        d.putArray("requirementResponses")
                .addObject()
                .put("requirementId", "r")
                .put("status", "FULL");
        rejected(p, d, 400, "INVALID_REQUEST", null);
        d = draft();
        d.putArray("requirementResponses")
                .addObject()
                .put("requirementId", "r")
                .put("status", "EXCLUDED")
                .put("reason", "no");
        rejected(p, d, 400, "INVALID_REQUEST", null);
        d = draft();
        d.putArray("requirementResponses").addObject().put("requirementId", "r");
        d.withArray("requirementResponses").addObject().put("requirementId", "r");
        rejected(p, d, 400, "INVALID_REQUEST", null);
    }
}

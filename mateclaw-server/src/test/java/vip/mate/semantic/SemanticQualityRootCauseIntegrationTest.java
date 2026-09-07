package vip.mate.semantic;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import vip.mate.semantic.support.SemanticHttpFixture;

import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** A quality investigation must distinguish observations, competing hypotheses and reviewed causes. */
class SemanticQualityRootCauseIntegrationTest extends SemanticHttpFixture {
    @org.springframework.beans.factory.annotation.Autowired vip.mate.semantic.tool.SemanticTool tool;
    @Override
    protected Map<String, Object> definition() {
        return Map.of("types", List.of(type("Batch", "生产批次"), type("Equipment", "加工设备"),
                        type("QualityIssue", "质量问题"), type("Cause", "根因")),
                "properties", List.of(Map.of("key", "deviation", "label", "尺寸偏差", "description", "实测相对名义尺寸偏差",
                        "ownerTypeKey", "QualityIssue", "valueType", "DECIMAL", "multiplicity", "SINGLE", "fixedUnit", "mm")),
                "relations", List.of(relation("producedOn", "加工设备", "Batch", "Equipment", "SINGLE"),
                        relation("hasIssue", "发现问题", "Batch", "QualityIssue", "MULTI"),
                        relation("rootCause", "确认根因", "QualityIssue", "Cause", "SINGLE")));
    }

    @Test
    void evidenceBackedRootCauseSurvivesVersionPublicationButNotEvidenceWithdrawal() throws Exception {
        String ontology = create();
        JsonNode draft = save(ontology, draft(ontology).path("draftVersion").asLong());
        JsonNode v1 = publish(ontology, draft.path("draftVersion").asLong(), op());
        String kb = id(); LocalDateTime now = LocalDateTime.now();
        jdbc.update("INSERT INTO mate_wiki_knowledge_base(id,name,status,page_count,raw_count,workspace_id,create_time,update_time,deleted) VALUES(?,?,?,0,0,?,?,?,0)",
                Long.valueOf(kb), "尺寸超差根因调查", "active", Long.valueOf(workspace), now, now);
        JsonNode binding = call("PUT", "/knowledge-bases/" + kb + "/binding", "owner", workspace,
                Map.of("action", "ENABLE", "revisionId", v1.path("id").asText()), 200);
        String graph = binding.path("graphId").asText(), base = "/graphs/" + graph;
        String batch = entity(base, "Batch", "LOT-QA-0908"), machine = entity(base, "Equipment", "CNC-07");
        String issue = entity(base, "QualityIssue", "孔径超差"), wear = entity(base, "Cause", "刀具磨损"), fixture = entity(base, "Cause", "夹具偏移");

        Source inspection = source(base, kb, "终检记录", "检验🔎：LOT-QA-0908由CNC-07加工，发现孔径超差，偏差0.08mm。", now);
        Source investigation = source(base, kb, "根因复核报告", "复核：刀具磨损导致孔径超差；更换刀具后偏差恢复。", now);
        Source hypothesis = source(base, kb, "初步调查", "初步假设：夹具偏移可能导致孔径超差，尚待复核。", now);
        accept(base, proposeRelation(base, batch, "producedOn", machine, inspection.evidence()));
        accept(base, proposeRelation(base, batch, "hasIssue", issue, inspection.evidence()));
        Map<String, Object> measured = new HashMap<>(Map.of("operationId", op(), "subjectId", issue,
                "predicateKind", "PROPERTY", "predicateKey", "deviation", "valueType", "DECIMAL", "value", "0.08",
                "unit", "mm", "validityKind", "INTERVAL", "evidenceIds", List.of(inspection.evidence())));
        measured.put("validFrom", "2026-09-08T00:00:00Z");
        accept(base, call("POST", base + "/statements", "member", workspace, measured, 200));

        JsonNode wearCandidate = proposeRelation(base, issue, "rootCause", wear, investigation.evidence());
        JsonNode fixtureCandidate = proposeRelation(base, issue, "rootCause", fixture, hypothesis.evidence());
        assertEquals(0, search(base, "rootCause").size(), "Hypotheses are not trusted roots before review");
        review(base, wearCandidate, "viewer", 403);
        review(base, wearCandidate, "member", 403);
        review(base, wearCandidate, "owner", 409);
        JsonNode conflict = call("GET", base + "/conflicts", "owner", workspace, null, 200).path("items").get(0);
        Map<String, Object> resolution = Map.of("winnerStatementId", wearCandidate.path("id").asText(),
                "expectedMembers", List.of(member(wearCandidate, 1), member(fixtureCandidate, 1)),
                "reason", "依据复核报告和换刀验证确认刀具磨损，排除夹具偏移", "operationId", op());
        call("POST", base + "/conflicts/" + conflict.path("id").asText() + "/resolve", "owner", workspace, resolution, 200);
        review(base, wearCandidate, "owner", 409); // stale revision cannot overwrite the decision
        JsonNode root = search(base, "rootCause").get(0);
        assertEquals(wear, root.path("targetEntityId").asText());
        assertEquals(2, root.path("revision").asInt());
        assertEquals(investigation.evidence(), root.path("evidenceIds").get(0).asText());
        assertEquals(root.path("id"), search(base, "刀具磨损").get(0).path("id"), "Root cause must be discoverable by its target label");
        assertEquals(root.path("id"), search(base, "确认根因").get(0).path("id"), "Business users search the ontology label, not only the predicate key");
        JsonNode labeled = call("POST", base + "/search", "viewer", workspace, Map.of("query", "确认根因", "limit", 20), 200);
        assertEquals("刀具磨损", labeled.path("entityLabels").path(wear).asText());
        assertEquals("确认根因", labeled.path("predicateLabels").path("RELATION:rootCause").asText());
        assertFalse(labeled.path("entityLabels").has(fixture), "Do not expose labels of excluded or unreturned facts");

        JsonNode newerDraft = call("POST", "/ontologies/" + ontology + "/draft", "member", workspace,
                Map.of("baseRevisionId", v1.path("id").asText()), 200);
        JsonNode savedV2 = save(ontology, newerDraft.path("draftVersion").asLong());
        JsonNode v2 = publish(ontology, savedV2.path("draftVersion").asLong(), op());
        JsonNode detail = call("GET", base, "viewer", workspace, null, 200);
        assertEquals(v1.path("id"), detail.path("binding").path("ontologyRevisionId"));
        call("PUT", "/knowledge-bases/" + kb + "/binding", "owner", workspace,
                Map.of("action", "REBIND", "revisionId", v2.path("id").asText(),
                        "expectedGraphVersion", detail.path("binding").path("graphVersion").asLong()), 409);
        JsonNode neighborhood = call("GET", base + "/neighbors?entityId=" + batch + "&depth=2", "viewer", workspace, null, 200);
        assertEquals(4, neighborhood.path("nodes").size());
        assertEquals(3, neighborhood.path("edges").size());
        assertTrue(call("GET", base + "/statements", "viewer", workspace, null, 200).path("total").isNumber(), "Pagination count must remain numeric despite global Snowflake serialization");
        assertTrue(call("GET", base + "/snapshots", "viewer", workspace, null, 200).path("items").get(0).path("captureVersion").isNumber());
        assertTrue(call("GET", base + "/sources", "viewer", workspace, null, 200).path("items").get(0).path("snapshotCount").isNumber());
        call("GET", base + "/neighbors?entityId=" + batch, "owner", otherWorkspace, null, 404);
        call("GET", base + "/evidence/" + investigation.evidence(), "owner", otherWorkspace, null, 404);

        Long viewer = jdbc.queryForObject("SELECT user_id FROM mate_workspace_member WHERE workspace_id=? AND role='viewer' AND deleted=0", Long.class, Long.valueOf(workspace));
        var toolContext = vip.mate.agent.context.ChatOrigin.web("quality-review", "display-only", Long.valueOf(workspace), null, null, viewer).withAgent(agentWithKnowledgeBase(kb)).toToolContext();
        assertEquals(1, tool.semantic_search(graph, "刀具磨损", 5, toolContext).facts().size());
        for (boolean deleted : List.of(true, false)) {
            jdbc.update("UPDATE mate_wiki_knowledge_base SET deleted=?,workspace_id=? WHERE id=?", deleted ? 1 : 0,
                    Long.valueOf(deleted ? workspace : otherWorkspace), Long.valueOf(kb));
            call("GET", base, "viewer", workspace, null, 404);
            call("POST", base + "/search", "viewer", workspace, Map.of("query", "刀具磨损"), 404);
            call("GET", base + "/evidence/" + inspection.evidence(), "viewer", workspace, null, 404);
            call("POST", base + "/entities", "member", workspace, Map.of("typeKey", "Batch", "displayName", "must-not-write"), 404);
            assertEquals(404, assertThrows(vip.mate.semantic.web.SemanticApiException.class,
                    () -> tool.semantic_search(graph, "刀具磨损", 5, toolContext)).status());
        }
        jdbc.update("UPDATE mate_wiki_knowledge_base SET deleted=0,workspace_id=? WHERE id=?", Long.valueOf(workspace), Long.valueOf(kb));

        call("POST", base + "/sources/withdraw", "owner", workspace,
                Map.of("sourceKind", "WIKI_RAW", "sourceRef", investigation.raw(), "reason", "复核报告被撤销，根因需重审", "operationId", op()), 200);
        assertEquals(0, search(base, "rootCause").size());
        assertEquals(2, search(base, "LOT-QA-0908").size(), "Independent inspection topology must remain trusted");
        assertEquals(1, search(base, "deviation").size(), "Measured deviation is independent of root-cause evidence");
        call("GET", base + "/evidence/" + investigation.evidence(), "viewer", workspace, null, 404);
        assertEquals(inspection.text(), call("GET", base + "/evidence/" + inspection.evidence(), "viewer", workspace, null, 200).path("exactQuote").asText());
        JsonNode history = call("GET", base + "/statements/" + wearCandidate.path("id").asText() + "/revisions", "owner", workspace, null, 200).path("revisions");
        assertEquals(2, history.size());
        assertEquals("SUPPORT_LOST", history.get(1).path("supportStatus").asText());
        assertEquals(v1.path("id"), history.get(1).path("ontologyRevisionId"));
        assertEquals(2, jdbc.queryForObject("SELECT current_revision FROM mate_semantic_statement WHERE id=?", Integer.class, wearCandidate.path("id").asText()));
    }

    private JsonNode search(String base, String query) throws Exception {
        return call("POST", base + "/search", "viewer", workspace, Map.of("query", query, "limit", 20), 200).path("facts");
    }
    private String entity(String base, String type, String label) throws Exception {
        return call("POST", base + "/entities", "member", workspace, Map.of("typeKey", type, "displayName", label), 200).path("id").asText();
    }
    private JsonNode proposeRelation(String base, String subject, String key, String target, String evidence) throws Exception {
        return call("POST", base + "/statements", "member", workspace,
                Map.of("operationId", op(), "subjectId", subject, "predicateKind", "RELATION", "predicateKey", key,
                        "valueType", "ENTITY", "targetEntityId", target, "validityKind", "INTERVAL", "evidenceIds", List.of(evidence)), 200);
    }
    private void accept(String base, JsonNode fact) throws Exception { review(base, fact, "owner", 200); }
    private void review(String base, JsonNode fact, String role, int status) throws Exception {
        call("POST", base + "/statements/" + fact.path("id").asText() + "/review", role, workspace,
                Map.of("expectedRevision", fact.path("revision").asInt(), "action", "ACCEPT", "reason", "核验原文", "operationId", op()), status);
    }
    private Source source(String base, String kb, String title, String text, LocalDateTime now) throws Exception {
        String raw = id();
        jdbc.update("INSERT INTO mate_wiki_raw_material(id,kb_id,title,source_type,original_content,file_size,processing_status,create_time,update_time,deleted) VALUES(?,?,?,?,?,?,?,?,?,0)",
                Long.valueOf(raw), Long.valueOf(kb), title, "text", text, text.length(), "completed", now, now);
        JsonNode job = call("POST", base + "/imports", "member", workspace,
                Map.of("sourceKind", "WIKI_RAW", "sourceRef", raw, "operationId", op()), 200);
        JsonNode evidence = call("POST", base + "/snapshots/" + job.path("snapshotId").asText() + "/evidence", "member", workspace,
                Map.of("operationId", op(), "startCodePoint", 0, "endCodePoint", text.codePointCount(0, text.length()), "exactQuote", text), 200);
        return new Source(raw, evidence.path("id").asText(), text);
    }
    private record Source(String raw, String evidence, String text) {}
    private static Map<String, String> type(String key, String label) { return Map.of("key", key, "label", label, "description", ""); }
    private static Map<String, String> relation(String key, String label, String from, String to, String multiplicity) {
        return Map.of("key", key, "label", label, "description", "", "sourceTypeKey", from, "targetTypeKey", to, "multiplicity", multiplicity);
    }
    private static Map<String, Object> member(JsonNode fact, int revision) { return Map.of("statementId", fact.path("id").asText(), "revision", revision); }
    private static String id() { return com.baomidou.mybatisplus.core.toolkit.IdWorker.getIdStr(); }
    private static String op() { return UUID.randomUUID().toString(); }
}

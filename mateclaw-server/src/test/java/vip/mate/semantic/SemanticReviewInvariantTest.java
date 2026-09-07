package vip.mate.semantic;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import vip.mate.semantic.support.SemanticHttpFixture;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SemanticReviewInvariantTest extends SemanticHttpFixture {
    @Test
    void pairResolutionCannotOverrideAThirdAcceptedValue() throws Exception {
        Fixture f = fixture(); JsonNode existing = propose(f, "220"); review(f, existing, "ACCEPT", 200);
        JsonNode a = propose(f, "380"), b = propose(f, "400");
        JsonNode pair = pair(f, a, b);
        resolve(f, pair, a, 409);
        JsonNode accepted = call("GET", f.base + "/statements?view=trusted", "viewer", workspace, null, 200).path("items");
        assertEquals(1, accepted.size()); assertEquals("220", accepted.get(0).path("value").asText());
        assertEquals(3, open(f).size());
        assertEquals(1, jdbc.queryForObject("SELECT current_revision FROM mate_semantic_statement WHERE id=?", Integer.class, a.path("id").asText()));
    }

    @Test
    void rejectingAConflictingCandidateLeavesAValidPathForItsPeer() throws Exception {
        Fixture f = fixture(); JsonNode a = propose(f, "380"), b = propose(f, "400");
        review(f, a, "REJECT", 200);
        assertEquals(0, open(f).size());
        review(f, b, "ACCEPT", 200);
        assertEquals("400", call("GET", f.base + "/statements?view=trusted", "viewer", workspace, null, 200).path("items").get(0).path("value").asText());
    }

    @Test
    void resolutionRefreshesOtherLiveConflictsInsteadOfClosingThem() throws Exception {
        Fixture f = fixture(); JsonNode a = propose(f, "380"), b = propose(f, "400"), c = propose(f, "220");
        resolve(f, pair(f, a, b), a, 200);
        List<JsonNode> remaining = open(f);
        assertEquals(1, remaining.size(), "A vs C must remain reviewable; B is rejected");
        JsonNode ac = remaining.getFirst();
        JsonNode member = ac.path("left").path("statementId").equals(a.path("id")) ? ac.path("left") : ac.path("right");
        assertEquals(2, member.path("revision").asInt());
        resolve(f, ac, c, 200);
        JsonNode accepted = call("GET", f.base + "/statements?view=trusted", "viewer", workspace, null, 200).path("items");
        assertEquals(1, accepted.size()); assertEquals("220", accepted.get(0).path("value").asText());
    }

    @Test
    void withdrawnEvidenceCannotBeApprovedThroughChangeOrConflict() throws Exception {
        Fixture f = fixture(); JsonNode accepted = propose(f, "380"); review(f, accepted, "ACCEPT", 200);
        JsonNode change = call("POST", f.base + "/statements/" + accepted.path("id").asText() + "/changes", "member", workspace,
                Map.of("expectedRevision", 2, "operationId", op(), "content", content(f, "381")), 200);
        JsonNode replacement = propose(f, "400"); JsonNode acceptedPair = pair(f, accepted, replacement);
        withdraw(f);
        call("POST", f.base + "/changes/" + change.path("id").asText() + "/review", "owner", workspace,
                Map.of("expectedRevision", 2, "action", "ACCEPT", "reason", "must recheck source", "operationId", op()), 422);
        resolve(f, acceptedPair, accepted, 422);
        assertEquals(2, jdbc.queryForObject("SELECT current_revision FROM mate_semantic_statement WHERE id=?", Integer.class, accepted.path("id").asText()));
        Fixture competing = fixture(); JsonNode a = propose(competing, "380"), b = propose(competing, "400");
        JsonNode pair = pair(competing, a, b); withdraw(competing); resolve(competing, pair, a, 422);
        assertEquals(1, open(competing).size());
    }

    @Test
    void changeReviewEnforcesItsRevisionAndStalesSiblingProposals() throws Exception {
        Fixture f = fixture(); JsonNode accepted = propose(f, "380"); review(f, accepted, "ACCEPT", 200);
        JsonNode first = change(f, accepted, "381"), sibling = change(f, accepted, "381");
        call("POST", f.base + "/changes/" + first.path("id").asText() + "/review", "owner", workspace,
                Map.of("expectedRevision", 999, "action", "ACCEPT", "reason", "stale client", "operationId", op()), 409);
        String operation = op();
        call("POST", f.base + "/changes/" + first.path("id").asText() + "/review", "owner", workspace,
                Map.of("expectedRevision", 2, "action", "ACCEPT", "reason", "verified", "operationId", operation), 200);
        assertEquals("STALE", jdbc.queryForObject("SELECT status FROM mate_semantic_change_proposal WHERE id=?", String.class, sibling.path("id").asText()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM mate_semantic_governance_event WHERE graph_id=(SELECT graph_id FROM mate_semantic_change_proposal WHERE id=?) AND operation_id=?", Integer.class, first.path("id").asText(), operation));
    }

    private Fixture fixture() throws Exception {
        String ontology = create(); JsonNode saved = save(ontology, draft(ontology).path("draftVersion").asLong());
        String revision = publish(ontology, saved.path("draftVersion").asLong(), op()).path("id").asText();
        String kb = id(), raw = id(); LocalDateTime now = LocalDateTime.now();
        jdbc.update("INSERT INTO mate_wiki_knowledge_base(id,name,status,page_count,raw_count,workspace_id,create_time,update_time,deleted) VALUES(?,?,?,0,0,?,?,?,0)", Long.valueOf(kb), "review invariants", "active", Long.valueOf(workspace), now, now);
        String graph = call("PUT", "/knowledge-bases/" + kb + "/binding", "owner", workspace, Map.of("action", "ENABLE", "revisionId", revision), 200).path("graphId").asText(), base = "/graphs/" + graph;
        String entity = call("POST", base + "/entities", "member", workspace, Map.of("typeKey", "Equipment", "displayName", "P-101"), 200).path("id").asText();
        String text = "Records: 220V 380V 400V";
        jdbc.update("INSERT INTO mate_wiki_raw_material(id,kb_id,title,source_type,original_content,file_size,processing_status,create_time,update_time,deleted) VALUES(?,?,?,?,?,?,?,?,?,0)", Long.valueOf(raw), Long.valueOf(kb), "test", "text", text, text.length(), "completed", now, now);
        String snapshot = call("POST", base + "/imports", "member", workspace, Map.of("sourceKind", "WIKI_RAW", "sourceRef", raw, "operationId", op()), 200).path("snapshotId").asText();
        String evidence = call("POST", base + "/snapshots/" + snapshot + "/evidence", "member", workspace, Map.of("operationId", op(), "startCodePoint", 0, "endCodePoint", text.length(), "exactQuote", text), 200).path("id").asText();
        return new Fixture(base, entity, raw, evidence);
    }
    private Map<String, Object> content(Fixture f, String value) { return Map.of("operationId", op(), "subjectId", f.entity, "predicateKind", "PROPERTY", "predicateKey", "voltage", "valueType", "DECIMAL", "value", value, "unit", "V", "validityKind", "INTERVAL", "evidenceIds", List.of(f.evidence)); }
    private JsonNode propose(Fixture f, String value) throws Exception { return call("POST", f.base + "/statements", "member", workspace, content(f, value), 200); }
    private JsonNode change(Fixture f, JsonNode fact, String value) throws Exception { int revision=jdbc.queryForObject("SELECT current_revision FROM mate_semantic_statement WHERE id=?",Integer.class,fact.path("id").asText());return call("POST", f.base + "/statements/" + fact.path("id").asText() + "/changes", "member", workspace, Map.of("expectedRevision", revision, "operationId", op(), "content", content(f, value)), 200); }
    private void review(Fixture f, JsonNode fact, String action, int status) throws Exception { call("POST", f.base + "/statements/" + fact.path("id").asText() + "/review", "owner", workspace, Map.of("expectedRevision", fact.path("revision").asInt(), "action", action, "reason", "verified", "operationId", op()), status); }
    private List<JsonNode> open(Fixture f) throws Exception { List<JsonNode> result = new ArrayList<>(); call("GET", f.base + "/conflicts", "owner", workspace, null, 200).path("items").forEach(x -> { if (x.path("status").asText().equals("OPEN")) result.add(x); }); return result; }
    private JsonNode pair(Fixture f, JsonNode a, JsonNode b) throws Exception { return open(f).stream().filter(x -> Set.of(x.path("left").path("statementId").asText(), x.path("right").path("statementId").asText()).equals(Set.of(a.path("id").asText(), b.path("id").asText()))).findFirst().orElseThrow(); }
    private void resolve(Fixture f, JsonNode pair, JsonNode winner, int status) throws Exception { call("POST", f.base + "/conflicts/" + pair.path("id").asText() + "/resolve", "owner", workspace, Map.of("winnerStatementId", winner.path("id").asText(), "expectedMembers", List.of(pair.path("left"), pair.path("right")), "reason", "verified", "operationId", op()), status); }
    private void withdraw(Fixture f) throws Exception { call("POST", f.base + "/sources/withdraw", "owner", workspace, Map.of("sourceKind", "WIKI_RAW", "sourceRef", f.raw, "reason", "withdrawn", "operationId", op()), 200); }
    private record Fixture(String base, String entity, String raw, String evidence) {}
    private static String op() { return UUID.randomUUID().toString(); }
    private static String id() { return com.baomidou.mybatisplus.core.toolkit.IdWorker.getIdStr(); }
}

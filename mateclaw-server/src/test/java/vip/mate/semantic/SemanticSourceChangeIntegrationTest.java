package vip.mate.semantic;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.databind.JsonNode;

import vip.mate.semantic.support.SemanticHttpFixture;

/** M7 source-change queue integration against isolated H2 data. */
class SemanticSourceChangeIntegrationTest extends SemanticHttpFixture {
    private record Fixture(String ontology, String revision, String graph, String kb, String raw,
            String snapshot, String evidence, String entity) {}

    @Test
    void resumeUsesFrozenBaselineAfterCapturedSnapshotSurvivesQueueFailure() throws Exception {
        Fixture f=fixture("P-101 reading 380V");
        JsonNode fact=propose(f,"380","accepted",200);accept(f,fact.path("id").asText(),1);
        jdbc.update("UPDATE mate_wiki_raw_material SET original_content=? WHERE id=?","P-101 reading 381V",Long.valueOf(f.raw()));
        String operation=op();
        JsonNode first=call("POST","/graphs/"+f.graph()+"/source-changes/scan","member",workspace,
            Map.of("operationId",operation,"sourceKind","WIKI_RAW","sourceRef",f.raw()),200);
        String run=first.path("runId").asText();
        jdbc.update("DELETE FROM mate_semantic_source_change_item WHERE graph_id=?",f.graph());
        jdbc.update("DELETE FROM mate_semantic_source_change WHERE run_id=?",run);
        jdbc.update("UPDATE mate_semantic_source_change_run SET status='RUNNING' WHERE id=?",run);
        JsonNode resumed=call("POST","/graphs/"+f.graph()+"/source-changes/scan","member",workspace,
            Map.of("operationId",operation,"sourceKind","WIKI_RAW","sourceRef",f.raw()),200);
        assertEquals(f.snapshot(),resumed.path("changes").get(0).path("oldSnapshotId").asText());
        assertEquals(1,resumed.path("changes").get(0).path("affectedCount").asInt());
    }

    @Test
    void updateIsDeduplicatedAndQueuesFactCandidateAndPendingProposalWithoutRewritingHistory() throws Exception {
        Fixture f = fixture("P-101 reading 380V\nother source text");
        JsonNode accepted = propose(f, "380", "accepted", 200);
        accept(f, accepted.path("id").asText(), 1);
        JsonNode candidate = propose(f, "400", "candidate", 200);
        JsonNode change = call("POST", "/graphs/" + f.graph() + "/statements/" + accepted.path("id").asText() + "/changes",
                "member", workspace, Map.of("expectedRevision", 2, "operationId", op(), "content", content(f, "381")), 200);
        assertEquals("PENDING", change.path("status").asText());
        int revisionsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM mate_semantic_statement_revision WHERE statement_id=?",
                Integer.class, accepted.path("id").asText());

        jdbc.update("UPDATE mate_wiki_raw_material SET original_content=?,update_time=? WHERE id=?",
                "P-101 reading 381V\nother source text", LocalDateTime.now(), Long.valueOf(f.raw()));
        String scanOp = op();
        JsonNode scan = call("POST", "/graphs/" + f.graph() + "/source-changes/scan", "member", workspace,
                Map.of("operationId", scanOp, "sourceKind", "WIKI_RAW", "sourceRef", f.raw()), 200);
        JsonNode changeRow = scan.path("changes").get(0);
        assertEquals("CHANGED", changeRow.path("sourceState").asText());
        assertEquals(f.snapshot(), changeRow.path("oldSnapshotId").asText());
        assertTrue(changeRow.path("newSnapshotId").isTextual());
        assertEquals(3, changeRow.path("affectedCount").asInt());

        JsonNode replay = call("POST", "/graphs/" + f.graph() + "/source-changes/scan", "member", workspace,
                Map.of("operationId", scanOp, "sourceKind", "WIKI_RAW", "sourceRef", f.raw()), 200);
        assertEquals(scan.path("runId").asText(), replay.path("runId").asText());
        assertEquals(scan.path("changes").toString(), replay.path("changes").toString());
        call("POST", "/graphs/" + f.graph() + "/source-changes/scan", "member", workspace,
                Map.of("operationId", scanOp, "sourceKind", "WIKI_RAW", "sourceRef", "999"), 409);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM mate_semantic_source_change_run WHERE graph_id=?",
                Integer.class, f.graph()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM mate_semantic_source_change WHERE run_id=?",
                Integer.class, scan.path("runId").asText()));

        JsonNode pending = call("GET", "/graphs/" + f.graph() + "/source-changes?limit=20", "viewer", workspace, null, 200);
        assertEquals(3, pending.size());
        assertTrue(pending.findValuesAsText("itemKind").containsAll(List.of("FACT", "CANDIDATE", "CHANGE_PROPOSAL")));
        call("GET", "/graphs/" + f.graph() + "/source-changes?limit=20", "owner", otherWorkspace, null, 404);
        assertEquals(revisionsBefore, jdbc.queryForObject("SELECT COUNT(*) FROM mate_semantic_statement_revision WHERE statement_id=?",
                Integer.class, accepted.path("id").asText()));

        JsonNode item = java.util.stream.StreamSupport.stream(pending.spliterator(), false)
                .filter(v -> "FACT".equals(v.path("itemKind").asText())).findFirst().orElseThrow();
        String itemId = item.path("id").asText();
        long graphVersion = scan.path("graphMutationVersion").asLong();
        call("POST", "/graphs/" + f.graph() + "/source-changes/items/" + itemId + "/decision",
                "owner", workspace, Map.of("operationId", op(), "expectedObservedDigest", item.path("newDigest").asText(),
                        "expectedGraphVersion", graphVersion - 1, "decision", "KEEP_HISTORICAL", "reason", "Wrong version"), 409);
        JsonNode reviewed = call("POST", "/graphs/" + f.graph() + "/source-changes/items/" + itemId + "/decision",
                "owner", workspace, Map.of("operationId", op(), "expectedObservedDigest", item.path("newDigest").asText(),
                        "expectedGraphVersion", graphVersion, "decision", "KEEP_HISTORICAL", "reason", "Retain historical fact"), 200);
        assertEquals("REVIEWED", reviewed.path("reviewState").asText());
        assertEquals("KEEP_HISTORICAL", reviewed.path("decision").asText());
        assertEquals(revisionsBefore, jdbc.queryForObject("SELECT COUNT(*) FROM mate_semantic_statement_revision WHERE statement_id=?",
                Integer.class, accepted.path("id").asText()));

        jdbc.update("UPDATE mate_wiki_raw_material SET original_content=?,update_time=? WHERE id=?",
                "P-101 reading 382V\nother source text", LocalDateTime.now(), Long.valueOf(f.raw()));
        JsonNode secondScan = call("POST", "/graphs/" + f.graph() + "/source-changes/scan", "member", workspace,
                Map.of("operationId", op(), "sourceKind", "WIKI_RAW", "sourceRef", f.raw()), 200);
        assertEquals("CHANGED", secondScan.path("changes").get(0).path("sourceState").asText());
        JsonNode staleItems = call("GET", "/graphs/" + f.graph() + "/source-changes/" + changeRow.path("id").asText() + "/items",
                "viewer", workspace, null, 200);
        JsonNode stale = java.util.stream.StreamSupport.stream(staleItems.spliterator(), false)
                .filter(v -> itemId.equals(v.path("id").asText())).findFirst().orElseThrow();
        assertEquals("STALE", stale.path("reviewState").asText());
        call("POST", "/graphs/" + f.graph() + "/source-changes/items/" + itemId + "/decision",
                "owner", workspace, Map.of("operationId", op(), "expectedObservedDigest", item.path("newDigest").asText(),
                        "expectedGraphVersion", secondScan.path("graphMutationVersion").asLong(), "decision", "KEEP_HISTORICAL",
                        "reason", "The source changed again"), 409);
    }

    @Test
    void contextUpdateQueuesAffectedItemsAndUnavailableKeepsHistory() throws Exception {
        Fixture f = fixture("P-101 reading 380V\nother source text");
        JsonNode accepted = propose(f, "380", "accepted", 200);
        accept(f, accepted.path("id").asText(), 1);
        jdbc.update("UPDATE mate_wiki_raw_material SET original_content=?,update_time=? WHERE id=?",
                "P-101 reading 380V\nother source text changed", LocalDateTime.now(), Long.valueOf(f.raw()));
        JsonNode scan = call("POST", "/graphs/" + f.graph() + "/source-changes/scan", "member", workspace,
                Map.of("operationId", op(), "sourceKind", "WIKI_RAW", "sourceRef", f.raw()), 200);
        assertEquals("CHANGED", scan.path("changes").get(0).path("sourceState").asText());
        assertEquals(1, scan.path("changes").get(0).path("affectedCount").asInt());
        assertEquals(1, call("GET", "/graphs/" + f.graph() + "/source-changes?limit=20", "viewer", workspace, null, 200).size());

        jdbc.update("UPDATE mate_wiki_raw_material SET deleted=1,update_time=? WHERE id=?", LocalDateTime.now(), Long.valueOf(f.raw()));
        JsonNode unavailable = call("POST", "/graphs/" + f.graph() + "/source-changes/scan", "member", workspace,
                Map.of("operationId", op(), "sourceKind", "WIKI_RAW", "sourceRef", f.raw()), 200);
        assertEquals("UNAVAILABLE", unavailable.path("changes").get(0).path("sourceState").asText());
        assertEquals(scan.path("changes").get(0).path("newSnapshotId").asText(), unavailable.path("changes").get(0).path("oldSnapshotId").asText());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM mate_semantic_statement_revision WHERE statement_id=?",
                Integer.class, accepted.path("id").asText()));
    }

    private Fixture fixture(String text) throws Exception {
        String ontology = create();
        JsonNode draft = draft(ontology);
        JsonNode saved = call("PUT", "/ontologies/" + ontology + "/draft", "member", workspace,
                saveBody(draft.path("draftVersion").asLong(), owlDocument("""
                        Ontology(<urn:test:source-change>
                          Declaration(Class(<urn:test:Equipment>))
                          Declaration(DataProperty(<urn:test:reading>)))
                        """)), 200);
        String revision = publish(ontology, saved.path("draftVersion").asLong(), op()).path("id").asText();
        String kb = IdWorker.getIdStr();
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("INSERT INTO mate_wiki_knowledge_base(id,name,description,status,page_count,raw_count,workspace_id,create_time,update_time,deleted) VALUES(?,?,?,?,?,?,?,?,?,0)",
                Long.valueOf(kb), "Source change KB", "", "active", 0, 0, Long.valueOf(workspace), now, now);
        String graph = call("PUT", "/knowledge-bases/" + kb + "/binding", "owner", workspace,
                Map.of("action", "ENABLE", "revisionId", revision), 200).path("graphId").asText();
        JsonNode entity = call("POST", "/graphs/" + graph + "/entities", "member", workspace,
                Map.of("iri", "urn:test:machine", "assertedTypes", List.of("urn:test:Equipment"), "displayName", "Machine"), 200);
        String raw = IdWorker.getIdStr();
        jdbc.update("INSERT INTO mate_wiki_raw_material(id,kb_id,title,source_type,original_content,file_size,processing_status,create_time,update_time,deleted) VALUES(?,?,?,?,?,?,?,?,?,0)",
                Long.valueOf(raw), Long.valueOf(kb), "Source", "text", text, text.getBytes(StandardCharsets.UTF_8).length, "completed", now, now);
        JsonNode imported = call("POST", "/graphs/" + graph + "/imports", "member", workspace,
                Map.of("sourceKind", "WIKI_RAW", "sourceRef", raw, "operationId", op()), 200);
        String snapshot = imported.path("snapshotId").asText();
        String quote = "P-101 reading 380V";
        String evidence = call("POST", "/graphs/" + graph + "/snapshots/" + snapshot + "/evidence", "member", workspace,
                Map.of("operationId", op(), "startCodePoint", 0, "endCodePoint", quote.codePointCount(0, quote.length()), "exactQuote", quote), 200)
                .path("id").asText();
        return new Fixture(ontology, revision, graph, kb, raw, snapshot, evidence, entity.path("id").asText());
    }

    private JsonNode propose(Fixture f, String value, String operation, int status) throws Exception {
        return call("POST", "/graphs/" + f.graph() + "/statements", "member", workspace,
                Map.of("operationId", operation + op(), "subjectId", f.entity(), "assertionText",
                        "DataPropertyAssertion(<urn:test:reading> <urn:test:machine> \"" + value + "\"^^<http://www.w3.org/2001/XMLSchema#integer>)",
                        "validityKind", "INTERVAL", "evidenceIds", List.of(f.evidence())), status);
    }

    private Map<String, Object> content(Fixture f, String value) {
        return Map.of("operationId", "content-" + op(), "subjectId", f.entity(), "assertionText",
                "DataPropertyAssertion(<urn:test:reading> <urn:test:machine> \"" + value + "\"^^<http://www.w3.org/2001/XMLSchema#integer>)",
                "validityKind", "INTERVAL", "evidenceIds", List.of(f.evidence()));
    }

    private void accept(Fixture f, String statement, int revision) throws Exception {
        call("POST", "/graphs/" + f.graph() + "/statements/" + statement + "/review", "owner", workspace,
                Map.of("expectedRevision", revision, "action", "ACCEPT", "reason", "Verified", "operationId", op()), 200);
    }

    private static String op() { return UUID.randomUUID().toString(); }
}

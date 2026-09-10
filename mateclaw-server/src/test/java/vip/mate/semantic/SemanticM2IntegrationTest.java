package vip.mate.semantic;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import vip.mate.semantic.support.SemanticHttpFixture;
import vip.mate.semantic.source.ImportJobRecovery;

import java.time.LocalDateTime;
import java.util.*;

class SemanticM2IntegrationTest extends SemanticHttpFixture {
    @Autowired ImportJobRecovery recovery;
    @Test
    void immutableSourceCandidateReviewTrustedQueryAndSupportLossRoundTrip() throws Exception {
        Fixture f = graph("设备😀额定380V");
        jdbc.update("UPDATE mate_wiki_raw_material SET original_content=? WHERE id=?", "设备😀额定400V", Long.valueOf(f.raw));
        JsonNode repeatedImport = call("POST", "/graphs/" + f.graph + "/imports", "member", workspace,
                Map.of("sourceKind", "WIKI_RAW", "sourceRef", f.raw, "operationId", f.importOperation), 200);
        assertEquals(f.importJob, repeatedImport.path("id").asText());
        assertEquals(f.snapshot, repeatedImport.path("snapshotId").asText());
        call("POST", "/graphs/" + f.graph + "/imports/" + f.importJob + "/retry", "member", workspace,
                Map.of("operationId", "retry-succeeded"), 409);
        assertEquals("设备😀额定380V", call("GET", "/graphs/" + f.graph + "/snapshots/" + f.snapshot + "/text", "member", workspace, null, 200).path("text").asText());
        JsonNode range=call("GET", "/graphs/"+f.graph+"/snapshots/"+f.snapshot+"/text?startCodePoint=2&endCodePoint=3", "member", workspace, null, 200);
        assertEquals("😀",range.path("text").asText());
        assertEquals(9,range.path("totalCodePoints").asInt());
        assertEquals(2,range.path("startCodePoint").asInt());
        call("GET", "/graphs/"+f.graph+"/snapshots/"+f.snapshot+"/text?startCodePoint=-1", "member", workspace, null, 400);
        call("POST", "/graphs/" + f.graph + "/snapshots/" + f.snapshot + "/evidence", "member", workspace,
                Map.of("operationId", "evidence-bad", "startCodePoint", 5, "endCodePoint", 9, "exactQuote", "400V"), 422);
        JsonNode evidence = call("POST", "/graphs/" + f.graph + "/snapshots/" + f.snapshot + "/evidence", "member", workspace,
                Map.of("operationId", "evidence-380", "startCodePoint", 5, "endCodePoint", 9, "exactQuote", "380V"), 200);
        JsonNode candidate = propose(f, "380", evidence.path("id").asText(), "candidate-380", 200);

        assertEquals(0, call("GET", "/graphs/" + f.graph + "/statements?view=trusted", "viewer", workspace, null, 200).path("total").asInt());
        assertEquals(0, call("GET", "/graphs/" + f.graph + "/neighbors?entityId=" + f.entity + "&depth=1", "viewer", workspace, null, 200).path("nodes").size());
        JsonNode accepted = call("POST", "/graphs/" + f.graph + "/statements/" + candidate.path("id").asText() + "/review", "owner", workspace,
                Map.of("expectedRevision", 1, "action", "ACCEPT", "reason", "verified", "operationId", "accept-380"), 200);
        assertEquals("ACCEPTED", accepted.path("reviewStatus").asText());
        assertEquals("SUPPORTED", accepted.path("supportStatus").asText());
        JsonNode replayed = call("POST", "/graphs/" + f.graph + "/statements/" + candidate.path("id").asText() + "/review", "owner", workspace,
                Map.of("expectedRevision", 1, "action", "ACCEPT", "reason", "verified", "operationId", "accept-380"), 200);
        assertEquals(accepted, replayed);

        JsonNode search = call("POST", "/graphs/" + f.graph + "/search", "viewer", workspace,
                Map.of("query", "P-101", "limit", 10), 200);
        assertEquals(1, search.path("facts").size());
        assertEquals("380", search.path("facts").get(0).path("assertion").path("literal").path("lexicalValue").asText());
        JsonNode readback = call("GET", "/graphs/" + f.graph + "/evidence/" + evidence.path("id").asText(), "viewer", workspace, null, 200);
        assertEquals("380V", readback.path("exactQuote").asText());

        JsonNode withdrawn = call("POST", "/graphs/" + f.graph + "/sources/withdraw", "owner", workspace,
                Map.of("sourceKind", "WIKI_RAW", "sourceRef", f.raw, "reason", "superseded", "operationId", "withdraw-raw"), 200);
        assertEquals("WITHDRAWN", withdrawn.path("state").asText());
        assertEquals(withdrawn, call("POST", "/graphs/" + f.graph + "/sources/withdraw", "owner", workspace,
                Map.of("sourceKind", "WIKI_RAW", "sourceRef", f.raw, "reason", "superseded", "operationId", "withdraw-raw"), 200));
        call("POST", "/graphs/" + f.graph + "/sources/withdraw", "owner", workspace,
                Map.of("sourceKind", "WIKI_RAW", "sourceRef", f.raw, "reason", "different", "operationId", "withdraw-raw"), 409);
        assertEquals(0, call("GET", "/graphs/" + f.graph + "/statements?view=trusted", "viewer", workspace, null, 200).path("total").asInt());
        assertEquals(0, call("GET", "/graphs/" + f.graph + "/neighbors?entityId=" + f.entity + "&depth=1", "viewer", workspace, null, 200).path("nodes").size());
        assertEquals("ACCEPTED", jdbc.queryForObject("SELECT review_status FROM mate_semantic_statement_revision WHERE statement_id=? AND revision=2", String.class, candidate.path("id").asText()));
    }

    @Test
    void competingCandidatesRequireExplicitConflictResolution() throws Exception {
        Fixture first = graph("设备😀额定380V");
        JsonNode e1 = call("POST", "/graphs/" + first.graph + "/snapshots/" + first.snapshot + "/evidence", "member", workspace,
                Map.of("operationId", "evidence-a", "startCodePoint", 5, "endCodePoint", 9, "exactQuote", "380V"), 200);
        String raw2 = raw(first.kb, "设备😀不是380V");
        JsonNode imported2 = call("POST", "/graphs/" + first.graph + "/imports", "member", workspace,
                Map.of("sourceKind", "WIKI_RAW", "sourceRef", raw2, "operationId", "import-400"), 200);
        JsonNode e2 = call("POST", "/graphs/" + first.graph + "/snapshots/" + imported2.path("snapshotId").asText() + "/evidence", "member", workspace,
                Map.of("operationId", "evidence-b", "startCodePoint", 5, "endCodePoint", 9, "exactQuote", "380V"), 200);
        JsonNode a = propose(first, "380", e1.path("id").asText(), "candidate-a", 200);
        String iri=jdbc.queryForObject("SELECT iri FROM mate_semantic_entity WHERE id=?",String.class,first.entity);
        JsonNode b=call("POST","/graphs/"+first.graph+"/statements","member",workspace,
            Map.of("operationId","candidate-b","subjectId",first.entity,"assertionText",
                "NegativeDataPropertyAssertion(<urn:test:voltage> <"+iri+"> \"380\"^^<http://www.w3.org/2001/XMLSchema#decimal>)",
                "validityKind","INTERVAL","evidenceIds",List.of(e2.path("id").asText())),200);
        call("POST", "/graphs/" + first.graph + "/statements/" + a.path("id").asText() + "/review", "member", workspace,
                Map.of("expectedRevision", 1, "action", "ACCEPT", "reason", "unauthorized", "operationId", "member-review"), 403);
        JsonNode conflicts = call("GET", "/graphs/" + first.graph + "/conflicts", "owner", workspace, null, 200);
        assertEquals(1, conflicts.path("total").asInt());
        call("POST", "/graphs/" + first.graph + "/statements/" + a.path("id").asText() + "/review", "owner", workspace,
                Map.of("expectedRevision", 1, "action", "ACCEPT", "reason", "try", "operationId", "direct-accept"), 409);
        JsonNode conflict = conflicts.path("items").get(0);
        Map<String,Object> resolution = Map.of("winnerStatementId", a.path("id").asText(), "expectedMembers", List.of(
                        Map.of("statementId", a.path("id").asText(), "revision", 1),
                        Map.of("statementId", b.path("id").asText(), "revision", 1)),
                        "reason", "source A approved", "operationId", "resolve-voltage");
        JsonNode resolved = call("POST", "/graphs/" + first.graph + "/conflicts/" + conflict.path("id").asText() + "/resolve", "owner", workspace, resolution, 200);
        assertEquals(resolved, call("POST", "/graphs/" + first.graph + "/conflicts/" + conflict.path("id").asText() + "/resolve", "owner", workspace, resolution, 200));
        JsonNode trusted = call("GET", "/graphs/" + first.graph + "/statements?view=trusted", "viewer", workspace, null, 200);
        assertEquals(1, trusted.path("total").asInt());
        assertEquals("380", trusted.path("items").get(0).path("assertion").path("literal").path("lexicalValue").asText());
    }

    @Test
    void failedImportCanBeRetriedAsAnIdempotentDurableAttempt() throws Exception {
        Fixture f = graph("设备😀额定380V");
        jdbc.update("UPDATE mate_semantic_import_job SET status='FAILED',snapshot_id=NULL,error_message='simulated transient failure' WHERE id=?", f.importJob);
        JsonNode retried = call("POST", "/graphs/" + f.graph + "/imports/" + f.importJob + "/retry", "member", workspace,
                Map.of("operationId", "retry-import"), 200);
        assertEquals("SUCCEEDED", retried.path("status").asText());
        assertEquals(2, retried.path("attempts").asInt());
        assertFalse(retried.path("snapshotId").asText().isBlank());
        assertEquals(retried, call("POST", "/graphs/" + f.graph + "/imports/" + f.importJob + "/retry", "member", workspace,
                Map.of("operationId", "retry-import"), 200));
        jdbc.update("UPDATE mate_semantic_import_job SET status='FAILED',attempts=4 WHERE id=?",retried.path("id").asText());
        call("POST","/graphs/"+f.graph+"/imports/"+retried.path("id").asText()+"/retry","member",workspace,Map.of("operationId","retry-exhausted"),409);
    }

    @Test
    void disabledGraphRejectsEvidenceAndRetryBeforeCreatingRecords() throws Exception {
        Fixture f=graph("设备😀额定380V");
        jdbc.update("UPDATE mate_semantic_import_job SET status='FAILED' WHERE id=?",f.importJob);
        jdbc.update("UPDATE mate_semantic_graph SET enabled=FALSE WHERE id=?",f.graph);
        call("POST","/graphs/"+f.graph+"/snapshots/"+f.snapshot+"/evidence","member",workspace,Map.of("operationId","disabled-evidence","startCodePoint",0,"endCodePoint",2,"exactQuote","设备"),409);
        call("POST","/graphs/"+f.graph+"/imports/"+f.importJob+"/retry","member",workspace,Map.of("operationId","disabled-retry"),409);
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM mate_semantic_evidence WHERE graph_id=?",Integer.class,f.graph));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM mate_semantic_import_job WHERE graph_id=?",Integer.class,f.graph));
    }

    @Test
    void queuedImportIsRecoveredWithTheOriginalActorsCurrentPermission() throws Exception {
        Fixture f = graph("设备😀额定380V");
        jdbc.update("UPDATE mate_semantic_import_job SET status='QUEUED',snapshot_id=NULL WHERE id=?", f.importJob);
        recovery.recover();
        assertEquals("SUCCEEDED", jdbc.queryForObject("SELECT status FROM mate_semantic_import_job WHERE id=?", String.class, f.importJob));
        assertEquals(2, jdbc.queryForObject("SELECT attempts FROM mate_semantic_import_job WHERE id=?", Integer.class, f.importJob));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM mate_semantic_source_snapshot WHERE graph_id=? AND source_id=?", Integer.class, f.graph, f.raw));
    }

    @Test
    void recoveryFailsClosedWhenTheOriginalActorLostWorkspaceAccess() throws Exception {
        Fixture f = graph("设备😀额定380V");
        String actor = jdbc.queryForObject("SELECT created_by FROM mate_semantic_import_job WHERE id=?", String.class, f.importJob);
        jdbc.update("UPDATE mate_semantic_import_job SET status='QUEUED',snapshot_id=NULL WHERE id=?", f.importJob);
        jdbc.update("UPDATE mate_workspace_member SET deleted=1 WHERE workspace_id=? AND user_id=?", Long.valueOf(workspace), Long.valueOf(actor));
        recovery.recover();
        assertEquals("FAILED", jdbc.queryForObject("SELECT status FROM mate_semantic_import_job WHERE id=?", String.class, f.importJob));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM mate_semantic_source_snapshot WHERE graph_id=? AND source_id=?", Integer.class, f.graph, f.raw));
    }

    private JsonNode propose(Fixture f, String value, String evidence, String operation, int status) throws Exception {
        String iri=jdbc.queryForObject("SELECT iri FROM mate_semantic_entity WHERE id=?",String.class,f.entity);
        String assertion="DataPropertyAssertion(<urn:test:voltage> <"+iri+"> \""+value+"\"^^<http://www.w3.org/2001/XMLSchema#decimal>)";
        return call("POST", "/graphs/" + f.graph + "/statements", "member", workspace,
                Map.of("operationId",operation,"subjectId",f.entity,"assertionText",assertion,
                    "validityKind","INTERVAL","evidenceIds",List.of(evidence)),status);
    }

    @Test
    void graphCapacityRejectsWritesWithoutTruncatingExistingContent() throws Exception {
        Fixture f=graph("设备😀额定380V");
        List<Object[]> entities=new ArrayList<>(),facts=new ArrayList<>();
        LocalDateTime now=LocalDateTime.now();
        for(int i=0;i<999;i++)entities.add(new Object[]{com.baomidou.mybatisplus.core.toolkit.IdWorker.getIdStr(),f.graph,"Equipment","Capacity "+i,"ACTIVE","1",now});
        jdbc.batchUpdate("INSERT INTO mate_semantic_entity(id,graph_id,type_key,display_name,status,created_by,created_at) VALUES(?,?,?,?,?,?,?)",entities);
        JsonNode entityError=call("POST","/graphs/"+f.graph+"/entities","member",workspace,Map.of("assertedTypes",List.of("urn:test:Equipment"),"displayName","Over limit"),422);
        assertEquals("GRAPH_ENTITY_LIMIT",entityError.path("code").asText());
        // Identity rows are sufficient to exercise admission control; no query is
        // allowed to treat a capped prefix as the graph's complete fact set.
        for(int i=0;i<10000;i++)facts.add(new Object[]{com.baomidou.mybatisplus.core.toolkit.IdWorker.getIdStr(),f.graph,1,"1",now});
        jdbc.batchUpdate("INSERT INTO mate_semantic_statement(id,graph_id,current_revision,created_by,created_at) VALUES(?,?,?,?,?)",facts);
        JsonNode factError=propose(f,"380","1","over-limit",422);
        assertEquals("GRAPH_STATEMENT_LIMIT",factError.path("code").asText());
        assertEquals(10000,jdbc.queryForObject("SELECT COUNT(*) FROM mate_semantic_statement WHERE graph_id=?",Integer.class,f.graph));
    }

    private Fixture graph(String text) throws Exception {
        String kb = kb(); String ontology = create(); JsonNode draft = draft(ontology); JsonNode saved = save(ontology, draft.path("draftVersion").asLong());
        JsonNode revision = publish(ontology, saved.path("draftVersion").asLong(), "publish-" + UUID.randomUUID());
        JsonNode binding = call("PUT", "/knowledge-bases/" + kb + "/binding", "owner", workspace, Map.of("action", "ENABLE", "revisionId", revision.path("id").asText()), 200);
        JsonNode entity = call("POST", "/graphs/" + binding.path("graphId").asText() + "/entities", "member", workspace, Map.of("assertedTypes", List.of("urn:test:Equipment"), "displayName", "P-101"), 200);
        String raw = raw(kb, text);
        String importOperation = "import-" + UUID.randomUUID();
        JsonNode imported = call("POST", "/graphs/" + binding.path("graphId").asText() + "/imports", "member", workspace, Map.of("sourceKind", "WIKI_RAW", "sourceRef", raw, "operationId", importOperation), 200);
        return new Fixture(kb, binding.path("graphId").asText(), entity.path("id").asText(), raw, imported.path("snapshotId").asText(), imported.path("id").asText(), importOperation);
    }
    private String kb() {String id=com.baomidou.mybatisplus.core.toolkit.IdWorker.getIdStr();LocalDateTime now=LocalDateTime.now();jdbc.update("INSERT INTO mate_wiki_knowledge_base(id,name,description,status,page_count,raw_count,workspace_id,create_time,update_time,deleted) VALUES(?,?,?,?,?,?,?,?,?,0)",Long.valueOf(id),"Semantic KB","","active",0,0,Long.valueOf(workspace),now,now);return id;}
    private String raw(String kb,String text){String id=com.baomidou.mybatisplus.core.toolkit.IdWorker.getIdStr();LocalDateTime now=LocalDateTime.now();jdbc.update("INSERT INTO mate_wiki_raw_material(id,kb_id,title,source_type,original_content,file_size,processing_status,create_time,update_time,deleted) VALUES(?,?,?,?,?,?,?,?,?,0)",Long.valueOf(id),Long.valueOf(kb),"repair note","text",text,text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,"completed",now,now);return id;}
    private record Fixture(String kb,String graph,String entity,String raw,String snapshot,String importJob,String importOperation){}
}

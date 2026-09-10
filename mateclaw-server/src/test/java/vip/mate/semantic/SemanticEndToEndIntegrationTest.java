package vip.mate.semantic;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import vip.mate.semantic.support.SemanticHttpFixture;
import java.time.LocalDateTime;
import java.util.*;

/** The same authenticated HTTP workflow also runs against an opt-in isolated MySQL database. */
class SemanticEndToEndIntegrationTest extends SemanticHttpFixture {
    @Override
    protected Map<String,Object> definition() {
        return owlDocument("""
            Ontology(<urn:test:equipment>
              Declaration(Class(<urn:test:Equipment>))
              Declaration(ObjectProperty(<urn:test:connects>))
              Declaration(DataProperty(<urn:test:voltage>))
              DataPropertyDomain(<urn:test:voltage> <urn:test:Equipment>)
              DataPropertyRange(<urn:test:voltage> <http://www.w3.org/2001/XMLSchema#decimal>))
            """);
    }

    @Test
    void pinnedSchemaMultiValueGraphChangeReviewAndSupportWithdrawal() throws Exception {
        String ontology=create();
        JsonNode saved=save(ontology,draft(ontology).path("draftVersion").asLong());
        JsonNode published=publish(ontology,saved.path("draftVersion").asLong(),op());
        String kb=id(); LocalDateTime now=LocalDateTime.now();
        jdbc.update("INSERT INTO mate_wiki_knowledge_base(id,name,status,page_count,raw_count,workspace_id,create_time,update_time,deleted) VALUES(?,?,?,0,0,?,?,?,0)",Long.valueOf(kb),"M3 验收","active",Long.valueOf(workspace),now,now);
        JsonNode binding=call("PUT","/knowledge-bases/"+kb+"/binding","owner",workspace,Map.of("action","ENABLE","revisionId",published.path("id").asText()),200);
        String graph=binding.path("graphId").asText(),base="/graphs/"+graph;
        JsonNode detail=call("GET",base,"viewer",workspace,null,200);
        assertEquals(published.path("id"),detail.path("binding").path("ontologyRevisionId"));
        assertEquals("owl-document-v1",detail.path("document").path("source").path("modelSchema").asText());
        assertTrue(java.util.stream.StreamSupport.stream(detail.path("document").path("axioms").spliterator(),false)
                .anyMatch(axiom -> axiom.path("rendering").asText().contains("ObjectProperty")));
        call("GET",base,"owner",otherWorkspace,null,404);
        List<String> entities=new ArrayList<>();
        for(String label:List.of("P-101","M-01","M-02"))
            entities.add(call("POST",base+"/entities","member",workspace,Map.of("iri","urn:test:"+label,"assertedTypes",List.of("urn:test:Equipment"),"displayName",label),200).path("id").asText());
        call("PUT","/knowledge-bases/"+kb+"/binding","owner",workspace,Map.of("action","REBIND","revisionId",published.path("id").asText(),"expectedGraphVersion",3),409);
        String raw=id();String text="设备😀额定380V，连接两个部件；另一份记录称400V。";
        jdbc.update("INSERT INTO mate_wiki_raw_material(id,kb_id,title,source_type,original_content,file_size,processing_status,create_time,update_time,deleted) VALUES(?,?,?,?,?,?,?,?,?,0)",Long.valueOf(raw),Long.valueOf(kb),"设备记录","text",text,text.length(),"completed",now,now);
        JsonNode job=call("POST",base+"/imports","member",workspace,Map.of("sourceKind","WIKI_RAW","sourceRef",raw,"operationId",op()),200);
        String snapshot=job.path("snapshotId").asText();
        JsonNode evidence=call("POST",base+"/snapshots/"+snapshot+"/evidence","member",workspace,Map.of("operationId",op(),"startCodePoint",0,"endCodePoint",text.codePointCount(0,text.length()),"exactQuote",text),200);
        String evidenceId=evidence.path("id").asText();
        for(String target:entities.subList(1,3)) {
            Map<String,Object> relation=new HashMap<>(Map.of("operationId",op(),"subjectId",entities.getFirst(),"assertionText",objectAssertion(entities.getFirst(),target),"validityKind","INTERVAL","evidenceIds",List.of(evidenceId)));
            JsonNode candidate=call("POST",base+"/statements","member",workspace,relation,200);
            review(base,candidate,"ACCEPT",200);
        }
        JsonNode neighborhood=call("GET",base+"/neighbors?entityId="+entities.getFirst()+"&depth=2","viewer",workspace,null,200);
        assertEquals(3,neighborhood.path("nodes").size());
        assertEquals(2,neighborhood.path("edges").size(),"Two-hop traversal must not duplicate edges");
        JsonNode limited=call("GET",base+"/neighbors?entityId="+entities.getFirst()+"&depth=2&nodeLimit=1","viewer",workspace,null,200);
        assertEquals(1,limited.path("nodes").size()); assertEquals(0,limited.path("edges").size());
        assertTrue(limited.path("truncated").asBoolean());
        Map<String,Object> content=new HashMap<>(Map.of("operationId",op(),"subjectId",entities.getFirst(),"assertionText",dataAssertion(entities.getFirst(),"380"),"validityKind","INTERVAL","evidenceIds",List.of(evidenceId)));
        JsonNode candidate=call("POST",base+"/statements","member",workspace,content,200);
        try (var workers=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var gate=new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<Integer> confirm=() -> {
                gate.await();
                return request("POST",base+"/statements/"+candidate.path("id").asText()+"/review","owner",workspace,
                        Map.of("expectedRevision",1,"action","ACCEPT","reason","并发核验","operationId",op())).getStatus();
            };
            var first=workers.submit(confirm); var second=workers.submit(confirm); gate.countDown();
            assertEquals(List.of(200,409),java.util.stream.Stream.of(first.get(),second.get()).sorted().toList());
        }
        JsonNode accepted=call("GET",base+"/statements/"+candidate.path("id").asText()+"/revisions","owner",workspace,null,200).path("revisions").get(1);
        content.put("operationId",op()); content.put("validFrom","2026-01-01T00:00:00Z");
        JsonNode change=call("POST",base+"/statements/"+candidate.path("id").asText()+"/changes","member",workspace,Map.of("expectedRevision",accepted.path("revision").asInt(),"operationId",op(),"content",content),200);
        JsonNode changeRead=call("GET",base+"/changes/"+change.path("id").asText(),"owner",workspace,null,200);
        assertTrue(changeRead.path("content").path("assertionText").asText().contains("\"380\""));
        assertEquals("2026-01-01T00:00:00Z",changeRead.path("content").path("validFrom").asText());
        call("POST",base+"/changes/"+change.path("id").asText()+"/review","owner",workspace,Map.of("expectedRevision",2,"action","ACCEPT","reason","补充时效","operationId",op()),200);
        JsonNode search=call("POST",base+"/search","viewer",workspace,Map.of("query","P-101","limit",10),200);
        assertEquals(3,search.path("facts").size());
        content.put("operationId",op()); content.put("assertionText",negativeDataAssertion(entities.getFirst(),"380"));
        JsonNode competing=call("POST",base+"/statements","member",workspace,content,200);
        review(base,competing,"ACCEPT",409);
        JsonNode conflict=call("GET",base+"/conflicts","owner",workspace,null,200).path("items").get(0);
        call("POST",base+"/conflicts/"+conflict.path("id").asText()+"/resolve","owner",workspace,
                Map.of("winnerStatementId",candidate.path("id").asText(),"expectedMembers",List.of(
                        Map.of("statementId",candidate.path("id").asText(),"revision",3),
                        Map.of("statementId",competing.path("id").asText(),"revision",1)),"reason","核实380V记录","operationId",op()),200);
        call("POST",base+"/sources/withdraw","owner",workspace,Map.of("sourceKind","WIKI_RAW","sourceRef",raw,"reason","资料撤回","operationId",op()),200);
        assertEquals(0,call("POST",base+"/search","viewer",workspace,Map.of("query","P-101"),200).path("facts").size());
        call("GET",base+"/evidence/"+evidenceId,"viewer",workspace,null,404);
        JsonNode history=call("GET",base+"/statements/"+candidate.path("id").asText()+"/revisions","owner",workspace,null,200);
        assertEquals(3,history.path("revisions").size());
        assertEquals("SUPPORT_LOST",history.path("revisions").get(2).path("supportStatus").asText());
        assertEquals(3,jdbc.queryForObject("SELECT current_revision FROM mate_semantic_statement WHERE id=?",Integer.class,candidate.path("id").asText()));
    }

    private JsonNode review(String base,JsonNode fact,String action,int expected) throws Exception {
        return call("POST",base+"/statements/"+fact.path("id").asText()+"/review","owner",workspace,
                Map.of("expectedRevision",fact.path("revision").asInt(),"action",action,"reason","核对原文","operationId",op()),expected);
    }
    private String entityIri(String entityId) {
        return jdbc.queryForObject("SELECT iri FROM mate_semantic_entity WHERE id=?",String.class,entityId);
    }
    private String objectAssertion(String subjectId,String targetId) {
        return "ObjectPropertyAssertion(<urn:test:connects> <"+entityIri(subjectId)+"> <"+entityIri(targetId)+">)";
    }
    private String dataAssertion(String subjectId,String value) {
        return "DataPropertyAssertion(<urn:test:voltage> <"+entityIri(subjectId)+"> \""+value+"\"^^<http://www.w3.org/2001/XMLSchema#decimal>)";
    }
    private String negativeDataAssertion(String subjectId,String value) {
        return "NegativeDataPropertyAssertion(<urn:test:voltage> <"+entityIri(subjectId)+"> \""+value+"\"^^<http://www.w3.org/2001/XMLSchema#decimal>)";
    }
    private static String id(){return com.baomidou.mybatisplus.core.toolkit.IdWorker.getIdStr();}
    private static String op(){return UUID.randomUUID().toString();}
}

package vip.mate.semantic;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.util.*;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import vip.mate.semantic.support.SemanticHttpFixture;
import vip.mate.semantic.core.ontology.OntologyDocument;
import com.fasterxml.jackson.databind.JsonNode;

class OntologyModelingIntegrationTest extends SemanticHttpFixture {
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    vip.mate.semantic.ontology.source.OntologySourceReviewService sourceService;
    private String path(String id){return "/modeling-tasks/"+id;}
    private Map<String,Object> createRequest(){return Map.of("operationId",UUID.randomUUID().toString(),"newOntology",Map.of("name","设备建模","description",""),"goal","定义设备及其组件","sources",List.of());}
    private JsonNode task(Map<String,Object> input)throws Exception{return call("POST","/modeling-tasks","member",workspace,input,200);}
    private Map<String,Object> proposal(long version){return Map.of("operationId",UUID.randomUUID().toString(),"expectedDraftVersion",version,
            "changes",List.of(Map.of("kind","CREATE_TERM","termKind","OBJECT","clientId","equipment","name","设备")),
            "questions",List.of(),"samples",List.of(Map.of("name","候选设备","serial","S-001")));}
    private JsonNode submit(String id,Map<String,Object> input)throws Exception{return call("POST",path(id)+"/proposals","member",workspace,input,200);}
    private JsonNode decide(String id,String proposal,Map<String,Object> input,int status)throws Exception{return call("POST",path(id)+"/proposals/"+proposal+"/decision","member",workspace,input,status);}
    @Test void createsAndAcceptsIdempotentlyWithDurableResultsAndIsolatedSamples()throws Exception {
        var input=createRequest();var t=task(input);String id=t.path("id").asText();String ontology=t.path("ontologyId").asText();
        assertEquals(t,task(input));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM mate_semantic_ontology WHERE id=?",Integer.class,ontology));
        var pInput=proposal(1);var pending=submit(id,pInput);assertEquals(pending,submit(id,pInput));
        assertEquals(pending,call("GET",path(id),"viewer",workspace,null,200));
        assertEquals("S-001",pending.path("proposals").get(0).path("input").path("samples").get(0).path("serial").asText());
        long facts=jdbc.queryForObject("SELECT COUNT(*) FROM mate_semantic_entity",Long.class);
        var decision=Map.<String,Object>of("operationId",UUID.randomUUID().toString(),"decision","ACCEPT");
        String proposal=pending.path("proposals").get(0).path("id").asText();
        var accepted=decide(id,proposal,decision,200);assertEquals("ACCEPTED",accepted.path("proposals").get(0).path("status").asText());
        assertEquals(accepted,decide(id,proposal,decision,200));
        assertEquals(accepted,call("GET",path(id),"viewer",workspace,null,200));
        assertEquals(facts,jdbc.queryForObject("SELECT COUNT(*) FROM mate_semantic_entity",Long.class));
        var result=accepted.path("proposals").get(0).path("result");assertEquals(2,result.path("draft").path("draftVersion").asLong());
        assertFalse(result.path("items").get(0).path("targetId").asText().isBlank());
        assertEquals("ACCEPTED",new com.fasterxml.jackson.databind.ObjectMapper().readTree(jdbc.queryForObject("SELECT state_json FROM mate_semantic_modeling_task WHERE id=?",String.class,id)).path("proposals").get(0).path("status").asText());
        call("GET",path(id),"owner",otherWorkspace,null,404);
        call("POST",path(id)+"/proposals","viewer",workspace,proposal(2),403);
    }
    @Test void persistsStaleDraftAndCancellationBlocksLateApplication()throws Exception {
        var t=task(createRequest());String id=t.path("id").asText(),ontology=t.path("ontologyId").asText();
        var pending=submit(id,proposal(1));String pid=pending.path("proposals").get(0).path("id").asText();
        save(ontology,1);
        var stale=decide(id,pid,Map.of("operationId",UUID.randomUUID().toString(),"decision","ACCEPT"),200);
        assertEquals("STALE",stale.path("proposals").get(0).path("status").asText());
        assertEquals(stale,call("GET",path(id),"viewer",workspace,null,200));
        var next=submit(id,proposal(2));String nextId=next.path("proposals").get(1).path("id").asText();
        var cancelled=call("PATCH",path(id)+"/stage","member",workspace,Map.of("stage","CANCELLED"),200);
        assertEquals(cancelled,call("PATCH",path(id)+"/stage","member",workspace,Map.of("stage","CANCELLED"),200));
        decide(id,nextId,Map.of("operationId",UUID.randomUUID().toString(),"decision","ACCEPT"),409);
        call("POST",path(id)+"/proposals","member",workspace,proposal(2),409);
        assertEquals(2,call("GET","/ontologies/"+ontology+"/draft","viewer",workspace,null,200).path("draftVersion").asLong());
    }
    @Test void rejectionFailureRecoveryAndQuestionAnswersPersist()throws Exception {
        var t=task(createRequest());String id=t.path("id").asText();
        call("PATCH",path(id)+"/stage","member",workspace,Map.of("stage","FAILED","message","model unavailable"),200);
        call("PATCH",path(id)+"/stage","member",workspace,Map.of("stage","READY"),200);
        var p=new HashMap<>(proposal(1));p.put("questions",List.of("设备是否包含测针？"));
        var pending=submit(id,p);String pid=pending.path("proposals").get(0).path("id").asText();
        decide(id,pid,Map.of("operationId",UUID.randomUUID().toString(),"decision","ACCEPT"),400);
        var rejected=decide(id,pid,Map.of("operationId",UUID.randomUUID().toString(),"decision","REJECT"),200);
        assertEquals("REJECTED",rejected.path("proposals").get(0).path("status").asText());
        var nextInput=new HashMap<>(p);nextInput.put("operationId",UUID.randomUUID().toString());
        var pending2=submit(id,nextInput);
        var accepted=decide(id,pending2.path("proposals").get(1).path("id").asText(),Map.of("operationId",UUID.randomUUID().toString(),"decision","ACCEPT","answers",Map.of("设备是否包含测针？","是")),200);
        assertEquals("是",accepted.path("proposals").get(1).path("answers").path("设备是否包含测针？").asText());
    }
    @Test void sourceEvidenceIsAtomicAndChangedSourceMakesProposalStale()throws Exception {
        String kb=com.baomidou.mybatisplus.core.toolkit.IdWorker.getIdStr(),raw=com.baomidou.mybatisplus.core.toolkit.IdWorker.getIdStr();
        var now=LocalDateTime.now();String text="设备😀用于测量。";
        jdbc.update("INSERT INTO mate_wiki_knowledge_base(id,name,description,status,page_count,raw_count,workspace_id,create_time,update_time,deleted) VALUES(?,?,?,?,?,?,?,?,?,0)",Long.valueOf(kb),"Source KB","","active",0,0,Long.valueOf(workspace),now,now);
        jdbc.update("INSERT INTO mate_wiki_raw_material(id,kb_id,title,source_type,original_content,file_size,processing_status,create_time,update_time,deleted) VALUES(?,?,?,?,?,?,?,?,?,0)",Long.valueOf(raw),Long.valueOf(kb),"Definitions","text",text,100,"completed",now,now);
        var create=new HashMap<>(createRequest());create.put("sources",List.of(Map.of("knowledgeBaseId",kb,"sourceRef",raw,"sourceDigest",OntologyDocument.sha256(text))));
        var task=task(create);String id=task.path("id").asText();var proposal=new HashMap<>(proposal(1));
        proposal.put("evidence",List.of(Map.of("clientId","equipment","knowledgeBaseId",kb,"sourceRef",raw,"sourceDigest",OntologyDocument.sha256(text),"exactQuote","😀","origin","EXTRACTED")));
        var pending=submit(id,proposal);
        var decision=Map.<String,Object>of("operationId",UUID.randomUUID().toString(),"decision","ACCEPT");
        String pid=pending.path("proposals").get(0).path("id").asText();
        doThrow(new vip.mate.semantic.web.SemanticApiException(500,"TEST_BIND_FAILURE","Simulated source persistence failure"))
                .when(sourceService).bind(anyString(),anyString(),any());
        decide(id,pid,decision,500);
        assertEquals(1,call("GET","/ontologies/"+task.path("ontologyId").asText()+"/draft","viewer",workspace,null,200).path("draftVersion").asLong());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM mate_semantic_axiom_source WHERE revision_id=?",Integer.class,task.path("draftId").asText()));
        assertEquals("PENDING",call("GET",path(id),"viewer",workspace,null,200).path("proposals").get(0).path("status").asText());
        doCallRealMethod().when(sourceService).bind(anyString(),anyString(),any());
        var accepted=decide(id,pid,decision,200);
        var result=accepted.path("proposals").get(0).path("result");
        assertTrue(result.path("draft").path("draftVersion").asLong()>2);
        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM mate_semantic_axiom_source WHERE revision_id=?",Integer.class,task.path("draftId").asText())>0);
        proposal.put("expectedDraftVersion",result.path("draft").path("draftVersion").asLong());proposal.put("operationId",UUID.randomUUID().toString());
        var pending2=submit(id,proposal);
        jdbc.update("UPDATE mate_wiki_raw_material SET deleted=1 WHERE id=?",Long.valueOf(raw));
        var stale=decide(id,pending2.path("proposals").get(1).path("id").asText(),Map.of("operationId",UUID.randomUUID().toString(),"decision","ACCEPT"),200);
        assertEquals("STALE",stale.path("proposals").get(1).path("status").asText());
        assertEquals("SOURCE_CHANGED",stale.path("proposals").get(1).path("reason").asText());
        assertEquals(stale,call("GET",path(id),"viewer",workspace,null,200));
    }
    @Test void discardedDraftAndMalformedInputHaveControlledOutcomes()throws Exception {
        var invalid=new HashMap<>(createRequest());invalid.put("sources",Arrays.asList((Object)null));
        call("POST","/modeling-tasks","member",workspace,invalid,400);
        var task=task(createRequest());String id=task.path("id").asText();
        var badProposal=new HashMap<>(proposal(1));badProposal.put("questions",Arrays.asList((Object)null));
        call("POST",path(id)+"/proposals","member",workspace,badProposal,400);
        var pending=submit(id,proposal(1));
        call("DELETE","/ontologies/"+task.path("ontologyId").asText()+"/draft?expectedDraftVersion=1","member",workspace,null,200);
        var stale=call("GET",path(id),"viewer",workspace,null,200);
        assertEquals("STALE",stale.path("proposals").get(0).path("status").asText());
        decide(id,pending.path("proposals").get(0).path("id").asText(),Map.of("operationId",UUID.randomUUID().toString(),"decision","ACCEPT"),200);
    }

}

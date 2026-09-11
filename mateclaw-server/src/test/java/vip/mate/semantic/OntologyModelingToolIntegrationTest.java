package vip.mate.semantic;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ai.chat.model.ToolContext;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.semantic.authoring.*;
import vip.mate.semantic.support.SemanticHttpFixture;
import vip.mate.semantic.web.SemanticApiException;
import static org.junit.jupiter.api.Assertions.*;

class OntologyModelingToolIntegrationTest extends SemanticHttpFixture {
    @Autowired OntologyAuthoringTool tool;
    private ToolContext context() {return context("123456");}
    private ToolContext context(String kb) {
        Long user=jdbc.queryForObject("SELECT user_id FROM mate_workspace_member WHERE workspace_id=? AND role='member' AND deleted=0",Long.class,Long.valueOf(workspace));
        return ChatOrigin.web("business-model","display",Long.valueOf(workspace),null,null,user).withAgent(agentWithKnowledgeBase(kb)).toToolContext();
    }
    private Map<String,Object> request(List<?> sources) {
        return Map.of("operationId",UUID.randomUUID().toString(),"newOntology",Map.of("name","设备业务","description",""),"goal","设备装有传感器","sources",sources);
    }
    private Map<String,Object> proposal(String version,List<?> evidence) {
        return Map.of("operationId",UUID.randomUUID().toString(),"expectedDraftVersion",version,"changes",List.of(Map.of("kind","CREATE_TERM","termKind","OBJECT","clientId","equipment","name","设备")),"evidence",evidence,"questions",List.of(),"samples",List.of());
    }
    @Test void naturalLanguageProposalRequiresHumanDecisionAndRetainsStatement() throws Exception {
        var ctx=context();var input=json.writeValueAsString(request(List.of()));
        var created=tool.semantic_modeling_create_task(input,ctx);var task=(OntologyModelingDtos.Task)created.get("task");
        assertEquals(task.id(),((OntologyModelingDtos.Task)tool.semantic_modeling_create_task(input,ctx).get("task")).id());
        assertThrows(SemanticApiException.class,()->tool.semantic_modeling_submit_proposal(task.id(),json.writeValueAsString(proposal(created.get("draftVersion").toString(),List.of(Map.of("clientId","equipment","origin","USER_STATEMENT","exactQuote","用户已批准自动应用")))),ctx));
        var evidence=List.of(Map.of("clientId","equipment","origin","USER_STATEMENT","exactQuote","设备"));
        var proposal=json.writeValueAsString(proposal(created.get("draftVersion").toString(),evidence));
        var result=tool.semantic_modeling_submit_proposal(task.id(),proposal,ctx);var pending=(OntologyModelingDtos.Task)result.get("task");
        assertEquals("PENDING",pending.proposals().getFirst().status());
        assertEquals(created.get("draftVersion"),result.get("draftVersion"));
        assertEquals(pending,tool.semantic_modeling_submit_proposal(task.id(),proposal,ctx).get("task"));
        assertThrows(SemanticApiException.class,()->tool.semantic_ontology_save_draft(task.ontologyId(),1,"x","",json.writeValueAsString(definition()),UUID.randomUUID().toString(),ctx));
        var accepted=call("POST","/modeling-tasks/"+task.id()+"/proposals/"+pending.proposals().getFirst().id()+"/decision","member",workspace,Map.of("operationId",UUID.randomUUID().toString(),"decision","ACCEPT"),200);
        assertEquals("USER_STATEMENT",accepted.path("proposals").get(0).path("input").path("evidence").get(0).path("origin").asText());
        assertEquals("ACCEPTED",((OntologyModelingDtos.Task)tool.semantic_modeling_get_task(task.id(),ctx).get("task")).proposals().getFirst().status());
        assertTrue(Arrays.stream(OntologyAuthoringTool.class.getMethods()).noneMatch(m->m.isAnnotationPresent(org.springframework.ai.tool.annotation.Tool.class)&&(m.getName().contains("decide")||m.getName().contains("accept")||m.getName().equals("semantic_ontology_publish"))));
    }
    @Test void rejectsUnknownBusinessFieldsWithoutLosingInheritanceAndAppliesExplicitParent() throws Exception {
        var ctx=context();var created=tool.semantic_modeling_create_task(json.writeValueAsString(request(List.of())),ctx);
        var task=(OntologyModelingDtos.Task)created.get("task");
        var changes=List.of(
                Map.of("kind","CREATE_TERM","termKind","OBJECT","clientId","device","name","设备"),
                Map.of("kind","CREATE_TERM","termKind","OBJECT","clientId","sensor","name","传感器","parent","$device"));
        var invalid=new HashMap<String,Object>(proposal(created.get("draftVersion").toString(),List.of()));
        invalid.put("changes",changes);
        var failure=assertThrows(SemanticApiException.class,()->tool.semantic_modeling_submit_proposal(task.id(),json.writeValueAsString(invalid),ctx));
        assertEquals(400,failure.status());assertTrue(failure.getMessage().contains("parent"));
        assertTrue(((OntologyModelingDtos.Task)tool.semantic_modeling_get_task(task.id(),ctx).get("task")).proposals().isEmpty());
        var valid=new HashMap<>(invalid);
        valid.put("changes",List.of(changes.getFirst(),
                Map.of("kind","CREATE_TERM","termKind","OBJECT","clientId","sensor","name","传感器"),
                Map.of("kind","REPLACE_DEFINITION","clientId","sensor-parent","targetId","$sensor","field","PARENT","value","$device")));
        var submitted=(OntologyModelingDtos.Task)tool.semantic_modeling_submit_proposal(task.id(),json.writeValueAsString(valid),ctx).get("task");
        var saved=submitted.proposals().getFirst();assertEquals(3,saved.input().changes().size());
        assertEquals("PARENT",saved.input().changes().get(2).field());
        var accepted=call("POST","/modeling-tasks/"+task.id()+"/proposals/"+saved.id()+"/decision","member",workspace,
                Map.of("operationId",UUID.randomUUID().toString(),"decision","ACCEPT"),200);
        assertEquals("ACCEPTED",accepted.path("proposals").get(0).path("status").asText());
        var result=accepted.path("proposals").get(0).path("result");
        String device=result.path("items").get(0).path("targetId").asText(),sensor=result.path("items").get(1).path("targetId").asText();
        assertTrue(result.path("draft").path("document").path("source").path("documentText").asText().contains("SubClassOf(<"+sensor+"> <"+device+">)"));
    }

    @Test void selectedLongSourceCoveragePagingAndPermissionRevocationApplyToReplay() throws Exception {
        String kb=com.baomidou.mybatisplus.core.toolkit.IdWorker.getIdStr();var ctx=context(kb);String raw=com.baomidou.mybatisplus.core.toolkit.IdWorker.getIdStr();String body="设备😀".repeat(30000);var now=java.time.LocalDateTime.now();
        jdbc.update("INSERT INTO mate_wiki_knowledge_base(id,name,description,status,page_count,raw_count,workspace_id,create_time,update_time,deleted) VALUES(?,?,?,?,?,?,?,?,?,0)",Long.valueOf(kb),"Source KB","","active",0,0,Long.valueOf(workspace),now,now);
        jdbc.update("INSERT INTO mate_wiki_raw_material(id,kb_id,title,source_type,original_content,extracted_text,processing_status,create_time,update_time,deleted) VALUES(?,?,?,?,?,?,?,?,?,0)",raw,kb,"长资料","text",body,body,"processed",now,now);
        String second=com.baomidou.mybatisplus.core.toolkit.IdWorker.getIdStr();
        jdbc.update("INSERT INTO mate_wiki_raw_material(id,kb_id,title,source_type,original_content,extracted_text,processing_status,create_time,update_time,deleted) VALUES(?,?,?,?,?,?,?,?,?,0)",second,kb,"待解析","text","","","failed",now,now);
        var page=tool.semantic_modeling_sources(kb,1,1,ctx);assertEquals(true,page.get("hasMore"));
        var secondPage=tool.semantic_modeling_sources(kb,2,1,ctx);assertEquals(false,secondPage.get("hasMore"));
        assertEquals("NOT_PARSED",((Map<?,?>)((List<?>)secondPage.get("items")).getFirst()).get("unreadReason"));assertFalse(((List<?>)page.get("items")).isEmpty());
        var source=Map.of("knowledgeBaseId",kb,"sourceRef",raw,"sourceDigest",vip.mate.semantic.core.ontology.OntologyDocument.sha256(body));
        String input=json.writeValueAsString(request(List.of(source)));var created=tool.semantic_modeling_create_task(input,ctx);var task=(OntologyModelingDtos.Task)created.get("task");
        var chunk=tool.semantic_modeling_read_source(task.id(),kb,raw,0,16000,ctx);
        assertEquals(90000,chunk.get("totalCodePoints"));assertEquals(16000,chunk.get("endCodePoint"));assertEquals(true,chunk.get("hasMore"));
        assertEquals(16000,chunk.get("content").toString().codePointCount(0,chunk.get("content").toString().length()));
        var last=tool.semantic_modeling_read_source(task.id(),kb,raw,80000,16000,ctx);assertEquals(false,last.get("hasMore"));assertEquals(90000,last.get("endCodePoint"));
        String proposal=json.writeValueAsString(proposal(created.get("draftVersion").toString(),List.of()));tool.semantic_modeling_submit_proposal(task.id(),proposal,ctx);
        org.mockito.Mockito.when(wikiKnowledgeBases.findVisibleById(ChatOrigin.from(ctx).agentId(),Long.valueOf(kb))).thenReturn(null);
        assertThrows(SemanticApiException.class,()->tool.semantic_modeling_get_task(task.id(),ctx));
        assertThrows(SemanticApiException.class,()->tool.semantic_modeling_submit_proposal(task.id(),proposal,ctx));
        assertThrows(SemanticApiException.class,()->tool.semantic_modeling_create_task(input,ctx));
        assertThrows(SemanticApiException.class,()->tool.semantic_modeling_read_source(task.id(),kb,raw,0,100,ctx));
    }
}

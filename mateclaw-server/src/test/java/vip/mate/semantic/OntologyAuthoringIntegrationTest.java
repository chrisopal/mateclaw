package vip.mate.semantic;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.security.core.context.SecurityContextHolder;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.semantic.authoring.OntologyAuthoringTool;
import vip.mate.semantic.support.SemanticHttpFixture;
import vip.mate.semantic.web.SemanticApiException;
import static org.junit.jupiter.api.Assertions.*;

class OntologyAuthoringIntegrationTest extends SemanticHttpFixture {
    @Autowired OntologyAuthoringTool tool;
    private ToolContext context(String role) {
        Long user=jdbc.queryForObject("SELECT user_id FROM mate_workspace_member WHERE workspace_id=? AND role=? AND deleted=0",Long.class,Long.valueOf(workspace),role);
        return ChatOrigin.web("ontology-build","display",Long.valueOf(workspace),null,null,user).withAgent(agentWithKnowledgeBase("123456")).toToolContext();
    }
    @Test void draftValidationHumanPublicationAndExactReadback() throws Exception {
        var context=context("member");
        var original=SecurityContextHolder.getContext();
        var draft=tool.semantic_ontology_create_draft("质量领域","依据检验规范；待专家确认",json.writeValueAsString(definition()),context);
        assertSame(original,SecurityContextHolder.getContext());
        assertTrue(tool.semantic_ontology_validate(draft.ontologyId(),draft.draftVersion(),context).valid());
        var prepare=tool.semantic_ontology_prepare_publish(draft.ontologyId(),draft.draftVersion(),context);
        assertEquals("HUMAN_REVIEW_REQUIRED",prepare.get("status"));
        assertTrue(((List<?>)tool.semantic_ontology_get(draft.ontologyId(),context).get("revisions")).isEmpty());
        call("POST","/ontologies/"+draft.ontologyId()+"/draft/publish","member",workspace,
                Map.of("expectedDraftVersion",draft.draftVersion(),"operationId",UUID.randomUUID().toString(),"note","业务核对完成"),403);
        var revision=publish(draft.ontologyId(),draft.draftVersion(),UUID.randomUUID().toString());
        assertEquals(1,((List<?>)tool.semantic_ontology_get(draft.ontologyId(),context).get("revisions")).size());
        var copied=tool.semantic_ontology_copy_revision(draft.ontologyId(),revision.path("id").asText(),context);
        assertEquals(revision.path("id").asText(),copied.baseRevisionId());
    }
    @Test void rejectsUntrustedIdentityViewerAndStaleDraftAndRestoresContext() throws Exception {
        assertThrows(SemanticApiException.class,()->tool.semantic_ontology_list("",1,new ToolContext(Map.of())));
        var viewer=context("viewer");
        assertThrows(SemanticApiException.class,()->tool.semantic_ontology_create_draft("x","",json.writeValueAsString(definition()),viewer));
        var member=context("member");var original=SecurityContextHolder.getContext();
        var draft=tool.semantic_ontology_create_draft("x","",json.writeValueAsString(definition()),member);
        tool.semantic_ontology_save_draft(draft.ontologyId(),draft.draftVersion(),"x","updated",json.writeValueAsString(definition()),UUID.randomUUID().toString(),member);
        assertThrows(SemanticApiException.class,()->tool.semantic_ontology_validate(draft.ontologyId(),draft.draftVersion(),member));
        assertSame(original,SecurityContextHolder.getContext());
        long agent=ChatOrigin.from(member).agentId();
        jdbc.update("UPDATE mate_agent SET workspace_id=? WHERE id=?",Long.valueOf(otherWorkspace),agent);
        assertThrows(SemanticApiException.class,()->tool.semantic_ontology_get(draft.ontologyId(),member));
    }
    @Test void rejectsMalformedDraftAtomicallyAndUnauthorizedSource() throws Exception {
        var member=context("member");
        long before=tool.semantic_ontology_list("",1,member).total();
        assertThrows(SemanticApiException.class,()->tool.semantic_ontology_create_draft("bad","","{}",member));
        assertEquals(before,tool.semantic_ontology_list("",1,member).total());
        assertThrows(SemanticApiException.class,()->tool.semantic_ontology_read_source("999999","123",member));
    }

    @Test void readsActualMaterialsWithoutGraphAndRejectsDeletedSource() {
        var member=context("member");
        String source=com.baomidou.mybatisplus.core.toolkit.IdWorker.getIdStr();
        var now=java.time.LocalDateTime.now();
        jdbc.update("INSERT INTO mate_wiki_raw_material(id,kb_id,title,source_type,original_content,extracted_text,processing_status,create_time,update_time,deleted) VALUES(?,?,?,?,?,?,?,?,?,0)",
                source,"123456","检验规范","text","尺寸偏差以mm记录。","尺寸偏差以mm记录。","processed",now,now);
        var result=tool.semantic_ontology_read_source("123456",source,member);
        assertEquals(source,result.get("sourceRef"));
        assertEquals("尺寸偏差以mm记录。",result.get("content"));
        assertEquals(64,result.get("sha256").toString().length());
        jdbc.update("UPDATE mate_wiki_raw_material SET deleted=1 WHERE id=?",source);
        assertThrows(SemanticApiException.class,()->tool.semantic_ontology_read_source("123456",source,member));
    }
}

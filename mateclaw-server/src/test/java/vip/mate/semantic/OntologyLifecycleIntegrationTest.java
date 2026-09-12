package vip.mate.semantic;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vip.mate.semantic.support.SemanticHttpFixture;

class OntologyLifecycleIntegrationTest extends SemanticHttpFixture {
    @Test void archiveRetainsHistoryAndDraftAndRestoreKeepsVersionsClosed() throws Exception {
        String id=create(), base="/ontologies/"+id;
        draft(id); save(id,1); var published=publish(id,2,UUID.randomUUID().toString());
        String kb=kb(), secondKb=kb();
        String binding="/knowledge-bases/"+kb+"/binding";
        var graph=call("PUT",binding,"owner",workspace,Map.of("action","ENABLE","revisionId",published.path("id").asText()),200);
        draft(id);
        var before=call("GET",base,"viewer",workspace,null,200);
        var request=Map.of("expectedUpdatedAt",before.path("updatedAt").asText());
        call("POST",base+"/archive","owner",workspace,Map.of("expectedUpdatedAt",123),400);
        call("POST",base+"/archive","owner",workspace,Map.of("expectedUpdatedAt","invalid-time"),400);
        call("POST",base+"/archive","member",workspace,request,403);
        call("POST",base+"/archive","owner",otherWorkspace,request,404);
        call("POST",base+"/archive","owner",workspace,Map.of("expectedUpdatedAt","2000-01-01T00:00:00Z"),409);
        var archived=call("POST",base+"/archive","owner",workspace,request,200);
        assertTrue(archived.path("archived").asBoolean());
        assertEquals(archived,call("POST",base+"/archive","owner",workspace,request,200));
        call("GET",base+"/draft","viewer",workspace,null,200);
        call("PUT","/knowledge-bases/"+secondKb+"/binding","owner",workspace,Map.of("action","ENABLE","revisionId",published.path("id").asText()),409);
        var disabled=call("PUT",binding,"owner",workspace,Map.of("action","DISABLE","expectedGraphVersion",graph.path("graphVersion").asLong()),200);
        call("PUT",binding,"owner",workspace,Map.of("action","ENABLE","expectedGraphVersion",disabled.path("graphVersion").asLong()),200);
        String revision=base+"/revisions/"+published.path("id").asText();
        assertFalse(call("GET",revision,"viewer",workspace,null,200).path("availableForNewBindings").asBoolean());
        call("POST",base+"/draft","member",workspace,Map.of(),409);
        call("PUT",base+"/draft","member",workspace,saveBody(1,definition()),409);
        call("POST",base+"/draft/publish","owner",workspace,publishBody(1,UUID.randomUUID().toString()),409);
        call("PATCH",revision+"/availability","owner",workspace,Map.of("availableForNewBindings",true),409);
        var restored=call("POST",base+"/restore","owner",workspace,Map.of("expectedUpdatedAt",archived.path("updatedAt").asText()),200);
        assertFalse(restored.path("archived").asBoolean());
        assertFalse(call("GET",revision,"viewer",workspace,null,200).path("availableForNewBindings").asBoolean());
        call("PATCH",revision+"/availability","owner",workspace,Map.of("availableForNewBindings",true),200);
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM mate_semantic_ontology_revision WHERE ontology_id=? AND revision_state='PUBLISHED'",Integer.class,id));
    }
    @Test void unpublishedOntologyCanBeArchivedWithOntologyLevelAudit() throws Exception {
        String id=create(), base="/ontologies/"+id;
        var before=call("GET",base,"viewer",workspace,null,200);
        call("POST",base+"/archive","owner",workspace,Map.of("expectedUpdatedAt",before.path("updatedAt").asText()),200);
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM mate_semantic_governance_record WHERE ontology_id=? AND action='ARCHIVE_ONTOLOGY' AND revision_id IS NULL",Integer.class,id));
    }
    private String kb() {
        String id=com.baomidou.mybatisplus.core.toolkit.IdWorker.getIdStr();
        var now=java.time.LocalDateTime.now();
        jdbc.update("INSERT INTO mate_wiki_knowledge_base(id,name,description,status,page_count,raw_count,workspace_id,create_time,update_time,deleted) VALUES(?,?,?,?,?,?,?,?,?,0)",Long.valueOf(id),"Lifecycle KB","","active",0,0,Long.valueOf(workspace),now,now);
        return id;
    }
}

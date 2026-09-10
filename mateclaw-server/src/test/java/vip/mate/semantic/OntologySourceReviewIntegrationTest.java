package vip.mate.semantic;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import vip.mate.semantic.support.SemanticHttpFixture;
import vip.mate.semantic.core.ontology.OntologyDocument;
import vip.mate.semantic.query.SemanticContextService;
import vip.mate.semantic.query.SemanticContextDtos.Request;
import vip.mate.semantic.web.SemanticApiException;

class OntologySourceReviewIntegrationTest extends SemanticHttpFixture {
    @Autowired SemanticContextService contexts;
    @Autowired vip.mate.semantic.authoring.OntologyAuthoringTool authoring;
    private record Fixture(String ontology,String draft,List<String> axioms,String kb,String raw,String text) {}
    private Fixture fixture() throws Exception {
        String ontology=create();draft(ontology);var saved=save(ontology,1);
        String kb=com.baomidou.mybatisplus.core.toolkit.IdWorker.getIdStr(),raw=com.baomidou.mybatisplus.core.toolkit.IdWorker.getIdStr();
        var now=LocalDateTime.now();String text="设备😀用于测量。测针是设备组件。";
        jdbc.update("INSERT INTO mate_wiki_knowledge_base(id,name,description,status,page_count,raw_count,workspace_id,create_time,update_time,deleted) VALUES(?,?,?,?,?,?,?,?,?,0)",Long.valueOf(kb),"Source KB","","active",0,0,Long.valueOf(workspace),now,now);
        jdbc.update("INSERT INTO mate_wiki_raw_material(id,kb_id,title,source_type,original_content,file_size,processing_status,create_time,update_time,deleted) VALUES(?,?,?,?,?,?,?,?,?,0)",Long.valueOf(raw),Long.valueOf(kb),"Expert definitions","text",text,text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,"completed",now,now);
        List<String> axioms=new ArrayList<>();saved.path("document").path("axioms").forEach(a->axioms.add(a.path("axiomId").asText()));
        return new Fixture(ontology,saved.path("id").asText(),axioms,kb,raw,text);
    }
    private Map<String,Object> bind(Fixture f,long version,String axiom,String operation) {
        return Map.of("expectedDraftVersion",version,"operationId",operation,"axiomId",axiom,"knowledgeBaseId",f.kb(),"sourceRef",f.raw(),
            "expectedSourceDigest",OntologyDocument.sha256(f.text()),"startCodePoint",2,"endCodePoint",3,"exactQuote","😀","origin","EXTRACTED");
    }
    @Test void bindsExactSharedSnapshotAndCopiesWithoutLosingPublishedHistory() throws Exception {
        var f=fixture();var request=bind(f,2,f.axioms().get(0),UUID.randomUUID().toString());
        var first=call("POST","/ontologies/"+f.ontology()+"/draft/axiom-sources","member",workspace,request,200);
        assertEquals(first,call("POST","/ontologies/"+f.ontology()+"/draft/axiom-sources","member",workspace,request,200));
        var second=call("POST","/ontologies/"+f.ontology()+"/draft/axiom-sources","member",workspace,bind(f,3,f.axioms().get(1),UUID.randomUUID().toString()),200);
        assertEquals(first.path("binding").path("sourceSnapshotId"),second.path("binding").path("sourceSnapshotId"));
        var published=publish(f.ontology(),4,UUID.randomUUID().toString());
        var copied=call("POST","/ontologies/"+f.ontology()+"/draft","member",workspace,Map.of("baseRevisionId",f.draft()),200);
        var bindings=call("GET","/ontologies/"+f.ontology()+"/revisions/"+copied.path("id").asText()+"/axiom-sources","viewer",workspace,null,200);
        assertEquals(2,bindings.size());assertNotEquals(first.path("binding").path("id"),bindings.get(0).path("id"));
        assertEquals(first.path("binding").path("sourceSnapshotId"),bindings.get(0).path("sourceSnapshotId"));
        call("DELETE","/ontologies/"+f.ontology()+"/draft?expectedDraftVersion="+copied.path("draftVersion").asLong(),"member",workspace,null,200);
        assertEquals(2,call("GET","/ontologies/"+f.ontology()+"/revisions/"+f.draft()+"/axiom-sources","viewer",workspace,null,200).size());
        assertEquals(published,call("GET","/ontologies/"+f.ontology()+"/revisions/"+f.draft(),"viewer",workspace,null,200));
    }
    @Test void changedAndDeletedSourcesRequireReviewWithoutRewritingPublishedAxioms() throws Exception {
        var f=fixture();call("POST","/ontologies/"+f.ontology()+"/draft/axiom-sources","member",workspace,bind(f,2,f.axioms().get(0),UUID.randomUUID().toString()),200);
        var published=publish(f.ontology(),3,UUID.randomUUID().toString());
        jdbc.update("UPDATE mate_wiki_raw_material SET original_content=? WHERE id=?","修订后的定义",Long.valueOf(f.raw()));
        var command=Map.of("operationId",UUID.randomUUID().toString());
        var reviews=call("POST","/ontologies/"+f.ontology()+"/source-reviews/scan","member",workspace,command,200);
        assertEquals(reviews,call("POST","/ontologies/"+f.ontology()+"/source-reviews/scan","member",workspace,command,200));
        var changed=java.util.stream.StreamSupport.stream(reviews.spliterator(),false).filter(r->r.path("sourceState").asText().equals("CHANGED")).findFirst().orElseThrow();
        assertFalse(changed.path("observedSnapshotId").asText().isBlank());
        var comparison=call("GET","/ontologies/"+f.ontology()+"/source-reviews/"+changed.path("id").asText()+"/snapshots","viewer",workspace,null,200);
        assertEquals(f.text(),comparison.path("original").path("sourceText").asText());
        assertEquals("修订后的定义",comparison.path("observed").path("sourceText").asText());
        call("GET","/ontologies/"+f.ontology()+"/source-reviews/"+changed.path("id").asText()+"/snapshots","owner",otherWorkspace,null,404);
        jdbc.update("UPDATE mate_wiki_raw_material SET deleted=1 WHERE id=?",Long.valueOf(f.raw()));
        call("POST","/ontologies/"+f.ontology()+"/source-reviews/"+changed.path("id").asText()+"/decision","owner",workspace,
            Map.of("operationId",UUID.randomUUID().toString(),"expectedObservedDigest",changed.path("observedDigest").asText(),"decision","ACKNOWLEDGE","reason","Stale observation"),409);
        var deleted=call("POST","/ontologies/"+f.ontology()+"/source-reviews/scan","member",workspace,Map.of("operationId",UUID.randomUUID().toString()),200);
        var unavailable=java.util.stream.StreamSupport.stream(deleted.spliterator(),false).filter(r->r.path("sourceState").asText().equals("UNAVAILABLE")).findFirst().orElseThrow();
        var decision=Map.of("operationId",UUID.randomUUID().toString(),"expectedObservedDigest",unavailable.path("observedDigest").asText(),"decision","KEEP_HISTORICAL","reason","Retain original snapshot and require new modeling separately");
        call("POST","/ontologies/"+f.ontology()+"/source-reviews/"+unavailable.path("id").asText()+"/decision","member",workspace,decision,403);
        var reviewed=call("POST","/ontologies/"+f.ontology()+"/source-reviews/"+unavailable.path("id").asText()+"/decision","owner",workspace,decision,200);
        assertEquals(reviewed,call("POST","/ontologies/"+f.ontology()+"/source-reviews/"+unavailable.path("id").asText()+"/decision","owner",workspace,decision,200));
        assertEquals(published,call("GET","/ontologies/"+f.ontology()+"/revisions/"+f.draft(),"viewer",workspace,null,200));
        assertEquals(f.text(),jdbc.queryForObject("SELECT source_text FROM mate_semantic_ontology_source_snapshot WHERE source_ref=? AND source_digest=?",String.class,f.raw(),OntologyDocument.sha256(f.text())));
    }
    @Test void sharedSourceChangeFansOutToEveryAxiomWithIndependentReviewAndDeduplicatedSnapshots() throws Exception {
        var f=fixture();
        var first=call("POST","/ontologies/"+f.ontology()+"/draft/axiom-sources","member",workspace,bind(f,2,f.axioms().get(0),UUID.randomUUID().toString()),200);
        var second=call("POST","/ontologies/"+f.ontology()+"/draft/axiom-sources","member",workspace,bind(f,3,f.axioms().get(1),UUID.randomUUID().toString()),200);
        var published=publish(f.ontology(),4,UUID.randomUUID().toString());
        Set<String> bindingIds=Set.of(first.path("binding").path("id").asText(),second.path("binding").path("id").asText());
        jdbc.update("UPDATE mate_wiki_raw_material SET original_content=? WHERE id=?","Both definitions changed",Long.valueOf(f.raw()));
        var command=Map.of("operationId",UUID.randomUUID().toString());
        var scanned=call("POST","/ontologies/"+f.ontology()+"/source-reviews/scan","member",workspace,command,200);
        assertEquals(scanned,call("POST","/ontologies/"+f.ontology()+"/source-reviews/scan","member",workspace,command,200));
        assertEquals(scanned,call("POST","/ontologies/"+f.ontology()+"/source-reviews/scan","member",workspace,Map.of("operationId",UUID.randomUUID().toString()),200),
            "A fresh scan of the same source version must not duplicate review items");
        var changed=java.util.stream.StreamSupport.stream(scanned.spliterator(),false)
            .filter(v->v.path("sourceState").asText().equals("CHANGED")).toList();
        assertEquals(2,changed.size());
        assertEquals(bindingIds,changed.stream().map(v->v.path("bindingId").asText()).collect(java.util.stream.Collectors.toSet()));
        assertEquals(changed.get(0).path("observedSnapshotId"),changed.get(1).path("observedSnapshotId"));
        for(var review:changed)assertEquals("PENDING",review.path("reviewState").asText());
        String reviewedId=changed.get(0).path("id").asText();
        call("POST","/ontologies/"+f.ontology()+"/source-reviews/"+reviewedId+"/decision","owner",workspace,
            Map.of("operationId",UUID.randomUUID().toString(),"expectedObservedDigest",changed.get(0).path("observedDigest").asText(),"decision","ACKNOWLEDGE","reason","Review only this axiom"),200);
        var remaining=call("GET","/ontologies/"+f.ontology()+"/source-reviews","viewer",workspace,null,200);
        assertTrue(java.util.stream.StreamSupport.stream(remaining.spliterator(),false).anyMatch(v->
            v.path("id").equals(changed.get(1).path("id"))&&v.path("reviewState").asText().equals("PENDING")),"One axiom decision must not acknowledge its sibling");
        jdbc.update("UPDATE mate_wiki_raw_material SET deleted=1 WHERE id=?",Long.valueOf(f.raw()));
        var deleted=call("POST","/ontologies/"+f.ontology()+"/source-reviews/scan","member",workspace,Map.of("operationId",UUID.randomUUID().toString()),200);
        assertEquals(2,java.util.stream.StreamSupport.stream(deleted.spliterator(),false)
            .filter(v->v.path("sourceState").asText().equals("UNAVAILABLE")&&v.path("reviewState").asText().equals("PENDING")).count());
        assertTrue(java.util.stream.StreamSupport.stream(deleted.spliterator(),false).anyMatch(v->
            v.path("id").asText().equals(reviewedId)&&v.path("reviewState").asText().equals("REVIEWED")),"Completed decisions remain historical records");
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM mate_semantic_ontology_source_snapshot WHERE source_ref=?",Integer.class,f.raw()));
        assertEquals(published,call("GET","/ontologies/"+f.ontology()+"/revisions/"+f.draft(),"viewer",workspace,null,200));
    }

    @Test void ontologiesSharingMaterialShareSnapshotsButNotReviewDecisions() throws Exception {
        var first=fixture();String other=create();draft(other);var saved=save(other,1);
        var second=new Fixture(other,saved.path("id").asText(),List.of(saved.path("document").path("axioms").get(0).path("axiomId").asText()),first.kb(),first.raw(),first.text());
        var a=call("POST","/ontologies/"+first.ontology()+"/draft/axiom-sources","member",workspace,bind(first,2,first.axioms().get(0),UUID.randomUUID().toString()),200);
        var b=call("POST","/ontologies/"+second.ontology()+"/draft/axiom-sources","member",workspace,bind(second,2,second.axioms().get(0),UUID.randomUUID().toString()),200);
        assertEquals(a.path("binding").path("sourceSnapshotId"),b.path("binding").path("sourceSnapshotId"));
        var publishedA=publish(first.ontology(),3,UUID.randomUUID().toString());
        var publishedB=publish(second.ontology(),3,UUID.randomUUID().toString());
        jdbc.update("UPDATE mate_wiki_raw_material SET original_content=? WHERE id=?","Shared updated source",Long.valueOf(first.raw()));
        var changed=new ArrayList<com.fasterxml.jackson.databind.JsonNode>();
        for(var f:List.of(first,second)) {
            var scanned=call("POST","/ontologies/"+f.ontology()+"/source-reviews/scan","member",workspace,Map.of("operationId",UUID.randomUUID().toString()),200);
            changed.add(java.util.stream.StreamSupport.stream(scanned.spliterator(),false)
                .filter(v->v.path("sourceState").asText().equals("CHANGED")).findFirst().orElseThrow());
        }
        assertEquals(changed.get(0).path("observedSnapshotId"),changed.get(1).path("observedSnapshotId"));
        var decision=Map.of("operationId",UUID.randomUUID().toString(),"expectedObservedDigest",changed.get(0).path("observedDigest").asText(),"decision","ACKNOWLEDGE","reason","Only first ontology reviewed");
        call("POST","/ontologies/"+second.ontology()+"/source-reviews/"+changed.get(0).path("id").asText()+"/decision","owner",workspace,decision,404);
        call("POST","/ontologies/"+first.ontology()+"/source-reviews/"+changed.get(0).path("id").asText()+"/decision","owner",workspace,decision,200);
        var remaining=call("GET","/ontologies/"+second.ontology()+"/source-reviews","viewer",workspace,null,200);
        assertTrue(java.util.stream.StreamSupport.stream(remaining.spliterator(),false).anyMatch(v->v.path("id").equals(changed.get(1).path("id"))&&v.path("reviewState").asText().equals("PENDING")));
        assertEquals(publishedA,call("GET","/ontologies/"+first.ontology()+"/revisions/"+first.draft(),"viewer",workspace,null,200));
        assertEquals(publishedB,call("GET","/ontologies/"+second.ontology()+"/revisions/"+second.draft(),"viewer",workspace,null,200));
    }

    @Test void wrongQuoteCannotMutateDraftAndAgentSourceReadIsVersionAndPermissionBound() throws Exception {
        var f=fixture();var wrong=new HashMap<>(bind(f,2,f.axioms().get(0),UUID.randomUUID().toString()));wrong.put("exactQuote","错");
        call("POST","/ontologies/"+f.ontology()+"/draft/axiom-sources","member",workspace,wrong,422);
        assertEquals(2,call("GET","/ontologies/"+f.ontology()+"/draft","member",workspace,null,200).path("draftVersion").asLong());
        call("POST","/ontologies/"+f.ontology()+"/draft/axiom-sources","owner",otherWorkspace,wrong,404);
        var bound=call("POST","/ontologies/"+f.ontology()+"/draft/axiom-sources","member",workspace,bind(f,2,f.axioms().get(0),UUID.randomUUID().toString()),200);
        publish(f.ontology(),3,UUID.randomUUID().toString());
        String graph=call("PUT","/knowledge-bases/"+f.kb()+"/binding","owner",workspace,Map.of("action","ENABLE","revisionId",f.draft()),200).path("graphId").asText();
        Long agent=agentWithKnowledgeBase(f.kb());String actor=jdbc.queryForObject("SELECT user_id FROM mate_workspace_member WHERE workspace_id=? AND role='member' AND deleted=0",String.class,Long.valueOf(workspace));
        String binding=bound.path("binding").path("id").asText();
        assertEquals(f.text(),contexts.source(workspace,actor,agent,graph,binding).sourceText());
        var context=contexts.context(workspace,actor,agent,graph,new Request("",Set.of(),null,6000,null));
        assertTrue(context.axioms().stream().anyMatch(a->a.sourceBindingIds().contains(binding)));
        jdbc.update("UPDATE mate_wiki_raw_material SET original_content=? WHERE id=?","Updated definition",Long.valueOf(f.raw()));
        var updated=contexts.context(workspace,actor,agent,graph,new Request("",Set.of(),null,6000,null));
        assertTrue(updated.axioms().stream().flatMap(a->a.sources().stream()).anyMatch(v->v.bindingId().equals(binding)&&v.currentState().equals("CHANGED")&&v.reviewState().equals("PENDING")));
        assertEquals(f.text(),contexts.source(workspace,actor,agent,graph,binding).sourceText());
        when(wikiKnowledgeBases.findVisibleById(agent,Long.valueOf(f.kb()))).thenReturn(null);
        assertThrows(SemanticApiException.class,()->contexts.source(workspace,actor,agent,graph,binding));
    }
    @Test void authoringToolBindsOnlyAgentVisibleSourcesAndRestoresIdentity() throws Exception {
        var f=fixture(); Long agent=agentWithKnowledgeBase(f.kb());
        Long actor=jdbc.queryForObject("SELECT user_id FROM mate_workspace_member WHERE workspace_id=? AND role='member' AND deleted=0",Long.class,Long.valueOf(workspace));
        var context=vip.mate.agent.context.ChatOrigin.web("source-bind","display",Long.valueOf(workspace),null,null,actor).withAgent(agent).toToolContext();
        var original=org.springframework.security.core.context.SecurityContextHolder.getContext();
        String operation=UUID.randomUUID().toString();
        var result=authoring.semantic_ontology_bind_source(f.ontology(),2,operation,f.axioms().get(0),f.kb(),f.raw(),OntologyDocument.sha256(f.text()),2,3,"😀",context);
        assertEquals(3,result.draftVersion());
        assertEquals(result,authoring.semantic_ontology_bind_source(f.ontology(),2,operation,f.axioms().get(0),f.kb(),f.raw(),OntologyDocument.sha256(f.text()),2,3,"😀",context));
        assertSame(original,org.springframework.security.core.context.SecurityContextHolder.getContext());
        when(wikiKnowledgeBases.findVisibleById(agent,Long.valueOf(f.kb()))).thenReturn(null);
        assertThrows(SemanticApiException.class,()->authoring.semantic_ontology_bind_source(f.ontology(),2,operation,f.axioms().get(0),f.kb(),f.raw(),OntologyDocument.sha256(f.text()),2,3,"😀",context));
        assertSame(original,org.springframework.security.core.context.SecurityContextHolder.getContext());
    }

}

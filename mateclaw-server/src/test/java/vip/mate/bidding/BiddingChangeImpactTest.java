package vip.mate.bidding;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class BiddingChangeImpactTest extends BiddingHttpFixture {
    @Autowired BiddingDependencies dependencies;
    @Autowired BiddingRepository repository;

    @Test void deadlineOnlyTransitionClonesBothChapterBodiesOntoNewOutlineWithCas() throws Exception {
        var fixture=baselineTransition(false);
        JsonNode impact=dependencies.impact(fixture.scope(),fixture.oldBaseline());
        assertEquals(2,impact.path("unaffectedRefs").size());
        assertEquals(0,impact.path("unknownRefs").size());
        JsonNode read=api("GET","/projects/"+fixture.scope().projectId()+"/change-impact","owner",workspace,null,200);
        assertTrue(read.path("events").get(0).path("confirmation").path("ready").asBoolean());
        assertEquals(2,read.path("events").get(0).path("confirmation").path("payload").path("unchangedRefs").size());
        ObjectNode confirmed=dependencies.reconfirm(fixture.scope(),fixture.command(impact,"deadline-only-reconfirm"));
        assertEquals(2,confirmed.path("clonedRefs").size());
        for(BiddingTypes.Ref oldChapter:fixture.chapters()) {
            BiddingTypes.Ref selected=repository.selectedChapterRefs(fixture.scope()).stream().filter(r->r.id().equals(oldChapter.id())).findFirst().orElseThrow();
            assertNotEquals(oldChapter,selected);
            assertEquals(List.of(fixture.newOutline()),repository.businessRefs(repository.businessRevision(fixture.scope(),selected)));
            assertEquals("NEEDS_RECONFIRMATION",repository.businessRevision(fixture.scope(),oldChapter).path("status").asText());
            assertEquals(repository.businessRevision(fixture.scope(),oldChapter).path("chapter"),
                    json.readTree(repository.rawRevisionPayload(fixture.scope(),selected)).path("chapter"));
        }
        assertEquals("CONFIRMED",repository.changeEvent(fixture.scope(),fixture.eventId()).path("status").asText());
    }

    @Test void changedTechnicalRequirementBlocksOnlyMappedChapterAndClonesTheOther() throws Exception {
        var fixture=baselineTransition(true);
        JsonNode impact=dependencies.impact(fixture.scope(),fixture.oldBaseline());
        assertEquals(List.of(fixture.chapters().get(0)),refs(impact.path("affectedRefs")));
        assertEquals(List.of(fixture.chapters().get(1)),refs(impact.path("unaffectedRefs")));
        ObjectNode confirmed=dependencies.reconfirm(fixture.scope(),fixture.command(impact,"technical-reconfirm"));
        assertEquals(1,confirmed.path("clonedRefs").size());
        assertEquals(fixture.chapters().get(0),repository.selectedChapterRefs(fixture.scope()).stream().filter(r->r.id().equals("chapter-1")).findFirst().orElseThrow());
        assertEquals("NEEDS_RECONFIRMATION",repository.businessRevision(fixture.scope(),fixture.chapters().get(0)).path("status").asText());
        assertNotEquals(fixture.chapters().get(1),repository.selectedChapterRefs(fixture.scope()).stream().filter(r->r.id().equals("chapter-2")).findFirst().orElseThrow());
        assertTrue(repository.changeEvent(fixture.scope(),fixture.eventId()).path("impact").path("formalBlocked").asBoolean());
    }

    @Test void unavailableReplacementEvidenceKeepsChaptersUnknownAndHidesConfirmation() throws Exception {
        var fixture=baselineTransition(false);
        jdbc.update("UPDATE mate_bidding_source SET blocks_json='[]' WHERE workspace_id=? AND project_id=?",fixture.scope().workspaceId(),fixture.scope().projectId());
        JsonNode impact=dependencies.impact(fixture.scope(),fixture.oldBaseline());
        assertEquals(2,impact.path("unknownRefs").size());
        JsonNode read=api("GET","/projects/"+fixture.scope().projectId()+"/change-impact","owner",workspace,null,200);
        assertFalse(read.path("events").get(0).has("confirmation"));
    }

    @Test void sourceAndSourceSetEventsResolveOnlyThroughConfirmedBaselineEvidenceChain() throws Exception {
        var fixture=baselineTransition(false,true,false);
        JsonNode baseImpact=dependencies.impact(fixture.scope(),fixture.oldBaseline());
        assertEquals(0,baseImpact.path("unknownRefs").size(),baseImpact.toString());
        dependencies.reconfirm(fixture.scope(),fixture.command(baseImpact,"baseline-chain-confirm"));
        for(JsonNode event:repository.changeEvents(fixture.scope())) {
            String kind=event.path("changedRef").path("kind").asText();
            if(!Set.of("source","sourceSet").contains(kind))continue;
            BiddingTypes.Ref changed=json.treeToValue(event.path("changedRef"),BiddingTypes.Ref.class);
            JsonNode impact=dependencies.impact(fixture.scope(),changed);
            assertEquals(0,impact.path("unknownRefs").size(),kind+" must use the exact confirmed baseline chain");
            JsonNode read=api("GET","/projects/"+fixture.scope().projectId()+"/change-impact","owner",workspace,null,200);
            JsonNode view=java.util.stream.StreamSupport.stream(read.path("events").spliterator(),false)
                    .filter(item->item.path("eventId").asText().equals(event.path("eventId").asText())).findFirst().orElseThrow();
            assertTrue(view.path("confirmation").path("ready").asBoolean());
            dependencies.reconfirm(fixture.scope(),fixture.command(event,impact,"resolve-"+kind));
        }
        for(BiddingTypes.Ref old:fixture.chapters()) {
            BiddingTypes.Ref selected=repository.selectedChapterRefs(fixture.scope()).stream().filter(r->r.id().equals(old.id())).findFirst().orElseThrow();
            JsonNode cloned=json.readTree(repository.rawRevisionPayload(fixture.scope(),selected));
            assertEquals("2",cloned.path("citations").get(0).path("version").asText());
            assertEquals(json.readTree(repository.rawRevisionPayload(fixture.scope(),old)).path("chapter"),cloned.path("chapter"));
        }
    }

    @Test void changedNewSourceQuoteDoesNotProveSameChapterBody() throws Exception {
        var fixture=baselineTransition(false,true,true);
        JsonNode impact=dependencies.impact(fixture.scope(),fixture.oldBaseline());
        assertEquals(2,impact.path("unknownRefs").size());
        JsonNode read=api("GET","/projects/"+fixture.scope().projectId()+"/change-impact","owner",workspace,null,200);
        assertFalse(read.path("events").get(0).has("confirmation"));
    }

    private final class BaselineFixture {
        private final BiddingTypes.Scope scope; private final BiddingTypes.Ref oldBaseline,newOutline,oldOutline;
        private final List<BiddingTypes.Ref> chapters; private final String eventId,chapterBody;
        BaselineFixture(BiddingTypes.Scope scope,BiddingTypes.Ref oldBaseline,BiddingTypes.Ref newOutline,List<BiddingTypes.Ref> chapters,
                String eventId,BiddingTypes.Ref oldOutline,String chapterBody) {
            this.scope=scope;this.oldBaseline=oldBaseline;this.newOutline=newOutline;this.chapters=chapters;this.eventId=eventId;this.oldOutline=oldOutline;this.chapterBody=chapterBody;
        }
        BiddingTypes.Scope scope(){return scope;} BiddingTypes.Ref oldBaseline(){return oldBaseline;} BiddingTypes.Ref newOutline(){return newOutline;}
        List<BiddingTypes.Ref> chapters(){return chapters;} String eventId(){return eventId;} BiddingTypes.Ref oldOutline(){return oldOutline;} String chapterBody(){return chapterBody;}
        BiddingTypes.Command command(JsonNode impact,String operation) throws Exception {
            ObjectNode event=json.createObjectNode().put("eventId",eventId);event.set("changedRef",json.valueToTree(oldBaseline));
            return command(event,impact,operation);
        }
        BiddingTypes.Command command(JsonNode event,JsonNode impact,String operation) throws Exception {
            var unchanged=new java.util.ArrayList<BiddingTypes.Ref>();for(JsonNode n:impact.path("unaffectedRefs"))unchanged.add(json.treeToValue(n,BiddingTypes.Ref.class));
            var p=json.createObjectNode().put("eventId",event.path("eventId").asText());p.set("changedRef",event.path("changedRef").deepCopy());p.set("unchangedRefs",json.valueToTree(unchanged));p.putArray("resolutions");
            var project=repository.findProject(scope.workspaceId(),scope.projectId());
            return new BiddingTypes.Command(operation,ref(project),"CONFIRM_CHANGE_IMPACT",p);
        }
    }
    private List<BiddingTypes.Ref> refs(JsonNode values) throws Exception { List<BiddingTypes.Ref> out=new java.util.ArrayList<>();for(JsonNode n:values)out.add(json.treeToValue(n,BiddingTypes.Ref.class));return out; }
    private BaselineFixture baselineTransition(boolean changeTechnical) throws Exception {
        return baselineTransition(changeTechnical,false,false);
    }
    private BaselineFixture baselineTransition(boolean changeTechnical,boolean changeSource,boolean wrongQuote) throws Exception {
        var p=project();String projectId=p.path("id").asText(),actor=p.path("ownerId").asText();var scope=new BiddingTypes.Scope(workspace,actor,projectId);Timestamp now=Timestamp.from(Instant.now());
        BiddingTypes.Ref source=new BiddingTypes.Ref("source","source-proof",1,"source-proof-"+UUID.randomUUID());
        repository.insertSource(UUID.randomUUID().toString(),workspace,projectId,source.id(),source.version(),"TENDER",source.digest(),"evidence".getBytes(),"tender.pdf",now);
        jdbc.update("UPDATE mate_bidding_source SET read_status='READY',quality='READY',blocks_json=? WHERE workspace_id=? AND project_id=? AND source_id=?",
                "[{\"id\":\"block-1\",\"text\":\"Exact tender evidence\"}]",workspace,projectId,source.id());
        BiddingTypes.Ref replacementSource=source;
        BiddingTypes.Ref replacementSet=new BiddingTypes.Ref("sourceSet","current",1,"set-"+UUID.randomUUID());
        if(changeSource) {
            replacementSource=new BiddingTypes.Ref("source","source-proof",2,"source-proof-v2-"+UUID.randomUUID());
            repository.insertSource(UUID.randomUUID().toString(),workspace,projectId,replacementSource.id(),replacementSource.version(),"TENDER",replacementSource.digest(),"evidence-v2".getBytes(),"tender-v2.pdf",now);
            jdbc.update("UPDATE mate_bidding_source SET read_status='READY',quality='READY',blocks_json=? WHERE workspace_id=? AND project_id=? AND source_id=? AND version=2",
                    "[{\"id\":\"block-2\",\"text\":\""+(wrongQuote?"Different tender evidence":"Exact tender evidence")+"\"}]",workspace,projectId,replacementSource.id());
            replacementSet=new BiddingTypes.Ref("sourceSet","current",2,"set-v2-"+UUID.randomUUID());
        }
        BiddingTypes.Ref sourceSet=new BiddingTypes.Ref("sourceSet","current",1,"set-"+UUID.randomUUID());
        if(!changeSource)replacementSet=sourceSet;
        repository.insertRevision(UUID.randomUUID().toString(),workspace,projectId,"sourceSet","current",1,json.writeValueAsString(Map.of("sourceRefs",List.of(source))),json.writeValueAsString(List.of(source)),"CONFIRMED",sourceSet.digest(),now);
        if(changeSource)repository.insertRevision(UUID.randomUUID().toString(),workspace,projectId,"sourceSet","current",2,json.writeValueAsString(Map.of("sourceRefs",List.of(replacementSource))),json.writeValueAsString(List.of(replacementSource)),"CONFIRMED",replacementSet.digest(),now);
        BiddingTypes.Ref oldBaseline=new BiddingTypes.Ref("analysisBaseline","current",1,"base-old-"+UUID.randomUUID());
        BiddingTypes.Ref newBaseline=new BiddingTypes.Ref("analysisBaseline","current",2,"base-new-"+UUID.randomUUID());
        repository.insertRevision(UUID.randomUUID().toString(),workspace,projectId,"analysisBaseline","current",1,baselinePayload("2026-10-01",false,source),json.writeValueAsString(List.of(sourceSet)),"CONFIRMED",oldBaseline.digest(),now);
        repository.insertRevision(UUID.randomUUID().toString(),workspace,projectId,"analysisBaseline","current",2,baselinePayload("2026-11-01",changeTechnical,replacementSource),json.writeValueAsString(List.of(replacementSet)),"CONFIRMED",newBaseline.digest(),now);
        head(scope,oldBaseline);head(scope,newBaseline);
        BiddingTypes.Ref oldOutline=new BiddingTypes.Ref("outline","current",1,"outline-old-"+UUID.randomUUID());
        BiddingTypes.Ref newOutline=new BiddingTypes.Ref("outline","current",2,"outline-new-"+UUID.randomUUID());
        String outlinePayload=outlinePayload();repository.insertRevision(UUID.randomUUID().toString(),workspace,projectId,"outline","current",1,outlinePayload,json.writeValueAsString(List.of(oldBaseline)),"NEEDS_RECONFIRMATION",oldOutline.digest(),now);
        repository.insertRevision(UUID.randomUUID().toString(),workspace,projectId,"outline","current",2,outlinePayload,json.writeValueAsString(List.of(newBaseline)),"CONFIRMED",newOutline.digest(),now);
        head(scope,oldOutline);head(scope,newOutline);
        List<BiddingTypes.Ref> chapters=new java.util.ArrayList<>();String chapterBody="";
        for(int i=1;i<=2;i++) {
            var chapter=new BiddingTypes.Ref("chapter","chapter-"+i,1,"chapter-"+i+"-"+UUID.randomUUID());chapters.add(chapter);
            ObjectNode body=json.createObjectNode().put("schemaVersion","1");body.putObject("chapter").put("chapterId",chapter.id()).put("title","Chapter "+i);
            body.putArray("responses");body.putArray("citations");body.putArray("missingMaterials");body.putArray("unresolvedItems");body.putObject("_bidding").set("outlineRef",json.valueToTree(oldOutline));
            body.withArray("citations").addObject().put("requirementRef","REQ-"+i).put("sourceId",source.id()).put("version",source.version()).put("blockId","block-1").put("quote","Exact tender evidence");
            chapterBody=json.writeValueAsString(body).replaceAll("\\\\s+","");
            repository.insertRevision(UUID.randomUUID().toString(),workspace,projectId,"chapter",chapter.id(),1,body.toString(),json.writeValueAsString(List.of(oldOutline)),"SELECTED",chapter.digest(),now);head(scope,chapter);
        }
        chapterBody=json.readTree(repository.rawRevisionPayload(scope,chapters.get(1))).path("chapter").toString();
        var oldSource=new BiddingTypes.Ref("analysisBaseline","current",1,oldBaseline.digest());dependencies.recordTransition(scope,oldSource,newBaseline);
        String eventId=repository.changeEvents(scope).stream().filter(e->"analysisBaseline".equals(e.path("changedRef").path("kind").asText())).findFirst().orElseThrow().path("eventId").asText();
        if(changeSource){dependencies.recordTransition(scope,sourceSet,replacementSet);dependencies.recordTransition(scope,source,replacementSource);head(scope,sourceSet);head(scope,replacementSet);repository.invalidateDependencies(scope,source);repository.invalidateDependencies(scope,sourceSet);}
        repository.invalidateDependencies(scope,oldBaseline);
        return new BaselineFixture(scope,oldBaseline,newOutline,List.copyOf(chapters),eventId,oldOutline,chapterBody);
    }
    private String baselinePayload(String deadline,boolean changedTechnical,BiddingTypes.Ref source) {
        ObjectNode root=json.createObjectNode();ObjectNode analyses=root.putObject("analyses");analyses.putObject("bidding-tender-profile").putArray("deadlines").addObject().put("value",deadline);
        var requirements=analyses.putObject("bidding-requirement-analysis").putArray("requirements");
        for(int i=1;i<=2;i++){var req=requirements.addObject().put("id","REQ-"+i).put("category","TECHNICAL").put("text",i==1&&changedTechnical?"New technical threshold":i==1?"Stable technical requirement":"Stable second requirement");req.putArray("evidenceRefs").addObject().put("sourceId",source.id()).put("version",source.version()).put("blockId",source.version()==2?"block-2":"block-1").put("quote","Exact tender evidence");}
        analyses.putObject("bidding-scoring-analysis").putArray("criteria");analyses.putObject("bidding-elimination-analysis").putArray("items");
        return root.toString();
    }
    private String outlinePayload() {
        ObjectNode p=json.createObjectNode().put("schemaVersion","1");var cs=p.putArray("chapters");
        for(int i=1;i<=2;i++){var c=cs.addObject().put("id","chapter-"+i).put("title","Chapter "+i).put("order",i);c.putNull("parentId");c.putArray("requirementRefs").add("REQ-"+i);c.putArray("scoringRefs");c.putArray("mandatoryOutlineRefs");c.putArray("materialRefs");}
        return p.toString();
    }
    private void head(BiddingTypes.Scope s,BiddingTypes.Ref ref) throws Exception {
        jdbc.update("MERGE INTO mate_bidding_head(workspace_id,project_id,kind,object_id,version,selected_ref_json) KEY(workspace_id,project_id,kind,object_id) VALUES(?,?,?,?,?,?)",s.workspaceId(),s.projectId(),ref.kind(),ref.id(),ref.version(),json.writeValueAsString(ref));
    }

    @Test void persistedTransitionsAreVisibleToReadersAndReadsDoNotConfirmThem() throws Exception {
        var project=project(); String id=project.path("id").asText(), actor=project.path("ownerId").asText();
        String sourceId=UUID.randomUUID().toString(), digest="a".repeat(64); var oldRef=new BiddingTypes.Ref("source",sourceId,1,digest);
        repository.insertSource(UUID.randomUUID().toString(),workspace,id,sourceId,1,"TENDER",digest,new byte[]{1},"tender.pdf",Timestamp.from(Instant.now()));
        jdbc.update("UPDATE mate_bidding_source SET read_status='READY',quality='READY' WHERE workspace_id=? AND project_id=? AND source_id=?",workspace,id,sourceId);
        var newRef=new BiddingTypes.Ref("source",sourceId,2,"b".repeat(64));
        var scope=new BiddingTypes.Scope(workspace,actor,id); Timestamp now=Timestamp.from(Instant.now());
        var sourceSet=new BiddingTypes.Ref("sourceSet","current",1,"c".repeat(64));
        repository.insertRevision(UUID.randomUUID().toString(),workspace,id,"sourceSet","current",1,
                json.writeValueAsString(java.util.Map.of("sourceRefs",java.util.List.of(oldRef))),json.writeValueAsString(java.util.List.of(oldRef)),"CONFIRMED",sourceSet.digest(),now);
        var baseline=new BiddingTypes.Ref("analysisBaseline","current",1,"d".repeat(64));
        repository.insertRevision(UUID.randomUUID().toString(),workspace,id,"analysisBaseline","current",1,"{}",json.writeValueAsString(java.util.List.of(sourceSet)),"CONFIRMED",baseline.digest(),now);
        var outline=new BiddingTypes.Ref("outline","current",1,"e".repeat(64));
        repository.insertRevision(UUID.randomUUID().toString(),workspace,id,"outline","current",1,"{}",json.writeValueAsString(java.util.List.of(baseline)),"CONFIRMED",outline.digest(),now);
        var chapter=new BiddingTypes.Ref("chapter","chapter-1",1,"f".repeat(64));
        repository.insertRevision(UUID.randomUUID().toString(),workspace,id,"chapter","chapter-1",1,
                "{\"payload\":{\"chapter\":{\"title\":\"架构设计\"}}}",json.writeValueAsString(java.util.List.of(outline)),"SELECTED",chapter.digest(),now);
        var unrelated=new BiddingTypes.Ref("chapter","unrelated",1,"1".repeat(64));
        repository.insertRevision(UUID.randomUUID().toString(),workspace,id,"chapter","unrelated",1,"{}",
                json.writeValueAsString(java.util.List.of(new BiddingTypes.Ref("source",sourceId,1,"9".repeat(64)))),"SELECTED",unrelated.digest(),now);
        dependencies.recordTransition(scope,oldRef,newRef);
        dependencies.invalidate(scope,oldRef);

        JsonNode impact=api("GET","/projects/"+id+"/change-impact","viewer",workspace,null,200);
        assertEquals(1,impact.path("events").size());
        assertEquals(oldRef,json.treeToValue(impact.path("events").get(0).path("changedRef"),BiddingTypes.Ref.class));
        assertEquals(newRef,json.treeToValue(impact.path("events").get(0).path("replacementRef"),BiddingTypes.Ref.class));
        assertEquals("PENDING",impact.path("events").get(0).path("status").asText());
        assertTrue(impact.path("formalBlocked").asBoolean());
        JsonNode unknown=impact.path("events").get(0).path("impact").path("unknownRefs");
        assertTrue(java.util.stream.StreamSupport.stream(unknown.spliterator(),false).anyMatch(value->chapter.equals(json.convertValue(value,BiddingTypes.Ref.class))));
        assertFalse(java.util.stream.StreamSupport.stream(unknown.spliterator(),false).anyMatch(value->unrelated.equals(json.convertValue(value,BiddingTypes.Ref.class))),"same source id with a different digest is not an exact dependency");
        assertEquals("NEEDS_RECONFIRMATION",repository.businessRevision(scope,chapter).path("status").asText());
        assertTrue(impact.path("events").get(0).path("impact").path("refLabels").toString().contains("架构设计"));
        api("GET","/projects/"+id+"/change-impact","viewer",otherWorkspace,null,403);
        assertEquals("PENDING",repository.changeEvents(new BiddingTypes.Scope(workspace,actor,id)).getFirst().path("status").asText());
    }

    @Test void callerReasonAndAcceptRiskCannotClearAnUnprovenImpact() throws Exception {
        var project=project(); String id=project.path("id").asText(), actor=project.path("ownerId").asText();
        String sourceId=UUID.randomUUID().toString(), digest="2".repeat(64); var oldRef=new BiddingTypes.Ref("source",sourceId,1,digest);
        repository.insertSource(UUID.randomUUID().toString(),workspace,id,sourceId,1,"TENDER",digest,new byte[]{1},"tender.pdf",Timestamp.from(Instant.now()));
        jdbc.update("UPDATE mate_bidding_source SET read_status='READY',quality='READY' WHERE workspace_id=? AND project_id=? AND source_id=?",workspace,id,sourceId);
        var newRef=new BiddingTypes.Ref("source",sourceId,2,"3".repeat(64));
        dependencies.recordTransition(new BiddingTypes.Scope(workspace,actor,id),oldRef,newRef);
        var event=repository.changeEvents(new BiddingTypes.Scope(workspace,actor,id)).getFirst();
        var payload=java.util.Map.of("eventId",event.path("eventId").asText(),"changedRef",oldRef,
                "unchangedRefs",java.util.List.of(oldRef),"resolutions",java.util.List.of(java.util.Map.of(
                        "ref",oldRef,"decision","ACCEPT_RISK","reason","继续使用","evidenceRefs",java.util.List.of())));
        api("POST","/projects/"+id+"/commands","owner",workspace,
                java.util.Map.of("operationId","accept-unproven-impact","expected",ref(project),"action","CONFIRM_CHANGE_IMPACT","payload",payload),422);
        assertEquals("PENDING",repository.changeEvents(new BiddingTypes.Scope(workspace,actor,id)).getFirst().path("status").asText());
    }

    @Test void revokedActorCannotReadImpactButDoesNotPermanentlyBlockAnotherAuthorizedActor() throws Exception {
        var project=project(); String id=project.path("id").asText(), actor=project.path("ownerId").asText();
        String sourceId=UUID.randomUUID().toString(), digest="4".repeat(64); var oldRef=new BiddingTypes.Ref("source",sourceId,1,digest);
        repository.insertSource(UUID.randomUUID().toString(),workspace,id,sourceId,1,"TENDER",digest,new byte[]{1},"tender.pdf",Timestamp.from(Instant.now()));
        jdbc.update("UPDATE mate_bidding_source SET read_status='READY',quality='READY' WHERE workspace_id=? AND project_id=? AND source_id=?",workspace,id,sourceId);
        dependencies.recordTransition(new BiddingTypes.Scope(workspace,actor,id),oldRef,new BiddingTypes.Ref("source",sourceId,2,"5".repeat(64)));
        String username=auth.parseToken(tokens.get("viewer").substring(7));
        jdbc.update("UPDATE mate_workspace_member SET deleted=1 WHERE workspace_id=? AND user_id=(SELECT id FROM mate_user WHERE username=?)",Long.valueOf(workspace),username);
        api("GET","/projects/"+id+"/change-impact","viewer",workspace,null,403);
        assertTrue(api("GET","/projects/"+id+"/change-impact","owner",workspace,null,200).path("formalBlocked").asBoolean());
    }
}

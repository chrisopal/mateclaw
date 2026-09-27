package vip.mate.bidding;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.mockito.Mockito;
import org.springframework.boot.test.mock.mockito.MockBean;

class BiddingWritingTest extends BiddingHttpFixture {
    @Autowired BiddingWritingService writing;
    @Autowired BiddingReviewService reviews;
    @Autowired BiddingTaskService tasks;
    @Autowired BiddingRepository repository;
    @MockBean BiddingEmployeeRuntime runtime;
    @MockBean BiddingDependencies dependencies;

    @BeforeEach void allowCurrentReadRefsByDefault() {
        Mockito.when(dependencies.isCurrentForRead(Mockito.any(),Mockito.anyList())).thenReturn(true);
    }

    @Test void leavesWriteIndependentlyAndLateCandidateCannotReplaceHumanEdit() throws Exception {
        var project=project();String id=project.path("id").asText(),actor=project.path("ownerId").asText();
        var outline=seedConfirmedOutline(id);
        var scope=new BiddingTypes.Scope(workspace,actor,id);
        ObjectNode before=writing.read(scope);var chapters=before.path("chapters");assertEquals(2,chapters.size());
        var firstGuard=json.treeToValue(chapters.get(0).path("editExpectedRef"),BiddingTypes.Ref.class);
        var secondGuard=json.treeToValue(chapters.get(1).path("editExpectedRef"),BiddingTypes.Ref.class);
        BiddingTypes.Ref first=writing.accept(claim(scope,outline,"c1",firstGuard),result("c1"));
        BiddingTypes.Ref second=writing.accept(claim(scope,outline,"c2",secondGuard),result("c2"));
        assertNotNull(first);assertNotNull(second);assertNotEquals(first.id(),second.id());
        var editPayload=json.createObjectNode().put("chapterId","c1");editPayload.putArray("blocks").addObject().put("type","paragraph").put("text","Human edit");
        editPayload.putArray("responses");editPayload.putArray("citations");editPayload.putArray("missingMaterials");editPayload.putArray("unresolvedItems");
        var edited=writing.edit(scope,new BiddingTypes.Command("human-edit-c1",firstGuard,"EDIT_CHAPTER",editPayload));
        var humanRef=json.treeToValue(edited.path("ref"),BiddingTypes.Ref.class);
        BiddingApiException late=assertThrows(BiddingApiException.class,()->writing.accept(claim(scope,outline,"c1",firstGuard),result("c1")));
        assertEquals("CHAPTER_STALE",late.code());
        ObjectNode adoptPayload=json.createObjectNode().put("chapterId","c1");adoptPayload.set("candidateRef",json.valueToTree(first));
        var retryAdopt=new BiddingTypes.Command("adopt-late-candidate",humanRef,"ADOPT_CHAPTER",adoptPayload);
        BiddingApiException stale=assertThrows(BiddingApiException.class,()->writing.adopt(scope,retryAdopt));
        assertEquals(409,stale.status());
        assertEquals(humanRef,json.treeToValue(writing.read(scope).path("chapters").get(0).path("editExpectedRef"),BiddingTypes.Ref.class));
    }

    @Test void persistedRewriteAdoptionReadbackAssemblyAndReplayKeepHumanDraft() throws Exception {
        var project=project();String projectId=project.path("id").asText(),actor=project.path("ownerId").asText();
        var outline=seedConfirmedOutline(projectId);var scope=new BiddingTypes.Scope(workspace,actor,projectId);
        Mockito.doNothing().when(dependencies).validate(Mockito.eq(scope),Mockito.anyList());
        Mockito.doAnswer(invocation->{
            List<BiddingTypes.Ref> checked=invocation.getArgument(1);
            if(checked==null||checked.isEmpty())throw BiddingAccess.error(422,"SOURCE_SET_INCOMPLETE","Fixed references are required");
            return null;
        }).when(dependencies).validateForComparisonRead(Mockito.eq(scope),Mockito.any());
        Mockito.when(dependencies.isCurrent(Mockito.eq(scope),Mockito.anyList())).thenReturn(true);
        Mockito.when(dependencies.isCurrentForRead(Mockito.eq(scope),Mockito.anyList())).thenReturn(true);
        Mockito.doNothing().when(employees).validate(Mockito.eq(scope),Mockito.anyString(),Mockito.anyString());
        Mockito.when(employees.modelConfigId(Mockito.eq(scope),Mockito.anyString())).thenReturn("model-config");
        bindWritingSkill(scope,project);

        ObjectNode dispatch=json.createObjectNode().set("outlineRef",json.valueToTree(outline));dispatch.putArray("chapterIds").add("c1").add("c2");dispatch.putArray("materialRefs");
        var queued=writing.dispatch(scope,new BiddingTypes.Command("write-two-chapters",outline,"DISPATCH_WRITING",dispatch));
        assertEquals(2,queued.path("tasks").size());
        Map<String,BiddingTypes.Ref> firstCandidates=new HashMap<>();Map<String,String> taskIds=new HashMap<>();
        for(int i=0;i<2;i++){var claim=repository.claimDue(Instant.now(),"writing-persisted-test",1).getFirst();String chapter=claim.input().path("chapterId").asText();taskIds.put(chapter,claim.taskId());tasks.complete(claim,execution(claim,chapter,"Initial "+chapter));assertEquals("SUCCEEDED",jdbc.queryForObject("SELECT status FROM mate_bidding_task WHERE id=?",String.class,claim.taskId()));}
        var firstRead=writing.read(scope);
        for(JsonNode item:firstRead.path("chapters")){String chapter=item.path("chapterId").asText();assertEquals(taskIds.get(chapter),item.path("tasks").get(0).path("taskId").asText());assertEquals("CANDIDATE",item.path("candidates").get(0).path("status").asText());firstCandidates.put(chapter,json.treeToValue(item.path("candidates").get(0).path("ref"),BiddingTypes.Ref.class));}
        for(String chapter:List.of("c1","c2")){var item=find(firstRead.path("chapters"),chapter);var guard=json.treeToValue(item.path("editExpectedRef"),BiddingTypes.Ref.class);var payload=json.createObjectNode().put("chapterId",chapter);payload.set("candidateRef",json.valueToTree(firstCandidates.get(chapter)));var adopted=writing.adopt(scope,new BiddingTypes.Command("adopt-first-"+chapter,guard,"ADOPT_CHAPTER",payload));assertEquals(firstCandidates.get(chapter),json.treeToValue(adopted.path("ref"),BiddingTypes.Ref.class));}

        BiddingTypes.Ref previous=json.treeToValue(writing.read(scope).path("chapters").get(0).path("selected").path("ref"),BiddingTypes.Ref.class);
        ObjectNode rewrite=json.createObjectNode().set("outlineRef",json.valueToTree(outline));rewrite.putArray("chapterIds").add("c1");rewrite.putArray("materialRefs");
        writing.dispatch(scope,new BiddingTypes.Command("rewrite-c1",outline,"DISPATCH_WRITING",rewrite));
        var rewriteClaim=repository.claimDue(Instant.now(),"writing-rewrite-test",1).getFirst();assertEquals("c1",rewriteClaim.input().path("chapterId").asText());
        assertEquals(previous,json.treeToValue(rewriteClaim.input().path("previousChapterRef"),BiddingTypes.Ref.class));
        assertFalse(rewriteClaim.inputRefs().contains(previous),"prior chapter is provenance, not a current dependency");
        tasks.complete(rewriteClaim,execution(rewriteClaim,"c1","Rewritten c1"));
        assertEquals("SUCCEEDED",jdbc.queryForObject("SELECT status FROM mate_bidding_task WHERE id=?",String.class,rewriteClaim.taskId()));
        var afterRewrite=writing.read(scope);JsonNode rewriteCandidate=find(afterRewrite.path("chapters"),"c1").path("candidates").get(0);
        assertEquals("CANDIDATE",rewriteCandidate.path("status").asText());
        String candidatePayloadRaw=jdbc.queryForObject("SELECT payload_json FROM mate_bidding_revision WHERE workspace_id=? AND project_id=? AND kind='chapter' AND object_id='c1' AND version=?",String.class,workspace,projectId,rewriteCandidate.path("ref").path("version").asLong());
        assertEquals(previous,json.treeToValue(json.readTree(candidatePayloadRaw).path("_bidding").path("previousChapterRef"),BiddingTypes.Ref.class));
        assertEquals(previous,json.treeToValue(rewriteCandidate.path("headGuard"),BiddingTypes.Ref.class));
        BiddingTypes.Ref next=json.treeToValue(rewriteCandidate.path("ref"),BiddingTypes.Ref.class);
        var currentGuard=json.treeToValue(find(afterRewrite.path("chapters"),"c1").path("editExpectedRef"),BiddingTypes.Ref.class);
        ObjectNode adoptPayload=json.createObjectNode().put("chapterId","c1");adoptPayload.set("candidateRef",json.valueToTree(next));
        var adoptCommand=new BiddingTypes.Command("adopt-rewrite-c1",currentGuard,"ADOPT_CHAPTER",adoptPayload);
        ObjectNode lateDispatch=json.createObjectNode().set("outlineRef",json.valueToTree(outline));lateDispatch.putArray("chapterIds").add("c1");lateDispatch.putArray("materialRefs");
        writing.dispatch(scope,new BiddingTypes.Command("late-write-c1",outline,"DISPATCH_WRITING",lateDispatch));
        var lateClaim=repository.claimDue(Instant.now(),"writing-late-test",1).getFirst();assertEquals(previous,json.treeToValue(lateClaim.input().path("_biddingHeadGuard"),BiddingTypes.Ref.class));
        var adopted=writing.adopt(scope,adoptCommand);assertEquals(next,json.treeToValue(adopted.path("ref"),BiddingTypes.Ref.class));
        tasks.complete(lateClaim,execution(lateClaim,"c1","Late stale write"));
        assertEquals("STALE",jdbc.queryForObject("SELECT status FROM mate_bidding_task WHERE id=?",String.class,lateClaim.taskId()));
        assertEquals("STALE",jdbc.queryForObject("SELECT state FROM mate_bidding_attempt WHERE id=?",String.class,lateClaim.attemptId()));
        assertEquals(adopted,writing.adopt(scope,adoptCommand));
        var readBack=writing.read(scope);assertEquals(next,json.treeToValue(find(readBack.path("chapters"),"c1").path("selected").path("ref"),BiddingTypes.Ref.class));
        assertTrue(find(readBack.path("chapters"),"c1").path("candidates").isEmpty(),"adopted candidate moves out of the candidate list");

        var c2=find(readBack.path("chapters"),"c2");var c2head=json.treeToValue(c2.path("editExpectedRef"),BiddingTypes.Ref.class);
        ObjectNode edit=json.createObjectNode().put("chapterId","c2");edit.putArray("blocks").addObject().put("type","paragraph").put("text","Human authored draft");edit.putArray("responses");edit.putArray("citations");edit.putArray("missingMaterials");edit.putArray("unresolvedItems");
        var human=writing.edit(scope,new BiddingTypes.Command("edit-c2",c2head,"EDIT_CHAPTER",edit));BiddingTypes.Ref humanRef=json.treeToValue(human.path("ref"),BiddingTypes.Ref.class);
        var finalRead=writing.read(scope);var refs=json.createArrayNode();for(String chapter:List.of("c1","c2")){JsonNode item=find(finalRead.path("chapters"),chapter);ObjectNode chosen=refs.addObject().put("chapterId",chapter);chosen.set("ref",item.path("editExpectedRef").deepCopy());}
        ObjectNode assemblePayload=json.createObjectNode().set("outlineRef",json.valueToTree(outline));assemblePayload.set("chapterRefs",refs);
        var assembleCommand=new BiddingTypes.Command("assemble-selected-and-human",outline,"ASSEMBLE_MANUSCRIPT",assemblePayload);
        var assembled=writing.assemble(scope,assembleCommand);assertEquals("DRAFT_PENDING_REVIEW",assembled.path("status").asText());
        var reviewRead=api("GET","/projects/"+projectId+"/review","member",workspace,null,200);
        assertEquals("NOT_DISPATCHED",reviewRead.path("status").asText());
        assertEquals(assembled.path("ref"),reviewRead.path("manuscriptRef"));
        JsonNode manuscript=json.readTree(jdbc.queryForObject("SELECT payload_json FROM mate_bidding_revision WHERE workspace_id=? AND project_id=? AND kind='manuscript' AND version=?",String.class,workspace,projectId,assembled.path("ref").path("version").asLong()));
        assertEquals("Human authored draft",manuscript.path("chapters").get(1).path("chapter").path("blocks").get(0).path("text").asText());
        JsonNode manuscriptView=writing.read(scope).path("manuscript");
        assertTrue(manuscriptView.isObject(),"A persisted manuscript must be returned by the public writing read model");
        assertEquals(assembled.path("ref"),manuscriptView.path("ref"));
        assertEquals(manuscript,manuscriptView.path("payload"));
        assertEquals(3,manuscriptView.path("inputRefs").size());
        assertEquals(assembled,writing.assemble(scope,assembleCommand));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM mate_bidding_revision WHERE workspace_id=? AND project_id=? AND kind='manuscript'",Integer.class,workspace,projectId));
        assertEquals(humanRef,json.treeToValue(find(writing.read(scope).path("chapters"),"c2").path("selected").path("ref"),BiddingTypes.Ref.class));
        List<BiddingTypes.Ref> manuscriptInputs=json.convertValue(manuscriptView.path("inputRefs"),new com.fasterxml.jackson.core.type.TypeReference<List<BiddingTypes.Ref>>(){});
        Mockito.doThrow(BiddingAccess.error(403,"FORBIDDEN","A manuscript source is no longer readable"))
                .when(dependencies).validateForComparisonRead(scope,manuscriptInputs);
        assertFalse(writing.read(scope).has("manuscript"),"Revoked source access must hide the assembled manuscript");
    }

    @Test void adoptingRevisionAndReassemblingSchedulesReviewForNewManuscript() throws Exception {
        var project=project();String projectId=project.path("id").asText(),actor=project.path("ownerId").asText();
        var outline=seedConfirmedOutline(projectId);var scope=new BiddingTypes.Scope(workspace,actor,projectId);
        Mockito.doNothing().when(dependencies).validate(Mockito.eq(scope),Mockito.anyList());
        Mockito.doNothing().when(employees).validate(Mockito.eq(scope),Mockito.anyString(),Mockito.anyString());
        Mockito.when(employees.modelConfigId(Mockito.eq(scope),Mockito.anyString())).thenReturn("model-config");
        bindWritingAndReviewSkills(scope,project);

        ObjectNode read=writing.read(scope);Map<String,BiddingTypes.Ref> selected=new HashMap<>();
        for(String chapter:List.of("c1","c2")) {
            BiddingTypes.Ref guard=json.treeToValue(find(read.path("chapters"),chapter).path("editExpectedRef"),BiddingTypes.Ref.class);
            BiddingTypes.Ref candidate=writing.accept(claim(scope,outline,chapter,guard),result(chapter,"Initial "+chapter));
            ObjectNode adopt=json.createObjectNode().put("chapterId",chapter);adopt.set("candidateRef",json.valueToTree(candidate));
            writing.adopt(scope,new BiddingTypes.Command("adopt-initial-"+chapter,guard,"ADOPT_CHAPTER",adopt));selected.put(chapter,candidate);
        }
        BiddingTypes.Ref firstManuscript=assembleSelected(scope,outline,selected,"assemble-first");
        int initiallyQueued=jdbc.queryForObject("SELECT COUNT(*) FROM mate_bidding_task WHERE workspace_id=? AND project_id=? AND agent_id='920102' AND status='QUEUED'",Integer.class,workspace,projectId);
        assertEquals(3,initiallyQueued,"review readback="+reviews.read(scope));
        completeAutoReviewTasks(scope,firstManuscript,3);
        assertDoesNotThrow(()->reviews.requireReviewed(scope,firstManuscript));

        BiddingTypes.Ref chapterHead=selected.get("c1");ObjectNode rewrite=json.createObjectNode().set("outlineRef",json.valueToTree(outline));
        rewrite.putArray("chapterIds").add("c1");rewrite.putArray("materialRefs");
        writing.dispatch(scope,new BiddingTypes.Command("rewrite-after-review",outline,"DISPATCH_WRITING",rewrite));
        BiddingTypes.Claim rewriteClaim=repository.claimDue(Instant.now(),"review-rewrite",1).getFirst();
        tasks.complete(rewriteClaim,execution(rewriteClaim,"c1","Revised c1"));
        JsonNode candidate=find(writing.read(scope).path("chapters"),"c1").path("candidates").get(0);
        BiddingTypes.Ref revisedChapter=json.treeToValue(candidate.path("ref"),BiddingTypes.Ref.class);
        ObjectNode adopt=json.createObjectNode().put("chapterId","c1");adopt.set("candidateRef",json.valueToTree(revisedChapter));
        writing.adopt(scope,new BiddingTypes.Command("adopt-reviewed-revision",chapterHead,"ADOPT_CHAPTER",adopt));selected.put("c1",revisedChapter);

        BiddingTypes.Ref secondManuscript=assembleSelected(scope,outline,selected,"assemble-second");
        assertNotEquals(firstManuscript,secondManuscript);
        BiddingApiException stale=assertThrows(BiddingApiException.class,()->reviews.requireReviewed(scope,firstManuscript));
        assertEquals("REVIEW_STALE",stale.code());
        BiddingApiException incomplete=assertThrows(BiddingApiException.class,()->reviews.requireReviewed(scope,secondManuscript));
        assertEquals("REVIEW_INCOMPLETE",incomplete.code());
        JsonNode current=reviews.read(scope);assertEquals("IN_PROGRESS",current.path("status").asText());
        assertEquals(secondManuscript,json.treeToValue(current.path("manuscriptRef"),BiddingTypes.Ref.class));
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM mate_bidding_task WHERE workspace_id=? AND project_id=? AND agent_id='920102' AND status='QUEUED'",Integer.class,workspace,projectId));
        List<JsonNode> latestInputs=jdbc.query("SELECT input_json FROM mate_bidding_task WHERE workspace_id=? AND project_id=? AND agent_id='920102' AND status='QUEUED'",(rs,n)->{try{return json.readTree(rs.getString(1));}catch(Exception e){throw new IllegalStateException(e);}},workspace,projectId);
        assertEquals(3,latestInputs.size());assertTrue(latestInputs.stream().allMatch(input->secondManuscript.equals(json.convertValue(input.path("input").path("manuscriptRef"),BiddingTypes.Ref.class))));
        completeAutoReviewTasks(scope,secondManuscript,3);
    }

    @Test void automaticReviewDispatchFailureLeavesDraftAndExposesSafeReason() throws Exception {
        var project=project();String projectId=project.path("id").asText(),actor=project.path("ownerId").asText();
        var outline=seedConfirmedOutline(projectId);var scope=new BiddingTypes.Scope(workspace,actor,projectId);
        Mockito.doNothing().when(dependencies).validate(Mockito.eq(scope),Mockito.anyList());
        Mockito.doNothing().when(employees).validate(Mockito.eq(scope),Mockito.anyString(),Mockito.anyString());
        Mockito.when(employees.modelConfigId(Mockito.eq(scope),Mockito.anyString())).thenReturn("model-config");bindWritingAndReviewSkills(scope,project);
        String baselineRaw=jdbc.queryForObject("SELECT payload_json FROM mate_bidding_revision WHERE workspace_id=? AND project_id=? AND kind='analysisBaseline' AND object_id='current'",String.class,workspace,projectId);
        ObjectNode unavailable=json.readValue(baselineRaw,ObjectNode.class);unavailable.remove("sourceSetRef");
        jdbc.update("UPDATE mate_bidding_revision SET payload_json=? WHERE workspace_id=? AND project_id=? AND kind='analysisBaseline' AND object_id='current'",json.writeValueAsString(unavailable),workspace,projectId);
        Map<String,BiddingTypes.Ref> selected=new HashMap<>();
        JsonNode read=writing.read(scope);
        for(String chapter:List.of("c1","c2")) {
            BiddingTypes.Ref guard=json.treeToValue(find(read.path("chapters"),chapter).path("editExpectedRef"),BiddingTypes.Ref.class);
            BiddingTypes.Ref candidate=writing.accept(claim(scope,outline,chapter,guard),result(chapter,"Draft "+chapter));
            ObjectNode adopt=json.createObjectNode().put("chapterId",chapter);adopt.set("candidateRef",json.valueToTree(candidate));
            writing.adopt(scope,new BiddingTypes.Command("adopt-failure-"+chapter,guard,"ADOPT_CHAPTER",adopt));selected.put(chapter,candidate);
        }
        BiddingTypes.Ref manuscript=assembleSelected(scope,outline,selected,"assemble-review-source-missing");
        assertEquals("DRAFT_PENDING_REVIEW",jdbc.queryForObject("SELECT status FROM mate_bidding_revision WHERE workspace_id=? AND project_id=? AND kind='manuscript' AND object_id='manuscript' AND digest=?",String.class,workspace,projectId,manuscript.digest()));
        JsonNode review=api("GET","/projects/"+projectId+"/review","member",workspace,null,200);
        assertEquals("NOT_DISPATCHED",review.path("status").asText());assertEquals("SOURCE_UNREADABLE",review.path("reason").asText());
        JsonNode failure=json.readTree(jdbc.queryForObject("SELECT payload_json FROM mate_bidding_revision WHERE workspace_id=? AND project_id=? AND kind='reviewDispatch' AND object_id=?",String.class,workspace,projectId,manuscript.digest().substring(0,48)));
        assertEquals("SOURCE_UNREADABLE",failure.path("reasonCode").asText());assertFalse(failure.has("exceptionMessage"));
    }

    @Test void editEnvelopeLimitIncludesLargeEvidenceArrays() throws Exception {
        var project=project();String id=project.path("id").asText(),actor=project.path("ownerId").asText();var outline=seedConfirmedOutline(id);var scope=new BiddingTypes.Scope(workspace,actor,id);
        var guard=json.treeToValue(writing.read(scope).path("chapters").get(0).path("editExpectedRef"),BiddingTypes.Ref.class);
        ObjectNode edit=json.createObjectNode().put("chapterId","c1");edit.putArray("blocks").addObject().put("type","paragraph").put("text","small");edit.putArray("responses");edit.putArray("citations");edit.putArray("missingMaterials");edit.putArray("unresolvedItems").add("x".repeat(2*1024*1024));
        BiddingApiException error=assertThrows(BiddingApiException.class,()->writing.edit(scope,new BiddingTypes.Command("large-edit",guard,"EDIT_CHAPTER",edit)));
        assertEquals("WRITING_OUTPUT_LIMIT",error.code());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM mate_bidding_revision WHERE workspace_id=? AND project_id=? AND kind='chapter'",Integer.class,workspace,id));
    }

    @Test void targetedFindingRevisionCreatesCandidateWithoutChangingSelectedChapter() throws Exception {
        var project=project();String projectId=project.path("id").asText(),actor=project.path("ownerId").asText();
        var outline=seedConfirmedOutline(projectId);var scope=new BiddingTypes.Scope(workspace,actor,projectId);
        Mockito.doNothing().when(dependencies).validate(Mockito.eq(scope),Mockito.anyList());
        Mockito.when(dependencies.isCurrentForRead(Mockito.eq(scope),Mockito.anyList())).thenReturn(true);
        Mockito.doNothing().when(employees).validate(Mockito.eq(scope),Mockito.anyString(),Mockito.anyString());
        Mockito.when(employees.modelConfigId(Mockito.eq(scope),Mockito.anyString())).thenReturn("model-config");bindWritingSkill(scope,project);
        for(String chapter:List.of("c1","c2")) {
            JsonNode item=find(writing.read(scope).path("chapters"),chapter);BiddingTypes.Ref guard=json.treeToValue(item.path("editExpectedRef"),BiddingTypes.Ref.class);
            ObjectNode edit=json.createObjectNode().put("chapterId",chapter);edit.putArray("blocks").addObject().put("type","paragraph").put("text","Initial "+chapter);edit.putArray("responses");edit.putArray("citations");edit.putArray("missingMaterials");edit.putArray("unresolvedItems");
            writing.edit(scope,new BiddingTypes.Command("select-"+chapter,guard,"EDIT_CHAPTER",edit));
        }
        JsonNode read=writing.read(scope);ArrayNode chapterRefs=json.createArrayNode();for(String chapter:List.of("c1","c2")){JsonNode row=find(read.path("chapters"),chapter);chapterRefs.addObject().put("chapterId",chapter).set("ref",row.path("editExpectedRef").deepCopy());}
        ObjectNode assemble=json.createObjectNode().set("outlineRef",json.valueToTree(outline));assemble.set("chapterRefs",chapterRefs);
        BiddingTypes.Ref manuscript=json.treeToValue(writing.assemble(scope,new BiddingTypes.Command("assemble-for-fix",outline,"ASSEMBLE_MANUSCRIPT",assemble)).path("ref"),BiddingTypes.Ref.class);
        BiddingTypes.Ref chapter=json.treeToValue(find(writing.read(scope).path("chapters"),"c1").path("selected").path("ref"),BiddingTypes.Ref.class);
        BiddingTypes.Ref reviewRef=new BiddingTypes.Ref("review","revision-test",1,"e".repeat(64));ObjectNode reviewBody=json.createObjectNode().put("schemaVersion","1");
        ObjectNode meta=reviewBody.putObject("_bidding");meta.set("manuscriptRef",json.valueToTree(manuscript));meta.put("reviewKey","revision-test-key");meta.put("reviewerId","920102");
        ObjectNode finding=reviewBody.putArray("findings").addObject().put("id","F-1").put("severity","MAJOR").put("category","TECHNICAL_GAP");
        finding.set("chapterRefs",json.createArrayNode().add(json.valueToTree(chapter)));finding.putArray("requirementRefs");finding.putArray("evidenceRefs");finding.put("description","补充方案细节").put("recommendation","定向修订章节");
        persist(scope,reviewRef,reviewBody,List.of(outline),"REVIEW_RESULT");
        BiddingTypes.Ref findingRef=new BiddingTypes.Ref("reviewFinding","revision-finding",1,"f".repeat(64));ObjectNode issue=json.createObjectNode().put("findingId","F-1");issue.set("reviewRef",json.valueToTree(reviewRef));issue.set("finding",finding.deepCopy());persist(scope,findingRef,issue,List.of(reviewRef),"OPEN");
        jdbc.update("INSERT INTO mate_bidding_revision(id,workspace_id,project_id,kind,object_id,version,payload_json,input_refs_json,status,digest,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",UUID.randomUUID().toString(),workspace,projectId,"findingDecision",findingRef.id(),1,"{}",json.writeValueAsString(List.of(findingRef)),"FIX","decision-digest");
        ObjectNode revise=json.createObjectNode().set("manuscriptRef",json.valueToTree(manuscript));revise.set("chapterRef",json.valueToTree(chapter));revise.putArray("selectedFindingRefs").add(json.valueToTree(findingRef));
        ObjectNode queued=writing.revise(scope,new BiddingTypes.Command("revise-finding-F1",chapter,"REVISE_CHAPTER",revise));assertEquals("CANDIDATE_PENDING",queued.path("status").asText());
        assertEquals(chapter,json.treeToValue(find(writing.read(scope).path("chapters"),"c1").path("selected").path("ref"),BiddingTypes.Ref.class));
        var claim=repository.claimDue(Instant.now(),"targeted-revision-test",1).getFirst();assertEquals("c1",claim.input().path("chapterId").asText());assertEquals(1,claim.input().path("selectedFindingRefs").size());
        tasks.complete(claim,execution(claim,"c1","Revision candidate"));
        JsonNode after=writing.read(scope);assertEquals(chapter,json.treeToValue(find(after.path("chapters"),"c1").path("selected").path("ref"),BiddingTypes.Ref.class));assertEquals("CANDIDATE",find(after.path("chapters"),"c1").path("candidates").get(0).path("status").asText());
    }

    private BiddingTypes.Ref assembleSelected(BiddingTypes.Scope scope,BiddingTypes.Ref outline,Map<String,BiddingTypes.Ref> selected,String operation)throws Exception {
        ObjectNode payload=json.createObjectNode().set("outlineRef",json.valueToTree(outline));ArrayNode refs=payload.putArray("chapterRefs");
        for(String chapter:List.of("c1","c2")){ObjectNode item=refs.addObject().put("chapterId",chapter);item.set("ref",json.valueToTree(selected.get(chapter)));}
        ObjectNode assembled=writing.assemble(scope,new BiddingTypes.Command(operation,outline,"ASSEMBLE_MANUSCRIPT",payload));
        return json.treeToValue(assembled.path("ref"),BiddingTypes.Ref.class);
    }

    private void completeAutoReviewTasks(BiddingTypes.Scope scope,BiddingTypes.Ref manuscript,int expected)throws Exception {
        for(int i=0;i<expected;i++) {
            BiddingTypes.Claim claim=repository.claimDue(Instant.now(),"auto-review-test",1).getFirst();
            assertEquals("920102",claim.agentId());assertEquals(manuscript,json.treeToValue(claim.input().path("manuscriptRef"),BiddingTypes.Ref.class));
            ObjectNode output=json.createObjectNode().put("schemaVersion","1");output.putArray("findings");
            ObjectNode coverage=output.putObject("coverage");ArrayNode chapters=coverage.putArray("chapterRefs");ArrayNode requirements=coverage.putArray("requirementRefs");
            for(JsonNode chapter:claim.input().path("chapters"))chapters.add(chapter.path("chapterRef").deepCopy());
            for(JsonNode requirement:claim.input().path("requirements"))requirements.add(requirement.path("id").asText());
            coverage.put("crossChapterReviewed",claim.input().path("_biddingTargetId").asText().endsWith(":cross"));
            output.putArray("limitations");output.putArray("warnings");tasks.complete(claim,new BiddingTypes.Execution(output,null,claim.skill().digest(),claim.configDigest(),null));
        }
        assertEquals(expected,jdbc.queryForObject("SELECT COUNT(*) FROM mate_bidding_revision WHERE workspace_id=? AND project_id=? AND kind='review' AND status='REVIEW_RESULT' AND payload_json LIKE ?",Integer.class,workspace,scope.projectId(),"%"+manuscript.digest()+"%"));
    }

    private void bindWritingAndReviewSkills(BiddingTypes.Scope scope,JsonNode project)throws Exception {
        long writeId=92_100_000L+Math.floorMod(UUID.randomUUID().hashCode(),1_000_000),reviewId=writeId+1;String writeDigest="e".repeat(64),reviewDigest="f".repeat(64),config="a".repeat(64);
        for(var entry:List.of(Map.entry(writeId,"bidding-technical-writing"),Map.entry(reviewId,"bidding-technical-review"))) {
            jdbc.update("INSERT INTO mate_skill(id,name,workspace_id,create_time,update_time) VALUES(?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",entry.getKey(),entry.getValue(),Long.valueOf(workspace));
            grantCurrentSkill(entry.getValue().equals("bidding-technical-writing") ? 920101L : 920102L, entry.getKey());
            String digest=entry.getKey().equals(writeId)?writeDigest:reviewDigest;
            Map<String,String> files=Map.of("SKILL.md","---\nname: "+entry.getValue()+"\n---\nRead only.","input.schema.json","{}","output.schema.json","{}");
            jdbc.update("INSERT INTO mate_bidding_skill_package(id,workspace_id,project_id,skill_id,version,digest,files_json,created_at) VALUES(?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",UUID.randomUUID().toString(),workspace,scope.projectId(),Long.toString(entry.getKey()),"v1",digest,json.writeValueAsString(files));
        }
        ObjectNode stored=(ObjectNode)project.deepCopy();ObjectNode bindings=stored.putObject("bindings");
        bindings.putObject("writer").put("agentId","920101").put("configDigest",config).putArray("skillPins").addObject().put("skillId",Long.toString(writeId)).put("digest",writeDigest);
        bindings.putObject("reviewer").put("agentId","920102").put("configDigest",config).putArray("skillPins").addObject().put("skillId",Long.toString(reviewId)).put("digest",reviewDigest);
        jdbc.update("UPDATE mate_bidding_project SET body_json=? WHERE id=?",json.writeValueAsString(stored),scope.projectId());
    }

    private void bindWritingSkill(BiddingTypes.Scope scope,JsonNode project) throws Exception {
        long skillId=91_000_000L+Math.floorMod(UUID.randomUUID().hashCode(),1_000_000);String skill=Long.toString(skillId),digest="c".repeat(64),config="b".repeat(64),packageId=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO mate_skill(id,name,workspace_id,create_time,update_time) VALUES(?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",skillId,"bidding-technical-writing",Long.valueOf(workspace));
        grantCurrentSkill(920103L,skillId);
        String skillMd="---\nname: bidding-technical-writing\n---\nWrite safely.";
        String files=json.writeValueAsString(Map.of("SKILL.md",skillMd,"input.schema.json","{}","output.schema.json","{}"));
        jdbc.update("INSERT INTO mate_bidding_skill_package(id,workspace_id,project_id,skill_id,version,digest,files_json,created_at) VALUES(?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",packageId,workspace,scope.projectId(),skill,"v1",digest,files);
        var stored=(ObjectNode)project.deepCopy();var writer=stored.putObject("bindings").putObject("writer");writer.put("agentId","920103").put("configDigest",config);writer.putArray("skillPins").addObject().put("skillId",skill).put("digest",digest);
        jdbc.update("UPDATE mate_bidding_project SET body_json=? WHERE id=?",json.writeValueAsString(stored),scope.projectId());
    }

    private void persist(BiddingTypes.Scope scope,BiddingTypes.Ref ref,ObjectNode payload,List<BiddingTypes.Ref> refs,String status)throws Exception {
        jdbc.update("INSERT INTO mate_bidding_revision(id,workspace_id,project_id,kind,object_id,version,payload_json,input_refs_json,status,digest,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",UUID.randomUUID().toString(),scope.workspaceId(),scope.projectId(),ref.kind(),ref.id(),ref.version(),json.writeValueAsString(payload),json.writeValueAsString(refs),status,ref.digest(),Timestamp.from(Instant.now()));
    }

    private BiddingTypes.Execution execution(BiddingTypes.Claim claim,String chapter,String text){return new BiddingTypes.Execution(result(chapter,text),null,claim.skill().digest(),claim.configDigest(),null);}
    private ObjectNode result(String chapter,String text){ObjectNode out=json.createObjectNode().put("schemaVersion","1");out.putObject("chapter").put("chapterId",chapter).putArray("blocks").addObject().put("type","paragraph").put("text",text);out.putArray("responses");out.putArray("citations");out.putArray("missingMaterials");out.putArray("unresolvedItems");out.putArray("warnings");return out;}
    private JsonNode find(JsonNode array,String id){for(JsonNode item:array)if(id.equals(item.path("chapterId").asText()))return item;throw new AssertionError("Missing chapter "+id);}

    private BiddingTypes.Claim claim(BiddingTypes.Scope scope,BiddingTypes.Ref outline,String chapter,BiddingTypes.Ref guard){
        ObjectNode input=json.createObjectNode().set("outlineRef",json.valueToTree(outline));input.put("chapterId",chapter);input.set("_biddingHeadGuard",json.valueToTree(guard));
        return new BiddingTypes.Claim(scope,"task-"+chapter,"attempt-"+chapter,"token-"+chapter,1,1,Instant.now(),"920103",
                new BiddingTypes.SkillPin("bidding-technical-writing","1","digest",Map.of()),"model","config",List.of(outline),input);
    }
    private ObjectNode result(String chapter){ObjectNode out=json.createObjectNode().put("schemaVersion","1");ObjectNode body=out.putObject("chapter").put("chapterId",chapter);body.putArray("blocks").addObject().put("type","paragraph").put("text","Evidence pending");out.putArray("responses");out.putArray("citations");out.putArray("missingMaterials");out.putArray("unresolvedItems");out.putArray("warnings");return out;}
    private BiddingTypes.Ref seedConfirmedOutline(String projectId)throws Exception{
        var scope=new BiddingTypes.Scope(workspace,"1",projectId);
        var sourceSet=new BiddingTypes.Ref("sourceSet","current",1,"set-digest");
        String sourceId="writing-source-"+projectId,sourceDigest="writing-source-digest";BiddingTypes.Ref sourceRef=new BiddingTypes.Ref("source",sourceId,1,sourceDigest);
        jdbc.update("INSERT INTO mate_bidding_source(id,workspace_id,project_id,source_id,version,kind,digest,content,blocks_json,quality,read_token,read_started_at,filename,read_status,problems_json,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(),workspace,projectId,sourceId,1,"TENDER",sourceDigest,"source".getBytes(),"[]","PASS",null,null,"source.txt","READY","[]",Timestamp.from(Instant.now()));
        jdbc.update("INSERT INTO mate_bidding_revision(id,workspace_id,project_id,kind,object_id,version,payload_json,input_refs_json,status,digest,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",UUID.randomUUID().toString(),workspace,projectId,"sourceSet","current",1,"{}",json.writeValueAsString(List.of(sourceRef)),"CONFIRMED",sourceSet.digest(),Timestamp.from(Instant.now()));
        jdbc.update("INSERT INTO mate_bidding_head(workspace_id,project_id,kind,object_id,version,selected_ref_json) VALUES(?,?,?,?,?,?)",workspace,projectId,"sourceSet","current",1,json.writeValueAsString(sourceSet));
        var baseline=new BiddingTypes.Ref("analysisBaseline","current",1,"base-digest");ObjectNode base=json.createObjectNode().put("schemaVersion","1");base.set("sourceSetRef",json.valueToTree(sourceSet));
        jdbc.update("INSERT INTO mate_bidding_revision(id,workspace_id,project_id,kind,object_id,version,payload_json,input_refs_json,status,digest,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",UUID.randomUUID().toString(),workspace,projectId,baseline.kind(),baseline.id(),1,json.writeValueAsString(base),json.writeValueAsString(List.of(sourceSet)),"CONFIRMED",baseline.digest(),Timestamp.from(Instant.now()));
        jdbc.update("INSERT INTO mate_bidding_head(workspace_id,project_id,kind,object_id,version,selected_ref_json) VALUES(?,?,?,?,?,?)",workspace,projectId,baseline.kind(),baseline.id(),1,json.writeValueAsString(baseline));
        ObjectNode p=json.createObjectNode().put("schemaVersion","1");var cs=p.putArray("chapters");for(int i=1;i<=2;i++){ObjectNode c=cs.addObject().put("id","c"+i).putNull("parentId").put("order",i).put("title","Chapter "+i);c.putArray("requirementRefs");c.putArray("scoringRefs");c.putArray("materialRefs");c.putArray("mandatoryOutlineRefs");}
        var outline=new BiddingTypes.Ref("outline","current",1,"outline-digest");jdbc.update("INSERT INTO mate_bidding_revision(id,workspace_id,project_id,kind,object_id,version,payload_json,input_refs_json,status,digest,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",UUID.randomUUID().toString(),workspace,projectId,outline.kind(),outline.id(),1,json.writeValueAsString(p),json.writeValueAsString(List.of(baseline)),"CONFIRMED",outline.digest(),Timestamp.from(Instant.now()));
        jdbc.update("INSERT INTO mate_bidding_head(workspace_id,project_id,kind,object_id,version,selected_ref_json) VALUES(?,?,?,?,?,?)",workspace,projectId,outline.kind(),outline.id(),1,json.writeValueAsString(outline));return outline;
    }
}

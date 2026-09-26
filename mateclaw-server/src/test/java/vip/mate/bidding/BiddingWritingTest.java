package vip.mate.bidding;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.mockito.Mockito;
import org.springframework.boot.test.mock.mockito.MockBean;

class BiddingWritingTest extends BiddingHttpFixture {
    @Autowired BiddingWritingService writing;
    @Autowired BiddingTaskService tasks;
    @Autowired BiddingRepository repository;
    @MockBean BiddingEmployeeBindings employees;
    @MockBean BiddingEmployeeRuntime runtime;
    @MockBean BiddingDependencies dependencies;

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
        Mockito.doNothing().when(dependencies).validateForComparisonRead(Mockito.eq(scope),Mockito.anyList());
        Mockito.when(dependencies.isCurrent(Mockito.eq(scope),Mockito.anyList())).thenReturn(true);
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
        JsonNode manuscript=json.readTree(jdbc.queryForObject("SELECT payload_json FROM mate_bidding_revision WHERE workspace_id=? AND project_id=? AND kind='manuscript' AND version=?",String.class,workspace,projectId,assembled.path("ref").path("version").asLong()));
        assertEquals("Human authored draft",manuscript.path("chapters").get(1).path("chapter").path("blocks").get(0).path("text").asText());
        assertEquals(assembled,writing.assemble(scope,assembleCommand));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM mate_bidding_revision WHERE workspace_id=? AND project_id=? AND kind='manuscript'",Integer.class,workspace,projectId));
        assertEquals(humanRef,json.treeToValue(find(writing.read(scope).path("chapters"),"c2").path("selected").path("ref"),BiddingTypes.Ref.class));
    }

    @Test void editEnvelopeLimitIncludesLargeEvidenceArrays() throws Exception {
        var project=project();String id=project.path("id").asText(),actor=project.path("ownerId").asText();var outline=seedConfirmedOutline(id);var scope=new BiddingTypes.Scope(workspace,actor,id);
        var guard=json.treeToValue(writing.read(scope).path("chapters").get(0).path("editExpectedRef"),BiddingTypes.Ref.class);
        ObjectNode edit=json.createObjectNode().put("chapterId","c1");edit.putArray("blocks").addObject().put("type","paragraph").put("text","small");edit.putArray("responses");edit.putArray("citations");edit.putArray("missingMaterials");edit.putArray("unresolvedItems").add("x".repeat(2*1024*1024));
        BiddingApiException error=assertThrows(BiddingApiException.class,()->writing.edit(scope,new BiddingTypes.Command("large-edit",guard,"EDIT_CHAPTER",edit)));
        assertEquals("WRITING_OUTPUT_LIMIT",error.code());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM mate_bidding_revision WHERE workspace_id=? AND project_id=? AND kind='chapter'",Integer.class,workspace,id));
    }

    private void bindWritingSkill(BiddingTypes.Scope scope,JsonNode project) throws Exception {
        long skillId=91_000_000L+Math.floorMod(UUID.randomUUID().hashCode(),1_000_000);String skill=Long.toString(skillId),digest="c".repeat(64),config="b".repeat(64),packageId=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO mate_skill(id,name,workspace_id,create_time,update_time) VALUES(?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",skillId,"bidding-technical-writing",Long.valueOf(workspace));
        String skillMd="---\nname: bidding-technical-writing\n---\nWrite safely.";
        String files=json.writeValueAsString(Map.of("SKILL.md",skillMd,"input.schema.json","{}","output.schema.json","{}"));
        jdbc.update("INSERT INTO mate_bidding_skill_package(id,workspace_id,project_id,skill_id,version,digest,files_json,created_at) VALUES(?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",packageId,workspace,scope.projectId(),skill,"v1",digest,files);
        var stored=(ObjectNode)project.deepCopy();var writer=stored.putObject("bindings").putObject("writer");writer.put("agentId","employee").put("configDigest",config);writer.putArray("skillPins").addObject().put("skillId",skill).put("digest",digest);
        jdbc.update("UPDATE mate_bidding_project SET body_json=? WHERE id=?",json.writeValueAsString(stored),scope.projectId());
    }

    private BiddingTypes.Execution execution(BiddingTypes.Claim claim,String chapter,String text){return new BiddingTypes.Execution(result(chapter,text),null,claim.skill().digest(),claim.configDigest(),null);}
    private ObjectNode result(String chapter,String text){ObjectNode out=json.createObjectNode().put("schemaVersion","1");out.putObject("chapter").put("chapterId",chapter).putArray("blocks").addObject().put("type","paragraph").put("text",text);out.putArray("responses");out.putArray("citations");out.putArray("missingMaterials");out.putArray("unresolvedItems");out.putArray("warnings");return out;}
    private JsonNode find(JsonNode array,String id){for(JsonNode item:array)if(id.equals(item.path("chapterId").asText()))return item;throw new AssertionError("Missing chapter "+id);}

    private BiddingTypes.Claim claim(BiddingTypes.Scope scope,BiddingTypes.Ref outline,String chapter,BiddingTypes.Ref guard){
        ObjectNode input=json.createObjectNode().set("outlineRef",json.valueToTree(outline));input.put("chapterId",chapter);input.set("_biddingHeadGuard",json.valueToTree(guard));
        return new BiddingTypes.Claim(scope,"task-"+chapter,"attempt-"+chapter,"token-"+chapter,1,1,Instant.now(),"agent",
                new BiddingTypes.SkillPin("bidding-technical-writing","1","digest",Map.of()),"model","config",List.of(outline),input);
    }
    private ObjectNode result(String chapter){ObjectNode out=json.createObjectNode().put("schemaVersion","1");ObjectNode body=out.putObject("chapter").put("chapterId",chapter);body.putArray("blocks").addObject().put("type","paragraph").put("text","Evidence pending");out.putArray("responses");out.putArray("citations");out.putArray("missingMaterials");out.putArray("unresolvedItems");out.putArray("warnings");return out;}
    private BiddingTypes.Ref seedConfirmedOutline(String projectId)throws Exception{
        var scope=new BiddingTypes.Scope(workspace,"1",projectId);
        var sourceSet=new BiddingTypes.Ref("sourceSet","current",1,"set-digest");
        jdbc.update("INSERT INTO mate_bidding_revision(id,workspace_id,project_id,kind,object_id,version,payload_json,input_refs_json,status,digest,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",UUID.randomUUID().toString(),workspace,projectId,"sourceSet","current",1,"{}","[]","CONFIRMED",sourceSet.digest(),Timestamp.from(Instant.now()));
        jdbc.update("INSERT INTO mate_bidding_head(workspace_id,project_id,kind,object_id,version,selected_ref_json) VALUES(?,?,?,?,?,?)",workspace,projectId,"sourceSet","current",1,json.writeValueAsString(sourceSet));
        var baseline=new BiddingTypes.Ref("analysisBaseline","current",1,"base-digest");ObjectNode base=json.createObjectNode().put("schemaVersion","1");
        jdbc.update("INSERT INTO mate_bidding_revision(id,workspace_id,project_id,kind,object_id,version,payload_json,input_refs_json,status,digest,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",UUID.randomUUID().toString(),workspace,projectId,baseline.kind(),baseline.id(),1,json.writeValueAsString(base),json.writeValueAsString(List.of(sourceSet)),"CONFIRMED",baseline.digest(),Timestamp.from(Instant.now()));
        jdbc.update("INSERT INTO mate_bidding_head(workspace_id,project_id,kind,object_id,version,selected_ref_json) VALUES(?,?,?,?,?,?)",workspace,projectId,baseline.kind(),baseline.id(),1,json.writeValueAsString(baseline));
        ObjectNode p=json.createObjectNode().put("schemaVersion","1");var cs=p.putArray("chapters");for(int i=1;i<=2;i++){ObjectNode c=cs.addObject().put("id","c"+i).putNull("parentId").put("order",i).put("title","Chapter "+i);c.putArray("requirementRefs");c.putArray("scoringRefs");c.putArray("materialRefs");c.putArray("mandatoryOutlineRefs");}
        var outline=new BiddingTypes.Ref("outline","current",1,"outline-digest");jdbc.update("INSERT INTO mate_bidding_revision(id,workspace_id,project_id,kind,object_id,version,payload_json,input_refs_json,status,digest,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",UUID.randomUUID().toString(),workspace,projectId,outline.kind(),outline.id(),1,json.writeValueAsString(p),json.writeValueAsString(List.of(baseline)),"CONFIRMED",outline.digest(),Timestamp.from(Instant.now()));
        jdbc.update("INSERT INTO mate_bidding_head(workspace_id,project_id,kind,object_id,version,selected_ref_json) VALUES(?,?,?,?,?,?)",workspace,projectId,outline.kind(),outline.id(),1,json.writeValueAsString(outline));return outline;
    }
}

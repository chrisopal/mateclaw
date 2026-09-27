package vip.mate.bidding;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.MockBean;

class BiddingReviewTest extends BiddingHttpFixture {
    @MockBean BiddingEmployeeBindings employees;
    @MockBean BiddingEmployeeRuntime runtime;
    @MockBean BiddingDependencies dependencies;
    @Autowired BiddingRepository repository;
    @Autowired BiddingReviewService reviews;
    @Autowired BiddingTaskService tasks;

    @Test void unresolvedMandatoryProofAndUnsupportedCommitmentBlockTechnicalApproval() {
        assertTrue(BiddingReviewService.blocksTechnicalApproval("MISSING_MANDATORY_PROOF", "BLOCKER", false));
        assertTrue(BiddingReviewService.blocksTechnicalApproval("UNSUPPORTED_COMMITMENT", "BLOCKER", false));
        assertTrue(BiddingReviewService.blocksTechnicalApproval("UNANSWERED_TECHNICAL_REQUIREMENT", "WARNING", false));
        assertFalse(BiddingReviewService.blocksTechnicalApproval("STYLE_SUGGESTION", "SUGGESTION", false));
        assertFalse(BiddingReviewService.blocksTechnicalApproval("MISSING_MANDATORY_PROOF", "BLOCKER", true));
    }

    @Test void reviewOutputMustCoverExactAssignedChapterAndCannotClaimApproval() throws Exception {
        var validator = new BiddingSkillValidator();
        ObjectNode input = json.readValue("""
            {"schemaVersion":"1","baselineRef":{"kind":"analysisBaseline","id":"b","version":1,"digest":"d"},
             "outlineRef":{"kind":"outline","id":"o","version":1,"digest":"d"},
             "manuscriptRef":{"kind":"manuscript","id":"m","version":1,"digest":"d"},
             "chapters":[{"chapterId":"c1","chapterRef":{"kind":"chapter","id":"c1","version":3,"digest":"chapter-digest"},"content":{}}],
             "requirements":[],"criteria":[],"eliminationItems":[],"evidenceSnapshot":{"blocks":[],"materials":{"items":[]}},
             "_biddingTargetId":"review:key:chapter:c1"}
            """, ObjectNode.class);
        ObjectNode output = json.readValue("""
            {"schemaVersion":"1","findings":[],"coverage":{"chapterRefs":[{"kind":"chapter","id":"c1","version":3,"digest":"chapter-digest"}],
             "requirementRefs":[],"crossChapterReviewed":false},"limitations":[],"warnings":[]}
            """, ObjectNode.class);
        assertDoesNotThrow(() -> validator.validateReview(output, input));

        ObjectNode stale = output.deepCopy();
        ((ObjectNode) stale.path("coverage").path("chapterRefs").get(0)).put("version", 2);
        assertThrows(BiddingApiException.class, () -> validator.validateReview(stale, input));
        ObjectNode incomplete = output.deepCopy(); incomplete.with("coverage").putArray("chapterRefs");
        assertThrows(BiddingApiException.class, () -> validator.validateReview(incomplete, input));

        ObjectNode modelApproval = output.deepCopy(); modelApproval.put("approved", true);
        assertThrows(BiddingApiException.class, () -> validator.validateReview(modelApproval, input));
    }

    @Test void reviewReadModelIsScopedAndReportsUnassembledProject() throws Exception {
        var project = project();
        String id = project.path("id").asText();

        var read = api("GET", "/projects/" + id + "/review", "viewer", workspace, null, 200);
        assertEquals("NOT_ASSEMBLED", read.path("status").asText());
        assertTrue(read.path("findings").isArray());

        api("GET", "/projects/" + id + "/review", "viewer", otherWorkspace, null, 403);
    }

    @Test void mandatoryFindingCannotBeDismissedWithoutEvidence() throws Exception {
        JsonNode project=project();String id=project.path("id").asText();BiddingTypes.Scope scope=new BiddingTypes.Scope(workspace,project.path("ownerId").asText(),id);
        ObjectNode finding=json.createObjectNode().put("id","F-1").put("severity","BLOCKER").put("category","MISSING_MANDATORY_PROOF");
        finding.putArray("chapterRefs");finding.putArray("requirementRefs");finding.putArray("evidenceRefs");
        ObjectNode saved=json.createObjectNode().put("findingId","F-1");saved.set("finding",finding);
        BiddingTypes.Ref findingRef=save(scope,new BiddingTypes.Ref("reviewFinding","finding-1",1,"finding-digest"),saved,List.of(),"OPEN");
        ObjectNode payload=json.createObjectNode().set("findingRef",json.valueToTree(findingRef));payload.put("decision","DISMISS_WITH_EVIDENCE").put("reason","accepted").putArray("evidenceRefs");
        BiddingApiException rejected=assertThrows(BiddingApiException.class,()->reviews.resolve(scope,new BiddingTypes.Command("dismiss-without-proof",findingRef,"RESOLVE_FINDING",payload)));
        assertEquals("FINDING_EVIDENCE_REQUIRED",rejected.code());
    }

    @Test void dispatchPersistsChapterAndCrossChapterTasksAgainstOneExactManuscript() throws Exception {
        JsonNode project = project(); String projectId = project.path("id").asText();
        BiddingTypes.Scope scope = new BiddingTypes.Scope(workspace, project.path("ownerId").asText(), projectId);
        String reviewer = "920001", writer = "920002", configDigest = "b".repeat(64), skillDigest = "c".repeat(64);
        long skillNumericId = 92_000_001L;
        jdbc.update("INSERT INTO mate_skill(id,name,workspace_id,create_time,update_time) VALUES(?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", skillNumericId, "bidding-technical-review", Long.valueOf(workspace));
        Map<String,String> files = Map.of("SKILL.md", "---\nname: bidding-technical-review\n---\nRead only.", "input.schema.json", "{}", "output.schema.json", "{}");
        String packageId=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO mate_bidding_skill_package(id,workspace_id,project_id,skill_id,version,digest,files_json,created_at) VALUES(?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",packageId,workspace,projectId,Long.toString(skillNumericId),"v1",skillDigest,json.writeValueAsString(files));
        ObjectNode stored = repository.findProject(workspace, projectId);
        ObjectNode bindings=(ObjectNode)stored.path("bindings"); ObjectNode reviewerBinding=bindings.putObject("reviewer");
        reviewerBinding.put("agentId",reviewer).put("configDigest",configDigest).putArray("skillPins").addObject().put("skillId",Long.toString(skillNumericId)).put("digest",skillDigest);
        bindings.putObject("writer").put("agentId",writer).put("configDigest","d".repeat(64)).putArray("skillPins");
        jdbc.update("UPDATE mate_bidding_project SET body_json=? WHERE id=?",json.writeValueAsString(stored),projectId);
        Mockito.doNothing().when(employees).validate(Mockito.eq(scope),Mockito.anyString(),Mockito.anyString());
        Mockito.when(employees.modelConfigId(Mockito.eq(scope),Mockito.anyString())).thenReturn("review-model");
        Mockito.doNothing().when(dependencies).validate(Mockito.eq(scope),Mockito.anyList());

        BiddingTypes.Ref sourceSet=save(scope,new BiddingTypes.Ref("sourceSet","review-source-set",1,"source-set-digest"),json.createObjectNode(),List.of(),"CONFIRMED");
        ObjectNode baselineBody=json.createObjectNode().put("schemaVersion","1");baselineBody.put("sourceSetRef",json.writeValueAsString(sourceSet));
        baselineBody.set("sourceSetRef",json.valueToTree(sourceSet));
        ObjectNode analyses=baselineBody.putObject("analyses");
        ArrayNode requirements=analyses.putObject("bidding-requirement-analysis").putArray("requirements");
        requirements.addObject().put("id","REQ-1").put("category","TECHNICAL").put("text","接口响应时间不超过2秒");
        requirements.addObject().put("id","COMM-1").put("category","COMMERCIAL").put("text","项目商务负责人需确认报价口径");
        analyses.putObject("bidding-scoring-analysis").putArray("criteria"); analyses.putObject("bidding-elimination-analysis").putArray("items");
        BiddingTypes.Ref baseline=save(scope,new BiddingTypes.Ref("analysisBaseline","review-baseline",1,"baseline-digest"),baselineBody,List.of(sourceSet),"CONFIRMED");
        ObjectNode outlineBody=json.createObjectNode().put("schemaVersion","1");outlineBody.putArray("chapters").addObject().put("id","c1").putArray("requirementRefs").add("REQ-1");
        BiddingTypes.Ref outline=save(scope,new BiddingTypes.Ref("outline","review-outline",1,"outline-digest"),outlineBody,List.of(baseline),"CONFIRMED");
        ObjectNode chapterBody=json.createObjectNode().put("chapterId","c1");chapterBody.putArray("blocks").addObject().put("type","paragraph").put("text","接口响应时间不超过2秒");
        BiddingTypes.Ref chapter=save(scope,new BiddingTypes.Ref("chapter","c1",1,"chapter-digest"),chapterBody,List.of(outline),"CONFIRMED");
        ObjectNode manuscriptBody=json.createObjectNode().put("schemaVersion","1");manuscriptBody.set("outlineRef",json.valueToTree(outline));
        manuscriptBody.putArray("chapters").addObject().put("chapterId","c1").set("chapter",chapterBody);
        BiddingTypes.Ref manuscript=save(scope,new BiddingTypes.Ref("manuscript","manuscript",1,"manuscript-digest"),manuscriptBody,List.of(outline,chapter),"DRAFT_PENDING_REVIEW");

        ObjectNode payload=json.createObjectNode().set("manuscriptRef",json.valueToTree(manuscript));
        ObjectNode dispatched=reviews.dispatch(scope,new BiddingTypes.Command("manual-review",manuscript,"DISPATCH_REVIEW",payload));
        assertEquals("QUEUED",dispatched.path("status").asText()); assertEquals(2,dispatched.path("tasks").size());
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM mate_bidding_task WHERE workspace_id=? AND project_id=? AND agent_id=? AND status='QUEUED'",Integer.class,workspace,projectId,reviewer));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM mate_bidding_revision WHERE workspace_id=? AND project_id=? AND kind='HUMAN_TODO' AND status='OPEN'",Integer.class,workspace,projectId));
        JsonNode todo=json.readTree(jdbc.queryForObject("SELECT payload_json FROM mate_bidding_revision WHERE workspace_id=? AND project_id=? AND kind='HUMAN_TODO'",String.class,workspace,projectId));
        assertEquals(scope.actorId(),todo.path("ownerId").asText());assertFalse(todo.path("affectsTechnical").asBoolean());
        var snapshots=jdbc.query("SELECT input_json FROM mate_bidding_task WHERE workspace_id=? AND project_id=? AND agent_id=?",(rs,n)->rs.getString(1),workspace,projectId,reviewer).stream().map(raw->{try{return json.readTree(raw);}catch(Exception e){throw new IllegalStateException(e);}}).toList();
        assertEquals(2,snapshots.size());
        assertTrue(snapshots.stream().allMatch(s->manuscript.equals(json.convertValue(s.path("input").path("manuscriptRef"),BiddingTypes.Ref.class))));
        assertTrue(snapshots.stream().anyMatch(s->s.path("_bidding").path("targetId").asText().endsWith(":cross")));
        assertTrue(snapshots.stream().anyMatch(s->s.path("_bidding").path("targetId").asText().contains(":chapter:c1")));
        for (int i=0;i<2;i++) {
            BiddingTypes.Claim claim=repository.claimDue(Instant.now(),"reviewer-test",1).getFirst();
            ObjectNode output=json.createObjectNode().put("schemaVersion","1");ArrayNode findingRows=output.putArray("findings");
            ObjectNode coverage=output.putObject("coverage");ArrayNode chapterCoverage=coverage.putArray("chapterRefs");ArrayNode requirementCoverage=coverage.putArray("requirementRefs");
            for(JsonNode assigned:claim.input().path("chapters"))chapterCoverage.add(assigned.path("chapterRef").deepCopy());
            for(JsonNode assigned:claim.input().path("requirements"))requirementCoverage.add(assigned.path("id").asText());
            if(claim.input().path("_biddingTargetId").asText().contains(":chapter:")) {
                ObjectNode finding=findingRows.addObject().put("id","F-1").put("severity","MAJOR").put("category","TECHNICAL_GAP");
                finding.set("chapterRefs",chapterCoverage.deepCopy());finding.set("requirementRefs",json.createArrayNode().add("REQ-1"));finding.putArray("evidenceRefs");finding.put("description","需补充技术响应细节").put("recommendation","定向补写技术方案");
            }
            coverage.put("crossChapterReviewed",claim.input().path("_biddingTargetId").asText().endsWith(":cross"));output.putArray("limitations");output.putArray("warnings");
            tasks.complete(claim,new BiddingTypes.Execution(output,null,claim.skill().digest(),claim.configDigest(),null));
        }
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM mate_bidding_revision WHERE workspace_id=? AND project_id=? AND kind='review' AND status='REVIEW_RESULT'",Integer.class,workspace,projectId));
        JsonNode review=reviews.read(scope);assertEquals("REVIEWED",review.path("status").asText());assertEquals(2,review.path("tasks").size());
        assertEquals(1,review.path("humanTodos").size());assertEquals(scope.actorId(),review.path("humanTodos").get(0).path("ownerId").asText());
        JsonNode publicReview=api("GET","/projects/"+projectId+"/review","member",workspace,null,200);
        assertEquals("REVIEWED",publicReview.path("status").asText());assertEquals(2,publicReview.path("tasks").size());
        JsonNode reviewFinding=review.path("findings").get(0);BiddingTypes.Ref findingRef=json.treeToValue(reviewFinding.path("findingRef"),BiddingTypes.Ref.class);
        ObjectNode fix=json.createObjectNode().set("findingRef",json.valueToTree(findingRef));fix.put("decision","FIX").put("reason","需要定向修订").putArray("evidenceRefs");
        reviews.resolve(scope,new BiddingTypes.Command("fix-finding",findingRef,"RESOLVE_FINDING",fix));
        assertEquals(1,reviews.selectedFindings(scope,"c1",json.createArrayNode().add(json.valueToTree(findingRef)),manuscript).size());
        assertDoesNotThrow(()->reviews.requireReviewed(scope,manuscript));
        ObjectNode gatingTodo=json.createObjectNode().put("todoId","COMM-TECH").put("ownerId",scope.actorId()).put("status","OPEN").put("affectsTechnical",true);
        save(scope,new BiddingTypes.Ref("HUMAN_TODO","COMM-TECH",1,"todo-digest"),gatingTodo,List.of(),"OPEN");
        BiddingApiException todoGate=assertThrows(BiddingApiException.class,()->reviews.requireReviewed(scope,manuscript));assertEquals("HUMAN_TODO_BLOCKS_TECHNICAL_APPROVAL",todoGate.code());
        BiddingTypes.Ref revised=save(scope,new BiddingTypes.Ref("manuscript","manuscript",2,"new-manuscript-digest"),manuscriptBody,List.of(outline,chapter),"DRAFT_PENDING_REVIEW");
        BiddingApiException staleReview=assertThrows(BiddingApiException.class,()->reviews.requireReviewed(scope,revised));
        assertEquals("REVIEW_INCOMPLETE",staleReview.code());
        ObjectNode latest=repository.findProject(workspace,projectId);((ObjectNode)latest.path("bindings").path("reviewer")).put("agentId",writer);
        jdbc.update("UPDATE mate_bidding_project SET body_json=? WHERE id=?",json.writeValueAsString(latest),projectId);
        ObjectNode sameEmployeePayload=json.createObjectNode().set("manuscriptRef",json.valueToTree(revised));
        BiddingApiException sameEmployee=assertThrows(BiddingApiException.class,()->reviews.dispatch(scope,new BiddingTypes.Command("same-employee",revised,"DISPATCH_REVIEW",sameEmployeePayload)));
        assertEquals("REVIEWER_MUST_DIFFER",sameEmployee.code());
    }

    private BiddingTypes.Ref save(BiddingTypes.Scope scope,BiddingTypes.Ref ref,ObjectNode payload,List<BiddingTypes.Ref> refs,String status)throws Exception {
        jdbc.update("INSERT INTO mate_bidding_revision(id,workspace_id,project_id,kind,object_id,version,payload_json,input_refs_json,status,digest,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",UUID.randomUUID().toString(),scope.workspaceId(),scope.projectId(),ref.kind(),ref.id(),ref.version(),json.writeValueAsString(payload),json.writeValueAsString(refs),status,ref.digest(),Timestamp.from(Instant.now()));
        return ref;
    }
}

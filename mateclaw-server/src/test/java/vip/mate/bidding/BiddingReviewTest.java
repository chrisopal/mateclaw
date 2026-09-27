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
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.mockito.Mockito;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.beans.factory.annotation.Autowired;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import vip.mate.wiki.model.WikiKnowledgeBaseEntity;
import vip.mate.wiki.model.WikiPageEntity;
import vip.mate.wiki.service.WikiKnowledgeBaseService;
import vip.mate.wiki.service.WikiPageService;
import vip.mate.wiki.service.WikiPageTypePermissionService;

class BiddingReviewTest extends BiddingHttpFixture {
    @MockBean BiddingEmployeeRuntime runtime;
    @SpyBean BiddingDependencies dependencies;
    @Autowired BiddingRepository repository;
    @Autowired BiddingReviewService reviews;
    @Autowired BiddingTaskService tasks;
    @Autowired BiddingMaterials materials;
    @Autowired BiddingSourceService sources;
    @Autowired WikiKnowledgeBaseService knowledgeBases;
    @MockBean WikiPageService pages;
    @MockBean WikiPageTypePermissionService pageTypePermissions;

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

    @Test void mandatoryAndBlockerFindingsCannotBeDeferredAsSuggestions() throws Exception {
        JsonNode project=project();String id=project.path("id").asText();BiddingTypes.Scope scope=new BiddingTypes.Scope(workspace,project.path("ownerId").asText(),id);
        for (String[] findingCase : List.of(new String[]{"MISSING_MANDATORY_PROOF","SUGGESTION"}, new String[]{"STYLE_SUGGESTION","BLOCKER"})) {
            String category=findingCase[0], severity=findingCase[1], suffix=category+severity;
            ObjectNode finding=json.createObjectNode().put("id",suffix).put("severity",severity).put("category",category);
            finding.putArray("chapterRefs");finding.putArray("requirementRefs");finding.putArray("evidenceRefs");
            ObjectNode saved=json.createObjectNode().put("findingId",suffix);saved.set("finding",finding);
            BiddingTypes.Ref findingRef=save(scope,new BiddingTypes.Ref("reviewFinding","finding-"+suffix,1,"finding-digest-"+suffix),saved,List.of(),"OPEN");
            ObjectNode payload=json.createObjectNode().set("findingRef",json.valueToTree(findingRef));payload.put("decision","DEFER_SUGGESTION").put("reason","defer").putArray("evidenceRefs");
            BiddingApiException rejected=assertThrows(BiddingApiException.class,()->reviews.resolve(scope,new BiddingTypes.Command("defer-"+suffix,findingRef,"RESOLVE_FINDING",payload)));
            assertEquals("FINDING_DECISION_INVALID",rejected.code());
        }
    }

    @Test void todoClassificationAndResolutionRejectReplacementForOriginalSourceClosure() throws Exception {
        JsonNode project=project();
        BiddingTypes.Scope scope=new BiddingTypes.Scope(workspace,project.path("ownerId").asText(),project.path("id").asText());
        ObjectNode originalUpload=sources.upload(scope,"todo-original-source","TENDER",null,
                docx("报价口径由商务负责人确认"),"original.docx");
        assertEquals(1,sources.readPending(2));
        BiddingTypes.Ref original=json.treeToValue(originalUpload.path("ref"),BiddingTypes.Ref.class);
        BiddingTypes.Ref originalSet=confirmSourceSet(scope,null,List.of(original),"select-original-todo-source");
        JsonNode originalBlock=sourceBlock(scope,original);

        ObjectNode todo=json.createObjectNode().put("todoId","COMM-REPLACED").put("title","Confirm pricing basis")
                .put("ownerId",scope.actorId()).put("status","OPEN").put("impactClassification","UNCLASSIFIED");
        todo.putArray("sourceRefs").add(json.valueToTree(original));
        todo.putArray("evidenceRefs").addObject().put("sourceId",original.id()).put("version",original.version())
                .put("blockId",originalBlock.path("id").asText()).put("quote",originalBlock.path("text").asText());
        BiddingTypes.Ref todoRef=save(scope,new BiddingTypes.Ref("HUMAN_TODO","todo-original-source",1,"todo-original-digest"),todo,List.of(original),"OPEN");
        ObjectNode classify=json.createObjectNode().set("todoRef",json.valueToTree(todoRef));
        classify.put("affectsTechnical",false).put("reason","business-only owner confirmation");
        classify.putArray("evidenceRefs").addObject().put("sourceId",original.id()).put("version",original.version())
                .put("blockId",originalBlock.path("id").asText()).put("quote",originalBlock.path("text").asText());
        ObjectNode classified=reviews.classifyHumanTodo(scope,new BiddingTypes.Command("classify-original-todo",todoRef,"CLASSIFY_HUMAN_TODO",classify));
        BiddingTypes.Ref classifiedRef=json.treeToValue(classified.path("ref"),BiddingTypes.Ref.class);

        ObjectNode replacementUpload=sources.upload(scope,"todo-replacement-source","TENDER",null,
                docx("报价口径已由另一个来源确认"),"replacement.docx");
        assertEquals(1,sources.readPending(2));
        BiddingTypes.Ref replacement=json.treeToValue(replacementUpload.path("ref"),BiddingTypes.Ref.class);
        BiddingTypes.Ref replacementSet=confirmSourceSet(scope,originalSet,List.of(replacement),"select-replacement-todo-source");
        JsonNode replacementBlock=sourceBlock(scope,replacement);
        assertDoesNotThrow(()->dependencies.validate(scope,List.of(replacement)),"replacement evidence is current on its own");

        ObjectNode reclassify=classify.deepCopy().set("todoRef",json.valueToTree(classifiedRef));
        reclassify.put("affectsTechnical",true).put("reason","newly supplied quote does not repair original todo provenance");
        reclassify.putArray("evidenceRefs").removeAll().addObject().put("sourceId",replacement.id()).put("version",replacement.version())
                .put("blockId",replacementBlock.path("id").asText()).put("quote",replacementBlock.path("text").asText());
        BiddingApiException staleClassification=assertThrows(BiddingApiException.class,()->reviews.classifyHumanTodo(scope,
                new BiddingTypes.Command("reclassify-replaced-todo",classifiedRef,"CLASSIFY_HUMAN_TODO",reclassify)));
        assertEquals("SOURCE_NOT_CONFIRMED",staleClassification.code());

        ObjectNode resolve=json.createObjectNode();resolve.set("todoRef",json.valueToTree(classifiedRef));resolve.put("reason","close from replacement source");
        resolve.putArray("evidenceRefs").addObject().put("sourceId",replacement.id()).put("version",replacement.version())
                .put("blockId",replacementBlock.path("id").asText()).put("quote",replacementBlock.path("text").asText());
        BiddingApiException staleResolution=assertThrows(BiddingApiException.class,()->reviews.resolveHumanTodo(scope,
                new BiddingTypes.Command("resolve-replaced-todo",classifiedRef,"RESOLVE_HUMAN_TODO",resolve)));
        assertEquals("SOURCE_NOT_CONFIRMED",staleResolution.code());
        assertEquals(2,repository.maxRevisionVersion(scope,"HUMAN_TODO",todoRef.id()),"neither rejected operation may append a todo revision");
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
        grantCurrentSkill(Long.parseLong(reviewer),skillNumericId);
        ObjectNode stored = repository.findProject(workspace, projectId);
        ObjectNode bindings=(ObjectNode)stored.path("bindings"); ObjectNode reviewerBinding=bindings.putObject("reviewer");
        reviewerBinding.put("agentId",reviewer).put("configDigest",configDigest).putArray("skillPins").addObject().put("skillId",Long.toString(skillNumericId)).put("digest",skillDigest);
        bindings.putObject("writer").put("agentId",writer).put("configDigest","d".repeat(64)).putArray("skillPins");
        jdbc.update("UPDATE mate_bidding_project SET body_json=? WHERE id=?",json.writeValueAsString(stored),projectId);
        jdbc.update("INSERT INTO mate_agent(id,name,enabled,workspace_id,create_time,update_time,deleted) VALUES(?,?,TRUE,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)",Long.valueOf(writer),"Review fixture writer",Long.valueOf(workspace));
        jdbc.update("INSERT INTO mate_agent(id,name,enabled,workspace_id,create_time,update_time,deleted) VALUES(?,?,TRUE,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)",Long.valueOf(reviewer),"Review fixture reviewer",Long.valueOf(workspace));
        long kbId=7101,pageId=9811;WikiKnowledgeBaseEntity kb=new WikiKnowledgeBaseEntity();kb.setId(kbId);kb.setWorkspaceId(Long.valueOf(workspace));kb.setDeleted(0);
        Mockito.when(knowledgeBases.findVisibleById(Long.valueOf(reviewer),kbId)).thenReturn(kb);Mockito.when(knowledgeBases.findVisibleById(Long.valueOf(writer),kbId)).thenReturn(kb);
        WikiPageEntity wikiPage=new WikiPageEntity();wikiPage.setId(pageId);wikiPage.setKbId(kbId);wikiPage.setDeleted(0);wikiPage.setTitle("Reviewer evidence");wikiPage.setPageType("experience");wikiPage.setContent("Frozen reviewer-only evidence");
        Mockito.when(pages.getById(pageId)).thenReturn(wikiPage);Mockito.when(pageTypePermissions.canRead(Long.valueOf(reviewer),kbId,"experience")).thenReturn(true);Mockito.when(pageTypePermissions.canRead(Long.valueOf(writer),kbId,"experience")).thenReturn(true);
        String materialDigest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(wikiPage.getContent().getBytes(StandardCharsets.UTF_8)));
        BiddingTypes.Ref reviewerMaterial=new BiddingTypes.Ref("material",kbId+":"+pageId,1,materialDigest);
        ObjectNode materialContent=json.createObjectNode().put("title",wikiPage.getTitle()).put("content",wikiPage.getContent()).put("pageType","experience").put("applicability","review evidence");
        ObjectNode materialAccess=json.createObjectNode().put("knowledgeBaseId",kbId).put("pageId",pageId).put("agentId",writer).put("workspaceId",workspace).put("pageType","experience");
        jdbc.update("INSERT INTO mate_bidding_material(id,workspace_id,project_id,source_kind,external_id,version,digest,content_json,access_ref_json,validity,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",UUID.randomUUID().toString(),workspace,projectId,"WIKI_PAGE",reviewerMaterial.id(),1,materialDigest,json.writeValueAsString(materialContent),json.writeValueAsString(materialAccess),"VALID");
        Mockito.doNothing().when(employees).validate(Mockito.eq(scope),Mockito.anyString(),Mockito.anyString());
        Mockito.when(employees.modelConfigId(Mockito.eq(scope),Mockito.anyString())).thenReturn("review-model");
        Mockito.doNothing().when(dependencies).validate(Mockito.eq(scope),Mockito.anyList());

        String sourceId="review-evidence"; String sourceDigest="evidence-digest";
        jdbc.update("INSERT INTO mate_bidding_source(id,workspace_id,project_id,source_id,version,kind,digest,content,blocks_json,quality,read_token,read_started_at,filename,read_status,problems_json,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(),workspace,projectId,sourceId,1,"TENDER",sourceDigest,"evidence".getBytes(),"[{\"id\":\"b1\",\"locator\":\"p1\",\"text\":\"报价口径由商务负责人确认\"}]","PASS",null,null,"evidence.txt","READY","[]",Timestamp.from(Instant.now()));
        BiddingTypes.Ref sourceRef=new BiddingTypes.Ref("source",sourceId,1,sourceDigest);
        BiddingTypes.Ref sourceSet=confirmSourceSet(scope,null,List.of(sourceRef),"confirm-review-source-set");
        ObjectNode baselineBody=json.createObjectNode().put("schemaVersion","1");baselineBody.put("sourceSetRef",json.writeValueAsString(sourceSet));
        baselineBody.set("sourceSetRef",json.valueToTree(sourceSet));
        ObjectNode analyses=baselineBody.putObject("analyses");
        ArrayNode requirements=analyses.putObject("bidding-requirement-analysis").putArray("requirements");
        ObjectNode analysisInput=json.createObjectNode().put("schemaVersion","1");analysisInput.putArray("blocks").addObject().put("id","b1").put("sourceId",sourceId).put("version",1).put("text","报价口径由商务负责人确认");analysisInput.putArray("readBlockIds").add("b1");
        ObjectNode analysisOutput=json.createObjectNode().put("schemaVersion","1");ArrayNode analysisRequirements=analysisOutput.putArray("requirements");
        ObjectNode commercial=analysisRequirements.addObject().put("id","COMM-1").put("text","项目商务负责人需确认报价口径").put("category","COMMERCIAL").putNull("acceptance");
        commercial.putArray("constraints").add("项目商务负责人需确认报价口径");commercial.putArray("evidenceRefs").addObject().put("sourceId",sourceId).put("version",1).put("blockId","b1").put("quote","报价口径由商务负责人确认");commercial.putArray("unknowns");
        analysisOutput.putObject("coverage").putArray("processedBlockIds").add("b1");analysisOutput.path("coverage").withArray("unprocessedBlockIds");analysisOutput.putArray("warnings");
        new BiddingSkillValidator().validate("bidding-requirement-analysis",analysisOutput,analysisInput);
        assertFalse(analysisOutput.path("requirements").get(0).has("affectsTechnical"),"COMMERCIAL impact is a human classification, not a model output field");
        requirements.addObject().put("id","REQ-1").put("category","TECHNICAL").put("text","接口响应时间不超过2秒");
        requirements.addAll((ArrayNode)analysisOutput.path("requirements"));
        analyses.putObject("bidding-scoring-analysis").putArray("criteria"); analyses.putObject("bidding-elimination-analysis").putArray("items");
        BiddingTypes.Ref baseline=save(scope,new BiddingTypes.Ref("analysisBaseline","review-baseline",1,"baseline-digest"),baselineBody,List.of(sourceSet),"CONFIRMED");
        jdbc.update("INSERT INTO mate_bidding_head(workspace_id,project_id,kind,object_id,version,selected_ref_json) VALUES(?,?,?,?,?,?)",
                workspace,projectId,"analysisBaseline",baseline.id(),baseline.version(),json.writeValueAsString(baseline));
        ObjectNode outlineBody=json.createObjectNode().put("schemaVersion","1");outlineBody.putArray("chapters").addObject().put("id","c1").putArray("requirementRefs").add("REQ-1");
        BiddingTypes.Ref outline=save(scope,new BiddingTypes.Ref("outline","review-outline",1,"outline-digest"),outlineBody,List.of(baseline),"CONFIRMED");
        ObjectNode chapterBody=json.createObjectNode().put("chapterId","c1");chapterBody.putArray("blocks").addObject().put("type","paragraph").put("text","接口响应时间不超过2秒");
        BiddingTypes.Ref chapter=save(scope,new BiddingTypes.Ref("chapter","c1",1,"chapter-digest"),chapterBody,List.of(outline,reviewerMaterial),"CONFIRMED");
        ObjectNode manuscriptBody=json.createObjectNode().put("schemaVersion","1");manuscriptBody.set("outlineRef",json.valueToTree(outline));
        manuscriptBody.putArray("chapters").addObject().put("chapterId","c1").set("chapter",chapterBody);
        BiddingTypes.Ref manuscript=save(scope,new BiddingTypes.Ref("manuscript","manuscript",1,"manuscript-digest"),manuscriptBody,List.of(outline,chapter),"DRAFT_PENDING_REVIEW");

        ObjectNode payload=json.createObjectNode().set("manuscriptRef",json.valueToTree(manuscript));
        ObjectNode dispatched=reviews.dispatch(scope,new BiddingTypes.Command("manual-review",manuscript,"DISPATCH_REVIEW",payload));
        assertEquals("QUEUED",dispatched.path("status").asText()); assertEquals(2,dispatched.path("tasks").size());
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM mate_bidding_task WHERE workspace_id=? AND project_id=? AND agent_id=? AND status='QUEUED'",Integer.class,workspace,projectId,reviewer));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM mate_bidding_revision WHERE workspace_id=? AND project_id=? AND kind='HUMAN_TODO' AND status='OPEN'",Integer.class,workspace,projectId));
        JsonNode todo=json.readTree(jdbc.queryForObject("SELECT payload_json FROM mate_bidding_revision WHERE workspace_id=? AND project_id=? AND kind='HUMAN_TODO'",String.class,workspace,projectId));
        assertEquals(scope.actorId(),todo.path("ownerId").asText());assertEquals("UNCLASSIFIED",todo.path("impactClassification").asText());
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
                ObjectNode dismiss=findingRows.addObject().put("id","F-EVID").put("severity","MAJOR").put("category","UNSUPPORTED_COMMITMENT");
                dismiss.set("chapterRefs",chapterCoverage.deepCopy());dismiss.putArray("requirementRefs").add("REQ-1");
                dismiss.putArray("evidenceRefs").addObject().put("sourceId",sourceId).put("version",1).put("blockId","b1").put("quote","报价口径由商务负责人确认");
                dismiss.put("description","commitment requires qualification").put("recommendation","confirm source qualification");
                ObjectNode suggestion=findingRows.addObject().put("id","F-SUG").put("severity","SUGGESTION").put("category","STYLE_SUGGESTION");
                suggestion.set("chapterRefs",chapterCoverage.deepCopy());suggestion.putArray("requirementRefs");suggestion.putArray("evidenceRefs");
                suggestion.put("description","consider a clearer heading").put("recommendation","defer if not in scope");
            }
            coverage.put("crossChapterReviewed",claim.input().path("_biddingTargetId").asText().endsWith(":cross"));output.putArray("limitations");output.putArray("warnings");
            tasks.complete(claim,new BiddingTypes.Execution(output,null,claim.skill().digest(),claim.configDigest(),null));
        }
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM mate_bidding_revision WHERE workspace_id=? AND project_id=? AND kind='review' AND status='REVIEW_RESULT'",Integer.class,workspace,projectId));
        String reviewerTaskId=dispatched.path("tasks").get(0).path("taskId").asText();
        JsonNode reviewerTask=api("GET","/tasks/"+reviewerTaskId,"viewer",workspace,null,200);
        assertTrue(reviewerTask.path("snapshot").path("input").path("evidenceSnapshot").path("materials").path("items").toString().contains("Frozen reviewer-only evidence"));
        // Rebinding the task to a different employee must revoke the old reviewer's
        // embedded snapshot even when that employee is now the project writer.
        String reboundReviewer="920003";
        jdbc.update("INSERT INTO mate_agent(id,name,enabled,workspace_id,create_time,update_time,deleted) VALUES(?,?,TRUE,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)",Long.valueOf(reboundReviewer),"Rebound review fixture",Long.valueOf(workspace));
        ObjectNode reboundProject=repository.findProject(workspace,projectId);
        ((ObjectNode)reboundProject.path("bindings").path("writer")).put("agentId",reviewer);
        ((ObjectNode)reboundProject.path("bindings").path("reviewer")).put("agentId",reboundReviewer);
        jdbc.update("UPDATE mate_bidding_project SET body_json=? WHERE id=?",json.writeValueAsString(reboundProject),projectId);
        JsonNode formerReviewerRead=api("GET","/tasks/"+reviewerTaskId,"viewer",workspace,null,403);
        assertFalse(formerReviewerRead.toString().contains("Frozen reviewer-only evidence"),"a former reviewer must not receive the embedded historical evidence snapshot");
        // Restore the original reviewer for the remaining review and ACL checks.
        ((ObjectNode)reboundProject.path("bindings").path("writer")).put("agentId",writer);
        ((ObjectNode)reboundProject.path("bindings").path("reviewer")).put("agentId",reviewer);
        jdbc.update("UPDATE mate_bidding_project SET body_json=? WHERE id=?",json.writeValueAsString(reboundProject),projectId);
        Mockito.doNothing().when(dependencies).validateForRead(scope,List.of(manuscript));
        JsonNode review=reviews.read(scope);assertEquals("REVIEWED",review.path("status").asText());assertEquals(2,review.path("tasks").size());
        assertEquals(1,review.path("humanTodos").size());assertEquals(scope.actorId(),review.path("humanTodos").get(0).path("ownerId").asText());
        JsonNode publicReview=api("GET","/projects/"+projectId+"/review","member",workspace,null,200);
        assertEquals("REVIEWED",publicReview.path("status").asText());assertEquals(2,publicReview.path("tasks").size());
        JsonNode reviewFinding=findFinding(review,"F-1");BiddingTypes.Ref findingRef=json.treeToValue(reviewFinding.path("findingRef"),BiddingTypes.Ref.class);
        ObjectNode fix=json.createObjectNode().set("findingRef",json.valueToTree(findingRef));fix.put("decision","FIX").put("reason","需要定向修订").putArray("evidenceRefs");
        reviews.resolve(scope,new BiddingTypes.Command("fix-finding",findingRef,"RESOLVE_FINDING",fix));
        assertEquals(1,reviews.selectedFindings(scope,"c1",json.createArrayNode().add(json.valueToTree(findingRef)),manuscript).size());
        JsonNode evidenceFinding=findFinding(review,"F-EVID");BiddingTypes.Ref evidenceFindingRef=json.treeToValue(evidenceFinding.path("findingRef"),BiddingTypes.Ref.class);
        ObjectNode dismiss=json.createObjectNode().set("findingRef",json.valueToTree(evidenceFindingRef));dismiss.put("decision","DISMISS_WITH_EVIDENCE").put("reason","source evidence resolves the concern");
        dismiss.putArray("evidenceRefs").addObject().put("sourceId",sourceId).put("version",1).put("blockId","b1").put("quote","报价口径由商务负责人确认");
        reviews.resolve(scope,new BiddingTypes.Command("dismiss-with-evidence",evidenceFindingRef,"RESOLVE_FINDING",dismiss));
        JsonNode suggestionFinding=findFinding(review,"F-SUG");BiddingTypes.Ref suggestionRef=json.treeToValue(suggestionFinding.path("findingRef"),BiddingTypes.Ref.class);
        ObjectNode defer=json.createObjectNode().set("findingRef",json.valueToTree(suggestionRef));defer.put("decision","DEFER_SUGGESTION").put("reason","retain as a follow-up suggestion").putArray("evidenceRefs");
        reviews.resolve(scope,new BiddingTypes.Command("defer-suggestion",suggestionRef,"RESOLVE_FINDING",defer));
        JsonNode savedDismiss=json.readTree(jdbc.queryForObject("SELECT payload_json FROM mate_bidding_revision WHERE workspace_id=? AND project_id=? AND kind='findingDecision' AND object_id=?",String.class,workspace,projectId,evidenceFindingRef.id()));
        assertEquals("DISMISS_WITH_EVIDENCE",jdbc.queryForObject("SELECT status FROM mate_bidding_revision WHERE workspace_id=? AND project_id=? AND kind='findingDecision' AND object_id=?",String.class,workspace,projectId,evidenceFindingRef.id()));
        assertEquals(sourceId,savedDismiss.path("evidenceRefs").get(0).path("sourceId").asText());
        assertEquals("DEFER_SUGGESTION",jdbc.queryForObject("SELECT status FROM mate_bidding_revision WHERE workspace_id=? AND project_id=? AND kind='findingDecision' AND object_id=?",String.class,workspace,projectId,suggestionRef.id()));
        BiddingTypes.Ref todoRef=json.treeToValue(review.path("humanTodos").get(0).path("ref"),BiddingTypes.Ref.class);
        ObjectNode prematureClose=json.createObjectNode().put("operationId","premature-close-unclassified").set("expected",json.valueToTree(todoRef));prematureClose.put("action","RESOLVE_HUMAN_TODO");
        ObjectNode prematurePayload=json.createObjectNode().set("todoRef",json.valueToTree(todoRef));prematurePayload.put("reason","attempt to close before classifying impact");
        prematurePayload.putArray("evidenceRefs").addObject().put("sourceId",sourceId).put("version",1).put("blockId","b1").put("quote","报价口径由商务负责人确认");prematureClose.set("payload",prematurePayload);
        assertEquals(409,api("POST","/projects/"+projectId+"/commands","owner",workspace,prematureClose,409).path("code").asInt());
        ObjectNode classify=json.createObjectNode().set("todoRef",json.valueToTree(todoRef));classify.put("affectsTechnical",true).put("reason","the pricing assumption changes the required technical configuration");
        classify.putArray("evidenceRefs").addObject().put("sourceId",sourceId).put("version",1).put("blockId","b1").put("quote","报价口径由商务负责人确认");
        ObjectNode falseClassification=classify.deepCopy().put("affectsTechnical",false);
        JsonNode businessOnly=classifyTodo(projectId,todoRef,"classify-business-only",falseClassification);
        BiddingTypes.Ref businessTodo=json.treeToValue(businessOnly.path("ref"),BiddingTypes.Ref.class);
        JsonNode openBusinessTodo=json.readTree(jdbc.queryForObject("SELECT payload_json FROM mate_bidding_revision WHERE workspace_id=? AND project_id=? AND kind='HUMAN_TODO' AND object_id=? ORDER BY version DESC LIMIT 1",String.class,workspace,projectId,todoRef.id()));
        assertEquals("OPEN",openBusinessTodo.path("status").asText());assertFalse(openBusinessTodo.path("affectsTechnical").asBoolean());
        assertDoesNotThrow(()->reviews.requireReviewed(scope,manuscript),"a human-classified business-only todo stays open without blocking the technical review");
        ObjectNode technicalClassification=classify.deepCopy().set("todoRef",json.valueToTree(businessTodo));
        JsonNode classified=classifyTodo(projectId,businessTodo,"classify-commercial-impact",technicalClassification);
        ObjectNode classifyCommand=json.createObjectNode().put("operationId","classify-commercial-impact").set("expected",json.valueToTree(businessTodo));
        classifyCommand.put("action","CLASSIFY_HUMAN_TODO").set("payload",technicalClassification);
        JsonNode replayedClassification=api("POST","/projects/"+projectId+"/commands","owner",workspace,classifyCommand,200);
        BiddingTypes.Ref classifiedTodo=json.treeToValue(classified.path("ref"),BiddingTypes.Ref.class);
        assertEquals(classified,replayedClassification,"replaying the same human classification must return its immutable decision");
        ObjectNode conflictingPayload=technicalClassification.deepCopy().put("reason","conflicting reason");
        ObjectNode conflictingCommand=classifyCommand.deepCopy().set("payload",conflictingPayload);
        assertEquals(409,api("POST","/projects/"+projectId+"/commands","owner",workspace,conflictingCommand,409).path("code").asInt());
        JsonNode persistedClassification=json.readTree(jdbc.queryForObject("SELECT payload_json FROM mate_bidding_revision WHERE workspace_id=? AND project_id=? AND kind='HUMAN_TODO' AND object_id=? ORDER BY version DESC LIMIT 1",String.class,workspace,projectId,todoRef.id()));
        assertEquals(true,persistedClassification.path("affectsTechnical").asBoolean());assertEquals("CLASSIFIED",persistedClassification.path("impactClassification").asText());
        BiddingApiException todoGate=assertThrows(BiddingApiException.class,()->reviews.requireReviewed(scope,manuscript));assertEquals("HUMAN_TODO_BLOCKS_TECHNICAL_APPROVAL",todoGate.code());
        ObjectNode closeTodo=json.createObjectNode().set("todoRef",json.valueToTree(classifiedTodo));closeTodo.put("reason","confirmed exact quotation with commercial owner");
        closeTodo.putArray("evidenceRefs").addObject().put("sourceId",sourceId).put("version",1).put("blockId","b1").put("quote","报价口径由商务负责人确认");
        ObjectNode todoResolution=reviews.resolveHumanTodo(scope,new BiddingTypes.Command("resolve-real-todo",classifiedTodo,"RESOLVE_HUMAN_TODO",closeTodo));
        assertEquals("RESOLVED",todoResolution.path("status").asText());
        JsonNode persistedTodo=json.readTree(jdbc.queryForObject("SELECT payload_json FROM mate_bidding_revision WHERE workspace_id=? AND project_id=? AND kind='HUMAN_TODO' AND object_id=? ORDER BY version DESC LIMIT 1",String.class,workspace,projectId,todoRef.id()));
        assertEquals(scope.actorId(),persistedTodo.path("resolvedBy").asText());assertEquals("confirmed exact quotation with commercial owner",persistedTodo.path("resolutionReason").asText());
        assertEquals(sourceId,persistedTodo.path("resolutionEvidenceRefs").get(0).path("sourceId").asText());
        assertDoesNotThrow(()->reviews.requireReviewed(scope,manuscript));
        assertEquals(4,repository.maxRevisionVersion(scope,"HUMAN_TODO",todoRef.id()),
                "classification and resolution revisions stay on the same baseline-scoped todo identity");

        Mockito.verify(dependencies,Mockito.atLeastOnce()).validate(scope,List.of(manuscript));
        Mockito.doThrow(new BiddingApiException(409,"DEPENDENCY_STALE","test current source closure changed"))
                .when(dependencies).validate(scope,List.of(manuscript));
        BiddingApiException staleClosure=assertThrows(BiddingApiException.class,()->reviews.requireReviewed(scope,manuscript));
        assertEquals("DEPENDENCY_STALE",staleClosure.code());
        Mockito.reset(dependencies);
        Mockito.doNothing().when(dependencies).validate(Mockito.eq(scope),Mockito.anyList());
        Mockito.doThrow(new BiddingApiException(409,"DEPENDENCY_STALE","test current source closure changed"))
                .when(dependencies).validateForRead(scope,List.of(manuscript));
        assertEquals("REVIEW_STALE",reviews.read(scope).path("status").asText());
        Mockito.reset(dependencies);
        Mockito.doNothing().when(dependencies).validate(Mockito.eq(scope),Mockito.anyList());
        Mockito.doNothing().when(dependencies).validateForRead(scope,List.of(manuscript));
        Mockito.when(pageTypePermissions.canRead(Long.valueOf(reviewer),kbId,"experience")).thenReturn(false);
        assertEquals("Frozen reviewer-only evidence",materials.snapshot(scope,writer,List.of(reviewerMaterial)).path("items").get(0).path("content").path("content").asText(),"the writer retains material access");
        JsonNode revokedRead=reviews.read(scope);assertEquals("REVIEW_ACCESS_REVOKED",revokedRead.path("status").asText());
        assertFalse(revokedRead.toString().contains("Frozen reviewer-only evidence"),"revoked material content must not be returned with the status marker");
        api("GET","/tasks/"+reviewerTaskId,"viewer",workspace,null,403);
        BiddingApiException revokedGate=assertThrows(BiddingApiException.class,()->reviews.requireReviewed(scope,manuscript));
        assertEquals("REVIEWER_MATERIAL_UNAVAILABLE",revokedGate.code());
        // Preserve an old, pre-baseline todo shape. Reusing COMM-1 in a later
        // analysis must create a new open todo instead of inheriting this resolved
        // nontechnical decision.
        ObjectNode legacyTodo=json.createObjectNode().put("todoId","COMM-1").put("requirementRef","COMM-1")
                .put("title","legacy commercial assumption").put("ownerId",scope.actorId()).put("status","RESOLVED")
                .put("impactClassification","CLASSIFIED").put("affectsTechnical",false);
        legacyTodo.set("sourceRefs",json.valueToTree(List.of(sourceRef)));
        legacyTodo.putArray("evidenceRefs").addObject().put("sourceId",sourceId).put("version",1)
                .put("blockId","b1").put("quote","报价口径由商务负责人确认");
        long legacyVersion=repository.maxRevisionVersion(scope,"HUMAN_TODO","COMM-1")+1;
        save(scope,new BiddingTypes.Ref("HUMAN_TODO","COMM-1",legacyVersion,"legacy-todo-digest-"+legacyVersion),
                legacyTodo,List.of(sourceRef),"RESOLVED");

        String nextSourceId="review-evidence-next",nextSourceDigest="evidence-digest-next";
        jdbc.update("INSERT INTO mate_bidding_source(id,workspace_id,project_id,source_id,version,kind,digest,content,blocks_json,quality,read_token,read_started_at,filename,read_status,problems_json,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",
                UUID.randomUUID().toString(),workspace,projectId,nextSourceId,1,"TENDER",nextSourceDigest,"new evidence".getBytes(),"[{\"id\":\"b2\",\"locator\":\"p2\",\"text\":\"新基线的商务口径\"}]","PASS",null,null,"evidence-next.txt","READY","[]");
        BiddingTypes.Ref nextSourceRef=new BiddingTypes.Ref("source",nextSourceId,1,nextSourceDigest);
        BiddingTypes.Ref nextSourceSet=confirmSourceSet(scope,sourceSet,List.of(sourceRef,nextSourceRef),"confirm-next-review-source-set");
        ObjectNode nextBaselineBody=baselineBody.deepCopy();nextBaselineBody.set("sourceSetRef",json.valueToTree(nextSourceSet));
        ObjectNode nextAnalysis=(ObjectNode)nextBaselineBody.path("analyses").path("bidding-requirement-analysis");
        ObjectNode nextCommercial=(ObjectNode)nextAnalysis.path("requirements").get(1);
        nextCommercial.put("text","新基线要求商务负责人确认报价边界");
        nextCommercial.putArray("evidenceRefs").addObject().put("sourceId",nextSourceId).put("version",1)
                .put("blockId","b2").put("quote","新基线的商务口径");
        BiddingTypes.Ref nextBaseline=save(scope,new BiddingTypes.Ref("analysisBaseline","review-baseline-next",1,"baseline-digest-next"),
                nextBaselineBody,List.of(nextSourceSet),"CONFIRMED");
        jdbc.update("UPDATE mate_bidding_head SET version=?,selected_ref_json=? WHERE workspace_id=? AND project_id=? AND kind='analysisBaseline' AND object_id=?",
                nextBaseline.version(),json.writeValueAsString(nextBaseline),workspace,projectId,nextBaseline.id());
        ObjectNode nextOutlineBody=json.createObjectNode().put("schemaVersion","1");
        nextOutlineBody.putArray("chapters").addObject().put("id","c1").putArray("requirementRefs").add("REQ-1");
        BiddingTypes.Ref nextOutline=save(scope,new BiddingTypes.Ref("outline","review-outline-next",1,"outline-digest-next"),
                nextOutlineBody,List.of(nextBaseline),"CONFIRMED");
        ObjectNode nextChapterBody=json.createObjectNode().put("chapterId","c1");
        nextChapterBody.putArray("blocks").addObject().put("type","paragraph").put("text","接口响应时间不超过2秒");
        BiddingTypes.Ref nextChapter=save(scope,new BiddingTypes.Ref("chapter","c1",2,"chapter-digest-next"),
                nextChapterBody,List.of(nextOutline,reviewerMaterial),"CONFIRMED");
        Mockito.when(pageTypePermissions.canRead(Long.valueOf(reviewer),kbId,"experience")).thenReturn(true);
        ObjectNode nextManuscriptBody=json.createObjectNode().put("schemaVersion","1");
        nextManuscriptBody.set("outlineRef",json.valueToTree(nextOutline));
        nextManuscriptBody.putArray("chapters").addObject().put("chapterId","c1").set("chapter",nextChapterBody);
        BiddingTypes.Ref nextManuscript=save(scope,new BiddingTypes.Ref("manuscript","manuscript",2,"next-manuscript-digest"),
                nextManuscriptBody,List.of(nextOutline,nextChapter),"DRAFT_PENDING_REVIEW");
        ObjectNode nextDispatch=json.createObjectNode().set("manuscriptRef",json.valueToTree(nextManuscript));
        reviews.dispatch(scope,new BiddingTypes.Command("review-next-baseline",nextManuscript,"DISPATCH_REVIEW",nextDispatch));
        Mockito.doNothing().when(dependencies).validateForRead(scope,List.of(nextManuscript));
        JsonNode nextReviewRead=reviews.read(scope);
        assertEquals(1,nextReviewRead.path("humanTodos").size(),"historical and unscoped todo rows must not appear in the current baseline read model: "+nextReviewRead);
        JsonNode nextTodo=nextReviewRead.path("humanTodos").get(0);
        assertEquals("OPEN",nextTodo.path("status").asText());
        assertEquals("UNCLASSIFIED",nextTodo.path("impactClassification").asText());
        assertEquals(nextBaseline,json.treeToValue(nextTodo.path("baselineRef"),BiddingTypes.Ref.class));
        assertEquals("COMM-1",nextTodo.path("requirementId").asText());
        assertEquals(nextSourceId,nextTodo.path("sourceRefs").get(0).path("id").asText());
        JsonNode storedNextTodoRefs=json.readTree(jdbc.queryForObject("SELECT input_refs_json FROM mate_bidding_revision WHERE workspace_id=? AND project_id=? AND kind='HUMAN_TODO' AND object_id=? ORDER BY version DESC LIMIT 1",String.class,workspace,projectId,nextTodo.path("ref").path("id").asText()));
        assertTrue(java.util.stream.StreamSupport.stream(storedNextTodoRefs.spliterator(),false)
                .anyMatch(ref->nextBaseline.equals(json.convertValue(ref,BiddingTypes.Ref.class))),"stored todo dependency closure includes the exact analysis baseline");
        for(int i=0;i<2;i++) {
            BiddingTypes.Claim claim=repository.claimDue(Instant.now(),"reviewer-next-baseline",1).getFirst();
            ObjectNode output=json.createObjectNode().put("schemaVersion","1");output.putArray("findings");
            ObjectNode coverage=output.putObject("coverage");ArrayNode chapterCoverage=coverage.putArray("chapterRefs");
            for(JsonNode assigned:claim.input().path("chapters"))chapterCoverage.add(assigned.path("chapterRef").deepCopy());
            ArrayNode requirementCoverage=coverage.putArray("requirementRefs");
            for(JsonNode assigned:claim.input().path("requirements"))requirementCoverage.add(assigned.path("id").asText());
            coverage.put("crossChapterReviewed",claim.input().path("_biddingTargetId").asText().endsWith(":cross"));
            output.putArray("limitations");output.putArray("warnings");
            tasks.complete(claim,new BiddingTypes.Execution(output,null,claim.skill().digest(),claim.configDigest(),null));
        }
        BiddingApiException freshBaselineGate=assertThrows(BiddingApiException.class,()->reviews.requireReviewed(scope,nextManuscript));
        assertEquals("HUMAN_TODO_BLOCKS_TECHNICAL_APPROVAL",freshBaselineGate.code());
        BiddingApiException freshBaselineApproval=assertThrows(BiddingApiException.class,()->reviews.approvalEvidence(scope,nextManuscript));
        assertEquals("HUMAN_TODO_BLOCKS_TECHNICAL_APPROVAL",freshBaselineApproval.code());
        Mockito.when(pageTypePermissions.canRead(Long.valueOf(reviewer),kbId,"experience")).thenReturn(true);
        BiddingTypes.Ref revised=save(scope,new BiddingTypes.Ref("manuscript","manuscript",3,"new-manuscript-digest"),manuscriptBody,List.of(outline,chapter),"DRAFT_PENDING_REVIEW");
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

    private BiddingTypes.Ref confirmSourceSet(BiddingTypes.Scope scope,BiddingTypes.Ref expected,List<BiddingTypes.Ref> selected,String operationId) {
        ObjectNode payload=json.createObjectNode();
        if(expected==null)payload.putNull("expectedSourceSetRef");else payload.set("expectedSourceSetRef",json.valueToTree(expected));
        payload.set("sourceRefs",json.valueToTree(selected));payload.putArray("exclusions");
        BiddingTypes.Ref projectRef=json.convertValue(repository.findProject(scope.workspaceId(),scope.projectId()).path("ref"),BiddingTypes.Ref.class);
        ObjectNode result=sources.confirmSet(scope,new BiddingTypes.Command(operationId,projectRef,"CONFIRM_SOURCE_SET",payload));
        return json.convertValue(result.path("ref"),BiddingTypes.Ref.class);
    }

    private JsonNode sourceBlock(BiddingTypes.Scope scope,BiddingTypes.Ref ref) throws Exception {
        BiddingRepository.SourceRow source=repository.source(scope.workspaceId(),scope.projectId(),ref.id(),ref.version());
        assertNotNull(source);JsonNode blocks=json.readTree(source.blocks());assertTrue(blocks.isArray()&&!blocks.isEmpty());return blocks.get(0);
    }

    private byte[] docx(String text) throws Exception {
        try (XWPFDocument document=new XWPFDocument();ByteArrayOutputStream output=new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText(text);document.write(output);return output.toByteArray();
        }
    }

    private JsonNode findFinding(JsonNode review,String id){for(JsonNode finding:review.path("findings"))if(id.equals(finding.path("finding").path("id").asText()))return finding;throw new AssertionError("Missing finding "+id);}
    private JsonNode classifyTodo(String projectId,BiddingTypes.Ref expected,String operationId,ObjectNode payload) throws Exception {
        ObjectNode command=json.createObjectNode().put("operationId",operationId).set("expected",json.valueToTree(expected));
        command.put("action","CLASSIFY_HUMAN_TODO").set("payload",payload.deepCopy().set("todoRef",json.valueToTree(expected)));
        return api("POST","/projects/"+projectId+"/commands","owner",workspace,command,200);
    }
}

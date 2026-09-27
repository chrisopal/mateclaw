package vip.mate.bidding;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.StreamSupport;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import reactor.core.publisher.Flux;
import vip.mate.MateClawApplication;
import vip.mate.agent.model.AgentEntity;
import vip.mate.bidding.BiddingTypes.Claim;
import vip.mate.auth.model.UserEntity;
import vip.mate.auth.service.AuthService;
import vip.mate.llm.service.ModelConfigService;
import vip.mate.workspace.core.model.WorkspaceEntity;
import vip.mate.workspace.core.service.WorkspaceService;

/** Full application acceptance harness; only the external model provider is controlled. */
@SpringBootTest(classes = MateClawApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:bidding_e2e_${random.uuid};MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
                "spring.ai.dashscope.api-key=test-key",
                "mateclaw.semantic.enabled=false",
                "mateclaw.presales.enabled=false",
                "mateclaw.bidding.enabled=true",
                "mateclaw.bidding.scheduler-enabled=false",
                "mateclaw.skill.workspace.root=${java.io.tmpdir}/mateclaw-bidding-e2e-${random.uuid}"
        })
@AutoConfigureMockMvc
class BiddingEndToEndTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired AuthService auth;
    @Autowired WorkspaceService workspaces;
    @Autowired ModelConfigService modelConfigs;
    @Autowired BiddingSourceService sources;
    @Autowired BiddingTaskService tasks;
    @Autowired BiddingEmployeeRuntime runtime;
    @Autowired BiddingEmployeeBindings employeeBindings;
    @Autowired BiddingSkillValidator skillValidator;
    @Autowired vip.mate.llm.service.ModelProviderService modelProviders;
    @Autowired vip.mate.llm.failover.AvailableProviderPool providerPool;

    @MockBean vip.mate.llm.chatmodel.ProviderChatModelFactory providerFactory;

    private String workspace;
    private String ownerToken;
    private String memberToken;
    private final AtomicReference<Claim> activeClaim = new AtomicReference<>();
    private final List<String> modelTrace = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final java.util.concurrent.ConcurrentMap<String, java.util.Set<String>> loadedPinnedFiles = new java.util.concurrent.ConcurrentHashMap<>();

    @BeforeEach
    void createAuthenticatedWorkspace() throws Exception {
        AuthAccount owner = createUser("owner");
        AuthAccount member = createUser("member");
        WorkspaceEntity created = new WorkspaceEntity();
        created.setName("Bidding acceptance " + UUID.randomUUID());
        created.setDeleted(0);
        workspace = workspaces.create(created, owner.user().getId()).getId().toString();
        workspaces.addMember(Long.valueOf(workspace), member.user().getId(), "member");
        ownerToken = login(owner);
        memberToken = login(member);
        modelTrace.clear();
        loadedPinnedFiles.clear();
        jdbc.update("UPDATE mate_model_provider SET api_key='test-key',enabled=TRUE WHERE provider_id='dashscope'");
    }

    @Test
    void cannotSkipAnalysisAndOutlineByPostingWritingCommands() throws Exception {
        JsonNode project = createProject("阶段门禁验证");
        String projectId = project.path("id").asText();
        BiddingTypes.Ref expected = ref(project);

        ObjectNode writingPayload = json.createObjectNode();
        writingPayload.putArray("chapterIds").add("c1");
        JsonNode writing = command(projectId, expected, "DISPATCH_WRITING",
                writingPayload, ownerToken, 409);
        ObjectNode exportPayload = json.createObjectNode().put("mode", "candidate");
        exportPayload.set("manuscriptRef", json.valueToTree(new BiddingTypes.Ref(
                "manuscript", UUID.randomUUID().toString(), 1, "0".repeat(64))));
        JsonNode export = command(projectId, expected, "DISPATCH_EXPORT", exportPayload, ownerToken, 409);
        assertNotEquals("QUEUED", writing.path("data").path("status").asText());
        assertNotEquals("QUEUED", export.path("data").path("status").asText());

        ObjectNode approval = json.createObjectNode().put("artifactId", "forged").put("digest", "0".repeat(64));
        approval.set("manuscriptRef", json.valueToTree(expected));
        approval.set("templateRef", json.valueToTree(expected));
        approval.set("formatRef", json.valueToTree(expected));
        approval.set("reviewRef", json.valueToTree(expected));
        approval.putObject("inspection").put("opened", true).put("layoutChecked", true)
                .put("reason", "不能绕过前序阶段");
        JsonNode denied = command(projectId, expected, "APPROVE_ARTIFACT", approval, memberToken, 403);
        assertEquals("FORBIDDEN", denied.path("data").path("code").asText());

        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM mate_bidding_task WHERE project_id=?",
                Integer.class, projectId), "rejected stage skips must not enqueue downstream work");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM mate_bidding_decision WHERE project_id=?",
                Integer.class, projectId), "a workspace member cannot create an approval decision");
    }

    @Test
    void sourceAndAnalysisUseHttpCommandsAndPersistRejectedCrossProjectEvidenceBeforeRetry() throws Exception {
        JsonNode project = createProject("来源证据与分析重试");
        String projectId = project.path("id").asText();
        BiddingTypes.Ref projectRef = ref(project);
        Map<String, Long> pins = activeBiddingSkills();
        AgentEntity analyst = createAgent("analysis");
        AgentEntity writer = createAgent("writer");
        AgentEntity reviewer = createAgent("reviewer");
        bindSkills(analyst, List.of("bidding-tender-profile", "bidding-elimination-analysis",
                "bidding-requirement-analysis", "bidding-scoring-analysis"), pins);
        bindSkills(writer, List.of("bidding-outline-planning", "bidding-technical-writing", "bidding-document-export"), pins);
        bindSkills(reviewer, List.of("bidding-technical-review"), pins);
        for (AgentEntity employee : List.of(analyst, writer, reviewer)) {
            assertEquals(Long.valueOf(workspace), employee.getWorkspaceId());
            String modelId = employeeBindings.modelConfigId(new BiddingTypes.Scope(workspace, "", "probe"), employee.getId().toString());
            assertNotNull(modelId);
            assertEquals("openai", jdbc.queryForObject("SELECT provider FROM mate_model_config WHERE id=?", String.class, Long.valueOf(modelId)));
            assertTrue(modelProviders.isProviderConfigured("openai"));
        }
        ObjectNode assign = json.createObjectNode();
        assign.put("analystAgentId", analyst.getId()).put("writerAgentId", writer.getId())
                .put("reviewerAgentId", reviewer.getId());
        JsonNode assignment = command(projectId, projectRef, "ASSIGN_EMPLOYEES", assign, ownerToken, 200).path("data");
        assertEquals(4, assignment.path("result").path("bindings").path("analyst").path("skillPins").size(), assignment.toString());
        assertEquals(4, assignment.path("result").path("bindings").path("analyst").path("skillPins").size(),
                "assignment retains all four fixed numeric pins");
        projectRef = currentProjectRef(projectId);

        MockMultipartFile sourceFile = new MockMultipartFile("file", "tender.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", tenderDocx());
        var uploaded = mvc.perform(MockMvcRequestBuilders.multipart(
                        "/api/v1/bidding/projects/" + projectId + "/sources")
                .file(sourceFile).param("operationId", UUID.randomUUID().toString()).param("sourceKind", "TENDER")
                .header("Authorization", ownerToken).header("X-Workspace-Id", workspace))
                .andReturn().getResponse();
        assertEquals(200, uploaded.getStatus(), uploaded.getContentAsString());
        JsonNode source = json.readTree(uploaded.getContentAsString()).path("data");
        assertEquals(1, sources.readPending(2));
        JsonNode parsed = api("GET", "/api/v1/bidding/projects/" + projectId + "/sources", ownerToken, null, 200)
                .path("data").get(0);
        assertEquals("READY", parsed.path("readStatus").asText());
        JsonNode requirementBlock = StreamSupport.stream(parsed.path("blocks").spliterator(), false)
                .filter(block -> block.path("text").asText().contains("交付验收前提供接口性能验证记录"))
                .findFirst().orElseThrow(() -> new AssertionError("parsed source must retain the delivery acceptance requirement"));
        String blockId = requirementBlock.path("id").asText();
        String quote = "交付验收前提供接口性能验证记录";
        BiddingTypes.Ref sourceRef = json.convertValue(source.path("ref"), BiddingTypes.Ref.class);
        ObjectNode confirmSource = json.createObjectNode().putNull("expectedSourceSetRef");
        confirmSource.putArray("sourceRefs").add(json.valueToTree(sourceRef));
        confirmSource.putArray("exclusions");
        projectRef = currentProjectRef(projectId);
        JsonNode sourceSet = command(projectId, projectRef, "CONFIRM_SOURCE_SET", confirmSource, ownerToken, 200)
                .path("data");
        BiddingTypes.Ref sourceSetRef = json.convertValue(sourceSet.path("ref"), BiddingTypes.Ref.class);

        AtomicReference<String> forgedValidationPath = new AtomicReference<>();
        AtomicBoolean forgeFirstEvidence = new AtomicBoolean(true);
        JsonNode foreignSource = createProjectSource("其他项目来源", "另一项目的性能证据");
        assertNotEquals(projectId, foreignSource.path("projectId").asText());
        BiddingTypes.Ref foreignRef = json.convertValue(foreignSource.path("ref"), BiddingTypes.Ref.class);
        String foreignBlockId = foreignSource.path("blocks").get(0).path("id").asText();
        String foreignQuote = foreignSource.path("blocks").get(0).path("text").asText();
        installControlledProvider(activeClaim, sourceRef, blockId, quote, foreignRef, foreignBlockId,
                foreignQuote, forgeFirstEvidence, forgedValidationPath, modelTrace);
        ObjectNode dispatch = json.createObjectNode().set("sourceSetRef", json.valueToTree(sourceSetRef));
        projectRef = currentProjectRef(projectId);
        JsonNode dispatched = command(projectId, projectRef, "DISPATCH_ANALYSIS", dispatch, ownerToken, 200).path("data");
        List<String> taskIds = new ArrayList<>();
        dispatched.path("taskIds").forEach(item -> taskIds.add(item.asText()));
        assertEquals(4, taskIds.size(), "the four pinned analysis packages must be scheduled");

        for (int i = 0; i < taskIds.size(); i++) {
            Claim claim = tasks.claimDue(Instant.now(), "e2e-analysis-" + i, 1).getFirst();
            activeClaim.set(claim);
            tasks.complete(claim, executeAndAssertPinnedFiles(claim));
        }
        JsonNode afterRejected = api("GET", "/api/v1/bidding/projects/" + projectId + "/tasks", ownerToken, null, 200)
                .path("data").path("items");
        String rejectedTask = null;
        String rejectedSkill = null;
        for (JsonNode task : afterRejected) {
            if (taskIds.contains(task.path("taskId").asText()) && "FAILED".equals(task.path("status").asText())) {
                rejectedTask = task.path("taskId").asText();
                rejectedSkill = task.path("skillId").asText();
            }
        }
        String diagnostic = taskIds.isEmpty() ? "none" : jdbc.query(
                "SELECT error_json FROM mate_bidding_attempt WHERE task_id=? ORDER BY attempt_no DESC",
                rs -> rs.next() ? rs.getString(1) : "no attempt", taskIds.get(0));
        assertNotNull(rejectedTask, "cross-project source evidence must be rejected and persisted; first attempt=" + diagnostic);
        String rejected = jdbc.queryForObject("SELECT error_json FROM mate_bidding_attempt WHERE task_id=? ORDER BY attempt_no DESC", String.class, rejectedTask);
        JsonNode rejectedError = json.readTree(rejected);
        assertEquals("EVIDENCE_OUT_OF_SCOPE", rejectedError.path("code").asText(), rejected);
        assertTrue(rejectedError.path("category").asText().equals("VALIDATION"), rejected);
        String receipts = jdbc.queryForObject("SELECT tool_receipts_json FROM mate_bidding_attempt WHERE task_id=? ORDER BY attempt_no DESC", String.class, rejectedTask);
        assertTrue(receipts.contains("bidding_read_source"), "failure follows an actual scoped source-reader receipt: " + receipts);
        JsonNode taskSnapshot = json.readTree(jdbc.queryForObject("SELECT input_json FROM mate_bidding_task WHERE id=?", String.class, rejectedTask));
        ObjectNode taskInput = ((ObjectNode) taskSnapshot.path("input")).deepCopy();
        ArrayNode readBlockIds = json.createArrayNode();
        for (JsonNode receipt : json.readTree(receipts)) {
            if ("bidding_read_source".equals(receipt.path("tool").asText())) readBlockIds.add(receipt.path("blockId").asText());
        }
        taskInput.set("readBlockIds", readBlockIds);
        JsonNode rejectedPayload = json.readTree(jdbc.queryForObject(
                "SELECT rejected_output FROM mate_bidding_attempt WHERE task_id=? ORDER BY attempt_no DESC", String.class, rejectedTask));
        String exactRejectedSkill = taskInput.path("skillId").asText();
        BiddingApiException exactValidation = assertThrows(BiddingApiException.class,
                () -> skillValidator.validate(exactRejectedSkill, (ObjectNode) rejectedPayload, taskInput));
        assertEquals("EVIDENCE_OUT_OF_SCOPE", exactValidation.code(), exactValidation.getMessage());
        assertTrue(exactValidation.getMessage().startsWith(forgedValidationPath.get() + ":"), exactValidation.getMessage());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM mate_bidding_revision WHERE workspace_id=? AND project_id=? AND kind='analysisBaseline'",
                Integer.class, workspace, projectId), "rejected evidence cannot create a confirmed baseline");
        ObjectNode retry = json.createObjectNode().put("taskId", rejectedTask);
        projectRef = currentProjectRef(projectId);
        command(projectId, projectRef, "RETRY_TASK", retry, ownerToken, 200);
        Claim retried = tasks.claimDue(Instant.now(), "e2e-analysis-retry", 1).getFirst();
        activeClaim.set(retried);
        tasks.complete(retried, executeAndAssertPinnedFiles(retried));
        JsonNode reloadedTasks = api("GET", "/api/v1/bidding/projects/" + projectId + "/tasks", ownerToken, null, 200)
                .path("data").path("items");
        for (String taskId : taskIds) {
            JsonNode task = null;
            for (JsonNode item : reloadedTasks) if (taskId.equals(item.path("taskId").asText())) task = item;
            assertNotNull(task, reloadedTasks.toString());
            String taskError = jdbc.query("SELECT error_json FROM mate_bidding_attempt WHERE task_id=? ORDER BY attempt_no DESC LIMIT 1",
                    rs -> rs.next() ? rs.getString(1) : "no attempt", taskId);
            String taskSnapshotJson = jdbc.queryForObject("SELECT input_json FROM mate_bidding_task WHERE id=?", String.class, taskId);
            String rejectedText = jdbc.query("SELECT rejected_output FROM mate_bidding_attempt WHERE task_id=? ORDER BY attempt_no DESC LIMIT 1",
                    rs -> rs.next() ? rs.getString(1) : "no rejection", taskId);
            String latestAttempt = jdbc.queryForObject("SELECT id FROM mate_bidding_attempt WHERE task_id=? ORDER BY attempt_no DESC LIMIT 1",
                    String.class, taskId);
            assertEquals("SUCCEEDED", task.path("status").asText(), "every analysis shard must succeed before baseline: " + task
                    + " error=" + taskError + " snapshot=" + taskSnapshotJson + " output=" + rejectedText
                    + " modelTrace=" + modelTrace.stream().filter(line -> line.contains(latestAttempt)).toList());
        }
        assertTrue(reloadedTasks.findValuesAsText("status").contains("SUCCEEDED"), reloadedTasks.toString());
        JsonNode retriedTask = null;
        for (JsonNode task : reloadedTasks) if (rejectedTask.equals(task.path("taskId").asText())) retriedTask = task;
        assertNotNull(retriedTask, reloadedTasks.toString());
        assertEquals("SUCCEEDED", retriedTask.path("status").asText(), retriedTask.toString());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM mate_bidding_attempt WHERE task_id=?",
                Integer.class, rejectedTask), "attempt history must retain both failure and retry");
        String retryReceipts = jdbc.queryForObject("SELECT tool_receipts_json FROM mate_bidding_attempt WHERE task_id=? ORDER BY attempt_no DESC LIMIT 1",
                String.class, rejectedTask);
        assertTrue(retryReceipts.contains("bidding_read_source"), "retry must persist its actual scoped reader receipt");

        ObjectNode confirmAnalysis = json.createObjectNode().put("taskGroupId", dispatched.path("taskGroupId").asText());
        JsonNode baselineResult = command(projectId, currentProjectRef(projectId), "CONFIRM_ANALYSIS", confirmAnalysis, ownerToken, 200)
                .path("data");
        BiddingTypes.Ref baselineRef = ref(baselineResult.path("ref"));
        assertNotNull(baselineRef, baselineResult.toString());
        JsonNode outlineTask = command(projectId, baselineRef, "DISPATCH_OUTLINE", json.createObjectNode(), ownerToken, 200)
                .path("data");
        assertEquals("QUEUED", outlineTask.path("status").asText(), outlineTask.toString());
        Claim outlineClaim = tasks.claimDue(Instant.now(), "e2e-outline", 1).getFirst();
        activeClaim.set(outlineClaim);
        tasks.complete(outlineClaim, executeAndAssertPinnedFiles(outlineClaim));
        JsonNode outlineView = api("GET", "/api/v1/bidding/projects/" + projectId + "/outline", ownerToken, null, 200)
                .path("data");
        JsonNode outlineCandidate = outlineView.path("candidates").get(0);
        assertNotNull(outlineCandidate, outlineView.toString());
        BiddingTypes.Ref outlineRef = ref(outlineCandidate.path("ref"));
        JsonNode confirmedOutline = command(projectId, ref(outlineView.path("editExpectedRef")), "CONFIRM_OUTLINE",
                json.createObjectNode().set("outlineRef", json.valueToTree(outlineRef)), ownerToken, 200).path("data");
        BiddingTypes.Ref confirmedOutlineRef = ref(confirmedOutline.path("ref"));
        assertNotNull(confirmedOutlineRef, confirmedOutline.toString());
        ObjectNode dispatchWriting = json.createObjectNode().set("outlineRef", json.valueToTree(confirmedOutlineRef));
        dispatchWriting.putArray("chapterIds").add("c1"); dispatchWriting.putArray("materialRefs");
        JsonNode writingDispatch = command(projectId, currentProjectRef(projectId), "DISPATCH_WRITING", dispatchWriting,
                ownerToken, 200).path("data");
        String writingTaskId = writingDispatch.path("tasks").get(0).path("taskId").asText();
        assertFalse(writingTaskId.isBlank(), writingDispatch.toString());
        Claim writingClaim = tasks.claimDue(Instant.now(), "e2e-writing", 1).getFirst();
        activeClaim.set(writingClaim);
        tasks.complete(writingClaim, executeAndAssertPinnedFiles(writingClaim));
        JsonNode writingView = api("GET", "/api/v1/bidding/projects/" + projectId + "/writing", ownerToken, null, 200)
                .path("data");
        JsonNode chapter = writingView.path("chapters").get(0);
        JsonNode chapterCandidate = chapter.path("candidates").get(0);
        assertNotNull(chapterCandidate, writingView.toString());
        BiddingTypes.Ref chapterCandidateRef = ref(chapterCandidate.path("ref"));
        JsonNode adopted = command(projectId, ref(chapter.path("editExpectedRef")), "ADOPT_CHAPTER",
                json.createObjectNode().put("chapterId", "c1").set("candidateRef", json.valueToTree(chapterCandidateRef)),
                ownerToken, 200).path("data");
        BiddingTypes.Ref selectedChapterRef = ref(adopted.path("ref"));
        assertNotNull(selectedChapterRef, adopted.toString());
        ObjectNode assemble = json.createObjectNode().set("outlineRef", json.valueToTree(confirmedOutlineRef));
        assemble.putArray("chapterRefs").addObject().put("chapterId", "c1").set("ref", json.valueToTree(selectedChapterRef));
        JsonNode assembled = command(projectId, confirmedOutlineRef, "ASSEMBLE_MANUSCRIPT", assemble, ownerToken, 200)
                .path("data");
        BiddingTypes.Ref manuscriptRef = ref(assembled);
        assertNotNull(manuscriptRef, assembled.toString());

        JsonNode initialReview = completeReviewTasks(projectId, manuscriptRef, "review-v1", true);
        assertEquals("REVIEWED", initialReview.path("status").asText(), initialReview.toString());
        JsonNode initialFinding = findFinding(initialReview, "F-E2E-TECH");
        assertNotNull(initialFinding, initialReview.toString());
        BiddingTypes.Ref findingRef = json.convertValue(initialFinding.path("findingRef"), BiddingTypes.Ref.class);
        ObjectNode fix = json.createObjectNode().set("findingRef", json.valueToTree(findingRef));
        fix.put("decision", "FIX").put("reason", "补充接口性能验证步骤").putArray("evidenceRefs");
        command(projectId, currentProjectRef(projectId), "RESOLVE_FINDING", fix, ownerToken, 200);

        ObjectNode revise = json.createObjectNode().set("manuscriptRef", json.valueToTree(manuscriptRef));
        revise.set("chapterRef", json.valueToTree(selectedChapterRef));
        revise.putArray("selectedFindingRefs").add(json.valueToTree(findingRef));
        JsonNode revisionDispatch = command(projectId, selectedChapterRef, "REVISE_CHAPTER", revise, ownerToken, 200)
                .path("data");
        assertEquals("CANDIDATE_PENDING", revisionDispatch.path("status").asText(), revisionDispatch.toString());
        completeClaimForAgent(writer.getId().toString(), "e2e-targeted-revision");
        JsonNode revisedWriting = api("GET", "/api/v1/bidding/projects/" + projectId + "/writing", ownerToken, null, 200)
                .path("data");
        JsonNode revisedChapter = revisedWriting.path("chapters").get(0);
        JsonNode revisedCandidate = revisedChapter.path("candidates").get(0);
        assertNotNull(revisedCandidate, revisedWriting.toString());
        BiddingTypes.Ref revisedCandidateRef = json.convertValue(revisedCandidate.path("ref"), BiddingTypes.Ref.class);
        JsonNode revisedAdoption = command(projectId, ref(revisedChapter.path("editExpectedRef")), "ADOPT_CHAPTER",
                json.createObjectNode().put("chapterId", "c1").set("candidateRef", json.valueToTree(revisedCandidateRef)),
                ownerToken, 200).path("data");
        BiddingTypes.Ref revisedChapterRef = ref(revisedAdoption);
        assertNotNull(revisedChapterRef, revisedAdoption.toString());
        ObjectNode reassemble = json.createObjectNode().set("outlineRef", json.valueToTree(confirmedOutlineRef));
        reassemble.putArray("chapterRefs").addObject().put("chapterId", "c1")
                .set("ref", json.valueToTree(revisedChapterRef));
        JsonNode revisedManuscriptResult = command(projectId, confirmedOutlineRef, "ASSEMBLE_MANUSCRIPT", reassemble,
                ownerToken, 200).path("data");
        BiddingTypes.Ref revisedManuscriptRef = ref(revisedManuscriptResult);
        assertNotNull(revisedManuscriptRef, revisedManuscriptResult.toString());
        assertNotEquals(manuscriptRef, revisedManuscriptRef);
        JsonNode finalReview = completeReviewTasks(projectId, revisedManuscriptRef, "review-v2", false);
        assertEquals("REVIEWED", finalReview.path("status").asText(), finalReview.toString());
        assertTrue(finalReview.path("findings").isEmpty(), finalReview.toString());

        JsonNode prepared = command(projectId, currentProjectRef(projectId), "PREPARE_EXPORT", json.createObjectNode(),
                ownerToken, 200).path("data");
        BiddingTypes.Ref templateRef = ref(prepared.path("templateRef"));
        BiddingTypes.Ref formatRef = ref(prepared.path("formatRef"));
        assertNotNull(templateRef, prepared.toString());
        assertNotNull(formatRef, prepared.toString());
        ObjectNode exportRequest = json.createObjectNode().set("manuscriptRef", json.valueToTree(revisedManuscriptRef));
        exportRequest.set("templateRef", json.valueToTree(templateRef));
        exportRequest.set("formatRef", json.valueToTree(formatRef));
        exportRequest.put("mode", "candidate");
        JsonNode exportDispatch = command(projectId, currentProjectRef(projectId), "DISPATCH_EXPORT", exportRequest,
                ownerToken, 200).path("data");
        assertEquals("QUEUED", exportDispatch.path("status").asText(), exportDispatch.toString());
        Claim exportClaim = tasks.claimDue(Instant.now(), "e2e-export", 1).getFirst();
        assertEquals(writer.getId().toString(), exportClaim.agentId());
        activeClaim.set(exportClaim);
        BiddingTypes.Execution exportExecution = executeAndAssertPinnedFiles(exportClaim);
        assertNull(exportExecution.failure(), "export task failed from persisted attempt pin and real renderer/tool path: "
                + modelTrace.stream().filter(line -> line.contains(exportClaim.attemptId())).toList());
        tasks.complete(exportClaim, exportExecution);
        assertEquals("SUCCEEDED", jdbc.queryForObject("SELECT status FROM mate_bidding_task WHERE id=?", String.class,
                exportClaim.taskId()));
        JsonNode manifest = exportExecution.payload();
        String artifactId = manifest.path("artifactId").asText();
        assertFalse(artifactId.isBlank(), manifest.toString());
        byte[] storedCandidate = jdbc.queryForObject("SELECT content FROM mate_bidding_artifact WHERE workspace_id=? AND project_id=? AND id=?",
                (rs, row) -> rs.getBytes(1), workspace, projectId, artifactId);
        MockHttpServletRequestBuilder candidateRequest = MockMvcRequestBuilders.get(
                        "/api/v1/bidding/projects/" + projectId + "/artifacts/" + artifactId + "/content")
                .param("mode", "candidate").header("Authorization", ownerToken).header("X-Workspace-Id", workspace);
        var candidateResponse = mvc.perform(candidateRequest).andReturn().getResponse();
        assertEquals(200, candidateResponse.getStatus());
        byte[] candidateBytes = candidateResponse.getContentAsByteArray();
        assertArrayEquals(storedCandidate, candidateBytes, "candidate download must preserve original generated bytes");
        java.nio.file.Path acceptanceDocx = java.nio.file.Path.of("target/bidding/task5-candidate.docx");
        java.nio.file.Files.createDirectories(acceptanceDocx.getParent());
        java.nio.file.Files.write(acceptanceDocx, candidateBytes);

        JsonNode approvalContext = api("GET", "/api/v1/bidding/projects/" + projectId + "/artifacts/" + artifactId
                + "/approval-context", ownerToken, null, 200).path("data");
        assertEquals("READY", approvalContext.path("status").asText(), approvalContext.toString());
        ObjectNode approval = json.createObjectNode().put("artifactId", artifactId)
                .put("digest", approvalContext.path("digest").asText());
        approval.set("manuscriptRef", approvalContext.path("manuscriptRef").deepCopy());
        approval.set("templateRef", approvalContext.path("templateRef").deepCopy());
        approval.set("formatRef", approvalContext.path("formatRef").deepCopy());
        approval.set("reviewRef", approvalContext.path("reviewRef").deepCopy());
        approval.putObject("inspection").put("opened", true).put("layoutChecked", true)
                .put("reason", "已检查候选 DOCX 的页面布局");
        JsonNode approved = command(projectId, ref(approvalContext.path("artifactRef")), "APPROVE_ARTIFACT", approval,
                ownerToken, 200).path("data");
        assertEquals("APPROVED", approved.path("status").asText(), approved.toString());
        MockHttpServletRequestBuilder formalRequest = MockMvcRequestBuilders.get(
                        "/api/v1/bidding/projects/" + projectId + "/artifacts/" + artifactId + "/content")
                .param("mode", "formal").header("Authorization", ownerToken).header("X-Workspace-Id", workspace);
        var formalResponse = mvc.perform(formalRequest).andReturn().getResponse();
        assertEquals(200, formalResponse.getStatus());
        byte[] formalBytes = formalResponse.getContentAsByteArray();
        assertArrayEquals(storedCandidate, formalBytes, "formal approval serves the exact stored candidate bytes");
        String sha256 = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(formalBytes));
        assertEquals(manifest.path("digest").asText(), sha256);
        Map<String,String> roles=Map.of(analyst.getId().toString(),"analyst",writer.getId().toString(),"writer",reviewer.getId().toString(),"reviewer");
        writeRunEvidence(projectId,pins,roles,manifest,artifactId,candidateResponse.getStatus(),formalResponse.getStatus(),
                java.util.Arrays.equals(storedCandidate,candidateBytes),java.util.Arrays.equals(storedCandidate,formalBytes));
    }

    private JsonNode completeReviewTasks(String projectId, BiddingTypes.Ref manuscriptRef, String worker,
            boolean expectFinding) throws Exception {
        ObjectNode payload = json.createObjectNode().set("manuscriptRef", json.valueToTree(manuscriptRef));
        JsonNode dispatch = command(projectId, manuscriptRef, "DISPATCH_REVIEW", payload, ownerToken, 200).path("data");
        assertEquals(2, dispatch.path("tasks").size(), dispatch.toString());
        for (int index = 0; index < 2; index++) {
            List<Claim> claims = tasks.claimDue(Instant.now(), worker + "-" + index, 1);
            assertEquals(1, claims.size(), "review task " + index + " should be claimable; group=" + dispatch
                    + " statuses=" + jdbc.queryForList("SELECT id,status FROM mate_bidding_task WHERE project_id=?", projectId));
            Claim claim = claims.getFirst();
            assertEquals(pinnedSkillName(claim), "bidding-technical-review");
            activeClaim.set(claim);
            BiddingTypes.Execution execution = executeAndAssertPinnedFiles(claim);
            assertNull(execution.failure(), "review attempt " + claim.attemptId() + " failed; trace=" + modelTraceFor(claim));
            tasks.complete(claim, execution);
            String status = jdbc.queryForObject("SELECT status FROM mate_bidding_task WHERE id=?", String.class,
                    claim.taskId());
            assertEquals("SUCCEEDED", status, "review task must be durably accepted before reading findings");
        }
        JsonNode review = api("GET", "/api/v1/bidding/projects/" + projectId + "/review", ownerToken, null, 200)
                .path("data");
        assertEquals(expectFinding ? 1 : 0, review.path("findings").size(), review.toString());
        return review;
    }

    private void completeClaimForAgent(String agentId, String worker) {
        Claim claim = tasks.claimDue(Instant.now(), worker, 1).getFirst();
        assertEquals(agentId, claim.agentId());
        activeClaim.set(claim);
        BiddingTypes.Execution execution = executeAndAssertPinnedFiles(claim);
        assertNull(execution.failure(), "attempt " + claim.attemptId() + " failed; trace=" + modelTraceFor(claim));
        tasks.complete(claim, execution);
        assertEquals("SUCCEEDED", jdbc.queryForObject("SELECT status FROM mate_bidding_task WHERE id=?",
                String.class, claim.taskId()));
    }

    private String modelTraceFor(Claim claim) {
        return "claim=" + claim.attemptId() + " pin=" + pinnedSkillName(claim);
    }

    private JsonNode findFinding(JsonNode review, String findingId) {
        for (JsonNode item : review.path("findings"))
            if (findingId.equals(item.path("finding").path("id").asText())) return item;
        return null;
    }

    private JsonNode createProject(String name) throws Exception {
        JsonNode response = api("POST", "/api/v1/bidding/projects", ownerToken,
                Map.of("operationId", UUID.randomUUID().toString(), "name", name, "lotName", "一标段"), 200);
        return response.path("data");
    }

    private JsonNode createProjectSource(String projectName, String sourceText) throws Exception {
        JsonNode project = createProject(projectName);
        String projectId = project.path("id").asText();
        MockMultipartFile sourceFile = new MockMultipartFile("file", "foreign-tender.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", tenderDocx(sourceText));
        var response = mvc.perform(MockMvcRequestBuilders.multipart(
                        "/api/v1/bidding/projects/" + projectId + "/sources")
                .file(sourceFile).param("operationId", UUID.randomUUID().toString()).param("sourceKind", "TENDER")
                .header("Authorization", ownerToken).header("X-Workspace-Id", workspace))
                .andReturn().getResponse();
        assertEquals(200, response.getStatus(), response.getContentAsString());
        JsonNode uploaded = json.readTree(response.getContentAsString()).path("data");
        assertEquals(1, sources.readPending(2));
        JsonNode parsed = api("GET", "/api/v1/bidding/projects/" + projectId + "/sources", ownerToken, null, 200)
                .path("data").get(0);
        assertEquals("READY", parsed.path("readStatus").asText());
        ObjectNode selection = json.createObjectNode().putNull("expectedSourceSetRef");
        selection.putArray("sourceRefs").add(uploaded.path("ref"));
        selection.putArray("exclusions");
        command(projectId, currentProjectRef(projectId), "CONFIRM_SOURCE_SET", selection, ownerToken, 200);
        ObjectNode result = json.createObjectNode();
        result.put("projectId", projectId);
        result.set("ref", uploaded.path("ref"));
        result.set("blocks", parsed.path("blocks"));
        return result;
    }

    private BiddingTypes.Ref currentProjectRef(String projectId) throws Exception {
        return ref(api("GET", "/api/v1/bidding/projects/" + projectId, ownerToken, null, 200).path("data"));
    }

    private Map<String, Long> activeBiddingSkills() throws Exception {
        JsonNode page = api("GET", "/api/v1/skills?page=1&size=200&enabled=true&lifecycleState=active",
                ownerToken, null, 200).path("data");
        Map<String, Long> ids = new java.util.HashMap<>();
        for (JsonNode item : page.path("records")) {
            String name = item.path("name").asText();
            if (name.startsWith("bidding-")) ids.put(name, item.path("id").asLong());
        }
        for (String required : List.of("bidding-tender-profile", "bidding-elimination-analysis",
                "bidding-requirement-analysis", "bidding-scoring-analysis", "bidding-outline-planning",
                "bidding-technical-writing", "bidding-technical-review", "bidding-document-export")) {
            assertTrue(ids.containsKey(required), "active bundled skill must be discoverable: " + required);
        }
        return ids;
    }

    private AgentEntity createAgent(String role) throws Exception {
        jdbc.update("UPDATE mate_model_provider SET api_key='test-key', enabled=TRUE, chat_model='OpenAIChatModel' WHERE provider_id='openai'");
        providerPool.add("openai");
        long modelId = Math.abs(UUID.randomUUID().getMostSignificantBits());
        String modelName = "bidding-e2e-" + modelId;
        jdbc.update("INSERT INTO mate_model_config(id,name,provider,model_name,model_type,enabled,is_default,create_time,update_time,deleted) VALUES(?,?, 'openai',?,'chat',TRUE,FALSE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)",
                modelId, "Bidding E2E " + role, modelName);
        ObjectNode body = json.createObjectNode().put("name", "E2E " + role + " " + UUID.randomUUID())
                .put("description", "Controlled full application acceptance employee")
                .put("agentType", "react").put("runtimeType", "native")
                .put("systemPrompt", "Return the pinned structured output only")
                .put("maxIterations", 8).put("modelName", modelName);
        JsonNode response = api("POST", "/api/v1/agents", ownerToken, body, 200).path("data");
        return json.convertValue(response, AgentEntity.class);
    }

    private void bindSkills(AgentEntity agent, List<String> names, Map<String, Long> pins) throws Exception {
        List<Long> ids = names.stream().map(name -> {
            Long id = pins.get(name);
            assertNotNull(id, "missing active skill " + name);
            return id;
        }).toList();
        api("PUT", "/api/v1/agents/" + agent.getId() + "/skills", ownerToken, ids, 200);
    }

    private byte[] tenderDocx() throws Exception {
        return tenderDocx("投标人应提交技术方案，并在交付验收前提供接口性能验证记录。");
    }

    private byte[] tenderDocx(String sourceText) throws Exception {
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("技术招标文件 QM-E2E-01");
            document.createParagraph().createRun().setText(sourceText);
            document.write(bytes);
            return bytes.toByteArray();
        }
    }

    private void installControlledProvider(AtomicReference<Claim> activeClaim, BiddingTypes.Ref sourceRef,
            String blockId, String quote, BiddingTypes.Ref foreignRef, String foreignBlockId,
            String foreignQuote, AtomicBoolean forgeFirstEvidence, AtomicReference<String> forgedValidationPath,
            List<String> modelTrace) {
        ChatModel fake = mock(ChatModel.class);
        java.util.concurrent.ConcurrentMap<String, AtomicInteger> rounds = new java.util.concurrent.ConcurrentHashMap<>();
        when(fake.stream(any(Prompt.class))).thenAnswer(invocation -> {
            Claim claim = activeClaim.get();
            assertNotNull(claim, "runtime claim must be set before the controlled provider is called");
            int round = rounds.computeIfAbsent(claim.attemptId(), ignored -> new AtomicInteger()).incrementAndGet();
            observePinnedFileResponses(claim, invocation.getArgument(0, Prompt.class));
            String skill = pinnedSkillName(claim);
            modelTrace.add("attempt=" + claim.attemptId() + " skill=" + skill + " round=" + round + " prompt="
                    + invocation.getArgument(0, Prompt.class).getInstructions().stream().map(message -> {
                        if (message instanceof ToolResponseMessage response) return response.getResponses().stream()
                                .map(item -> item.name() + "=" + item.responseData().substring(0, Math.min(1000, item.responseData().length())))
                                .toList().toString();
                        if (message instanceof AssistantMessage assistant) return "assistant-tools="
                                + assistant.getToolCalls().stream().map(AssistantMessage.ToolCall::name).toList();
                        return message.getClass().getSimpleName();
                    }).toList());
            if (round == 1) {
                AssistantMessage load = AssistantMessage.builder().content("").toolCalls(List.of(
                        new AssistantMessage.ToolCall("load-" + claim.attemptId(), "function", "load_skill",
                                json.writeValueAsString(Map.of("skillName", skill, "filePath", "SKILL.md"))),
                        new AssistantMessage.ToolCall("schema-" + claim.attemptId(), "function", "readSkillFile",
                                json.writeValueAsString(Map.of("skillName", skill, "filePath", "output.schema.json"))))).build();
                return Flux.just(new ChatResponse(List.of(new Generation(load))));
            }
            if (round == 2) {
                if ("bidding-document-export".equals(skill)) {
                    ObjectNode args = json.createObjectNode();
                    args.put("manuscriptRef", claim.input().path("manuscriptRef").toString());
                    args.put("templateRef", claim.input().path("templateRef").toString());
                    args.put("formatRef", claim.input().path("formatRef").toString());
                    AssistantMessage export = AssistantMessage.builder().content("").toolCalls(List.of(
                            new AssistantMessage.ToolCall("export-" + claim.attemptId(), "function", "bidding_export_document", args.toString()))).build();
                    return Flux.just(new ChatResponse(List.of(new Generation(export))));
                }
                if (claim.input().path("blocks").isArray() && !claim.input().path("blocks").isEmpty()) {
                    ArrayNode requests = json.createArrayNode();
                    for (JsonNode assigned : claim.input().path("blocks")) {
                        requests.addObject().put("sourceId", assigned.path("sourceId").asText())
                                .put("version", assigned.path("version").asLong()).put("blockId", assigned.path("id").asText());
                    }
                    AssistantMessage read = AssistantMessage.builder().content("").toolCalls(List.of(
                            new AssistantMessage.ToolCall("sources-" + claim.attemptId(), "function", "bidding_read_sources",
                                    json.writeValueAsString(Map.of("requestsJson", requests.toString()))))).build();
                    return Flux.just(new ChatResponse(List.of(new Generation(read))));
                }
            }
            if ("bidding-document-export".equals(skill)) {
                ObjectNode manifest = exportManifestFromToolResponse(invocation.getArgument(0));
                return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage(manifest.toString())))));
            }
            if ("bidding-outline-planning".equals(skill)) {
                ObjectNode output = outlineOutput(claim);
                return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage(output.toString())))));
            }
            if ("bidding-technical-writing".equals(skill)) {
                ObjectNode output = writingOutput(claim);
                return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage(output.toString())))));
            }
            if ("bidding-technical-review".equals(skill)) {
                ObjectNode output = reviewOutput(claim);
                return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage(output.toString())))));
            }
            boolean forge = forgeFirstEvidence.compareAndSet(true, false);
            ObjectNode output = analysisOutput(claim, forge ? foreignRef : sourceRef,
                    forge ? foreignBlockId : blockId, forge ? foreignQuote : quote, forge);
            modelTrace.add("attempt=" + claim.attemptId() + " pin=" + claim.skill().skillId() + "/" + skill
                    + " finalChars=" + output.toString().length() + " pinnedContract="
                    + inspectPinnedContract(claim, output));
            if (forge) forgedValidationPath.set(evidencePath(skill));
            return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage(output.toString())))));
        });
        when(providerFactory.buildFor(any(), any())).thenReturn(fake);
    }

    private void observePinnedFileResponses(Claim claim,Prompt prompt) {
        java.util.Set<String> files=loadedPinnedFiles.computeIfAbsent(claim.attemptId(),ignored->java.util.concurrent.ConcurrentHashMap.newKeySet());
        prompt.getInstructions().stream().filter(ToolResponseMessage.class::isInstance).map(ToolResponseMessage.class::cast)
                .flatMap(message->message.getResponses().stream()).forEach(response->{
                    if("load_skill".equals(response.name()))files.add("SKILL.md");
                    if("readSkillFile".equals(response.name()))files.add("output.schema.json");
                });
    }

    private BiddingTypes.Execution executeAndAssertPinnedFiles(Claim claim) {
        BiddingTypes.Execution execution=runtime.execute(claim);
        java.util.Set<String> loaded=loadedPinnedFiles.getOrDefault(claim.attemptId(),java.util.Set.of());
        assertTrue(loaded.contains("SKILL.md")&&loaded.contains("output.schema.json"),
                "real runtime must return the claim's pinned skill/schema to the next provider turn: attempt="+claim.attemptId()+" trace="+modelTraceFor(claim));
        return execution;
    }

    private void writeRunEvidence(String projectId,Map<String,Long> pins,Map<String,String> roles,JsonNode manifest,
            String artifactId,int candidateStatus,int formalStatus,boolean candidateEqualsDatabase,boolean formalEqualsDatabase)throws Exception {
        ObjectNode evidence=json.createObjectNode().put("evidenceType","controlled-provider engineering E2E; synthetic tender; not human-reviewed gold")
                .put("projectId",projectId).put("workspaceId",workspace);
        ArrayNode tasksEvidence=evidence.putArray("tasks");
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT t.id,t.agent_id,t.skill_package_id,t.status,p.skill_id,p.version,p.digest FROM mate_bidding_task t JOIN mate_bidding_skill_package p ON p.id=t.skill_package_id WHERE t.workspace_id=? AND t.project_id=? ORDER BY t.created_at,t.id",workspace,projectId);
        Map<Long,String> namesById=new java.util.HashMap<>();pins.forEach((name,id)->namesById.put(id,name));
        for(Map<String,Object> row:rows) {
            String taskId=String.valueOf(row.get("id")),agentId=String.valueOf(row.get("agent_id"));String packageSkill=String.valueOf(row.get("skill_id"));
            long numericSkillId=Long.parseLong(packageSkill);String name=namesById.getOrDefault(numericSkillId,"unknown");
            ObjectNode task=tasksEvidence.addObject().put("taskId",taskId).put("role",roles.getOrDefault(agentId,"unknown"))
                    .put("agentId",agentId).put("skillId",numericSkillId).put("skillName",name)
                    .put("skillVersion",String.valueOf(row.get("version"))).put("pinnedDigest",String.valueOf(row.get("digest")))
                    .put("status",String.valueOf(row.get("status")));
            ArrayNode attempts=task.putArray("attempts");
            List<Map<String,Object>> attemptRows=jdbc.queryForList("SELECT id,attempt_no,state,error_json,tool_receipts_json FROM mate_bidding_attempt WHERE workspace_id=? AND project_id=? AND task_id=? ORDER BY attempt_no",workspace,projectId,taskId);
            for(Map<String,Object> attemptRow:attemptRows) {
                String attemptId=String.valueOf(attemptRow.get("id"));ObjectNode attempt=attempts.addObject().put("attemptId",attemptId)
                        .put("attemptNo",((Number)attemptRow.get("attempt_no")).intValue()).put("state",String.valueOf(attemptRow.get("state")));
                String error=(String)attemptRow.get("error_json");if(error!=null)attempt.put("errorCode",json.readTree(error).path("code").asText());
                String receipts=(String)attemptRow.get("tool_receipts_json");ArrayNode tools=attempt.putArray("observedToolNames");
                if(receipts!=null&&json.readTree(receipts).isArray())json.readTree(receipts).forEach(receipt->{String tool=receipt.path("tool").asText();if(!tool.isBlank()&&!tools.toString().contains("\""+tool+"\""))tools.add(tool);});
                java.util.Set<String> loaded=loadedPinnedFiles.getOrDefault(attemptId,java.util.Set.of());
                attempt.put("pinnedSkillMdObservedByNextModelTurn",loaded.contains("SKILL.md"));
                attempt.put("pinnedOutputSchemaObservedByNextModelTurn",loaded.contains("output.schema.json"));
            }
        }
        ArrayNode revisions=evidence.putArray("persistedRevisions");
        jdbc.query("SELECT kind,object_id,version,digest,status FROM mate_bidding_revision WHERE workspace_id=? AND project_id=? ORDER BY created_at,kind,object_id,version",rs->{
            while(rs.next())revisions.addObject().put("kind",rs.getString("kind")).put("id",rs.getString("object_id"))
                    .put("version",rs.getLong("version")).put("digest",rs.getString("digest")).put("status",rs.getString("status"));
            return null;
        },workspace,projectId);
        ObjectNode artifact=evidence.putObject("artifact").put("artifactId",artifactId).put("digest",manifest.path("digest").asText())
                .put("byteSize",manifest.path("byteSize").asLong()).put("candidateHttpStatus",candidateStatus).put("formalHttpStatus",formalStatus)
                .put("candidateBytesEqualDatabase",candidateEqualsDatabase).put("formalBytesEqualDatabase",formalEqualsDatabase);
        artifact.put("databaseDigest",jdbc.queryForObject("SELECT digest FROM mate_bidding_artifact WHERE workspace_id=? AND project_id=? AND id=?",String.class,workspace,projectId,artifactId));
        java.nio.file.Path path=java.nio.file.Path.of("target/bidding/task5-run-evidence.json");java.nio.file.Files.createDirectories(path.getParent());
        java.nio.file.Files.writeString(path,json.writerWithDefaultPrettyPrinter().writeValueAsString(evidence));
    }

    private String inspectPinnedContract(Claim claim, ObjectNode output) {
        try {
            JsonNode schema = json.readTree(claim.skill().files().get("output.schema.json"));
            var parse = BiddingEmployeeRuntime.class.getDeclaredMethod("parseOutputJson", String.class);
            var validSchema = BiddingEmployeeRuntime.class.getDeclaredMethod("validSchema", JsonNode.class);
            var validAgainstSchema = BiddingEmployeeRuntime.class.getDeclaredMethod(
                    "validAgainstSchema", JsonNode.class, JsonNode.class);
            parse.setAccessible(true); validSchema.setAccessible(true); validAgainstSchema.setAccessible(true);
            JsonNode parsed = (JsonNode) parse.invoke(null, output.toString());
            boolean schemaValid = (boolean) validSchema.invoke(null, schema);
            boolean matches = parsed != null && schemaValid && (boolean) validAgainstSchema.invoke(null, parsed, schema);
            return "parse=" + (parsed != null) + ", schema=" + schemaValid + ", matches=" + matches
                    + ", mismatch=" + (parsed == null || !schemaValid ? "not-evaluated" : explainSchemaMismatch(parsed, schema, ""))
                    + ", required=" + schema.path("required");
        } catch (ReflectiveOperationException | java.io.IOException e) {
            throw new AssertionError("Cannot diagnose the actual claimed schema", e);
        }
    }

    private String pinnedSkillName(Claim claim) {
        String skillFile = claim.skill().files().get("SKILL.md");
        assertNotNull(skillFile, "the active claim must carry its pinned SKILL.md");
        return skillFile.lines().map(String::strip)
                .filter(line -> line.startsWith("name:")).map(line -> line.substring("name:".length()).strip())
                .findFirst().orElseThrow(() -> new AssertionError("pinned SKILL.md must declare its exact name"));
    }

    private String explainSchemaMismatch(JsonNode value, JsonNode schema, String path) {
        JsonNode type = schema.path("type");
        if (type.isTextual() && !matchesSimpleType(value, type.asText())) return path + " type=" + type.asText();
        if (type.isArray() && java.util.stream.StreamSupport.stream(type.spliterator(), false)
                .noneMatch(candidate -> matchesSimpleType(value, candidate.asText()))) return path + " type=" + type;
        JsonNode required = schema.path("required");
        if (required.isArray() && value.isObject()) for (JsonNode field : required)
            if (!value.has(field.asText())) return path + "/" + field.asText() + " missing";
        JsonNode properties = schema.path("properties");
        if (properties.isObject() && value.isObject()) {
            var fields = properties.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (value.has(field.getKey())) {
                    String nested = explainSchemaMismatch(value.get(field.getKey()), field.getValue(), path + "/" + field.getKey());
                    if (nested != null) return nested;
                }
            }
        }
        if (schema.path("additionalProperties").isBoolean() && !schema.path("additionalProperties").asBoolean()
                && value.isObject()) {
            var names = value.fieldNames();
            while (names.hasNext()) { String name = names.next(); if (!properties.has(name)) return path + "/" + name + " additional"; }
        }
        if (schema.path("items").isObject() && value.isArray()) for (int i = 0; i < value.size(); i++) {
            String nested = explainSchemaMismatch(value.get(i), schema.path("items"), path + "/" + i);
            if (nested != null) return nested;
        }
        if (value.isTextual()) {
            int length = value.asText().codePointCount(0, value.asText().length());
            if (schema.has("minLength") && length < schema.path("minLength").asInt()) return path + " minLength";
            if (schema.has("maxLength") && length > schema.path("maxLength").asInt()) return path + " maxLength";
            if (schema.has("pattern") && !java.util.regex.Pattern.compile(schema.path("pattern").asText()).matcher(value.asText()).find()) return path + " pattern";
        }
        if (value.isArray()) {
            if (schema.has("minItems") && value.size() < schema.path("minItems").asInt()) return path + " minItems";
            if (schema.has("maxItems") && value.size() > schema.path("maxItems").asInt()) return path + " maxItems";
        }
        if (schema.has("const") && !schema.path("const").equals(value)) return path + " const";
        if (schema.path("enum").isArray() && java.util.stream.StreamSupport.stream(schema.path("enum").spliterator(), false).noneMatch(value::equals)) return path + " enum";
        return null;
    }

    private boolean matchesSimpleType(JsonNode value, String type) {
        return switch (type) {
            case "object" -> value.isObject(); case "array" -> value.isArray(); case "string" -> value.isTextual();
            case "number" -> value.isNumber(); case "integer" -> value.isIntegralNumber(); case "boolean" -> value.isBoolean();
            case "null" -> value.isNull(); default -> false;
        };
    }

    private ObjectNode outlineOutput(Claim claim) {
        ObjectNode output = json.createObjectNode().put("schemaVersion", "1");
        ObjectNode chapter = output.putArray("chapters").addObject().put("id", "c1").putNull("parentId")
                .put("order", 0).put("title", "技术方案").put("instructions", "逐项响应已确认的技术要求，并说明交付验收条件。");
        ArrayNode mandatory = chapter.putArray("mandatoryOutlineRefs");
        for (JsonNode item : claim.input().path("profile").path("mandatoryOutline")) mandatory.add(item.path("id").asText());
        ArrayNode requirements = chapter.putArray("requirementRefs");
        for (JsonNode item : claim.input().path("requirements")) if ("TECHNICAL".equals(item.path("category").asText())) requirements.add(item.path("id").asText());
        ArrayNode scores = chapter.putArray("scoringRefs");
        for (JsonNode item : claim.input().path("criteria")) scores.add(item.path("id").asText());
        chapter.putArray("materialRefs"); output.putArray("unmappedItems"); output.putArray("warnings");
        return output;
    }

    private ObjectNode writingOutput(Claim claim) {
        ObjectNode output = json.createObjectNode().put("schemaVersion", "1");
        output.putObject("chapter").put("chapterId", claim.input().path("chapterId").asText())
                .putArray("blocks").addObject().put("type", "paragraph").put("text", "本方案覆盖系统接口设计、验证步骤及交付验收安排。");
        ArrayNode responses = output.putArray("responses"), citations = output.putArray("citations");
        for (JsonNode requirement : claim.input().path("requirements")) {
            String requirementId = requirement.path("id").asText();
            responses.addObject().put("requirementRef", requirementId).put("status", "RESPONDED")
                    .put("text", "按已确认条款实施，并在交付验收前提交对应的接口性能验证记录。");
            JsonNode evidence = requirement.path("evidenceRefs").path(0);
            if (evidence.isObject()) {
                ObjectNode citation = citations.addObject().put("requirementRef", requirementId);
                citation.put("sourceId", evidence.path("sourceId").asText()).put("version", evidence.path("version").asLong())
                        .put("blockId", evidence.path("blockId").asText()).put("quote", evidence.path("quote").asText());
            }
        }
        output.putArray("missingMaterials"); output.putArray("unresolvedItems"); output.putArray("warnings");
        return output;
    }

    private ObjectNode reviewOutput(Claim claim) {
        ObjectNode output = json.createObjectNode().put("schemaVersion", "1");
        ArrayNode findings = output.putArray("findings");
        String target = claim.input().path("_biddingTargetId").asText();
        if (target.contains(":chapter:") && claim.input().path("manuscriptRef").path("version").asLong() == 1) {
            JsonNode chapter = claim.input().path("chapters").get(0);
            JsonNode evidence = StreamSupport.stream(claim.input().path("evidenceSnapshot").path("blocks").spliterator(), false)
                    .filter(block -> block.path("text").asText().contains("交付验收前提供接口性能验证记录"))
                    .findFirst().orElse(null);
            JsonNode requirement = claim.input().path("requirements").get(0);
            ObjectNode finding = findings.addObject().put("id", "F-E2E-TECH").put("severity", "MAJOR")
                    .put("category", "TECHNICAL_GAP").put("description", "需明确接口性能验证记录的验收方法")
                    .put("recommendation", "补充可执行的性能验证步骤和交付验收说明");
            finding.putArray("chapterRefs").add(chapter.path("chapterRef").deepCopy());
            finding.putArray("requirementRefs").add(requirement == null ? "" : requirement.path("id").asText());
            if (evidence.isObject()) finding.putArray("evidenceRefs").addObject()
                    .put("sourceId", evidence.path("sourceId").asText()).put("version", evidence.path("version").asLong())
                    .put("blockId", evidence.path("blockId").asText()).put("quote", evidence.path("text").asText().substring(0,
                            Math.min(80, evidence.path("text").asText().length())));
            else finding.putArray("evidenceRefs");
        }
        ObjectNode coverage = output.putObject("coverage");
        ArrayNode chapterRefs = coverage.putArray("chapterRefs");
        for (JsonNode chapter : claim.input().path("chapters")) chapterRefs.add(chapter.path("chapterRef").deepCopy());
        ArrayNode requirementRefs = coverage.putArray("requirementRefs");
        for (JsonNode requirement : claim.input().path("requirements")) requirementRefs.add(requirement.path("id").asText());
        coverage.put("crossChapterReviewed", target.endsWith(":cross"));
        output.putArray("limitations"); output.putArray("warnings");
        return output;
    }

    private ObjectNode exportManifestFromToolResponse(Prompt prompt) throws Exception {
        for (var message : prompt.getInstructions()) if (message instanceof ToolResponseMessage toolResponses) {
            for (var response : toolResponses.getResponses()) if ("bidding_export_document".equals(response.name())) {
                JsonNode parsed = json.readTree(response.responseData());
                return (ObjectNode) parsed;
            }
        }
        throw new AssertionError("Export final result must echo the actual bidding_export_document response");
    }

    private static String evidencePath(String skill) {
        return switch (skill) {
            case "bidding-tender-profile" -> "/mandatoryOutline/0/evidenceRefs/0";
            case "bidding-elimination-analysis" -> "/items/0/evidenceRefs/0";
            case "bidding-requirement-analysis" -> "/requirements/0/evidenceRefs/0";
            case "bidding-scoring-analysis" -> "/criteria/0/evidenceRefs/0";
            default -> throw new AssertionError("Unexpected analysis skill: " + skill);
        };
    }

    private ObjectNode analysisOutput(Claim claim, BiddingTypes.Ref sourceRef, String blockId, String quote,
            boolean forgeEvidence) {
        String skill = claim.input().path("skillId").asText();
        String sourceId = forgeEvidence ? "different-project-source" : sourceRef.id();
        var evidence = json.createObjectNode().put("sourceId", sourceId).put("version", sourceRef.version())
                .put("blockId", blockId).put("quote", quote);
        ObjectNode output = json.createObjectNode().put("schemaVersion", "1");
        ArrayNode assigned = output.putObject("coverage").putArray("processedBlockIds");
        for (JsonNode block : claim.input().path("blocks")) assigned.add(block.path("id").asText());
        output.path("coverage").withArray("unprocessedBlockIds");
        output.putArray("warnings");
        if ("bidding-tender-profile".equals(skill)) {
            output.putObject("basicInfo").put("project", "QM-E2E-01").put("tenderer", "演示招标单位").put("lot", "一标段");
            output.putArray("deadlines"); output.putArray("deliveryConditions");
            output.putArray("mandatoryOutline").addObject().put("name", "接口性能验证").put("value", "交付验收前提供接口性能验证记录")
                    .putNull("reason").putArray("evidenceRefs").add(evidence);
            output.putArray("formatRequirements"); output.putArray("unknowns");
        } else if ("bidding-elimination-analysis".equals(skill)) {
            ObjectNode item=output.putArray("items").addObject().put("id", "E-E2E").put("text", "提交性能验证记录");
            item.put("scope", "技术标").putNull("trigger"); item.putArray("evidenceRefs").add(evidence); item.putArray("unknowns");
        } else if ("bidding-requirement-analysis".equals(skill)) {
            var requirement = output.putArray("requirements").addObject().put("id", "R-E2E")
                    .put("text", "提交接口性能验证记录").put("category", "TECHNICAL").putNull("acceptance");
            requirement.putArray("constraints").add("提交性能测试记录");
            requirement.putArray("evidenceRefs").add(evidence);
            requirement.putArray("unknowns");
        } else if ("bidding-scoring-analysis".equals(skill)) {
            var criterion = output.putArray("criteria").addObject().put("id", "S-E2E").putNull("parentId")
                    .put("title", "接口性能验证记录").put("score", "10").put("unit", "分")
                    .put("rule", "按提交的性能记录评审").put("requiredProof", "性能测试记录");
            criterion.putArray("evidenceRefs").add(evidence);
            ObjectNode total=output.putArray("totalChecks").addObject().put("name", "总分核验");
            total.set("criterionIds",json.valueToTree(List.of("S-E2E")));
            total.put("statedTotal", "10").put("calculatedTotal", "10").put("difference", "0");
            total.putArray("evidenceRefs").add(evidence);
        } else {
            throw new AssertionError("Unexpected analysis skill: " + skill);
        }
        var coverage = output.putObject("coverage");
        var processed = coverage.putArray("processedBlockIds");
        for (JsonNode block : claim.input().path("blocks")) processed.add(block.path("id").asText());
        coverage.putArray("unprocessedBlockIds");
        output.putArray("warnings");
        return output;
    }

    private JsonNode command(String projectId, BiddingTypes.Ref expected, String action, ObjectNode payload,
            String token, int status) throws Exception {
        ObjectNode body = json.createObjectNode().put("operationId", UUID.randomUUID().toString());
        body.set("expected", json.valueToTree(expected));
        body.put("action", action).set("payload", payload);
        return api("POST", "/api/v1/bidding/projects/" + projectId + "/commands", token, body, status);
    }

    private JsonNode api(String method, String path, String token, Object body, int expectedStatus) throws Exception {
        MockHttpServletRequestBuilder request = MockMvcRequestBuilders.request(
                org.springframework.http.HttpMethod.valueOf(method), path)
                .header("X-Workspace-Id", workspace)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON);
        if (token != null) request.header("Authorization", token);
        if (body != null) request.content(json.writeValueAsBytes(body));
        var response = mvc.perform(request).andReturn().getResponse();
        String text = response.getContentAsString();
        assertEquals(expectedStatus, response.getStatus(), text);
        return text.isBlank() ? json.nullNode() : json.readTree(text);
    }

    private AuthAccount createUser(String prefix) {
        UserEntity user = new UserEntity();
        user.setUsername("bidding_e2e_" + prefix + "_" + UUID.randomUUID());
        String password = UUID.randomUUID().toString();
        user.setPassword(password);
        user.setRole("user");
        user.setDeleted(0);
        auth.createUser(user);
        return new AuthAccount(user, password);
    }

    private String login(AuthAccount account) throws Exception {
        JsonNode response = api("POST", "/api/v1/auth/login", null,
                Map.of("username", account.user().getUsername(), "password", account.password()), 200);
        return "Bearer " + response.path("data").path("token").asText();
    }

    private record AuthAccount(UserEntity user, String password) {}

    private BiddingTypes.Ref ref(JsonNode node) {
        return json.convertValue(node.has("kind") ? node : node.path("ref"), BiddingTypes.Ref.class);
    }
}

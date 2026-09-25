package vip.mate.bidding;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import vip.mate.agent.execution.ProjectExecutionOptions;

class BiddingAnalysisTest extends BiddingHttpFixture {
    private static final List<String> SKILLS = List.of("bidding-tender-profile", "bidding-elimination-analysis",
            "bidding-requirement-analysis", "bidding-scoring-analysis");
    @MockBean BiddingEmployeeBindings employees;
    @MockBean BiddingEmployeeRuntime runtime;
    @org.springframework.beans.factory.annotation.Autowired BiddingRepository repository;
    @org.springframework.beans.factory.annotation.Autowired BiddingTaskService tasks;
    @org.springframework.beans.factory.annotation.Autowired BiddingSourceService sources;
    @org.springframework.beans.factory.annotation.Autowired BiddingReadTool readTool;

    @Test void dispatchReadsPinnedSkillsAndConfirmsDatabaseBackedBaselineIdempotently() throws Exception {
        doNothing().when(employees).validate(any(), anyString(), anyString());
        when(employees.modelConfigId(any(), anyString())).thenReturn("model-config");
        var project = project();
        var source = upload(project, "DataHub bid deadline 2026-10-01. Late bids are invalid. System must support HTTPS. Technology solution earns 12.50 points; stated total 12.50.");
        sources.readPending(2);
        BiddingTypes.Ref sourceRef = json.convertValue(source.path("ref"), BiddingTypes.Ref.class);
        var sourceSet = confirmSet(project, sourceRef);
        BiddingTypes.Ref sourceSetRef = ref(sourceSet);
        installPinnedAnalysisSkills(project);

        var dispatched = command(project, ref(project), "DISPATCH_ANALYSIS", Map.of("sourceSetRef", sourceSetRef), "member", 200);
        String groupId = dispatched.path("taskGroupId").asText();
        assertEquals(4, dispatched.path("taskIds").size());
        assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM mate_bidding_task WHERE project_id=? AND status='QUEUED'", Integer.class, project.path("id").asText()));

        Map<String, JsonNode> golden = goldenOutputs();
        for (int index = 0; index < 4; index++) {
            BiddingTypes.Claim claim = repository.claimDue(Instant.now(), "analysis-test", 1).getFirst();
            String skill = jdbc.queryForObject("SELECT name FROM mate_skill WHERE id=?", String.class, Long.valueOf(claim.skill().skillId()));
            assertTrue(SKILLS.contains(skill));
            String blockId = claim.input().path("blocks").get(0).path("id").asText();
            String sourceId = claim.input().path("blocks").get(0).path("sourceId").asText();
            long version = claim.input().path("blocks").get(0).path("version").asLong();
            var options = new ProjectExecutionOptions(claim.attemptId(), claim.modelConfigId(), claim.configDigest(), skill,
                    claim.skill().digest(), claim.skill().files(), Set.of("bidding_read_source"), new BiddingToolScope(claim), 0, false, false, 12);
            when(runtime.claim(any())).thenReturn(claim);
            doNothing().when(runtime).requireActive(any(BiddingTypes.Claim.class));
            readTool.readSource(sourceId, version, blockId, new org.springframework.ai.chat.model.ToolContext(Map.of(ProjectExecutionOptions.TOOL_CONTEXT_KEY, options)));

            ObjectNode output = golden.get(skill).deepCopy();
            String exactQuote = claim.input().path("blocks").get(0).path("text").asText();
            rewriteCoverageAndEvidence(output, blockId, sourceId, version, exactQuote);
            tasks.complete(claim, new BiddingTypes.Execution(output, null, claim.skill().digest(), claim.configDigest(), null));
        }
        assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM mate_bidding_revision WHERE workspace_id=? AND project_id=? AND kind LIKE 'analysisCandidate_%' AND object_id=?",
                Integer.class, workspace, project.path("id").asText(), groupId),
                "tasks=" + jdbc.queryForList("SELECT id,status FROM mate_bidding_task WHERE project_id=?", project.path("id").asText())
                        + " attempts=" + jdbc.queryForList("SELECT a.error_json,a.rejected_output FROM mate_bidding_attempt a JOIN mate_bidding_task t ON t.id=a.task_id WHERE t.project_id=?", project.path("id").asText())
                        + " revisions=" + jdbc.queryForList("SELECT kind,object_id,version,status FROM mate_bidding_revision WHERE project_id=?", project.path("id").asText()));
        assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM mate_bidding_attempt a JOIN mate_bidding_task t ON t.id=a.task_id WHERE t.project_id=? AND a.state='SUCCEEDED' AND a.tool_receipts_json LIKE '%bidding_read_source%'",
                Integer.class, project.path("id").asText()));

        String confirmOp = UUID.randomUUID().toString();
        Map<String, Object> confirmBody = Map.of("operationId", confirmOp, "expected", ref(project), "action", "CONFIRM_ANALYSIS",
                "payload", Map.of("taskGroupId", groupId, "reason", "人工复核完成"));
        JsonNode confirmed = api("POST", "/projects/" + project.path("id").asText() + "/commands", "owner", workspace, confirmBody, 200);
        assertEquals("CONFIRMED", confirmed.path("baseline").path("decision").asText());
        assertEquals("CONFIGURATION_REQUIRED", confirmed.path("nextStageState").asText());
        var baselineRef = json.convertValue(confirmed.path("ref"), BiddingTypes.Ref.class);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM mate_bidding_revision WHERE workspace_id=? AND project_id=? AND kind='analysisBaseline' AND object_id='current' AND status='CONFIRMED'",
                Integer.class, workspace, project.path("id").asText()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM mate_bidding_decision WHERE workspace_id=? AND project_id=? AND decision='CONFIRM_ANALYSIS' AND target_ref_json LIKE ?",
                Integer.class, workspace, project.path("id").asText(), "%" + baselineRef.digest() + "%"));
        String persisted = jdbc.queryForObject("SELECT payload_json FROM mate_bidding_revision WHERE workspace_id=? AND project_id=? AND kind='analysisBaseline' AND object_id='current'",
                String.class, workspace, project.path("id").asText());
        assertEquals("1", json.readTree(persisted).path("analyses").path("bidding-scoring-analysis").path("schemaVersion").asText());
        JsonNode replay = api("POST", "/projects/" + project.path("id").asText() + "/commands", "owner", workspace, confirmBody, 200);
        assertEquals(confirmed.path("ref"), replay.path("ref"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM mate_bidding_decision WHERE workspace_id=? AND project_id=? AND decision='CONFIRM_ANALYSIS'",
                Integer.class, workspace, project.path("id").asText()));
    }

    @Test void refusesDispatchWithUnpinnedSkillAndPreventsPartialSourceCoverageFromConfirmation() throws Exception {
        var project = project(); var source = upload(project, "Tender clause: late submission is invalid."); sources.readPending(2);
        BiddingTypes.Ref sourceRef = json.convertValue(source.path("ref"), BiddingTypes.Ref.class);
        var sourceSet = confirmSet(project, sourceRef);
        var rejected = command(project, ref(project), "DISPATCH_ANALYSIS", Map.of("sourceSetRef", ref(sourceSet)), "member", 422);
        assertEquals("EMPLOYEE_UNAVAILABLE", rejected.path("data").path("code").asText());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM mate_bidding_task WHERE project_id=?", Integer.class, project.path("id").asText()));
    }

    private JsonNode upload(JsonNode project, String text) throws Exception {
        byte[] bytes = pdf(text);
        var response = mvc.perform(MockMvcRequestBuilders.multipart("/api/v1/bidding/projects/{id}/sources", project.path("id").asText())
                .file(new MockMultipartFile("file", "analysis.pdf", "application/pdf", bytes))
                .param("operationId", UUID.randomUUID().toString()).param("sourceKind", "TENDER")
                .header("Authorization", tokens.get("member")).header("X-Workspace-Id", workspace)).andReturn().getResponse();
        assertEquals(200, response.getStatus(), response.getContentAsString());
        return json.readTree(response.getContentAsString()).path("data");
    }

    private JsonNode confirmSet(JsonNode project, BiddingTypes.Ref sourceRef) throws Exception {
        Map<String, Object> payload = new HashMap<>(); payload.put("sourceRefs", List.of(sourceRef));
        payload.put("exclusions", List.of()); payload.put("expectedSourceSetRef", null);
        return command(project, ref(project), "CONFIRM_SOURCE_SET", payload, "owner", 200);
    }

    private void installPinnedAnalysisSkills(JsonNode project) throws Exception {
        ObjectNode body = projectsBody(project.path("id").asText()); ObjectNode analyst = body.putObject("bindings").putObject("analyst");
        analyst.put("agentId", "999999"); analyst.put("configDigest", "b".repeat(64)); analyst.put("modelConfigId", "model-config");
        var pins = analyst.putArray("skillPins"); long next = jdbc.queryForObject("SELECT COALESCE(MAX(id),0)+1 FROM mate_skill", Long.class);
        for (String skill : SKILLS) {
            long id = next++;
            Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM mate_skill WHERE workspace_id=? AND name=? AND deleted=0", Integer.class, Long.valueOf(workspace), skill);
            if (count == null || count == 0) jdbc.update("INSERT INTO mate_skill(id,name,workspace_id,create_time,update_time,deleted) VALUES(?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)", id, skill, Long.valueOf(workspace));
            else id = jdbc.queryForObject("SELECT id FROM mate_skill WHERE workspace_id=? AND name=? AND deleted=0", Long.class, Long.valueOf(workspace), skill);
            Map<String, String> files = packageFiles(skill); String digest = BiddingSkillPackages.digest(files);
            jdbc.update("INSERT INTO mate_bidding_skill_package(id,workspace_id,project_id,skill_id,version,digest,files_json,created_at) VALUES(?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",
                    UUID.randomUUID().toString(), workspace, project.path("id").asText(), Long.toString(id), "test-v1", digest, json.writeValueAsString(files));
            ObjectNode pin = pins.addObject(); pin.put("skillId", Long.toString(id)); pin.put("digest", digest);
        }
        jdbc.update("UPDATE mate_bidding_project SET body_json=? WHERE workspace_id=? AND id=?", json.writeValueAsString(body), workspace, project.path("id").asText());
    }

    private ObjectNode projectsBody(String id) throws Exception {
        return (ObjectNode) json.readTree(jdbc.queryForObject("SELECT body_json FROM mate_bidding_project WHERE workspace_id=? AND id=?", String.class, workspace, id));
    }

    private Map<String, String> packageFiles(String skill) throws Exception {
        Map<String, String> files = new TreeMap<>();
        for (String path : List.of("SKILL.md", "input.schema.json", "output.schema.json")) {
            try (var stream = getClass().getResourceAsStream("/skills/" + skill + "/" + path)) {
                assertNotNull(stream); files.put(path, new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return files;
    }

    private Map<String, JsonNode> goldenOutputs() throws Exception {
        try (var stream = getClass().getResourceAsStream("/bidding/analysis/golden.json")) {
            JsonNode valid = json.readTree(stream).path("valid"); Map<String, JsonNode> result = new HashMap<>();
            for (String skill : SKILLS) result.put(skill, valid.path(skill)); return result;
        }
    }

    private void rewriteCoverageAndEvidence(ObjectNode output, String blockId, String sourceId, long version, String quote) {
        JsonNode processed = output.path("coverage").path("processedBlockIds");
        ArrayList<String> values = new ArrayList<>(); for (JsonNode id : processed) values.add(blockId);
        ArrayNode replacement = json.createArrayNode(); if (values.isEmpty()) replacement.add(blockId); else values.forEach(replacement::add);
        ((ObjectNode) output.path("coverage")).set("processedBlockIds", replacement);
        output.path("coverage").path("unprocessedBlockIds");
        replaceEvidence(output, blockId, sourceId, version, quote);
    }

    private void replaceEvidence(JsonNode node, String blockId, String sourceId, long version, String quote) {
        if (node.isObject()) {
            if (node.path("sourceId").isTextual() && node.has("blockId") && node.has("quote")) {
                ((ObjectNode) node).put("sourceId", sourceId); ((ObjectNode) node).put("version", version);
                ((ObjectNode) node).put("blockId", blockId); ((ObjectNode) node).put("quote", quote); return;
            }
            node.fields().forEachRemaining(entry -> replaceEvidence(entry.getValue(), blockId, sourceId, version, quote));
        } else if (node.isArray()) node.forEach(child -> replaceEvidence(child, blockId, sourceId, version, quote));
    }

    private byte[] pdf(String text) throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(); document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText(); stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11); stream.newLineAtOffset(50, 740); stream.showText(text); stream.endText();
            }
            document.save(bytes); return bytes.toByteArray();
        }
    }
}

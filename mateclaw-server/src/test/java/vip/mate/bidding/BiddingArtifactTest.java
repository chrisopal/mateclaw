package vip.mate.bidding;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.test.context.TestPropertySource;
import vip.mate.agent.execution.ProjectExecutionOptions;
import vip.mate.MateClawApplication;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.messages.AssistantMessage;
import reactor.core.publisher.Flux;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import java.util.zip.ZipEntry;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@SpringBootTest(classes=MateClawApplication.class,webEnvironment=SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties={"spring.datasource.url=jdbc:h2:mem:bidding_artifact_${random.uuid};MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1","spring.ai.dashscope.api-key=test-key","spring.main.web-application-type=none","mateclaw.semantic.enabled=false","mateclaw.presales.enabled=false","mateclaw.bidding.enabled=true","mateclaw.bidding.scheduler-enabled=false","mateclaw.bidding.artifacts.max-bytes=10000","mateclaw.bidding.artifacts.project-capacity-bytes=18000"})
class BiddingArtifactTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired BiddingArtifactService artifacts;
    @SpyBean BiddingDocxRenderer renderer;
    @Autowired BiddingExportTool exportTool;
    @Autowired BiddingTaskService tasks;
    @Autowired BiddingEmployeeRuntime runtime;
    @SpyBean BiddingRepository repository;
    @Autowired vip.mate.agent.AgentService agents;
    @Autowired vip.mate.llm.service.ModelConfigService models;
    @Autowired vip.mate.llm.failover.AvailableProviderPool providerPool;
    @Autowired BiddingEmployeeBindings employeeBindings;
    @MockBean BiddingAccess access;
    @MockBean vip.mate.llm.chatmodel.ProviderChatModelFactory providerFactory;

    @Test void artifactVerifierAcceptsAuthorizedImagesAndRejectsMissingOrExtraImages() throws Exception {
        for(String imageFormat:List.of("png","jpeg")) {
            java.io.ByteArrayOutputStream imageBytes=new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(24,12,java.awt.image.BufferedImage.TYPE_INT_RGB),imageFormat,imageBytes);
            String digest=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(imageBytes.toByteArray()));
            String imageId="authorized-"+imageFormat;
            String imageKey=imageId+":7:"+digest;
            ObjectNode withImage=(ObjectNode)json.readTree("""
                {"title":"图像验证","chapters":[{"title":"图像章节","level":1,"blocks":[
                  {"type":"image","materialRef":{"kind":"material","id":"%s","version":7,"digest":"%s"},"alt":"示例","caption":"图注"}]}]}
                """.formatted(imageId,digest));
            ObjectNode withoutImage=(ObjectNode)json.readTree("""
                {"title":"图像验证","chapters":[{"title":"图像章节","level":1,"blocks":[{"type":"paragraph","text":"无图文本"}]}]}
                """);
            ObjectNode template=(ObjectNode)json.readTree("""
                {"version":"1","pageSize":"A4","font":"宋体","fontSize":12,"marginMm":25,"bodyWidthMm":160,
                 "maxImageWidthMm":160,"toc":true,"pageNumbers":true,"headingStyles":{"1":"Heading1","2":"Heading2","3":"Heading3"}}
                """);
            byte[] valid=new BiddingDocxRenderer().render(withImage,template,Map.of(imageKey,imageBytes.toByteArray()));
            assertEquals("PASS",artifacts.verify(valid,withImage).path("structural").asText(),imageFormat);
            byte[] missing=new BiddingDocxRenderer().render(withoutImage,template,Map.of());
            assertEquals("ARTIFACT_CONTENT_MISMATCH",assertThrows(BiddingApiException.class,()->artifacts.verify(missing,withImage)).code(),"missing "+imageFormat);
            assertEquals("ARTIFACT_CONTENT_MISMATCH",assertThrows(BiddingApiException.class,()->artifacts.verify(valid,withoutImage)).code(),"extra "+imageFormat);
        }
    }

    @Test void claimedExportToolPersistsImmutableDocxAndRegisteredHandlerAcceptsReadback() throws Exception {
        doNothing().when(access).requireActor(any(),anyString());doNothing().when(access).requireReaderActor(any(),anyString());doNothing().when(access).requireOwner(anyString(),anyString());
        jdbc.update("UPDATE mate_model_provider SET api_key='test-key',enabled=TRUE,chat_model='OpenAIChatModel' WHERE provider_id='openai'");
        providerPool.add("openai");
        String actor="actor";var created=projects.create(new BiddingTypes.Scope("1",actor,null),new BiddingTypes.NewProject("create-artifact-project-"+UUID.randomUUID(),"artifact","lot",actor));
        String projectId=created.path("id").asText();
        var scope=new BiddingTypes.Scope("1",actor,projectId);
        BiddingTypes.Ref baseline=new BiddingTypes.Ref("analysisBaseline","current",1,"a".repeat(64));
        BiddingTypes.Ref sourceSet=new BiddingTypes.Ref("sourceSet","current",1,"s".repeat(64));
        BiddingTypes.Ref sourceRef=new BiddingTypes.Ref("source","artifact-source",1,"t".repeat(64));
        jdbc.update("INSERT INTO mate_bidding_source(id,workspace_id,project_id,source_id,version,kind,digest,content,blocks_json,quality,read_token,read_started_at,filename,read_status,problems_json,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(),"1",projectId,sourceRef.id(),1,"TENDER",sourceRef.digest(),"tender".getBytes(java.nio.charset.StandardCharsets.UTF_8),"[]","PASS",null,null,"tender.txt","READY","[]",java.sql.Timestamp.from(Instant.now()));
        ObjectNode sourceSetBody=json.createObjectNode().put("schemaVersion","1");sourceSetBody.set("sourceRefs",json.valueToTree(List.of(sourceRef)));
        saveRevision(scope,sourceSet,sourceSetBody,List.of(sourceRef),"CONFIRMED",true);
        ObjectNode baselineBody=json.createObjectNode().put("schemaVersion","1");
        baselineBody.set("sourceSetRef",json.valueToTree(sourceSet));
        baselineBody.putObject("analyses").putObject("bidding-tender-profile").putArray("formatRequirements");
        saveRevision(scope,baseline,baselineBody,List.of(sourceSet),"CONFIRMED",true);
        BiddingTypes.Ref outline=new BiddingTypes.Ref("outline","current",1,"b".repeat(64));
        ObjectNode outlineBody=json.createObjectNode().put("schemaVersion","1");var outlineChapters=outlineBody.putArray("chapters");
        ObjectNode parent=outlineChapters.addObject().put("id","parent").putNull("parentId").put("order",1).put("title","总体方案");parent.putArray("requirementRefs").add("req");parent.putArray("scoringRefs");parent.putArray("materialRefs");parent.putArray("mandatoryOutlineRefs");
        ObjectNode leaf=outlineChapters.addObject().put("id","leaf").put("parentId","parent").put("order",3).put("title","实施计划");leaf.putArray("requirementRefs");leaf.putArray("scoringRefs");leaf.putArray("materialRefs");leaf.putArray("mandatoryOutlineRefs");
        ObjectNode early=outlineChapters.addObject().put("id","early").put("parentId","parent").put("order",2).put("title","前置实施");early.putArray("requirementRefs");early.putArray("scoringRefs");early.putArray("materialRefs");early.putArray("mandatoryOutlineRefs");
        saveRevision(scope,outline,outlineBody,List.of(baseline),"CONFIRMED",true);
        BiddingTypes.Ref manuscript=new BiddingTypes.Ref("manuscript","manuscript",1,"c".repeat(64));
        ObjectNode manuscriptBody=json.createObjectNode().put("schemaVersion","1").put("status","DRAFT_PENDING_REVIEW");manuscriptBody.set("outlineRef",json.valueToTree(outline));
        ObjectNode chapter=manuscriptBody.putArray("chapters").addObject().put("chapterId","leaf");
        ObjectNode chapterBody=chapter.putObject("chapter").put("chapterId","leaf").put("title","实施计划");chapterBody.putArray("blocks").addObject().put("type","paragraph").put("text","确保现场安全");
        ObjectNode earlyChapter=manuscriptBody.withArray("chapters").addObject().put("chapterId","early").putObject("chapter");earlyChapter.put("chapterId","early").put("title","前置实施");earlyChapter.putArray("blocks").addObject().put("type","paragraph").put("text","先行实施措施");
        ObjectNode table=earlyChapter.withArray("blocks").addObject().put("type","table");table.putArray("columns").add("验收项").add("标准");table.putArray("rows").addArray().add("响应时间").add("2秒");
        saveRevision(scope,manuscript,manuscriptBody,List.of(outline),"DRAFT_PENDING_REVIEW",false);
        long modelId=Math.abs(UUID.randomUUID().getLeastSignificantBits());String modelName="artifact-runtime-"+modelId;
        jdbc.update("INSERT INTO mate_model_config(id,name,provider,model_name,model_type,enabled,is_default,create_time,update_time,deleted) VALUES(?,?,? ,?,'chat',TRUE,FALSE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)",modelId,"artifact test model","openai",modelName);
        models.getModel(modelId);
        vip.mate.agent.model.AgentEntity agent=new vip.mate.agent.model.AgentEntity();agent.setName("artifact-writer-"+modelId);agent.setDescription("artifact export test");agent.setAgentType("react");agent.setRuntimeType("native");agent.setSystemPrompt("Return JSON only");agent.setMaxIterations(12);agent.setWorkspaceId(1L);agent.setModelName(modelName);agent=agents.createAgent(agent);
        long skillId=90_000_000L+Math.floorMod(UUID.randomUUID().hashCode(),1_000_000);String skill=Long.toString(skillId),skillDigest="d".repeat(64);
        jdbc.update("INSERT INTO mate_skill(id,name,workspace_id,create_time,update_time) VALUES(?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",skillId,"bidding-document-export",1L);
        Map<String,String> files=Map.of("SKILL.md","---\nname: bidding-document-export\n---\nUse exact refs.","input.schema.json",resource("/skills/bidding-document-export/input.schema.json"),"output.schema.json",resource("/skills/bidding-document-export/output.schema.json"));
        jdbc.update("INSERT INTO mate_bidding_skill_package(id,workspace_id,project_id,skill_id,version,digest,files_json,created_at) VALUES(?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",UUID.randomUUID().toString(),"1",projectId,skill,"v1",skillDigest,json.writeValueAsString(files));
        ObjectNode project=(ObjectNode)created.deepCopy();ObjectNode writer=project.putObject("bindings").putObject("writer");writer.put("agentId",agent.getId().toString());writer.put("configDigest","pending");writer.putArray("skillPins").addObject().put("skillId",skill).put("digest",skillDigest);
        jdbc.update("UPDATE mate_bidding_project SET body_json=? WHERE id=?",json.writeValueAsString(project),projectId);
        String configDigest=employeeBindings.configDigest(scope,agent.getId().toString());project.path("bindings").path("writer");((ObjectNode)project.path("bindings").path("writer")).put("configDigest",configDigest);
        jdbc.update("UPDATE mate_bidding_project SET body_json=? WHERE id=?",json.writeValueAsString(project),projectId);
        BiddingTypes.Ref projectRef=json.convertValue(created.path("ref"),BiddingTypes.Ref.class);
        List<ObjectNode> unprepared=artifacts.templates(scope);
        assertEquals("UNPREPARED",unprepared.getFirst().path("status").asText(),"template GET must state that explicit preparation is required");
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM mate_bidding_revision WHERE workspace_id=? AND project_id=? AND kind IN ('TEMPLATE','FORMAT_REQUIREMENTS')",Integer.class,scope.workspaceId(),scope.projectId()));
        ObjectNode unpreparedDispatch=json.createObjectNode().set("manuscriptRef",json.valueToTree(manuscript));unpreparedDispatch.put("mode","candidate");
        BiddingApiException requiresPreparation=assertThrows(BiddingApiException.class,()->artifacts.dispatch(scope,new BiddingTypes.Command("unprepared-export",projectRef,"DISPATCH_EXPORT",unpreparedDispatch)));
        assertEquals("EXPORT_REFS_REQUIRED",requiresPreparation.code());
        ObjectNode preparePayload=json.createObjectNode();
        BiddingTypes.Ref staleProjectRef=new BiddingTypes.Ref(projectRef.kind(),projectRef.id(),projectRef.version()+1,"0".repeat(64));
        BiddingApiException stalePrepare=assertThrows(BiddingApiException.class,()->artifacts.prepareExport(scope,new BiddingTypes.Command("stale-prepare",staleProjectRef,"PREPARE_EXPORT",preparePayload)));
        assertEquals("VERSION_CONFLICT",stalePrepare.code());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM mate_bidding_revision WHERE workspace_id=? AND project_id=? AND kind IN ('TEMPLATE','FORMAT_REQUIREMENTS')",Integer.class,scope.workspaceId(),scope.projectId()));
        BiddingTypes.Command prepare=new BiddingTypes.Command("prepare-export-1",projectRef,"PREPARE_EXPORT",preparePayload);
        ObjectNode prepared=artifacts.prepareExport(scope,prepare);
        ObjectNode preparedAgain=artifacts.prepareExport(scope,prepare);
        assertEquals(prepared,preparedAgain,"same operation replays the exact prepared refs");
        BiddingApiException operationConflict=assertThrows(BiddingApiException.class,()->artifacts.prepareExport(scope,
                new BiddingTypes.Command("prepare-export-1",staleProjectRef,"PREPARE_EXPORT",preparePayload)));
        assertEquals("OPERATION_CONFLICT",operationConflict.code());
        ObjectNode preparedTemplate=artifacts.templates(scope).getFirst();
        assertEquals("PREPARED",preparedTemplate.path("status").asText());
        assertEquals(prepared.path("templateRef"),preparedTemplate.path("ref"));
        assertEquals(prepared.path("formatRef"),preparedTemplate.path("formatRef"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM mate_bidding_revision WHERE workspace_id=? AND project_id=? AND kind='TEMPLATE'",Integer.class,scope.workspaceId(),scope.projectId()));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM mate_bidding_revision WHERE workspace_id=? AND project_id=? AND kind='FORMAT_REQUIREMENTS'",Integer.class,scope.workspaceId(),scope.projectId()));
        ObjectNode dispatch=json.createObjectNode().set("manuscriptRef",json.valueToTree(manuscript));dispatch.set("templateRef",prepared.path("templateRef"));dispatch.set("formatRef",prepared.path("formatRef"));dispatch.put("mode","candidate");
        ObjectNode staleTemplateDispatch=dispatch.deepCopy();staleTemplateDispatch.set("templateRef",json.valueToTree(new BiddingTypes.Ref("TEMPLATE","technical-v1",99,"x".repeat(64))));
        assertEquals("TEMPLATE_STALE",assertThrows(BiddingApiException.class,()->artifacts.dispatch(scope,new BiddingTypes.Command("stale-template",projectRef,"DISPATCH_EXPORT",staleTemplateDispatch))).code());
        ObjectNode staleFormatDispatch=dispatch.deepCopy();staleFormatDispatch.set("formatRef",json.valueToTree(new BiddingTypes.Ref("FORMAT_REQUIREMENTS","current",99,"y".repeat(64))));
        assertEquals("FORMAT_REQUIREMENTS_STALE",assertThrows(BiddingApiException.class,()->artifacts.dispatch(scope,new BiddingTypes.Command("stale-format",projectRef,"DISPATCH_EXPORT",staleFormatDispatch))).code());
        ObjectNode queued=artifacts.dispatch(scope,new BiddingTypes.Command("export-op-1",projectRef,"DISPATCH_EXPORT",dispatch));
        assertEquals("QUEUED",queued.path("status").asText());
        BiddingTypes.Claim claim=tasks.claimDue(Instant.now(),"artifact-test",1).getFirst();
        assertActualRuntimeWhitelistsExport(claim);
        assertEquals(Long.toString(modelId),claim.modelConfigId(),"claimed model config");
        assertEquals(agent.getId().toString(),claim.agentId());assertEquals(claim.modelConfigId(),employeeBindings.modelConfigId(claim.scope(),claim.agentId()),"current model config lookup");
        assertEquals(claim.configDigest(),employeeBindings.configDigest(claim.scope(),claim.agentId()),"current runtime config digest");
        ProjectExecutionOptions options=new ProjectExecutionOptions(claim.attemptId(),claim.modelConfigId(),claim.configDigest(),"bidding-document-export",claim.skill().digest(),claim.skill().files(),Set.of("bidding_export_document"),new BiddingToolScope(claim),0,false,false,12);
        ToolContext context=new ToolContext(Map.of(ProjectExecutionOptions.TOOL_CONTEXT_KEY,options));
        String args=json.writeValueAsString(claim.input().path("manuscriptRef"));String template=json.writeValueAsString(claim.input().path("templateRef"));String format=json.writeValueAsString(claim.input().path("formatRef"));
        ObjectNode manifest=(ObjectNode)json.readTree(exportTool.generate(args,template,format,context));
        ObjectNode repeated=(ObjectNode)json.readTree(exportTool.generate(args,template,format,context));
        assertEquals(manifest.path("artifactId"),repeated.path("artifactId"));assertEquals(manifest.path("digest"),repeated.path("digest"));
        tasks.complete(claim,new BiddingTypes.Execution(manifest,null,claim.skill().digest(),claim.configDigest(),null));
        assertEquals("SUCCEEDED",jdbc.queryForObject("SELECT status FROM mate_bidding_task WHERE id=?",String.class,claim.taskId()));
        assertEquals("CANDIDATE",jdbc.queryForObject("SELECT status FROM mate_bidding_artifact WHERE id=?",String.class,manifest.path("artifactId").asText()));
        BiddingTypes.Scope reader=new BiddingTypes.Scope("1",actor,projectId);
        byte[] persisted=artifacts.bytes(reader,manifest.path("artifactId").asText());
        assertEquals(manifest.path("byteSize").asLong(),persisted.length);
        assertEquals(manifest.path("digest").asText(),HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(persisted)));
        ObjectNode expectedDocument=expectedDocument();
        assertEquals("PASS",artifacts.verify(persisted,expectedDocument).path("structural").asText());
        assertDocxRejected(persisted,expectedDocument,doc->{
            var earlyText=doc.getParagraphs().stream().filter(p->"先行实施措施".equals(p.getText())).findFirst().orElseThrow();
            var leafText=doc.getParagraphs().stream().filter(p->"确保现场安全".equals(p.getText())).findFirst().orElseThrow();
            replaceParagraph(earlyText,"确保现场安全");replaceParagraph(leafText,"先行实施措施");
        });
        assertDocxRejected(persisted,expectedDocument,doc->{var removed=doc.getParagraphs().stream().filter(p->"先行实施措施".equals(p.getText())).findFirst().orElseThrow();replaceParagraph(removed,"");});
        assertDocxRejected(persisted,expectedDocument,doc->doc.createParagraph().createRun().setText("确保现场安全"));
        assertDocxRejected(persisted,expectedDocument,doc->doc.getTables().getFirst().getRow(1).getCell(1).getParagraphs().getFirst().getRuns().getFirst().setText("3秒",0));
        try(var empty=new org.apache.poi.xwpf.usermodel.XWPFDocument();var bytes=new java.io.ByteArrayOutputStream()) { empty.write(bytes);assertThrows(BiddingApiException.class,()->artifacts.verify(bytes.toByteArray(),expectedDocument)); }
        BiddingApiException external=assertThrows(BiddingApiException.class,()->artifacts.verify(withExternalRelationship(persisted),expectedDocument));
        assertEquals("ARTIFACT_EXTERNAL_RELATIONSHIP",external.code());
        BiddingTypes.Ref templateRef=json.convertValue(queued.path("templateRef"),BiddingTypes.Ref.class);
        BiddingTypes.Ref formatRef=json.convertValue(queued.path("formatRef"),BiddingTypes.Ref.class);
        assertConcurrentGenerationRespectsRealByteCapacity(scope,projectRef,claim,manuscript,templateRef,formatRef);
        assertCanceledExportRemainsUnusable(scope,projectRef,claim,manuscript,templateRef,formatRef);
        assertTamperedManifestIsRejected(scope,projectRef,claim,manuscript,templateRef,formatRef);
        assertCapacityRejectsRealGeneratedBytes(scope,projectRef,claim);
        assertSingleArtifactLimitRejectsActualOversizedDocx(scope,projectRef,manuscriptBody,manuscript,templateRef,formatRef,outline);
        BiddingTypes.Ref replacementOutline=new BiddingTypes.Ref("outline","current",2,"z".repeat(64));
        saveRevision(scope,replacementOutline,outlineBody,List.of(baseline),"CONFIRMED",false);
        jdbc.update("UPDATE mate_bidding_head SET version=2,selected_ref_json=? WHERE workspace_id=? AND project_id=? AND kind='outline' AND object_id='current'",json.writeValueAsString(replacementOutline),scope.workspaceId(),scope.projectId());
        BiddingApiException staleRead=assertThrows(BiddingApiException.class,()->artifacts.bytes(scope,manifest.path("artifactId").asText()));
        assertEquals(409,staleRead.status(),"historical candidate bytes are gated by current outline dependency closure");
        try (var doc = new org.apache.poi.xwpf.usermodel.XWPFDocument(new java.io.ByteArrayInputStream(persisted))) {
            var texts=doc.getParagraphs().stream().map(org.apache.poi.xwpf.usermodel.XWPFParagraph::getText).toList();
            assertTrue(texts.indexOf("前置实施") < texts.indexOf("实施计划"),"outline sibling order controls DOCX order");
            assertTrue(texts.indexOf("总体方案") < texts.indexOf("前置实施"),"frozen parent heading precedes its leaf chapters");
            assertTrue(texts.contains("先行实施措施") && texts.contains("确保现场安全"));
        }
    }

    private void assertCanceledExportRemainsUnusable(BiddingTypes.Scope scope,BiddingTypes.Ref projectRef,BiddingTypes.Claim prior,
            BiddingTypes.Ref manuscript,BiddingTypes.Ref template,BiddingTypes.Ref format) throws Exception {
        String op="cancel-export-"+UUID.randomUUID();ObjectNode payload=json.createObjectNode();
        ObjectNode input=prior.input().deepCopy().put("mode","preview");
        tasks.enqueue(scope,new BiddingTypes.Command(op,projectRef,"DISPATCH_EXPORT",payload),prior.skill().skillId(),"cancel-export:"+op,prior.inputRefs(),input);
        BiddingTypes.Claim claim=tasks.claimDue(Instant.now(),"artifact-cancel-test",1).getFirst();
        ProjectExecutionOptions options=new ProjectExecutionOptions(claim.attemptId(),claim.modelConfigId(),claim.configDigest(),"bidding-document-export",claim.skill().digest(),claim.skill().files(),Set.of("bidding_export_document"),new BiddingToolScope(claim),0,false,false,12);
        ToolContext context=new ToolContext(Map.of(ProjectExecutionOptions.TOOL_CONTEXT_KEY,options));
        ObjectNode staged=(ObjectNode)json.readTree(exportTool.generate(json.writeValueAsString(manuscript),json.writeValueAsString(template),json.writeValueAsString(format),context));
        String artifactId=staged.path("artifactId").asText();
        assertEquals("STAGED",jdbc.queryForObject("SELECT status FROM mate_bidding_artifact WHERE id=?",String.class,artifactId));
        ObjectNode cancel=json.createObjectNode().put("taskId",claim.taskId());
        tasks.cancel(scope,new BiddingTypes.Command("cancel-"+op,null,"CANCEL_TASK",cancel));
        assertEquals("CANCELLED",jdbc.queryForObject("SELECT status FROM mate_bidding_task WHERE id=?",String.class,claim.taskId()));
        assertEquals("STAGED",jdbc.queryForObject("SELECT status FROM mate_bidding_artifact WHERE id=?",String.class,artifactId));
        BiddingApiException hidden=assertThrows(BiddingApiException.class,()->artifacts.bytes(scope,artifactId));
        assertEquals(404,hidden.status());
    }

    private byte[] withExternalRelationship(byte[] docx) throws Exception {
        ByteArrayOutputStream output=new ByteArrayOutputStream();boolean changed=false;
        try(ZipInputStream input=new ZipInputStream(new java.io.ByteArrayInputStream(docx));ZipOutputStream zip=new ZipOutputStream(output)) {
            ZipEntry entry;
            while((entry=input.getNextEntry())!=null) {
                byte[] content=input.readAllBytes();
                if("word/_rels/document.xml.rels".equals(entry.getName())) {
                    String xml=new String(content,java.nio.charset.StandardCharsets.UTF_8);
                    int close=xml.lastIndexOf("</Relationships>");assertTrue(close>=0,xml);
                    String link="<Relationship Id=\"rIdExternalTest\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink\" Target=\"https://example.invalid\" TargetMode=\"External\"/>";
                    content=(xml.substring(0,close)+link+xml.substring(close)).getBytes(java.nio.charset.StandardCharsets.UTF_8);changed=true;
                }
                zip.putNextEntry(new ZipEntry(entry.getName()));zip.write(content);zip.closeEntry();
            }
        }
        assertTrue(changed,"generated package should contain document relationships");return output.toByteArray();
    }

    private void assertActualRuntimeWhitelistsExport(BiddingTypes.Claim claim) throws Exception {
        AtomicReference<Prompt> captured=new AtomicReference<>();ChatModel fake=mock(ChatModel.class);
        when(fake.stream(any(Prompt.class))).thenAnswer(invocation->{captured.set(invocation.getArgument(0));return Flux.concat(
                Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("{}"))))),
                Flux.error(new IllegalStateException("controlled test disconnect")));});
        when(providerFactory.buildFor(any(),any())).thenReturn(fake);
        BiddingTypes.Execution result=runtime.execute(claim);
        assertNull(result.payload());assertNotNull(result.failure());
        Prompt prompt=captured.get();assertNotNull(prompt);
        String taskJson=prompt.getInstructions().stream().filter(org.springframework.ai.chat.messages.UserMessage.class::isInstance)
                .map(message->((org.springframework.ai.chat.messages.UserMessage)message).getText()).filter(text->text.contains("\"references\"")).findFirst().orElseThrow();
        JsonNode execution=json.readTree(taskJson).path("execution");
        assertEquals("bidding-document-export",execution.path("skillName").asText());
        assertEquals(claim.skill().skillId(),execution.path("skillId").asText());
        assertEquals("SKILL.md",execution.path("loadSkillArgs").path("filePath").asText());
        var options=(org.springframework.ai.model.tool.ToolCallingChatOptions)prompt.getOptions();
        Set<String> callbacks=options.getToolCallbacks().stream().map(callback->callback.getToolDefinition().name()).collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of("load_skill","readSkillFile","bidding_read_source","bidding_read_sources","bidding_export_document"),callbacks);
    }

    private BiddingTypes.Claim enqueueExport(BiddingTypes.Scope scope,BiddingTypes.Ref projectRef,BiddingTypes.Claim prior,String target,ObjectNode input) {
        String op="op-"+UUID.randomUUID();
        tasks.enqueue(scope,new BiddingTypes.Command(op,projectRef,"DISPATCH_EXPORT",json.createObjectNode()),prior.skill().skillId(),target+":"+op,prior.inputRefs(),input);
        return tasks.claimDue(Instant.now(),"artifact-test-"+UUID.randomUUID(),1).getFirst();
    }
    private ObjectNode callTool(BiddingTypes.Claim claim,BiddingTypes.Ref manuscript,BiddingTypes.Ref template,BiddingTypes.Ref format) throws Exception {
        ProjectExecutionOptions options=new ProjectExecutionOptions(claim.attemptId(),claim.modelConfigId(),claim.configDigest(),"bidding-document-export",claim.skill().digest(),claim.skill().files(),Set.of("bidding_export_document"),new BiddingToolScope(claim),0,false,false,12);
        ToolContext context=new ToolContext(Map.of(ProjectExecutionOptions.TOOL_CONTEXT_KEY,options));
        return (ObjectNode)json.readTree(exportTool.generate(json.writeValueAsString(manuscript),json.writeValueAsString(template),json.writeValueAsString(format),context));
    }
    private void persistToolFailure(BiddingTypes.Claim claim,String code) {
        tasks.complete(claim,new BiddingTypes.Execution(null,new BiddingTypes.Failure(code,"VALIDATION",null,false,false,false),claim.skill().digest(),claim.configDigest(),null));
    }
    private ObjectNode expectedDocument() {
        ObjectNode doc=json.createObjectNode().put("title","技术标");var chapters=doc.putArray("chapters");
        chapters.addObject().put("title","总体方案").put("level",1).putArray("blocks");
        var early=chapters.addObject().put("title","前置实施").put("level",2).putArray("blocks");early.addObject().put("type","paragraph").put("text","先行实施措施");
        ObjectNode table=early.addObject().put("type","table");table.putArray("columns").add("验收项").add("标准");table.putArray("rows").addArray().add("响应时间").add("2秒");
        var leaf=chapters.addObject().put("title","实施计划").put("level",2).putArray("blocks");leaf.addObject().put("type","paragraph").put("text","确保现场安全");
        return doc;
    }
    private void assertDocxRejected(byte[] source,ObjectNode expected,java.util.function.Consumer<org.apache.poi.xwpf.usermodel.XWPFDocument> mutation) throws Exception {
        byte[] tampered;try(var doc=new org.apache.poi.xwpf.usermodel.XWPFDocument(new java.io.ByteArrayInputStream(source));var out=new java.io.ByteArrayOutputStream()) { mutation.accept(doc);doc.write(out);tampered=out.toByteArray(); }
        BiddingApiException rejected=assertThrows(BiddingApiException.class,()->artifacts.verify(tampered,expected));assertEquals("ARTIFACT_CONTENT_MISMATCH",rejected.code());
    }
    private void replaceParagraph(org.apache.poi.xwpf.usermodel.XWPFParagraph paragraph,String text) { while(!paragraph.getRuns().isEmpty())paragraph.removeRun(paragraph.getRuns().size()-1);paragraph.createRun().setText(text); }
    private void assertTamperedManifestIsRejected(BiddingTypes.Scope scope,BiddingTypes.Ref projectRef,BiddingTypes.Claim prior,
            BiddingTypes.Ref manuscript,BiddingTypes.Ref template,BiddingTypes.Ref format) throws Exception {
        ObjectNode input=prior.input().deepCopy().put("mode","preview");
        BiddingTypes.Claim claim=enqueueExport(scope,projectRef,prior,"tamper-manifest",input);
        ObjectNode valid=callTool(claim,manuscript,template,format);String artifactId=valid.path("artifactId").asText();
        for(String tamper:List.of("digest","checks","status")) {
            ObjectNode forged=valid.deepCopy();
            if("digest".equals(tamper)) forged.put("digest","0".repeat(64));
            if("checks".equals(tamper)) forged.with("checks").put("structural","FAIL");
            if("status".equals(tamper)) forged.put("status","CANDIDATE");
            BiddingApiException rejected=assertThrows(BiddingApiException.class,()->artifacts.accept(claim,forged),tamper);
            assertEquals("ARTIFACT_MANIFEST_INVALID",rejected.code());
        }
        assertEquals("STAGED",jdbc.queryForObject("SELECT status FROM mate_bidding_artifact WHERE id=?",String.class,artifactId));
        assertEquals("NOT_FOUND",assertThrows(BiddingApiException.class,()->artifacts.bytes(scope,artifactId)).code());
        persistToolFailure(claim,"MANIFEST_INVALID_TEST");
    }

    private void assertCapacityRejectsRealGeneratedBytes(BiddingTypes.Scope scope,BiddingTypes.Ref projectRef,BiddingTypes.Claim prior) throws Exception {
        BiddingTypes.Claim claim=enqueueExport(scope,projectRef,prior,"capacity",prior.input().deepCopy());
        BiddingApiException rejected=assertThrows(BiddingApiException.class,()->callTool(claim,json.convertValue(claim.input().path("manuscriptRef"),BiddingTypes.Ref.class),
                json.convertValue(claim.input().path("templateRef"),BiddingTypes.Ref.class),json.convertValue(claim.input().path("formatRef"),BiddingTypes.Ref.class)));
        assertEquals("PROJECT_ARTIFACT_CAPACITY",rejected.code());persistToolFailure(claim,rejected.code());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM mate_bidding_artifact WHERE generator_attempt_id=?",Integer.class,claim.attemptId()));
    }

    private void assertConcurrentGenerationRespectsRealByteCapacity(BiddingTypes.Scope scope,BiddingTypes.Ref projectRef,BiddingTypes.Claim prior,
            BiddingTypes.Ref manuscript,BiddingTypes.Ref template,BiddingTypes.Ref format) throws Exception {
        Long before=jdbc.queryForObject("SELECT COALESCE(SUM(byte_size),0) FROM mate_bidding_artifact WHERE workspace_id=? AND project_id=?",Long.class,scope.workspaceId(),scope.projectId());
        var sameAttempt=enqueueExport(scope,projectRef,prior,"concurrent-idempotency",prior.input().deepCopy());
        AtomicInteger renderCalls=new AtomicInteger(),lockCalls=new AtomicInteger();CountDownLatch firstRendering=new CountDownLatch(1),bothLocksEntered=new CountDownLatch(2),releaseFirst=new CountDownLatch(1);
        doAnswer(invocation->{lockCalls.incrementAndGet();bothLocksEntered.countDown();return invocation.callRealMethod();}).when(repository).lockProject(scope.workspaceId(),scope.projectId());
        doAnswer(invocation->{renderCalls.incrementAndGet();firstRendering.countDown();if(!releaseFirst.await(10,TimeUnit.SECONDS))throw new IllegalStateException("test did not release first real render");return invocation.callRealMethod();})
                .when(renderer).render(any(ObjectNode.class),any(ObjectNode.class),anyMap());
        var pool=Executors.newFixedThreadPool(2);
        try {
            var first=pool.submit(()->callTool(sameAttempt,manuscript,template,format));
            assertTrue(firstRendering.await(5,TimeUnit.SECONDS),"first real DOCX render entered the controlled latch");
            var second=pool.submit(()->callTool(sameAttempt,manuscript,template,format));
            assertTrue(bothLocksEntered.await(5,TimeUnit.SECONDS),"both tool calls reached the real project lock");
            releaseFirst.countDown();ObjectNode acceptedManifest=first.get(10,TimeUnit.SECONDS),replayedManifest=second.get(10,TimeUnit.SECONDS);
            assertEquals(acceptedManifest.path("artifactId"),replayedManifest.path("artifactId"));assertEquals(acceptedManifest.path("digest"),replayedManifest.path("digest"));
            tasks.complete(sameAttempt,new BiddingTypes.Execution(acceptedManifest,null,sameAttempt.skill().digest(),sameAttempt.configDigest(),null));
            assertEquals(1,renderCalls.get(),"the serialized retry returns the existing artifact instead of rendering again");
            Long after=jdbc.queryForObject("SELECT COALESCE(SUM(byte_size),0) FROM mate_bidding_artifact WHERE workspace_id=? AND project_id=?",Long.class,scope.workspaceId(),scope.projectId());
            assertTrue(after-before>0,"read-back uses actual generated DOCX byte_size");
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM mate_bidding_artifact WHERE generator_attempt_id=? AND status='CANDIDATE'",Integer.class,sameAttempt.attemptId()));
        } finally {releaseFirst.countDown();pool.shutdownNow();clearInvocations(renderer);clearInvocations(repository);}
    }
    private void assertSingleArtifactLimitRejectsActualOversizedDocx(BiddingTypes.Scope scope,BiddingTypes.Ref projectRef,ObjectNode originalBody,
            BiddingTypes.Ref originalRef,BiddingTypes.Ref template,BiddingTypes.Ref format,BiddingTypes.Ref outline) throws Exception {
        ObjectNode large=originalBody.deepCopy();String highEntropy=java.util.stream.IntStream.range(0,2500).mapToObj(i->UUID.randomUUID().toString()).collect(java.util.stream.Collectors.joining(" "));
        ((ObjectNode)large.path("chapters").get(0).path("chapter").path("blocks").get(0)).put("text",highEntropy);
        BiddingTypes.Ref next=new BiddingTypes.Ref("manuscript",originalRef.id(),originalRef.version()+1,"e".repeat(64));
        saveRevision(scope,next,large,List.of(outline),"DRAFT_PENDING_REVIEW",false);
        ObjectNode dispatch=json.createObjectNode().set("manuscriptRef",json.valueToTree(next));dispatch.set("templateRef",json.valueToTree(template));dispatch.set("formatRef",json.valueToTree(format));dispatch.put("mode","candidate");
        ObjectNode queued=artifacts.dispatch(scope,new BiddingTypes.Command("oversized-"+UUID.randomUUID(),projectRef,"DISPATCH_EXPORT",dispatch));
        BiddingTypes.Claim claim=tasks.claimDue(Instant.now(),"oversize-test",1).getFirst();
        BiddingApiException rejected=assertThrows(BiddingApiException.class,()->callTool(claim,next,template,format));
        assertEquals("ARTIFACT_SIZE_LIMIT",rejected.code());persistToolFailure(claim,rejected.code());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM mate_bidding_artifact WHERE generator_attempt_id=?",Integer.class,claim.attemptId()));
    }

    @Test void unsupportedTenderFormatCannotFallBackToTemplateAndConfirmedHardRequirementsCannotBeRemoved() throws Exception {
        doNothing().when(access).requireApprover(any());
        var unsupported=projects.create(new BiddingTypes.Scope("1","approver",null),new BiddingTypes.NewProject("format-unsupported-"+UUID.randomUUID(),"format","lot","approver"));
        var unsupportedScope=new BiddingTypes.Scope("1","approver",unsupported.path("id").asText());
        var baseline=new BiddingTypes.Ref("analysisBaseline","current",1,"f".repeat(64));
        ObjectNode base=json.createObjectNode().put("schemaVersion","1");
        var profile=base.putObject("analyses").putObject("bidding-tender-profile");
        var hard=profile.putArray("formatRequirements").addObject().put("name","页边距").put("value","30 mm");hard.putArray("evidenceRefs");
        saveRevision(unsupportedScope,baseline,base,List.of(),"CONFIRMED",true);
        var unsupportedProjectRef=json.convertValue(unsupported.path("ref"),BiddingTypes.Ref.class);
        var unsupportedError=assertThrows(BiddingApiException.class,()->artifacts.prepareExport(unsupportedScope,
                new BiddingTypes.Command("unsupported-format",unsupportedProjectRef,"PREPARE_EXPORT",json.createObjectNode())));
        assertEquals("EXPORT_FORMAT_UNSUPPORTED",unsupportedError.code());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM mate_bidding_revision WHERE workspace_id='1' AND project_id=? AND kind='FORMAT_REQUIREMENTS'",Integer.class,unsupportedScope.projectId()));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM mate_bidding_revision WHERE workspace_id='1' AND project_id=? AND kind='TEMPLATE'",Integer.class,unsupportedScope.projectId()));

        var constrained=projects.create(new BiddingTypes.Scope("1","approver",null),new BiddingTypes.NewProject("format-retention-"+UUID.randomUUID(),"format","lot","approver"));
        var constrainedScope=new BiddingTypes.Scope("1","approver",constrained.path("id").asText());
        var secondBaseline=new BiddingTypes.Ref("analysisBaseline","current",1,"g".repeat(64));
        ObjectNode secondBase=json.createObjectNode().put("schemaVersion","1");
        var secondFact=secondBase.putObject("analyses").putObject("bidding-tender-profile").putArray("formatRequirements").addObject();
        secondFact.put("name","纸张").put("value","A4");secondFact.putArray("evidenceRefs");
        saveRevision(constrainedScope,secondBaseline,secondBase,List.of(),"CONFIRMED",true);
        var formatRef=new BiddingTypes.Ref("FORMAT_REQUIREMENTS","current",1,"h".repeat(64));
        ObjectNode formatBody=json.createObjectNode().put("schemaVersion","1");var requirement=formatBody.putArray("requirements").addObject();requirement.put("name","纸张").put("value","A4").put("mandatory",true);requirement.putArray("evidenceRefs");
        saveRevision(constrainedScope,formatRef,formatBody,List.of(secondBaseline),"CONFIRMED",true);
        var removal=json.createObjectNode();removal.putArray("requirements");
        var removalError=assertThrows(BiddingApiException.class,()->artifacts.saveFormatRequirements(constrainedScope,
                new BiddingTypes.Command("drop-hard-format",formatRef,"SAVE_FORMAT_REQUIREMENTS",removal)));
        assertEquals("FORMAT_REQUIREMENT_CONFLICT",removalError.code());
    }

    @Autowired BiddingProjectService projects;

    private String resource(String path)throws Exception { try(var in=getClass().getResourceAsStream(path)){return new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);} }
    private void saveRevision(BiddingTypes.Scope scope,BiddingTypes.Ref ref,ObjectNode body,List<BiddingTypes.Ref> refs,String status,boolean head)throws Exception {
        String digest=ref.digest();jdbc.update("INSERT INTO mate_bidding_revision(id,workspace_id,project_id,kind,object_id,version,payload_json,input_refs_json,status,digest,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",UUID.randomUUID().toString(),scope.workspaceId(),scope.projectId(),ref.kind(),ref.id(),ref.version(),json.writeValueAsString(body),json.writeValueAsString(refs),status,digest,java.sql.Timestamp.from(Instant.now()));
        if(head)jdbc.update("INSERT INTO mate_bidding_head(workspace_id,project_id,kind,object_id,version,selected_ref_json) VALUES(?,?,?,?,?,?)",scope.workspaceId(),scope.projectId(),ref.kind(),ref.id(),ref.version(),json.writeValueAsString(ref));
    }
}

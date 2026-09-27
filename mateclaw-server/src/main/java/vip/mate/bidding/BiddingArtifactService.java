package vip.mate.bidding;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipEntry;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.BodyElementType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Persists and verifies byte-backed DOCX candidates tied to immutable bidding refs. */
@Service
public class BiddingArtifactService implements BiddingResultHandler {
    private static final String SKILL = "bidding-document-export";
    private static final long MAX_ARTIFACT_BYTES = 50L * 1024 * 1024;
    private static final long PROJECT_CAPACITY = 500L * 1024 * 1024;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final BiddingAccess access;
    private final BiddingProjectService projects;
    private final BiddingRepository repository;
    private final BiddingDependencies dependencies;
    private final BiddingTaskService tasks;
    private final BiddingDocxRenderer renderer;
    private final ObjectProvider<BiddingWritingService> writing;
    private final long artifactLimitBytes;
    private final long projectCapacityBytes;

    public BiddingArtifactService(JdbcTemplate jdbc, ObjectMapper json, BiddingAccess access,
            BiddingProjectService projects, BiddingRepository repository, BiddingDependencies dependencies,
            BiddingTaskService tasks, BiddingDocxRenderer renderer, ObjectProvider<BiddingWritingService> writing,
            @Value("${mateclaw.bidding.artifacts.max-bytes:52428800}") long configuredArtifactLimit,
            @Value("${mateclaw.bidding.artifacts.project-capacity-bytes:524288000}") long configuredProjectCapacity) {
        this.jdbc=jdbc; this.json=json; this.access=access; this.projects=projects; this.repository=repository;
        this.dependencies=dependencies; this.tasks=tasks; this.renderer=renderer; this.writing=writing;
        this.artifactLimitBytes=Math.max(1,Math.min(MAX_ARTIFACT_BYTES,configuredArtifactLimit));
        this.projectCapacityBytes=Math.max(1,Math.min(PROJECT_CAPACITY,configuredProjectCapacity));
    }

    @Override public Set<String> skillIds() { return Set.of(SKILL); }

    @Transactional(readOnly=true)
    public List<ObjectNode> templates(BiddingTypes.Scope scope) {
        access.requireReaderActor(scope, scope.actorId()); projects.get(scope);
        BiddingTypes.Ref ref=repository.selectedRef(scope,"TEMPLATE","technical-v1");
        BiddingTypes.Ref format=repository.selectedRef(scope,"FORMAT_REQUIREMENTS","current");
        ObjectNode value=json.createObjectNode().put("id","technical-v1").put("name","Technical proposal v1");
        if(ref!=null&&format!=null&&repository.businessRevision(scope,ref)!=null&&repository.businessRevision(scope,format)!=null) {
            value.put("status","PREPARED");value.set("ref",json.valueToTree(ref));value.set("formatRef",json.valueToTree(format));
            value.put("format","docx");
        } else value.put("status","UNPREPARED");
        return List.of(value);
    }

    @Transactional
    public ObjectNode prepareExport(BiddingTypes.Scope scope,BiddingTypes.Command command) {
        access.requireActor(scope,scope.actorId());
        if(command==null||command.payload()==null||!"PREPARE_EXPORT".equals(command.action())||command.operationId()==null||command.operationId().isBlank()||command.operationId().length()>128||command.expected()==null)
            throw BiddingAccess.error(400,"INVALID_REQUEST","PREPARE_EXPORT requires expected project ref and operationId");
        only(command.payload(),Set.of());
        if(!repository.lockProject(scope.workspaceId(),scope.projectId()))throw BiddingAccess.error(404,"NOT_FOUND","Project not found");
        String requestDigest=sha(canonical(json.valueToTree(Map.of("action",command.action(),"expected",command.expected(),"payload",command.payload()))));
        BiddingRepository.StoredOperation prior=repository.findOperation(scope.workspaceId(),scope.actorId(),command.operationId());
        if(prior!=null) {
            if(!prior.digest().equals(requestDigest))throw BiddingAccess.error(409,"OPERATION_CONFLICT","operationId was used for a different preparation request");
            return prior.result();
        }
        ObjectNode project=projects.get(scope);BiddingTypes.Ref current=parseRef(project.path("ref"));
        if(!Objects.equals(current,command.expected()))throw BiddingAccess.error(409,"VERSION_CONFLICT","Project changed; reload before export preparation");
        BiddingTypes.Ref template=ensureTemplate(scope),format=ensureFormat(scope);
        ObjectNode result=json.createObjectNode().put("status","PREPARED");result.set("templateRef",json.valueToTree(template));result.set("formatRef",json.valueToTree(format));
        repository.insertOperation(scope.workspaceId(),scope.actorId(),command.operationId(),requestDigest,"{}",Timestamp.from(Instant.now()));
        repository.updateOperation(scope.workspaceId(),scope.actorId(),command.operationId(),write(result));
        return result;
    }

    @Transactional
    public ObjectNode dispatch(BiddingTypes.Scope scope, BiddingTypes.Command command) {
        access.requireActor(scope,scope.actorId());
        if(command==null||command.payload()==null||!"DISPATCH_EXPORT".equals(command.action())) throw BiddingAccess.error(400,"INVALID_REQUEST","Export command is required");
        if(!repository.lockProject(scope.workspaceId(),scope.projectId())) throw BiddingAccess.error(404,"NOT_FOUND","Project not found");
        ObjectNode project=projects.get(scope);
        if(command.expected()==null||!command.expected().equals(json.convertValue(project.path("ref"),BiddingTypes.Ref.class))) throw BiddingAccess.error(409,"VERSION_CONFLICT","Project changed; reload before export");
        only(command.payload(),Set.of("manuscriptRef","templateRef","formatRef","mode"));
        BiddingTypes.Ref manuscript=parseRef(command.payload().path("manuscriptRef"));
        if(manuscript==null||!"manuscript".equals(manuscript.kind())) throw BiddingAccess.error(422,"MANUSCRIPT_REF_INVALID","A fixed manuscript reference is required");
        String mode=command.payload().path("mode").asText(); if(!Set.of("preview","candidate").contains(mode)) throw BiddingAccess.error(422,"EXPORT_MODE_INVALID","Export mode must be preview or candidate");
        ObjectNode row=repository.businessRevision(scope,manuscript);
        if(row==null||!isLatestManuscript(scope,manuscript)||!dependencies.isCurrentForRead(scope,repository.businessRefs(row))) throw BiddingAccess.error(409,"MANUSCRIPT_STALE","Only the current dependency-valid manuscript can be exported");
        BiddingTypes.Ref template=parseRef(command.payload().path("templateRef")),format=parseRef(command.payload().path("formatRef"));
        if(template==null||format==null)throw BiddingAccess.error(422,"EXPORT_REFS_REQUIRED","Prepare export and provide exact template and format references");
        if(!template.equals(repository.selectedRef(scope,"TEMPLATE","technical-v1"))||repository.businessRevision(scope,template)==null) throw BiddingAccess.error(409,"TEMPLATE_STALE","Selected template is not the prepared current template");
        if(!format.equals(repository.selectedRef(scope,"FORMAT_REQUIREMENTS","current"))||repository.businessRevision(scope,format)==null) throw BiddingAccess.error(409,"FORMAT_REQUIREMENTS_STALE","Selected format requirements are not the prepared current revision");
        validateFormat(scope,format);
        List<BiddingTypes.Ref> refs=List.of(manuscript,template,format);
        dependencies.validate(scope,refs);
        String skillId=exportSkillId(project,scope);
        ObjectNode input=json.createObjectNode().put("schemaVersion","1").put("skillId",SKILL).put("mode",mode);
        input.set("manuscriptRef",json.valueToTree(manuscript)); input.set("templateRef",json.valueToTree(template)); input.set("formatRef",json.valueToTree(format));
        ObjectNode result=tasks.enqueue(scope,command,skillId,"export:"+manuscript.id()+":"+manuscript.version(),refs,input);
        result.put("status","QUEUED"); result.set("manuscriptRef",json.valueToTree(manuscript)); result.set("templateRef",json.valueToTree(template)); result.set("formatRef",json.valueToTree(format)); result.put("mode",mode);
        return result;
    }

    @Transactional
    public BiddingTypes.Ref accept(BiddingTypes.Claim claim,ObjectNode payload) {
        if(claim==null||payload==null||!SKILL.equals(skillName(claim.skill().files()))) throw BiddingAccess.error(422,"SKILL_ROLE_MISMATCH","Export task result is invalid");
        BiddingTypes.Ref manuscript=parseRef(claim.input().path("manuscriptRef")),template=parseRef(claim.input().path("templateRef")),format=parseRef(claim.input().path("formatRef"));
        if(manuscript==null||template==null||format==null) throw BiddingAccess.error(422,"EXPORT_INPUT_INVALID","Fixed export references are missing");
        dependencies.validate(claim.scope(),claim.inputRefs());
        only(payload,Set.of("artifactId","format","digest","byteSize","mode","status","checks"));
        String artifactId=payload.path("artifactId").asText();
        Artifact row=artifact(claim.scope(),artifactId);
        if(row==null||!claim.attemptId().equals(row.attemptId())||!"STAGED".equals(row.status())||!manuscript.equals(row.manuscript())||!template.equals(row.template())||!format.equals(row.formatRef())) throw BiddingAccess.error(409,"ARTIFACT_UNAVAILABLE","Generated artifact is not staged for this task attempt");
        ObjectNode storedChecks=read(row.checks());
        if(!"docx".equals(payload.path("format").asText())||!row.digest().equals(payload.path("digest").asText())||row.size()!=payload.path("byteSize").asLong(-1)||!row.mode().equals(payload.path("mode").asText())||!"STAGED".equals(payload.path("status").asText())||!payload.path("checks").isObject()||!storedChecks.equals(payload.path("checks"))) throw BiddingAccess.error(422,"ARTIFACT_MANIFEST_INVALID","Tool manifest does not match stored bytes");
        ObjectNode checks=verify(row.bytes(),renderInput(claim.scope(),loadManuscript(claim.scope(),manuscript)));
        if(!row.digest().equals(sha(row.bytes()))||row.size()!=row.bytes().length) throw BiddingAccess.error(422,"ARTIFACT_INTEGRITY_FAILED","Candidate bytes do not match their manifest");
        String terminal="preview".equals(row.mode())?"PREVIEW":"CANDIDATE";
        jdbc.update("UPDATE mate_bidding_artifact SET status=?,checks_json=? WHERE workspace_id=? AND project_id=? AND id=? AND generator_attempt_id=? AND status='STAGED'",terminal,write(checks),claim.scope().workspaceId(),claim.scope().projectId(),artifactId,claim.attemptId());
        return new BiddingTypes.Ref("preview".equals(row.mode())?"previewArtifact":"artifact",artifactId,1,row.digest());
    }

    @Transactional
    public ObjectNode generate(BiddingTypes.Claim claim,BiddingTypes.Ref manuscriptRef,BiddingTypes.Ref templateRef,BiddingTypes.Ref formatRef,String mode) {
        if(claim==null||!SKILL.equals(skillName(claim.skill().files()))) throw BiddingAccess.error(403,"EXPORT_SKILL_REQUIRED","A pinned export skill claim is required");
        BiddingTypes.Ref im=parseRef(claim.input().path("manuscriptRef")),it=parseRef(claim.input().path("templateRef")),iff=parseRef(claim.input().path("formatRef"));
        if(!Objects.equals(im,manuscriptRef)||!Objects.equals(it,templateRef)||!Objects.equals(iff,formatRef)||!mode.equals(claim.input().path("mode").asText())) throw BiddingAccess.error(403,"EXPORT_REFS_NOT_ASSIGNED","Tool arguments differ from fixed task references");
        dependencies.validate(claim.scope(),claim.inputRefs());
        Artifact prior=artifactByAttempt(claim.attemptId()); if(prior!=null) return manifest(prior,mode);
        ObjectNode manuscript=loadManuscript(claim.scope(),manuscriptRef);
        ObjectNode renderInput=renderInput(claim.scope(),manuscript);
        ObjectNode template=readTemplate(claim.scope(),templateRef);
        validateFormat(claim.scope(),formatRef);
        byte[] bytes=renderer.render(renderInput,template,Map.of());
        if(bytes.length>artifactLimitBytes) throw BiddingAccess.error(413,"ARTIFACT_SIZE_LIMIT","DOCX candidate exceeds 50 MiB");
        Long used=jdbc.queryForObject("SELECT COALESCE(SUM(byte_size),0) FROM mate_bidding_artifact WHERE workspace_id=? AND project_id=?",Long.class,claim.scope().workspaceId(),claim.scope().projectId());
        if((used==null?0:used)+bytes.length>projectCapacityBytes) throw BiddingAccess.error(413,"PROJECT_ARTIFACT_CAPACITY","Project candidate capacity is exhausted");
        ObjectNode checks=verify(bytes,renderInput); String digest=sha(bytes),id=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO mate_bidding_artifact(workspace_id,project_id,id,manuscript_ref_json,template_ref_json,format_ref_json,mode,format,digest,byte_size,content,checks_json,generator_attempt_id,status,decision_id,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,'STAGED',NULL,?)",
                claim.scope().workspaceId(),claim.scope().projectId(),id,write(manuscriptRef),write(templateRef),write(formatRef),mode,"docx",digest,bytes.length,bytes,write(checks),claim.attemptId(),Timestamp.from(Instant.now()));
        return manifest(artifact(claim.scope(),id),mode);
    }

    @Transactional(readOnly=true)
    public ObjectNode metadata(BiddingTypes.Scope scope,String artifactId) {
        access.requireReaderActor(scope,scope.actorId()); projects.get(scope);
        Artifact row=artifact(scope,artifactId); if(row==null||!Set.of("CANDIDATE","PREVIEW").contains(row.status())) throw BiddingAccess.error(404,"NOT_FOUND","Candidate artifact not found");
        dependencies.validateForRead(scope,List.of(row.manuscript(),row.template(),row.formatRef()));
        ObjectNode out=manifest(row,row.mode()); out.put("status",row.status()); out.set("checks",read(row.checks())); return out;
    }

    @Transactional(readOnly=true)
    public byte[] bytes(BiddingTypes.Scope scope,String artifactId) {
        ObjectNode meta=metadata(scope,artifactId); Artifact row=artifact(scope,artifactId); return row.bytes();
    }

    public ObjectNode verify(byte[] bytes,ObjectNode manuscript) {
        if(bytes==null||bytes.length==0||bytes.length>artifactLimitBytes) throw BiddingAccess.error(422,"ARTIFACT_INVALID","Candidate bytes are empty or oversized");
        try(XWPFDocument doc=new XWPFDocument(new ByteArrayInputStream(bytes))) {
            verifyBodyElements(doc,manuscript);
            validatePackageSafety(bytes);
            int expectedImages=countImageBlocks(manuscript);
            if(doc.getAllPictures().size()!=expectedImages) throw BiddingAccess.error(422,"ARTIFACT_CONTENT_MISMATCH","DOCX image content does not match the manuscript");
            ObjectNode checks=json.createObjectNode().put("structural","PASS").put("visualPageInspection","NOT_RUN").put("allManuscriptTextPresent",true).put("externalRelationships",false).put("validZipDocx",true);
            checks.put("digest",sha(bytes)); checks.put("byteSize",bytes.length); return checks;
        } catch(BiddingApiException e) { throw e; }
        catch(Exception e) { throw BiddingAccess.error(422,"ARTIFACT_DOCX_INVALID","Candidate bytes are not a readable DOCX"); }
    }

    private void verifyBodyElements(XWPFDocument doc,ObjectNode expected) {
        if(expected==null||!expected.path("title").isTextual()||!expected.path("chapters").isArray())contentMismatch();
        if(!expected.path("title").asText().equals(doc.getProperties().getCoreProperties().getTitle()))contentMismatch();
        if(doc.getBodyElements().size()<2||doc.getParagraphs().size()<2||!"目录".equals(doc.getParagraphs().getFirst().getText())
                ||!"TOCHeading".equals(doc.getParagraphs().getFirst().getStyle())||!doc.getParagraphs().get(1).getCTP().xmlText().contains(" TOC "))
            contentMismatch();
        List<ExpectedElement> want=new ArrayList<>();
        for(JsonNode chapter:expected.path("chapters")) {
            if(!chapter.path("title").isTextual()||!chapter.path("blocks").isArray())contentMismatch();
            int level=Math.max(1,Math.min(3,chapter.path("level").asInt(1)));
            want.add(new ExpectedElement("heading",chapter.path("title").asText(),level,List.of()));
            for(JsonNode block:chapter.path("blocks")) {
                switch(block.path("type").asText()) {
                    case "paragraph" -> want.add(new ExpectedElement("paragraph",block.path("text").asText(),0,List.of()));
                    case "heading" -> want.add(new ExpectedElement("heading",block.path("text").asText(),block.path("level").asInt(),List.of()));
                    case "list" -> { int i=1;for(JsonNode item:block.path("items"))want.add(new ExpectedElement("paragraph",(block.path("ordered").asBoolean()?i++ + ". ":"• ")+item.asText(),0,List.of())); }
                    case "table" -> {
                        List<List<String>> rows=new ArrayList<>();List<String> header=new ArrayList<>();block.path("columns").forEach(c->header.add(c.asText()));rows.add(header);
                        block.path("rows").forEach(row->{List<String> cells=new ArrayList<>();row.forEach(c->cells.add(c.asText()));rows.add(cells);});
                        want.add(new ExpectedElement("table","",0,rows));
                    }
                    case "image" -> {
                        want.add(new ExpectedElement("image","",0,List.of()));
                        if(!block.path("caption").asText("").isBlank())want.add(new ExpectedElement("paragraph",block.path("caption").asText(),0,List.of()));
                    }
                    default -> contentMismatch();
                }
            }
        }
        List<IBodyElement> body=doc.getBodyElements();
        if(body.size()!=want.size()+2)contentMismatch();
        for(int i=0;i<want.size();i++) {
            ExpectedElement expectedElement=want.get(i);IBodyElement actual=body.get(i+2);
            if("table".equals(expectedElement.type())) {
                if(actual.getElementType()!=BodyElementType.TABLE)contentMismatch();
                var table=(org.apache.poi.xwpf.usermodel.XWPFTable)actual;
                if(table.getRows().size()!=expectedElement.rows().size())contentMismatch();
                for(int r=0;r<expectedElement.rows().size();r++) {
                    var actualRow=table.getRow(r);List<String> expectedRow=expectedElement.rows().get(r);
                    if(actualRow.getTableCells().size()!=expectedRow.size())contentMismatch();
                    for(int c=0;c<expectedRow.size();c++)if(!expectedRow.get(c).equals(actualRow.getCell(c).getText()))contentMismatch();
                }
            } else {
                if(actual.getElementType()!=BodyElementType.PARAGRAPH)contentMismatch();
                var paragraph=(org.apache.poi.xwpf.usermodel.XWPFParagraph)actual;
                if("image".equals(expectedElement.type())) {
                    if(paragraph.getRuns().stream().noneMatch(run->run.getCTR().sizeOfDrawingArray()>0))contentMismatch();
                } else if(!expectedElement.text().equals(paragraph.getText()))contentMismatch();
                if("heading".equals(expectedElement.type())&&!("Heading"+expectedElement.level()).equals(paragraph.getStyle()))contentMismatch();
            }
        }
    }
    private void contentMismatch() { throw BiddingAccess.error(422,"ARTIFACT_CONTENT_MISMATCH","DOCX body structure does not exactly match the rendered manuscript"); }

    private void validatePackageSafety(byte[] bytes) {
        try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while((entry=zip.getNextEntry())!=null) {
                if(entry.isDirectory())continue;
                String name=entry.getName().toLowerCase(Locale.ROOT);
                if(name.endsWith(".bin")||name.contains("vbaproject")||name.contains("oleobject"))
                    throw BiddingAccess.error(422,"ARTIFACT_EXTERNAL_RELATIONSHIP","DOCX contains active or embedded content");
                if(name.endsWith(".rels")||name.equals("word/document.xml")) {
                    String xml=new String(zip.readAllBytes(),StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
                    if(xml.contains("targetmode=\"external\"")||xml.contains("oleobject")||xml.contains("vbaproject"))
                        throw BiddingAccess.error(422,"ARTIFACT_EXTERNAL_RELATIONSHIP","DOCX contains an external relationship or active content");
                }
            }
        } catch(BiddingApiException e) { throw e; }
        catch(Exception e) { throw BiddingAccess.error(422,"ARTIFACT_DOCX_INVALID","Candidate bytes are not a readable DOCX package"); }
    }
    private int countImageBlocks(JsonNode manuscript) {
        int count=0;
        for(JsonNode chapter:manuscript.path("chapters")) for(JsonNode block:chapter.path("chapter").path("blocks"))
            if("image".equals(block.path("type").asText()))count++;
        return count;
    }

    @Transactional
    public ObjectNode saveFormatRequirements(BiddingTypes.Scope scope,BiddingTypes.Command command) {
        access.requireApprover(scope);
        if(command==null||command.payload()==null||!"SAVE_FORMAT_REQUIREMENTS".equals(command.action())) throw BiddingAccess.error(400,"INVALID_REQUEST","Format requirements command is required");
        if(!repository.lockProject(scope.workspaceId(),scope.projectId())) throw BiddingAccess.error(404,"NOT_FOUND","Project not found");
        only(command.payload(),Set.of("requirements"));
        BiddingTypes.Ref current=repository.selectedRef(scope,"FORMAT_REQUIREMENTS","current");
        if(current==null||repository.businessRevision(scope,current)==null)throw BiddingAccess.error(409,"FORMAT_REQUIREMENTS_UNAVAILABLE","Prepare export before changing format requirements");
        if(!current.equals(command.expected())) throw BiddingAccess.error(409,"VERSION_CONFLICT","Format requirements changed; reload before saving");
        ArrayNode requirements=command.payload().withArray("requirements"); validateRequirements(requirements);
        JsonNode existing=repository.businessRevision(scope,current).path("requirements");
        for(JsonNode mandatory:existing) if(mandatory.path("mandatory").asBoolean(true)) {
            boolean retained=false;
            for(JsonNode proposed:requirements) if(mandatory.path("name").asText().equalsIgnoreCase(proposed.path("name").asText())
                    && mandatory.path("value").asText().equalsIgnoreCase(proposed.path("value").asText())
                    && proposed.path("mandatory").asBoolean(true)) { retained=true;break; }
            if(!retained)throw BiddingAccess.error(422,"FORMAT_REQUIREMENT_CONFLICT","A confirmed mandatory format requirement cannot be removed or weakened");
        }
        ObjectNode payload=json.createObjectNode().put("schemaVersion","1");payload.set("requirements",requirements.deepCopy());
        String body=canonical(payload),digest=sha(body); long version=repository.nextRevisionVersion(scope,"FORMAT_REQUIREMENTS","current");
        List<BiddingTypes.Ref> refs=repository.businessRefs(repository.businessRevision(scope,current));
        repository.insertRevision(UUID.randomUUID().toString(),scope.workspaceId(),scope.projectId(),"FORMAT_REQUIREMENTS","current",version,body,write(refs),"CONFIRMED",digest,Timestamp.from(Instant.now()));
        BiddingTypes.Ref next=new BiddingTypes.Ref("FORMAT_REQUIREMENTS","current",version,digest); advanceHead(scope,current,next);
        jdbc.update("UPDATE mate_bidding_artifact SET status='STALE' WHERE workspace_id=? AND project_id=? AND status='CANDIDATE'",scope.workspaceId(),scope.projectId());
        ObjectNode out=json.createObjectNode().set("formatRef",json.valueToTree(next));out.put("status","SAVED");return out;
    }

    private BiddingTypes.Ref ensureTemplate(BiddingTypes.Scope scope) {
        BiddingTypes.Ref current=repository.selectedRef(scope,"TEMPLATE","technical-v1");
        if(current!=null&&repository.historicalRevisionExists(scope,current)) return current;
        ObjectNode template=readTemplateBytes();String body=canonical(template),digest=sha(body);long version=repository.nextRevisionVersion(scope,"TEMPLATE","technical-v1");
        repository.insertRevision(UUID.randomUUID().toString(),scope.workspaceId(),scope.projectId(),"TEMPLATE","technical-v1",version,body,"[]","CONFIRMED",digest,Timestamp.from(Instant.now()));
        BiddingTypes.Ref next=new BiddingTypes.Ref("TEMPLATE","technical-v1",version,digest);createHead(scope,next);return next;
    }
    private BiddingTypes.Ref ensureFormat(BiddingTypes.Scope scope) {
        BiddingTypes.Ref current=repository.selectedRef(scope,"FORMAT_REQUIREMENTS","current");
        BiddingTypes.Ref baseline=repository.selectedRef(scope,"analysisBaseline","current");
        if(baseline==null) throw BiddingAccess.error(409,"FORMAT_REQUIREMENTS_UNAVAILABLE","Confirmed tender format requirements are unavailable");
        if(current!=null&&repository.businessRevision(scope,current)!=null) {
            List<BiddingTypes.Ref> refs=repository.businessRefs(repository.businessRevision(scope,current));
            if(refs.contains(baseline)) return current;
        }
        ObjectNode baselineBody=repository.businessRevision(scope,baseline);JsonNode facts=baselineBody.path("analyses").path("bidding-tender-profile").path("formatRequirements");
        if(!facts.isArray()) throw BiddingAccess.error(409,"FORMAT_REQUIREMENTS_UNAVAILABLE","Confirmed tender format requirements are unavailable");
        ArrayNode requirements=json.createArrayNode();
        for(JsonNode fact:facts) { ObjectNode item=requirements.addObject();item.put("name",fact.path("name").asText());item.put("value",fact.path("value").asText(""));item.put("mandatory",true);item.set("evidenceRefs",fact.path("evidenceRefs").deepCopy()); }
        validateRequirements(requirements);
        ObjectNode payload=json.createObjectNode().put("schemaVersion","1");payload.set("requirements",requirements);
        String body=canonical(payload),digest=sha(body);long version=repository.nextRevisionVersion(scope,"FORMAT_REQUIREMENTS","current");
        repository.insertRevision(UUID.randomUUID().toString(),scope.workspaceId(),scope.projectId(),"FORMAT_REQUIREMENTS","current",version,body,write(List.of(baseline)),"CONFIRMED",digest,Timestamp.from(Instant.now()));
        BiddingTypes.Ref next=new BiddingTypes.Ref("FORMAT_REQUIREMENTS","current",version,digest);if(current==null)createHead(scope,next);else advanceHead(scope,current,next);return next;
    }
    private void validateFormat(BiddingTypes.Scope scope,BiddingTypes.Ref ref) {
        ObjectNode revision=repository.businessRevision(scope,ref);if(revision==null||!revision.path("requirements").isArray())throw BiddingAccess.error(409,"FORMAT_REQUIREMENTS_STALE","Format requirements are no longer available");
        validateRequirements(revision.path("requirements"));
    }
    private void validateRequirements(JsonNode requirements) {
        if(!requirements.isArray())throw BiddingAccess.error(422,"FORMAT_REQUIREMENTS_INVALID","Requirements must be an array");
        for(JsonNode item:requirements) {
            if(!item.isObject()||!item.path("name").isTextual()||!item.path("value").isTextual())
                throw BiddingAccess.error(422,"FORMAT_REQUIREMENTS_INVALID","Each format requirement needs a name and explicit value");
            String name=item.path("name").asText().toLowerCase(Locale.ROOT).replaceAll("\\s+","");
            String value=item.path("value").asText().toLowerCase(Locale.ROOT).replaceAll("\\s+","");
            if(!item.path("mandatory").asBoolean(true))continue;
            boolean supported=(Set.of("page","pagesize","paper","纸张","页面大小").contains(name)&&Set.of("a4","210mmx297mm","210×297mm").contains(value))
                    ||(Set.of("margin","margins","页边距","边距").contains(name)&&Set.of("25mm","四边25mm","上下左右25mm").contains(value))
                    ||(Set.of("font","字体").contains(name)&&Set.of("宋体").contains(value))
                    ||(Set.of("fontsize","字号").contains(name)&&Set.of("12pt","小四").contains(value));
            if(!supported)throw BiddingAccess.error(422,"EXPORT_FORMAT_UNSUPPORTED","Mandatory tender format requirement is not supported by technical-v1");
        }
    }
    private ObjectNode renderInput(BiddingTypes.Scope scope,ObjectNode manuscript) {
        ObjectNode input=json.createObjectNode().put("title",manuscript.path("title").asText("技术标"));ArrayNode output=input.putArray("chapters");
        ObjectNode outline=repository.businessRevision(scope,parseRef(manuscript.path("outlineRef")));
        if(outline==null)throw BiddingAccess.error(409,"MANUSCRIPT_STALE","Frozen outline is unavailable");
        JsonNode outlineChapters=outline.path("chapters");
        List<ResolvedChapter> resolved=new ArrayList<>();
        for(JsonNode chapter:manuscript.path("chapters")) {
            JsonNode body=chapter.path("chapter");String id=chapter.path("chapterId").asText();
            if(!id.equals(body.path("chapterId").asText()))throw BiddingAccess.error(422,"MANUSCRIPT_INVALID","Chapter identity does not match frozen outline");
            List<JsonNode> path=new ArrayList<>();if(!findOutlinePath(outlineChapters,id,path))throw BiddingAccess.error(409,"MANUSCRIPT_STALE","Chapter is absent from frozen outline");
            resolved.add(new ResolvedChapter(chapter,body,path));
        }
        resolved.sort((left,right)->compareOutlineOrder(left.path(),right.path()));
        List<JsonNode> lastAncestors=List.of();
        for(ResolvedChapter entry:resolved) {
            List<JsonNode> path=entry.path();int common=0;
            while(common<lastAncestors.size()&&common<path.size()-1&&sameOutlineNode(lastAncestors.get(common),path.get(common)))common++;
            for(int i=common;i<path.size()-1;i++) { JsonNode node=path.get(i);ObjectNode heading=output.addObject().put("title",node.path("title").asText());heading.put("level",Math.min(3,i+1));heading.putArray("blocks"); }
            ObjectNode leaf=output.addObject().put("title",entry.body().path("title").asText(path.getLast().path("title").asText()));leaf.put("level",Math.min(3,path.size()));leaf.set("blocks",entry.body().path("blocks").deepCopy());
            lastAncestors=List.copyOf(path.subList(0,path.size()-1));
        }
        return input;
    }
    private int compareOutlineOrder(List<JsonNode> left,List<JsonNode> right) {
        for(int i=0;i<Math.min(left.size(),right.size());i++) {
            int order=Integer.compare(left.get(i).path("order").asInt(Integer.MAX_VALUE),right.get(i).path("order").asInt(Integer.MAX_VALUE));
            if(order!=0)return order;
        }
        return Integer.compare(left.size(),right.size());
    }
    private boolean sameOutlineNode(JsonNode left,JsonNode right) { return left.path("id").asText(left.path("chapterId").asText()).equals(right.path("id").asText(right.path("chapterId").asText())); }
    private record ResolvedChapter(JsonNode source,JsonNode body,List<JsonNode> path) {}
    private boolean findOutlinePath(JsonNode nodes,String id,List<JsonNode> path) {
        if(!nodes.isArray())return false;
        Map<String,JsonNode> byId=new HashMap<>();
        for(JsonNode node:nodes) {
            String nodeId=node.path("id").asText(node.path("chapterId").asText());
            if(nodeId.isBlank()||byId.putIfAbsent(nodeId,node)!=null)return false;
        }
        JsonNode target=byId.get(id);
        if(target==null)return false;
        Set<String> seen=new HashSet<>();
        while(target!=null) {
            String nodeId=target.path("id").asText(target.path("chapterId").asText());
            if(!seen.add(nodeId))return false;
            path.add(target);
            String parent=target.path("parentId").asText("");
            if(parent.isBlank()||"null".equals(parent))break;
            target=byId.get(parent);
            if(target==null)return false;
        }
        Collections.reverse(path);
        return true;
    }
    private ObjectNode loadManuscript(BiddingTypes.Scope s,BiddingTypes.Ref ref) { ObjectNode row=repository.businessRevision(s,ref);if(row==null)throw BiddingAccess.error(404,"NOT_FOUND","Manuscript not found");return row; }
    private ObjectNode readTemplate(BiddingTypes.Scope scope,BiddingTypes.Ref ref) { ObjectNode stored=repository.businessRevision(scope,ref);if(stored==null)throw BiddingAccess.error(409,"TEMPLATE_STALE","Fixed template revision is unavailable");return stored; }
    private boolean isLatestManuscript(BiddingTypes.Scope scope,BiddingTypes.Ref ref) { BiddingTypes.Ref current=jdbc.query("SELECT kind,object_id,version,digest FROM mate_bidding_revision WHERE workspace_id=? AND project_id=? AND kind='manuscript' ORDER BY version DESC",rs->rs.next()?new BiddingTypes.Ref(rs.getString(1),rs.getString(2),rs.getLong(3),rs.getString(4)):null,scope.workspaceId(),scope.projectId());return ref.equals(current); }
    private ObjectNode readTemplateBytes() { try(var in=getClass().getResourceAsStream("/bidding/templates/technical-v1.json")){if(in==null)throw new IllegalStateException("Template missing");return (ObjectNode)json.readTree(in);}catch(Exception e){throw BiddingAccess.error(500,"TEMPLATE_UNAVAILABLE","Fixed DOCX template is unavailable");} }
    private ObjectNode manifest(Artifact row,String mode) { ObjectNode out=json.createObjectNode();out.put("artifactId",row.id());out.put("format","docx");out.put("digest",row.digest());out.put("byteSize",row.size());out.put("mode",mode);out.put("status",row.status());out.set("checks",read(row.checks()));return out; }
    private Artifact artifact(BiddingTypes.Scope s,String id) { return jdbc.query("SELECT id,manuscript_ref_json,template_ref_json,format_ref_json,mode,digest,byte_size,content,checks_json,generator_attempt_id,status FROM mate_bidding_artifact WHERE workspace_id=? AND project_id=? AND id=?",rs->rs.next()?new Artifact(rs.getString(1),parseRef(json,rs.getString(2)),parseRef(json,rs.getString(3)),parseRef(json,rs.getString(4)),rs.getString(5),rs.getString(6),rs.getLong(7),rs.getBytes(8),rs.getString(9),rs.getString(10),rs.getString(11)):null,s.workspaceId(),s.projectId(),id); }
    private Artifact artifactByAttempt(String attempt) { return jdbc.query("SELECT workspace_id,project_id,id FROM mate_bidding_artifact WHERE generator_attempt_id=?",rs->rs.next()?artifact(new BiddingTypes.Scope(rs.getString(1),"",rs.getString(2)),rs.getString(3)):null,attempt); }
    private String exportSkillId(ObjectNode project,BiddingTypes.Scope scope) {
        String agent=project.path("bindings").path("writer").path("agentId").asText("");
        if(agent.isBlank())throw BiddingAccess.error(409,"WRITER_NOT_ASSIGNED","A writer must be assigned before export");
        for(JsonNode pin:project.path("bindings").path("writer").path("skillPins")) {
            String id=pin.path("skillId").asText(""),digest=pin.path("digest").asText("");
            String name=jdbc.query("SELECT name FROM mate_skill WHERE id=? AND workspace_id=?",rs->rs.next()?rs.getString(1):null,Long.valueOf(id),Long.valueOf(scope.workspaceId()));
            if(!SKILL.equals(name))continue;
            Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM mate_bidding_skill_package WHERE workspace_id=? AND project_id=? AND skill_id=? AND digest=?",Integer.class,scope.workspaceId(),scope.projectId(),id,digest);
            if(count!=null&&count==1)return id;
        }
        throw BiddingAccess.error(422,"EXPORT_SKILL_NOT_PINNED","The writer must be assigned the fixed export skill package");
    }
    private record ExpectedElement(String type,String text,int level,List<List<String>> rows) {}
    private void createHead(BiddingTypes.Scope s,BiddingTypes.Ref r) { jdbc.update("INSERT INTO mate_bidding_head(workspace_id,project_id,kind,object_id,version,selected_ref_json) VALUES(?,?,?,?,?,?)",s.workspaceId(),s.projectId(),r.kind(),r.id(),r.version(),write(r)); }
    private void advanceHead(BiddingTypes.Scope s,BiddingTypes.Ref old,BiddingTypes.Ref next) { int count=jdbc.update("UPDATE mate_bidding_head SET version=?,selected_ref_json=? WHERE workspace_id=? AND project_id=? AND kind=? AND object_id=? AND version=?",next.version(),write(next),s.workspaceId(),s.projectId(),old.kind(),old.id(),old.version());if(count!=1)throw BiddingAccess.error(409,"VERSION_CONFLICT","Format requirements changed during save"); }
    private String canonical(JsonNode n) { try { return json.writeValueAsString(json.treeToValue(n,Object.class)); }catch(Exception e){throw new IllegalStateException(e);} }
    private String write(Object n) { try{return json.writeValueAsString(n);}catch(Exception e){throw new IllegalStateException(e);} }
    private ObjectNode read(String raw) { try{return (ObjectNode)json.readTree(raw);}catch(Exception e){throw new IllegalStateException("Invalid stored export JSON",e);} }
    private String sha(byte[] bytes) { try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IllegalStateException(e);} }
    private String sha(String text) { return sha(text.getBytes(StandardCharsets.UTF_8)); }
    private BiddingTypes.Ref parseRef(JsonNode n) { return parseRef(json,n); }
    private String skillName(Map<String,String> files) { String markdown=files==null?"":files.getOrDefault("SKILL.md","");var matcher=java.util.regex.Pattern.compile("(?m)^name:\\s*['\"]?([^'\"\\r\\n]+)").matcher(markdown);if(matcher.find())return matcher.group(1).trim();matcher=java.util.regex.Pattern.compile("(?m)^#\\s+(.+)$").matcher(markdown);return matcher.find()?matcher.group(1).trim():""; }
    private static BiddingTypes.Ref parseRef(ObjectMapper m,String raw) { try{return m.readValue(raw,BiddingTypes.Ref.class);}catch(Exception e){return null;} }
    private BiddingTypes.Ref parseRef(ObjectMapper m,JsonNode n) { try{return m.treeToValue(n,BiddingTypes.Ref.class);}catch(Exception e){return null;} }
    private void only(JsonNode n,Set<String> allowed) { n.fieldNames().forEachRemaining(k->{if(!allowed.contains(k))throw BiddingAccess.error(400,"INVALID_REQUEST","Unsupported export field: "+k);}); }
    private record Artifact(String id,BiddingTypes.Ref manuscript,BiddingTypes.Ref template,BiddingTypes.Ref formatRef,String mode,String digest,long size,byte[] bytes,String checks,String attemptId,String status) {}
}

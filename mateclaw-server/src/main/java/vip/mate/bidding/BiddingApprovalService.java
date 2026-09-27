package vip.mate.bidding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Binds human approval to one immutable artifact and one whole-book review snapshot. */
@Service
public class BiddingApprovalService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final BiddingAccess access;
    private final BiddingProjectService projects;
    private final BiddingRepository repository;
    private final BiddingDependencies dependencies;
    private final BiddingReviewService reviews;
    private final BiddingArtifactService artifacts;

    public BiddingApprovalService(JdbcTemplate jdbc,ObjectMapper json,BiddingAccess access,BiddingProjectService projects,
            BiddingRepository repository,BiddingDependencies dependencies,BiddingReviewService reviews,BiddingArtifactService artifacts) {
        this.jdbc=jdbc;this.json=json;this.access=access;this.projects=projects;this.repository=repository;this.dependencies=dependencies;this.reviews=reviews;this.artifacts=artifacts;
    }

    @Transactional
    public ObjectNode approve(BiddingTypes.Scope scope,BiddingTypes.Command command) {
        access.requireApprover(scope);
        if(command==null||command.payload()==null||!"APPROVE_ARTIFACT".equals(command.action())||command.operationId()==null||command.operationId().isBlank()||command.operationId().length()>128||command.expected()==null)
            throw BiddingAccess.error(400,"INVALID_REQUEST","Artifact approval command is invalid");
        only(command.payload(),Set.of("artifactId","digest","manuscriptRef","templateRef","formatRef","reviewRef","inspection"));
        if(!repository.lockProject(scope.workspaceId(),scope.projectId()))throw BiddingAccess.error(404,"NOT_FOUND","Project not found");
        String requestDigest=sha(canonical(json.valueToTree(Map.of("action",command.action(),"expected",command.expected(),"payload",command.payload()))));
        BiddingRepository.StoredOperation replay=repository.findOperation(scope.workspaceId(),scope.actorId(),command.operationId());
        if(replay!=null){if(!requestDigest.equals(replay.digest()))throw BiddingAccess.error(409,"OPERATION_CONFLICT","operationId was used for a different approval");return replay.result();}
        String artifactId=command.payload().path("artifactId").asText("");
        if(artifactId.isBlank())throw BiddingAccess.error(422,"APPROVAL_INVALID","artifactId is required");
        jdbc.query("SELECT id FROM mate_bidding_artifact WHERE workspace_id=? AND project_id=? AND id=? FOR UPDATE",rs->rs.next()?rs.getString(1):null,scope.workspaceId(),scope.projectId(),artifactId);
        ObjectNode artifact=artifacts.approvalRecord(scope,artifactId);
        if(artifact==null)throw BiddingAccess.error(404,"ARTIFACT_NOT_FOUND","Candidate artifact not found");
        BiddingTypes.Ref artifactRef=new BiddingTypes.Ref("artifact",artifactId,1,artifact.path("digest").asText());
        if(!artifactRef.equals(command.expected())||!artifactRef.digest().equals(command.payload().path("digest").asText()))throw BiddingAccess.error(409,"ARTIFACT_STALE","Approval must identify the exact artifact digest");
        if(!"candidate".equals(artifact.path("mode").asText())||!"CANDIDATE".equals(artifact.path("status").asText()))throw BiddingAccess.error(422,"ARTIFACT_NOT_APPROVABLE","Only a completed candidate can be approved");
        BiddingTypes.Ref manuscript=ref(command.payload().path("manuscriptRef")),template=ref(command.payload().path("templateRef")),format=ref(command.payload().path("formatRef")),reviewRef=ref(command.payload().path("reviewRef"));
        if(!Objects.equals(manuscript,ref(artifact.path("manuscriptRef")))||!Objects.equals(template,ref(artifact.path("templateRef")))||!Objects.equals(format,ref(artifact.path("formatRef")))||reviewRef==null||!"reviewSnapshot".equals(reviewRef.kind()))
            throw BiddingAccess.error(409,"APPROVAL_REFS_MISMATCH","Approval references must match the exact candidate and current whole-book review");
        JsonNode inspection=command.payload().path("inspection");
        only(inspection,Set.of("opened","layoutChecked","reason"));
        if(!inspection.path("opened").asBoolean(false)||!inspection.path("layoutChecked").asBoolean(false)||inspection.path("reason").asText("").isBlank())throw BiddingAccess.error(422,"HUMAN_INSPECTION_REQUIRED","Record that the reviewer opened and visually checked this file");
        byte[] candidateBytes=artifacts.download(scope,artifactId,"candidate");
        if(!artifactRef.digest().equals(sha(candidateBytes))||artifact.path("byteSize").asLong(-1)!=candidateBytes.length)
            throw BiddingAccess.error(409,"ARTIFACT_STALE","Candidate bytes no longer match the reviewed digest");
        dependencies.validate(scope,List.of(manuscript,template,format));
        ObjectNode evidence=reviews.approvalEvidence(scope,manuscript);
        if(!reviewRef.equals(ref(evidence.path("reviewRef"))))throw BiddingAccess.error(409,"REVIEW_STALE","Whole-book review changed; reload before approval");
        ObjectNode target=json.createObjectNode().put("schemaVersion","1").put("artifactId",artifactId).put("digest",artifactRef.digest());
        target.set("artifactRef",json.valueToTree(artifactRef));target.set("manuscriptRef",json.valueToTree(manuscript));target.set("templateRef",json.valueToTree(template));target.set("formatRef",json.valueToTree(format));target.set("reviewRef",json.valueToTree(reviewRef));target.set("reviewEvidence",evidence.path("evidence").deepCopy());target.set("inspection",inspection.deepCopy());
        String decisionId=java.util.UUID.randomUUID().toString();
        jdbc.update("INSERT INTO mate_bidding_decision(id,workspace_id,project_id,target_ref_json,decision,reason,actor_id,created_at) VALUES(?,?,?,?,?,?,?,?)",decisionId,scope.workspaceId(),scope.projectId(),write(target),"APPROVE_ARTIFACT",inspection.path("reason").asText(),scope.actorId(),Timestamp.from(Instant.now()));
        int changed=jdbc.update("UPDATE mate_bidding_artifact SET status='APPROVED',decision_id=? WHERE workspace_id=? AND project_id=? AND id=? AND status='CANDIDATE' AND mode='candidate' AND digest=?",decisionId,scope.workspaceId(),scope.projectId(),artifactId,artifactRef.digest());
        if(changed!=1)throw BiddingAccess.error(409,"ARTIFACT_APPROVAL_CONFLICT","Candidate changed while approval was being recorded");
        BiddingTypes.Ref decisionRef=new BiddingTypes.Ref("approvalDecision",decisionId,1,sha(canonical(target)));
        ObjectNode result=json.createObjectNode().put("status","APPROVED");result.set("artifactRef",json.valueToTree(artifactRef));result.set("decisionRef",json.valueToTree(decisionRef));result.set("reviewRef",json.valueToTree(reviewRef));
        repository.insertOperation(scope.workspaceId(),scope.actorId(),command.operationId(),requestDigest,write(result),Timestamp.from(Instant.now()));
        return result;
    }

    @Transactional(readOnly=true)
    public void requireDownloadable(BiddingTypes.Scope scope,String artifactId,String mode) {
        access.requireReaderActor(scope,scope.actorId());projects.get(scope);
        if(!"formal".equals(mode))throw BiddingAccess.error(400,"DOWNLOAD_MODE_INVALID","Formal approval is required for this download mode");
        ObjectNode artifact=artifacts.approvalRecord(scope,artifactId);
        if(artifact==null||!"APPROVED".equals(artifact.path("status").asText())||artifact.path("decisionId").isNull()||artifact.path("decisionId").asText().isBlank())throw BiddingAccess.error(404,"FORMAL_ARTIFACT_UNAVAILABLE","No approved formal artifact is available");
        BiddingTypes.Ref manuscript=ref(artifact.path("manuscriptRef")),template=ref(artifact.path("templateRef")),format=ref(artifact.path("formatRef"));
        dependencies.validateForRead(scope,List.of(manuscript,template,format));
        String decisionId=artifact.path("decisionId").asText();String raw=jdbc.query("SELECT target_ref_json FROM mate_bidding_decision WHERE workspace_id=? AND project_id=? AND id=? AND decision='APPROVE_ARTIFACT'",rs->rs.next()?rs.getString(1):null,scope.workspaceId(),scope.projectId(),decisionId);
        if(raw==null)throw BiddingAccess.error(409,"APPROVAL_STALE","Approval evidence is unavailable");
        ObjectNode target=read(raw);
        BiddingTypes.Ref artifactRef=new BiddingTypes.Ref("artifact",artifactId,1,artifact.path("digest").asText());
        if(!artifactRef.equals(ref(target.path("artifactRef")))||!artifactRef.digest().equals(target.path("digest").asText())
                ||!Objects.equals(manuscript,ref(target.path("manuscriptRef")))||!Objects.equals(template,ref(target.path("templateRef")))||!Objects.equals(format,ref(target.path("formatRef"))))
            throw BiddingAccess.error(409,"APPROVAL_STALE","Approved artifact references changed");
        ObjectNode current=reviews.approvalEvidence(scope,manuscript);
        if(!Objects.equals(ref(target.path("reviewRef")),ref(current.path("reviewRef")))||!target.path("reviewEvidence").equals(current.path("evidence")))
            throw BiddingAccess.error(409,"APPROVAL_STALE","Current review dispositions differ from the recorded approval");
    }

    private BiddingTypes.Ref ref(JsonNode value){try{return json.treeToValue(value,BiddingTypes.Ref.class);}catch(Exception e){return null;}}
    private ObjectNode read(String raw){try{return (ObjectNode)json.readTree(raw);}catch(Exception e){throw BiddingAccess.error(500,"APPROVAL_RECORD_INVALID","Approval record is unavailable");}}
    private String write(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
    private String canonical(Object value){try{return json.writer().with(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
    private String sha(String text){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private String sha(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IllegalStateException(e);}}
    private void only(JsonNode value,Set<String> allowed){value.fieldNames().forEachRemaining(key->{if(!allowed.contains(key))throw BiddingAccess.error(400,"INVALID_REQUEST","Unsupported approval field");});}
}

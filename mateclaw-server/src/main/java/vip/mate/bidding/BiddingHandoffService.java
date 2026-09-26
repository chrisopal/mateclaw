package vip.mate.bidding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.presales.PresalesService;

@Service
public class BiddingHandoffService {
    private final BiddingAccess access;
    private final BiddingRepository repository;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ObjectProvider<PresalesService> presales;

    public BiddingHandoffService(BiddingAccess access, BiddingRepository repository, JdbcTemplate jdbc,
            ObjectMapper json, ObjectProvider<PresalesService> presales) {
        this.access = access; this.repository = repository; this.jdbc = jdbc; this.json = json; this.presales = presales;
    }

    public List<ObjectNode> options(BiddingTypes.Scope scope, String presalesProjectId) {
        access.requireReaderActor(scope, scope.actorId());
        var service = presales.getIfAvailable();
        if (service == null) throw BiddingAccess.error(409, "PRESALES_UNAVAILABLE", "Presales is unavailable");
        ObjectNode project = service.get(scope.workspaceId(), presalesProjectId);
        java.util.ArrayList<ObjectNode> out = new java.util.ArrayList<>();
        for (var release : project.withArray("releases")) {
            if (!"PUBLISHED".equals(release.path("status").asText())) continue;
            ObjectNode option = json.createObjectNode().put("presalesProjectId", presalesProjectId)
                    .put("releaseId", release.path("id").asText()).put("status", "PUBLISHED");
            try {
                ObjectNode handoff = service.handoff(scope.workspaceId(), presalesProjectId, release.path("id").asText());
                option.put("digest", digest(handoff)).put("title", handoff.path("solution").path("title").asText(""));
                if (handoff.path("solution").has("version")) option.put("solutionVersion", handoff.path("solution").path("version").asInt());
                if (handoff.path("release").hasNonNull("publishedAt")) option.put("publishedAt", handoff.path("release").path("publishedAt").asText());
                option.put("available", true);
            } catch (vip.mate.semantic.web.SemanticApiException unavailable) {
                if (!"HISTORICAL_SNAPSHOT_UNAVAILABLE".equals(unavailable.code())) throw unavailable;
                option.put("available", false).put("unavailableReason", "HISTORICAL_SNAPSHOT_UNAVAILABLE");
            }
            ObjectNode releaseView=json.createObjectNode().put("id",release.path("id").asText()).put("status","PUBLISHED");
            if(release.hasNonNull("publishedAt")) releaseView.put("publishedAt",release.path("publishedAt").asText());
            if(release.hasNonNull("version")) releaseView.put("version",release.path("version").asInt());
            option.set("release", releaseView); out.add(option);
        }
        return List.copyOf(out);
    }

    @Transactional
    public ObjectNode receive(BiddingTypes.Scope scope, BiddingTypes.Command command) {
        access.requireActor(scope, scope.actorId());
        if (command == null || command.payload() == null) throw BiddingAccess.error(400, "INVALID_COMMAND", "Command payload is required");
        var p = command.payload(); String presalesId = p.path("presalesProjectId").asText();
        String releaseId = p.path("releaseId").asText(); String op = command.operationId();
        if (presalesId.isBlank() || releaseId.isBlank() || op == null || op.isBlank()) throw BiddingAccess.error(400, "INVALID_COMMAND", "Presales project, release and operation are required");
        var service = presales.getIfAvailable();
        if (service == null) throw BiddingAccess.error(409, "PRESALES_UNAVAILABLE", "Presales is unavailable");
        ObjectNode snapshot = service.handoff(scope.workspaceId(), presalesId, releaseId);
        String actual = digest(snapshot);
        if (!actual.equals(p.path("expectedDigest").asText())) throw BiddingAccess.error(409, "HANDOFF_DIGEST_MISMATCH", "Presales release changed after preview");
        ObjectNode request = p.deepCopy();
        ObjectNode requestEnvelope=json.createObjectNode().put("projectId",scope.projectId()).put("action",command.action());
        requestEnvelope.set("expected",json.valueToTree(command.expected())); requestEnvelope.set("payload",request);
        String requestDigest = digest(requestEnvelope);
        if (!repository.lockProject(scope.workspaceId(), scope.projectId())) throw BiddingAccess.error(404, "NOT_FOUND", "Project not found");
        var replay=repository.findOperation(scope.workspaceId(),scope.actorId(),op);
        if(replay!=null) {
            if(!requestDigest.equals(replay.digest())) throw BiddingAccess.error(409,"IDEMPOTENCY_CONFLICT","Operation id was used for another request");
            return replay.result();
        }
        ObjectNode project = repository.findProject(scope.workspaceId(), scope.projectId());
        ObjectNode accepted = json.createObjectNode().put("presalesProjectId", presalesId).put("releaseId", releaseId)
                .put("digest", actual).put("receivedAt", Instant.now().toString()).put("actorId", scope.actorId())
                .put("customerConfirmationStatus", "UNCONFIRMED");
        accepted.set("snapshot", snapshot);
        ArrayNode refs = accepted.putArray("materialRefs");
        for (var ref : snapshot.path("materialRefs")) refs.add(ref.deepCopy());
        ObjectNode materialRef = json.createObjectNode().put("kind", "material")
                .put("id", presalesMaterialId(presalesId, releaseId)).put("version", 1).put("digest", actual);
        refs.add(materialRef);
        accepted.set("receivedNotes", receivedNotes(p.path("receivedNotes"), scope.actorId()));
        int nextVersion = project.path("version").asInt(1) + 1;
        if(command.expected()==null || !"project".equals(command.expected().kind()) || !scope.projectId().equals(command.expected().id())
                || command.expected().version()!=project.path("version").asLong()
                || !command.expected().digest().equals(project.path("ref").path("digest").asText()))
            throw BiddingAccess.error(409,"VERSION_CONFLICT","Project has changed; reload before receiving this release");
        ObjectNode metadata=json.createObjectNode().put("presalesProjectId",presalesId).put("releaseId",releaseId)
                .put("digest",actual).put("receivedAt",accepted.path("receivedAt").asText()).put("customerConfirmationStatus","UNCONFIRMED");
        ObjectNode selected=project.with("selectedRefs"); selected.set("handoff",metadata);
        project.put("version", nextVersion);
        var newRef=project.with("ref"); newRef.put("version",nextVersion).put("digest",projectDigest(scope.workspaceId(),project,nextVersion));
        jdbc.update("UPDATE mate_bidding_project SET body_json=?,version=?,updated_at=CURRENT_TIMESTAMP WHERE workspace_id=? AND id=?",
                encode(project), nextVersion, scope.workspaceId(), scope.projectId());
        jdbc.update("INSERT INTO mate_bidding_handoff(id,workspace_id,project_id,presales_project_id,release_id,baseline_ref,solution_ref,snapshot_json,digest,actor_id,received_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(), scope.workspaceId(), scope.projectId(), presalesId, releaseId, snapshot.path("baseline").path("id").asText(""),
                snapshot.path("solution").path("id").asText(""), encode(accepted), actual, scope.actorId(), java.sql.Timestamp.from(Instant.now()));
        ObjectNode accessRef=json.createObjectNode().put("presalesProjectId",presalesId).put("releaseId",releaseId)
                .put("workspaceId",scope.workspaceId()).put("receivedAt",accepted.path("receivedAt").asText());
        jdbc.update("INSERT INTO mate_bidding_material(id,workspace_id,project_id,source_kind,external_id,version,digest,content_json,access_ref_json,validity,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(),scope.workspaceId(),scope.projectId(),"PRESALES_RELEASE",presalesMaterialId(presalesId,releaseId),1,actual,encode(snapshot),encode(accessRef),"VALID",java.sql.Timestamp.from(Instant.now()));
        repository.insertOperation(scope.workspaceId(), scope.actorId(), op, requestDigest, encode(accepted), java.sql.Timestamp.from(Instant.now()));
        return accepted;
    }

    private ArrayNode receivedNotes(com.fasterxml.jackson.databind.JsonNode input, String actor) {
        ArrayNode out = json.createArrayNode();
        if (input != null && input.isArray()) for (var note : input) {
            if (!note.isTextual() || note.asText().isBlank()) continue;
            out.addObject().put("text", note.asText()).put("receivedAt", Instant.now().toString()).put("source", "BIDDING_RECEIPT").put("actorId", actor);
        }
        return out;
    }
    private String presalesMaterialId(String projectId,String releaseId) { return "presales:"+projectId+":"+releaseId; }
    private String encode(ObjectNode value) { try { return json.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException(e); } }
    private String digest(ObjectNode value) { try { byte[] bytes=json.writeValueAsBytes(value); return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); } catch(Exception e) { throw new IllegalStateException(e); } }
    private String projectDigest(String workspace,ObjectNode project,int version) {
        ObjectNode basis=json.createObjectNode().put("workspaceId",workspace).put("name",project.path("name").asText())
                .put("lotName",project.path("lotName").asText()).put("ownerId",project.path("ownerId").asText())
                .put("version",version).put("stage",project.path("stage").asText());
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(canonical(basis)))); }
        catch(Exception e) { throw new IllegalStateException(e); }
    }
    private com.fasterxml.jackson.databind.JsonNode canonical(com.fasterxml.jackson.databind.JsonNode node) {
        if(node.isObject()) {
            ObjectNode sorted=json.createObjectNode(); java.util.TreeSet<String> names=new java.util.TreeSet<>(); node.fieldNames().forEachRemaining(names::add);
            for(String name:names) sorted.set(name,canonical(node.get(name))); return sorted;
        }
        if(node.isArray()) { var array=json.createArrayNode(); node.forEach(value->array.add(canonical(value))); return array; }
        return node;
    }
}

package vip.mate.bidding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.repository.AgentMapper;
import vip.mate.presales.PresalesService;
import vip.mate.wiki.model.WikiKnowledgeBaseEntity;
import vip.mate.wiki.model.WikiPageEntity;
import vip.mate.wiki.service.WikiKnowledgeBaseService;
import vip.mate.wiki.service.WikiPageService;

@Service
public class BiddingMaterials {
    private final BiddingAccess access;
    private final BiddingRepository repository;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ObjectProvider<WikiKnowledgeBaseService> knowledgeBases;
    private final ObjectProvider<WikiPageService> pages;
    private final AgentMapper agents;
    private final ObjectProvider<PresalesService> presales;

    public BiddingMaterials(BiddingAccess access, BiddingRepository repository, JdbcTemplate jdbc, ObjectMapper json,
            ObjectProvider<WikiKnowledgeBaseService> knowledgeBases, ObjectProvider<WikiPageService> pages,
            AgentMapper agents,ObjectProvider<PresalesService> presales) {
        this.access=access; this.repository=repository; this.jdbc=jdbc; this.json=json; this.knowledgeBases=knowledgeBases; this.pages=pages; this.agents=agents; this.presales=presales;
    }

    @Transactional
    public ObjectNode bind(BiddingTypes.Scope scope, BiddingTypes.Command command) {
        access.requireActor(scope,scope.actorId());
        if(command==null || command.operationId()==null || command.operationId().isBlank()) throw BiddingAccess.error(400,"INVALID_COMMAND","Operation id is required");
        var p=command.payload(); if(p==null || !"WIKI_PAGE".equals(p.path("kind").asText())) throw BiddingAccess.error(400,"MATERIAL_KIND_INVALID","Only wiki pages can be bound");
        long kbId=parseId(p.path("knowledgeBaseId").asText()), pageId=parseId(p.path("pageId").asText());
        if(!repository.lockProject(scope.workspaceId(),scope.projectId())) throw BiddingAccess.error(404,"NOT_FOUND","Project not found");
        ObjectNode project=repository.findProject(scope.workspaceId(),scope.projectId());
        if(project==null) throw BiddingAccess.error(404,"NOT_FOUND","Project not found");
        String agentId=project.path("bindings").path("writer").path("agentId").asText("");
        if(agentId.isBlank()) throw BiddingAccess.error(422,"MATERIAL_AGENT_REQUIRED","Bind a writer before selecting materials");
        AgentEntity employee=requireAgent(scope,agentId);
        var kbService=knowledgeBases.getIfAvailable(); var pageService=pages.getIfAvailable();
        if(kbService==null || pageService==null) throw BiddingAccess.error(409,"WIKI_UNAVAILABLE","Wiki is unavailable");
        WikiKnowledgeBaseEntity kb=kbService.findVisibleById(employee.getId(),kbId);
        if(kb==null || kb.getWorkspaceId()==null || !scope.workspaceId().equals(kb.getWorkspaceId().toString()) || !active(kb.getDeleted()))
            throw BiddingAccess.error(404,"NOT_FOUND","Knowledge base not found");
        WikiPageEntity page=pageService.getById(pageId);
        if(page==null || !active(page.getDeleted()) || page.getKbId()==null || page.getKbId()!=kbId)
            throw BiddingAccess.error(404,"NOT_FOUND","Knowledge page not found");
        String requestDigest=requestDigest(scope,command);
        var replay=repository.findOperation(scope.workspaceId(),scope.actorId(),command.operationId());
        if(replay!=null) {
            if(!requestDigest.equals(replay.digest())) throw BiddingAccess.error(409,"IDEMPOTENCY_CONFLICT","Operation id was used for another request");
            return replay.result();
        }
        if(command.expected()==null || !"project".equals(command.expected().kind()) || !scope.projectId().equals(command.expected().id())
                || command.expected().version()!=project.path("version").asLong()
                || !command.expected().digest().equals(project.path("ref").path("digest").asText()))
            throw BiddingAccess.error(409,"VERSION_CONFLICT","Project has changed; reload before selecting material");
        String content=page.getContent()==null?"":page.getContent(); String digest=sha(content);
        if(!digest.equals(p.path("expectedDigest").asText())) throw BiddingAccess.error(409,"MATERIAL_DIGEST_MISMATCH","Wiki page changed after preview");
        int version=1;
        String applicability=p.path("applicability").asText(); if(applicability.isBlank()) throw BiddingAccess.error(422,"MATERIAL_APPLICABILITY_REQUIRED","Specify how the material applies to this project");
        String selectedAt=Instant.now().toString();
        ObjectNode contentJson=json.createObjectNode().put("title",page.getTitle()).put("content",content).put("pageType",page.getPageType())
                .put("applicability",applicability).put("selectedAt",selectedAt);
        ObjectNode ref=json.createObjectNode().put("kind","material").put("id",kbId+":"+pageId).put("version",version).put("digest",digest);
        ObjectNode accessRef=json.createObjectNode().put("knowledgeBaseId",kbId).put("pageId",pageId).put("agentId",agentId).put("workspaceId",scope.workspaceId()).put("pageType",page.getPageType());
        Integer max=jdbc.queryForObject("SELECT MAX(version) FROM mate_bidding_material WHERE workspace_id=? AND project_id=? AND external_id=?",Integer.class,
                scope.workspaceId(),scope.projectId(),kbId+":"+pageId);
        if(max!=null) version=max+1;
        ref.put("version",version);
        jdbc.update("INSERT INTO mate_bidding_material(id,workspace_id,project_id,source_kind,external_id,version,digest,content_json,access_ref_json,validity,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                java.util.UUID.randomUUID().toString(),scope.workspaceId(),scope.projectId(),"WIKI_PAGE",kbId+":"+pageId,version,digest,write(contentJson),write(accessRef),"VALID",java.sql.Timestamp.from(Instant.now()));
        ObjectNode result=json.createObjectNode().set("ref",ref);
        result.put("title",page.getTitle()).put("source","WIKI_PAGE").put("selectedAt",selectedAt).put("applicability",applicability);
        result.set("content",contentJson); result.set("accessRef",accessRef);
        repository.insertOperation(scope.workspaceId(),scope.actorId(),command.operationId(),requestDigest,write(result),java.sql.Timestamp.from(Instant.now()));
        return result;
    }

    public ObjectNode list(BiddingTypes.Scope scope) {
        access.requireReaderActor(scope,scope.actorId());
        var project=repository.findProject(scope.workspaceId(),scope.projectId());
        if(project==null) throw BiddingAccess.error(404,"NOT_FOUND","Project not found");
        String agentId=project.path("bindings").path("writer").path("agentId").asText("");
        ObjectNode result=json.createObjectNode(); var items=result.putArray("items");
        var refs=jdbc.query("SELECT external_id,version,digest,access_ref_json,content_json,validity FROM mate_bidding_material WHERE workspace_id=? AND project_id=? AND source_kind='WIKI_PAGE' ORDER BY created_at,id",
                (rs,n)->new MaterialRow(rs.getString(1),rs.getLong(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6)),scope.workspaceId(),scope.projectId());
        for(var row:refs) {
            ObjectNode item=items.addObject().put("source","WIKI_PAGE").put("version",row.version()).put("digest",row.digest());
            item.set("ref",json.createObjectNode().put("kind","material").put("id",row.externalId()).put("version",row.version()).put("digest",row.digest()));
            boolean readable="VALID".equals(row.validity());
            if(readable) try { requireReadable(scope,agentId,json.convertValue(item.path("ref"),BiddingTypes.Ref.class)); }
                catch(BiddingApiException revoked) { if(revoked.status()==403 || revoked.status()==404) readable=false; else throw revoked; }
            item.put("validity",readable?"VALID":"UNAVAILABLE");
            if(readable) {
                ObjectNode saved=read(row.contentJson());
                item.put("title",saved.path("title").asText()).put("applicability",saved.path("applicability").asText())
                        .put("selectedAt",saved.path("selectedAt").asText());
            }
        }
        var accepted=jdbc.query("SELECT presales_project_id,release_id,digest,snapshot_json,received_at FROM mate_bidding_handoff WHERE workspace_id=? AND project_id=? ORDER BY received_at DESC",
                (rs,n)->new HandoffRow(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getTimestamp(5)),scope.workspaceId(),scope.projectId());
        var presalesService=presales.getIfAvailable();
        for(var row:accepted) {
            String id="presales:"+row.presalesProjectId()+":"+row.releaseId();
            ObjectNode ref=json.createObjectNode().put("kind","material").put("id",id).put("version",1).put("digest",row.digest());
            ObjectNode item=items.addObject().put("source","PRESALES_RELEASE").put("releaseId",row.releaseId()).put("receivedAt",row.receivedAt().toInstant().toString())
                    .put("digest",row.digest()).put("validity","UNAVAILABLE");
            item.set("ref",ref);
            if(presalesService==null) continue;
            try {
                ObjectNode snapshot=presalesService.handoff(scope.workspaceId(),row.presalesProjectId(),row.releaseId());
                if(!row.digest().equals(digest(snapshot))) continue;
                requireReadable(scope,agentId,json.treeToValue(ref,BiddingTypes.Ref.class));
                item.put("validity","VALID").put("title",snapshot.path("solution").path("title").asText(""));
                if(snapshot.path("solution").has("version")) item.put("solutionVersion",snapshot.path("solution").path("version").asInt());
                if(snapshot.path("release").hasNonNull("publishedAt")) item.put("publishedAt",snapshot.path("release").path("publishedAt").asText());
            } catch(vip.mate.semantic.web.SemanticApiException denied) {
                if(denied.status()!=403 && denied.status()!=404 && denied.status()!=409) throw denied;
            } catch(BiddingApiException denied) {
                if(denied.status()!=403 && denied.status()!=404 && denied.status()!=409) throw denied;
            } catch(Exception invalidRef) {
                throw new IllegalStateException(invalidRef);
            }
        }
        return result;
    }

    public ObjectNode snapshot(BiddingTypes.Scope scope,String agentId,java.util.List<BiddingTypes.Ref> refs) {
        access.requireReaderActor(scope,scope.actorId());
        ObjectNode result=json.createObjectNode(); var items=result.putArray("items");
        if(refs==null) return result;
        for(var ref:refs) if(ref!=null && "material".equals(ref.kind())) {
            requireReadable(scope,agentId,ref);
            var row=jdbc.queryForMap("SELECT content_json,access_ref_json,validity,source_kind FROM mate_bidding_material WHERE workspace_id=? AND project_id=? AND external_id=? AND version=? AND digest=?",
                    scope.workspaceId(),scope.projectId(),ref.id(),ref.version(),ref.digest());
            ObjectNode item=items.addObject(); item.set("ref",json.valueToTree(ref));
            ObjectNode content=read((String)row.get("content_json"));
            if("PRESALES_RELEASE".equals(row.get("source_kind"))) {
                item.set("content",content);
                item.put("title",content.path("solution").path("title").asText(""));
                item.put("applicability","已发布售前版本的冻结交接资料");
                item.put("selectedAt",read((String)row.get("access_ref_json")).path("receivedAt").asText(""));
            }
            else { item.set("content",content); item.set("accessRef",read((String)row.get("access_ref_json"))); }
            item.put("source",(String)row.get("source_kind")).put("validity",(String)row.get("validity"));
        }
        return result;
    }

    public void requireReadable(BiddingTypes.Scope scope,String agentId,BiddingTypes.Ref ref) {
        if(ref==null || !"material".equals(ref.kind())) return;
        access.requireReaderActor(scope,scope.actorId());
        ObjectNode project=repository.findProject(scope.workspaceId(),scope.projectId());
        if(project==null || agentId==null || !agentId.equals(project.path("bindings").path("writer").path("agentId").asText()))
            throw BiddingAccess.error(403,"MATERIAL_UNAVAILABLE","Material is no longer bound to the active writer");
        var rows=jdbc.query("SELECT access_ref_json,validity,source_kind,content_json FROM mate_bidding_material WHERE workspace_id=? AND project_id=? AND external_id=? AND version=? AND digest=?",
                (rs,n)->new MaterialAccess(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4)),scope.workspaceId(),scope.projectId(),ref.id(),ref.version(),ref.digest());
        if(rows.isEmpty()) throw BiddingAccess.error(404,"NOT_FOUND","Material not found");
        var material=rows.getFirst();
        if(!"VALID".equals(material.validity())) throw BiddingAccess.error(403,"MATERIAL_UNAVAILABLE","Material is no longer valid");
        ObjectNode link=read(material.accessRef());
        if("PRESALES_RELEASE".equals(material.sourceKind())) {
            AgentEntity employee=requireAgent(scope,agentId);
            var service=presales.getIfAvailable();
            if(service==null) throw BiddingAccess.error(403,"MATERIAL_UNAVAILABLE","Presales authorization cannot be rechecked");
            ObjectNode current;
            try { current=service.handoff(scope.workspaceId(),link.path("presalesProjectId").asText(),link.path("releaseId").asText()); }
            catch(vip.mate.semantic.web.SemanticApiException denied) { throw BiddingAccess.error(403,"MATERIAL_UNAVAILABLE","Presales source access was revoked"); }
            if(!ref.digest().equals(sha(write(current)))) throw BiddingAccess.error(403,"MATERIAL_UNAVAILABLE","Presales source snapshot is no longer valid");
            var kbService=knowledgeBases.getIfAvailable();
            if(kbService==null) throw BiddingAccess.error(403,"MATERIAL_UNAVAILABLE","Knowledge authorization cannot be rechecked");
            for(var source:current.path("materials")) {
                long kbId=parseId(source.path("kbId").asText());
                WikiKnowledgeBaseEntity visible=kbService.findVisibleById(employee.getId(),kbId);
                if(visible==null || !scope.workspaceId().equals(String.valueOf(visible.getWorkspaceId())) || !active(visible.getDeleted()))
                    throw BiddingAccess.error(403,"MATERIAL_UNAVAILABLE","Presales source access was revoked");
            }
            return;
        }
        long kbId=link.path("knowledgeBaseId").asLong(-1), pageId=link.path("pageId").asLong(-1);
        if(agentId==null || agentId.isBlank()) throw BiddingAccess.error(403,"MATERIAL_UNAVAILABLE","Active employee is required");
        var kb=knowledgeBases.getIfAvailable(); var pageService=pages.getIfAvailable();
        if(kb==null || pageService==null) throw BiddingAccess.error(403,"MATERIAL_UNAVAILABLE","Material authorization cannot be rechecked");
        WikiKnowledgeBaseEntity knowledgeBase=kb.findVisibleById(parseId(agentId),kbId);
        WikiPageEntity page=pageService.getById(pageId);
        if(knowledgeBase==null || !scope.workspaceId().equals(String.valueOf(knowledgeBase.getWorkspaceId())) || !active(knowledgeBase.getDeleted())
                || page==null || !active(page.getDeleted()) || page.getKbId()==null || page.getKbId()!=kbId)
            throw BiddingAccess.error(403,"MATERIAL_UNAVAILABLE","Material access was revoked");
    }

    public void requireReadable(BiddingTypes.Scope scope,String agentId,com.fasterxml.jackson.databind.JsonNode refs) {
        if(refs==null || !refs.isArray()) return;
        for(var node:refs) if("material".equals(node.path("kind").asText()))
            requireReadable(scope,agentId,json.convertValue(node,BiddingTypes.Ref.class));
    }
    private boolean active(Integer deleted){ return deleted==null || deleted==0; }
    private AgentEntity requireAgent(BiddingTypes.Scope scope,String agentId) {
        AgentEntity employee=agents.selectById(parseId(agentId));
        if(employee==null || !Boolean.TRUE.equals(employee.getEnabled()) || !active(employee.getDeleted())
                || employee.getWorkspaceId()==null || !scope.workspaceId().equals(employee.getWorkspaceId().toString()))
            throw BiddingAccess.error(403,"MATERIAL_UNAVAILABLE","Selected employee is unavailable in this workspace");
        return employee;
    }
    private long parseId(String value){ try { long id=Long.parseLong(value); if(id>0)return id; } catch(Exception ignored){} throw BiddingAccess.error(404,"NOT_FOUND","Knowledge source not found"); }
    private String sha(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private String digest(ObjectNode value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(value)));}catch(Exception e){throw new IllegalStateException(e);}}
    private String requestDigest(BiddingTypes.Scope scope,BiddingTypes.Command command) {
        ObjectNode envelope=json.createObjectNode().put("projectId",scope.projectId()).put("action",command.action());
        envelope.set("expected",json.valueToTree(command.expected())); envelope.set("payload",command.payload());
        return sha(write(envelope));
    }
    private String write(ObjectNode n){try{return json.writeValueAsString(n);}catch(Exception e){throw new IllegalStateException(e);}}
    private ObjectNode read(String raw){try{return (ObjectNode)json.readTree(raw);}catch(Exception e){throw new IllegalStateException(e);}}
    private record MaterialRow(String externalId,long version,String digest,String accessRef,String contentJson,String validity) {}
    private record HandoffRow(String presalesProjectId,String releaseId,String digest,String snapshotJson,java.sql.Timestamp receivedAt) {}
    private record MaterialAccess(String accessRef,String validity,String sourceKind,String contentJson) {}
}

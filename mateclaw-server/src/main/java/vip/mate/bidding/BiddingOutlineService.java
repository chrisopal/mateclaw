package vip.mate.bidding;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BiddingOutlineService implements BiddingResultHandler {
    private static final String SKILL="bidding-outline-planning", KIND="outline", OBJECT="current";
    private static final int MAX_CHAPTERS=300, MAX_DEPTH=6;
    private final JdbcTemplate jdbc; private final ObjectMapper json; private final BiddingAccess access;
    private final BiddingProjectService projects; private final BiddingDependencies dependencies;
    private final BiddingTaskService tasks;
    private final BiddingSkillValidator validator;
    private final BiddingMaterials materials;
    public BiddingOutlineService(JdbcTemplate jdbc,ObjectMapper json,BiddingAccess access,BiddingProjectService projects,
            BiddingDependencies dependencies,BiddingTaskService tasks,BiddingSkillValidator validator,BiddingMaterials materials) {
        this.jdbc=jdbc; this.json=json; this.access=access; this.projects=projects; this.dependencies=dependencies; this.tasks=tasks; this.validator=validator; this.materials=materials;
    }
    @Override public Set<String> skillIds(){ return Set.of(SKILL); }

    @Transactional
    public ObjectNode dispatch(BiddingTypes.Scope scope,BiddingTypes.Command command) {
        access.requireActor(scope,scope.actorId());
        if(command==null || command.payload()==null || command.expected()==null) throw BiddingAccess.error(400,"INVALID_REQUEST","Outline dispatch needs a baseline reference");
        BiddingTypes.Ref baseline=command.expected(); requireConfirmedBaseline(scope,baseline);
        dependencies.validate(scope,List.of(baseline));
        ObjectNode project=projects.get(scope); JsonNode writer=project.path("bindings").path("writer");
        String agent=writer.path("agentId").asText("");
        if(agent.isBlank()) return json.createObjectNode().put("status","CONFIGURATION_REQUIRED").put("todo","Bind a writer with the outline planning skill");
        JsonNode pins=writer.path("skillPins"); String skillDigest=null,skillId=null;
        for(JsonNode pin:pins) {
            String pinnedId=pin.path("skillId").asText("");
            String name=jdbc.query("SELECT name FROM mate_skill WHERE id=? AND workspace_id=? AND deleted=0",rs->rs.next()?rs.getString(1):null,number(pinnedId),number(scope.workspaceId()));
            if(SKILL.equals(name)) {skillDigest=pin.path("digest").asText(null);skillId=pinnedId;break;}
        }
        if(skillDigest==null || skillDigest.isBlank() || skillId==null) return json.createObjectNode().put("status","CONFIGURATION_REQUIRED").put("todo","Bind a writer with the outline planning skill");
        List<BiddingTypes.Ref> materialRefs=selectedMaterialRefs(scope);
        List<BiddingTypes.Ref> inputRefs=new ArrayList<>(); inputRefs.add(baseline); inputRefs.addAll(materialRefs);
        dependencies.validate(scope,inputRefs);
        ObjectNode input=outlineInput(scope,baseline,materialRefs);
        String operation=command.operationId()==null||command.operationId().isBlank()?"outline:"+baseline.version()+":"+baseline.digest():command.operationId();
        ObjectNode result=tasks.enqueue(scope,new BiddingTypes.Command(operation,readRef(project.path("ref")),"DISPATCH_OUTLINE",json.createObjectNode()),skillId,OBJECT,inputRefs,input);
        result.put("status",result.path("status").asText("QUEUED")); return result;
    }

    @Override @Transactional
    public BiddingTypes.Ref accept(BiddingTypes.Claim claim,ObjectNode payload) {
        BiddingTypes.Scope scope=claim.scope(); dependencies.validate(scope,claim.inputRefs());
        BiddingTypes.Ref baseline=claim.inputRefs().stream().filter(r->"analysisBaseline".equals(r.kind())).findFirst()
                .orElseThrow(()->BiddingAccess.error(422,"OUTLINE_BASELINE_REQUIRED","Confirmed baseline is required"));
        requireConfirmedBaseline(scope,baseline); List<BiddingTypes.Ref> materialRefs=claim.inputRefs().stream().filter(r->"material".equals(r.kind())).toList();
        validatePayload(payload,outlineInput(scope,baseline,materialRefs),false);
        return saveRevision(scope,payload,claim.inputRefs(),"CANDIDATE",false);
    }

    @Transactional
    public ObjectNode save(BiddingTypes.Scope scope,BiddingTypes.Command command) {
        validateCommand(command,"SAVE_OUTLINE"); access.requireActor(scope,scope.actorId());
        lockProject(scope);
        String requestDigest=requestDigest(command); ObjectNode replay=operation(scope,command,requestDigest); if(replay!=null)return replay;
        BiddingTypes.Ref expected=command.expected(); BiddingTypes.Ref head=head(scope); if(head==null) head=emptyHead(scope);
        if(!same(expected,head)) throw BiddingAccess.error(409,"OUTLINE_VERSION_CONFLICT","Outline changed; reload before saving");
        JsonNode rawPayload=command.payload().path("payload");
        if(!rawPayload.isObject()) throw BiddingAccess.error(422,"OUTLINE_SCHEMA","Outline payload must be an object");
        ObjectNode payload=(ObjectNode)rawPayload;
        BiddingTypes.Ref baseline=baselineRef(scope); if(baseline==null) throw BiddingAccess.error(409,"BASELINE_NOT_CONFIRMED","Confirm analysis before editing the outline");
        List<BiddingTypes.Ref> materialRefs=selectedMaterialRefs(scope); List<BiddingTypes.Ref> inputRefs=new ArrayList<>();inputRefs.add(baseline);inputRefs.addAll(materialRefs);
        dependencies.validate(scope,inputRefs); validatePayload(payload,outlineInput(scope,baseline,materialRefs),false);
        BiddingTypes.Ref saved=saveRevision(scope,payload,inputRefs,"CANDIDATE",true);
        ObjectNode result=json.createObjectNode(); result.set("ref",json.valueToTree(saved)); result.put("status","CANDIDATE");
        result.set("editExpectedRef",json.valueToTree(saved)); storeOperation(scope,command,requestDigest,result); return result;
    }

    @Transactional
    public ObjectNode confirm(BiddingTypes.Scope scope,BiddingTypes.Command command) {
        validateCommand(command,"CONFIRM_OUTLINE"); access.requireApprover(scope); lockProject(scope);
        String requestDigest=requestDigest(command); ObjectNode replay=operation(scope,command,requestDigest); if(replay!=null)return replay;
        BiddingTypes.Ref head=head(scope), expected=command.expected(); if(head==null) head=emptyHead(scope);
        if(!same(expected,head)) throw BiddingAccess.error(409,"OUTLINE_VERSION_CONFLICT","Outline changed; reload before confirming");
        BiddingTypes.Ref candidate=readRef(command.payload().path("outlineRef"));
        if(candidate==null || !KIND.equals(candidate.kind()) || !OBJECT.equals(candidate.id())) throw BiddingAccess.error(422,"OUTLINE_REF_INVALID","Choose an exact outline candidate");
        JsonNode row=revision(scope,candidate);
        if(row==null || !"CANDIDATE".equals(row.path("status").asText())) throw BiddingAccess.error(404,"NOT_FOUND","Outline candidate not found");
        BiddingTypes.Ref baseline=baselineRef(scope);
        if(baseline==null) throw BiddingAccess.error(409,"BASELINE_NOT_CONFIRMED","Confirm analysis before confirming outline");
        dependencies.validate(scope,List.of(baseline)); List<BiddingTypes.Ref> chosenRefs=refs(row.path("refs")); dependencies.validate(scope,chosenRefs);
        List<BiddingTypes.Ref> materialRefs=chosenRefs.stream().filter(r->"material".equals(r.kind())).toList();
        validatePayload((ObjectNode)row.path("payload"),outlineInput(scope,baseline,materialRefs),true);
        BiddingTypes.Ref confirmed=candidate;
        setRevisionStatus(scope,candidate,"CONFIRMED"); setHead(scope,confirmed);
        jdbc.update("INSERT INTO mate_bidding_decision(id,workspace_id,project_id,target_ref_json,decision,reason,actor_id,created_at) VALUES(?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(),scope.workspaceId(),scope.projectId(),write(confirmed),"CONFIRM_OUTLINE",command.payload().path("reason").asText(null),scope.actorId(),Timestamp.from(Instant.now()));
        ObjectNode result=json.createObjectNode(); result.set("ref",json.valueToTree(confirmed)); result.put("status","CONFIRMED"); storeOperation(scope,command,requestDigest,result); return result;
    }

    @Transactional(readOnly=true)
    public ObjectNode read(BiddingTypes.Scope scope) {
        access.requireReaderActor(scope,scope.actorId()); projects.get(scope);
        BiddingTypes.Ref baseline=baselineRef(scope), edit=head(scope), confirmed=confirmedHead(scope);
        ObjectNode out=json.createObjectNode(); if(baseline==null) out.putNull("baselineRef"); else out.set("baselineRef",json.valueToTree(baseline));
        out.set("editExpectedRef",json.valueToTree(edit==null?emptyHead(scope):edit));
        if(confirmed!=null) { JsonNode r=revision(scope,confirmed); if(r!=null && readable(scope,r)) out.set("confirmed",envelope(r,confirmed)); }
        ArrayNode candidates=out.putArray("candidates");
        for(ObjectNode r:revisions(scope,"CANDIDATE")) if(readable(scope,r)) candidates.add(envelope(r,new BiddingTypes.Ref(KIND,OBJECT,r.path("version").asLong(),r.path("digest").asText())));
        ObjectNode todo=dispatchTodo(scope); if(todo!=null) out.set("dispatchTodo",todo);
        return out;
    }

    public static void requireAcyclic(Map<String,String> parentById) {
        if(parentById==null) throw BiddingAccess.error(422,"OUTLINE_PARENT","Outline parents are required");
        for(String start:parentById.keySet()) {
            Set<String> seen=new HashSet<>(); String id=start; int depth=0;
            while(id!=null) {
                if(!parentById.containsKey(id)) throw BiddingAccess.error(422,"OUTLINE_PARENT","Outline parent does not exist");
                if(!seen.add(id)) throw BiddingAccess.error(422,"OUTLINE_CYCLE","Outline contains a cycle");
                if(++depth>MAX_DEPTH) throw BiddingAccess.error(422,"OUTLINE_DEPTH","Outline exceeds six levels");
                id=parentById.get(id);
            }
        }
    }

    private void validatePayload(ObjectNode p,ObjectNode input,boolean requireCoverage) {
        validator.validateOutline(p);
        if(p==null || !"1".equals(p.path("schemaVersion").asText()) || !p.path("chapters").isArray()) invalid("Outline schema is invalid");
        only(p,Set.of("schemaVersion","chapters","unmappedItems","warnings"));
        ArrayNode chapters=(ArrayNode)p.path("chapters"); if(chapters.size()>MAX_CHAPTERS) throw BiddingAccess.error(422,"OUTLINE_LIMIT","Outline may contain at most 300 chapters");
        Map<String,String> parents=new LinkedHashMap<>(); Map<String,Integer> orders=new HashMap<>(); Set<String> ids=new HashSet<>();
        for(JsonNode c:chapters) {
            only(c,Set.of("id","parentId","order","title","instructions","mandatoryOutlineRefs","requirementRefs","scoringRefs","materialRefs"));
            String id=c.path("id").asText(""); if(id.isBlank() || id.length()>128 || !ids.add(id)) throw BiddingAccess.error(422,"OUTLINE_ID","Chapter IDs must be unique and at most 128 characters");
            String parent=c.path("parentId").isNull()?null:c.path("parentId").asText(null); parents.put(id,parent);
            if(!c.path("order").canConvertToInt() || c.path("order").asInt()<0) invalid("Chapter order must be nonnegative");
            String sibling=Objects.toString(parent,"<root>"); if(orders.putIfAbsent(sibling+"/"+c.path("order").asInt(),c.path("order").asInt())!=null) throw BiddingAccess.error(422,"OUTLINE_ORDER","Sibling chapter order must be unique");
            if(c.path("title").asText().isBlank() || c.path("title").asText().length()>500 || !c.path("instructions").isTextual() || c.path("instructions").asText().length()>4000) invalid("Chapter title and instructions are required and within limits");
            for(String field:List.of("mandatoryOutlineRefs","requirementRefs","scoringRefs","materialRefs")) if(!c.path(field).isArray()) invalid("Chapter references must be arrays: "+field);
        }
        requireAcyclic(parents);
        Set<String> leaf=new HashSet<>(ids); for(String id:ids) if(parents.containsValue(id)) leaf.remove(id);
        Set<String> mandatory=new LinkedHashSet<>(); for(JsonNode item:input.path("profile").path("mandatoryOutline")) mandatory.add(item.path("id").asText());
        Set<String> technical=new LinkedHashSet<>(); for(JsonNode item:input.path("requirements")) if("TECHNICAL".equals(item.path("category").asText())) technical.add(item.path("id").asText());
        Set<String> scoring=new LinkedHashSet<>(); for(JsonNode item:input.path("criteria")) scoring.add(item.path("id").asText());
        Set<String> materials=new LinkedHashSet<>(); for(JsonNode item:input.path("materials").path("items")) materials.add(item.path("ref").path("id").asText());
        Set<String> coveredMandatory=new HashSet<>(),coveredReq=new HashSet<>();
        for(JsonNode c:chapters) {
            Set<String> mandatoryRefs=stringSet(c.path("mandatoryOutlineRefs")), requirementRefs=stringSet(c.path("requirementRefs"));
            for(String id:stringSet(c.path("scoringRefs"))) if(!scoring.contains(id)) throw BiddingAccess.error(422,"OUTLINE_REF_UNKNOWN","Unknown scoring reference");
            for(String id:stringSet(c.path("materialRefs"))) if(!materials.contains(id)) throw BiddingAccess.error(422,"OUTLINE_REF_UNKNOWN","Unknown material reference");
            for(String id:mandatoryRefs) if(!mandatory.contains(id)) throw BiddingAccess.error(422,"OUTLINE_REF_UNKNOWN","Unknown mandatory outline reference");
            for(String id:requirementRefs) if(!technical.contains(id)) throw BiddingAccess.error(422,"OUTLINE_REF_UNKNOWN","Unknown requirement reference");
            if(leaf.contains(c.path("id").asText())) { coveredMandatory.addAll(mandatoryRefs); coveredReq.addAll(requirementRefs); }
        }
        if(requireCoverage&&(!coveredMandatory.containsAll(mandatory)||!coveredReq.containsAll(technical))) throw BiddingAccess.error(422,"OUTLINE_COVERAGE_INCOMPLETE","Every mandatory outline item and technical requirement must map to a leaf chapter");
        if(!p.path("unmappedItems").isArray()||!p.path("warnings").isArray()) invalid("unmappedItems and warnings must be arrays");
        for(JsonNode item:p.path("unmappedItems")) if(!item.isObject()) invalid("unmappedItems entries must be objects");
        for(JsonNode warning:p.path("warnings")) if(!warning.isTextual()||warning.asText().length()>1000) invalid("warnings must be strings of at most 1000 characters");
    }
    private ObjectNode outlineInput(BiddingTypes.Scope scope,BiddingTypes.Ref baseline,List<BiddingTypes.Ref> materialRefs) {
        JsonNode rev=revision(scope,baseline); if(rev==null||!"CONFIRMED".equals(rev.path("status").asText())) throw BiddingAccess.error(409,"BASELINE_NOT_CONFIRMED","Confirmed baseline is unavailable");
        ObjectNode payload=(ObjectNode)rev.path("payload"); ObjectNode input=json.createObjectNode().put("schemaVersion","1"); input.set("baselineRef",json.valueToTree(baseline));
        ObjectNode profile=json.createObjectNode();
        JsonNode analyses=payload.path("analyses");
        JsonNode p=analyses.path("bidding-tender-profile"); JsonNode m=p.path("mandatoryOutline");
        for(int i=0;i<m.size();i++) { JsonNode item=m.get(i); String id="mandatory-outline-"+i+"-"+sha(canonical(item)); ObjectNode normalized=item.deepCopy(); normalized.put("id",id); ((ArrayNode)ensure(profile,"mandatoryOutline")).add(normalized); }
        ArrayNode requirements=input.putArray("requirements"); for(JsonNode item:analyses.path("bidding-requirement-analysis").path("requirements")) requirements.add(item.deepCopy());
        input.set("criteria",analyses.path("bidding-scoring-analysis").path("criteria").deepCopy());
        profile.set("basicInfo",p.path("basicInfo").deepCopy()); input.set("profile",profile);
        ArrayNode elimination=input.putArray("eliminationItems"); for(JsonNode item:analyses.path("bidding-elimination-analysis").path("items")) elimination.add(item.deepCopy());
        String agent=projects.get(scope).path("bindings").path("writer").path("agentId").asText("");
        input.set("materials",materials.snapshot(scope,agent,materialRefs));
        return input;
    }
    private List<BiddingTypes.Ref> selectedMaterialRefs(BiddingTypes.Scope scope) {
        ArrayNode items=(ArrayNode)materials.list(scope).path("items"); List<BiddingTypes.Ref> refs=new ArrayList<>();
        for(JsonNode item:items) if("VALID".equals(item.path("validity").asText())) { BiddingTypes.Ref ref=readRef(item.path("ref")); if(ref!=null)refs.add(ref); }
        return List.copyOf(refs);
    }
    private ObjectNode dispatchTodo(BiddingTypes.Scope scope) {
        List<ObjectNode> rows=jdbc.query("SELECT id,target_ref_json,reason FROM mate_bidding_decision WHERE workspace_id=? AND project_id=? AND decision='CONFIRM_ANALYSIS' ORDER BY created_at DESC,id DESC",
                (rs,n)->{ObjectNode r=json.createObjectNode();r.put("decisionId",rs.getString(1));r.set("baselineRef",read(rs.getString(2)));r.put("reason",rs.getString(3));return r;},scope.workspaceId(),scope.projectId());
        if(rows.isEmpty()) return null; ObjectNode row=rows.getFirst(); String rawReason=row.path("reason").asText(null);
        if(rawReason==null||rawReason.isBlank()) return null;
        JsonNode reason; try{reason=json.readTree(rawReason);}catch(Exception malformedLegacyReason){return null;}
        if(!reason.isObject()||!reason.path("autoPlanOutline").isBoolean()||!reason.path("autoPlanOutline").asBoolean()) return null;
        BiddingTypes.Ref baseline=readRef(row.path("baselineRef")); if(baseline==null) return null;
        Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM mate_bidding_task WHERE workspace_id=? AND project_id=? AND input_refs_json LIKE ?",Integer.class,
                scope.workspaceId(),scope.projectId(),"%"+baseline.digest()+"%");
        if(count!=null && count>0) return null;
        ObjectNode todo=json.createObjectNode().put("reasonCode","OUTLINE_CONFIGURATION_REQUIRED").put("decisionId",row.path("decisionId").asText()).put("status","TODO");
        todo.set("baselineRef",row.path("baselineRef").deepCopy()); todo.put("action","DISPATCH_OUTLINE");
        return todo;
    }
    private JsonNode ensure(ObjectNode node,String key){ if(!node.path(key).isArray()) node.set(key,json.createArrayNode()); return node.path(key); }
    private BiddingTypes.Ref saveRevision(BiddingTypes.Scope scope,ObjectNode payload,List<BiddingTypes.Ref> refs,String status,boolean moveHead) {
        long version=nextVersion(scope); String raw=canonical(payload),digest=sha(raw); Timestamp now=Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO mate_bidding_revision(id,workspace_id,project_id,kind,object_id,version,payload_json,input_refs_json,status,digest,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(),scope.workspaceId(),scope.projectId(),KIND,OBJECT,version,raw,write(refs),status,digest,now);
        BiddingTypes.Ref ref=new BiddingTypes.Ref(KIND,OBJECT,version,digest); if(moveHead)setHead(scope,ref); return ref;
    }
    private void setHead(BiddingTypes.Scope s,BiddingTypes.Ref r){ ObjectNode n=json.valueToTree(r); jdbc.update("UPDATE mate_bidding_head SET version=?,selected_ref_json=? WHERE workspace_id=? AND project_id=? AND kind=? AND object_id=?",r.version(),write(n),s.workspaceId(),s.projectId(),KIND,OBJECT); if(head(s)==null) jdbc.update("INSERT INTO mate_bidding_head(workspace_id,project_id,kind,object_id,version,selected_ref_json) VALUES(?,?,?,?,?,?)",s.workspaceId(),s.projectId(),KIND,OBJECT,r.version(),write(n)); }
    private BiddingTypes.Ref head(BiddingTypes.Scope s){ return selected(s,KIND,OBJECT); }
    private BiddingTypes.Ref confirmedHead(BiddingTypes.Scope s){ BiddingTypes.Ref r=head(s); if(r==null)return null; JsonNode row=revision(s,r); return row!=null&&"CONFIRMED".equals(row.path("status").asText())?r:null; }
    private BiddingTypes.Ref baselineRef(BiddingTypes.Scope s){ return selected(s,"analysisBaseline","current"); }
    private BiddingTypes.Ref selected(BiddingTypes.Scope s,String kind,String id){ String raw=jdbc.query("SELECT selected_ref_json FROM mate_bidding_head WHERE workspace_id=? AND project_id=? AND kind=? AND object_id=?",rs->rs.next()?rs.getString(1):null,s.workspaceId(),s.projectId(),kind,id); return raw==null?null:readRef(read(raw)); }
    private BiddingTypes.Ref emptyHead(BiddingTypes.Scope s){ return new BiddingTypes.Ref(KIND,OBJECT,0,sha(s.workspaceId()+":"+s.projectId()+":"+KIND+":"+OBJECT+":0")); }
    private long nextVersion(BiddingTypes.Scope s){ Long v=jdbc.queryForObject("SELECT COALESCE(MAX(version),0)+1 FROM mate_bidding_revision WHERE workspace_id=? AND project_id=? AND kind=? AND object_id=?",Long.class,s.workspaceId(),s.projectId(),KIND,OBJECT); return v==null?1:v; }
    private JsonNode revision(BiddingTypes.Scope s,BiddingTypes.Ref r){ return jdbc.query("SELECT payload_json,input_refs_json,status,digest FROM mate_bidding_revision WHERE workspace_id=? AND project_id=? AND kind=? AND object_id=? AND version=? AND digest=?",rs->rs.next()?row(rs):null,s.workspaceId(),s.projectId(),r.kind(),r.id(),r.version(),r.digest()); }
    private List<ObjectNode> revisions(BiddingTypes.Scope s,String status){ return jdbc.query("SELECT kind,object_id,version,payload_json,input_refs_json,status,digest FROM mate_bidding_revision WHERE workspace_id=? AND project_id=? AND kind=? AND object_id=? AND status=? ORDER BY version DESC",(rs,n)->{ObjectNode r=json.createObjectNode();r.put("kind",rs.getString("kind"));r.put("objectId",rs.getString("object_id"));r.put("version",rs.getLong("version"));r.set("payload",read(rs.getString("payload_json")));r.set("refs",read(rs.getString("input_refs_json")));r.put("status",rs.getString("status"));r.put("digest",rs.getString("digest"));return r;},s.workspaceId(),s.projectId(),KIND,OBJECT,status); }
    private ObjectNode row(java.sql.ResultSet rs)throws java.sql.SQLException{ObjectNode r=json.createObjectNode();r.set("payload",read(rs.getString(1)));r.set("refs",read(rs.getString(2)));r.put("status",rs.getString(3));r.put("digest",rs.getString(4));return r;}
    private boolean readable(BiddingTypes.Scope s,JsonNode r){ try{dependencies.validateForComparisonRead(s,refs(r.path("refs")));return true;}catch(BiddingApiException e){if(e.status()==403||e.status()==404||e.status()==409||e.status()==422)return false;throw e;} }
    private ObjectNode envelope(JsonNode r,BiddingTypes.Ref ref){ ObjectNode n=json.createObjectNode(); n.set("ref",json.valueToTree(ref));n.put("status",r.path("status").asText());n.set("payload",r.path("payload").deepCopy());n.set("inputRefs",r.path("refs").deepCopy());return n; }
    private void setRevisionStatus(BiddingTypes.Scope s,BiddingTypes.Ref r,String status){jdbc.update("UPDATE mate_bidding_revision SET status=? WHERE workspace_id=? AND project_id=? AND kind=? AND object_id=? AND version=? AND digest=?",status,s.workspaceId(),s.projectId(),r.kind(),r.id(),r.version(),r.digest());}
    private void requireConfirmedBaseline(BiddingTypes.Scope s,BiddingTypes.Ref r){if(!"analysisBaseline".equals(r.kind())||!"current".equals(r.id())||!r.equals(baselineRef(s)))throw BiddingAccess.error(409,"DEPENDENCY_STALE","Baseline is no longer current");JsonNode row=revision(s,r);if(row==null||!"CONFIRMED".equals(row.path("status").asText()))throw BiddingAccess.error(409,"BASELINE_NOT_CONFIRMED","Baseline must be confirmed");}
    private void lockProject(BiddingTypes.Scope s){ if(jdbc.query("SELECT id FROM mate_bidding_project WHERE workspace_id=? AND id=? FOR UPDATE",rs->rs.next()?rs.getString(1):null,s.workspaceId(),s.projectId())==null)throw BiddingAccess.error(404,"NOT_FOUND","Project not found"); }
    private void validateCommand(BiddingTypes.Command c,String action){if(c==null||!action.equals(c.action())||c.payload()==null||c.expected()==null||c.operationId()==null||c.operationId().isBlank()||c.operationId().length()>128)throw BiddingAccess.error(400,"INVALID_REQUEST",action+" request is invalid");}
    private String requestDigest(BiddingTypes.Command c){return sha(canonical(Map.of("action",c.action(),"expected",c.expected(),"payload",c.payload())));}
    private ObjectNode operation(BiddingTypes.Scope s,BiddingTypes.Command c,String digest){List<String> rows=jdbc.query("SELECT request_digest,result_json FROM mate_bidding_operation WHERE workspace_id=? AND actor_id=? AND operation_id=?",(rs,n)->{if(!digest.equals(rs.getString(1)))throw BiddingAccess.error(409,"OPERATION_CONFLICT","operationId was used for a different request");return rs.getString(2);},s.workspaceId(),s.actorId(),c.operationId());return rows.isEmpty()?null:(ObjectNode)read(rows.getFirst());}
    private void storeOperation(BiddingTypes.Scope s,BiddingTypes.Command c,String digest,ObjectNode result){jdbc.update("INSERT INTO mate_bidding_operation(workspace_id,actor_id,operation_id,request_digest,result_json,created_at) VALUES(?,?,?,?,?,?)",s.workspaceId(),s.actorId(),c.operationId(),digest,write(result),Timestamp.from(Instant.now()));}
    private void only(JsonNode n,Set<String> allowed){if(!n.isObject())invalid("Expected an object");n.fieldNames().forEachRemaining(k->{if(!allowed.contains(k))throw BiddingAccess.error(422,"OUTLINE_SCHEMA","Unknown outline field: "+k);});}
    private void invalid(String s){throw BiddingAccess.error(422,"OUTLINE_SCHEMA",s);}
    private boolean same(BiddingTypes.Ref a,BiddingTypes.Ref b){return a!=null&&b!=null&&a.equals(b);}
    private BiddingTypes.Ref readRef(JsonNode n){try{return json.treeToValue(n,BiddingTypes.Ref.class);}catch(Exception e){return null;}}
    private List<BiddingTypes.Ref> refs(JsonNode n){try{return json.convertValue(n,new TypeReference<List<BiddingTypes.Ref>>(){});}catch(Exception e){throw BiddingAccess.error(422,"DEPENDENCY_INVALID","Revision references are invalid");}}
    private Set<String> stringSet(JsonNode n){Set<String>s=new HashSet<>();if(n.isArray())for(JsonNode x:n){if(!x.isTextual()||x.asText().isBlank()||x.asText().length()>128||!s.add(x.asText()))invalid("References must be unique nonempty IDs up to 128 characters");}return s;}
    private JsonNode read(String s){try{return json.readTree(s);}catch(Exception e){throw new IllegalStateException("Stored outline JSON is invalid",e);}}
    private String write(Object o){try{return json.writeValueAsString(o);}catch(Exception e){throw new IllegalStateException(e);}}
    private String canonical(Object o){try{return json.writeValueAsString(json.valueToTree(o));}catch(Exception e){throw new IllegalStateException(e);}}
    private String sha(String s){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private Long number(String value){try{return Long.valueOf(value);}catch(Exception e){throw BiddingAccess.error(422,"SKILL_PIN_INVALID","Writer skill pin is invalid");}}
}

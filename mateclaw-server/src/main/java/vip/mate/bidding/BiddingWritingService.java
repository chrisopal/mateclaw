package vip.mate.bidding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Chapter candidates, human edits and assembled draft manuscripts. */
@Service
public class BiddingWritingService implements BiddingResultHandler {
    private static final String SKILL="bidding-technical-writing", KIND="chapter", MANUSCRIPT="manuscript";
    private final JdbcTemplate jdbc; private final ObjectMapper json; private final BiddingAccess access;
    private final BiddingProjectService projects; private final BiddingRepository repository;
    private final BiddingDependencies dependencies; private final BiddingTaskService tasks;
    private final BiddingMaterials materials; private final BiddingContentBlocks blocks=new BiddingContentBlocks();
    private final BiddingSkillValidator validator;
    public BiddingWritingService(JdbcTemplate jdbc,ObjectMapper json,BiddingAccess access,BiddingProjectService projects,
            BiddingRepository repository,BiddingDependencies dependencies,BiddingTaskService tasks,BiddingMaterials materials,BiddingSkillValidator validator) {
        this.jdbc=jdbc;this.json=json;this.access=access;this.projects=projects;this.repository=repository;this.dependencies=dependencies;this.tasks=tasks;this.materials=materials;this.validator=validator;
    }
    @Override public Set<String> skillIds(){return Set.of(SKILL);}

    @Transactional
    public ObjectNode dispatch(BiddingTypes.Scope scope,BiddingTypes.Command command) {
        access.requireActor(scope,scope.actorId()); validate(command,"DISPATCH_WRITING"); lockProject(scope);
        only(command.payload(),Set.of("outlineRef","chapterIds","materialRefs","retryOfTaskId"));
        String digest=requestDigest(command); ObjectNode replay=operation(scope,command,digest);if(replay!=null)return replay;
        BiddingTypes.Ref outline=ref(command.payload().path("outlineRef")); requireConfirmedOutline(scope,outline);
        List<BiddingTypes.Ref> outlineRefs=repository.businessRefs(repository.businessRevision(scope,outline)); dependencies.validate(scope,outlineRefs);
        ObjectNode outlinePayload=payload(scope,outline); JsonNode chapterArray=outlinePayload.path("chapters");
        BiddingTypes.Ref baselineRef=repository.businessRefs(repository.businessRevision(scope,outline)).stream().filter(r->"analysisBaseline".equals(r.kind())).findFirst().orElseThrow();
        JsonNode baselinePayload=repository.businessRevision(scope,baselineRef);JsonNode analyses=baselinePayload.path("analyses");
        Set<String> leaves=leafIds(chapterArray); if(!command.payload().path("chapterIds").isArray())throw BiddingAccess.error(422,"CHAPTER_REQUIRED","chapterIds must be an array"); ArrayNode requested=(ArrayNode)command.payload().path("chapterIds");
        LinkedHashSet<String> ids=new LinkedHashSet<>();for(JsonNode id:requested){if(!id.isTextual()||!leaves.contains(id.asText()))throw BiddingAccess.error(422,"CHAPTER_INVALID","Only confirmed leaf chapters can be written");ids.add(id.asText());}
        if(ids.isEmpty())throw BiddingAccess.error(422,"CHAPTER_REQUIRED","Select at least one leaf chapter");
        List<BiddingTypes.Ref> outlineMaterials=outlineRefs.stream().filter(r->"material".equals(r.kind())).toList();
        Set<BiddingTypes.Ref> available=new HashSet<>(); for(JsonNode item:materials.snapshot(scope,writerId(scope),outlineMaterials).path("items")) if(item.path("validity").asText().equals("VALID"))available.add(ref(item.path("ref")));
        if(command.payload().has("materialRefs")&&!command.payload().path("materialRefs").isArray())throw BiddingAccess.error(422,"MATERIAL_REF_INVALID","materialRefs must be an array");
        ArrayNode requestedMaterials=command.payload().path("materialRefs") instanceof ArrayNode a?a:json.createArrayNode();
        List<BiddingTypes.Ref> selected=new ArrayList<>(); for(JsonNode n:requestedMaterials){BiddingTypes.Ref r=ref(n);if(r==null||!"material".equals(r.kind())||!available.contains(r))throw BiddingAccess.error(403,"MATERIAL_UNAVAILABLE","Writing material is outside the confirmed outline snapshot");if(!selected.contains(r))selected.add(r);}
        if(selected.isEmpty())selected.addAll(outlineRefs.stream().filter(r->"material".equals(r.kind())).toList());
        dependencies.validate(scope,append(List.of(outline),selected));
        ObjectNode result=json.createObjectNode().put("status","QUEUED");ArrayNode taskItems=result.putArray("tasks");
        String retryTask=command.payload().path("retryOfTaskId").asText(null);
        if(retryTask!=null&&!retryTask.isBlank()&&ids.size()!=1)throw BiddingAccess.error(422,"RETRY_TARGET_INVALID","A retry targets exactly one chapter");
        for(String id:ids) {
            JsonNode chapter=findChapter(chapterArray,id); BiddingTypes.Ref current=head(scope,id),guard=current==null?emptyHead(scope,id):current;
            ObjectNode input=json.createObjectNode();input.set("baselineRef",json.valueToTree(baselineRef));input.set("outlineRef",json.valueToTree(outline));input.put("chapterId",id);
            input.set("requirements",selectById(analyses.path("bidding-requirement-analysis").path("requirements"),chapter.path("requirementRefs")));
            input.set("criteria",selectById(analyses.path("bidding-scoring-analysis").path("criteria"),chapter.path("scoringRefs")));input.set("materials",materials.snapshot(scope,writerId(scope),selected));
            if(current!=null)input.set("previousChapterRef",json.valueToTree(current));
            input.set("selectedFindingRefs",json.createArrayNode());input.set("_chapterHeadGuard",json.valueToTree(guard));
            // The prior chapter is frozen provenance and is guarded separately. It is
            // intentionally not a current dependency: replacing this chapter must not
            // make its own newly-adopted candidate stale.
            List<BiddingTypes.Ref> taskRefs=append(List.of(outline),selected);dependencies.validate(scope,taskRefs);
            ObjectNode task;
            if(retryTask!=null&&!retryTask.isBlank()) {
                String snapshot=jdbc.query("SELECT input_json FROM mate_bidding_task WHERE id=? AND workspace_id=? AND project_id=?",rs->rs.next()?rs.getString(1):null,retryTask,scope.workspaceId(),scope.projectId());
                if(snapshot==null||!read(snapshot).path("_bidding").path("targetId").asText().equals("chapter:"+id))throw BiddingAccess.error(404,"NOT_FOUND","Retry task does not belong to this chapter");
                ObjectNode retryPayload=json.createObjectNode().put("taskId",retryTask);task=tasks.retry(scope,new BiddingTypes.Command(operationKey(command.operationId(),id),command.expected(),"RETRY_TASK",retryPayload));
            } else task=tasks.enqueue(scope,new BiddingTypes.Command(operationKey(command.operationId(),id),command.expected(),"DISPATCH_WRITING",json.createObjectNode()),skillId(scope),"chapter:"+id,taskRefs,input);
            task.put("chapterId",id);taskItems.add(task);
        }
        store(scope,command,digest,result);return result;
    }

    @Override @Transactional
    public BiddingTypes.Ref accept(BiddingTypes.Claim claim,ObjectNode output) {
        BiddingTypes.Scope scope=claim.scope(); ObjectNode input=claim.input();
        BiddingTypes.Ref outline=ref(input.path("outlineRef")),guard=ref(input.path("_biddingHeadGuard"));String chapterId=input.path("chapterId").asText();
        if(chapterId.isBlank()||guard==null||outline==null)throw BiddingAccess.error(422,"WRITING_INPUT_INVALID","Frozen chapter and head guard are required");
        BiddingTypes.Ref current=head(scope,chapterId);if(!same(guard,current==null?emptyHead(scope,chapterId):current))throw BiddingAccess.error(409,"CHAPTER_STALE","Chapter changed while writing was in progress");
        dependencies.validate(scope,claim.inputRefs());requireConfirmedOutline(scope,outline);
        ObjectNode chapter=output!=null&&output.path("chapter").isObject()?(ObjectNode)output.path("chapter"):null;
        if(chapter==null||!chapterId.equals(chapter.path("chapterId").asText()))throw BiddingAccess.error(422,"WRITING_OUTPUT_INVALID","Output chapter does not match the assigned chapter");
        validator.validateWriting(output,claim.inputRefs(),input.path("materials")); validateEvidenceEnvelope(output);requireResponseCoverage(output.path("responses"),findChapter(payload(scope,outline).path("chapters"),chapterId));validator.validateWritingEvidence(output,input);
        ObjectNode stored=output.deepCopy();ObjectNode privateData=stored.putObject("_bidding");privateData.set("headGuard",json.valueToTree(guard));privateData.set("outlineRef",json.valueToTree(outline));
        if(input.path("previousChapterRef").isObject())privateData.set("previousChapterRef",input.path("previousChapterRef").deepCopy());
        return saveRevision(scope,chapterId,stored,claim.inputRefs(),"CANDIDATE");
    }

    @Transactional
    public ObjectNode edit(BiddingTypes.Scope scope,BiddingTypes.Command command) {
        access.requireActor(scope,scope.actorId());validate(command,"EDIT_CHAPTER");lockProject(scope);String digest=requestDigest(command);ObjectNode replay=operation(scope,command,digest);if(replay!=null)return replay;
        only(command.payload(),Set.of("chapterId","blocks","responses","citations","missingMaterials","unresolvedItems"));String id=required(command.payload(),"chapterId");BiddingTypes.Ref current=head(scope,id),expected=current==null?emptyHead(scope,id):current;if(!same(command.expected(),expected))throw conflict();
        ObjectNode chapter=json.createObjectNode().put("chapterId",id);chapter.set("blocks",copyArray(command.payload().path("blocks")));BiddingTypes.Ref outline=confirmedOutline(scope);if(outline==null)throw BiddingAccess.error(409,"OUTLINE_NOT_CONFIRMED","Confirm an outline before editing chapters");List<BiddingTypes.Ref> allowed=repository.businessRefs(repository.businessRevision(scope,outline)).stream().filter(r->"material".equals(r.kind())).toList();
        ObjectNode payload=json.createObjectNode().put("schemaVersion","1");payload.set("chapter",chapter);for(String key:List.of("responses","citations","missingMaterials","unresolvedItems")){JsonNode value=command.payload().path(key);if(!value.isArray())throw BiddingAccess.error(422,"WRITING_OUTPUT_INVALID",key+" must be an array");payload.set(key,value.deepCopy());}payload.putArray("warnings");
        boolean containsImage=chapter.path("blocks").isArray()&&java.util.stream.StreamSupport.stream(chapter.path("blocks").spliterator(),false).anyMatch(b->"image".equals(b.path("type").asText()));
        ObjectNode snapshot=containsImage?materials.snapshot(scope,writerId(scope),allowed):null;
        validator.validateWriting(payload,allowed,snapshot);
        requireResponseCoverage(payload.path("responses"),findChapter(payload(scope,outline).path("chapters"),id));
        ObjectNode evidencePayload=json.createObjectNode();evidencePayload.set("citations",payload.path("citations"));evidencePayload.set("responses",payload.path("responses"));validator.validateWritingEvidence(evidencePayload,evidenceInput(scope,outline,id,allowed));
        BiddingTypes.Ref saved=saveRevision(scope,id,payload,append(List.of(outline),allowed),"HUMAN_EDIT");advanceHead(scope,id,current,saved);if(current!=null)dependencies.invalidate(scope,current);ObjectNode out=json.createObjectNode().set("ref",json.valueToTree(saved));out.put("status","EDITED");store(scope,command,digest,out);return out;
    }

    @Transactional
    public ObjectNode adopt(BiddingTypes.Scope scope,BiddingTypes.Command command) {
        access.requireActor(scope,scope.actorId());validate(command,"ADOPT_CHAPTER");lockProject(scope);String digest=requestDigest(command);ObjectNode replay=operation(scope,command,digest);if(replay!=null)return replay;
        String id=required(command.payload(),"chapterId");BiddingTypes.Ref current=head(scope,id),expected=current==null?emptyHead(scope,id):current;if(!same(command.expected(),expected))throw conflict();BiddingTypes.Ref candidate=ref(command.payload().path("candidateRef"));JsonNode row=revision(scope,candidate,id);if(row==null||!"CANDIDATE".equals(row.path("status").asText()))throw BiddingAccess.error(404,"NOT_FOUND","Chapter candidate not found");
        List<BiddingTypes.Ref> refs=refs(row.path("refs"));dependencies.validate(scope,refs);BiddingTypes.Ref outline=confirmedOutline(scope);if(outline==null||refs.stream().noneMatch(r->same(r,outline)))throw BiddingAccess.error(409,"CHAPTER_STALE","Candidate outline is no longer confirmed");
        BiddingTypes.Ref guard=ref(row.path("payload").path("_bidding").path("headGuard"));if(!same(guard,expected))throw BiddingAccess.error(409,"CHAPTER_STALE","Candidate was written against a different chapter head");
        advanceHead(scope,id,current,candidate);setStatus(scope,candidate,"SELECTED");if(current!=null)dependencies.invalidate(scope,current);ObjectNode out=json.createObjectNode().set("ref",json.valueToTree(candidate));out.put("status","SELECTED");store(scope,command,digest,out);return out;
    }

    @Transactional
    public ObjectNode assemble(BiddingTypes.Scope scope,BiddingTypes.Command command) {
        access.requireActor(scope,scope.actorId());validate(command,"ASSEMBLE_MANUSCRIPT");lockProject(scope);String digest=requestDigest(command);ObjectNode replay=operation(scope,command,digest);if(replay!=null)return replay;
        only(command.payload(),Set.of("outlineRef","chapterRefs"));
        BiddingTypes.Ref outline=ref(command.payload().path("outlineRef"));requireConfirmedOutline(scope,outline);ObjectNode op=payload(scope,outline);ArrayNode chapterRefs=command.payload().path("chapterRefs") instanceof ArrayNode a?a:null;if(chapterRefs==null)throw BiddingAccess.error(422,"MANUSCRIPT_INVALID","chapterRefs is required");
        Map<String,BiddingTypes.Ref> selected=new HashMap<>();for(JsonNode n:chapterRefs){String id=n.path("chapterId").asText();BiddingTypes.Ref r=ref(n.path("ref"));if(id.isBlank()||r==null||!Objects.equals(r,head(scope,id)))throw BiddingAccess.error(409,"CHAPTER_STALE","Assemble only the exact selected chapter heads");selected.put(id,r);}
        List<String> order=leafOrder(op.path("chapters"));if(selected.size()!=order.size()||!selected.keySet().containsAll(order))throw BiddingAccess.error(422,"MANUSCRIPT_INCOMPLETE","Every confirmed leaf chapter needs a selected revision");
        ObjectNode manuscript=json.createObjectNode().put("schemaVersion","1").put("status","DRAFT_PENDING_REVIEW");manuscript.set("outlineRef",json.valueToTree(outline));ArrayNode contents=manuscript.putArray("chapters");List<BiddingTypes.Ref> refs=new ArrayList<>();refs.add(outline);
        for(String id:order){BiddingTypes.Ref r=selected.get(id);dependencies.validate(scope,refs(scope,r));JsonNode rev=revision(scope,r,id);if(rev==null||!Set.of("SELECTED","HUMAN_EDIT").contains(rev.path("status").asText()))throw BiddingAccess.error(409,"CHAPTER_STALE","Selected chapter is unreadable");JsonNode body=rev.path("payload");requireResponseCoverage(body.path("responses"),findChapter(op.path("chapters"),id));ObjectNode item=contents.addObject().put("chapterId",id);item.set("chapter",body.path("chapter").deepCopy());copyField(body,item,"responses");copyField(body,item,"citations");copyField(body,item,"missingMaterials");copyField(body,item,"unresolvedItems");refs.add(r);}
        if(manuscript.path("chapters").isEmpty())throw BiddingAccess.error(422,"MANUSCRIPT_INCOMPLETE","No chapter content can be assembled");dependencies.validate(scope,refs);String body=canonical(manuscript),refsJson=write(refs),bodyDigest=sha(body);BiddingTypes.Ref saved=jdbc.query("SELECT version FROM mate_bidding_revision WHERE workspace_id=? AND project_id=? AND kind='manuscript' AND object_id='manuscript' AND digest=? AND input_refs_json=? ORDER BY version DESC",rs->rs.next()?new BiddingTypes.Ref(MANUSCRIPT,"manuscript",rs.getLong(1),bodyDigest):null,scope.workspaceId(),scope.projectId(),bodyDigest,refsJson);if(saved==null)saved=saveRevision(scope,MANUSCRIPT,"manuscript",manuscript,refs,"DRAFT_PENDING_REVIEW");ObjectNode out=json.createObjectNode().set("ref",json.valueToTree(saved));out.put("status","DRAFT_PENDING_REVIEW");store(scope,command,digest,out);return out;
    }

    @Transactional(readOnly=true)
    public ObjectNode read(BiddingTypes.Scope scope) {
        access.requireReaderActor(scope,scope.actorId());projects.get(scope);BiddingTypes.Ref outline=confirmedOutline(scope);ObjectNode out=json.createObjectNode();if(outline==null){out.putNull("outlineRef");out.putArray("chapters");return out;}out.set("outlineRef",json.valueToTree(outline));JsonNode tree=payload(scope,outline).path("chapters");ArrayNode chapters=out.putArray("chapters");
        for(String id:leafOrder(tree)){JsonNode source=findChapter(tree,id);ObjectNode item=chapters.addObject().put("chapterId",id).put("title",source.path("title").asText());BiddingTypes.Ref head=head(scope,id),expected=head==null?emptyHead(scope,id):head;item.set("editExpectedRef",json.valueToTree(expected));if(head!=null){JsonNode selected=revision(scope,head,id);if(selected!=null&&dependencies.isCurrent(scope,refs(selected.path("refs"))))item.set("selected",envelope(selected,head));}
            ArrayNode candidates=item.putArray("candidates");for(ObjectNode candidate:revisions(scope,id,"CANDIDATE")){List<BiddingTypes.Ref> candidateRefs=refs(candidate.path("refs"));if(readable(scope,candidateRefs)){BiddingTypes.Ref cr=ref(candidate.path("ref"));JsonNode headGuard=candidate.path("payload").path("_bidding").path("headGuard");boolean currentCandidate=dependencies.isCurrent(scope,candidateRefs)&&same(ref(headGuard),expected);ObjectNode view=envelope(candidate,cr);view.put("status",currentCandidate?"CANDIDATE":"STALE");if(headGuard.isObject())view.set("headGuard",headGuard.deepCopy());candidates.add(view);}}
            ArrayNode taskItems=item.putArray("tasks");for(ObjectNode task:chapterTasks(scope,id))taskItems.add(task);
        }
        ObjectNode ms=latestManuscript(scope);if(ms!=null&&readable(scope,refs(ms.path("refs"))))out.set("manuscript",ms);return out;
    }

    private List<ObjectNode> chapterTasks(BiddingTypes.Scope s,String chapter){return jdbc.query("SELECT id,input_json,status,attempt_count,created_at FROM mate_bidding_task WHERE workspace_id=? AND project_id=? ORDER BY created_at,id",(rs,n)->{try{JsonNode stored=json.readTree(rs.getString(2));if(!("chapter:"+chapter).equals(stored.path("_bidding").path("targetId").asText()))return null;ObjectNode x=json.createObjectNode().put("taskId",rs.getString(1)).put("status",rs.getString(3)).put("attemptCount",rs.getInt(4));Timestamp ts=rs.getTimestamp(5);if(ts!=null)x.put("createdAt",ts.toInstant().toString());return x;}catch(Exception e){return null;}},s.workspaceId(),s.projectId()).stream().filter(Objects::nonNull).toList();}
    private ObjectNode latestManuscript(BiddingTypes.Scope s){return jdbc.query("SELECT kind,object_id,version,digest FROM mate_bidding_revision WHERE workspace_id=? AND project_id=? AND kind='manuscript' ORDER BY version DESC",(rs,n)->{BiddingTypes.Ref r=new BiddingTypes.Ref(rs.getString(1),rs.getString(2),rs.getLong(3),rs.getString(4));JsonNode rev=revision(s,r);return envelope(rev,r);},s.workspaceId(),s.projectId()).stream().findFirst().orElse(null);}
    private BiddingTypes.Ref saveRevision(BiddingTypes.Scope s,String id,ObjectNode p,List<BiddingTypes.Ref> refs,String status){return saveRevision(s,KIND,id,p,refs,status);}
    private BiddingTypes.Ref saveRevision(BiddingTypes.Scope s,String kind,String id,ObjectNode p,List<BiddingTypes.Ref> refs,String status){String raw=canonical(p),d=sha(raw);long v=repository.maxRevisionVersion(s,kind,id)+1;BiddingTypes.Ref r=new BiddingTypes.Ref(kind,id,v,d);repository.insertRevision(UUID.randomUUID().toString(),s.workspaceId(),s.projectId(),kind,id,v,raw,write(refs),status,d,Timestamp.from(Instant.now()));return r;}
    private BiddingTypes.Ref head(BiddingTypes.Scope s,String id){String raw=jdbc.query("SELECT selected_ref_json FROM mate_bidding_head WHERE workspace_id=? AND project_id=? AND kind='chapter' AND object_id=?",rs->rs.next()?rs.getString(1):null,s.workspaceId(),s.projectId(),id);return raw==null?null:ref(read(raw));}
    private BiddingTypes.Ref emptyHead(BiddingTypes.Scope s,String id){return new BiddingTypes.Ref(KIND,id,0,sha(s.workspaceId()+":"+s.projectId()+":"+id+":0"));}
    private void advanceHead(BiddingTypes.Scope s,String id,BiddingTypes.Ref old,BiddingTypes.Ref next){String raw=write(next);int changed;if(old==null){try{changed=jdbc.update("INSERT INTO mate_bidding_head(workspace_id,project_id,kind,object_id,version,selected_ref_json) VALUES(?,?,?, ?,?,?)",s.workspaceId(),s.projectId(),KIND,id,next.version(),raw);}catch(org.springframework.dao.DuplicateKeyException e){throw conflict();}}else changed=jdbc.update("UPDATE mate_bidding_head SET version=?,selected_ref_json=? WHERE workspace_id=? AND project_id=? AND kind=? AND object_id=? AND version=?",next.version(),raw,s.workspaceId(),s.projectId(),KIND,id,old.version());if(changed!=1)throw conflict();}
    private JsonNode revision(BiddingTypes.Scope s,BiddingTypes.Ref r,String id){if(r==null)return null;return jdbc.query("SELECT payload_json,input_refs_json,status,digest FROM mate_bidding_revision WHERE workspace_id=? AND project_id=? AND kind=? AND object_id=? AND version=? AND digest=?",rs->{if(!rs.next())return null;ObjectNode n=json.createObjectNode();n.set("payload",read(rs.getString(1)));n.set("refs",read(rs.getString(2)));n.put("status",rs.getString(3)).put("digest",rs.getString(4));n.set("ref",json.valueToTree(r));return n;},s.workspaceId(),s.projectId(),r.kind(),id,r.version(),r.digest());}
    private JsonNode revision(BiddingTypes.Scope s,BiddingTypes.Ref r){return revision(s,r,r==null?null:r.id());}
    private List<ObjectNode> revisions(BiddingTypes.Scope s,String id,String status){return jdbc.query("SELECT version,digest,payload_json,input_refs_json,status FROM mate_bidding_revision WHERE workspace_id=? AND project_id=? AND kind='chapter' AND object_id=? AND status=? ORDER BY version DESC",(rs,n)->{BiddingTypes.Ref r=new BiddingTypes.Ref(KIND,id,rs.getLong(1),rs.getString(2));ObjectNode x=json.createObjectNode();x.set("ref",json.valueToTree(r));x.set("payload",read(rs.getString(3)));x.set("refs",read(rs.getString(4)));x.put("status",rs.getString(5));return x;},s.workspaceId(),s.projectId(),id,status);}
    private ObjectNode envelope(JsonNode row,BiddingTypes.Ref r){ObjectNode n=json.createObjectNode().set("ref",json.valueToTree(r));if(row!=null){n.put("status",row.path("status").asText());JsonNode body=row.path("payload");ObjectNode visible=body.isObject()?((ObjectNode)body).deepCopy():json.createObjectNode();visible.remove("_bidding");n.set("payload",visible);n.set("inputRefs",row.path("refs").deepCopy());}return n;}
    private boolean readable(BiddingTypes.Scope s,List<BiddingTypes.Ref> r){try{dependencies.validateForComparisonRead(s,r);return true;}catch(BiddingApiException e){if(Set.of(403,404,409,422).contains(e.status()))return false;throw e;}}
    private void setStatus(BiddingTypes.Scope s,BiddingTypes.Ref r,String status){jdbc.update("UPDATE mate_bidding_revision SET status=? WHERE workspace_id=? AND project_id=? AND kind=? AND object_id=? AND version=? AND digest=?",status,s.workspaceId(),s.projectId(),r.kind(),r.id(),r.version(),r.digest());}
    private BiddingTypes.Ref confirmedOutline(BiddingTypes.Scope s){String raw=jdbc.query("SELECT selected_ref_json FROM mate_bidding_head WHERE workspace_id=? AND project_id=? AND kind='outline' AND object_id='current'",rs->rs.next()?rs.getString(1):null,s.workspaceId(),s.projectId());if(raw==null)return null;BiddingTypes.Ref r=ref(read(raw));JsonNode row=revision(s,r);return row!=null&&"CONFIRMED".equals(row.path("status").asText())?r:null;}
    private void requireConfirmedOutline(BiddingTypes.Scope s,BiddingTypes.Ref r){if(r==null||!same(r,confirmedOutline(s)))throw BiddingAccess.error(409,"OUTLINE_STALE","Confirmed outline changed");}
    private ObjectNode payload(BiddingTypes.Scope s,BiddingTypes.Ref r){JsonNode row=revision(s,r);if(row==null)throw BiddingAccess.error(404,"NOT_FOUND","Outline not found");return (ObjectNode)row.path("payload");}
    private void validateEvidenceEnvelope(ObjectNode out){only(out,Set.of("schemaVersion","chapter","responses","citations","missingMaterials","unresolvedItems","warnings"));if(!"1".equals(out.path("schemaVersion").asText()))throw BiddingAccess.error(422,"WRITING_OUTPUT_INVALID","schemaVersion must be 1");for(String key:List.of("responses","citations","missingMaterials","unresolvedItems"))if(!out.path(key).isArray())throw BiddingAccess.error(422,"WRITING_OUTPUT_INVALID",key+" must be an array");}
    private void lockProject(BiddingTypes.Scope s){if(!repository.lockProject(s.workspaceId(),s.projectId()))throw BiddingAccess.error(404,"NOT_FOUND","Project not found");}
    private String skillId(BiddingTypes.Scope s){JsonNode pins=projects.get(s).path("bindings").path("writer").path("skillPins");for(JsonNode pin:pins){String id=pin.path("skillId").asText();try{String name=jdbc.queryForObject("SELECT name FROM mate_skill WHERE id=? AND workspace_id=? AND deleted=0",String.class,Long.valueOf(id),Long.valueOf(s.workspaceId()));if(SKILL.equals(name))return id;}catch(Exception ignored){}}throw BiddingAccess.error(409,"WRITER_SKILL_REQUIRED","Bind the pinned technical-writing skill first");}
    private String writerId(BiddingTypes.Scope s){return projects.get(s).path("bindings").path("writer").path("agentId").asText("");}
    private Set<String> leafIds(JsonNode tree){Set<String> all=new LinkedHashSet<>(),parents=new HashSet<>();for(JsonNode c:tree){String id=c.path("id").asText();all.add(id);if(c.path("parentId").isTextual())parents.add(c.path("parentId").asText());}all.removeAll(parents);return all;}
    private List<String> leafOrder(JsonNode tree){Map<String,List<JsonNode>> children=new LinkedHashMap<>();for(JsonNode node:tree){String parent=node.path("parentId").isTextual()?node.path("parentId").asText():"";children.computeIfAbsent(parent,k->new ArrayList<>()).add(node);}children.values().forEach(list->list.sort(Comparator.comparingInt(n->n.path("order").asInt())));List<String> out=new ArrayList<>();walkLeaves("",children,out);return out;}
    private void walkLeaves(String parent,Map<String,List<JsonNode>> children,List<String> out){for(JsonNode node:children.getOrDefault(parent,List.of())){String id=node.path("id").asText();if(children.getOrDefault(id,List.of()).isEmpty())out.add(id);else walkLeaves(id,children,out);}}
    private String operationKey(String op,String chapter){return sha(op+":"+chapter).substring(0,48);}
    private JsonNode findChapter(JsonNode tree,String id){for(JsonNode c:tree)if(id.equals(c.path("id").asText()))return c;throw BiddingAccess.error(404,"NOT_FOUND","Chapter not found");}
    private ArrayNode selectById(JsonNode source,JsonNode selected){ArrayNode out=json.createArrayNode();if(!selected.isArray())return out;Set<String> wanted=new HashSet<>();selected.forEach(v->wanted.add(v.asText()));if(source.isArray())for(JsonNode item:source)if(wanted.contains(item.path("id").asText()))out.add(item.deepCopy());return out;}
    private ObjectNode evidenceInput(BiddingTypes.Scope scope,BiddingTypes.Ref outline,String chapterId,List<BiddingTypes.Ref> allowedMaterials){List<BiddingTypes.Ref> outlineRefs=repository.businessRefs(repository.businessRevision(scope,outline));BiddingTypes.Ref baseline=outlineRefs.stream().filter(r->"analysisBaseline".equals(r.kind())).findFirst().orElseThrow();JsonNode analyses=repository.businessRevision(scope,baseline).path("analyses");JsonNode c=findChapter(payload(scope,outline).path("chapters"),chapterId);ObjectNode input=json.createObjectNode();input.set("requirements",selectById(analyses.path("bidding-requirement-analysis").path("requirements"),c.path("requirementRefs")));input.set("criteria",selectById(analyses.path("bidding-scoring-analysis").path("criteria"),c.path("scoringRefs")));input.set("materials",materials.snapshot(scope,writerId(scope),allowedMaterials));return input;}
    private void requireResponseCoverage(JsonNode responses,JsonNode outlineChapter){Set<String> required=new HashSet<>();if(outlineChapter.path("requirementRefs").isArray())outlineChapter.path("requirementRefs").forEach(r->required.add(r.asText()));Set<String> answered=new HashSet<>();if(responses.isArray())for(JsonNode response:responses){if(!response.isObject()||!response.path("requirementRef").isTextual()||!required.contains(response.path("requirementRef").asText())||!Set.of("RESPONDED","MISSING_MATERIAL","UNRESOLVED").contains(response.path("status").asText()))throw BiddingAccess.error(422,"RESPONSE_INVALID","Every response must identify an assigned requirement and an explicit status");answered.add(response.path("requirementRef").asText());}if(!answered.containsAll(required)){Set<String> missing=new HashSet<>(required);missing.removeAll(answered);throw BiddingAccess.error(422,"RESPONSE_COVERAGE_INCOMPLETE","Responses are missing for assigned requirements: "+String.join(",",missing));}}
    private List<BiddingTypes.Ref> refs(JsonNode n){try{return json.convertValue(n,new com.fasterxml.jackson.core.type.TypeReference<List<BiddingTypes.Ref>>(){});}catch(Exception e){throw BiddingAccess.error(422,"DEPENDENCY_INVALID","Revision references are invalid");}}
    private List<BiddingTypes.Ref> append(List<BiddingTypes.Ref>a,List<BiddingTypes.Ref>b){List<BiddingTypes.Ref> n=new ArrayList<>(a);for(var r:b)if(!n.contains(r))n.add(r);return List.copyOf(n);}
    private List<BiddingTypes.Ref> refs(BiddingTypes.Scope s,BiddingTypes.Ref r){JsonNode row=revision(s,r);if(row==null)throw BiddingAccess.error(404,"NOT_FOUND","Chapter not found");return refs(row.path("refs"));}
    private void copyField(JsonNode from,ObjectNode to,String field){to.set(field,from.path(field).deepCopy());}
    private ArrayNode copyArray(JsonNode n){return n instanceof ArrayNode a?a.deepCopy():json.createArrayNode();}
    private void only(JsonNode n,Set<String> allowed){if(!n.isObject())throw BiddingAccess.error(422,"WRITING_OUTPUT_INVALID","Expected object");n.fieldNames().forEachRemaining(k->{if(!allowed.contains(k))throw BiddingAccess.error(422,"WRITING_OUTPUT_INVALID","Unknown field: "+k);});}
    private String required(JsonNode n,String k){String s=n.path(k).asText("");if(s.isBlank()||s.length()>128)throw BiddingAccess.error(422,"WRITING_OUTPUT_INVALID",k+" is required");return s;}
    private void validate(BiddingTypes.Command c,String a){if(c==null||!a.equals(c.action())||c.expected()==null||c.payload()==null||c.operationId()==null||c.operationId().isBlank()||c.operationId().length()>128)throw BiddingAccess.error(400,"INVALID_REQUEST",a+" is invalid");}
    private ObjectNode operation(BiddingTypes.Scope s,BiddingTypes.Command c,String d){var o=repository.findOperation(s.workspaceId(),s.actorId(),c.operationId());if(o==null)return null;if(!d.equals(o.digest()))throw BiddingAccess.error(409,"OPERATION_CONFLICT","operationId was used for another request");return o.result();}
    private void store(BiddingTypes.Scope s,BiddingTypes.Command c,String d,ObjectNode r){repository.insertOperation(s.workspaceId(),s.actorId(),c.operationId(),d,"{}",Timestamp.from(Instant.now()));repository.updateOperation(s.workspaceId(),s.actorId(),c.operationId(),write(r));}
    private String requestDigest(BiddingTypes.Command c){return sha(canonical(Map.of("action",c.action(),"expected",c.expected(),"payload",c.payload())));}
    private boolean same(BiddingTypes.Ref a,BiddingTypes.Ref b){return Objects.equals(a,b);}
    private BiddingTypes.Ref ref(JsonNode n){try{return n==null||n.isMissingNode()||n.isNull()?null:json.treeToValue(n,BiddingTypes.Ref.class);}catch(Exception e){return null;}}
    private BiddingApiException conflict(){return BiddingAccess.error(409,"CHAPTER_VERSION_CONFLICT","Chapter changed; reload before saving");}
    private JsonNode read(String raw){try{return json.readTree(raw);}catch(Exception e){throw new IllegalStateException("Stored bidding JSON is invalid",e);}}
    private String write(Object o){try{return json.writeValueAsString(o);}catch(Exception e){throw new IllegalStateException(e);}}
    private String canonical(Object o){return write(json.valueToTree(o));}
    private String sha(String s){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}

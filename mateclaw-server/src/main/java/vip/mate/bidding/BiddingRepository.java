package vip.mate.bidding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class BiddingRepository {
    private static final List<String> ANALYSIS_SKILLS = List.of("bidding-tender-profile", "bidding-elimination-analysis",
            "bidding-requirement-analysis", "bidding-scoring-analysis");
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper json;
    public BiddingRepository(NamedParameterJdbcTemplate jdbc,ObjectMapper json) { this.jdbc=jdbc; this.json=json; }

    /** Claims at most the available worker capacity using persistent compare-and-set state. */
    @org.springframework.transaction.annotation.Transactional
    public synchronized List<BiddingTypes.Claim> claimDue(Instant now,String bootId,int limit) {
        int capacity=Math.min(2,Math.max(0,limit));
        if(capacity==0) return List.of();
        Timestamp at=Timestamp.from(now);
        // A V212-era row has no trustworthy actor. Fail it closed and retain a diagnostic attempt.
        List<LegacyTask> legacy=jdbc.query("SELECT id,workspace_id,project_id,attempt_count FROM mate_bidding_task "
                + "WHERE (actor_id IS NULL OR TRIM(actor_id)='') AND status IN ('QUEUED','WAITING_RETRY')",
            Map.of(),(rs,n)->new LegacyTask(rs.getString(1),rs.getString(2),rs.getString(3),rs.getInt(4)));
        for(LegacyTask task:legacy) {
            int changed=jdbc.update("UPDATE mate_bidding_task SET status='FAILED',active_attempt_id=NULL,attempt_count=attempt_count+1,updated_at=:now "
                    + "WHERE id=:id AND (actor_id IS NULL OR TRIM(actor_id)='') AND status IN ('QUEUED','WAITING_RETRY')",
                Map.of("now",at,"id",task.id()));
            if(changed==1) jdbc.update("INSERT INTO mate_bidding_attempt(id,workspace_id,project_id,task_id,attempt_no,token,state,tool_receipts_json,error_json,started_at,finished_at) "
                    + "VALUES(:id,:workspace,:project,:task,:attempt,:token,'FAILED','[]',:error,:now,:now)",
                Map.of("id",UUID.randomUUID().toString(),"workspace",task.workspaceId(),"project",task.projectId(),"task",task.id(),
                    "attempt",task.attemptCount()+1,"token",UUID.randomUUID()+"."+UUID.randomUUID(),"error","{\"code\":\"TASK_ACTOR_MISSING\",\"category\":\"PERMANENT\",\"resultUnknown\":false}","now",at));
        }
        Integer running=jdbc.queryForObject("SELECT COUNT(*) FROM mate_bidding_task WHERE status='RUNNING'",Map.of(),Integer.class);
        int available=Math.min(capacity,Math.max(0,2-(running==null?0:running)));
        if(available==0) return List.of();
        List<TaskCandidate> candidates=jdbc.query("SELECT t.id,t.workspace_id,t.project_id,t.actor_id,t.agent_id,t.skill_package_id,"
                + "t.config_digest,t.input_json,t.input_refs_json,t.attempt_count,t.cycle_attempt,t.deadline_at,p.skill_id,p.version,p.digest,p.files_json "
                + "FROM mate_bidding_task t LEFT JOIN mate_bidding_skill_package p ON p.id=t.skill_package_id "
                + "WHERE t.status IN ('QUEUED','WAITING_RETRY') AND (t.next_run_at IS NULL OR t.next_run_at<=:now) "
                + "AND t.actor_id IS NOT NULL AND TRIM(t.actor_id)<>'' "
                + "AND NOT EXISTS (SELECT 1 FROM mate_bidding_task r WHERE r.workspace_id=t.workspace_id AND r.status='RUNNING') "
                + "ORDER BY COALESCE(t.next_run_at,t.created_at),t.created_at,t.id LIMIT :limit",
            Map.of("now",at,"limit",Math.min(100,available*10)),(rs,n)->new TaskCandidate(rs.getString("id"),rs.getString("workspace_id"),rs.getString("project_id"),rs.getString("actor_id"),
                rs.getString("agent_id"),rs.getString("skill_package_id"),rs.getString("config_digest"),rs.getString("input_json"),rs.getString("input_refs_json"),
                rs.getInt("attempt_count"),rs.getInt("cycle_attempt"),rs.getTimestamp("deadline_at"),rs.getString("skill_id"),rs.getString("version"),rs.getString("digest"),rs.getString("files_json")));
        List<BiddingTypes.Claim> claims=new ArrayList<>();
        Set<String> claimedWorkspaces=new HashSet<>();
        for(TaskCandidate task:candidates) {
            if(claims.size()>=available) break;
            if(claimedWorkspaces.contains(task.workspaceId())) continue;
            PreparedTask prepared;
            try { prepared=prepareTask(task); }
            catch(RuntimeException invalidSnapshot) {
                failInvalidCandidate(task,at,"TASK_SNAPSHOT_INVALID");
                continue;
            }
            // Workspace row lock makes the no-running-task invariant hold across repository calls.
            if(!lockWorkspace(task.workspaceId())) {
                failInvalidWorkspace(task,at);
                continue;
            }
            String attemptId=UUID.randomUUID().toString(),token=UUID.randomUUID()+"."+UUID.randomUUID();
            int nextAttempt=task.attemptCount()+1;
            int changed=jdbc.update("UPDATE mate_bidding_task SET status='RUNNING',active_attempt_id=:attempt,attempt_count=:count,boot_id=:boot,"
                    + "deadline_at=:deadline,updated_at=:now WHERE id=:id AND status IN ('QUEUED','WAITING_RETRY') AND (next_run_at IS NULL OR next_run_at<=:now) AND active_attempt_id IS NULL "
                    + "AND NOT EXISTS (SELECT 1 FROM mate_bidding_task r WHERE r.workspace_id=:workspace AND r.status='RUNNING' AND r.id<>:id)",
                new MapSqlParameterSource().addValue("attempt",attemptId).addValue("count",nextAttempt).addValue("boot",bootId)
                    .addValue("deadline",Timestamp.from(now.plusSeconds(300))).addValue("now",at).addValue("id",task.id()).addValue("workspace",task.workspaceId()));
            if(changed!=1) continue;
            claimedWorkspaces.add(task.workspaceId());
            jdbc.update("INSERT INTO mate_bidding_attempt(id,workspace_id,project_id,task_id,attempt_no,token,state,tool_receipts_json,started_at) "
                    + "VALUES(:id,:workspace,:project,:task,:attempt,:token,'RUNNING','[]',:now)",
                Map.of("id",attemptId,"workspace",task.workspaceId(),"project",task.projectId(),"task",task.id(),"attempt",nextAttempt,"token",token,"now",at));
            var scope=new BiddingTypes.Scope(task.workspaceId(),task.actorId(),task.projectId());
            claims.add(new BiddingTypes.Claim(scope,task.id(),attemptId,token,nextAttempt,task.cycleAttempt(),
                now.plusSeconds(300),task.agentId(),prepared.pin(),prepared.modelConfigId(),task.configDigest(),prepared.refs(),prepared.input()));
        }
        return List.copyOf(claims);
    }

    @org.springframework.transaction.annotation.Transactional
    public int recoverInterrupted(String bootId,Instant now) {
        Timestamp at=Timestamp.from(now);
        List<String> expired=jdbc.query("SELECT active_attempt_id FROM mate_bidding_task WHERE status='RUNNING' AND (boot_id IS NULL OR boot_id<>:boot OR deadline_at<=:now) AND active_attempt_id IS NOT NULL",
            Map.of("boot",bootId,"now",at),(rs,n)->rs.getString(1));
        int count=jdbc.update("UPDATE mate_bidding_task SET status='FAILED',active_attempt_id=NULL,next_run_at=NULL,deadline_at=NULL,updated_at=:now WHERE status='RUNNING' AND (boot_id IS NULL OR boot_id<>:boot OR deadline_at<=:now)",Map.of("boot",bootId,"now",at));
        for(String attempt:expired) jdbc.update("UPDATE mate_bidding_attempt SET state='FAILED',error_json=:error,finished_at=:now WHERE id=:id AND state='RUNNING'",
            Map.of("error","{\"code\":\"EXECUTION_INTERRUPTED\",\"category\":\"PERMANENT\",\"resultUnknown\":true}","now",at,"id",attempt));
        return count;
    }

    public boolean isTaskRunning(String taskId) {
        Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM mate_bidding_task WHERE id=:id AND status='RUNNING'",Map.of("id",taskId),Integer.class);
        return count!=null && count>0;
    }

    private List<BiddingTypes.Ref> readRefs(String raw) {
        try { return Objects.requireNonNull(json.readValue(raw,new com.fasterxml.jackson.core.type.TypeReference<List<BiddingTypes.Ref>>() {})); }
        catch(Exception e) { throw new IllegalStateException("Invalid persisted bidding references",e); }
    }
    private PreparedTask prepareTask(TaskCandidate task) {
        if(task.packageId()==null || task.skillId()==null || task.skillVersion()==null || task.skillDigest()==null || task.filesJson()==null)
            throw new IllegalStateException("Pinned skill package is missing");
        ObjectNode stored=parseObject(task.inputJson());
        if(!stored.path("input").isObject()) throw new IllegalStateException("Task input is not an object");
        ObjectNode input=(ObjectNode)stored.path("input").deepCopy();
        if(stored.path("_bidding").hasNonNull("targetId")) input.put("_biddingTargetId",stored.path("_bidding").path("targetId").asText());
        if(stored.path("_bidding").path("headGuard").isObject()) input.set("_biddingHeadGuard",stored.path("_bidding").path("headGuard").deepCopy());
        List<BiddingTypes.Ref> refs=readRefs(task.refsJson());
        Map<String,String> files;
        try { files=json.readValue(task.filesJson(),new com.fasterxml.jackson.core.type.TypeReference<Map<String,String>>() {}); }
        catch(Exception e) { throw new IllegalStateException("Invalid pinned skill package",e); }
        if(files==null) throw new IllegalStateException("Pinned skill package files are missing");
        var pin=new BiddingTypes.SkillPin(task.skillId(),task.skillVersion(),task.skillDigest(),Map.copyOf(files));
        return new PreparedTask(input,refs,pin,stored.path("_bidding").path("modelConfigId").asText(null));
    }
    private void failInvalidCandidate(TaskCandidate task,Timestamp now,String code) {
        int changed=jdbc.update("UPDATE mate_bidding_task SET status='FAILED',active_attempt_id=NULL,attempt_count=attempt_count+1,next_run_at=NULL,deadline_at=NULL,updated_at=:now WHERE id=:id AND status IN ('QUEUED','WAITING_RETRY') AND active_attempt_id IS NULL",
            Map.of("id",task.id(),"now",now));
        if(changed==1) jdbc.update("INSERT INTO mate_bidding_attempt(id,workspace_id,project_id,task_id,attempt_no,token,state,tool_receipts_json,error_json,started_at,finished_at) "
                + "VALUES(:id,:workspace,:project,:task,:attempt,:token,'FAILED','[]',:error,:now,:now)",
            Map.of("id",UUID.randomUUID().toString(),"workspace",task.workspaceId(),"project",task.projectId(),"task",task.id(),
                "attempt",task.attemptCount()+1,"token",UUID.randomUUID()+"."+UUID.randomUUID(),
                "error","{\"code\":\""+code+"\",\"category\":\"PERMANENT\",\"resultUnknown\":false}","now",now));
    }
    private boolean lockWorkspace(String workspaceId) {
        try {
            long id=Long.parseLong(workspaceId);
            return !jdbc.query("SELECT id FROM mate_workspace WHERE id=:workspace FOR UPDATE",Map.of("workspace",id),(rs,n)->rs.getLong(1)).isEmpty();
        } catch(NumberFormatException ignored) {
            return false;
        }
    }
    private void failInvalidWorkspace(TaskCandidate task,Timestamp now) {
        int changed=jdbc.update("UPDATE mate_bidding_task SET status='FAILED',active_attempt_id=NULL,attempt_count=attempt_count+1,updated_at=:now WHERE id=:id AND status IN ('QUEUED','WAITING_RETRY') AND active_attempt_id IS NULL",
            Map.of("id",task.id(),"now",now));
        if(changed==1) jdbc.update("INSERT INTO mate_bidding_attempt(id,workspace_id,project_id,task_id,attempt_no,token,state,tool_receipts_json,error_json,started_at,finished_at) "
                + "VALUES(:id,:workspace,:project,:task,:attempt,:token,'FAILED','[]',:error,:now,:now)",
            Map.of("id",UUID.randomUUID().toString(),"workspace",task.workspaceId(),"project",task.projectId(),"task",task.id(),
                "attempt",task.attemptCount()+1,"token",UUID.randomUUID()+"."+UUID.randomUUID(),"error","{\"code\":\"TASK_WORKSPACE_MISSING\",\"category\":\"PERMANENT\",\"resultUnknown\":false}","now",now));
    }
    private record TaskCandidate(String id,String workspaceId,String projectId,String actorId,String agentId,String packageId,String configDigest,
        String inputJson,String refsJson,int attemptCount,int cycleAttempt,Timestamp deadline,String skillId,String skillVersion,String skillDigest,String filesJson) {}
    private record PreparedTask(ObjectNode input,List<BiddingTypes.Ref> refs,BiddingTypes.SkillPin pin,String modelConfigId) {}
    private record LegacyTask(String id,String workspaceId,String projectId,int attemptCount) {}

    public ObjectNode findProject(String workspaceId,String projectId) {
        List<ObjectNode> rows=jdbc.query("SELECT body_json FROM mate_bidding_project WHERE workspace_id=:workspaceId AND id=:id",
            Map.of("workspaceId",workspaceId,"id",projectId),(rs,n)->readObject(rs,"body_json"));
        return rows.isEmpty()?null:rows.getFirst();
    }
    public int insertProject(String id,String workspaceId,String ownerId,String name,String lotName,String body,long version,String digest,java.sql.Timestamp now) {
        return jdbc.update("INSERT INTO mate_bidding_project(id,workspace_id,project_id,owner_id,version,name,lot_name,stage,body_json,created_at,updated_at) VALUES(:id,:workspaceId,:id,:ownerId,:version,:name,:lotName,'SETUP',:body,:now,:now)",
            new MapSqlParameterSource().addValue("id",id).addValue("workspaceId",workspaceId).addValue("ownerId",ownerId)
                .addValue("version",version).addValue("name",name).addValue("lotName",lotName).addValue("body",body).addValue("now",now));
    }
    public boolean lockProject(String workspace,String project) {
        List<String> rows=jdbc.query("SELECT id FROM mate_bidding_project WHERE workspace_id=:w AND id=:p FOR UPDATE",Map.of("w",workspace,"p",project),(rs,n)->rs.getString(1));
        return !rows.isEmpty();
    }
    public void insertOperation(String workspaceId,String actorId,String operationId,String digest,String result,java.sql.Timestamp now) {
        jdbc.update("INSERT INTO mate_bidding_operation(workspace_id,actor_id,operation_id,request_digest,result_json,created_at) VALUES(:workspace,:actor,:operation,:digest,:result,:now)",
            Map.of("workspace",workspaceId,"actor",actorId,"operation",operationId,"digest",digest,"result",result,"now",now));
    }
    public void updateOperation(String workspaceId,String actorId,String operationId,String result) {
        jdbc.update("UPDATE mate_bidding_operation SET result_json=:result WHERE workspace_id=:workspace AND actor_id=:actor AND operation_id=:operation",
            Map.of("workspace",workspaceId,"actor",actorId,"operation",operationId,"result",result));
    }
    public StoredOperation findOperation(String workspaceId,String actorId,String operationId) {
        List<StoredOperation> rows=jdbc.query("SELECT request_digest,result_json FROM mate_bidding_operation WHERE workspace_id=:workspace AND actor_id=:actor AND operation_id=:operation",
            Map.of("workspace",workspaceId,"actor",actorId,"operation",operationId),(rs,n)->new StoredOperation(rs.getString(1),parseObject(rs.getString(2))));
        return rows.isEmpty()?null:rows.getFirst();
    }
    public BiddingTypes.Page<ObjectNode> list(String workspaceId,String query,String stage,String ownerId,int page,int pageSize) {
        String q= query==null?"":query.trim();
        String selectedStage=stage==null||stage.isBlank()?null:stage.trim(), selectedOwner=ownerId==null||ownerId.isBlank()?null:ownerId.trim();
        var params=new MapSqlParameterSource().addValue("workspace",workspaceId).addValue("q","%"+q+"%")
            .addValue("stage",selectedStage).addValue("owner",selectedOwner).addValue("limit",pageSize).addValue("offset",(long)(page-1)*pageSize);
        String where=" WHERE workspace_id=:workspace AND (:q='%%' OR LOWER(name) LIKE LOWER(:q) OR LOWER(lot_name) LIKE LOWER(:q)) AND (:stage IS NULL OR stage=:stage) AND (:owner IS NULL OR owner_id=:owner)";
        long total=jdbc.queryForObject("SELECT COUNT(*) FROM mate_bidding_project"+where,params,Long.class);
        List<ObjectNode> items=jdbc.query("SELECT body_json FROM mate_bidding_project"+where+" ORDER BY updated_at DESC,id LIMIT :limit OFFSET :offset",params,(rs,n)->readObject(rs,"body_json"));
        return new BiddingTypes.Page<>(items,total,page,pageSize);
    }
    public ObjectNode dashboard(String workspaceId,String query,String stage,String ownerId) {
        String q=query==null?"":query.trim(), selectedStage=stage==null||stage.isBlank()?null:stage.trim(), selectedOwner=ownerId==null||ownerId.isBlank()?null:ownerId.trim();
        var params=new MapSqlParameterSource().addValue("workspace",workspaceId).addValue("q","%"+q+"%").addValue("stage",selectedStage).addValue("owner",selectedOwner);
        String where=" WHERE workspace_id=:workspace AND (:q='%%' OR LOWER(name) LIKE LOWER(:q) OR LOWER(lot_name) LIKE LOWER(:q)) AND (:stage IS NULL OR stage=:stage) AND (:owner IS NULL OR owner_id=:owner)";
        ObjectNode result=json.createObjectNode();
        Long inProgress=jdbc.queryForObject("SELECT COUNT(*) FROM mate_bidding_project"+where+" AND stage<>'ARCHIVED'",params,Long.class);
        Long failed=jdbc.queryForObject("SELECT COUNT(*) FROM mate_bidding_task t WHERE t.workspace_id=:workspace AND t.status IN ('FAILED','STALE') AND EXISTS (SELECT 1 FROM mate_bidding_project p"+where+" AND p.id=t.project_id)",params,Long.class);
        String whereP=" WHERE p.workspace_id=:workspace AND (:q='%%' OR LOWER(p.name) LIKE LOWER(:q) OR LOWER(p.lot_name) LIKE LOWER(:q)) AND (:stage IS NULL OR p.stage=:stage) AND (:owner IS NULL OR p.owner_id=:owner)";
        List<String> visibleProjects=jdbc.query("SELECT id FROM mate_bidding_project"+where,params,(rs,n)->rs.getString(1));
        int pending=0; for(String projectId:visibleProjects) if(awaitingAnalysisConfirmation(workspaceId,projectId)) pending++;
        result.put("inProgress",inProgress==null?0:inProgress); result.put("failedTasks",failed==null?0:failed); result.put("pendingConfirmation",pending);
        List<DeadlineProject> projects=jdbc.query("SELECT p.id,p.stage,r.status,r.payload_json FROM mate_bidding_project p LEFT JOIN mate_bidding_head h ON h.workspace_id=p.workspace_id AND h.project_id=p.id AND h.kind='analysisBaseline' AND h.object_id='current' LEFT JOIN mate_bidding_revision r ON r.workspace_id=h.workspace_id AND r.project_id=h.project_id AND r.kind=h.kind AND r.object_id=h.object_id AND r.version=h.version"+whereP,
                params,(rs,n)->new DeadlineProject(rs.getString("id"),rs.getString("stage"),rs.getString("status"),rs.getString("payload_json")));
        int due=0, overdue=0, unknown=0; java.time.Instant now=java.time.Instant.now(), limit=now.plus(java.time.Duration.ofDays(7));
        for(DeadlineProject project:projects) {
            if("ARCHIVED".equals(project.stage())) continue;
            List<java.time.Instant> dates=deadlineInstants(workspaceId,project);
            if(dates.isEmpty()) { unknown++; continue; }
            if(dates.stream().anyMatch(date->date.isBefore(now))) overdue++;
            if(dates.stream().anyMatch(date->!date.isBefore(now)&&!date.isAfter(limit))) due++;
        }
        result.put("dueWithin7Days",due); result.put("overdueDeadlines",overdue); result.put("unknownDeadlines",unknown); return result;
    }
    private boolean awaitingAnalysisConfirmation(String workspaceId,String projectId) {
        BiddingTypes.Scope scope=new BiddingTypes.Scope(workspaceId,"",projectId);
        String currentGroup="";
        try {
            var baseline=jdbc.getJdbcTemplate().query("SELECT r.status,r.payload_json FROM mate_bidding_head h JOIN mate_bidding_revision r ON r.workspace_id=h.workspace_id AND r.project_id=h.project_id AND r.kind=h.kind AND r.object_id=h.object_id AND r.version=h.version WHERE h.workspace_id=? AND h.project_id=? AND h.kind='analysisBaseline' AND h.object_id='current'",
                    rs->rs.next()?new AbstractMap.SimpleImmutableEntry<>(rs.getString(1),rs.getString(2)):null,workspaceId,projectId);
            if(baseline!=null && "CONFIRMED".equals(baseline.getKey())) currentGroup=parseObject(baseline.getValue()).path("taskGroupId").asText("");
        } catch(org.springframework.dao.EmptyResultDataAccessException ignored) { }
        List<DashboardTask> rows=jdbc.getJdbcTemplate().query("SELECT id,status,input_json,input_refs_json FROM mate_bidding_task WHERE workspace_id=? AND project_id=? ORDER BY created_at,id",
                (rs,n)->new DashboardTask(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4)),workspaceId,projectId);
        Map<String,List<DashboardTask>> groups=new LinkedHashMap<>();
        for(DashboardTask row:rows) {
            try { String group=parseObject(row.inputJson()).path("input").path("taskGroupId").asText(""); if(!group.isBlank()) groups.computeIfAbsent(group,key->new ArrayList<>()).add(row); }
            catch(RuntimeException ignored) { }
        }
        for(var entry:groups.entrySet()) {
            String groupId=entry.getKey(); List<DashboardTask> group=entry.getValue();
            if(groupId.equals(currentGroup) || group.stream().anyMatch(task->!"SUCCEEDED".equals(task.status()))) continue;
            try {
                JsonNode refs=json.readTree(group.getFirst().inputRefsJson()); BiddingTypes.Ref sourceSet=null;
                for(JsonNode ref:refs) if("sourceSet".equals(ref.path("kind").asText())) { sourceSet=json.treeToValue(ref,BiddingTypes.Ref.class); break; }
                if(sourceSet==null || !isSelectedSourceSet(scope,sourceSet)) continue;
                int shards=0; Set<String> expected=new HashSet<>(); Map<String,DashboardTask> bySkillShard=new HashMap<>();
                for(DashboardTask task:group) {
                    JsonNode input=parseObject(task.inputJson()).path("input"); String skill=input.path("skillId").asText(); int index=input.path("shardIndex").asInt(-1), count=input.path("shardCount").asInt(0);
                    if(!ANALYSIS_SKILLS.contains(skill)||index<0||count<1||!sourceSet.equals(sourceSetRef(task.inputRefsJson()))) { expected.clear(); break; }
                    shards=Math.max(shards,count); String key=skill+":"+index; if(bySkillShard.putIfAbsent(key,task)!=null){expected.clear();break;} expected.add(key);
                }
                if(shards<1 || group.size()!=ANALYSIS_SKILLS.size()*shards || expected.size()!=group.size()) continue;
                boolean complete=true;
                for(String skill:ANALYSIS_SKILLS) for(int shard=0;shard<shards;shard++) {
                    DashboardTask task=bySkillShard.get(skill+":"+shard); if(task==null){complete=false;break;}
                    String raw=jdbc.getJdbcTemplate().query("SELECT payload_json FROM mate_bidding_revision WHERE workspace_id=? AND project_id=? AND kind=? AND object_id=? AND version=? AND status='CANDIDATE'",
                            rs->rs.next()?rs.getString(1):null,workspaceId,projectId,"analysisCandidate_"+skill,groupId,(long)shard+1);
                    if(raw==null){complete=false;break;}
                    ObjectNode candidate=parseObject(raw); JsonNode output=candidate.path("payload"), read=candidate.path("readBlockIds"), coverage=output.path("coverage");
                    Set<String> assigned=new HashSet<>(),processed=new HashSet<>(); read.forEach(item->assigned.add(item.asText())); coverage.path("processedBlockIds").forEach(item->processed.add(item.asText()));
                    if(!task.id().equals(candidate.path("taskId").asText()) || !groupId.equals(candidate.path("taskGroupId").asText())
                            || !skill.equals(candidate.path("skillId").asText()) || candidate.path("shardIndex").asInt(-1)!=shard
                            || !coverage.path("unprocessedBlockIds").isArray() || !coverage.path("unprocessedBlockIds").isEmpty()
                            || !assigned.equals(processed)) { complete=false;break; }
                }
                if(complete)return true;
            } catch(Exception ignored) { }
        }
        return false;
    }
    private BiddingTypes.Ref sourceSetRef(String refsJson) throws Exception {
        JsonNode refs=json.readTree(refsJson); for(JsonNode ref:refs) if("sourceSet".equals(ref.path("kind").asText())) return json.treeToValue(ref,BiddingTypes.Ref.class); return null;
    }
    private record DashboardTask(String id,String status,String inputJson,String inputRefsJson) {}
    private List<java.time.Instant> deadlineInstants(String workspaceId,DeadlineProject project) {
        if(!"CONFIRMED".equals(project.baselineStatus())||project.payload()==null)return List.of();
        try {
            JsonNode baseline=json.readTree(project.payload()), sourceSetNode=baseline.path("sourceSetRef");
            BiddingTypes.Ref sourceSet=json.treeToValue(sourceSetNode,BiddingTypes.Ref.class);
            BiddingTypes.Scope scope=new BiddingTypes.Scope(workspaceId,"",project.id());
            if(!isSelectedSourceSet(scope,sourceSet))return List.of();
            JsonNode deadlines=baseline.path("analyses").path("bidding-tender-profile").path("deadlines"); List<java.time.Instant> found=new ArrayList<>();
            if(!deadlines.isArray())return List.of();
            for(JsonNode deadline:deadlines) {
                String value=deadline.path("value").asText(""); if(value.isBlank())continue;
                java.time.Instant instant=parseOffsetInstant(value); if(instant==null)continue;
                boolean backed=false; JsonNode evidenceRefs=deadline.path("evidenceRefs");
                if(evidenceRefs.isArray())for(JsonNode evidence:evidenceRefs) {
                    String sourceId=evidence.path("sourceId").asText(""),blockId=evidence.path("blockId").asText(""),quote=evidence.path("quote").asText(""); long version=evidence.path("version").asLong(0);
                    if(sourceId.isBlank()||blockId.isBlank()||version<1||!hasExplicitTimezone(quote))continue;
                    BiddingRepository.SourceRow source=source(workspaceId,project.id(),sourceId,version);
                    if(source==null||!"READY".equals(source.status()))continue;
                    BiddingTypes.Ref sourceRef=new BiddingTypes.Ref("source",sourceId,version,source.digest());
                    if(!sourceSetContains(scope,sourceRef))continue;
                    try { JsonNode blocks=json.readTree(source.blocks()); for(JsonNode block:blocks)if(block.path("id").asText().equals(blockId)&&block.path("text").asText("").contains(quote)){backed=true;break;} }
                    catch(Exception ignored) { }
                    if(backed)break;
                }
                if(backed)found.add(instant);
            }
            return found;
        } catch(Exception ignored) { return List.of(); }
    }
    private java.time.Instant parseOffsetInstant(String value) {
        try { return java.time.OffsetDateTime.parse(value.trim()).toInstant(); }
        catch(Exception ignored) { try { return java.time.ZonedDateTime.parse(value.trim()).toInstant(); } catch(Exception alsoIgnored) { return null; } }
    }
    private boolean hasExplicitTimezone(String quote) { return quote.matches("(?s).*(?:\\bUTC\\b|\\bGMT\\b|北京时间|中国标准时间|[+-](?:0[0-9]|1[0-4]):?[0-5][0-9]\\b|[0-2][0-9]:[0-5][0-9]Z\\b).*"); }
    private record DeadlineProject(String id,String stage,String baselineStatus,String payload) {}
    public BiddingTypes.Ref sourceSetHead(BiddingTypes.Scope scope) {
        try { return json.convertValue(parseObject(jdbc.queryForObject("SELECT selected_ref_json FROM mate_bidding_head WHERE workspace_id=:w AND project_id=:p AND kind='sourceSet' AND object_id='current'",Map.of("w",scope.workspaceId(),"p",scope.projectId()),String.class)),BiddingTypes.Ref.class); }
        catch(org.springframework.dao.EmptyResultDataAccessException e) { return null; }
    }
    public boolean advanceSourceSetHead(BiddingTypes.Scope scope,BiddingTypes.Ref expected,BiddingTypes.Ref next,String refJson) {
        var args=new MapSqlParameterSource().addValue("w",scope.workspaceId()).addValue("p",scope.projectId()).addValue("v",next.version()).addValue("ref",refJson);
        if(expected==null) {
            try { jdbc.update("INSERT INTO mate_bidding_head(workspace_id,project_id,kind,object_id,version,selected_ref_json) VALUES(:w,:p,'sourceSet','current',:v,:ref)",args); return true; }
            catch(org.springframework.dao.DuplicateKeyException conflict) { return false; }
        }
        args.addValue("expectedVersion",expected.version());
        return jdbc.update("UPDATE mate_bidding_head SET version=:v,selected_ref_json=:ref WHERE workspace_id=:w AND project_id=:p AND kind='sourceSet' AND object_id='current' AND version=:expectedVersion",args)==1;
    }
    public boolean sourceWasConfirmed(BiddingTypes.Scope scope,BiddingTypes.Ref sourceRef) {
        List<String> histories=jdbc.query("SELECT input_refs_json FROM mate_bidding_revision WHERE workspace_id=:w AND project_id=:p AND kind='sourceSet'",
            Map.of("w",scope.workspaceId(),"p",scope.projectId()),(rs,n)->rs.getString(1));
        for(String history:histories) {
            try {
                JsonNode refs=json.readTree(history);
                if(refs.isArray()) for(JsonNode node:refs) if(sourceRef.equals(json.convertValue(node,BiddingTypes.Ref.class))) return true;
            } catch(Exception e) { throw new IllegalStateException("Invalid persisted source-set references",e); }
        }
        return false;
    }
    public int insertSource(String id,String workspace,String project,String sourceId,long version,String kind,String digest,byte[] content,
        String filename,java.sql.Timestamp now) {
        return jdbc.update("INSERT INTO mate_bidding_source(id,workspace_id,project_id,source_id,version,kind,digest,content,blocks_json,quality,read_token,read_started_at,filename,read_status,problems_json,created_at) " +
            "VALUES(:id,:w,:p,:source,:version,:kind,:digest,:content,'[]','PENDING',NULL,NULL,:filename,'PENDING','[]',:now)",
            new MapSqlParameterSource().addValue("id",id).addValue("w",workspace).addValue("p",project).addValue("source",sourceId).addValue("version",version)
                .addValue("kind",kind).addValue("digest",digest).addValue("content",content).addValue("filename",filename).addValue("now",now));
    }
    public SourceRow source(String workspace,String project,String sourceId,long version) {
        var rows=jdbc.query("SELECT id,source_id,version,kind,digest,content,filename,blocks_json,quality,read_status,problems_json,read_token FROM mate_bidding_source WHERE workspace_id=:w AND project_id=:p AND source_id=:s AND version=:v",
            Map.of("w",workspace,"p",project,"s",sourceId,"v",version),(rs,n)->new SourceRow(rs.getString("id"),rs.getString("source_id"),rs.getLong("version"),rs.getString("kind"),rs.getString("digest"),rs.getBytes("content"),rs.getString("filename"),rs.getString("blocks_json"),rs.getString("quality"),rs.getString("read_status"),rs.getString("problems_json"),rs.getString("read_token")));
        return rows.isEmpty()?null:rows.getFirst();
    }
    public List<SourceRow> pendingSources(int limit) {
        return jdbc.query("SELECT id,workspace_id,project_id,source_id,version,kind,digest,content,filename,blocks_json,quality,read_status,problems_json,read_token FROM mate_bidding_source WHERE read_status='PENDING' ORDER BY created_at LIMIT :limit",
            Map.of("limit",Math.min(2,Math.max(0,limit))),(rs,n)->new SourceRow(rs.getString("id"),rs.getString("workspace_id"),rs.getString("project_id"),rs.getString("source_id"),rs.getLong("version"),rs.getString("kind"),rs.getString("digest"),rs.getBytes("content"),rs.getString("filename"),rs.getString("blocks_json"),rs.getString("quality"),rs.getString("read_status"),rs.getString("problems_json"),rs.getString("read_token")));
    }
    public int claimSource(String rowId,String token,java.sql.Timestamp now) {
        return jdbc.update("UPDATE mate_bidding_source SET read_status='READING',read_token=:token,read_started_at=:now WHERE id=:id AND read_status='PENDING'",Map.of("id",rowId,"token",token,"now",now));
    }
    public int finishSource(String rowId,String token,String status,String blocks,String quality,String problems,java.sql.Timestamp now) {
        return jdbc.update("UPDATE mate_bidding_source SET read_status=:status,blocks_json=:blocks,quality=:quality,problems_json=:problems,read_completed_at=:now WHERE id=:id AND read_status='READING' AND read_token=:token",
            Map.of("id",rowId,"token",token,"status",status,"blocks",blocks,"quality",quality,"problems",problems,"now",now));
    }
    public int recoverReadingSources(java.sql.Timestamp now) {
        return jdbc.update("UPDATE mate_bidding_source SET read_status='FAILED',quality='FAILED',problems_json='[\"READ_INTERRUPTED\"]',read_completed_at=:now WHERE read_status='READING'",Map.of("now",now));
    }
    public int invalidateSourceSet(BiddingTypes.Scope scope) {
        int derived=jdbc.update("UPDATE mate_bidding_revision SET status='NEEDS_RECONFIRMATION' WHERE workspace_id=:w AND project_id=:p AND status='CONFIRMED' AND kind<>'sourceSet' AND input_refs_json LIKE :needle",
            Map.of("w",scope.workspaceId(),"p",scope.projectId(),"needle","%sourceSet%current%"));
        jdbc.update("UPDATE mate_bidding_revision SET status='NEEDS_RECONFIRMATION' WHERE workspace_id=:w AND project_id=:p AND kind='sourceSet' AND object_id='current' AND version<(SELECT MAX(v.version) FROM mate_bidding_revision v WHERE v.workspace_id=:w AND v.project_id=:p AND v.kind='sourceSet' AND v.object_id='current')",
            Map.of("w",scope.workspaceId(),"p",scope.projectId()));
        return derived;
    }
    public int retrySource(String workspace,String project,String sourceId,long version) {
        return jdbc.update("UPDATE mate_bidding_source SET read_status='PENDING',quality='PENDING',blocks_json='[]',problems_json='[]',read_token=NULL,read_started_at=NULL,read_completed_at=NULL WHERE workspace_id=:w AND project_id=:p AND source_id=:s AND version=:v AND read_status IN ('FAILED','NEEDS_REVIEW')",
            Map.of("w",workspace,"p",project,"s",sourceId,"v",version));
    }
    public int insertRevision(String id,String workspace,String project,String kind,String objectId,long version,String payload,String refs,String status,String digest,java.sql.Timestamp now) {
        return jdbc.update("INSERT INTO mate_bidding_revision(id,workspace_id,project_id,kind,object_id,version,payload_json,input_refs_json,status,digest,created_at) VALUES(:id,:w,:p,:kind,:object,:version,:payload,:refs,:status,:digest,:now)",
            new MapSqlParameterSource().addValue("id",id).addValue("w",workspace).addValue("p",project).addValue("kind",kind).addValue("object",objectId)
                .addValue("version",version).addValue("payload",payload).addValue("refs",refs).addValue("status",status).addValue("digest",digest).addValue("now",now));
    }
    public boolean revisionExists(BiddingTypes.Scope scope,BiddingTypes.Ref ref) {
        Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM mate_bidding_revision WHERE workspace_id=:w AND project_id=:p AND kind=:kind AND object_id=:object AND version=:version AND digest=:digest AND status='CONFIRMED'",
            Map.of("w",scope.workspaceId(),"p",scope.projectId(),"kind",ref.kind(),"object",ref.id(),"version",ref.version(),"digest",ref.digest()),Integer.class);
        return count!=null && count==1;
    }
    public ObjectNode businessRevision(BiddingTypes.Scope scope,BiddingTypes.Ref ref) {
        try {
            String payload=jdbc.queryForObject("SELECT payload_json FROM mate_bidding_revision WHERE workspace_id=:w AND project_id=:p AND kind=:kind AND object_id=:object AND version=:version AND digest=:digest",Map.of("w",scope.workspaceId(),"p",scope.projectId(),"kind",ref.kind(),"object",ref.id(),"version",ref.version(),"digest",ref.digest()),String.class);
            String status=jdbc.queryForObject("SELECT status FROM mate_bidding_revision WHERE workspace_id=:w AND project_id=:p AND kind=:kind AND object_id=:object AND version=:version AND digest=:digest",Map.of("w",scope.workspaceId(),"p",scope.projectId(),"kind",ref.kind(),"object",ref.id(),"version",ref.version(),"digest",ref.digest()),String.class);
            String refs=jdbc.queryForObject("SELECT input_refs_json FROM mate_bidding_revision WHERE workspace_id=:w AND project_id=:p AND kind=:kind AND object_id=:object AND version=:version AND digest=:digest",Map.of("w",scope.workspaceId(),"p",scope.projectId(),"kind",ref.kind(),"object",ref.id(),"version",ref.version(),"digest",ref.digest()),String.class);
            ObjectNode result=parseObject(payload); result.put("status",status); result.set("refs",json.valueToTree(readRefs(refs))); return result;
        } catch(org.springframework.dao.EmptyResultDataAccessException absent) { return null; }
    }
    public List<BiddingTypes.Ref> businessRefs(ObjectNode revision) { try { return json.convertValue(revision.path("refs"),new com.fasterxml.jackson.core.type.TypeReference<List<BiddingTypes.Ref>>(){}); } catch(Exception e) { throw BiddingAccess.error(422,"DEPENDENCY_INVALID","Business revision references are invalid"); } }
    public boolean isSelectedBusinessRevision(BiddingTypes.Scope scope,BiddingTypes.Ref ref) {
        try { String selected=jdbc.queryForObject("SELECT selected_ref_json FROM mate_bidding_head WHERE workspace_id=:w AND project_id=:p AND kind=:kind AND object_id=:object",Map.of("w",scope.workspaceId(),"p",scope.projectId(),"kind",ref.kind(),"object",ref.id()),String.class); ObjectNode node=parseObject(selected); return node.path("version").asLong()==ref.version()&&node.path("digest").asText().equals(ref.digest()); }
        catch(org.springframework.dao.EmptyResultDataAccessException absent){return false;}
    }
    public boolean dependenciesCurrent(BiddingTypes.Scope scope,List<BiddingTypes.Ref> refs) {
        try { var head=jdbc.queryForObject("SELECT selected_ref_json FROM mate_bidding_head WHERE workspace_id=:w AND project_id=:p AND kind='sourceSet' AND object_id='current'",Map.of("w",scope.workspaceId(),"p",scope.projectId()),String.class);
            return refs.stream().anyMatch(ref -> ref.kind().equals("sourceSet") && ref.digest().equals(parseObject(head).path("digest").asText()));
        } catch(org.springframework.dao.EmptyResultDataAccessException e) { return false; }
    }
    public boolean isSelectedSourceSet(BiddingTypes.Scope scope,BiddingTypes.Ref ref) {
        try {
            String head=jdbc.queryForObject("SELECT selected_ref_json FROM mate_bidding_head WHERE workspace_id=:w AND project_id=:p AND kind='sourceSet' AND object_id='current'",Map.of("w",scope.workspaceId(),"p",scope.projectId()),String.class);
            ObjectNode selected=parseObject(head);
            return "sourceSet".equals(ref.kind()) && "current".equals(ref.id()) && selected.path("version").asLong()==ref.version() && selected.path("digest").asText().equals(ref.digest());
        } catch(org.springframework.dao.EmptyResultDataAccessException e) { return false; }
    }
    public boolean sourceSetContains(BiddingTypes.Scope scope,BiddingTypes.Ref sourceRef) {
        try {
            String head=jdbc.queryForObject("SELECT selected_ref_json FROM mate_bidding_head WHERE workspace_id=:w AND project_id=:p AND kind='sourceSet' AND object_id='current'",Map.of("w",scope.workspaceId(),"p",scope.projectId()),String.class);
            ObjectNode selected=parseObject(head);
            String payload=jdbc.queryForObject("SELECT payload_json FROM mate_bidding_revision WHERE workspace_id=:w AND project_id=:p AND kind='sourceSet' AND object_id='current' AND version=:v AND status='CONFIRMED'",
                Map.of("w",scope.workspaceId(),"p",scope.projectId(),"v",selected.path("version").asLong()),String.class);
            JsonNode refs=parseObject(payload).path("sourceRefs");
            for(JsonNode ref:refs) if(ref.path("kind").asText().equals(sourceRef.kind()) && ref.path("id").asText().equals(sourceRef.id())
                && ref.path("version").asLong()==sourceRef.version() && ref.path("digest").asText().equals(sourceRef.digest())) return true;
            return false;
        } catch(org.springframework.dao.EmptyResultDataAccessException e) { return false; }
    }
    public void invalidateDependencies(BiddingTypes.Scope scope,BiddingTypes.Ref changed) {
        jdbc.update("UPDATE mate_bidding_revision SET status='NEEDS_RECONFIRMATION' WHERE workspace_id=:w AND project_id=:p AND status='CONFIRMED' AND input_refs_json LIKE :needle",
            Map.of("w",scope.workspaceId(),"p",scope.projectId(),"needle","%"+changed.id()+"%"));
    }
    public List<SourceRow> sources(String workspace,String project) {
        return jdbc.query("SELECT id,source_id,version,kind,digest,content,filename,blocks_json,quality,read_status,problems_json,read_token FROM mate_bidding_source WHERE workspace_id=:w AND project_id=:p ORDER BY created_at,source_id,version",
            Map.of("w",workspace,"p",project),(rs,n)->new SourceRow(rs.getString("id"),rs.getString("source_id"),rs.getLong("version"),rs.getString("kind"),rs.getString("digest"),null,rs.getString("filename"),rs.getString("blocks_json"),rs.getString("quality"),rs.getString("read_status"),rs.getString("problems_json"),rs.getString("read_token")));
    }
    public int insertSourceHead(BiddingTypes.Scope scope,String sourceId,long version,String refJson) {
        jdbc.update("DELETE FROM mate_bidding_head WHERE workspace_id=:w AND project_id=:p AND kind='source' AND object_id=:id",Map.of("w",scope.workspaceId(),"p",scope.projectId(),"id",sourceId));
        return jdbc.update("INSERT INTO mate_bidding_head(workspace_id,project_id,kind,object_id,version,selected_ref_json) VALUES(:w,:p,'source',:id,:v,:ref)",Map.of("w",scope.workspaceId(),"p",scope.projectId(),"id",sourceId,"v",version,"ref",refJson));
    }
    public long maxSourceVersion(BiddingTypes.Scope scope,String sourceId) {
        Long result=jdbc.queryForObject("SELECT COALESCE(MAX(version),0) FROM mate_bidding_source WHERE workspace_id=:w AND project_id=:p AND source_id=:id",Map.of("w",scope.workspaceId(),"p",scope.projectId(),"id",sourceId),Long.class);
        return result==null?0:result;
    }
    public long maxRevisionVersion(BiddingTypes.Scope scope,String kind,String objectId) {
        Long result=jdbc.queryForObject("SELECT COALESCE(MAX(version),0) FROM mate_bidding_revision WHERE workspace_id=:w AND project_id=:p AND kind=:kind AND object_id=:object",
            Map.of("w",scope.workspaceId(),"p",scope.projectId(),"kind",kind,"object",objectId),Long.class);
        return result==null?0:result;
    }
    public record SourceRow(String rowId,String workspaceId,String projectId,String sourceId,long version,String kind,String digest,byte[] content,String filename,String blocks,String quality,String status,String problems,String readToken) {
        public SourceRow(String rowId,String sourceId,long version,String kind,String digest,byte[] content,String filename,String blocks,String quality,String status,String problems,String readToken) {
            this(rowId,null,null,sourceId,version,kind,digest,content,filename,blocks,quality,status,problems,readToken);
        }
    }
    public int updateProject(String workspace,String projectId,String ownerId,String name,String lotName,String stage,String body,int expectedVersion,java.sql.Timestamp now) {
        return jdbc.update("UPDATE mate_bidding_project SET owner_id=:owner,name=:name,lot_name=:lotName,stage=:stage,body_json=:body,version=version+1,updated_at=:now WHERE id=:id AND workspace_id=:workspace AND version=:version",
            Map.of("owner",ownerId,"name",name,"lotName",lotName,"stage",stage,"body",body,"now",now,"id",projectId,"workspace",workspace,"version",expectedVersion));
    }
    public record StoredOperation(String digest,ObjectNode result) {}
    private ObjectNode readObject(ResultSet rs,String column) { try { return (ObjectNode)json.readTree(rs.getString(column)); } catch(Exception e) { throw new IllegalStateException("Invalid persisted bidding JSON",e); } }
    private ObjectNode parseObject(String value) { try { return (ObjectNode)json.readTree(value); } catch(Exception e) { throw new IllegalStateException("Invalid persisted bidding JSON",e); } }
}

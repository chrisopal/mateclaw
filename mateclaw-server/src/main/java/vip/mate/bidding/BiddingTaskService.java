package vip.mate.bidding;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class BiddingTaskService {
    private static final int MAX_INPUT_BYTES=2*1024*1024;
    private static final Map<String,Set<String>> ROLE_SKILLS=Map.of(
        "analyst",Set.of("bidding-tender-profile","bidding-elimination-analysis","bidding-requirement-analysis","bidding-scoring-analysis"),
        "writer",Set.of("bidding-outline-planning","bidding-technical-writing","bidding-document-export"),
        "reviewer",Set.of("bidding-technical-review"));
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final BiddingRepository repository;
    private final BiddingProjectService projects;
    private final BiddingAccess access;
    private final ObjectProvider<BiddingEmployeeBindings> employees;
    private final BiddingDependencies dependencies;
    private final ObjectProvider<BiddingEmployeeRuntime> runtime;
    private final ObjectProvider<BiddingResultHandler> handlers;
    private final ObjectProvider<BiddingScheduler> scheduler;
    private final BiddingRetryPolicy retryPolicy;
    private final TransactionTemplate transactions;

    public BiddingTaskService(JdbcTemplate jdbc,ObjectMapper json,BiddingRepository repository,BiddingProjectService projects,BiddingAccess access,
            ObjectProvider<BiddingEmployeeBindings> employees,BiddingDependencies dependencies,
            ObjectProvider<BiddingEmployeeRuntime> runtime,ObjectProvider<BiddingResultHandler> handlers,ObjectProvider<BiddingScheduler> scheduler,BiddingRetryPolicy retryPolicy,
            PlatformTransactionManager manager) {
        this.jdbc=jdbc; this.json=json; this.repository=repository; this.projects=projects; this.access=access; this.employees=employees;
        this.dependencies=dependencies; this.runtime=runtime; this.handlers=handlers; this.scheduler=scheduler; this.retryPolicy=retryPolicy;
        this.transactions=new TransactionTemplate(manager);
    }

    public ObjectNode enqueue(BiddingTypes.Scope scope,BiddingTypes.Command command,String skillId,String targetId,
            List<BiddingTypes.Ref> refs,ObjectNode input) {
        access.requireActor(scope,scope.actorId());
        if(command==null || command.operationId()==null || command.operationId().isBlank() || command.operationId().length()>128)
            throw BiddingAccess.error(400,"INVALID_REQUEST","operationId is required");
        if(skillId==null || skillId.isBlank() || targetId==null || targetId.isBlank() || input==null)
            throw BiddingAccess.error(400,"INVALID_REQUEST","Task skill, target and input are required");
        try {
            if(json.writeValueAsBytes(input).length>MAX_INPUT_BYTES) throw BiddingAccess.error(413,"TASK_INPUT_LIMIT","Task input exceeds the size limit");
        } catch(BiddingApiException e) { throw e; }
        catch(Exception e) { throw new IllegalArgumentException("Invalid task input",e); }
        String digest=digest(Map.of("action",Objects.toString(command.action(),"ENQUEUE_TASK"),"skillId",skillId,"targetId",targetId,"refs",refs==null?List.of():refs,"input",input));
        try { return transactions.execute(tx->enqueueInTransaction(scope,command,skillId,targetId,refs,input,digest)); }
        catch(org.springframework.dao.DuplicateKeyException race) { return replay(scope,command.operationId(),digest); }
    }

    private ObjectNode enqueueInTransaction(BiddingTypes.Scope scope,BiddingTypes.Command command,String skillId,String targetId,
            List<BiddingTypes.Ref> refs,ObjectNode input,String digest) {
        access.requireActor(scope,scope.actorId());
        ObjectNode project=repository.findProject(scope.workspaceId(),scope.projectId());
        if(project==null) throw BiddingAccess.error(404,"NOT_FOUND","Project not found");
        if("ARCHIVED".equals(project.path("stage").asText())) throw BiddingAccess.error(409,"PROJECT_ARCHIVED","Archived projects cannot start tasks");
        var prior=repository.findOperation(scope.workspaceId(),scope.actorId(),command.operationId());
        if(prior!=null) return replay(prior,digest);
        if(refs==null || refs.isEmpty()) throw BiddingAccess.error(422,"SOURCE_SET_INCOMPLETE","Fixed references are required");
        Binding selected=findBinding(project,skillId);
        handler(skillId); // Do not spend a model call on a skill with no unique result writer.
        dependencies.validate(scope,refs);
        employees.getObject().validate(scope,selected.agentId(),selected.configDigest());
        String modelConfigId=employees.getObject().modelConfigId(scope,selected.agentId());
        BiddingTypes.SkillPin pin=loadPinnedPackage(scope,skillId,selected.skillDigest());
        ObjectNode snapshot=json.createObjectNode(); ObjectNode metadata=snapshot.putObject("_bidding");
        metadata.put("skillId",skillId); metadata.put("targetId",targetId); metadata.put("modelConfigId",modelConfigId);
        snapshot.set("input",input.deepCopy());
        List<BiddingTypes.Ref> fixed=List.copyOf(refs);
        String id=UUID.randomUUID().toString(); Timestamp now=Timestamp.from(Instant.now());
        ObjectNode result=json.createObjectNode(); result.put("taskId",id); result.put("status","QUEUED"); result.put("attemptCount",0);
        repository.insertOperation(scope.workspaceId(),scope.actorId(),command.operationId(),digest,"{}",now);
        jdbc.update("INSERT INTO mate_bidding_task(id,workspace_id,project_id,actor_id,agent_id,skill_package_id,config_digest,input_json,input_refs_json,status,cycle_no,cycle_attempt,attempt_count,next_run_at,created_at,updated_at) "
                + "VALUES(?,?,?,?,?,?,?,?,?,'QUEUED',0,1,0,NULL,?,?)",id,scope.workspaceId(),scope.projectId(),scope.actorId(),selected.agentId(),pinPackageId(scope,skillId,pin.digest()),selected.configDigest(),write(snapshot),write(fixed),now,now);
        repository.updateOperation(scope.workspaceId(),scope.actorId(),command.operationId(),write(result));
        return result;
    }

    public ObjectNode retry(BiddingTypes.Scope caller,BiddingTypes.Command command) {
        access.requireActor(caller,caller.actorId());
        if(command==null || command.payload()==null || command.operationId()==null || command.operationId().isBlank() || command.operationId().length()>128)
            throw BiddingAccess.error(400,"INVALID_REQUEST","Retry taskId and operationId are required");
        String taskId=command.payload().path("taskId").asText(null);
        if(taskId==null || taskId.isBlank()) throw BiddingAccess.error(400,"INVALID_REQUEST","Retry taskId is required");
        String digest=digest(Map.of("action","RETRY_TASK","taskId",taskId));
        ObjectNode result=transactions.execute(tx->{
            var prior=repository.findOperation(caller.workspaceId(),caller.actorId(),command.operationId());
            if(prior!=null) return replay(prior,digest);
            TaskRow task=task(caller,taskId,true);
            if(!Set.of("FAILED","CANCELLED","STALE").contains(task.status())) throw BiddingAccess.error(409,"TASK_NOT_RETRYABLE","Task is not in a retryable state");
            if(task.actorId()==null || task.actorId().isBlank()) throw BiddingAccess.error(409,"TASK_ACTOR_MISSING","Legacy task has no authorized actor");
            BiddingTypes.Scope original=new BiddingTypes.Scope(caller.workspaceId(),task.actorId(),caller.projectId());
            access.requireActor(original,task.actorId()); dependencies.validate(original,readRefs(task.refsJson()));
            employees.getObject().validate(original,task.agentId(),task.configDigest());
            Timestamp now=Timestamp.from(Instant.now());
            int changed=jdbc.update("UPDATE mate_bidding_task SET status='QUEUED',cycle_no=cycle_no+1,cycle_attempt=1,next_run_at=NULL,active_attempt_id=NULL,updated_at=? WHERE id=? AND status=?",
                now,task.id(),task.status());
            if(changed!=1) throw BiddingAccess.error(409,"TASK_CHANGED","Task state changed during retry");
            ObjectNode response=json.createObjectNode(); response.put("taskId",task.id()); response.put("status","QUEUED");
            repository.insertOperation(caller.workspaceId(),caller.actorId(),command.operationId(),digest,"{}",now);
            repository.updateOperation(caller.workspaceId(),caller.actorId(),command.operationId(),write(response));
            return response;
        });
        return result;
    }

    public ObjectNode cancel(BiddingTypes.Scope scope,BiddingTypes.Command command) {
        access.requireActor(scope,scope.actorId());
        if(command==null || command.payload()==null || command.operationId()==null || command.operationId().isBlank() || command.operationId().length()>128)
            throw BiddingAccess.error(400,"INVALID_REQUEST","Cancel taskId and operationId are required");
        String taskId=command.payload().path("taskId").asText(null);
        if(taskId==null || taskId.isBlank()) throw BiddingAccess.error(400,"INVALID_REQUEST","Cancel taskId is required");
        String digest=digest(Map.of("action","CANCEL_TASK","taskId",taskId));
        ObjectNode result=transactions.execute(tx->{
            var prior=repository.findOperation(scope.workspaceId(),scope.actorId(),command.operationId());
            if(prior!=null) return replay(prior,digest);
            TaskRow task=task(scope,taskId,true); Timestamp now=Timestamp.from(Instant.now());
            if(!Set.of("QUEUED","WAITING_RETRY","RUNNING").contains(task.status())) throw BiddingAccess.error(409,"TASK_NOT_CANCELLABLE","Task is no longer cancellable");
            jdbc.update("UPDATE mate_bidding_attempt SET state='CANCELLED',finished_at=? WHERE id=? AND state='RUNNING'",now,task.activeAttemptId());
            int changed=jdbc.update("UPDATE mate_bidding_task SET status='CANCELLED',active_attempt_id=NULL,next_run_at=NULL,deadline_at=NULL,updated_at=? WHERE id=? AND status=?",now,task.id(),task.status());
            if(changed!=1) throw BiddingAccess.error(409,"TASK_CHANGED","Task state changed during cancellation");
            ObjectNode response=json.createObjectNode(); response.put("taskId",task.id()); response.put("status","CANCELLED");
            repository.insertOperation(scope.workspaceId(),scope.actorId(),command.operationId(),digest,"{}",now);
            repository.updateOperation(scope.workspaceId(),scope.actorId(),command.operationId(),write(response)); return response;
        });
        scheduler.ifAvailable(worker->worker.cancelTask(taskId));
        return result;
    }

    public BiddingTypes.Page<ObjectNode> listTasks(BiddingTypes.Scope scope,int page,int pageSize) {
        if(page<1 || pageSize<1 || pageSize>100) throw BiddingAccess.error(400,"INVALID_PAGINATION","Task pagination must be page >= 1 and pageSize between 1 and 100");
        requireVisibleProject(scope);
        long total=jdbc.queryForObject("SELECT COUNT(*) FROM mate_bidding_task WHERE workspace_id=? AND project_id=?",Long.class,scope.workspaceId(),scope.projectId());
        List<ObjectNode> items=jdbc.query("SELECT id,status,cycle_no,cycle_attempt,attempt_count,created_at,updated_at FROM mate_bidding_task "
                + "WHERE workspace_id=? AND project_id=? ORDER BY created_at DESC,id LIMIT ? OFFSET ?",
            (rs,n)->{
                ObjectNode item=json.createObjectNode(); item.put("taskId",rs.getString("id")); item.put("status",rs.getString("status"));
                item.put("cycleNo",rs.getInt("cycle_no")); item.put("cycleAttempt",rs.getInt("cycle_attempt")); item.put("attemptCount",rs.getInt("attempt_count"));
                timestamp(item,"createdAt",rs.getTimestamp("created_at")); timestamp(item,"updatedAt",rs.getTimestamp("updated_at")); return item;
            },scope.workspaceId(),scope.projectId(),pageSize,(long)(page-1)*pageSize);
        return new BiddingTypes.Page<>(items,total,page,pageSize);
    }

    public ObjectNode taskDetails(BiddingTypes.Scope scope,String taskId) {
        if(taskId==null || taskId.isBlank()) throw BiddingAccess.error(400,"INVALID_REQUEST","Task id is required");
        TaskView task=jdbc.query("SELECT id,project_id,input_json,input_refs_json,status,cycle_no,cycle_attempt,attempt_count,created_at,updated_at FROM mate_bidding_task WHERE id=? AND workspace_id=?",
            rs->rs.next()?new TaskView(rs.getString("id"),rs.getString("project_id"),rs.getString("input_json"),rs.getString("input_refs_json"),rs.getString("status"),rs.getInt("cycle_no"),rs.getInt("cycle_attempt"),rs.getInt("attempt_count"),rs.getTimestamp("created_at"),rs.getTimestamp("updated_at")):null,
            taskId,scope.workspaceId());
        if(task==null) throw BiddingAccess.error(404,"NOT_FOUND","Task not found");
        try { projects.get(new BiddingTypes.Scope(scope.workspaceId(),scope.actorId(),task.projectId())); }
        catch(BiddingApiException missing) { if(missing.status()==404) throw BiddingAccess.error(404,"NOT_FOUND","Task not found"); throw missing; }
        if(scope.projectId()!=null && !scope.projectId().equals(task.projectId())) throw BiddingAccess.error(404,"NOT_FOUND","Task not found");
        ObjectNode result=json.createObjectNode(); result.put("taskId",task.id()); result.put("projectId",task.projectId()); result.put("status",task.status());
        result.put("cycleNo",task.cycleNo()); result.put("cycleAttempt",task.cycleAttempt()); result.put("attemptCount",task.attemptCount());
        timestamp(result,"createdAt",task.createdAt()); timestamp(result,"updatedAt",task.updatedAt());
        ObjectNode snapshot=result.putObject("snapshot");
        ObjectNode stored=parseObject(task.inputJson()); ObjectNode metadata=snapshot.putObject("_bidding");
        if(stored.path("_bidding").hasNonNull("skillId")) metadata.put("skillId",stored.path("_bidding").path("skillId").asText());
        if(stored.path("_bidding").hasNonNull("targetId")) metadata.put("targetId",stored.path("_bidding").path("targetId").asText());
        snapshot.set("input",stored.path("input").isObject()?stored.path("input").deepCopy():json.createObjectNode());
        snapshot.set("inputRefs",json.valueToTree(readRefs(task.refsJson())));
        List<ObjectNode> attempts=jdbc.query("SELECT attempt_no,state,error_json,rejected_output,output_json,started_at,finished_at FROM mate_bidding_attempt "
                + "WHERE task_id=? AND workspace_id=? AND project_id=? ORDER BY attempt_no",
            (rs,n)->{
                ObjectNode item=json.createObjectNode(); item.put("attemptNo",rs.getInt("attempt_no")); item.put("state",rs.getString("state"));
                setJson(item,"rejection",rs.getString("error_json"));
                String rejected=rs.getString("rejected_output"); if(rejected!=null) item.put("rejectedOutput",rejected);
                setJson(item,"result",rs.getString("output_json")); timestamp(item,"startedAt",rs.getTimestamp("started_at")); timestamp(item,"finishedAt",rs.getTimestamp("finished_at"));
                return item;
            },task.id(),scope.workspaceId(),task.projectId());
        result.set("attempts",json.valueToTree(attempts));
        ObjectNode accepted=attempts.stream().filter(a->"SUCCEEDED".equals(a.path("state").asText())).map(a->(ObjectNode)a.path("result")).findFirst().orElse(null);
        if(accepted==null) result.putNull("effectiveResult"); else result.set("effectiveResult",accepted.deepCopy());
        return result;
    }

    private void requireVisibleProject(BiddingTypes.Scope scope) {
        if(scope.projectId()==null)
            throw BiddingAccess.error(404,"NOT_FOUND","Project not found");
        projects.get(scope);
    }
    private void timestamp(ObjectNode node,String field,Timestamp value) { if(value==null) node.putNull(field); else node.put(field,value.toInstant().toString()); }
    private void setJson(ObjectNode node,String field,String raw) {
        if(raw==null) { node.putNull(field); return; }
        try { node.set(field,json.readTree(raw)); } catch(Exception ignored) { node.put(field,raw); }
    }

    public void complete(BiddingTypes.Claim claim,BiddingTypes.Execution execution) {
        if(claim==null || execution==null) throw new IllegalArgumentException("Claim and execution are required");
        if(execution.failure()!=null) { persistFailure(claim,execution); return; }
        RuntimeException last=null;
        long[] delays={0,100,300,900};
        for(long delay:delays) {
            if(delay>0) sleep(delay);
            try { transactions.execute(tx->{completeSuccess(claim,execution); return null;}); return; }
            catch(BiddingApiException rejected) { persistRejected(claim,execution,rejected.code(),"VALIDATION"); return; }
            catch(ResultHandlerFailure rejected) { persistRejected(claim,execution,"RESULT_HANDLER_FAILURE","HANDLER"); return; }
            catch(DataAccessException|TransactionException persistenceFailure) { last=persistenceFailure; }
            catch(RuntimeException programmingFailure) { persistRejected(claim,execution,"COMPLETE_FAILURE","INTERNAL"); return; }
        }
        if(last!=null) throw last;
    }

    private void completeSuccess(BiddingTypes.Claim claim,BiddingTypes.Execution execution) {
        AttemptRow active=activeAttempt(claim);
        if(active==null) return; // Idempotent late duplicate, including an already accepted token.
        revalidate(claim);
        if(!Objects.equals(claim.skill().digest(),execution.loadedSkillDigest()) || !Objects.equals(claim.configDigest(),execution.configDigest()))
            throw BiddingAccess.error(409,"EXECUTION_FINGERPRINT_MISMATCH","Execution fingerprint does not match the claimed snapshot");
        BiddingResultHandler handler=handler(claim.skill().skillId());
        BiddingTypes.Ref accepted;
        try { accepted=handler.accept(claim,execution.payload()); }
        catch(DataAccessException|TransactionException persistenceFailure) { throw persistenceFailure; }
        catch(BiddingApiException rejected) { throw rejected; }
        catch(RuntimeException programmingFailure) { throw new ResultHandlerFailure(programmingFailure); }
        if(accepted==null) throw BiddingAccess.error(422,"RESULT_NOT_ACCEPTED","Task result handler did not create an accepted result");
        ObjectNode output=json.createObjectNode(); output.set("ref",json.valueToTree(accepted)); output.set("payload",execution.payload());
        Timestamp now=Timestamp.from(Instant.now());
        int attemptChanged=jdbc.update("UPDATE mate_bidding_attempt SET state='SUCCEEDED',output_json=?,finished_at=? WHERE id=? AND token=? AND state='RUNNING'",write(output),now,claim.attemptId(),claim.token());
        if(attemptChanged!=1) throw BiddingAccess.error(409,"ATTEMPT_STALE","Task attempt is no longer active");
        int changed=jdbc.update("UPDATE mate_bidding_task SET status='SUCCEEDED',active_attempt_id=NULL,next_run_at=NULL,updated_at=? WHERE id=? AND status='RUNNING' AND active_attempt_id=?",now,claim.taskId(),claim.attemptId());
        if(changed!=1) throw BiddingAccess.error(409,"ATTEMPT_STALE","Task attempt is no longer active");
    }

    private void persistFailure(BiddingTypes.Claim claim,BiddingTypes.Execution execution) {
        BiddingTypes.Failure failure=execution.failure();
        transactions.execute(tx->{
            if(activeAttempt(claim)==null) return null;
            Timestamp now=Timestamp.from(Instant.now()); String error=write(failure);
            long jitter=Math.floorMod(claim.attemptId().hashCode(),1000);
            var delay=retryPolicy.nextDelayMs(failure,claim.cycleAttempt(),jitter);
            if(delay.isPresent()) {
                jdbc.update("UPDATE mate_bidding_attempt SET state='FAILED',error_json=?,rejected_output=?,finished_at=? WHERE id=? AND token=? AND state='RUNNING'",
                    error,execution.rejectedOutput(),now,claim.attemptId(),claim.token());
                jdbc.update("UPDATE mate_bidding_task SET status='WAITING_RETRY',active_attempt_id=NULL,cycle_attempt=cycle_attempt+1,next_run_at=?,updated_at=? WHERE id=? AND status='RUNNING' AND active_attempt_id=?",
                    Timestamp.from(now.toInstant().plusMillis(delay.getAsLong())),now,claim.taskId(),claim.attemptId());
            } else {
                String status="STALE".equals(failure.category())?"STALE":"FAILED";
                jdbc.update("UPDATE mate_bidding_attempt SET state=?,error_json=?,rejected_output=?,finished_at=? WHERE id=? AND token=? AND state='RUNNING'",
                    status,error,execution.rejectedOutput(),now,claim.attemptId(),claim.token());
                jdbc.update("UPDATE mate_bidding_task SET status=?,active_attempt_id=NULL,next_run_at=NULL,updated_at=? WHERE id=? AND status='RUNNING' AND active_attempt_id=?",
                    status,now,claim.taskId(),claim.attemptId());
            }
            return null;
        });
    }

    private void persistRejected(BiddingTypes.Claim claim,BiddingTypes.Execution execution,String code,String category) {
        transactions.execute(tx->{
            if(activeAttempt(claim)==null) return null;
            Timestamp now=Timestamp.from(Instant.now()); ObjectNode error=json.createObjectNode(); error.put("code",code); error.put("category",category);
            jdbc.update("UPDATE mate_bidding_attempt SET state='FAILED',error_json=?,rejected_output=?,finished_at=? WHERE id=? AND token=? AND state='RUNNING'",
                write(error),execution.rejectedOutput()==null?write(execution.payload()):execution.rejectedOutput(),now,claim.attemptId(),claim.token());
            jdbc.update("UPDATE mate_bidding_task SET status='FAILED',active_attempt_id=NULL,next_run_at=NULL,updated_at=? WHERE id=? AND status='RUNNING' AND active_attempt_id=?",now,claim.taskId(),claim.attemptId());
            return null;
        });
    }

    private void revalidate(BiddingTypes.Claim claim) {
        runtime.getObject().requireActive(claim);
    }
    private AttemptRow activeAttempt(BiddingTypes.Claim claim) {
        return jdbc.query("SELECT a.state FROM mate_bidding_attempt a JOIN mate_bidding_task t ON t.id=a.task_id WHERE a.id=? AND a.token=? AND a.task_id=? AND a.state='RUNNING' AND t.status='RUNNING' AND t.active_attempt_id=a.id",
            rs->rs.next()?new AttemptRow(rs.getString(1)):null,claim.attemptId(),claim.token(),claim.taskId());
    }
    private BiddingResultHandler handler(String skillId) {
        List<BiddingResultHandler> matches=handlers.orderedStream().filter(h->h.skillIds().contains(skillId)).toList();
        if(matches.size()!=1) throw BiddingAccess.error(422,matches.isEmpty()?"SKILL_HANDLER_MISSING":"SKILL_HANDLER_AMBIGUOUS","Task result handler is unavailable");
        return matches.getFirst();
    }
    private TaskRow task(BiddingTypes.Scope scope,String id,boolean lock) {
        String sql="SELECT id,actor_id,agent_id,config_digest,input_refs_json,status,active_attempt_id FROM mate_bidding_task WHERE id=? AND workspace_id=? AND project_id=?"+(lock?" FOR UPDATE":"");
        return jdbc.query(sql,rs->rs.next()?new TaskRow(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),rs.getString(7)):null,id,scope.workspaceId(),scope.projectId())
            instanceof TaskRow row?row:missingTask();
    }
    private TaskRow missingTask() { throw BiddingAccess.error(404,"NOT_FOUND","Task not found"); }
    private Binding findBinding(ObjectNode project,String skillId) {
        for(String role:List.of("analyst","writer","reviewer")) {
            var binding=project.path("bindings").path(role);
            if(binding.isMissingNode() || binding.isNull()) continue;
            for(var pin:binding.path("skillPins")) if(skillId.equals(pin.path("skillId").asText())) {
                String name=jdbc.query("SELECT name FROM mate_skill WHERE id=?",rs->rs.next()?rs.getString(1):null,Long.valueOf(skillId));
                if(name==null || !ROLE_SKILLS.get(role).contains(name)) throw BiddingAccess.error(422,"SKILL_ROLE_MISMATCH","Skill is not allowed for this project role");
                String agent=binding.path("agentId").asText(null),config=binding.path("configDigest").asText(null);
                if(agent==null || config==null) throw BiddingAccess.error(422,"EMPLOYEE_UNAVAILABLE","No employee is assigned for this skill");
                return new Binding(agent,config,pin.path("digest").asText());
            }
        }
        throw BiddingAccess.error(422,"SKILL_NOT_ASSIGNED","Skill is not pinned to a project role");
    }
    private BiddingTypes.SkillPin loadPinnedPackage(BiddingTypes.Scope scope,String skillId,String digest) {
        return jdbc.query("SELECT version,digest,files_json FROM mate_bidding_skill_package WHERE workspace_id=? AND project_id=? AND skill_id=? AND digest=? ORDER BY created_at DESC",
            rs->{if(!rs.next()) throw BiddingAccess.error(409,"SKILL_PIN_STALE","Pinned skill package is unavailable");
                try { return new BiddingTypes.SkillPin(skillId,rs.getString(1),rs.getString(2),json.readValue(rs.getString(3),new TypeReference<Map<String,String>>(){})); }
                catch(Exception e) { throw new IllegalStateException("Invalid pinned skill package",e); }},scope.workspaceId(),scope.projectId(),skillId,digest);
    }
    private String pinPackageId(BiddingTypes.Scope scope,String skillId,String digest) {
        return jdbc.queryForObject("SELECT id FROM mate_bidding_skill_package WHERE workspace_id=? AND project_id=? AND skill_id=? AND digest=? ORDER BY created_at DESC LIMIT 1",String.class,scope.workspaceId(),scope.projectId(),skillId,digest);
    }
    private List<BiddingTypes.Ref> readRefs(String raw) { try{return json.readValue(raw,new TypeReference<List<BiddingTypes.Ref>>(){});}catch(Exception e){throw new IllegalStateException("Invalid task reference snapshot",e);} }
    private ObjectNode parseObject(String raw) { try { var value=json.readTree(raw); if(!value.isObject()) throw new IllegalStateException("Expected object snapshot"); return (ObjectNode)value; } catch(IllegalStateException e) { throw e; } catch(Exception e) { throw new IllegalStateException("Invalid task input snapshot",e); } }
    private ObjectNode replay(BiddingTypes.Scope scope,String op,String digest) { var old=repository.findOperation(scope.workspaceId(),scope.actorId(),op); if(old==null) throw BiddingAccess.error(409,"OPERATION_CONFLICT","Task operation conflicted"); return replay(old,digest); }
    private ObjectNode replay(BiddingRepository.StoredOperation old,String digest) { if(!old.digest().equals(digest)) throw BiddingAccess.error(409,"OPERATION_CONFLICT","operationId was already used with a different task"); return old.result(); }
    private String digest(Object value) { try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(value)));}catch(Exception e){throw new IllegalStateException(e);} }
    private String write(Object value) { try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);} }
    private void sleep(long millis) { try { Thread.sleep(millis); } catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("Completion retry interrupted",e); } }
    public int recoverInterrupted(String newBootId) { return repository.recoverInterrupted(newBootId,Instant.now()); }
    public int recoverInterrupted(String newBootId,Instant now) { return repository.recoverInterrupted(newBootId,now); }
    public boolean isTaskRunning(String taskId) { return repository.isTaskRunning(taskId); }
    public List<BiddingTypes.Claim> claimDue(Instant now,String bootId,int limit) { return repository.claimDue(now,bootId,limit); }
    public void run(BiddingTypes.Claim claim) {
        try { revalidate(claim); }
        catch(BiddingApiException e) { complete(claim,failed(e.code(),"STALE",false)); return; }
        catch(RuntimeException e) { complete(claim,failed("AUTHORIZATION_REVALIDATION_FAILED","PERMANENT",false)); return; }
        BiddingTypes.Execution execution;
        try { execution=runtime.getObject().execute(claim); }
        catch(RuntimeException e) { execution=failed("EXECUTION_RUNTIME_FAILURE","PERMANENT",true); }
        if(execution==null) execution=failed("EXECUTION_RUNTIME_FAILURE","PERMANENT",true);
        complete(claim,execution);
    }

    private BiddingTypes.Execution failed(String code,String category,boolean resultUnknown) {
        return new BiddingTypes.Execution(null,new BiddingTypes.Failure(code,category,null,resultUnknown,false,false),null,null,null);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void validateResultHandlers() {
        Set<String> registered=new HashSet<>();
        for(BiddingResultHandler handler:handlers.orderedStream().toList())
            for(String skillId:handler.skillIds()) if(!registered.add(skillId))
                throw new IllegalStateException("Duplicate bidding result handler for skill "+skillId);
    }

    private record Binding(String agentId,String configDigest,String skillDigest) {}
    private record TaskRow(String id,String actorId,String agentId,String configDigest,String refsJson,String status,String activeAttemptId) {}
    private record AttemptRow(String state) {}
    private static final class ResultHandlerFailure extends RuntimeException {
        ResultHandlerFailure(RuntimeException cause) { super("Bidding result handler failed",cause); }
    }
    private record TaskView(String id,String projectId,String inputJson,String refsJson,String status,int cycleNo,int cycleAttempt,int attemptCount,Timestamp createdAt,Timestamp updatedAt) {}
}

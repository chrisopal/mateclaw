package vip.mate.semantic.extraction;

import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vip.mate.semantic.web.SemanticApiException;
import static vip.mate.semantic.application.extraction.ExtractionContracts.*;
import vip.mate.semantic.application.extraction.ExtractionPorts.ExtractionTaskRepository;

/** All claims serialize on a single database row, including capacity and expiry recovery. */
public class JdbcExtractionRepository implements ExtractionTaskRepository {
    final JdbcTemplate jdbc;
    final TransactionTemplate tx;
    final ExtractionJson json=new ExtractionJson();
    public JdbcExtractionRepository(JdbcTemplate jdbc,PlatformTransactionManager manager){this.jdbc=jdbc;tx=new TransactionTemplate(manager);}
    private void gate(){jdbc.queryForObject("SELECT id FROM mate_semantic_extraction_gate WHERE id=1 FOR UPDATE",Integer.class);}
    public Task insertOrReplay(Task t){return tx.execute(s->{
        gate();
        var found=jdbc.query("SELECT * FROM mate_semantic_extraction_task WHERE workspace_id=? AND graph_id=? AND operation_id=?",this::task,t.actor().workspaceId(),t.graphId(),t.operationId());
        if(!found.isEmpty()){if(!found.getFirst().requestHash().equals(t.requestHash()))throw conflict("OPERATION_CONFLICT");return found.getFirst();}
        jdbc.update("INSERT INTO mate_semantic_extraction_task(id,workspace_id,graph_id,operation_id,request_hash,payload_json,status,version,cancel_requested,generation,attempts,completed_chunks,trace_id,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,0,0,0,?,?,?)",
            t.taskId(),t.actor().workspaceId(),t.graphId(),t.operationId(),t.requestHash(),json.write(t),t.status().name(),t.version(),false,UUID.randomUUID().toString(),at(t.createdAt()),at(t.updatedAt()));return t;
    });}
    public Optional<Task> find(Actor a,String graph,String id){return jdbc.query("SELECT * FROM mate_semantic_extraction_task WHERE id=? AND workspace_id=? AND graph_id=?",this::task,id,a.workspaceId(),graph).stream().findFirst();}
    public Task requeue(Actor actor,String graph,String taskId,String operation,Instant now){return tx.execute(s->{
        gate();Task t=find(actor,graph,taskId).orElseThrow(()->conflict("NOT_FOUND"));
        var replay=jdbc.queryForList("SELECT resource_id,action FROM mate_semantic_extraction_action WHERE graph_id=? AND operation_id=?",graph,operation);
        if(!replay.isEmpty()){if(!taskId.equals(replay.getFirst().get("resource_id"))||!"RETRY".equals(replay.getFirst().get("action")))throw conflict("OPERATION_CONFLICT");return t;}
        int attempts=((Number)metadata(taskId).get("attempts")).intValue();
        if(attempts>=3)throw conflict("ATTEMPT_LIMIT");if(t.status()!=TaskStatus.FAILED)throw conflict("TASK_NOT_RETRYABLE");
        jdbc.update("UPDATE mate_semantic_extraction_task SET status='QUEUED',cancel_requested=FALSE,version=version+1,updated_at=? WHERE id=?",at(now),taskId);
        Task result=require(taskId);jdbc.update("INSERT INTO mate_semantic_extraction_action(graph_id,operation_id,resource_id,action,request_hash,result_json) VALUES(?,?,?,'RETRY',?,?)",graph,operation,taskId,t.requestHash(),json.write(result));return result;
    });}
    public <T>T editOperation(String graph,String id,String operation,String hash,Class<T> type,java.util.function.Supplier<T> work){return tx.execute(s->{
        gate();var rows=jdbc.queryForList("SELECT resource_id,action,request_hash,result_json FROM mate_semantic_extraction_action WHERE graph_id=? AND operation_id=?",graph,operation);
        if(!rows.isEmpty()){var r=rows.getFirst();if(!id.equals(r.get("resource_id"))||!"EDIT".equals(r.get("action"))||!hash.equals(r.get("request_hash")))throw conflict("OPERATION_CONFLICT");return json.read((String)r.get("result_json"),type);}
        T result=work.get();jdbc.update("INSERT INTO mate_semantic_extraction_action(graph_id,operation_id,resource_id,action,request_hash,result_json) VALUES(?,?,?,'EDIT',?,?)",graph,operation,id,hash,json.write(result));return result;
    });}
    public Task findClaimed(Lease lease){return require(lease.taskId());}
    public Task require(String id){return jdbc.query("SELECT * FROM mate_semantic_extraction_task WHERE id=?",this::task,id).stream().findFirst().orElseThrow(()->new SemanticApiException(404,"NOT_FOUND","Task unavailable"));}
    public List<Task> tasks(Actor a,String graph){return jdbc.query("SELECT * FROM mate_semantic_extraction_task WHERE workspace_id=? AND graph_id=? ORDER BY created_at DESC,id",this::task,a.workspaceId(),graph);}
    public Optional<Attempt> claim(String worker,Instant now,Instant expiry,int workspaceLimit,int globalLimit){return tx.execute(s->{
        gate();
        jdbc.update("UPDATE mate_semantic_extraction_task SET status='FAILED',error_code='ATTEMPT_LIMIT',version=version+1,updated_at=? WHERE status='RUNNING' AND lease_until<=? AND attempts>=3",at(now),at(now));
        int running=jdbc.queryForObject("SELECT COUNT(*) FROM mate_semantic_extraction_task WHERE status='RUNNING' AND lease_until>?",Integer.class,at(now));
        if(running>=globalLimit)return Optional.empty();
        var candidates=jdbc.query("SELECT * FROM mate_semantic_extraction_task WHERE cancel_requested=FALSE AND attempts<3 AND (status='QUEUED' OR (status='RUNNING' AND lease_until<=?)) ORDER BY created_at,id",this::task,at(now));
        for(Task t:candidates){
            int count=jdbc.queryForObject("SELECT COUNT(*) FROM mate_semantic_extraction_task WHERE workspace_id=? AND status='RUNNING' AND lease_until>?",Integer.class,t.actor().workspaceId(),at(now));
            if(count>=workspaceLimit)continue;
            jdbc.update("UPDATE mate_semantic_extraction_task SET status='RUNNING',version=version+1,generation=generation+1,attempts=attempts+1,lease_owner=?,lease_until=?,completed_chunks=0,error_code=NULL,updated_at=? WHERE id=?",worker,at(expiry),at(now),t.taskId());
            long generation=jdbc.queryForObject("SELECT generation FROM mate_semantic_extraction_task WHERE id=?",Long.class,t.taskId());
            int number=jdbc.queryForObject("SELECT attempts FROM mate_semantic_extraction_task WHERE id=?",Integer.class,t.taskId());
            Attempt a=new Attempt(id(),t.taskId(),number,new Lease(t.taskId(),worker,generation,expiry),t.configuration(),new Usage(0,0),now,null,null);
            jdbc.update("INSERT INTO mate_semantic_extraction_attempt(id,task_id,attempt_number,generation,payload_json) VALUES(?,?,?,?,?)",a.attemptId(),t.taskId(),number,generation,json.write(a));
            return Optional.of(a);
        }
        return Optional.empty();
    });}
    public boolean heartbeat(Lease l,Instant expiry){return jdbc.update("UPDATE mate_semantic_extraction_task SET lease_until=? WHERE id=? AND generation=? AND lease_owner=? AND status='RUNNING' AND cancel_requested=FALSE AND lease_until>?",at(expiry),l.taskId(),l.generation(),l.workerId(),at(expiry.minusSeconds(60)))==1;}
    public boolean progress(Lease l,int count,Instant now){return jdbc.update("UPDATE mate_semantic_extraction_task SET completed_chunks=?,updated_at=? WHERE id=? AND generation=? AND status='RUNNING' AND cancel_requested=FALSE AND lease_until>?",count,at(now),l.taskId(),l.generation(),at(now))==1;}
    private boolean finish(Lease l,Attempt a,String status,Instant now){
        int changed=jdbc.update("UPDATE mate_semantic_extraction_task SET status=?,version=version+1,lease_until=NULL,lease_owner=NULL,error_code=?,updated_at=? WHERE id=? AND generation=? AND lease_owner=? AND status='RUNNING' AND cancel_requested=FALSE AND lease_until>?",status,a.failure()==null?null:a.failure().code(),at(now),l.taskId(),l.generation(),l.workerId(),at(now));
        if(changed==1)jdbc.update("UPDATE mate_semantic_extraction_attempt SET payload_json=? WHERE id=? AND generation=?",json.write(a),a.attemptId(),l.generation());return changed==1;
    }
    public boolean complete(Lease l,Attempt a,List<Suggestion> suggestions,Instant now){return complete(l,a,suggestions,now,null);}
    public boolean complete(Lease l,Attempt a,List<Suggestion> suggestions,Instant now,String explanation){return Boolean.TRUE.equals(tx.execute(s->{
        if(!finish(l,a,"SUCCEEDED",now))return false;
        explanation(l.taskId(),explanation);
        String actor=require(l.taskId()).actor().userId();
        for(Suggestion suggestion:suggestions){
            jdbc.update("INSERT INTO mate_semantic_extraction_suggestion(id,task_id,attempt_id,version,status,payload_json) VALUES(?,?,?,?,?,?)",suggestion.suggestionId(),l.taskId(),a.attemptId(),suggestion.editVersion(),suggestion.status().name(),json.write(suggestion));
            jdbc.update("INSERT INTO mate_semantic_extraction_edit(suggestion_id,version,payload_json,actor_id,created_at) VALUES(?,?,?,?,?)",suggestion.suggestionId(),suggestion.editVersion(),json.write(suggestion),actor,at(now));
        }
        return true;
    }));}
    public boolean fail(Lease l,Attempt a,Instant now){return Boolean.TRUE.equals(tx.execute(s->finish(l,a,"FAILED",now)));}
    public boolean update(Task t,long version){return jdbc.update("UPDATE mate_semantic_extraction_task SET status=?,cancel_requested=?,version=version+1,updated_at=? WHERE id=? AND workspace_id=? AND graph_id=? AND version=?",t.status().name(),t.cancelRequested(),at(t.updatedAt()),t.taskId(),t.actor().workspaceId(),t.graphId(),version)==1;}
    public List<Suggestion> suggestions(Actor a,String graph,String task,int offset,int limit){
        if(find(a,graph,task).isEmpty())return List.of();
        return jdbc.query("SELECT s.* FROM mate_semantic_extraction_suggestion s JOIN mate_semantic_extraction_task t ON t.id=s.task_id WHERE t.id=? AND t.workspace_id=? AND t.graph_id=? AND t.status='SUCCEEDED' ORDER BY s.id LIMIT ? OFFSET ?",this::suggestion,task,a.workspaceId(),graph,limit,offset);
    }
    public Optional<Suggestion> suggestion(Actor a,String graph,String id){return jdbc.query("SELECT s.* FROM mate_semantic_extraction_suggestion s JOIN mate_semantic_extraction_task t ON t.id=s.task_id WHERE s.id=? AND t.workspace_id=? AND t.graph_id=? AND t.status='SUCCEEDED'",this::suggestion,id,a.workspaceId(),graph).stream().findFirst();}
    public boolean edit(Actor a,String graph,Suggestion value,long expected){return Boolean.TRUE.equals(tx.execute(s->{
        gate();
        if(suggestion(a,graph,value.suggestionId()).isEmpty())return false;
        if(jdbc.queryForObject("SELECT COUNT(*) FROM mate_semantic_extraction_submission_intent WHERE suggestion_id=?",Integer.class,value.suggestionId())>0)throw conflict("SUBMISSION_IN_PROGRESS");
        int n=jdbc.update("UPDATE mate_semantic_extraction_suggestion SET payload_json=?,version=?,status=? WHERE id=? AND version=? AND status='OPEN'",json.write(value),value.editVersion(),value.status().name(),value.suggestionId(),expected);
        if(n==1)jdbc.update("INSERT INTO mate_semantic_extraction_edit(suggestion_id,version,payload_json,actor_id,created_at) VALUES(?,?,?,?,?)",value.suggestionId(),value.editVersion(),json.write(value),a.userId(),at(Instant.now()));return n==1;
    }));}
    public Optional<Receipt> receipt(Actor a,String graph,String id,long version){if(suggestion(a,graph,id).isEmpty())return Optional.empty();return jdbc.query("SELECT payload_json FROM mate_semantic_extraction_receipt WHERE suggestion_id=? AND edit_version=? AND graph_id=?",(rs,n)->json.read(rs.getString(1),Receipt.class),id,version,graph).stream().findFirst();}
    public void reserveSubmission(Actor a,String graph,Suggestion suggestion,String operation,String hash){tx.executeWithoutResult(s->{
        gate();
        var current=suggestion(a,graph,suggestion.suggestionId()).orElseThrow(()->conflict("SUGGESTION_VERSION_CONFLICT"));
        if(current.editVersion()!=suggestion.editVersion()||current.status()!=SuggestionStatus.OPEN)throw conflict("SUGGESTION_VERSION_CONFLICT");
        var rows=jdbc.queryForList("SELECT operation_id,request_hash FROM mate_semantic_extraction_submission_intent WHERE suggestion_id=? AND edit_version=?",suggestion.suggestionId(),suggestion.editVersion());
        if(!rows.isEmpty()){if(!operation.equals(rows.getFirst().get("operation_id"))||!hash.equals(rows.getFirst().get("request_hash")))throw conflict("OPERATION_CONFLICT");return;}
        var reused=jdbc.queryForList("SELECT suggestion_id FROM mate_semantic_extraction_submission_intent WHERE graph_id=? AND operation_id=?",graph,operation);if(!reused.isEmpty())throw conflict("OPERATION_CONFLICT");
        jdbc.update("INSERT INTO mate_semantic_extraction_submission_intent(suggestion_id,edit_version,graph_id,operation_id,request_hash) VALUES(?,?,?,?,?)",suggestion.suggestionId(),suggestion.editVersion(),graph,operation,hash);
    });}
    public void saveReceipt(Actor a,String graph,Receipt value){tx.executeWithoutResult(s->{
        gate();var old=receipt(a,graph,value.suggestionId(),value.editVersion());
        if(old.isPresent()){if(!old.get().equals(value))throw conflict("OPERATION_CONFLICT");return;}
        jdbc.update("INSERT INTO mate_semantic_extraction_receipt(suggestion_id,edit_version,graph_id,operation_id,request_hash,payload_json) VALUES(?,?,?,?,?,?)",value.suggestionId(),value.editVersion(),graph,value.operationId(),value.requestHash(),json.write(value));
        if(jdbc.update("UPDATE mate_semantic_extraction_suggestion SET status='SUBMITTED' WHERE id=? AND version=? AND status='OPEN'",value.suggestionId(),value.editVersion())!=1)throw conflict("SUGGESTION_VERSION_CONFLICT");
    });}
    public <T>T lockedSuggestion(String id,java.util.function.Supplier<T> operation){return tx.execute(s->{jdbc.queryForObject("SELECT id FROM mate_semantic_extraction_suggestion WHERE id=? FOR UPDATE",String.class,id);return operation.get();});}
    public void cancelActive(){jdbc.update("UPDATE mate_semantic_extraction_task SET status='CANCELLED',cancel_requested=TRUE,version=version+1,updated_at=? WHERE status IN ('QUEUED','RUNNING')",at(Instant.now()));}
    public String pendingOperation(String id,long version){return jdbc.query("SELECT operation_id FROM mate_semantic_extraction_submission_intent WHERE suggestion_id=? AND edit_version=?",(rs,n)->rs.getString(1),id,version).stream().findFirst().orElse(null);}
    public Map<String,Object> metadata(String task){return jdbc.queryForMap("SELECT attempts,completed_chunks,error_code,trace_id,explanation FROM mate_semantic_extraction_task WHERE id=?",task);}
    public void explanation(String task,String value){jdbc.update("UPDATE mate_semantic_extraction_task SET explanation=? WHERE id=? AND status='SUCCEEDED'",value==null?null:value.substring(0,Math.min(1000,value.length())),task);}
    private Task task(ResultSet rs,int n)throws SQLException{Task t=json.read(rs.getString("payload_json"),Task.class);return new Task(t.taskId(),t.actor(),t.graphId(),t.operationId(),t.requestHash(),t.source(),t.ontology(),t.definitionHash(),t.promptVersion(),t.configuration(),TaskStatus.valueOf(rs.getString("status")),rs.getLong("version"),rs.getBoolean("cancel_requested"),t.createdAt(),rs.getTimestamp("updated_at").toInstant());}
    private Suggestion suggestion(ResultSet rs,int n)throws SQLException{Suggestion v=json.read(rs.getString("payload_json"),Suggestion.class);return new Suggestion(v.suggestionId(),v.taskId(),v.attemptId(),v.content(),v.mappedContent(),v.diagnostics(),rs.getLong("version"),SuggestionStatus.valueOf(rs.getString("status")));}
    static Timestamp at(Instant i){return Timestamp.from(i);}
    static String id(){return com.baomidou.mybatisplus.core.toolkit.IdWorker.getIdStr();}
    static SemanticApiException conflict(String code){return new SemanticApiException(409,code,"Extraction state changed; reload and retry");}
}

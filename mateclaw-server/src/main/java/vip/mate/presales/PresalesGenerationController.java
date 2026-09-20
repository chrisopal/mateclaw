package vip.mate.presales;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import vip.mate.common.result.R;

@RestController
@RequestMapping("/api/v1/presales")
@ConditionalOnProperty(name="mateclaw.presales.enabled",havingValue="true")
public class PresalesGenerationController {
    static final Map<String,String> SKILLS=Map.of("S1","customer-context-analysis","S2","requirement-analysis-and-clarification",
        "S3","capability-mapping","S4","case-retrieval","S5","solution-composer","S6","proposal-generation",
        "S7","solution-review","S8","context-maintenance");
    private final PresalesService service; private final PresalesAccess access; private final PresalesContextProvider contexts;
    private final PresalesEmployeeRuntime model; private final ObjectMapper json; private final PresalesGenerationCoordinator coordinator;
    public PresalesGenerationController(PresalesService service,PresalesAccess access,PresalesContextProvider contexts,PresalesEmployeeRuntime model,ObjectMapper json,PresalesGenerationCoordinator coordinator){this.service=service;this.access=access;this.contexts=contexts;this.model=model;this.json=json;this.coordinator=coordinator;}
    public record Generate(Integer expectedVersion,String operationId,String skill,String taskGoal){}
    public record Cancel(String operationId){}
    @GetMapping("/employees") public R<?> employees(@RequestHeader(value="X-Workspace-Id",required=false)String scope){access.require(scope,"viewer");return R.ok(model.employees(scope));}
    /** Persist the task and return immediately; the coordinator owns all model work. */
    @PostMapping("/projects/{id}/generate") public R<?> generate(@RequestHeader(value="X-Workspace-Id",required=false)String scope,@PathVariable String id,@RequestBody Generate input){
        String actor=access.require(scope,"member");
        if(input==null||input.expectedVersion()==null||input.operationId()==null||input.operationId().isBlank()
            ||input.operationId().length()>100||!SKILLS.containsKey(Objects.toString(input.skill(),""))
            ||input.taskGoal()==null||input.taskGoal().isBlank()||input.taskGoal().length()>5000)throw PresalesModelAdapter.error(400,"INVALID_REQUEST");
        ObjectNode project=service.get(scope,id);
        String requestHash;
        try{requestHash=PresalesArtifactRenderer.digest(json.writeValueAsBytes(input));}catch(Exception e){throw new IllegalStateException(e);}
        for(var previous:project.path("tasks"))if(input.operationId().equals(previous.path("operationId").asText())){
            if(!requestHash.equals(previous.path("requestHash").asText()))throw PresalesModelAdapter.error(409,"OPERATION_CONFLICT");
            // A retry returns the durable task envelope and never submits another model run.
            return R.ok(project);
        }
        if(project.path("version").asInt()!=input.expectedVersion())throw PresalesModelAdapter.error(409,"VERSION_CONFLICT");
        var employee=model.require(scope,project.path("agentId").asText());
        ObjectNode snapshot=contexts.snapshot(scope,project,input.skill(),input.taskGoal());
        ObjectNode task=json.createObjectNode();task.put("operationId",input.operationId()).put("requestHash",requestHash)
            .put("skill",input.skill()).put("agentId",employee.getId().toString()).put("agentName",employee.getName()).put("taskGoal",input.taskGoal()).put("status","RUNNING")
            .put("queueState","QUEUED").put("queuedAt",Instant.now().toString()).put("runId",UUID.randomUUID().toString()).put("needsHumanReview",true);
        task.put("conversationId","presales:"+scope+":"+id+":"+task.path("runId").asText());
        task.set("contextSnapshot",snapshot);
        project=service.command(scope,id,new PresalesDtos.Command(input.expectedVersion(),input.operationId()+":start","SAVE_AI_TASK",task));
        ObjectNode stored=(ObjectNode)project.path("tasks").get(project.path("tasks").size()-1);task=stored.deepCopy();
        snapshot.put("projectVersion",project.path("version").asInt());
        coordinator.enqueue(new PresalesGenerationCoordinator.Submission(scope,actor,id,input.operationId(),input.skill(),input.taskGoal(),task,snapshot,project.path("version").asInt()));
        return R.ok(project);
    }

    /** Reserve cancellation before changing the durable task state. */
    @PostMapping("/projects/{id}/tasks/{taskId}/cancel") public R<?> cancel(@RequestHeader(value="X-Workspace-Id",required=false)String scope,@PathVariable String id,@PathVariable String taskId,@RequestBody(required=false)Cancel input){
        access.require(scope,"member"); ObjectNode project=service.get(scope,id); ObjectNode task=service.find(project,"tasks",taskId);
        if(!"RUNNING".equals(task.path("status").asText()))throw PresalesModelAdapter.error(409,"TASK_STATE");
        String operationId=input==null||input.operationId()==null||input.operationId().isBlank()?"cancel:"+taskId:input.operationId();
        if(operationId.length()>100)throw PresalesModelAdapter.error(400,"INVALID_REQUEST"); coordinator.requestCancellation(scope,id,taskId);
        try{return R.ok(service.command(scope,id,new PresalesDtos.Command(project.path("version").asInt(),operationId,"CANCEL_AI_TASK",json.createObjectNode().put("taskId",taskId))));}
        catch(RuntimeException e){coordinator.clearCancellation(scope,id,taskId);throw e;}
    }
}

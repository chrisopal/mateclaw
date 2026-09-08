package vip.mate.semantic.extraction;

import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import vip.mate.common.result.R;
import vip.mate.semantic.security.SemanticAccessService;
import vip.mate.semantic.application.extraction.*;
import vip.mate.semantic.core.fact.*;
import vip.mate.semantic.core.identity.SemanticIds.*;
import vip.mate.semantic.web.SemanticApiException;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;
import static vip.mate.semantic.application.extraction.ExtractionContracts.*;

@RestController
@RequestMapping("/api/v1/semantic/graphs/{graphId}")
@ConditionalOnProperty(name="mateclaw.semantic.enabled",havingValue="true")
public class ExtractionController {
    private final SemanticAccessService identity;private final MateClawAccessAdapter access;private final MateClawContextAdapter context;private final MateClawModelAdapter models;
    private final JdbcExtractionRepository repo;private final ExtractionCoordinator coordinator;private final SuggestionSubmissionService submissions;private final ExtractionConfiguration.Feature feature;private final MateClawSourceAdapter sources;
    public ExtractionController(SemanticAccessService identity,MateClawAccessAdapter access,MateClawContextAdapter context,MateClawModelAdapter models,JdbcExtractionRepository repo,ExtractionCoordinator coordinator,SuggestionSubmissionService submissions,ExtractionConfiguration.Feature feature,MateClawSourceAdapter sources){this.identity=identity;this.access=access;this.context=context;this.models=models;this.repo=repo;this.coordinator=coordinator;this.submissions=submissions;this.feature=feature;this.sources=sources;}
    private Actor actor(String scope,String role){return new Actor(scope,identity.require(scope,role).getId().toString());}
    @GetMapping("/extraction-capabilities") @RequireWorkspaceRole("viewer")
    public R<ExtractionDtos.Capabilities> capabilities(@RequestHeader(value="X-Workspace-Id",required=false)String scope,@PathVariable String graphId){var a=actor(scope,"viewer");access.require(a,graphId,null,Action.READ);return R.ok(new ExtractionDtos.Capabilities(feature.enabled(),models.models(),context.ontology(a,graphId).revisionId().value()));}
    @PostMapping("/extraction-tasks") @ResponseStatus(HttpStatus.ACCEPTED) @RequireWorkspaceRole("member")
    public R<ExtractionDtos.TaskView> start(@RequestHeader(value="X-Workspace-Id",required=false)String scope,@PathVariable String graphId,@RequestBody ExtractionDtos.StartRequest request){var a=actor(scope,"member");var ref=sources.withGraphLock(a,graphId,()->coordinator.start(a,new StartCommand(graphId,request.sourceRef(),request.modelConfigId(),request.operationId())));return R.ok(view(visible(a,graphId,ref.taskId())));}
    @GetMapping("/extraction-tasks/{taskId}") @RequireWorkspaceRole("viewer")
    public R<ExtractionDtos.TaskView> task(@RequestHeader(value="X-Workspace-Id",required=false)String scope,@PathVariable String graphId,@PathVariable String taskId){return R.ok(view(visible(actor(scope,"viewer"),graphId,taskId)));}
    @GetMapping("/extraction-tasks") @RequireWorkspaceRole("viewer")
    public R<ExtractionDtos.Page<ExtractionDtos.TaskView>> tasks(@RequestHeader(value="X-Workspace-Id",required=false)String scope,@PathVariable String graphId,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int pageSize){
        var a=actor(scope,"viewer");access.require(a,graphId,null,Action.READ);page(page,pageSize);var visible=repo.tasks(a,graphId).stream().filter(t->{try{access.require(a,graphId,t.source().sourceRef(),Action.READ);snapshotAvailable(t);return true;}catch(SemanticApiException e){if(e.status()==404||e.status()==403)return false;throw e;}}).toList();
        return R.ok(new ExtractionDtos.Page<>(visible.stream().skip((long)(page-1)*pageSize).limit(pageSize).map(t->view(t,false)).toList(),visible.size(),page,pageSize));
    }
    @PostMapping("/extraction-tasks/{taskId}/cancel") @RequireWorkspaceRole("member")
    public R<ExtractionDtos.TaskView> cancel(@RequestHeader(value="X-Workspace-Id",required=false)String scope,@PathVariable String graphId,@PathVariable String taskId){var a=actor(scope,"member");coordinator.cancel(a,graphId,taskId);return R.ok(view(visible(a,graphId,taskId)));}
    @PostMapping("/extraction-tasks/{taskId}/retry") @RequireWorkspaceRole("member")
    public R<ExtractionDtos.TaskView> retry(@RequestHeader(value="X-Workspace-Id",required=false)String scope,@PathVariable String graphId,@PathVariable String taskId,@RequestBody ExtractionDtos.OperationRequest r){var a=actor(scope,"member");Task t=visible(a,graphId,taskId);if(((Number)repo.metadata(taskId).get("attempts")).intValue()>=3)throw new SemanticApiException(409,"ATTEMPT_LIMIT","Task exhausted three attempts");coordinator.retry(a,graphId,taskId,r.operationId());return R.ok(view(visible(a,graphId,taskId)));}
    @GetMapping("/extraction-tasks/{taskId}/suggestions") @RequireWorkspaceRole("viewer")
    public R<ExtractionDtos.Page<ExtractionDtos.SuggestionView>> suggestions(@RequestHeader(value="X-Workspace-Id",required=false)String scope,@PathVariable String graphId,@PathVariable String taskId,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int pageSize){var a=actor(scope,"viewer");visible(a,graphId,taskId);page(page,pageSize);var all=repo.suggestions(a,graphId,taskId,0,200);return R.ok(new ExtractionDtos.Page<>(all.stream().skip((long)(page-1)*pageSize).limit(pageSize).map(s->ExtractionMapping.view(s,repo.receipt(a,graphId,s.suggestionId(),s.editVersion()).orElse(null),repo.pendingOperation(s.suggestionId(),s.editVersion()))).toList(),all.size(),page,pageSize));}
    @PatchMapping("/suggestions/{suggestionId}") @RequireWorkspaceRole("member")
    public R<ExtractionDtos.SuggestionView> edit(@RequestHeader(value="X-Workspace-Id",required=false)String scope,@PathVariable String graphId,@PathVariable String suggestionId,@RequestBody ExtractionDtos.EditRequest r){
        var a=actor(scope,"member");ExtractionCoordinator.operation(r.operationId());if(r.expectedVersion()==null)throw new ExtractionException(400,"EXPECTED_VERSION_REQUIRED");
        Suggestion old=repo.suggestion(a,graphId,suggestionId).orElseThrow(()->new ExtractionException(404,"NOT_FOUND"));Task task=visible(a,graphId,old.taskId());access.require(a,graphId,task.source().sourceRef(),Action.EDIT);
        return R.ok(repo.editOperation(graphId,suggestionId,r.operationId(),ExtractionCoordinator.hash(repo.json.write(r)),ExtractionDtos.SuggestionView.class,()->{
        if(old.editVersion()!=r.expectedVersion()||old.status()!=SuggestionStatus.OPEN)throw new ExtractionException(409,"SUGGESTION_VERSION_CONFLICT");
        RawSuggestion raw;StatementContent mapped=null;
        try{raw="IGNORED".equals(r.status())?old.content():ExtractionMapping.raw(r);
            if(!"IGNORED".equals(r.status())&&r.subjectId()!=null&&!r.subjectId().isBlank())mapped=new StatementContent(context.scope(a,graphId),task.ontology().revisionId(),new EntityId(r.subjectId()),raw.predicate(),raw.target()==null?raw.value():new StatementValue.EntityValue(new EntityId(r.targetEntityId())),raw.validity(),Set.of());
        }catch(RuntimeException e){throw new ExtractionException(422,"INVALID_SUGGESTION");}
        var validator=new SuggestionValidator();var report=mapped==null?validator.validateRaw(context.scope(a,graphId),task.ontology(),raw,task.source().text()):validator.validate(context.scope(a,graphId),task.ontology(),mapped,context.entities(a,graphId),task.source().text(),raw.quotes());
        var next=new Suggestion(suggestionId,old.taskId(),old.attemptId(),raw,mapped,report.violations(),old.editVersion()+1,"IGNORED".equals(r.status())?SuggestionStatus.IGNORED:SuggestionStatus.OPEN);
        if(!repo.edit(a,graphId,next,old.editVersion()))throw new ExtractionException(409,"SUGGESTION_VERSION_CONFLICT");return ExtractionMapping.view(next,null);
        }));
    }
    @PostMapping("/suggestions/{suggestionId}/submit") @RequireWorkspaceRole("member")
    public R<ExtractionDtos.SubmissionView> submit(@RequestHeader(value="X-Workspace-Id",required=false)String scope,@PathVariable String graphId,@PathVariable String suggestionId,@RequestBody ExtractionDtos.SubmitRequest r){var a=actor(scope,"member");if(r.expectedVersion()==null)throw new ExtractionException(400,"EXPECTED_VERSION_REQUIRED");var s=repo.suggestion(a,graphId,suggestionId).orElseThrow(()->new ExtractionException(404,"NOT_FOUND"));visible(a,graphId,s.taskId());var result=submissions.submit(a,graphId,new SubmitCommand(suggestionId,r.expectedVersion(),r.operationId()));return R.ok(new ExtractionDtos.SubmissionView(result.statementId(),result.revision()));}
    private Task visible(Actor a,String graph,String id){Task t=repo.find(a,graph,id).orElseThrow(()->new ExtractionException(404,"NOT_FOUND"));access.require(a,graph,t.source().sourceRef(),Action.READ);snapshotAvailable(t);return t;}
    private void snapshotAvailable(Task t){if(repo.jdbc.queryForObject("SELECT COUNT(*) FROM mate_semantic_snapshot_exclusion WHERE graph_id=? AND snapshot_id=?",Integer.class,t.graphId(),t.source().snapshotId())>0)throw new SemanticApiException(404,"SOURCE_UNAVAILABLE","Snapshot excluded");}
    private ExtractionDtos.TaskView view(Task t){return view(t,true);}
    private ExtractionDtos.TaskView view(Task t,boolean includeText){var m=repo.metadata(t.taskId());String error=(String)m.get("error_code");return new ExtractionDtos.TaskView(t.taskId(),t.taskId(),t.status().name(),t.version(),t.graphId(),t.source().snapshotId(),t.ontology().revisionId().value(),t.configuration().modelName(),((Number)m.get("attempts")).intValue(),error,error==null?null:"Generation failed; see error category and trace identifier",(String)m.get("explanation"),t.createdAt(),t.updatedAt(),t.source().sourceRef(),t.source().metadata().get("title"),includeText?t.source().text():null,new SourceChunker().split(t.source().text()).size(),t.status()==TaskStatus.SUCCEEDED?new SourceChunker().split(t.source().text()).size():((Number)m.get("completed_chunks")).intValue(),(String)m.get("trace_id"));}
    private static void page(int page,int size){if(page<1||size<1||size>100)throw new ExtractionException(400,"INVALID_PAGE");}
}

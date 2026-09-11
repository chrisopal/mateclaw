package vip.mate.semantic.authoring;

import static vip.mate.semantic.authoring.OntologyModelingDtos.*;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import vip.mate.common.result.R;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;

@RestController
@RequestMapping("/api/v1/semantic/modeling-tasks")
@ConditionalOnProperty(name="mateclaw.semantic.enabled",havingValue="true")
public class OntologyModelingController {
    private final OntologyModelingService service;
    public OntologyModelingController(OntologyModelingService service){this.service=service;}
    @org.springframework.beans.factory.annotation.Autowired
    private OntologyModelingSourceService sources;
    @GetMapping("/sources") @RequireWorkspaceRole("viewer")
    public R<java.util.Map<String,Object>> sources(@RequestHeader(value="X-Workspace-Id",required=false) String scope,
            @RequestParam Long agentId,@RequestParam(required=false) String knowledgeBaseId,
            @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize) {
        return R.ok(sources.page(scope,agentId,knowledgeBaseId,page,pageSize));
    }
    @PostMapping @RequireWorkspaceRole("member")
    public R<Task> create(@RequestHeader(value="X-Workspace-Id",required=false) String scope,@RequestBody CreateTask input){return R.ok(service.create(scope,input));}
    @GetMapping @RequireWorkspaceRole("viewer")
    public R<List<Task>> list(@RequestHeader(value="X-Workspace-Id",required=false) String scope,@RequestParam(required=false) String ontologyId){return R.ok(service.list(scope,ontologyId));}
    @GetMapping("/{id}") @RequireWorkspaceRole("viewer")
    public R<Task> get(@RequestHeader(value="X-Workspace-Id",required=false) String scope,@PathVariable String id){return R.ok(service.read(scope,id));}
    @PostMapping("/{id}/proposals") @RequireWorkspaceRole("member")
    public R<Task> submit(@RequestHeader(value="X-Workspace-Id",required=false) String scope,@PathVariable String id,@RequestBody SubmitProposal input){return R.ok(service.submit(scope,id,input));}
    @PostMapping("/{id}/proposals/{proposalId}/decision") @RequireWorkspaceRole("member")
    public R<Task> decide(@RequestHeader(value="X-Workspace-Id",required=false) String scope,@PathVariable String id,@PathVariable String proposalId,@RequestBody Decision input){return R.ok(service.decide(scope,id,proposalId,input));}
    @PatchMapping("/{id}/stage") @RequireWorkspaceRole("member")
    public R<Task> stage(@RequestHeader(value="X-Workspace-Id",required=false) String scope,@PathVariable String id,@RequestBody StageChange input){return R.ok(service.stage(scope,id,input));}
}

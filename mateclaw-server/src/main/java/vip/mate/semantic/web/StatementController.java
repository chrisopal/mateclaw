package vip.mate.semantic.web;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import vip.mate.common.result.R;
import vip.mate.semantic.statement.*;
import vip.mate.semantic.web.StatementDtos.*;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;

@RestController @RequestMapping("/api/v1/semantic/graphs/{graphId}")
@ConditionalOnProperty(name="mateclaw.semantic.enabled",havingValue="true")
public class StatementController {
    private final StatementApplicationService service;
    public StatementController(StatementApplicationService service){this.service=service;}
    @PostMapping("/statements") @RequireWorkspaceRole("member") public R<StatementView> propose(@RequestHeader(value="X-Workspace-Id",required=false)String scope,@PathVariable String graphId,@RequestBody ProposeRequest request){return R.ok(service.propose(scope,graphId,request));}
    @PostMapping("/statements/{statementId}/changes") @RequireWorkspaceRole("member") public R<ChangeView> change(@RequestHeader(value="X-Workspace-Id",required=false)String scope,@PathVariable String graphId,@PathVariable String statementId,@RequestBody ChangeRequest request){return R.ok(service.proposeChange(scope,graphId,statementId,request));}
    @GetMapping("/statements") @RequireWorkspaceRole("viewer") public R<Page<StatementView>> statements(@RequestHeader(value="X-Workspace-Id",required=false)String scope,@PathVariable String graphId,@RequestParam(defaultValue="trusted")String view,@RequestParam(required=false)String status,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int pageSize){return R.ok(service.statements(scope,graphId,view,status,page,pageSize));}
    @GetMapping("/changes") @RequireWorkspaceRole("admin") public R<Page<ChangeView>> changes(@RequestHeader(value="X-Workspace-Id",required=false)String scope,@PathVariable String graphId,@RequestParam(required=false)String status,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int pageSize){return R.ok(service.changes(scope,graphId,status,page,pageSize));}
    @GetMapping("/changes/{proposalId}") @RequireWorkspaceRole("admin") public R<ChangeView> change(@RequestHeader(value="X-Workspace-Id",required=false)String scope,@PathVariable String graphId,@PathVariable String proposalId){return R.ok(service.change(scope,graphId,proposalId));}
    @GetMapping("/conflicts") @RequireWorkspaceRole("admin") public R<Page<ConflictView>> conflicts(@RequestHeader(value="X-Workspace-Id",required=false)String scope,@PathVariable String graphId,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int pageSize){return R.ok(service.conflicts(scope,graphId,page,pageSize));}
}

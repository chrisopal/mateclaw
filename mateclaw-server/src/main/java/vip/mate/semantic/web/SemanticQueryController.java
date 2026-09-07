package vip.mate.semantic.web;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import vip.mate.common.result.R;
import vip.mate.semantic.query.*;
import vip.mate.semantic.query.SemanticQueryDtos.*;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;

@RestController @RequestMapping("/api/v1/semantic/graphs/{graphId}")
@ConditionalOnProperty(name="mateclaw.semantic.enabled",havingValue="true")
public class SemanticQueryController {
    private final SemanticQueryService service;public SemanticQueryController(SemanticQueryService service){this.service=service;}
    @PostMapping("/search") @RequireWorkspaceRole("viewer") public R<SearchResult> search(@RequestHeader(value="X-Workspace-Id",required=false)String scope,@PathVariable String graphId,@RequestBody SearchRequest request){return R.ok(service.search(scope,graphId,request));}
    @GetMapping("/neighbors") @RequireWorkspaceRole("viewer") public R<GraphResult> neighbors(@RequestHeader(value="X-Workspace-Id",required=false)String scope,@PathVariable String graphId,@RequestParam String entityId,@RequestParam(defaultValue="1")int depth,@RequestParam(defaultValue="100")int nodeLimit,@RequestParam(defaultValue="200")int edgeLimit){return R.ok(service.neighbors(scope,graphId,entityId,depth,nodeLimit,edgeLimit));}
    @GetMapping("/evidence/{evidenceId}") @RequireWorkspaceRole("viewer") public R<EvidenceResult> evidence(@RequestHeader(value="X-Workspace-Id",required=false)String scope,@PathVariable String graphId,@PathVariable String evidenceId){return R.ok(service.evidence(scope,graphId,evidenceId));}
    @GetMapping("/statements/{statementId}/revisions") @RequireWorkspaceRole("admin") public R<HistoryResult> history(@RequestHeader(value="X-Workspace-Id",required=false)String scope,@PathVariable String graphId,@PathVariable String statementId){return R.ok(service.history(scope,graphId,statementId));}
}

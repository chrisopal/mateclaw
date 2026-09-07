package vip.mate.semantic.web;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import vip.mate.common.result.R;
import vip.mate.semantic.source.SourceGovernanceService;
import vip.mate.semantic.statement.StatementReviewService;
import vip.mate.semantic.web.SourceDtos.GovernanceRequest;
import vip.mate.semantic.web.StatementDtos.*;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;

@RestController @RequestMapping("/api/v1/semantic/graphs/{graphId}")
@ConditionalOnProperty(name="mateclaw.semantic.enabled",havingValue="true")
public class ReviewController {
    private final StatementReviewService reviews;private final SourceGovernanceService sources;
    public ReviewController(StatementReviewService reviews,SourceGovernanceService sources){this.reviews=reviews;this.sources=sources;}
    @PostMapping("/statements/{statementId}/review") @RequireWorkspaceRole("admin") public R<StatementView> review(@RequestHeader(value="X-Workspace-Id",required=false)String scope,@PathVariable String graphId,@PathVariable String statementId,@RequestBody ReviewRequest request){return R.ok(reviews.review(scope,graphId,statementId,request));}
    @PostMapping("/changes/{proposalId}/review") @RequireWorkspaceRole("admin") public R<ChangeView> reviewChange(@RequestHeader(value="X-Workspace-Id",required=false)String scope,@PathVariable String graphId,@PathVariable String proposalId,@RequestBody ReviewRequest request){return R.ok(reviews.reviewChange(scope,graphId,proposalId,request));}
    @PostMapping("/conflicts/{conflictId}/resolve") @RequireWorkspaceRole("admin") public R<ConflictView> resolve(@RequestHeader(value="X-Workspace-Id",required=false)String scope,@PathVariable String graphId,@PathVariable String conflictId,@RequestBody ResolveRequest request){return R.ok(reviews.resolve(scope,graphId,conflictId,request));}
    @PostMapping("/sources/withdraw") @RequireWorkspaceRole("admin") public R<vip.mate.semantic.web.SourceDtos.GovernanceResult> withdraw(@RequestHeader(value="X-Workspace-Id",required=false)String scope,@PathVariable String graphId,@RequestBody GovernanceRequest request){return R.ok(sources.withdraw(scope,graphId,request));}
    @PostMapping("/snapshots/{snapshotId}/exclude") @RequireWorkspaceRole("admin") public R<vip.mate.semantic.web.SourceDtos.GovernanceResult> exclude(@RequestHeader(value="X-Workspace-Id",required=false)String scope,@PathVariable String graphId,@PathVariable String snapshotId,@RequestBody GovernanceRequest request){return R.ok(sources.exclude(scope,graphId,snapshotId,request));}
}

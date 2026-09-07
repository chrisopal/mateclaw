package vip.mate.semantic.web;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import vip.mate.common.result.R;
import vip.mate.semantic.governance.SemanticGovernanceService;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;

/** Graph-scoped governance history for workspace administrators. */
@RestController
@RequestMapping("/api/v1/semantic/graphs/{graphId}/governance")
@ConditionalOnProperty(name = "mateclaw.semantic.enabled", havingValue = "true")
public class GovernanceController {
    private final SemanticGovernanceService governance;

    public GovernanceController(SemanticGovernanceService governance) {
        this.governance = governance;
    }

    @GetMapping
    @RequireWorkspaceRole("admin")
    public R<SemanticGovernanceService.GovernancePage> read(
            @RequestHeader(value = "X-Workspace-Id", required = false) String scope,
            @org.springframework.web.bind.annotation.PathVariable String graphId,
            @RequestParam(required = false) String resourceKind,
            @RequestParam(required = false) String resourceId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int pageSize) {
        return R.ok(governance.read(scope, graphId, resourceKind, resourceId, page, pageSize));
    }
}

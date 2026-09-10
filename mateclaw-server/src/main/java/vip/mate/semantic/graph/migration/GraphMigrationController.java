package vip.mate.semantic.graph.migration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import vip.mate.common.result.R;
import vip.mate.semantic.graph.migration.GraphMigrationDtos.*;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;

/** HTTP boundary for staged OWL graph migrations. */
@RestController
@RequestMapping("/api/v1/semantic/graphs/{graphId}/migrations/owl")
@ConditionalOnProperty(name = "mateclaw.semantic.enabled", havingValue = "true")
public final class GraphMigrationController {
    private final GraphMigrationService service;

    public GraphMigrationController(GraphMigrationService service) { this.service = service; }

    @PostMapping("/prepare")
    @RequireWorkspaceRole("admin")
    public R<PlanView> prepare(@RequestHeader(value = "X-Workspace-Id", required = false) String scope,
            @PathVariable String graphId, @RequestBody PrepareRequest request) {
        return R.ok(service.prepare(scope, graphId, request));
    }

    @GetMapping("/{planId}")
    @RequireWorkspaceRole("viewer")
    public R<PlanView> get(@RequestHeader(value = "X-Workspace-Id", required = false) String scope,
            @PathVariable String graphId, @PathVariable String planId) {
        return R.ok(service.get(scope, graphId, planId));
    }

    @GetMapping("/targets")
    @RequireWorkspaceRole("viewer")
    public R<java.util.List<TargetRevision>> targets(@RequestHeader(value = "X-Workspace-Id", required = false) String scope,
            @PathVariable String graphId) {
        return R.ok(service.targets(scope, graphId));
    }

    @PostMapping("/{planId}/approve")
    @RequireWorkspaceRole("admin")
    public R<PlanView> approve(@RequestHeader(value = "X-Workspace-Id", required = false) String scope,
            @PathVariable String graphId, @PathVariable String planId, @RequestBody ApproveRequest request) {
        return R.ok(service.approve(scope, graphId, planId, request));
    }

    @PostMapping("/{planId}/execute")
    @RequireWorkspaceRole("admin")
    public R<PlanView> execute(@RequestHeader(value = "X-Workspace-Id", required = false) String scope,
            @PathVariable String graphId, @PathVariable String planId, @RequestBody ExecuteRequest request) {
        return R.ok(service.execute(scope, graphId, planId, request));
    }

    @PostMapping("/{planId}/rollback")
    @RequireWorkspaceRole("admin")
    public R<PlanView> rollback(@RequestHeader(value = "X-Workspace-Id", required = false) String scope,
            @PathVariable String graphId, @PathVariable String planId, @RequestBody RollbackRequest request) {
        return R.ok(service.rollback(scope, graphId, planId, request));
    }
}

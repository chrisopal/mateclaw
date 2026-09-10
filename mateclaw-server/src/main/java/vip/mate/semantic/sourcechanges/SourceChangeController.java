package vip.mate.semantic.sourcechanges;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import vip.mate.common.result.R;
import vip.mate.semantic.sourcechanges.SourceChangeDtos.*;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;

@RestController
@RequestMapping("/api/v1/semantic/graphs/{graphId}/source-changes")
@ConditionalOnProperty(name = "mateclaw.semantic.enabled", havingValue = "true")
public class SourceChangeController {
    private final SourceChangeService service;

    public SourceChangeController(SourceChangeService service) { this.service = service; }

    @PostMapping("/scan")
    @RequireWorkspaceRole("member")
    public R<ScanResult> scan(@RequestHeader(value = "X-Workspace-Id", required = false) String scope,
            @PathVariable String graphId, @RequestBody ScanRequest request) {
        return R.ok(service.scan(scope, graphId, request));
    }

    @GetMapping
    @RequireWorkspaceRole("viewer")
    public R<List<ReviewItem>> pending(@RequestHeader(value = "X-Workspace-Id", required = false) String scope,
            @PathVariable String graphId, @RequestParam(required = false) Integer limit) {
        return R.ok(service.pending(scope, graphId, limit));
    }

    @GetMapping("/{changeId}/items")
    @RequireWorkspaceRole("viewer")
    public R<List<ReviewItem>> items(@RequestHeader(value = "X-Workspace-Id", required = false) String scope,
            @PathVariable String graphId, @PathVariable String changeId) {
        return R.ok(service.items(scope, graphId, changeId));
    }

    @PostMapping("/items/{itemId}/decision")
    @RequireWorkspaceRole("admin")
    public R<ReviewItem> decide(@RequestHeader(value = "X-Workspace-Id", required = false) String scope,
            @PathVariable String graphId, @PathVariable String itemId, @RequestBody DecideRequest request) {
        return R.ok(service.decide(scope, graphId, itemId, request));
    }
}

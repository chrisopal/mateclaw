package vip.mate.semantic.web;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import vip.mate.common.result.R;
import vip.mate.semantic.graph.GraphApplicationService;
import vip.mate.semantic.web.GraphDtos.*;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;

import java.util.List;

@RestController
@RequestMapping("/api/v1/semantic")
@ConditionalOnProperty(name = "mateclaw.semantic.enabled", havingValue = "true")
public class GraphController {
    private final GraphApplicationService service;
    public GraphController(GraphApplicationService service) { this.service = service; }

    @GetMapping("/graphs/{graphId}")
    @RequireWorkspaceRole("viewer")
    public R<GraphDetail> detail(@RequestHeader(value="X-Workspace-Id", required=false) String scope, @PathVariable String graphId) {
        return R.ok(service.detail(scope, graphId));
    }

    @GetMapping("/knowledge-bases/{kbId}/binding")
    @RequireWorkspaceRole("viewer")
    public R<Binding> get(@RequestHeader(value="X-Workspace-Id", required=false) String scope, @PathVariable String kbId) {
        return R.ok(service.get(scope, kbId));
    }
    @PutMapping("/knowledge-bases/{kbId}/binding")
    @RequireWorkspaceRole("admin")
    public R<Binding> bind(@RequestHeader(value="X-Workspace-Id", required=false) String scope, @PathVariable String kbId, @RequestBody BindRequest request) {
        return R.ok(service.bind(scope, kbId, request));
    }
    @GetMapping("/ontologies/{ontologyId}/bindings")
    @RequireWorkspaceRole("viewer")
    public R<List<Binding>> bindings(@RequestHeader(value="X-Workspace-Id", required=false) String scope, @PathVariable String ontologyId) {
        return R.ok(service.bindings(scope, ontologyId));
    }
    @GetMapping("/graphs/{graphId}/entities")
    @RequireWorkspaceRole("viewer")
    public R<EntityPage> entities(@RequestHeader(value="X-Workspace-Id", required=false) String scope, @PathVariable String graphId) {
        return R.ok(service.entities(scope, graphId));
    }
    @PostMapping("/graphs/{graphId}/entities")
    @RequireWorkspaceRole("member")
    public R<EntityView> createEntity(@RequestHeader(value="X-Workspace-Id", required=false) String scope, @PathVariable String graphId, @RequestBody CreateEntity request) {
        return R.ok(service.createEntity(scope, graphId, request));
    }
}

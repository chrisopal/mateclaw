package vip.mate.presales;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import vip.mate.common.result.R;

@RestController
@RequestMapping("/api/v1/presales")
@ConditionalOnProperty(name = "mateclaw.presales.enabled", havingValue = "true")
public class PresalesGenerationController {
    private final PresalesGenerationService generation;

    public PresalesGenerationController(PresalesGenerationService generation) {
        this.generation = generation;
    }

    @GetMapping("/employees")
    public R<List<PresalesDtos.Employee>> employees(
            @RequestHeader(value = "X-Workspace-Id", required = false) String scope) {
        return R.ok(generation.employees(scope));
    }

    @PostMapping("/projects/{id}/generate")
    public R<ObjectNode> generate(
            @RequestHeader(value = "X-Workspace-Id", required = false) String scope,
            @PathVariable String id,
            @RequestBody PresalesDtos.Generate input) {
        return R.ok(generation.generate(scope, id, input));
    }

    @PostMapping("/projects/{id}/tasks/{taskId}/cancel")
    public R<ObjectNode> cancel(
            @RequestHeader(value = "X-Workspace-Id", required = false) String scope,
            @PathVariable String id,
            @PathVariable String taskId,
            @RequestBody(required = false) PresalesDtos.Cancel input) {
        return R.ok(generation.cancel(scope, id, taskId, input));
    }
}

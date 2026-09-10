package vip.mate.semantic.object;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vip.mate.common.result.R;
import vip.mate.semantic.object.CompleteObjectValidationDtos.ValidationRequest;
import vip.mate.semantic.object.CompleteObjectValidationDtos.ValidationView;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;

/** Explicit complete-object gate; it never persists a completion result or fact. */
@RestController
@RequestMapping("/api/v1/semantic/graphs/{graphId}/objects")
@ConditionalOnProperty(name = "mateclaw.semantic.enabled", havingValue = "true")
public class CompleteObjectValidationController {
    private final CompleteObjectValidationService service;

    public CompleteObjectValidationController(CompleteObjectValidationService service) {
        this.service = service;
    }

    @PostMapping("/complete-validation")
    @RequireWorkspaceRole("member")
    public R<ValidationView> validate(
            @RequestHeader(value = "X-Workspace-Id", required = false) String scope,
            @PathVariable String graphId,
            @RequestBody ValidationRequest request) {
        return R.ok(service.validate(scope, graphId, request));
    }
}

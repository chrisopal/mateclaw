package vip.mate.semantic.web;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vip.mate.common.result.R;
import vip.mate.semantic.authoring.OntologyBuilderProvisioning;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;

/** Entry point for the workspace ontology authoring employee preset. */
@RestController
@RequestMapping("/api/v1/semantic/ontology-builder")
@ConditionalOnProperty(name = "mateclaw.semantic.enabled", havingValue = "true")
public class OntologyBuilderController {
    private final OntologyBuilderProvisioning provisioning;
    private final vip.mate.semantic.security.SemanticAccessService access;

    public OntologyBuilderController(OntologyBuilderProvisioning provisioning,
            vip.mate.semantic.security.SemanticAccessService access) {
        this.provisioning = provisioning;
        this.access = access;
    }

    @PostMapping("/ensure")
    @RequireWorkspaceRole("member")
    public R<OntologyBuilderProvisioning.EnsureResult> ensure(
            @RequestHeader(value = "X-Workspace-Id", required = false) String workspaceId) {
        access.require(workspaceId, "member");
        return R.ok(provisioning.ensure(workspaceId));
    }
}

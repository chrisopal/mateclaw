package vip.mate.semantic.web;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import vip.mate.common.result.R;
import vip.mate.semantic.ontology.OntologyImpactService;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;

@RestController
@RequestMapping("/api/v1/semantic/ontologies/{ontologyId}")
@ConditionalOnProperty(name = "mateclaw.semantic.enabled", havingValue = "true")
public class OntologyImpactController {
    private final OntologyImpactService service;

    public OntologyImpactController(OntologyImpactService service) {
        this.service = service;
    }

    @GetMapping("/usage")
    @RequireWorkspaceRole("viewer")
    public R<OntologyImpactDtos.UsagePage> usage(
            @RequestHeader(value = "X-Workspace-Id", required = false) String scope,
            @PathVariable String ontologyId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return R.ok(service.usage(scope, ontologyId, page, pageSize));
    }

    @PostMapping("/impact")
    @RequireWorkspaceRole("admin")
    public R<OntologyImpactDtos.Report> impact(
            @RequestHeader(value = "X-Workspace-Id", required = false) String scope,
            @PathVariable String ontologyId,
            @RequestBody OntologyImpactDtos.Request request) {
        return R.ok(service.analyze(scope, ontologyId, request));
    }
}

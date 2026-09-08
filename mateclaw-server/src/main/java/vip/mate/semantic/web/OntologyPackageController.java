package vip.mate.semantic.web;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import vip.mate.common.result.R;
import vip.mate.semantic.ontology.OntologyPackageService;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;

import java.io.IOException;

@RestController
@RequestMapping("/api/v1/semantic")
@ConditionalOnProperty(name = "mateclaw.semantic.enabled", havingValue = "true")
public class OntologyPackageController {
    private final OntologyPackageService service;

    public OntologyPackageController(OntologyPackageService service) {
        this.service = service;
    }

    @GetMapping("/ontologies/{id}/revisions/{revisionId}/package")
    @RequireWorkspaceRole("viewer")
    public R<OntologyPackageDtos.Package> export(
            @RequestHeader(value = "X-Workspace-Id", required = false) String scope,
            @PathVariable String id,
            @PathVariable String revisionId) {
        return R.ok(service.export(scope, id, revisionId));
    }

    @PostMapping(value = "/ontology-packages/preview", consumes = "application/json")
    @RequireWorkspaceRole("member")
    public R<OntologyPackageDtos.Preview> preview(
            @RequestHeader(value = "X-Workspace-Id", required = false) String scope,
            HttpServletRequest request)
            throws IOException {
        return R.ok(service.preview(scope, body(request)));
    }

    @PostMapping(value = "/ontology-packages/import", consumes = "application/json")
    @RequireWorkspaceRole("member")
    public R<OntologyPackageDtos.ImportResult> create(
            @RequestHeader(value = "X-Workspace-Id", required = false) String scope,
            HttpServletRequest request)
            throws IOException {
        return R.ok(service.importPackage(scope, body(request)));
    }

    @GetMapping("/ontology-package-imports/{operationId}")
    @RequireWorkspaceRole("member")
    public R<OntologyPackageDtos.ImportResult> operation(
            @RequestHeader(value = "X-Workspace-Id", required = false) String scope,
            @PathVariable String operationId) {
        return R.ok(service.operation(scope, operationId));
    }

    private byte[] body(HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > OntologyPackageService.MAX_BYTES)
            throw new SemanticApiException(413, "PACKAGE_TOO_LARGE", "Package exceeds 1 MiB");
        byte[] body = request.getInputStream().readNBytes(OntologyPackageService.MAX_BYTES + 1);
        if (body.length > OntologyPackageService.MAX_BYTES)
            throw new SemanticApiException(413, "PACKAGE_TOO_LARGE", "Package exceeds 1 MiB");
        return body;
    }
}

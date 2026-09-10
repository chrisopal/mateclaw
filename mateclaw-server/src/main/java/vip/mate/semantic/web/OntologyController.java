package vip.mate.semantic.web;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import vip.mate.common.result.R;
import vip.mate.semantic.ontology.OntologyApplicationService;
import vip.mate.semantic.web.OntologyDtos.*;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;

import java.util.List;

@RestController
@RequestMapping("/api/v1/semantic")
@ConditionalOnProperty(name = "mateclaw.semantic.enabled", havingValue = "true")
public class OntologyController {
    private final OntologyApplicationService service;

    public OntologyController(OntologyApplicationService service) {
        this.service = service;
    }

    @GetMapping("/ontologies")
    @RequireWorkspaceRole("viewer")
    public R<Page> list(
            @RequestHeader(value = "X-Workspace-Id", required = false) String scope,
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return R.ok(service.list(scope, q, page, pageSize));
    }

    @PostMapping("/ontologies")
    @RequireWorkspaceRole("member")
    public R<OntologyView> create(
            @RequestHeader(value = "X-Workspace-Id", required = false) String scope,
            @RequestBody Metadata body) {
        return R.ok(service.create(scope, body));
    }

    @GetMapping("/ontologies/{id}")
    @RequireWorkspaceRole("viewer")
    public R<OntologyView> get(
            @RequestHeader(value = "X-Workspace-Id", required = false) String scope,
            @PathVariable String id) {
        return R.ok(service.get(scope, id));
    }

    @PostMapping("/ontologies/{id}/draft")
    @RequireWorkspaceRole("member")
    public R<DraftView> createDraft(
            @RequestHeader(value = "X-Workspace-Id", required = false) String scope,
            @PathVariable String id,
            @RequestBody CreateDraft body) {
        return R.ok(service.createDraft(scope, id, body));
    }

    @GetMapping("/ontologies/{id}/draft")
    @RequireWorkspaceRole("viewer")
    public R<DraftView> draft(
            @RequestHeader(value = "X-Workspace-Id", required = false) String scope,
            @PathVariable String id) {
        return R.ok(service.getDraft(scope, id));
    }

    @PutMapping("/ontologies/{id}/draft")
    @RequireWorkspaceRole("member")
    public R<DraftView> saveDraft(
            @RequestHeader(value = "X-Workspace-Id", required = false) String scope,
            @PathVariable String id,
            @RequestBody SaveDraft body) {
        return R.ok(service.saveDraft(scope, id, body));
    }

    @PatchMapping("/ontologies/{id}/draft/axioms")
    @RequireWorkspaceRole("member")
    public R<DraftView> edit(@RequestHeader(value="X-Workspace-Id", required=false) String scope,
                             @PathVariable String id, @RequestBody EditDraft body) {
        return R.ok(service.editDraft(scope, id, body));
    }

    @GetMapping("/ontologies/{id}/draft/document")
    @RequireWorkspaceRole("viewer")
    public org.springframework.http.ResponseEntity<byte[]> draftDocument(
            @RequestHeader(value="X-Workspace-Id", required=false) String scope,
            @PathVariable String id, @RequestParam Long expectedDraftVersion,
            @RequestParam(defaultValue="RDF_XML") vip.mate.semantic.core.ontology.OntologyDocumentSyntax syntax) {
        return org.springframework.http.ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=ontology." + (syntax == vip.mate.semantic.core.ontology.OntologyDocumentSyntax.RDF_XML ? "rdf" : "ofn"))
                .contentType(org.springframework.http.MediaType.parseMediaType(syntax == vip.mate.semantic.core.ontology.OntologyDocumentSyntax.RDF_XML ? "application/rdf+xml" : "text/plain;charset=UTF-8"))
                .body(service.exportDraftDocument(scope, id, expectedDraftVersion, syntax));
    }

    @GetMapping("/ontologies/{id}/revisions/{revisionId}/document")
    @RequireWorkspaceRole("viewer")
    public org.springframework.http.ResponseEntity<byte[]> document(
            @RequestHeader(value="X-Workspace-Id", required=false) String scope,
            @PathVariable String id, @PathVariable String revisionId,
            @RequestParam(defaultValue="RDF_XML") vip.mate.semantic.core.ontology.OntologyDocumentSyntax syntax) {
        return org.springframework.http.ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=ontology." + (syntax == vip.mate.semantic.core.ontology.OntologyDocumentSyntax.RDF_XML ? "rdf" : "ofn"))
                .contentType(org.springframework.http.MediaType.parseMediaType(syntax == vip.mate.semantic.core.ontology.OntologyDocumentSyntax.RDF_XML ? "application/rdf+xml" : "text/plain;charset=UTF-8"))
                .body(service.exportDocument(scope, id, revisionId, syntax));
    }

    @DeleteMapping("/ontologies/{id}/draft")
    @RequireWorkspaceRole("member")
    public R<Void> discard(
            @RequestHeader(value = "X-Workspace-Id", required = false) String scope,
            @PathVariable String id,
            @RequestParam Long expectedDraftVersion) {
        service.discard(scope, id, expectedDraftVersion);
        return R.ok();
    }

    @PostMapping("/ontologies/{id}/draft/validate")
    @RequireWorkspaceRole("member")
    public R<ValidationView> validate(
            @RequestHeader(value = "X-Workspace-Id", required = false) String scope,
            @PathVariable String id,
            @RequestBody ValidateDraft body) {
        return R.ok(service.validate(scope, id, body));
    }

    @PostMapping("/ontologies/{id}/draft/publish")
    @RequireWorkspaceRole("admin")
    public R<RevisionView> publish(
            @RequestHeader(value = "X-Workspace-Id", required = false) String scope,
            @PathVariable String id,
            @RequestBody PublishDraft body) {
        return R.ok(service.publish(scope, id, body));
    }

    @GetMapping("/ontologies/{id}/revisions")
    @RequireWorkspaceRole("viewer")
    public R<List<RevisionView>> revisions(
            @RequestHeader(value = "X-Workspace-Id", required = false) String scope,
            @PathVariable String id) {
        return R.ok(service.revisions(scope, id));
    }

    @GetMapping("/ontologies/{id}/revisions/{revisionId}")
    @RequireWorkspaceRole("viewer")
    public R<RevisionView> revision(
            @RequestHeader(value = "X-Workspace-Id", required = false) String scope,
            @PathVariable String id,
            @PathVariable String revisionId) {
        return R.ok(service.revision(scope, id, revisionId));
    }

    @GetMapping("/ontologies/{id}/diff")
    @RequireWorkspaceRole("viewer")
    public R<Diff> diff(
            @RequestHeader(value = "X-Workspace-Id", required = false) String scope,
            @PathVariable String id,
            @RequestParam(required = false) String from,
            @RequestParam String to) {
        return R.ok(service.diff(scope, id, from, to));
    }

    @PatchMapping("/ontologies/{id}/revisions/{revisionId}/availability")
    @RequireWorkspaceRole("admin")
    public R<RevisionView> availability(
            @RequestHeader(value = "X-Workspace-Id", required = false) String scope,
            @PathVariable String id,
            @PathVariable String revisionId,
            @RequestBody Availability body) {
        return R.ok(service.availability(scope, id, revisionId, body));
    }

    @GetMapping("/operations/{operationId}")
    @RequireWorkspaceRole("admin")
    public R<Operation> operation(
            @RequestHeader(value = "X-Workspace-Id", required = false) String scope,
            @PathVariable String operationId) {
        return R.ok(service.operation(scope, operationId));
    }
}

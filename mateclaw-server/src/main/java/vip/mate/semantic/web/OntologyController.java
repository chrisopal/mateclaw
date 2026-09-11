package vip.mate.semantic.web;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import vip.mate.common.result.R;
import vip.mate.semantic.ontology.OntologyApplicationService;
import vip.mate.semantic.ontology.BusinessPolicyService;
import vip.mate.semantic.reasoning.DraftReasoningService;
import vip.mate.semantic.web.OntologyDtos.*;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;

import java.util.List;

@RestController
@RequestMapping("/api/v1/semantic")
@ConditionalOnProperty(name = "mateclaw.semantic.enabled", havingValue = "true")
public class OntologyController {
    private final OntologyApplicationService service;
    private final BusinessPolicyService businessPolicies;
    private final DraftReasoningService draftReasoning;

    public OntologyController(OntologyApplicationService service, BusinessPolicyService businessPolicies,
            DraftReasoningService draftReasoning) {
        this.service = service;
        this.businessPolicies = businessPolicies;
        this.draftReasoning = draftReasoning;
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

    @GetMapping("/ontologies/{id}/draft/projection")
    @RequireWorkspaceRole("viewer")
    public R<ProjectionView> draftProjection(
            @RequestHeader(value = "X-Workspace-Id", required = false) String scope,
            @PathVariable String id,
            @RequestParam Long expectedDraftVersion,
            @RequestParam(defaultValue = "500") int limit) {
        return R.ok(service.draftProjection(scope, id, expectedDraftVersion, limit));
    }

    @GetMapping("/ontologies/{id}/revisions/{revisionId}/projection")
    @RequireWorkspaceRole("viewer")
    public R<ProjectionView> revisionProjection(
            @RequestHeader(value = "X-Workspace-Id", required = false) String scope,
            @PathVariable String id,
            @PathVariable String revisionId,
            @RequestParam(defaultValue = "500") int limit) {
        return R.ok(service.revisionProjection(scope, id, revisionId, limit));
    }

    @PutMapping("/ontologies/{id}/draft")
    @RequireWorkspaceRole("member")
    public R<DraftView> saveDraft(
            @RequestHeader(value = "X-Workspace-Id", required = false) String scope,
            @PathVariable String id,
            @RequestBody SaveDraft body) {
        return R.ok(service.saveDraft(scope, id, body));
    }

    @PutMapping("/ontologies/{id}/draft/business-policy")
    @RequireWorkspaceRole("member")
    public R<DraftView> saveBusinessPolicy(
            @RequestHeader(value = "X-Workspace-Id", required = false) String scope,
            @PathVariable String id,
            @RequestBody SaveBusinessPolicy body) {
        return R.ok(businessPolicies.save(scope, id, body));
    }

    @PostMapping("/ontologies/{id}/draft/check-sample")
    @RequireWorkspaceRole("member")
    public R<BusinessPolicyCheckView> checkBusinessPolicySample(
            @RequestHeader(value = "X-Workspace-Id", required = false) String scope,
            @PathVariable String id,
            @RequestBody CheckBusinessPolicySample body) {
        return R.ok(businessPolicies.checkSample(scope, id, body));
    }

    @PatchMapping("/ontologies/{id}/draft/axioms")
    @RequireWorkspaceRole("member")
    public R<DraftView> edit(@RequestHeader(value="X-Workspace-Id", required=false) String scope,
                             @PathVariable String id, @RequestBody EditDraft body) {
        return R.ok(service.editDraft(scope, id, body));
    }

    @PostMapping("/ontologies/{id}/draft/model-edits")
    @RequireWorkspaceRole("member")
    public R<DraftView> modelEdits(
            @RequestHeader(value = "X-Workspace-Id", required = false) String scope,
            @PathVariable String id,
            @RequestBody ModelEditRequest body) {
        return R.ok(service.editModelDraft(scope, id, body));
    }

    @PostMapping("/ontologies/{id}/draft/model-commands")
    @RequireWorkspaceRole("member")
    public R<ModelCommandResult> modelCommands(
            @RequestHeader(value = "X-Workspace-Id", required = false) String scope,
            @PathVariable String id, @RequestBody ModelEditRequest body) {
        return R.ok(service.applyModelCommands(scope, id, body));
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

    @PostMapping("/ontologies/{id}/draft/reason")
    @RequireWorkspaceRole("member")
    public R<DraftReasoningView> reasonDraft(
            @RequestHeader(value = "X-Workspace-Id", required = false) String scope,
            @PathVariable String id,
            @RequestBody ReasonDraft body) {
        return R.ok(draftReasoning.reason(scope, id, body));
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

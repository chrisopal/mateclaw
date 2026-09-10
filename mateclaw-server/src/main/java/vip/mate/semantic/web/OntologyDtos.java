package vip.mate.semantic.web;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;


import java.time.Instant;
import java.util.List;
import vip.mate.semantic.core.ontology.OntologyDisplayProjection;

/** Public HTTP shapes, deliberately independent of core Optional/identity types. */
public final class OntologyDtos {
    private OntologyDtos() {}

    public record DocumentInput(
            String modelSchema,
            vip.mate.semantic.core.ontology.OntologyDocumentSyntax syntax,
            String documentText,
            List<vip.mate.semantic.core.ontology.LockedImport> imports,
            vip.mate.semantic.core.policy.BusinessPolicySet policy) {}

    public record DocumentView(
            DocumentInput source,
            String ontologyIri,
            String versionIri,
            String documentDigest,
            String importLockDigest,
            List<vip.mate.semantic.core.ontology.OntologyAxiomDescriptor> axioms) {}

    public record Metadata(String name, String description) {}

    public record CreateDraft(String baseRevisionId) {}

    public record SaveDraft(
            Long expectedDraftVersion, String name, String description, DocumentInput document, String operationId) {}

    public record AxiomEdit(String kind, String axiomId, String functionalSyntax) {}
    public record EditDraft(Long expectedDraftVersion, List<AxiomEdit> changes, String operationId) {}

    /** Business authoring commands translated to standard OWL by the server. */
    public record ModelEditRequest(
            Long expectedDraftVersion, String operationId, List<ModelEdit> changes) {}

    public record ModelEdit(
            String kind,
            String termKind,
            String targetId,
            String name,
            String domainId,
            String rangeId,
            String field,
            String value,
            String language,
            String operator,
            String propertyId,
            String fillerId,
            Integer cardinality,
            String originalAxiomId,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            String clientId) {
        public ModelEdit(String kind, String termKind, String targetId, String name, String domainId,
                String rangeId, String field, String value, String language, String operator,
                String propertyId, String fillerId, Integer cardinality, String originalAxiomId) {
            this(kind, termKind, targetId, name, domainId, rangeId, field, value, language, operator,
                    propertyId, fillerId, cardinality, originalAxiomId, null);
        }
    }

    public record ModelItemResult(String clientId, String targetId, List<String> axiomIds) {}
    public record ModelCommandResult(DraftView draft, List<ModelItemResult> items) {}

    public record ValidateDraft(Long expectedDraftVersion) {}

    public record PublishDraft(Long expectedDraftVersion, String operationId, String note) {}

    public record Availability(Boolean availableForNewBindings) {}

    public record OntologyView(
            String id,
            String workspaceId,
            String name,
            String description,
            Integer latestVersion,
            String latestRevisionId,
            boolean hasDraft,
            @JsonFormat(shape = JsonFormat.Shape.STRING) Instant updatedAt) {}

    public record Page(
            List<OntologyView> items,
            @JsonSerialize(using = SemanticCounterSerializer.class) long total,
            int page,
            int pageSize) {}

    public record DraftView(
            String id,
            String ontologyId,
            String baseRevisionId,
            int version,
            @JsonSerialize(using = SemanticCounterSerializer.class) long draftVersion,
            String name,
            String description,
            DocumentView document) {}

    public record RevisionView(
            String id,
            String ontologyId,
            int version,
            String name,
            String description,
            DocumentView document,
            boolean availableForNewBindings,
            @JsonFormat(shape = JsonFormat.Shape.STRING) Instant publishedAt,
            String publishedBy,
            String publicationNote,
            String baseRevisionId) {}

    public record Violation(String code, String path, String message, String severity) {}

    public record ValidationView(
            @JsonSerialize(using = SemanticCounterSerializer.class) long draftVersion,
            boolean valid,
            List<Violation> violations,
            String profile,
            String reasoningStatus) {}

    public record Snapshot(
            String ontologyId,
            String revisionId,
            @JsonSerialize(using = SemanticCounterSerializer.class) Long draftVersion,
            String documentDigest,
            String importLockDigest) {}

    public record ProjectionView(Snapshot snapshot, OntologyDisplayProjection projection) {}

    public record Change(String kind, String category, String key, Object before, Object after) {}

    public record TermChange(
            String kind, String key, String definitionChangeClass, List<String> reasons) {}

    public record Diff(
            String fromRevisionId,
            String toRevisionId,
            List<Change> changes,
            String definitionChangeClass,
            List<TermChange> termChanges) {
        public Diff(String from, String to, List<Change> changes) {
            this(from, to, changes, "ANNOTATION", List.of());
        }
    }

    public record Operation(
            String operationId, String kind, String resourceId, RevisionView result) {}

    public record Status(boolean enabled) {}

}

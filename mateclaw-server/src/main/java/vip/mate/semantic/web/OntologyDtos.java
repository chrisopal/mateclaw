package vip.mate.semantic.web;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import vip.mate.semantic.core.ontology.Multiplicity;
import vip.mate.semantic.core.ontology.ValueType;

import java.time.Instant;
import java.util.List;

/** Public HTTP shapes, deliberately independent of core Optional/identity types. */
public final class OntologyDtos {
    private OntologyDtos() {}

    public record Type(
            String key,
            String label,
            String description,
            List<String> aliases,
            boolean deprecated) {
        public Type {
            aliases = normalizeAliases(aliases);
        }

        public Type(String key, String label, String description) {
            this(key, label, description, List.of(), false);
        }
    }

    public record Constraints(List<String> allowedValues, String minimum, String maximum) {}

    public record Property(
            String key,
            String label,
            String description,
            String ownerTypeKey,
            ValueType valueType,
            Multiplicity multiplicity,
            String fixedUnit,
            List<String> aliases,
            boolean deprecated,
            Constraints constraints) {
        public Property {
            aliases = normalizeAliases(aliases);
        }

        public Property(
                String key,
                String label,
                String description,
                String ownerTypeKey,
                ValueType valueType,
                Multiplicity multiplicity,
                String fixedUnit) {
            this(
                    key,
                    label,
                    description,
                    ownerTypeKey,
                    valueType,
                    multiplicity,
                    fixedUnit,
                    List.of(),
                    false,
                    null);
        }
    }

    public record Relation(
            String key,
            String label,
            String description,
            String sourceTypeKey,
            String targetTypeKey,
            Multiplicity multiplicity,
            List<String> aliases,
            boolean deprecated) {
        public Relation {
            aliases = normalizeAliases(aliases);
        }

        public Relation(
                String key,
                String label,
                String description,
                String sourceTypeKey,
                String targetTypeKey,
                Multiplicity multiplicity) {
            this(
                    key,
                    label,
                    description,
                    sourceTypeKey,
                    targetTypeKey,
                    multiplicity,
                    List.of(),
                    false);
        }
    }

    @JsonDeserialize(using = vip.mate.semantic.ontology.OntologyDefinitionCodec.class)
    public record Definition(
            List<Type> types,
            List<Property> properties,
            List<Relation> relations,
            Integer definitionFormatVersion) {
        public Definition {
            definitionFormatVersion = definitionFormatVersion == null ? 1 : definitionFormatVersion;
        }

        public Definition(List<Type> types, List<Property> properties, List<Relation> relations) {
            this(types, properties, relations, 1);
        }
    }

    public record Metadata(String name, String description) {}

    public record CreateDraft(String baseRevisionId) {}

    public record SaveDraft(
            Long expectedDraftVersion, String name, String description, Definition definition) {}

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
            Definition definition) {}

    public record RevisionView(
            String id,
            String ontologyId,
            int version,
            String name,
            String description,
            Definition definition,
            boolean availableForNewBindings,
            @JsonFormat(shape = JsonFormat.Shape.STRING) Instant publishedAt,
            String publishedBy,
            String publicationNote,
            String baseRevisionId) {}

    public record Violation(String code, String path, String message, String severity) {}

    public record ValidationView(
            @JsonSerialize(using = SemanticCounterSerializer.class) long draftVersion,
            boolean valid,
            List<Violation> violations) {}

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

    private static List<String> normalizeAliases(List<String> values) {
        return values == null
                ? List.of()
                : values.stream()
                        .map(java.util.Objects::requireNonNull)
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .distinct()
                        .toList();
    }
}

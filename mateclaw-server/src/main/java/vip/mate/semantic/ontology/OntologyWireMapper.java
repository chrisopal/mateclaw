package vip.mate.semantic.ontology;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Component;

import vip.mate.semantic.core.ontology.*;
import vip.mate.semantic.web.OntologyDtos.*;
import vip.mate.semantic.web.SemanticApiException;

import java.util.*;

@Component
public class OntologyWireMapper {
    private final ObjectMapper json;
    private final OntologyValidator validator = new OntologyValidator();

    public OntologyWireMapper(ObjectMapper json) {
        this.json = json;
    }

    public String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot encode semantic state", e);
        }
    }

    public <T> T decode(String value, Class<T> type) {
        try {
            return json.readValue(value, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot decode persisted semantic state", e);
        }
    }

    public Definition empty() {
        return new Definition(List.of(), List.of(), List.of());
    }

    public void metadata(String name, String description) {
        List<Violation> errors = new ArrayList<>();
        text(name, "name", 128, true, errors);
        text(description, "description", 1000, false, errors);
        reject(errors);
    }

    public void text(String value, String path, int max, boolean nonblank, List<Violation> errors) {
        if (value == null || (nonblank && value.isBlank()))
            errors.add(new Violation("REQUIRED", path, "Value required", "ERROR"));
        else if (value.codePointCount(0, value.length()) > max)
            errors.add(new Violation("FIELD_TOO_LONG", path, "Maximum length exceeded", "ERROR"));
    }

    public void structural(Definition d) {
        List<Violation> all = violations(d);
        // Editors may persist an empty model and incomplete references. Publication always runs the
        // full validator.
        reject(
                all.stream()
                        .filter(
                                v ->
                                        !v.code().equals("EMPTY_TYPES")
                                                && !v.code().equals("UNKNOWN_TYPE")
                                                && !(v.code().equals("REQUIRED")
                                                        && v.path().endsWith("TypeKey")))
                        .toList());
    }

    public List<Violation> violations(Definition d) {
        if (d == null
                || d.types() == null
                || d.properties() == null
                || d.relations() == null
                || d.types().stream().anyMatch(Objects::isNull)
                || d.properties().stream().anyMatch(Objects::isNull)
                || d.relations().stream().anyMatch(Objects::isNull)) {
            throw new SemanticApiException(
                    422,
                    "INVALID_DEFINITION",
                    "Definition collections and entries are required",
                    List.of(
                            new Violation(
                                    "REQUIRED",
                                    "definition",
                                    "Definition collections and entries are required",
                                    "ERROR")));
        }
        var core =
                new OntologyDefinition(
                        d.types().stream()
                                .map(
                                        t ->
                                                new EntityTypeDefinition(
                                                        t.key(), t.label(), t.description()))
                                .toList(),
                        d.properties().stream()
                                .map(
                                        p ->
                                                new PropertyDefinition(
                                                        p.key(),
                                                        p.label(),
                                                        p.description(),
                                                        p.ownerTypeKey(),
                                                        p.valueType(),
                                                        p.multiplicity(),
                                                        Optional.ofNullable(p.fixedUnit())))
                                .toList(),
                        d.relations().stream()
                                .map(
                                        r ->
                                                new RelationDefinition(
                                                        r.key(),
                                                        r.label(),
                                                        r.description(),
                                                        r.sourceTypeKey(),
                                                        r.targetTypeKey(),
                                                        r.multiplicity()))
                                .toList());
        return validator.validate(core).violations().stream()
                .map(v -> new Violation(v.code(), v.path(), v.message(), v.severity().name()))
                .toList();
    }

    public void reject(List<Violation> errors) {
        if (!errors.isEmpty())
            throw new SemanticApiException(
                    422, "VALIDATION_FAILED", "Ontology validation failed", errors);
    }

    public OntologyView ontology(OntologyRow o) {
        return new OntologyView(
                o.getId(),
                o.getWorkspaceId().toString(),
                o.getName(),
                o.getDescription(),
                o.getLatestVersion(),
                o.getLatestRevisionId(),
                o.getDraftId() != null,
                o.getUpdatedAt().toInstant(java.time.ZoneOffset.UTC));
    }

    public DraftView draft(OntologyRevisionRow r) {
        return new DraftView(
                r.getId(),
                r.getOntologyId(),
                r.getBaseRevisionId(),
                r.getVersion(),
                r.getDraftVersion(),
                r.getName(),
                r.getDescription(),
                decode(r.getDefinitionJson(), Definition.class));
    }

    public RevisionView revision(OntologyRevisionRow r) {
        return new RevisionView(
                r.getId(),
                r.getOntologyId(),
                r.getVersion(),
                r.getName(),
                r.getDescription(),
                decode(r.getDefinitionJson(), Definition.class),
                r.getAvailableForNewBindings(),
                r.getPublishedAt().toInstant(java.time.ZoneOffset.UTC),
                r.getPublishedBy(),
                r.getPublicationNote(),
                r.getBaseRevisionId());
    }
}

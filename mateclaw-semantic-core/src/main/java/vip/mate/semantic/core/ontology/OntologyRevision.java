package vip.mate.semantic.core.ontology;

import java.util.Objects;

import vip.mate.semantic.core.identity.SemanticIds.OntologyId;
import vip.mate.semantic.core.identity.SemanticIds.OntologyRevisionId;

/** An addressable immutable ontology version. */
public record OntologyRevision(
        OntologyRevisionId revisionId,
        OntologyId ontologyId,
        int version,
        OntologyDefinition definition) {

    public OntologyRevision {
        Objects.requireNonNull(revisionId, "revisionId");
        Objects.requireNonNull(ontologyId, "ontologyId");
        Objects.requireNonNull(definition, "definition");
        if (version <= 0) {
            throw new IllegalArgumentException("version must be positive");
        }
    }

    public int nextVersion() {
        return Math.incrementExact(version);
    }
}

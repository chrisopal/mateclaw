package vip.mate.semantic.core.ontology;

import java.util.Objects;

import vip.mate.semantic.core.policy.BusinessPolicySet;

import vip.mate.semantic.core.identity.SemanticIds.OntologyId;
import vip.mate.semantic.core.identity.SemanticIds.OntologyRevisionId;

/** An addressable immutable ontology version. */
public record OntologyRevision(
        OntologyRevisionId revisionId,
        OntologyId ontologyId,
        int version,
        ParsedOntologyDocument document,
        BusinessPolicySet policy) {

    public OntologyRevision {
        Objects.requireNonNull(revisionId, "revisionId");
        Objects.requireNonNull(ontologyId, "ontologyId");
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(policy, "policy");
        if (!ontologyId.value().equals(document.document().ontologyId())) {
            throw new IllegalArgumentException("document ontologyId must match revision ontologyId");
        }
        if (!revisionId.value().equals(document.document().revisionId())) {
            throw new IllegalArgumentException("document revisionId must match revision revisionId");
        }
        if (version <= 0) {
            throw new IllegalArgumentException("version must be positive");
        }
    }

    public int nextVersion() {
        return Math.incrementExact(version);
    }
}

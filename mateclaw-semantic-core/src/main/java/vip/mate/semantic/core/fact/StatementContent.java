package vip.mate.semantic.core.fact;

import java.util.Objects;
import java.util.Set;

import vip.mate.semantic.core.identity.GraphScope;
import vip.mate.semantic.core.identity.SemanticIds.EntityId;
import vip.mate.semantic.core.identity.SemanticIds.EvidenceId;
import vip.mate.semantic.core.identity.SemanticIds.OntologyRevisionId;

/** Immutable payload shared by a proposed fact and its revisions. */
public record StatementContent(
        GraphScope scope,
        OntologyRevisionId ontologyRevisionId,
        EntityId subjectId,
        PredicateRef predicate,
        StatementValue value,
        Validity validity,
        Set<EvidenceId> evidenceIds) {

    public StatementContent {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(ontologyRevisionId, "ontologyRevisionId");
        Objects.requireNonNull(subjectId, "subjectId");
        Objects.requireNonNull(predicate, "predicate");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(validity, "validity");
        Objects.requireNonNull(evidenceIds, "evidenceIds");
        evidenceIds = Set.copyOf(evidenceIds);
    }
}

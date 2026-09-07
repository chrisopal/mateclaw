package vip.mate.semantic.core.conflict;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import vip.mate.semantic.core.fact.PredicateRef;
import vip.mate.semantic.core.fact.StatementContent;
import vip.mate.semantic.core.fact.StatementRevision;
import vip.mate.semantic.core.fact.StatementValue;
import vip.mate.semantic.core.fact.Validity;
import vip.mate.semantic.core.ontology.Multiplicity;
import vip.mate.semantic.core.ontology.OntologyRevision;
import vip.mate.semantic.core.ontology.PropertyDefinition;

/** Pure, deterministic comparison for same-scope statement content. */
public final class ConflictDetector {

    public Optional<ConflictKind> compare(
            OntologyRevision ontology,
            StatementContent left,
            StatementContent right) {
        Objects.requireNonNull(ontology, "ontology");
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        requireComparableScope(left, right, ontology);
        if (!left.subjectId().equals(right.subjectId()) || !left.predicate().equals(right.predicate())) {
            return Optional.empty();
        }
        if (!(left.predicate() instanceof PredicateRef.PropertyRef propertyRef)) {
            return compareRelation(ontology, left, right);
        }
        PropertyDefinition property = ontology.definition().properties().stream()
                .filter(item -> item != null && item.key().equals(propertyRef.key()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("predicate is not declared by the ontology"));
        if (property.multiplicity() == Multiplicity.MULTI) {
            return Optional.empty();
        }
        if (sameValue(left.value(), right.value())) {
            return Optional.empty();
        }
        return temporalConflict(left.validity(), right.validity());
    }

    public List<ConflictFinding> detect(
            OntologyRevision ontology,
            StatementContent candidate,
            List<StatementRevision> currentAcceptedStatements) {
        Objects.requireNonNull(currentAcceptedStatements, "currentAcceptedStatements");
        List<ConflictFinding> findings = new ArrayList<>();
        for (StatementRevision accepted : currentAcceptedStatements) {
            if (accepted == null || accepted.reviewStatus() != StatementRevision.ReviewStatus.ACCEPTED) {
                continue;
            }
            compare(ontology, candidate, accepted.content()).ifPresent(kind -> findings.add(
                    new ConflictFinding(kind,
                            new ConflictMemberRef.StatementRevisionRef(
                                    accepted.statementId(), accepted.revision()),
                            reason(kind))));
        }
        return List.copyOf(findings);
    }

    /** Compares one candidate against other candidate revisions before any is accepted. */
    public List<ConflictFinding> detectCandidates(
            OntologyRevision ontology,
            List<StatementRevision> candidates) {
        Objects.requireNonNull(candidates, "candidates");
        List<ConflictFinding> findings = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            StatementRevision left = candidates.get(i);
            if (left == null) {
                continue;
            }
            for (int j = i + 1; j < candidates.size(); j++) {
                StatementRevision right = candidates.get(j);
                if (right == null) {
                    continue;
                }
                compare(ontology, left.content(), right.content()).ifPresent(kind -> findings.add(
                        new ConflictFinding(kind,
                                new ConflictMemberRef.StatementRevisionRef(
                                        right.statementId(), right.revision()),
                                reason(kind))));
            }
        }
        return List.copyOf(findings);
    }

    /** Alias for callers that use the same detect entry point for candidate batches. */
    public List<ConflictFinding> detect(
            OntologyRevision ontology,
            List<StatementRevision> candidates) {
        return detectCandidates(ontology, candidates);
    }

    /** Compares one candidate revision with other candidate revisions, retaining identities. */
    public List<ConflictFinding> detect(
            OntologyRevision ontology,
            StatementRevision candidate,
            List<StatementRevision> otherCandidates) {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(otherCandidates, "otherCandidates");
        List<ConflictFinding> findings = new ArrayList<>();
        for (StatementRevision other : otherCandidates) {
            if (other == null || other.statementId().equals(candidate.statementId())) {
                continue;
            }
            compare(ontology, candidate.content(), other.content()).ifPresent(kind -> findings.add(
                    new ConflictFinding(kind,
                            new ConflictMemberRef.StatementRevisionRef(other.statementId(), other.revision()),
                            reason(kind))));
        }
        return List.copyOf(findings);
    }

    private static Optional<ConflictKind> compareRelation(
            OntologyRevision ontology, StatementContent left, StatementContent right) {
        String relationKey = ((PredicateRef.RelationRef) left.predicate()).key();
        // Relations obey the same cardinality rule as properties: a MULTI relation
        // may legitimately point to several different target entities.
        // The caller has already checked that the predicates are equal.
        // Relation lookup is intentionally kept local so this method remains pure.
        var relation = ontology.definition().relations().stream()
                .filter(item -> item != null && item.key().equals(relationKey))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("predicate is not declared by the ontology"));
        if (relation.multiplicity() == Multiplicity.MULTI) {
            return Optional.empty();
        }
        if (left.predicate() instanceof PredicateRef.RelationRef
                && left.value() instanceof StatementValue.EntityValue
                && right.value() instanceof StatementValue.EntityValue
                && sameValue(left.value(), right.value())) {
            return Optional.empty();
        }
        return temporalConflict(left.validity(), right.validity());
    }

    private static void requireComparableScope(
            StatementContent left, StatementContent right, OntologyRevision ontology) {
        if (!left.scope().equals(right.scope())) {
            throw new IllegalArgumentException("cannot compare statements from different graph scopes");
        }
        if (!ontology.revisionId().equals(left.ontologyRevisionId())
                || !ontology.revisionId().equals(right.ontologyRevisionId())) {
            throw new IllegalArgumentException("statements must use the supplied ontology revision");
        }
    }

    private static boolean sameValue(StatementValue left, StatementValue right) {
        if (left instanceof StatementValue.DecimalValue leftDecimal
                && right instanceof StatementValue.DecimalValue rightDecimal) {
            return leftDecimal.value().compareTo(rightDecimal.value()) == 0
                    && Objects.equals(leftDecimal.unit(), rightDecimal.unit());
        }
        return left.equals(right);
    }

    private static Optional<ConflictKind> temporalConflict(Validity left, Validity right) {
        if (left.isUnknown() || right.isUnknown()) {
            return Optional.of(ConflictKind.TEMPORAL_UNCERTAINTY);
        }
        return left.overlaps(right)
                ? Optional.of(ConflictKind.SINGLE_VALUE_DISAGREEMENT)
                : Optional.empty();
    }

    private static String reason(ConflictKind kind) {
        return switch (kind) {
            case SINGLE_VALUE_DISAGREEMENT -> "different values overlap for a single-value predicate";
            case TEMPORAL_UNCERTAINTY -> "different values cannot be compared because validity is unknown";
        };
    }
}

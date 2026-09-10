package vip.mate.semantic.core.conflict;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import vip.mate.semantic.core.fact.AssertionPayload;
import vip.mate.semantic.core.fact.StatementContent;
import vip.mate.semantic.core.fact.StatementRevision;
import vip.mate.semantic.core.fact.Validity;
import vip.mate.semantic.core.ontology.OntologyRevision;

/**
 * Pure comparison for same-scope statement content.
 *
 * <p>OWL open-world semantics do not make two different values a conflict.
 * This detector therefore reports only an explicit positive/negative
 * contradiction for the same assertion value. Business single-value rules
 * are evaluated by the versioned business policy layer.</p>
 */
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
        if (!oppositeAssertionValues(left.assertion(), right.assertion())) {
            return Optional.empty();
        }
        return temporalConflict(left.validity(), right.validity());
    }

    public Optional<ConflictKind> compare(OntologyRevision ontology,StatementContent left,
            StatementContent right, java.util.Set<String> subjectTypes) {
        var explicit=compare(ontology,left,right);
        if (explicit.isPresent()) return explicit;
        if (!left.subjectId().equals(right.subjectId()) || !left.predicate().equals(right.predicate())
                || left.predicate().isEmpty() || left.assertion().negative() || right.assertion().negative()
                || (!left.assertion().objectAssertion() && !left.assertion().dataAssertion())
                || left.assertion().kind()!=right.assertion().kind()) return Optional.empty();
        boolean single=ontology.policy().rules().stream().anyMatch(rule->rule.singleValue()
                && subjectTypes.contains(rule.classIri()) && rule.predicateIri().equals(left.predicate().orElseThrow().iri()));
        if (!single || businessValue(left.assertion()).equals(businessValue(right.assertion()))) return Optional.empty();
        if (left.validity().isUnknown() || right.validity().isUnknown()) return Optional.of(ConflictKind.BUSINESS_TEMPORAL_UNCERTAINTY);
        return left.validity().overlaps(right.validity()) ? Optional.of(ConflictKind.BUSINESS_SINGLE_VALUE) : Optional.empty();
    }
    private static Object businessValue(AssertionPayload assertion) {
        if (assertion.objectIri().isPresent()) return assertion.objectIri().orElseThrow();
        var literal=assertion.literal().orElseThrow();
        String datatype=literal.datatypeIri();
        if (datatype.startsWith("http://www.w3.org/2001/XMLSchema#")
                && java.util.Set.of("decimal","integer","long","int","short","byte","nonNegativeInteger",
                    "positiveInteger","nonPositiveInteger","negativeInteger","unsignedLong","unsignedInt","unsignedShort","unsignedByte")
                    .contains(datatype.substring(datatype.indexOf('#')+1))) {
            try { return new java.math.BigDecimal(literal.lexicalValue()).stripTrailingZeros(); }
            catch (NumberFormatException ignored) { /* Lexical validation reports malformed values separately. */ }
        }
        return literal;
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

    public List<ConflictFinding> detect(
            OntologyRevision ontology,
            List<StatementRevision> candidates) {
        return detectCandidates(ontology, candidates);
    }

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

    private static boolean oppositeAssertionValues(AssertionPayload left, AssertionPayload right) {
        if (left.kind() == AssertionPayload.AssertionKind.SAME_INDIVIDUAL
                || left.kind() == AssertionPayload.AssertionKind.DIFFERENT_INDIVIDUAL) {
            return left.relatedIndividualIri().equals(right.relatedIndividualIri())
                    && ((left.kind() == AssertionPayload.AssertionKind.SAME_INDIVIDUAL
                            && right.kind() == AssertionPayload.AssertionKind.DIFFERENT_INDIVIDUAL)
                        || (left.kind() == AssertionPayload.AssertionKind.DIFFERENT_INDIVIDUAL
                            && right.kind() == AssertionPayload.AssertionKind.SAME_INDIVIDUAL));
        }
        if (!left.objectAssertion() && !left.dataAssertion()) {
            return false;
        }
        boolean sameValue = left.objectIri().equals(right.objectIri())
                && left.literal().equals(right.literal())
                && left.predicateIri().equals(right.predicateIri());
        return sameValue && left.negative() != right.negative();
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

    private static Optional<ConflictKind> temporalConflict(Validity left, Validity right) {
        if (left.isUnknown() || right.isUnknown()) {
            return Optional.of(ConflictKind.TEMPORAL_UNCERTAINTY);
        }
        return left.overlaps(right)
                ? Optional.of(ConflictKind.ASSERTION_CONTRADICTION)
                : Optional.empty();
    }

    private static String reason(ConflictKind kind) {
        return switch (kind) {
            case BUSINESS_SINGLE_VALUE -> "explicit business single-value policy has overlapping distinct values";
            case BUSINESS_TEMPORAL_UNCERTAINTY -> "single-value policy needs review because validity is unknown";
            case ASSERTION_CONTRADICTION -> "positive and negative assertions overlap for the same value";
            case TEMPORAL_UNCERTAINTY -> "contradictory assertions cannot be compared because validity is unknown";
        };
    }
}

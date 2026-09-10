package vip.mate.semantic.core;

import java.util.List;

import org.junit.jupiter.api.Test;

import vip.mate.semantic.core.conflict.ConflictDetector;
import vip.mate.semantic.core.conflict.ConflictKind;
import vip.mate.semantic.core.fact.StatementRevision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConflictDetectorTest {

    private final ConflictDetector detector = new ConflictDetector();

    @Test void explicitSingleValuePolicyAppliesOnlyToItsSubjectClass() {
        var base=SemanticFixtures.voltageOntology();var left=SemanticFixtures.voltage("380");var right=SemanticFixtures.voltage("400");
        var type="urn:test:policy-equipment";
        var policy=new vip.mate.semantic.core.policy.BusinessPolicySet("single-v1",List.of(
                new vip.mate.semantic.core.policy.BusinessPolicySet.Rule(type,left.assertion().predicateIri().orElseThrow(),false,null,java.util.Set.of(),true)));
        var ontology=new vip.mate.semantic.core.ontology.OntologyRevision(base.revisionId(),base.ontologyId(),base.version(),base.document(),policy);
        assertTrue(detector.compare(ontology,left,right,java.util.Set.of(type)).isPresent());
        assertTrue(detector.compare(ontology,left,right,java.util.Set.of("urn:test:other")).isEmpty());
        assertTrue(detector.compare(ontology,left,SemanticFixtures.voltage("380.0"),java.util.Set.of(type)).isEmpty());
        assertTrue(detector.compare(ontology,left,SemanticFixtures.negativeVoltage("400"),java.util.Set.of(type)).isEmpty());
    }

    @Test
    void detectsAnExplicitPositiveNegativeContradiction() {
        var finding = detector.compare(
                SemanticFixtures.voltageOntology(),
                SemanticFixtures.voltage("380"),
                SemanticFixtures.negativeVoltage("380"));

        assertEquals(java.util.Optional.of(ConflictKind.ASSERTION_CONTRADICTION), finding);
    }

    @Test
    void doesNotTreatDifferentPositiveValuesAsAnOwlConflict() {
        assertTrue(detector.compare(
                SemanticFixtures.voltageOntology(),
                SemanticFixtures.voltage("380"),
                SemanticFixtures.voltage("400")).isEmpty());
    }

    @Test
    void doesNotReportContradictionsWhoseKnownValidityIntervalsDoNotOverlap() {
        var left = SemanticFixtures.voltage(SemanticFixtures.SCOPE, "380",
                SemanticFixtures.interval("2026-01-01T00:00:00Z", "2026-02-01T00:00:00Z"));
        var right = SemanticFixtures.voltage(SemanticFixtures.SCOPE, "380",
                SemanticFixtures.interval("2026-02-01T00:00:00Z", "2026-03-01T00:00:00Z"), true);

        assertTrue(detector.compare(SemanticFixtures.voltageOntology(), left, right).isEmpty());
    }

    @Test
    void marksContradictionsWithUnknownValidityAsTemporallyUncertain() {
        assertEquals(java.util.Optional.of(ConflictKind.TEMPORAL_UNCERTAINTY),
                detector.compare(SemanticFixtures.voltageOntology(),
                        SemanticFixtures.voltage("380"),
                        SemanticFixtures.voltage(SemanticFixtures.SCOPE, "380",
                                vip.mate.semantic.core.fact.Validity.unknown(), true)));
    }

    @Test
    void detectsContradictionsBetweenCandidatesBeforeEitherIsAccepted() {
        var left = new StatementRevision(
                new vip.mate.semantic.core.identity.SemanticIds.StatementId("41"),
                1,
                SemanticFixtures.voltage("380"),
                StatementRevision.ReviewStatus.PROPOSED);
        var right = new StatementRevision(
                new vip.mate.semantic.core.identity.SemanticIds.StatementId("42"),
                1,
                SemanticFixtures.negativeVoltage("380"),
                StatementRevision.ReviewStatus.PROPOSED);

        assertEquals(1, detector.detectCandidates(SemanticFixtures.voltageOntology(), List.of(left, right)).size());
    }

    @Test
    void rejectsComparisonsAcrossGraphScopes() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> detector.compare(SemanticFixtures.voltageOntology(),
                        SemanticFixtures.voltage("380"),
                        SemanticFixtures.voltage(SemanticFixtures.OTHER_SCOPE, "380",
                                vip.mate.semantic.core.fact.Validity.unbounded(), true)));
    }
}

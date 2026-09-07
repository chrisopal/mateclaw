package vip.mate.semantic.core;

import java.util.List;

import org.junit.jupiter.api.Test;

import vip.mate.semantic.core.conflict.ConflictDetector;
import vip.mate.semantic.core.conflict.ConflictKind;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConflictDetectorTest {

    private final ConflictDetector detector = new ConflictDetector();

    @Test
    void detectsAConflictForDifferentValuesOfASingleValueProperty() {
        var findings = detector.detect(SemanticFixtures.voltageOntology(),
                SemanticFixtures.voltage("400"), List.of(SemanticFixtures.acceptedVoltage("380")));

        assertEquals(1, findings.size());
        assertEquals(ConflictKind.SINGLE_VALUE_DISAGREEMENT, findings.getFirst().kind());
    }

    @Test
    void treatsBigDecimalRepresentationsOfTheSameValueAsEquivalent() {
        assertTrue(detector.compare(SemanticFixtures.voltageOntology(),
                SemanticFixtures.voltage("380.0"), SemanticFixtures.voltage("380")).isEmpty());
    }

    @Test
    void doesNotReportDifferentValuesForAMultiValueProperty() {
        var ontology = new vip.mate.semantic.core.ontology.OntologyRevision(
                new vip.mate.semantic.core.identity.SemanticIds.OntologyRevisionId("10"),
                new vip.mate.semantic.core.identity.SemanticIds.OntologyId("11"),
                1,
                new vip.mate.semantic.core.ontology.OntologyDefinition(
                        List.of(new vip.mate.semantic.core.ontology.EntityTypeDefinition("Equipment", "设备", "")),
                        List.of(new vip.mate.semantic.core.ontology.PropertyDefinition(
                                "ratedVoltage", "额定电压", "", "Equipment",
                                vip.mate.semantic.core.ontology.ValueType.DECIMAL,
                                vip.mate.semantic.core.ontology.Multiplicity.MULTI,
                                java.util.Optional.of("V"))), List.of()));

        assertTrue(detector.compare(ontology, SemanticFixtures.voltage("380"), SemanticFixtures.voltage("400")).isEmpty());
    }

    @Test
    void doesNotReportValuesWhoseKnownValidityIntervalsDoNotOverlap() {
        var left = SemanticFixtures.voltage(SemanticFixtures.SCOPE, "380",
                SemanticFixtures.interval("2026-01-01T00:00:00Z", "2026-02-01T00:00:00Z"));
        var right = SemanticFixtures.voltage(SemanticFixtures.SCOPE, "400",
                SemanticFixtures.interval("2026-02-01T00:00:00Z", "2026-03-01T00:00:00Z"));

        assertTrue(detector.compare(SemanticFixtures.voltageOntology(), left, right).isEmpty());
    }

    @Test
    void marksDifferentValuesWithUnknownValidityAsTemporallyUncertain() {
        assertEquals(java.util.Optional.of(ConflictKind.TEMPORAL_UNCERTAINTY),
                detector.compare(SemanticFixtures.voltageOntology(),
                        SemanticFixtures.voltage("380"),
                        SemanticFixtures.voltage(SemanticFixtures.SCOPE, "400",
                        vip.mate.semantic.core.fact.Validity.unknown())));
    }

    @Test
    void detectsConflictsBetweenTwoCandidatesBeforeEitherIsAccepted() {
        var left = new vip.mate.semantic.core.fact.StatementRevision(
                new vip.mate.semantic.core.identity.SemanticIds.StatementId("41"),
                1,
                SemanticFixtures.voltage("380"),
                vip.mate.semantic.core.fact.StatementRevision.ReviewStatus.PROPOSED);
        var right = new vip.mate.semantic.core.fact.StatementRevision(
                new vip.mate.semantic.core.identity.SemanticIds.StatementId("42"),
                1,
                SemanticFixtures.voltage("400"),
                vip.mate.semantic.core.fact.StatementRevision.ReviewStatus.PROPOSED);

        assertEquals(1, detector.detectCandidates(SemanticFixtures.voltageOntology(), List.of(left, right)).size());
    }

    @Test
    void rejectsComparisonsAcrossGraphScopes() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> detector.compare(SemanticFixtures.voltageOntology(),
                        SemanticFixtures.voltage("380"),
                        SemanticFixtures.voltage(SemanticFixtures.OTHER_SCOPE, "400",
                                vip.mate.semantic.core.fact.Validity.unbounded())));
    }
}

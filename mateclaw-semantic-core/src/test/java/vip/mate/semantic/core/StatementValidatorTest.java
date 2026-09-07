package vip.mate.semantic.core;

import java.util.Map;

import org.junit.jupiter.api.Test;

import vip.mate.semantic.core.fact.Entity;
import vip.mate.semantic.core.fact.PredicateRef;
import vip.mate.semantic.core.fact.StatementValidator;
import vip.mate.semantic.core.identity.SemanticIds;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatementValidatorTest {

    private final StatementValidator validator = new StatementValidator();

    @Test
    void acceptsAStatementWhenScopePredicateValueAndEntityTypeMatch() {
        var report = validator.validate(
                SemanticFixtures.SCOPE,
                SemanticFixtures.voltageOntology(),
                SemanticFixtures.voltage("380"),
                SemanticFixtures.equipmentEntity());

        assertTrue(report.valid(), () -> "unexpected violations: " + report.violations());
    }

    @Test
    void rejectsAStatementFromAnotherGraphScope() {
        var report = validator.validate(
                SemanticFixtures.SCOPE,
                SemanticFixtures.voltageOntology(),
                SemanticFixtures.voltage(SemanticFixtures.OTHER_SCOPE, "380", vip.mate.semantic.core.fact.Validity.unbounded()),
                SemanticFixtures.equipmentEntity());

        assertViolation(report, "SCOPE_MISMATCH");
    }

    @Test
    void rejectsAnEntityReferenceWhoseTypeDoesNotMatchTheRelationDefinition() {
        var content = new vip.mate.semantic.core.fact.StatementContent(
                SemanticFixtures.SCOPE,
                SemanticFixtures.voltageOntology().revisionId(),
                new SemanticIds.EntityId("20"),
                PredicateRef.property("ratedVoltage"),
                new vip.mate.semantic.core.fact.StatementValue.TextValue("not a decimal"),
                vip.mate.semantic.core.fact.Validity.unbounded(),
                java.util.Set.of(new SemanticIds.EvidenceId("30")));

        var report = validator.validate(SemanticFixtures.SCOPE, SemanticFixtures.voltageOntology(), content,
                Map.of(new SemanticIds.EntityId("20"),
                        new Entity(new SemanticIds.EntityId("20"), SemanticFixtures.SCOPE, "OtherType", "P-101")));

        assertViolation(report, "SUBJECT_TYPE_MISMATCH");
        assertViolation(report, "VALUE_TYPE_MISMATCH");
    }

    @Test
    void rejectsAUnitThatDiffersFromThePropertyFixedUnit() {
        var content = new vip.mate.semantic.core.fact.StatementContent(
                SemanticFixtures.SCOPE,
                SemanticFixtures.voltageOntology().revisionId(),
                new SemanticIds.EntityId("20"),
                PredicateRef.property("ratedVoltage"),
                new vip.mate.semantic.core.fact.StatementValue.DecimalValue("0.38", "kV"),
                vip.mate.semantic.core.fact.Validity.unbounded(),
                java.util.Set.of(new SemanticIds.EvidenceId("30")));

        var report = validator.validate(SemanticFixtures.SCOPE, SemanticFixtures.voltageOntology(), content,
                SemanticFixtures.equipmentEntity());

        assertViolation(report, "UNIT_MISMATCH");
    }

    private static void assertViolation(vip.mate.semantic.core.validation.ValidationReport report, String code) {
        assertFalse(report.violations().stream().noneMatch(violation -> violation.code().equals(code)),
                () -> "missing " + code + " in " + report.violations());
    }
}

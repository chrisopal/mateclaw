package vip.mate.semantic.core;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.List;

import org.junit.jupiter.api.Test;

import vip.mate.semantic.core.fact.Entity;
import vip.mate.semantic.core.fact.PredicateRef;
import vip.mate.semantic.core.fact.StatementContent;
import vip.mate.semantic.core.fact.StatementValidator;
import vip.mate.semantic.core.identity.SemanticIds;
import vip.mate.semantic.core.validation.ValidationReport;
import vip.mate.semantic.core.validation.Violation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatementValidatorTest {

    private final StatementValidator validator = new StatementValidator(SemanticFixtures::noSemanticViolations);

    @Test
    void acceptsAStatementWhenScopeRevisionAndAssertionIndexesMatch() {
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
    void rejectsAnAssertionWhosePredicateIndexDiffersFromTheStatementPredicate() {
        var assertion = vip.mate.semantic.core.fact.AssertionPayload.dataPropertyAssertion(
                "DataPropertyAssertion(...)",
                SemanticFixtures.ENTITY_IRI,
                "https://example.test/property/other",
                new vip.mate.semantic.core.fact.AssertionPayload.LiteralValue("380", SemanticFixtures.XSD_DECIMAL),
                false,
                Set.of(SemanticFixtures.ENTITY_IRI, "https://example.test/property/other"));
        var content = new StatementContent(
                SemanticFixtures.SCOPE,
                SemanticFixtures.voltageOntology().revisionId(),
                new SemanticIds.EntityId("20"),
                Optional.of(PredicateRef.property(SemanticFixtures.VOLTAGE_IRI)),
                assertion,
                vip.mate.semantic.core.fact.Validity.unbounded(),
                Set.of(new SemanticIds.EvidenceId("30")));

        var report = validator.validate(SemanticFixtures.SCOPE, SemanticFixtures.voltageOntology(), content,
                SemanticFixtures.equipmentEntity());

        assertViolation(report, "PREDICATE_MISMATCH");
    }

    @Test
    void requiresTheSemanticValidationPortToBeInjected() {
        org.junit.jupiter.api.Assertions.assertThrows(NullPointerException.class,
                () -> new StatementValidator(null));
    }

    @Test
    void propagatesAdapterSemanticViolations() {
        var validator = new StatementValidator((ontology, candidate, entities) -> new ValidationReport(List.of(
                new Violation("OWL_SYNTAX_INVALID", "assertion.functionalSyntax",
                        Violation.Severity.ERROR, "invalid Functional Syntax"))));

        var report = validator.validate(SemanticFixtures.SCOPE, SemanticFixtures.voltageOntology(),
                SemanticFixtures.voltage("380"), SemanticFixtures.equipmentEntity());

        assertViolation(report, "OWL_SYNTAX_INVALID");
    }

    private static void assertViolation(ValidationReport report, String code) {
        assertFalse(report.violations().stream().noneMatch(violation -> violation.code().equals(code)),
                () -> "missing " + code + " in " + report.violations());
    }
}

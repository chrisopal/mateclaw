package vip.mate.semantic.core.ontology;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import vip.mate.semantic.core.SemanticFixtures;
import vip.mate.semantic.core.fact.Entity;
import vip.mate.semantic.core.fact.PredicateRef;
import vip.mate.semantic.core.fact.StatementContent;
import vip.mate.semantic.core.fact.StatementValidator;
import vip.mate.semantic.core.fact.StatementValue;
import vip.mate.semantic.core.fact.Validity;
import vip.mate.semantic.core.identity.SemanticIds;
import vip.mate.semantic.core.validation.ValidationReport;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

class OntologyConstraintTest {

    private final OntologyValidator ontologyValidator = new OntologyValidator();
    private final StatementValidator statementValidator = new StatementValidator();

    @Test
    void normalizesAliasesAndWarnsWhenTermsShareAnAlias() {
        var definition =
                new OntologyDefinition(
                        List.of(
                                new EntityTypeDefinition(
                                        "Equipment",
                                        "设备",
                                        "",
                                        List.of("  设备  ", "机器", "机器", ""),
                                        false),
                                new EntityTypeDefinition(
                                        "Component", "部件", "", List.of("机器"), false)),
                        List.of(),
                        List.of());

        assertEquals(List.of("设备", "机器"), definition.types().get(0).aliases());
        ValidationReport report = ontologyValidator.validate(definition);

        assertTrue(
                report.valid(),
                () -> "warnings should not block validation: " + report.violations());
        assertTrue(
                report.violations().stream()
                        .anyMatch(
                                v ->
                                        v.code().equals("AMBIGUOUS_ALIAS")
                                                && v.severity().name().equals("WARNING")),
                () -> "missing alias warning: " + report.violations());
    }

    @Test
    void validatesTextEnumerationAndDecimalBoundsWithStrictApplicability() {
        var definition =
                new OntologyDefinition(
                        List.of(new EntityTypeDefinition("Equipment", "设备", "")),
                        List.of(
                                new PropertyDefinition(
                                        "status",
                                        "状态",
                                        "",
                                        "Equipment",
                                        ValueType.TEXT,
                                        Multiplicity.SINGLE,
                                        Optional.empty(),
                                        List.of("状态"),
                                        false,
                                        new PropertyConstraints(List.of("待复核", "已确认"), null, null)),
                                new PropertyDefinition(
                                        "deviation",
                                        "偏差",
                                        "",
                                        "Equipment",
                                        ValueType.DECIMAL,
                                        Multiplicity.SINGLE,
                                        Optional.of("mm"),
                                        List.of(),
                                        false,
                                        new PropertyConstraints(null, "-0.05", "0.05"))),
                        List.of());

        assertTrue(
                ontologyValidator.validate(definition).valid(),
                () ->
                        "unexpected violations: "
                                + ontologyValidator.validate(definition).violations());

        var duplicateAndInapplicable =
                new OntologyDefinition(
                        List.of(new EntityTypeDefinition("Equipment", "设备", "")),
                        List.of(
                                new PropertyDefinition(
                                        "status",
                                        "状态",
                                        "",
                                        "Equipment",
                                        ValueType.TEXT,
                                        Multiplicity.SINGLE,
                                        Optional.empty(),
                                        List.of(),
                                        false,
                                        new PropertyConstraints(List.of("待复核", "待复核"), "0", null))),
                        List.of());
        ValidationReport report = ontologyValidator.validate(duplicateAndInapplicable);
        assertViolation(
                report, "DUPLICATE_ALLOWED_VALUE", "properties[0].constraints.allowedValues[1]");
        assertViolation(report, "CONSTRAINT_NOT_APPLICABLE", "properties[0].constraints.minimum");
    }

    @Test
    void rejectsMalformedDecimalBoundsAndInvalidRanges() {
        var definition =
                new OntologyDefinition(
                        List.of(new EntityTypeDefinition("Equipment", "设备", "")),
                        List.of(
                                new PropertyDefinition(
                                        "deviation",
                                        "偏差",
                                        "",
                                        "Equipment",
                                        ValueType.DECIMAL,
                                        Multiplicity.SINGLE,
                                        Optional.empty(),
                                        List.of(),
                                        false,
                                        new PropertyConstraints(null, "1e-2", "0"))),
                        List.of());
        ValidationReport report = ontologyValidator.validate(definition);

        assertViolation(report, "INVALID_DECIMAL_BOUND", "properties[0].constraints.minimum");

        var reversed =
                new OntologyDefinition(
                        List.of(new EntityTypeDefinition("Equipment", "设备", "")),
                        List.of(
                                new PropertyDefinition(
                                        "deviation",
                                        "偏差",
                                        "",
                                        "Equipment",
                                        ValueType.DECIMAL,
                                        Multiplicity.SINGLE,
                                        Optional.empty(),
                                        List.of(),
                                        false,
                                        new PropertyConstraints(null, "1", "0"))),
                        List.of());
        assertViolation(
                ontologyValidator.validate(reversed),
                "INVALID_CONSTRAINT_RANGE",
                "properties[0].constraints");

        var tooPrecise =
                new PropertyConstraints(
                        null, "123456789012345678901234567890123456789", "0.1234567890123");
        var preciseDefinition =
                new OntologyDefinition(
                        List.of(new EntityTypeDefinition("Equipment", "设备", "")),
                        List.of(
                                new PropertyDefinition(
                                        "deviation",
                                        "偏差",
                                        "",
                                        "Equipment",
                                        ValueType.DECIMAL,
                                        Multiplicity.SINGLE,
                                        Optional.empty(),
                                        List.of(),
                                        false,
                                        tooPrecise)),
                        List.of());
        ValidationReport preciseReport = ontologyValidator.validate(preciseDefinition);
        assertTrue(
                preciseReport.violations().stream()
                                .filter(v -> v.code().equals("DECIMAL_BOUND_TOO_PRECISE"))
                                .count()
                        >= 2,
                () -> "missing precision violations: " + preciseReport.violations());
    }

    @Test
    void consumesTextAndDecimalConstraintsForInclusiveFactValidation() {
        var revision =
                new OntologyRevision(
                        new SemanticIds.OntologyRevisionId("12"),
                        new SemanticIds.OntologyId("13"),
                        1,
                        new OntologyDefinition(
                                List.of(new EntityTypeDefinition("Equipment", "设备", "")),
                                List.of(
                                        new PropertyDefinition(
                                                "status",
                                                "状态",
                                                "",
                                                "Equipment",
                                                ValueType.TEXT,
                                                Multiplicity.SINGLE,
                                                Optional.empty(),
                                                List.of(),
                                                false,
                                                new PropertyConstraints(
                                                        List.of("待复核", "已确认"), null, null)),
                                        new PropertyDefinition(
                                                "deviation",
                                                "偏差",
                                                "",
                                                "Equipment",
                                                ValueType.DECIMAL,
                                                Multiplicity.SINGLE,
                                                Optional.of("mm"),
                                                List.of(),
                                                false,
                                                new PropertyConstraints(null, "-0.05", "0.05"))),
                                List.of()));
        Entity equipment = SemanticFixtures.equipmentEntity().values().iterator().next();

        assertTrue(
                validateFact(
                                revision,
                                equipment,
                                PredicateRef.property("deviation"),
                                new StatementValue.DecimalValue(new BigDecimal("0.05"), "mm"))
                        .valid());
        assertViolation(
                validateFact(
                        revision,
                        equipment,
                        PredicateRef.property("deviation"),
                        new StatementValue.DecimalValue(new BigDecimal("0.0501"), "mm")),
                "VALUE_ABOVE_MAXIMUM");
        assertViolation(
                validateFact(
                        revision,
                        equipment,
                        PredicateRef.property("status"),
                        new StatementValue.TextValue("已关闭")),
                "VALUE_NOT_ALLOWED");
    }

    private ValidationReport validateFact(
            OntologyRevision revision,
            Entity equipment,
            PredicateRef predicate,
            StatementValue value) {
        StatementContent content =
                new StatementContent(
                        SemanticFixtures.SCOPE,
                        revision.revisionId(),
                        equipment.entityId(),
                        predicate,
                        value,
                        Validity.unbounded(),
                        java.util.Set.of(new SemanticIds.EvidenceId("30")));
        return statementValidator.validate(
                SemanticFixtures.SCOPE, revision, content, Map.of(equipment.entityId(), equipment));
    }

    private static void assertViolation(ValidationReport report, String code, String path) {
        assertTrue(
                report.violations().stream()
                        .anyMatch(v -> code.equals(v.code()) && path.equals(v.path())),
                () -> "missing " + code + " at " + path + ": " + report.violations());
    }

    private static void assertViolation(ValidationReport report, String code) {
        assertFalse(
                report.violations().stream().noneMatch(v -> code.equals(v.code())),
                () -> "missing " + code + ": " + report.violations());
    }
}

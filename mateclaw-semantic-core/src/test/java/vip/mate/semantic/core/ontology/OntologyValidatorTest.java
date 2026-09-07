package vip.mate.semantic.core.ontology;

import org.junit.jupiter.api.Test;
import vip.mate.semantic.core.fact.Entity;
import vip.mate.semantic.core.identity.GraphScope;
import vip.mate.semantic.core.identity.SemanticIds;
import vip.mate.semantic.core.validation.ValidationReport;
import vip.mate.semantic.core.validation.Violation;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OntologyValidatorTest {

    private final OntologyValidator validator = new OntologyValidator();

    @Test
    void validatesEquipmentAndComponentOntologyWithChineseLabels() {
        ValidationReport report = validator.validate(validDefinition());

        assertTrue(report.valid(), () -> "unexpected violations: " + report.violations());
        assertTrue(report.violations().isEmpty());
    }

    @Test
    void reportsMissingRelationTargetAtItsExactFieldPath() {
        var definition = new OntologyDefinition(
                List.of(new EntityTypeDefinition("Equipment", "设备", "")),
                List.of(),
                List.of(new RelationDefinition("hasPart", "安装部件", "",
                        "Equipment", "MissingType", Multiplicity.MULTI)));

        ValidationReport report = validator.validate(definition);

        assertFalse(report.valid());
        assertViolation(report, "UNKNOWN_TYPE", "relations[0].targetTypeKey");
    }

    @Test
    void reportsMissingOwnersAndRelationEndpointsAtExactPaths() {
        var definition = new OntologyDefinition(
                List.of(new EntityTypeDefinition("Equipment", "设备", "")),
                List.of(new PropertyDefinition("serialNumber", "序列号", "",
                        "MissingOwner", ValueType.TEXT, Multiplicity.SINGLE, Optional.empty())),
                List.of(new RelationDefinition("hasPart", "安装部件", "",
                        "MissingSource", "MissingTarget", Multiplicity.MULTI)));

        ValidationReport report = validator.validate(definition);

        assertViolation(report, "UNKNOWN_TYPE", "properties[0].ownerTypeKey");
        assertViolation(report, "UNKNOWN_TYPE", "relations[0].sourceTypeKey");
        assertViolation(report, "UNKNOWN_TYPE", "relations[0].targetTypeKey");
    }

    @Test
    void rejectsDuplicateKeysWithinEachDefinitionKind() {
        var definition = new OntologyDefinition(
                List.of(
                        new EntityTypeDefinition("Equipment", "设备", ""),
                        new EntityTypeDefinition("Equipment", "重复设备", "")),
                List.of(
                        new PropertyDefinition("serialNumber", "序列号", "",
                                "Equipment", ValueType.TEXT, Multiplicity.SINGLE, Optional.empty()),
                        new PropertyDefinition("serialNumber", "重复序列号", "",
                                "Equipment", ValueType.TEXT, Multiplicity.SINGLE, Optional.empty())),
                List.of(
                        new RelationDefinition("hasPart", "安装部件", "",
                                "Equipment", "Equipment", Multiplicity.MULTI),
                        new RelationDefinition("hasPart", "重复安装部件", "",
                                "Equipment", "Equipment", Multiplicity.MULTI)));

        ValidationReport report = validator.validate(definition);

        assertViolation(report, "DUPLICATE_KEY", "types[1].key");
        assertViolation(report, "DUPLICATE_KEY", "properties[1].key");
        assertViolation(report, "DUPLICATE_KEY", "relations[1].key");
    }

    @Test
    void allowsTheSameKeyInDifferentDefinitionKinds() {
        var definition = new OntologyDefinition(
                List.of(new EntityTypeDefinition("shared", "共享类型", "")),
                List.of(new PropertyDefinition("shared", "共享属性", "",
                        "shared", ValueType.TEXT, Multiplicity.SINGLE, Optional.empty())),
                List.of(new RelationDefinition("shared", "共享关系", "",
                        "shared", "shared", Multiplicity.MULTI)));

        ValidationReport report = validator.validate(definition);

        assertTrue(report.valid(), () -> "unexpected violations: " + report.violations());
    }

    @Test
    void rejectsFixedUnitsForNonDecimalProperties() {
        var definition = new OntologyDefinition(
                List.of(new EntityTypeDefinition("Equipment", "设备", "")),
                List.of(new PropertyDefinition("serialNumber", "序列号", "",
                        "Equipment", ValueType.TEXT, Multiplicity.SINGLE, Optional.of("V"))),
                List.of());

        ValidationReport report = validator.validate(definition);

        assertViolation(report, "UNIT_REQUIRES_DECIMAL", "properties[0].fixedUnit");
    }

    @Test
    void rejectsEmptyTypesAndOversizedFieldsAndCollections() {
        var noTypes = new OntologyDefinition(List.of(), List.of(), List.of());
        assertViolation(validator.validate(noTypes), "EMPTY_TYPES", "types");

        var oversizedFields = new OntologyDefinition(
                List.of(new EntityTypeDefinition("E".repeat(65), "设".repeat(129), "x".repeat(1001))),
                List.of(), List.of());
        ValidationReport fieldReport = validator.validate(oversizedFields);
        assertViolation(fieldReport, "FIELD_TOO_LONG", "types[0].key");
        assertViolation(fieldReport, "FIELD_TOO_LONG", "types[0].label");
        assertViolation(fieldReport, "FIELD_TOO_LONG", "types[0].description");

        var tooManyTypes = new OntologyDefinition(
                java.util.stream.IntStream.rangeClosed(0, 100)
                        .mapToObj(i -> new EntityTypeDefinition("Type" + i, "类型" + i, ""))
                        .toList(),
                List.of(), List.of());
        assertViolation(validator.validate(tooManyTypes), "TOO_MANY_DEFINITIONS", "types");
    }

    @Test
    void appliesTheFiveHundredItemBudgetAcrossPropertiesAndRelationsTogether() {
        var atLimit = new OntologyDefinition(
                List.of(new EntityTypeDefinition("Equipment", "设备", "")),
                properties(250),
                relations(250));
        assertTrue(validator.validate(atLimit).valid());

        var overLimit = new OntologyDefinition(
                List.of(new EntityTypeDefinition("Equipment", "设备", "")),
                properties(251),
                relations(250));
        assertViolation(validator.validate(overLimit), "TOO_MANY_DEFINITIONS", "$");
    }

    @Test
    void rejectsMalformedDefinitionKeysAndRequiredText() {
        var definition = new OntologyDefinition(
                List.of(
                        new EntityTypeDefinition("bad/key", "设备", ""),
                        new EntityTypeDefinition("2Equipment", " ", "")),
                List.of(), List.of());

        ValidationReport report = validator.validate(definition);

        assertViolation(report, "INVALID_KEY", "types[0].key");
        assertViolation(report, "INVALID_KEY", "types[1].key");
        assertViolation(report, "REQUIRED", "types[1].label");
    }

    @Test
    void defensivelyCopiesDefinitionsAndReports() {
        var types = new ArrayList<>(List.of(new EntityTypeDefinition("Equipment", "设备", "")));
        var definition = new OntologyDefinition(types, List.of(), List.of());
        types.clear();
        assertEquals(1, definition.types().size());
        assertThrows(UnsupportedOperationException.class,
                () -> definition.types().add(new EntityTypeDefinition("Other", "其他", "")));

        var violations = new ArrayList<>(List.of(
                new Violation("REQUIRED", "types", Violation.Severity.ERROR, "must not be empty")));
        var report = new ValidationReport(violations);
        violations.clear();
        assertEquals(1, report.violations().size());
        assertThrows(UnsupportedOperationException.class, () -> report.violations().clear());
    }

    @Test
    void semanticIdsAcceptOnlyPositiveIntegerStrings() {
        List<Function<String, ?>> factories = List.of(
                SemanticIds.WorkspaceId::new,
                SemanticIds.KnowledgeBaseId::new,
                SemanticIds.GraphId::new,
                SemanticIds.OntologyId::new,
                SemanticIds.OntologyRevisionId::new,
                SemanticIds.EntityId::new,
                SemanticIds.StatementId::new,
                SemanticIds.ProposalId::new,
                SemanticIds.SnapshotId::new,
                SemanticIds.EvidenceId::new,
                SemanticIds.ConflictId::new);

        for (Function<String, ?> factory : factories) {
            assertThrows(IllegalArgumentException.class, () -> factory.apply(null));
            assertThrows(IllegalArgumentException.class, () -> factory.apply(""));
            assertThrows(IllegalArgumentException.class, () -> factory.apply("0"));
            assertThrows(IllegalArgumentException.class, () -> factory.apply("-1"));
            assertThrows(IllegalArgumentException.class, () -> factory.apply("1/2"));
            assertThrows(IllegalArgumentException.class, () -> factory.apply("1\\2"));
            assertEquals("42", idValue(factory.apply("42")));
        }
    }

    @Test
    void graphScopeRevisionAndEntityRetainExplicitValidatedIdentity() {
        var scope = new GraphScope(
                new SemanticIds.WorkspaceId("1"),
                new SemanticIds.KnowledgeBaseId("2"),
                new SemanticIds.GraphId("3"));
        var revision = new OntologyRevision(
                new SemanticIds.OntologyRevisionId("4"),
                new SemanticIds.OntologyId("5"),
                1,
                validDefinition());
        var entity = new Entity(new SemanticIds.EntityId("6"), scope, "Equipment", "P-101");

        assertEquals("3", scope.graphId().value());
        assertEquals(1, revision.version());
        assertEquals("Equipment", entity.typeKey());
        assertThrows(IllegalArgumentException.class,
                () -> new OntologyRevision(revision.revisionId(), revision.ontologyId(), 0, revision.definition()));
        var lastRevision = new OntologyRevision(
                revision.revisionId(), revision.ontologyId(), Integer.MAX_VALUE, revision.definition());
        assertThrows(ArithmeticException.class, lastRevision::nextVersion);
    }

    private static Object idValue(Object id) {
        try {
            return id.getClass().getMethod("value").invoke(id);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static OntologyDefinition validDefinition() {
        return new OntologyDefinition(
                List.of(
                        new EntityTypeDefinition("Equipment", "设备", "企业设备"),
                        new EntityTypeDefinition("Component", "部件", "设备部件")),
                List.of(
                        new PropertyDefinition("serialNumber", "序列号", "",
                                "Equipment", ValueType.TEXT, Multiplicity.SINGLE, Optional.empty()),
                        new PropertyDefinition("ratedVoltage", "额定电压", "",
                                "Equipment", ValueType.DECIMAL, Multiplicity.SINGLE, Optional.of("V"))),
                List.of(new RelationDefinition("hasPart", "安装部件", "",
                        "Equipment", "Component", Multiplicity.MULTI)));
    }

    private static List<PropertyDefinition> properties(int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> new PropertyDefinition("property" + i, "属性" + i, "",
                        "Equipment", ValueType.TEXT, Multiplicity.SINGLE, Optional.empty()))
                .toList();
    }

    private static List<RelationDefinition> relations(int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> new RelationDefinition("relation" + i, "关系" + i, "",
                        "Equipment", "Equipment", Multiplicity.MULTI))
                .toList();
    }

    private static void assertViolation(ValidationReport report, String code, String path) {
        assertTrue(report.violations().stream()
                        .anyMatch(violation -> code.equals(violation.code()) && path.equals(violation.path())),
                () -> "missing " + code + " at " + path + "; got " + report.violations());
    }
}

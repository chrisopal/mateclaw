package vip.mate.semantic.core.ontology;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

class OntologyChangeClassifierTest {

    private final OntologyChangeClassifier classifier = new OntologyChangeClassifier();

    @Test
    void classifiesStableKeysAndMetadataSeparately() {
        OntologyDefinition source =
                definition(
                        new EntityTypeDefinition("Equipment", "设备", "旧说明"),
                        property("status", ValueType.TEXT, Multiplicity.SINGLE, null));
        OntologyDefinition target =
                definition(
                        new EntityTypeDefinition("Equipment", "设备", "新说明"),
                        property(
                                "status",
                                ValueType.TEXT,
                                Multiplicity.SINGLE,
                                new PropertyConstraints(List.of("待复核"), null, null)));

        OntologyChangeReport report = classifier.classify(source, target);

        assertEquals(DefinitionChangeClass.POTENTIALLY_BREAKING, report.definitionChangeClass());
        assertEquals(2, report.termChanges().size());
        assertEquals(
                DefinitionChangeClass.ANNOTATION,
                report.termChanges().get(0).definitionChangeClass());
        assertEquals(
                DefinitionChangeClass.POTENTIALLY_BREAKING,
                report.termChanges().get(1).definitionChangeClass());
    }

    @Test
    void classifiesAddedExpandedAndRemovedTermsDeterministically() {
        OntologyDefinition source =
                definition(
                        new EntityTypeDefinition("Equipment", "设备", ""),
                        property(
                                "status",
                                ValueType.TEXT,
                                Multiplicity.SINGLE,
                                new PropertyConstraints(List.of("待复核", "已确认"), null, null)));
        OntologyDefinition target =
                new OntologyDefinition(
                        List.of(new EntityTypeDefinition("Equipment", "设备", "")),
                        List.of(
                                property(
                                        "status",
                                        ValueType.TEXT,
                                        Multiplicity.SINGLE,
                                        new PropertyConstraints(List.of("待复核"), null, null)),
                                property("newProperty", ValueType.TEXT, Multiplicity.SINGLE, null)),
                        List.of());

        OntologyChangeReport report = classifier.classify(source, target);

        assertEquals(DefinitionChangeClass.POTENTIALLY_BREAKING, report.definitionChangeClass());
        assertEquals("status", report.termChanges().get(0).key());
        assertEquals(
                DefinitionChangeClass.POTENTIALLY_BREAKING,
                report.termChanges().get(0).definitionChangeClass());
        assertEquals("newProperty", report.termChanges().get(1).key());
        assertEquals(
                DefinitionChangeClass.ADDITIVE_OR_WIDENING,
                report.termChanges().get(1).definitionChangeClass());
        assertEquals(
                List.of("status", "newProperty"),
                classifier.classify(source, target).termChanges().stream()
                        .map(TermChange::key)
                        .toList());
    }

    @Test
    void treatsStructuralChangesAsBreakingEvenWhenNoDataIsPresent() {
        OntologyDefinition source =
                definition(
                        new EntityTypeDefinition("Equipment", "设备", ""),
                        property("reading", ValueType.DECIMAL, Multiplicity.SINGLE, null));
        OntologyDefinition target =
                definition(
                        new EntityTypeDefinition("Equipment", "设备", ""),
                        property("reading", ValueType.TEXT, Multiplicity.SINGLE, null));

        OntologyChangeReport report = classifier.classify(source, target);

        assertEquals(DefinitionChangeClass.BREAKING, report.definitionChangeClass());
        assertEquals(List.of("VALUE_TYPE_CHANGED"), report.termChanges().get(0).reasons());
    }

    private static OntologyDefinition definition(
            EntityTypeDefinition type, PropertyDefinition property) {
        return new OntologyDefinition(List.of(type), List.of(property), List.of());
    }

    private static PropertyDefinition property(
            String key,
            ValueType valueType,
            Multiplicity multiplicity,
            PropertyConstraints constraints) {
        return new PropertyDefinition(
                key,
                key,
                "",
                "Equipment",
                valueType,
                multiplicity,
                Optional.empty(),
                List.of(),
                false,
                constraints);
    }
}

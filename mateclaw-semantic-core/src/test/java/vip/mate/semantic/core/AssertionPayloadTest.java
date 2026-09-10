package vip.mate.semantic.core;

import java.util.Set;

import org.junit.jupiter.api.Test;

import vip.mate.semantic.core.fact.AssertionPayload;
import vip.mate.semantic.core.fact.Entity;
import vip.mate.semantic.core.fact.PredicateRef;
import vip.mate.semantic.core.identity.SemanticIds;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AssertionPayloadTest {

    @Test
    void supportsClassObjectDataAndIdentityAssertionShapes() {
        var classAssertion = AssertionPayload.classAssertion(
                "ClassAssertion(<https://example.test/class/Equipment> <https://example.test/entity/20>)",
                SemanticFixtures.ENTITY_IRI,
                "<" + SemanticFixtures.EQUIPMENT_IRI + ">",
                Set.of(SemanticFixtures.ENTITY_IRI, SemanticFixtures.EQUIPMENT_IRI));
        var objectAssertion = AssertionPayload.objectPropertyAssertion(
                "ObjectPropertyAssertion(<https://example.test/property/hasPart> <https://example.test/entity/20> <https://example.test/entity/21>)",
                SemanticFixtures.ENTITY_IRI,
                "https://example.test/property/hasPart",
                SemanticFixtures.OTHER_ENTITY_IRI,
                false,
                Set.of(SemanticFixtures.ENTITY_IRI, SemanticFixtures.OTHER_ENTITY_IRI,
                        "https://example.test/property/hasPart"));
        var dataAssertion = AssertionPayload.dataPropertyAssertion(
                "DataPropertyAssertion(<https://example.test/property/ratedVoltage> <https://example.test/entity/20> \"380\")",
                SemanticFixtures.ENTITY_IRI,
                SemanticFixtures.VOLTAGE_IRI,
                new AssertionPayload.LiteralValue("380", SemanticFixtures.XSD_DECIMAL),
                false,
                Set.of(SemanticFixtures.ENTITY_IRI, SemanticFixtures.VOLTAGE_IRI));
        var identityAssertion = AssertionPayload.individualIdentity(
                "SameIndividual(<https://example.test/entity/20> <https://example.test/entity/21>)",
                SemanticFixtures.ENTITY_IRI,
                SemanticFixtures.OTHER_ENTITY_IRI,
                false,
                Set.of(SemanticFixtures.ENTITY_IRI, SemanticFixtures.OTHER_ENTITY_IRI));

        assertEquals(AssertionPayload.AssertionKind.CLASS_ASSERTION, classAssertion.kind());
        assertEquals(AssertionPayload.AssertionKind.POSITIVE_OBJECT_PROPERTY, objectAssertion.kind());
        assertEquals(AssertionPayload.AssertionKind.POSITIVE_DATA_PROPERTY, dataAssertion.kind());
        assertEquals(AssertionPayload.AssertionKind.SAME_INDIVIDUAL, identityAssertion.kind());
    }

    @Test
    void rejectsNonAbsolutePredicateAndEntityIris() {
        assertThrows(IllegalArgumentException.class, () -> PredicateRef.property("ratedVoltage"));
        assertThrows(IllegalArgumentException.class, () -> new Entity(
                new SemanticIds.EntityId("20"), SemanticFixtures.SCOPE, "entity-20", Set.of(), "P-101"));
    }

    @Test
    void rejectsADataAssertionWithAnObjectIriInsteadOfALiteral() {
        assertThrows(IllegalArgumentException.class, () -> new AssertionPayload(
                AssertionPayload.AssertionKind.POSITIVE_DATA_PROPERTY,
                "DataPropertyAssertion(...)",
                Set.of(SemanticFixtures.ENTITY_IRI),
                java.util.Optional.of(SemanticFixtures.ENTITY_IRI),
                java.util.Optional.of(SemanticFixtures.VOLTAGE_IRI),
                java.util.Optional.of(SemanticFixtures.OTHER_ENTITY_IRI),
                java.util.Optional.empty(),
                java.util.Optional.empty(),
                java.util.Optional.empty()));
    }
}

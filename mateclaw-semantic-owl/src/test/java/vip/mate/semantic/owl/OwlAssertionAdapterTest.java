package vip.mate.semantic.owl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import vip.mate.semantic.core.fact.AssertionPayload;
import vip.mate.semantic.core.fact.Entity;
import vip.mate.semantic.core.fact.PredicateRef;
import vip.mate.semantic.core.fact.StatementContent;
import vip.mate.semantic.core.identity.GraphScope;
import vip.mate.semantic.core.identity.SemanticIds;
import vip.mate.semantic.core.ontology.OntologyDocument;
import vip.mate.semantic.core.ontology.OntologyDocumentSyntax;
import vip.mate.semantic.core.ontology.OntologyRevision;
import vip.mate.semantic.core.ontology.ParsedOntologyDocument;
import vip.mate.semantic.core.policy.BusinessPolicySet;
import vip.mate.semantic.core.fact.Validity;
import vip.mate.semantic.core.validation.ValidationReport;

class OwlAssertionAdapterTest {

    private static final String ROOT_IRI = "urn:test:quality";
    private static final String EQUIPMENT = "urn:test:Equipment";
    private static final String DEVICE = "urn:test:device-20";
    private static final String OTHER_DEVICE = "urn:test:device-21";
    private static final String VOLTAGE = "urn:test:ratedVoltage";
    private static final String HAS_PART = "urn:test:hasPart";
    private static final String XSD_DECIMAL = "http://www.w3.org/2001/XMLSchema#decimal";

    private final OwlAssertionAdapter adapter = new OwlAssertionAdapter();
    private final GraphScope scope = new GraphScope(
            new SemanticIds.WorkspaceId("1"), new SemanticIds.KnowledgeBaseId("2"), new SemanticIds.GraphId("3"));

    @Test
    void documentKeywordsInsideLiteralValuesAreData() {
        var value=adapter.parse("DataPropertyAssertion(<urn:test:p> <urn:test:i> \"Import( Ontology( Annotation(\")");
        assertEquals("Import( Ontology( Annotation(",value.literal().orElseThrow().lexicalValue());
        assertThrows(IllegalArgumentException.class,()->adapter.parse("Import(<https://invalid.example/ontology>)"));
    }

    @Test
    void roleMappingsPreservePunningNestedExpressionsAndLiteralText() {
        var adapter=new OwlAssertionAdapter();
        var mapped=adapter.remapRoles("ClassAssertion(ObjectIntersectionOf(<urn:test:X> ObjectSomeValuesFrom(<urn:test:p> <urn:test:X>)) <urn:test:X>)",
            Map.of("urn:test:X","urn:test:Y"),Map.of("urn:test:p","urn:test:q"),Map.of(),Map.of());
        assertTrue(mapped.functionalSyntax().contains("<urn:test:Y>"));
        assertTrue(mapped.functionalSyntax().contains("<urn:test:q>"));
        assertEquals("urn:test:X",mapped.subjectIri().orElseThrow());
        var literal=adapter.remapRoles("DataPropertyAssertion(<urn:test:p> <urn:test:X> \"urn:test:X\")",
            Map.of(),Map.of(),Map.of("urn:test:p","urn:test:q"),Map.of("urn:test:X","urn:test:Z"));
        assertEquals("urn:test:X",literal.literal().orElseThrow().lexicalValue());
        assertEquals("urn:test:Z",literal.subjectIri().orElseThrow());
        assertEquals("urn:test:q",literal.predicateIri().orElseThrow());
    }

    @Test
    void businessPolicyUsesExplicitUnitAnnotationsAndDoesNotRejectOverToleranceMeasurements() {
        var base=ontology();
        var policy=new BusinessPolicySet("units-v1",List.of(new BusinessPolicySet.Rule(EQUIPMENT,VOLTAGE,false,"V",Set.of())));
        var revision=new OntologyRevision(base.revisionId(),base.ontologyId(),base.version(),base.document(),policy);
        String unit="Annotation(<urn:mateclaw:semantic:unit> \"V\") ";
        var parsed=adapter.parse("DataPropertyAssertion("+unit+"<"+VOLTAGE+"> <"+DEVICE+"> \"9999\"^^<"+XSD_DECIMAL+">)");
        var validator=new vip.mate.semantic.core.fact.StatementValidator(adapter);
        assertTrue(validator.validate(scope,revision,content(Optional.of(PredicateRef.property(VOLTAGE)),parsed),entities()).valid());
        var wrong=adapter.parse(parsed.functionalSyntax().replace("\"V\"","\"mV\""));
        var report=validator.validate(scope,revision,content(Optional.of(PredicateRef.property(VOLTAGE)),wrong),entities());
        assertTrue(report.violations().stream().anyMatch(v->v.code().equals("BUSINESS_UNIT_MISMATCH")));
        var missing=adapter.parse(parsed.functionalSyntax().replace(unit,""));
        assertFalse(validator.validate(scope,revision,content(Optional.of(PredicateRef.property(VOLTAGE)),missing),entities()).valid());
    }

    @Test
    void parsesPositiveAndNegativeObjectAndDataAssertions() {
        AssertionPayload object = adapter.parse(
                "ObjectPropertyAssertion(<" + HAS_PART + "> <" + DEVICE + "> <" + OTHER_DEVICE + ">)");
        AssertionPayload negativeObject = adapter.parse(
                "NegativeObjectPropertyAssertion(<" + HAS_PART + "> <" + DEVICE + "> <" + OTHER_DEVICE + ">)");
        AssertionPayload data = adapter.parse(
                "DataPropertyAssertion(<" + VOLTAGE + "> <" + DEVICE + "> \"0.026\"^^<" + XSD_DECIMAL + ">)");
        AssertionPayload negativeData = adapter.parse(
                "NegativeDataPropertyAssertion(<" + VOLTAGE + "> <" + DEVICE + "> \"0.026\"^^<" + XSD_DECIMAL + ">)");

        assertEquals(AssertionPayload.AssertionKind.POSITIVE_OBJECT_PROPERTY, object.kind());
        assertEquals(AssertionPayload.AssertionKind.NEGATIVE_OBJECT_PROPERTY, negativeObject.kind());
        assertEquals(AssertionPayload.AssertionKind.POSITIVE_DATA_PROPERTY, data.kind());
        assertEquals(AssertionPayload.AssertionKind.NEGATIVE_DATA_PROPERTY, negativeData.kind());
        assertEquals("0.026", data.literal().orElseThrow().lexicalValue());
        assertEquals(Set.of(DEVICE, OTHER_DEVICE, HAS_PART), object.signatureIris());
    }

    @Test
    void parsesComplexClassExpressionAndIndividualIdentityAssertions() {
        AssertionPayload classAssertion = adapter.parse(
                "ClassAssertion(ObjectIntersectionOf(<" + EQUIPMENT + "> ObjectSomeValuesFrom(<" + HAS_PART
                        + "> <" + EQUIPMENT + ">)) <" + DEVICE + ">)");
        AssertionPayload same = adapter.parse(
                "SameIndividual(<" + DEVICE + "> <" + OTHER_DEVICE + ">)");
        AssertionPayload different = adapter.parse(
                "DifferentIndividuals(<" + DEVICE + "> <" + OTHER_DEVICE + ">)");

        assertEquals(AssertionPayload.AssertionKind.CLASS_ASSERTION, classAssertion.kind());
        assertTrue(classAssertion.classExpressionFunctionalSyntax().orElseThrow().contains("ObjectIntersectionOf"));
        assertEquals(AssertionPayload.AssertionKind.SAME_INDIVIDUAL, same.kind());
        assertEquals(AssertionPayload.AssertionKind.DIFFERENT_INDIVIDUAL, different.kind());
        assertEquals(Optional.of(OTHER_DEVICE), same.relatedIndividualIri());
    }

    @Test
    void rejectsUnpinnedImportsAndUnsupportedOntologyWrappers() {
        IllegalArgumentException importError = assertThrows(IllegalArgumentException.class,
                () -> adapter.parse("Import(<urn:test:remote>)"));
        assertTrue(importError.getMessage().toLowerCase().contains("import"));
        assertThrows(IllegalArgumentException.class,
                () -> adapter.parse("Ontology(<urn:test:wrapped> ClassAssertion(<" + EQUIPMENT + "> <" + DEVICE + ">))"));
    }

    @Test
    void rejectsSpoofedDerivedIndexesDuringValidation() {
        String syntax = "DataPropertyAssertion(<" + VOLTAGE + "> <" + DEVICE + "> \"0.026\"^^<"
                + XSD_DECIMAL + ">)";
        AssertionPayload parsed = adapter.parse(syntax);
        AssertionPayload spoofed = new AssertionPayload(
                parsed.kind(), parsed.functionalSyntax(), parsed.signatureIris(), parsed.subjectIri(),
                Optional.of(HAS_PART), parsed.objectIri(), parsed.literal(),
                parsed.classExpressionFunctionalSyntax(), parsed.relatedIndividualIri());
        StatementContent candidate = content(Optional.of(PredicateRef.property(VOLTAGE)), spoofed);

        ValidationReport report = adapter.validate(ontology(), candidate, entities());

        assertFalse(report.valid());
        assertTrue(report.violations().stream().anyMatch(v -> v.code().equals("ASSERTION_INDEX_MISMATCH")));
    }

    @Test
    void rejectsObjectAssertionWhenPinnedOntologyDeclaresDataProperty() {
        OntologyRevision ontology = ontologyWithDeclaration("Declaration(DataProperty(<" + VOLTAGE + ">))");
        String syntax = "ObjectPropertyAssertion(<" + VOLTAGE + "> <" + DEVICE + "> <" + OTHER_DEVICE + ">)";
        AssertionPayload payload = adapter.parse(syntax);

        ValidationReport report = adapter.validate(ontology,
                content(Optional.of(PredicateRef.relation(VOLTAGE)), payload), entities());

        assertFalse(report.valid());
        assertTrue(report.violations().stream().anyMatch(v -> v.code().equals("PROPERTY_KIND_MISMATCH")));
    }

    @Test
    void acceptsOutOfToleranceNumericInputAsAValidAssertion() {
        OntologyRevision ontology = ontologyWithDeclaration("Declaration(DataProperty(<" + VOLTAGE + ">))");
        String syntax = "DataPropertyAssertion(<" + VOLTAGE + "> <" + DEVICE + "> \"0.026\"^^<"
                + XSD_DECIMAL + ">)";

        ValidationReport report = adapter.validate(ontology,
                content(Optional.of(PredicateRef.property(VOLTAGE)), adapter.parse(syntax)), entities());

        assertTrue(report.valid(), () -> "unexpected violations: " + report.violations());
    }

    @Test
    void rejectsAssertionsWhoseNamedIndividualsAreNotReferencedEntities() {
        String syntax = "ObjectPropertyAssertion(<" + HAS_PART + "> <" + DEVICE + "> <urn:test:unknown>)";

        ValidationReport report = adapter.validate(ontology(),
                content(Optional.of(PredicateRef.relation(HAS_PART)), adapter.parse(syntax)), entities());

        assertFalse(report.valid());
        assertTrue(report.violations().stream().anyMatch(v -> v.code().equals("UNKNOWN_ENTITY")));
    }

    @Test
    void remapsOnlyNamedIndividualsThroughTheAst() {
        String literalText = "urn:test:device-20";
        AssertionPayload data = adapter.remapIndividuals(
                "DataPropertyAssertion(<" + VOLTAGE + "> <" + DEVICE + "> \"" + literalText + "\")",
                Map.of(DEVICE, "urn:test:renamed-device", VOLTAGE, "urn:test:renamed-property",
                        EQUIPMENT, "urn:test:renamed-class"));
        assertEquals("urn:test:renamed-device", data.subjectIri().orElseThrow());
        assertEquals(Optional.of(VOLTAGE), data.predicateIri());
        assertEquals(literalText, data.literal().orElseThrow().lexicalValue());

        AssertionPayload punned = adapter.remapIndividuals(
                "ObjectPropertyAssertion(<" + DEVICE + "> <" + DEVICE + "> <" + OTHER_DEVICE + ">)",
                Map.of(DEVICE, "urn:test:renamed-individual", OTHER_DEVICE, "urn:test:renamed-other"));
        assertEquals(Optional.of(DEVICE), punned.predicateIri());
        assertEquals("urn:test:renamed-individual", punned.subjectIri().orElseThrow());
        assertEquals("urn:test:renamed-other", punned.objectIri().orElseThrow());
    }

    private StatementContent content(Optional<PredicateRef> predicate, AssertionPayload payload) {
        return new StatementContent(scope, new SemanticIds.OntologyRevisionId("10"),
                new SemanticIds.EntityId("20"), predicate, payload, Validity.unbounded(), Set.of());
    }

    private Map<SemanticIds.EntityId, Entity> entities() {
        return Map.of(
                new SemanticIds.EntityId("20"),
                new Entity(new SemanticIds.EntityId("20"), scope, DEVICE, Set.of(EQUIPMENT), "P-101"),
                new SemanticIds.EntityId("21"),
                new Entity(new SemanticIds.EntityId("21"), scope, OTHER_DEVICE, Set.of(EQUIPMENT), "P-102"));
    }

    private OntologyRevision ontology() {
        return ontologyWithDeclaration(
                "Declaration(Class(<" + EQUIPMENT + ">))\n"
                        + "Declaration(ObjectProperty(<" + HAS_PART + ">))\n"
                        + "Declaration(DataProperty(<" + VOLTAGE + ">))");
    }

    private OntologyRevision ontologyWithDeclaration(String declarations) {
        String text = "Ontology(<" + ROOT_IRI + ">\n" + declarations + "\n)";
        OntologyDocument document = OntologyDocument.fromText(
                "11", "10", ROOT_IRI, Optional.empty(), OntologyDocumentSyntax.FUNCTIONAL, text,
                vip.mate.semantic.core.ontology.LockedImport.digest(List.of()));
        ParsedOntologyDocument parsed = new ParsedOntologyDocument(
                document, ROOT_IRI, Optional.empty(), List.of(), List.of(), List.of(), List.of());
        return new OntologyRevision(new SemanticIds.OntologyRevisionId("10"),
                new SemanticIds.OntologyId("11"), 1, parsed,
                new BusinessPolicySet("policy-v1", List.of()));
    }
}

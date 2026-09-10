package vip.mate.semantic.core;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import vip.mate.semantic.core.evidence.Evidence;
import vip.mate.semantic.core.evidence.SourceSnapshot;
import vip.mate.semantic.core.fact.AssertionPayload;
import vip.mate.semantic.core.fact.Entity;
import vip.mate.semantic.core.fact.PredicateRef;
import vip.mate.semantic.core.fact.StatementContent;
import vip.mate.semantic.core.fact.StatementRevision;
import vip.mate.semantic.core.fact.Validity;
import vip.mate.semantic.core.identity.GraphScope;
import vip.mate.semantic.core.identity.SemanticIds;
import vip.mate.semantic.core.ontology.OntologyDocument;
import vip.mate.semantic.core.ontology.OntologyDocumentSyntax;
import vip.mate.semantic.core.ontology.OntologyRevision;
import vip.mate.semantic.core.ontology.ParsedOntologyDocument;
import vip.mate.semantic.core.policy.BusinessPolicySet;
import vip.mate.semantic.core.validation.ValidationReport;

public final class SemanticFixtures {

    public static final GraphScope SCOPE = new GraphScope(
            new SemanticIds.WorkspaceId("1"),
            new SemanticIds.KnowledgeBaseId("2"),
            new SemanticIds.GraphId("3"));
    public static final GraphScope OTHER_SCOPE = new GraphScope(
            new SemanticIds.WorkspaceId("1"),
            new SemanticIds.KnowledgeBaseId("2"),
            new SemanticIds.GraphId("4"));
    public static final String ONTOLOGY_IRI = "https://example.test/ontology/quality";
    public static final String ENTITY_IRI = "https://example.test/entity/20";
    public static final String OTHER_ENTITY_IRI = "https://example.test/entity/21";
    public static final String EQUIPMENT_IRI = "https://example.test/class/Equipment";
    public static final String VOLTAGE_IRI = "https://example.test/property/ratedVoltage";
    public static final String XSD_DECIMAL = "http://www.w3.org/2001/XMLSchema#decimal";

    private SemanticFixtures() {
    }

    public static OntologyRevision voltageOntology() {
        String text = "Prefix(:=<" + ONTOLOGY_IRI + "#>) Ontology(<" + ONTOLOGY_IRI
                + "> Declaration(Class(:Equipment)))";
        OntologyDocument document = OntologyDocument.fromText(
                "11", "10", ONTOLOGY_IRI, Optional.empty(), OntologyDocumentSyntax.FUNCTIONAL, text, "lock-digest");
        ParsedOntologyDocument parsed = new ParsedOntologyDocument(
                document, ONTOLOGY_IRI, Optional.empty(), List.of(), List.of(), List.of(), List.of());
        return new OntologyRevision(
                new SemanticIds.OntologyRevisionId("10"),
                new SemanticIds.OntologyId("11"),
                1,
                parsed,
                new BusinessPolicySet("policy-v1", List.of()));
    }

    public static StatementContent voltage(String value) {
        return voltage(SCOPE, value, Validity.unbounded(), false);
    }

    public static StatementContent negativeVoltage(String value) {
        return voltage(SCOPE, value, Validity.unbounded(), true);
    }

    public static StatementContent voltage(GraphScope scope, String value, Validity validity) {
        return voltage(scope, value, validity, false);
    }

    public static StatementContent voltage(GraphScope scope, String value, Validity validity, boolean negative) {
        var assertion = AssertionPayload.dataPropertyAssertion(
                "DataPropertyAssertion(" + VOLTAGE_IRI + " " + ENTITY_IRI + " \"" + value + "\")",
                ENTITY_IRI,
                VOLTAGE_IRI,
                new AssertionPayload.LiteralValue(value, XSD_DECIMAL),
                negative,
                Set.of(ENTITY_IRI, VOLTAGE_IRI));
        return new StatementContent(
                scope,
                voltageOntology().revisionId(),
                new SemanticIds.EntityId("20"),
                Optional.of(PredicateRef.property(VOLTAGE_IRI)),
                assertion,
                validity,
                Set.of(new SemanticIds.EvidenceId("30")));
    }

    public static StatementRevision acceptedVoltage(String value) {
        return new StatementRevision(
                new SemanticIds.StatementId("40"),
                1,
                voltage(value),
                StatementRevision.ReviewStatus.ACCEPTED);
    }

    public static SourceSnapshot snapshot(String text) {
        return new SourceSnapshot(new SemanticIds.SnapshotId("50"), SCOPE, text);
    }

    public static Evidence evidence(int startCodePoint, int endCodePoint, String quote) {
        return new Evidence(
                new SemanticIds.EvidenceId("60"),
                new SemanticIds.SnapshotId("50"),
                startCodePoint,
                endCodePoint,
                quote);
    }

    public static Map<SemanticIds.EntityId, Entity> equipmentEntity() {
        return Map.of(new SemanticIds.EntityId("20"),
                new Entity(new SemanticIds.EntityId("20"), SCOPE, ENTITY_IRI,
                        Set.of(EQUIPMENT_IRI), "P-101"));
    }

    public static Map<SemanticIds.EntityId, Entity> twoEntities() {
        return Map.of(
                new SemanticIds.EntityId("20"),
                new Entity(new SemanticIds.EntityId("20"), SCOPE, ENTITY_IRI,
                        Set.of(EQUIPMENT_IRI), "P-101"),
                new SemanticIds.EntityId("21"),
                new Entity(new SemanticIds.EntityId("21"), SCOPE, OTHER_ENTITY_IRI,
                        Set.of(EQUIPMENT_IRI), "P-102"));
    }

    public static Validity interval(String from, String to) {
        return Validity.interval(Instant.parse(from), Instant.parse(to));
    }

    public static ValidationReport noSemanticViolations(
            vip.mate.semantic.core.ontology.OntologyRevision ignored,
            StatementContent ignoredCandidate,
            Map<SemanticIds.EntityId, Entity> ignoredEntities) {
        return new ValidationReport(List.of());
    }
}

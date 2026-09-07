package vip.mate.semantic.core;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import vip.mate.semantic.core.fact.Entity;
import vip.mate.semantic.core.fact.PredicateRef;
import vip.mate.semantic.core.fact.StatementContent;
import vip.mate.semantic.core.fact.StatementRevision;
import vip.mate.semantic.core.fact.StatementValue;
import vip.mate.semantic.core.fact.Validity;
import vip.mate.semantic.core.identity.GraphScope;
import vip.mate.semantic.core.identity.SemanticIds;
import vip.mate.semantic.core.ontology.EntityTypeDefinition;
import vip.mate.semantic.core.ontology.Multiplicity;
import vip.mate.semantic.core.ontology.OntologyDefinition;
import vip.mate.semantic.core.ontology.OntologyRevision;
import vip.mate.semantic.core.ontology.PropertyDefinition;
import vip.mate.semantic.core.ontology.ValueType;
import vip.mate.semantic.core.evidence.Evidence;
import vip.mate.semantic.core.evidence.SourceSnapshot;

public final class SemanticFixtures {

    public static final GraphScope SCOPE = new GraphScope(
            new SemanticIds.WorkspaceId("1"),
            new SemanticIds.KnowledgeBaseId("2"),
            new SemanticIds.GraphId("3"));
    public static final GraphScope OTHER_SCOPE = new GraphScope(
            new SemanticIds.WorkspaceId("1"),
            new SemanticIds.KnowledgeBaseId("2"),
            new SemanticIds.GraphId("4"));

    private SemanticFixtures() {
    }

    public static OntologyRevision voltageOntology() {
        return new OntologyRevision(
                new SemanticIds.OntologyRevisionId("10"),
                new SemanticIds.OntologyId("11"),
                1,
                new OntologyDefinition(
                        List.of(new EntityTypeDefinition("Equipment", "设备", "")),
                        List.of(new PropertyDefinition(
                                "ratedVoltage", "额定电压", "", "Equipment",
                                ValueType.DECIMAL, Multiplicity.SINGLE, Optional.of("V"))),
                        List.of()));
    }

    public static StatementContent voltage(String value) {
        return voltage(SCOPE, value, Validity.unbounded());
    }

    public static StatementContent voltage(GraphScope scope, String value, Validity validity) {
        return new StatementContent(
                scope,
                voltageOntology().revisionId(),
                new SemanticIds.EntityId("20"),
                PredicateRef.property("ratedVoltage"),
                new StatementValue.DecimalValue(new BigDecimal(value), "V"),
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
                new Entity(new SemanticIds.EntityId("20"), SCOPE, "Equipment", "P-101"));
    }

    public static Validity interval(String from, String to) {
        return Validity.interval(Instant.parse(from), Instant.parse(to));
    }
}

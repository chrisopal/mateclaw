package vip.mate.semantic.reasoning;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import vip.mate.semantic.core.reasoning.ReasoningRequest.AssertionScope;
import vip.mate.semantic.core.reasoning.ReasoningRequest.TaskKind;
import vip.mate.semantic.core.reasoning.ReasoningResult;

/** Server-facing request and read-only result for bounded semantic reasoning. */
public final class SemanticReasoningDtos {
    private SemanticReasoningDtos() {}

    public record Request(
            AssertionScope scope,
            TaskKind task,
            String individualIri,
            String axiomFunctionalSyntax,
            Instant asOf) {}

    public record Result(
            String schema,
            String traceId,
            String graphId,
            long graphMutationVersion,
            String ontologyRevisionId,
            String ontologyDocumentDigest,
            String importLockDigest,
            AssertionScope scope,
            TaskKind task,
            String engineName,
            String engineVersion,
            String inputDigest,
            ReasoningResult.ReasoningStatus status,
            long durationMillis,
            List<ReasoningResult.ClassRelation> classRelations,
            List<ReasoningResult.IndividualTypes> individualTypes,
            List<FactProvenance> facts,
            List<String> diagnostics,
            String explanationStatus) {}

    public record FactProvenance(
            String factId,
            int revision,
            String snapshotId,
            Set<String> evidenceIds,
            String evidenceDigest) {}
}

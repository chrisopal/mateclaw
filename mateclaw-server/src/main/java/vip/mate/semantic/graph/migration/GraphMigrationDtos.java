package vip.mate.semantic.graph.migration;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import vip.mate.semantic.web.SemanticCounterSerializer;

import java.time.Instant;
import java.util.List;

/** HTTP contracts for a controlled OWL revision migration of one non-empty graph. */
public final class GraphMigrationDtos {
    private GraphMigrationDtos() {}

    public record IriMapping(String from, String to) {}

    public record PrepareRequest(
            String operationId,
            String sourceRevisionId,
            String targetRevisionId,
            Long expectedGraphVersion,
            List<IriMapping> classes,
            List<IriMapping> objectProperties,
            List<IriMapping> dataProperties,
            List<IriMapping> individuals) {}

    public record ApproveRequest(String operationId, String expectedPlanDigest) {}

    public record ExecuteRequest(String operationId, String expectedPlanDigest, Long expectedGraphVersion) {}

    public record RollbackRequest(String operationId, String expectedPlanDigest, Long expectedGraphVersion) {}

    public record TargetRevision(String id, int version, String name) {}

    public record Blocker(String code, String path, String message) {}

    public record EntityPreview(
            String entityId,
            String oldIri,
            String targetIri,
            List<String> oldTypes,
            List<String> targetTypes,
            List<Blocker> blockers) {}

    public record FactPreview(
            String statementId,
            int sourceRevision,
            String sourceAssertionText,
            String targetAssertionText,
            String status,
            List<Blocker> blockers) {}

    public record Impact(
            int entities,
            int facts,
            int changedEntities,
            int changedFacts,
            List<Blocker> blockers,
            boolean truncated) {}

    public record PlanView(
            String id,
            String graphId,
            String sourceRevisionId,
            String targetRevisionId,
            int sourceVersion,
            int targetVersion,
            String status,
            String planDigest,
            String sourceDocumentDigest,
            String targetDocumentDigest,
            String sourceImportLockDigest,
            String targetImportLockDigest,
            @JsonSerialize(using = SemanticCounterSerializer.class) long expectedGraphVersion,
            @JsonSerialize(using = SemanticCounterSerializer.class) long executedGraphVersion,
            Impact impact,
            List<EntityPreview> entities,
            List<FactPreview> facts,
            @JsonFormat(shape = JsonFormat.Shape.STRING) Instant createdAt,
            @JsonFormat(shape = JsonFormat.Shape.STRING) Instant approvedAt,
            @JsonFormat(shape = JsonFormat.Shape.STRING) Instant executedAt,
            @JsonFormat(shape = JsonFormat.Shape.STRING) Instant rolledBackAt) {}
}

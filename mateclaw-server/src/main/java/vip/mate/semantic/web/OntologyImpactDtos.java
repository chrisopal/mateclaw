package vip.mate.semantic.web;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.time.Instant;
import java.util.List;

public final class OntologyImpactDtos {
    private OntologyImpactDtos() {}

    public record Usage(
            String graphId,
            String kbId,
            String kbName,
            String ontologyRevisionId,
            int ontologyVersion,
            boolean enabled,
            @JsonSerialize(using = SemanticCounterSerializer.class) long graphVersion) {}

    public record UsagePage(
            List<Usage> items,
            @JsonSerialize(using = SemanticCounterSerializer.class) long total,
            int page,
            int pageSize) {}

    public record Request(String graphId, String targetRevisionId, Long expectedDraftVersion) {}

    public record Diagnostic(
            String kind,
            String id,
            Integer revision,
            String code,
            String message,
            String termKey) {}

    public record Report(
            String graphId,
            String sourceRevisionId,
            String targetRevisionId,
            @JsonSerialize(using = SemanticCounterSerializer.class) Long targetDraftVersion,
            String definitionDigest,
            @JsonSerialize(using = SemanticCounterSerializer.class) long graphVersion,
            @JsonFormat(shape = JsonFormat.Shape.STRING) Instant scannedAt,
            String definitionChangeClass,
            String dataConformance,
            int scannedEntities,
            int scannedStatements,
            int scannedProposals,
            int affectedEntities,
            int affectedStatements,
            int affectedProposals,
            boolean detailsTruncated,
            List<Diagnostic> diagnostics) {}
}

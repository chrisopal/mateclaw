package vip.mate.semantic.extraction;

import java.time.Instant;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import vip.mate.semantic.web.SemanticCounterSerializer;
import java.util.List;

/** HTTP-only records. Identifiers are strings; content matches the existing statement API. */
public final class ExtractionDtos {
    private ExtractionDtos() {}
    public record StartRequest(String sourceRef, String modelConfigId, String operationId) {}
    public record OperationRequest(String operationId) {}
    public record SubmitRequest(Long expectedVersion, String operationId) {}
    public record Quote(int startCodePoint, int endCodePoint, String exactQuote) {}
    public record EditRequest(Long expectedVersion, String operationId, String status,
            String subjectTypeKey, String subjectName, String subjectId,
            String predicateKind, String predicateKey, String valueType, String value, String unit,
            String targetTypeKey, String targetName, String targetEntityId,
            String validityKind, Instant validFrom, Instant validTo, List<Quote> quotes) {}
    public record SuggestionView(String id, @JsonSerialize(using=SemanticCounterSerializer.class) long version, String status,
            String subjectTypeKey, String subjectName, String subjectId,
            String predicateKind, String predicateKey, String valueType, String value, String unit,
            String targetTypeKey, String targetName, String targetEntityId,
            String validityKind, Instant validFrom, Instant validTo, List<Quote> quotes,
            List<String> diagnostics, String statementId, String pendingOperationId) {}
    public record TaskView(String id, String taskId, String status, @JsonSerialize(using=SemanticCounterSerializer.class) long version, String graphId,
            String snapshotId, String ontologyRevisionId, String modelName, int attempts,
            String errorCode, String errorMessage, String explanation, Instant createdAt, Instant updatedAt,
            String sourceRef, String sourceTitle, String sourceText, int totalChunks, int completedChunks,
            String traceId) {}
    public record SubmissionView(String statementId, int revision) {}
    public record ModelView(String id, String name) {}
    public record Capabilities(boolean enabled, List<ModelView> models, String ontologyRevisionId) {}
    public record Page<T>(List<T> items, @JsonSerialize(using=SemanticCounterSerializer.class) long total, int page, int pageSize) {}
}

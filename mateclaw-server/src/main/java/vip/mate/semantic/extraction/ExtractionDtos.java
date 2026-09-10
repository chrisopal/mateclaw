package vip.mate.semantic.extraction;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import vip.mate.semantic.web.SemanticCounterSerializer;

/** HTTP-only extraction records. OWL Functional Syntax is the assertion authority. */
public final class ExtractionDtos {
    private ExtractionDtos() {}

    public record StartRequest(String sourceRef, String modelConfigId, String operationId) {}
    public record OperationRequest(String operationId) {}
    public record SubmitRequest(Long expectedVersion, String operationId) {}
    public record Quote(int startCodePoint, int endCodePoint, String exactQuote) {}

    public record EditRequest(Long expectedVersion, String operationId, String status,
            String subjectIri, Set<String> subjectTypeIris, String subjectName, String subjectId,
            String assertionText,
            String targetIri, Set<String> targetTypeIris, String targetName, String targetEntityId,
            String validityKind, Instant validFrom, Instant validTo, List<Quote> quotes) {}

    public record SuggestionView(String id,
            @JsonSerialize(using = SemanticCounterSerializer.class) long version,
            String status,
            String subjectIri, Set<String> subjectTypeIris, String subjectName, String subjectId,
            String assertionText,
            String targetIri, Set<String> targetTypeIris, String targetName, String targetEntityId,
            String validityKind, Instant validFrom, Instant validTo, List<Quote> quotes,
            List<String> diagnostics, String statementId, String pendingOperationId) {}

    public record TaskView(String id, String taskId, String status,
            @JsonSerialize(using = SemanticCounterSerializer.class) long version, String graphId,
            String snapshotId, String ontologyRevisionId, String modelName, int attempts,
            String errorCode, String errorMessage, String explanation, Instant createdAt, Instant updatedAt,
            String sourceRef, String sourceTitle, String sourceText, int totalChunks, int completedChunks,
            String traceId) {}

    public record SubmissionView(String statementId, int revision) {}
    public record ModelView(String id, String name) {}
    public record Capabilities(boolean enabled, List<ModelView> models, String ontologyRevisionId) {}
    public record Page<T>(List<T> items, @JsonSerialize(using = SemanticCounterSerializer.class) long total,
            int page, int pageSize) {}
}

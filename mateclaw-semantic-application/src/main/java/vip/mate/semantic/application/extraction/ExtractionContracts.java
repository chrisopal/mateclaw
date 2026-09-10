package vip.mate.semantic.application.extraction;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import vip.mate.semantic.core.fact.*;
import vip.mate.semantic.core.ontology.OntologyRevision;
import vip.mate.semantic.core.validation.Violation;

/** Application-owned payloads. Model configuration must contain only non-sensitive parameters. */
public final class ExtractionContracts {
    private ExtractionContracts() {}
    public record Actor(String workspaceId, String userId) {}
    public record StartCommand(String graphId, String sourceRef, String modelConfigId, String operationId) {}
    public record TaskRef(String taskId, String status, long version) {}
    public record Chunk(int ordinal, int startCodePoint, String text) {}
    /** Half-open Unicode code point offsets, relative to the enclosing snapshot or model chunk. */
    public record Quote(int startCodePoint, int endCodePoint, String exactQuote) {}
    public record SubmitCommand(String suggestionId, long expectedVersion, String operationId) {}
    public record SubmissionRef(String statementId, int revision) {}
    public enum Action { START, READ, CANCEL, RETRY, EDIT, SUBMIT }
    public enum TaskStatus { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED }
    public enum SuggestionStatus { OPEN, SUBMITTED, IGNORED }
    public enum ErrorCategory { TIMEOUT, RATE_LIMIT, TEMPORARY_UNAVAILABLE, AUTHORIZATION, RULE, FORMAT, LIMIT, CANCELLED }
    public record SourceSnapshotInput(String snapshotId, String sourceRef, String contentHash,
                                      String text, Map<String, String> metadata) {
        public SourceSnapshotInput { metadata = Map.copyOf(metadata); }
    }
    public record ModelConfiguration(String modelConfigId, String modelName, String configurationHash,
                                     Map<String, String> parameters) {
        public ModelConfiguration { parameters = Map.copyOf(parameters); }
    }
    public record ObjectMention(String temporaryRef, java.util.Set<String> typeIris, String name) { public ObjectMention { typeIris = java.util.Set.copyOf(typeIris); } }
    /** Standard assertion with temporary individual IRIs; never a business identity match. */
    public record RawSuggestion(ObjectMention subject, AssertionPayload assertion,
                                ObjectMention target, Validity validity, List<Quote> quotes) {
        public RawSuggestion {
            validity = validity == null ? Validity.unknown() : validity;
            quotes = List.copyOf(quotes);
        }
    }
    public record Usage(long inputTokens, long outputTokens) {}
    public record ModelRequest(OntologyRevision ontology, Chunk chunk, ModelConfiguration configuration,
                               String promptVersion) {}
    public record ModelResult(List<RawSuggestion> suggestions, Usage usage, String explanation) {
        public ModelResult {
            SuggestionValidator.requireWithinLimit(suggestions.size());
            suggestions = List.copyOf(suggestions);
        }
    }
    public record Failure(ErrorCategory category, String code, String traceId) {}
    public record Task(String taskId, Actor actor, String graphId, String operationId, String requestHash,
                       SourceSnapshotInput source, OntologyRevision ontology, String definitionHash,
                       String promptVersion, ModelConfiguration configuration, TaskStatus status,
                       long version, boolean cancelRequested, Instant createdAt, Instant updatedAt) {}
    public record Lease(String taskId, String workerId, long generation, Instant expiresAt) {}
    public record Attempt(String attemptId, String taskId, int number, Lease lease,
                          ModelConfiguration configuration, Usage usage, Instant startedAt,
                          Instant finishedAt, Failure failure) {}
    public record Suggestion(String suggestionId, String taskId, String attemptId, RawSuggestion content,
                             StatementContent mappedContent, List<Violation> diagnostics,
                             long editVersion, SuggestionStatus status) {
        public Suggestion { diagnostics = List.copyOf(diagnostics); }
    }
    public record Receipt(String suggestionId, long editVersion, String operationId, String requestHash,
                          SubmissionRef submission) {}
}

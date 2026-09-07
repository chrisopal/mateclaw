package vip.mate.semantic.web;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.time.Instant;
import java.util.List;

public final class StatementDtos {
    private StatementDtos() {}
    public record ProposeRequest(
            String operationId, String subjectId, String predicateKind, String predicateKey,
            String valueType, String value, String unit, String targetEntityId,
            String validityKind, Instant validFrom, Instant validTo, List<String> evidenceIds) {}
    public record ChangeRequest(Integer expectedRevision, String operationId, ProposeRequest content) {}
    public record ReviewRequest(Integer expectedRevision, String action, String reason, String operationId) {}
    public record ConflictMember(String kind, String statementId, Integer revision) {
        public ConflictMember {
            kind = kind == null || kind.isBlank() ? "STATEMENT" : kind.toUpperCase(java.util.Locale.ROOT);
        }
        public ConflictMember(String statementId, Integer revision) { this("STATEMENT", statementId, revision); }
    }
    public record ResolveRequest(String winnerStatementId, List<ConflictMember> expectedMembers, String reason, String operationId) {}
    public record StatementView(
            String id, String graphId, int revision, String ontologyRevisionId, String subjectId,
            String predicateKind, String predicateKey, String valueType, String value, String unit,
            String targetEntityId, String validityKind,
            @JsonFormat(shape=JsonFormat.Shape.STRING) Instant validFrom,
            @JsonFormat(shape=JsonFormat.Shape.STRING) Instant validTo,
            String reviewStatus, String supportStatus, List<String> evidenceIds, String proposedBy,
            @JsonFormat(shape=JsonFormat.Shape.STRING) Instant createdAt) {}
    public record ChangeView(String id,String graphId,String targetStatementId,int expectedRevision,String status,Integer resultRevision,String proposedBy,@JsonFormat(shape=JsonFormat.Shape.STRING) Instant createdAt, ProposeRequest content) {}
    public record ConflictView(String id,String graphId,String kind,String status,ConflictMember left,ConflictMember right,String resolution) {}
    public record Page<T>(List<T> items,@JsonSerialize(using=SemanticCounterSerializer.class) long total,int page,int pageSize) {}
}

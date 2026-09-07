package vip.mate.semantic.web;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Instant;
import java.util.List;

public final class SourceDtos {
    private SourceDtos() {}

    public record ImportRequest(String sourceKind, String sourceRef, String operationId) {}
    public record RetryRequest(String operationId) {}
    public record EvidenceRequest(String operationId, Integer startCodePoint, Integer endCodePoint, String exactQuote) {}
    public record GovernanceRequest(String sourceKind, String sourceRef, String reason, String operationId) {}
    public record GovernanceResult(String resourceKind, String resourceRef, String state) {}
    public record ImportJob(String id, String graphId, String sourceKind, String sourceRef, String status,
            String snapshotId, int attempts, String errorMessage,
            @JsonFormat(shape=JsonFormat.Shape.STRING) Instant updatedAt) {}
    public record Snapshot(String id, String graphId, String sourceKind, String sourceRef, String sourceTitle,
            long captureVersion, String textDigest, @JsonFormat(shape=JsonFormat.Shape.STRING) Instant createdAt) {}
    public record SnapshotText(String id, String textDigest, String text) {}
    public record EvidenceView(String id, String snapshotId, int startCodePoint, int endCodePoint, String exactQuote) {}
    public record SourceView(String sourceKind, String sourceRef, String title, String state, long snapshotCount) {}
    public record Page<T>(List<T> items) {}
}

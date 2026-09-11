package vip.mate.semantic.sourcechanges;

import java.time.Instant;
import java.util.List;

public final class SourceChangeDtos {
    private SourceChangeDtos() {}

    public record ScanRequest(String operationId, String sourceKind, String sourceRef,
            Long expectedGraphVersion) {}

    public record ScanResult(String runId, String graphId, long graphMutationVersion,
            List<Change> changes) {}

    public record Change(String id, String sourceKind, String sourceRef,
            String oldSnapshotId, String newSnapshotId, String oldDigest, String newDigest,
            String sourceState, long observedGraphVersion, int affectedCount,
            Instant createdAt) {}

    public record ReviewItem(String id, String changeId, String graphId, String sourceKind,
            String sourceRef, String itemKind, String itemId, Long itemRevision,
            String oldSnapshotId, String newSnapshotId, String oldDigest, String newDigest,
            String sourceState, String reviewState, String decision, String reason,
            Long observedGraphVersion, Instant createdAt, Instant reviewedAt) {}

    public record FactSnapshot(String id,String digest,String text) {}
    public record FactRevisionPreview(ReviewItem item,vip.mate.semantic.web.StatementDtos.StatementView statement,
            FactSnapshot original,FactSnapshot observed) {}
    public record FactRevisionRequest(String expectedObservedDigest,Integer expectedRevision,
            String assertionText,String validityKind,Instant validFrom,Instant validTo,
            String exactQuote,Integer startCodePoint,Integer endCodePoint) {}

    public record DecideRequest(String operationId, String expectedObservedDigest,
            Long expectedGraphVersion, String decision, String reason) {}
}

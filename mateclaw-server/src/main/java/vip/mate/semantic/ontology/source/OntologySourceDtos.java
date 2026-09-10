package vip.mate.semantic.ontology.source;

import java.time.Instant;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import vip.mate.semantic.web.SemanticCounterSerializer;

public final class OntologySourceDtos {
    private OntologySourceDtos() {}
    public record BindRequest(Long expectedDraftVersion,String operationId,String axiomId,String knowledgeBaseId,
        String sourceRef,String expectedSourceDigest,int startCodePoint,int endCodePoint,String exactQuote,String origin) {}
    public record Binding(String id,String revisionId,String axiomId,String sourceSnapshotId,String knowledgeBaseId,
        String sourceRef,String sourceDigest,String exactQuote,int startCodePoint,int endCodePoint,String origin,
        String reviewState,String currentSourceState) {}
    public record Bound(@JsonSerialize(using=SemanticCounterSerializer.class) long draftVersion,Binding binding) {}
    public record ScanRequest(String operationId) {}
    public record Review(String id,String bindingId,String observedDigest,String sourceState,String reviewState,String decision,String reason,String observedSnapshotId) {}
    public record DecideRequest(String operationId,String expectedObservedDigest,String decision,String reason) {}
    public record Snapshot(String id,String knowledgeBaseId,String sourceRef,String sourceTitle,String sourceText,String sourceDigest,Instant capturedAt) {}
}

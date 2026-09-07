package vip.mate.semantic.query;

import vip.mate.semantic.web.StatementDtos.StatementView;

import java.time.Instant;
import java.util.List;

public final class SemanticQueryDtos {
    private SemanticQueryDtos() {}
    public record SearchRequest(String query,Integer limit,Instant atTime) {}
    public record SearchResult(List<StatementView> facts,String traceId,boolean truncated) {}
    public record Node(String id,String typeKey,String label,List<StatementView> properties) {}
    public record Edge(String statementId,String sourceId,String targetId,String predicateKey,int revision) {}
    public record GraphResult(List<Node> nodes,List<Edge> edges,String traceId,boolean truncated) {}
    public record EvidenceResult(String id,String snapshotId,String sourceKind,String sourceRef,String sourceTitle,String exactQuote,int startCodePoint,int endCodePoint,String textDigest) {}
    public record HistoryResult(String statementId,List<StatementView> revisions) {}
}

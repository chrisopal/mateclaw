package vip.mate.semantic.source.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Reads persisted governance facts even when semantic mutation features are disabled. */
@Repository
public class SourceGovernanceReadRepository {
    private final JdbcTemplate jdbc;

    public SourceGovernanceReadRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isWithdrawn(String graphId, String sourceId) {
        Integer count =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM mate_semantic_source_governance WHERE graph_id=? AND source_id=? AND state='WITHDRAWN'",
                        Integer.class,
                        graphId,
                        sourceId);
        return count != null && count > 0;
    }

    public java.util.Optional<String> availableEvidenceSource(
            String scope, String graphId, String kbId, String evidenceId) {
        long workspace, kb;
        try {
            workspace = Long.parseLong(scope);
            kb = Long.parseLong(kbId);
            if (workspace <= 0 || kb <= 0) return java.util.Optional.empty();
        } catch (NumberFormatException exception) {
            return java.util.Optional.empty();
        }
        var rows =
                jdbc.query(
                        """
                SELECT s.source_id,s.source_kind FROM mate_semantic_evidence e
                JOIN mate_semantic_source_snapshot s ON s.id=e.snapshot_id AND s.graph_id=e.graph_id
                JOIN mate_semantic_graph g ON g.id=e.graph_id
                JOIN mate_wiki_knowledge_base k ON k.id=g.kb_id
                WHERE e.id=? AND e.graph_id=? AND g.workspace_id=? AND g.kb_id=?
                AND k.workspace_id=? AND k.deleted=0 AND s.source_kind='WIKI_RAW'
                AND NOT EXISTS(SELECT 1 FROM mate_semantic_snapshot_exclusion x WHERE x.graph_id=e.graph_id AND x.snapshot_id=s.id)
                AND NOT EXISTS(SELECT 1 FROM mate_semantic_source_governance w WHERE w.graph_id=e.graph_id AND w.source_id=s.source_id AND w.state='WITHDRAWN')
                """,
                        (rs, n) -> new EvidenceSource(rs.getString(1), rs.getString(2)),
                        evidenceId,
                        graphId,
                        workspace,
                        kb,
                        workspace);
        if (rows.size() != 1) return java.util.Optional.empty();
        if (!"WIKI_RAW".equals(rows.getFirst().kind())) return java.util.Optional.empty();
        String source = rows.getFirst().id();
        long sourceId;
        try {
            sourceId = Long.parseLong(source);
        } catch (NumberFormatException exception) {
            return java.util.Optional.empty();
        }
        Integer count =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM mate_wiki_raw_material WHERE id=? AND kb_id=? AND deleted=0",
                        Integer.class,
                        sourceId,
                        kb);
        return count != null && count == 1
                ? java.util.Optional.of(source)
                : java.util.Optional.empty();
    }

    private record EvidenceSource(String id, String kind) {}
}

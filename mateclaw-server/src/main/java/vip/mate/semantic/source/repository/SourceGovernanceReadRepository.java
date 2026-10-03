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
}

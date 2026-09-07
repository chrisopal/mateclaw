package vip.mate.semantic.source;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnProperty(name = "mateclaw.semantic.enabled", havingValue = "true")
public class ImportJobRecovery {
    private static final Logger log = LoggerFactory.getLogger(ImportJobRecovery.class);
    private final JdbcTemplate jdbc;
    private final ImportJobRunner runner;

    public ImportJobRecovery(JdbcTemplate jdbc, ImportJobRunner runner) {
        this.jdbc = jdbc;
        this.runner = runner;
    }

    @Scheduled(initialDelayString = "${mateclaw.semantic.import-recovery-initial-delay-ms:15000}",
            fixedDelayString = "${mateclaw.semantic.import-recovery-delay-ms:30000}")
    public void recover() {
        List<RecoverableJob> jobs = jdbc.query(
                "SELECT j.id,j.graph_id,j.created_by,g.workspace_id FROM mate_semantic_import_job j JOIN mate_semantic_graph g ON g.id=j.graph_id WHERE j.status='QUEUED' OR (j.status='RUNNING' AND (j.lease_until IS NULL OR j.lease_until<CURRENT_TIMESTAMP)) ORDER BY j.updated_at LIMIT 10",
                (rs, n) -> new RecoverableJob(rs.getString("id"), rs.getString("graph_id"),
                        rs.getString("created_by"), rs.getString("workspace_id")));
        for (RecoverableJob job : jobs) {
            try {
                runner.runAsActor(job.workspaceId(), job.graphId(), job.id(), job.actorId());
            } catch (RuntimeException failure) {
                runner.fail(job.graphId(), job.id(), failure);
                log.warn("Semantic import recovery failed for job {}", job.id());
            }
        }
    }

    private record RecoverableJob(String id, String graphId, String actorId, String workspaceId) {}
}

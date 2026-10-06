package vip.mate.presales.repository;

import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Durable immutable render input and attempt fencing; the caller owns authorization and
 * transactions.
 */
@Repository
@ConditionalOnProperty(name = "mateclaw.presales.enabled", havingValue = "true")
public class PresalesRenderTaskRepository {
    private static final String COLUMNS =
            "task_id,workspace_id,actor_id,operation_id,project_id,expected_version,request_hash,"
                    + "attempt_id,attempt_no,status,input_json,lease_until,created_at,updated_at";
    private static final RowMapper<RenderTask> TASK_ROW =
            (rs, n) ->
                    new RenderTask(
                            rs.getString("task_id"),
                            rs.getString("workspace_id"),
                            rs.getString("actor_id"),
                            rs.getString("operation_id"),
                            rs.getString("project_id"),
                            rs.getLong("expected_version"),
                            rs.getString("request_hash"),
                            rs.getString("attempt_id"),
                            rs.getInt("attempt_no"),
                            rs.getString("status"),
                            rs.getString("input_json"),
                            rs.getTimestamp("lease_until").toLocalDateTime(),
                            rs.getTimestamp("created_at").toLocalDateTime(),
                            rs.getTimestamp("updated_at").toLocalDateTime());
    private final JdbcTemplate jdbc;

    public PresalesRenderTaskRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record RenderTask(
            String taskId,
            String workspaceId,
            String actorId,
            String operationId,
            String projectId,
            long expectedVersion,
            String requestHash,
            String attemptId,
            int attemptNo,
            String status,
            String inputJson,
            LocalDateTime leaseUntil,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {}

    /** Callers reserve command keys under the shared authority fence, checking receipts as well. */
    public Optional<RenderTask> findOperation(
            String workspaceId, String actorId, String operationId, boolean lock) {
        return jdbc
                .query(
                        "SELECT "
                                + COLUMNS
                                + " FROM mate_presales_render_task"
                                + " WHERE workspace_id=? AND actor_id=? AND operation_id=?"
                                + (lock ? " FOR UPDATE" : ""),
                        TASK_ROW,
                        workspaceId,
                        actorId,
                        operationId)
                .stream()
                .findFirst();
    }

    public Optional<RenderTask> findTask(String taskId, boolean lock) {
        return jdbc
                .query(
                        "SELECT "
                                + COLUMNS
                                + " FROM mate_presales_render_task WHERE task_id=?"
                                + (lock ? " FOR UPDATE" : ""),
                        TASK_ROW,
                        taskId)
                .stream()
                .findFirst();
    }

    public void insertTask(RenderTask task) {
        jdbc.update(
                "INSERT INTO mate_presales_render_task("
                        + COLUMNS
                        + ") VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                task.taskId(),
                task.workspaceId(),
                task.actorId(),
                task.operationId(),
                task.projectId(),
                task.expectedVersion(),
                task.requestHash(),
                task.attemptId(),
                task.attemptNo(),
                task.status(),
                task.inputJson(),
                task.leaseUntil(),
                task.createdAt(),
                task.updatedAt());
    }

    public int claim(
            String taskId,
            String oldAttemptId,
            String newAttemptId,
            LocalDateTime now,
            LocalDateTime leaseUntil) {
        if (newAttemptId == null || newAttemptId.isBlank() || newAttemptId.equals(oldAttemptId)) {
            throw new IllegalArgumentException("A claim requires a new attempt identity");
        }
        if (leaseUntil == null || !leaseUntil.isAfter(now)) {
            throw new IllegalArgumentException("A claim requires a future lease");
        }
        return jdbc.update(
                "UPDATE mate_presales_render_task"
                        + " SET attempt_id=?,attempt_no=attempt_no+1,status='RUNNING',lease_until=?,updated_at=?"
                        + " WHERE task_id=? AND attempt_id=? AND attempt_no<2147483647"
                        + " AND (status='FAILED' OR (status='RUNNING' AND lease_until<=?))",
                newAttemptId,
                leaseUntil,
                now,
                taskId,
                oldAttemptId,
                now);
    }

    public int finish(String taskId, String attemptId, String status, LocalDateTime now) {
        if (!"SUCCEEDED".equals(status) && !"FAILED".equals(status)) {
            throw new IllegalArgumentException("A render attempt must finish SUCCEEDED or FAILED");
        }
        return jdbc.update(
                "UPDATE mate_presales_render_task SET status=?,updated_at=?"
                        + " WHERE task_id=? AND attempt_id=? AND status='RUNNING'"
                        + ("SUCCEEDED".equals(status) ? " AND lease_until>?" : ""),
                "SUCCEEDED".equals(status)
                        ? new Object[] {status, now, taskId, attemptId, now}
                        : new Object[] {status, now, taskId, attemptId});
    }
}

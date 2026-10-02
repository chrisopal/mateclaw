package vip.mate.presales.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** SQL facts only. Callers own authorization, JSON encoding, errors and transaction boundaries. */
@Repository
@ConditionalOnProperty(name = "mateclaw.presales.enabled", havingValue = "true")
public class PresalesProjectRepository {
    private final JdbcTemplate jdbc;

    public PresalesProjectRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record ProjectRow(
            String id,
            String workspaceId,
            int version,
            String name,
            String status,
            String bodyJson) {}

    public record OperationReceipt(String requestHash, String responseJson) {}

    public List<String> listBodies(String scope) {
        return jdbc.query(
                "SELECT body_json FROM mate_presales_project WHERE workspace_id=? ORDER BY name,id",
                (row, n) -> row.getString(1),
                scope);
    }

    public Optional<String> findBody(String scope, String id, boolean lock) {
        return jdbc
                .query(
                        "SELECT body_json FROM mate_presales_project WHERE id=? AND workspace_id=?"
                                + (lock ? " FOR UPDATE" : ""),
                        (row, n) -> row.getString(1),
                        id,
                        scope)
                .stream()
                .findFirst();
    }

    public void insert(ProjectRow project) {
        jdbc.update(
                "INSERT INTO mate_presales_project(id,workspace_id,version,name,status,body_json) VALUES(?,?,?,?,?,?)",
                project.id(),
                project.workspaceId(),
                project.version(),
                project.name(),
                project.status(),
                project.bodyJson());
    }

    public int update(ProjectRow project, int expectedVersion) {
        return jdbc.update(
                "UPDATE mate_presales_project SET version=?,name=?,status=?,body_json=? WHERE id=? AND workspace_id=? AND version=?",
                project.version(),
                project.name(),
                project.status(),
                project.bodyJson(),
                project.id(),
                project.workspaceId(),
                expectedVersion);
    }

    public Optional<OperationReceipt> findReceipt(String scope, String actor, String operation) {
        return jdbc
                .query(
                        "SELECT request_hash,response_json FROM mate_presales_operation WHERE workspace_id=? AND actor_id=? AND operation_id=?",
                        (row, n) -> new OperationReceipt(row.getString(1), row.getString(2)),
                        scope,
                        actor,
                        operation)
                .stream()
                .findFirst();
    }

    public void insertReceipt(
            String scope, String actor, String operation, String hash, String responseJson) {
        jdbc.update(
                "INSERT INTO mate_presales_operation(workspace_id,actor_id,operation_id,request_hash,response_json) VALUES(?,?,?,?,?)",
                scope,
                actor,
                operation,
                hash,
                responseJson);
    }

    public void insertRevision(
            String projectId,
            int version,
            String actor,
            String action,
            String bodyJson,
            LocalDateTime createdAt) {
        jdbc.update(
                "INSERT INTO mate_presales_revision(project_id,version,actor_id,action,body_json,created_at) VALUES(?,?,?,?,?,?)",
                projectId,
                version,
                actor,
                action,
                bodyJson,
                createdAt);
    }
}

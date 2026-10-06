package vip.mate.delivery.repository;

import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DeliveryHandoffRepository {
    private final JdbcTemplate jdbc;

    public DeliveryHandoffRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Row(
            String id,
            String workspaceId,
            String actorId,
            String operationId,
            String projectId,
            String releaseId,
            String digest,
            String snapshotJson) {}

    private static final String COLUMNS =
            "id,workspace_id,actor_id,operation_id,project_id,release_id,digest,snapshot_json";

    public Optional<Row> findOperation(String workspace, String actor, String operation) {
        return find(
                "workspace_id=? AND actor_id=? AND operation_id=?", workspace, actor, operation);
    }

    public Optional<Row> findById(String workspace, String id) {
        return find("workspace_id=? AND id=?", workspace, id);
    }

    private Optional<Row> find(String predicate, Object... args) {
        return jdbc
                .query(
                        "SELECT " + COLUMNS + " FROM mate_delivery_handoff WHERE " + predicate,
                        (r, n) ->
                                new Row(
                                        r.getString(1),
                                        r.getString(2),
                                        r.getString(3),
                                        r.getString(4),
                                        r.getString(5),
                                        r.getString(6),
                                        r.getString(7),
                                        r.getString(8)),
                        args)
                .stream()
                .findFirst();
    }

    public void insert(Row row) {
        jdbc.update(
                "INSERT INTO mate_delivery_handoff (" + COLUMNS + ") VALUES (?,?,?,?,?,?,?,?)",
                row.id(),
                row.workspaceId(),
                row.actorId(),
                row.operationId(),
                row.projectId(),
                row.releaseId(),
                row.digest(),
                row.snapshotJson());
    }
}

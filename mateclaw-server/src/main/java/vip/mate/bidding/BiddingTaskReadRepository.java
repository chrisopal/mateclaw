package vip.mate.bidding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Read-only task/project state used to revalidate task-detail visibility. */
@Repository
public class BiddingTaskReadRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public BiddingTaskReadRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public TaskAccess findAccess(String workspaceId, String projectId, String taskId) {
        return jdbc.query(
                "SELECT t.agent_id,p.body_json FROM mate_bidding_task t "
                        + "LEFT JOIN mate_bidding_project p ON p.workspace_id=t.workspace_id AND p.id=t.project_id "
                        + "WHERE t.workspace_id=? AND t.project_id=? AND t.id=?",
                rs ->
                        rs.next()
                                ? new TaskAccess(rs.getString(1), readProject(rs.getString(2)))
                                : null,
                workspaceId,
                projectId,
                taskId);
    }

    private JsonNode readProject(String body) {
        if (body == null) return null;
        try {
            return json.readTree(body);
        } catch (Exception invalid) {
            throw new IllegalStateException("Invalid bidding project state", invalid);
        }
    }

    public record TaskAccess(String agentId, JsonNode project) {}
}

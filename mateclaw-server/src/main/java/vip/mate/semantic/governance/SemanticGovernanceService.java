package vip.mate.semantic.governance;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import vip.mate.semantic.graph.GraphApplicationService;
import vip.mate.semantic.graph.GraphRow;
import vip.mate.semantic.security.SemanticAccessService;
import vip.mate.semantic.web.SemanticApiException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Append-only governance history shared by source, fact, and conflict mutations.
 *
 * <p>The write method deliberately requires an existing transaction. A governance event is
 * part of the mutation it describes and must not survive a failed mutation or be committed by
 * an independent transaction.
 */
@Service
@ConditionalOnProperty(name = "mateclaw.semantic.enabled", havingValue = "true")
public class SemanticGovernanceService {
    private final JdbcTemplate jdbc;
    private final GraphApplicationService graphs;
    private final SemanticAccessService access;

    public SemanticGovernanceService(
            JdbcTemplate jdbc, GraphApplicationService graphs, SemanticAccessService access) {
        this.jdbc = jdbc;
        this.graphs = graphs;
        this.access = access;
    }

    /**
     * Append one immutable governance event to the active transaction.
     *
     * @param graphId graph containing the governed resource
     * @param workspaceId owning workspace, retained to make cross-workspace readback explicit
     * @param resourceKind stable resource family, for example SOURCE, SNAPSHOT, STATEMENT, or CONFLICT
     * @param resourceId stable resource identifier
     * @param resourceVersion resource revision when the resource has one; null for versionless resources
     * @param action business action performed
     * @param operationId idempotent operation identifier of the mutation
     * @param actorId authenticated actor identifier
     * @param reason human supplied governance reason
     * @param resultJson serialized mutation result
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(
            String graphId,
            long workspaceId,
            String resourceKind,
            String resourceId,
            Integer resourceVersion,
            String action,
            String operationId,
            String actorId,
            String reason,
            String resultJson) {
        requireText(graphId, "graphId");
        requirePositive(workspaceId, "workspaceId");
        requireText(resourceKind, "resourceKind");
        requireText(resourceId, "resourceId");
        requireText(action, "action");
        requireText(operationId, "operationId");
        requireText(actorId, "actorId");
        requireText(reason, "reason");
        if (reason.codePointCount(0, reason.length()) > 1000) {
            throw new vip.mate.semantic.web.SemanticApiException(400, "INVALID_REQUEST", "Governance reason must not exceed 1000 characters");
        }
        requireText(resultJson, "resultJson");
        if (resourceVersion != null && resourceVersion < 0) {
            throw new IllegalArgumentException("resourceVersion must be non-negative");
        }
        jdbc.update(
                "INSERT INTO mate_semantic_governance_event"
                        + "(id,workspace_id,graph_id,resource_kind,resource_id,resource_version,action,operation_id,actor_id,reason,result_json,created_at)"
                        + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                id(),
                workspaceId,
                graphId,
                resourceKind,
                resourceId,
                resourceVersion,
                action,
                operationId,
                actorId,
                reason,
                resultJson,
                now());
    }

    /**
     * Read governance history only through a live graph owned by the requested workspace and an
     * authenticated workspace administrator.
     */
    @Transactional(readOnly = true)
    public GovernancePage read(
            String scope,
            String graphId,
            String resourceKind,
            String resourceId,
            int page,
            int pageSize) {
        access.require(scope, "admin");
        GraphRow graph = graphs.requireGraph(scope, graphId, false);
        if (page < 1 || pageSize < 1 || pageSize > 100) {
            throw new SemanticApiException(400, "INVALID_REQUEST", "Invalid pagination");
        }
        if (resourceKind != null && resourceKind.isBlank()) resourceKind = null;
        if (resourceId != null && resourceId.isBlank()) resourceId = null;

        String filter = " WHERE graph_id=? AND workspace_id=?";
        Object[] filterArgs;
        if (resourceKind != null && resourceId != null) {
            filter += " AND resource_kind=? AND resource_id=?";
            filterArgs = new Object[] {graphId, graph.getWorkspaceId(), resourceKind, resourceId};
        } else if (resourceKind != null) {
            filter += " AND resource_kind=?";
            filterArgs = new Object[] {graphId, graph.getWorkspaceId(), resourceKind};
        } else if (resourceId != null) {
            filter += " AND resource_id=?";
            filterArgs = new Object[] {graphId, graph.getWorkspaceId(), resourceId};
        } else {
            filterArgs = new Object[] {graphId, graph.getWorkspaceId()};
        }
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM mate_semantic_governance_event" + filter,
                Long.class,
                filterArgs);
        Object[] pageArgs = new Object[filterArgs.length + 2];
        System.arraycopy(filterArgs, 0, pageArgs, 0, filterArgs.length);
        pageArgs[filterArgs.length] = pageSize;
        pageArgs[filterArgs.length + 1] = (page - 1) * pageSize;
        List<GovernanceEvent> items = jdbc.query(
                "SELECT id,workspace_id,graph_id,resource_kind,resource_id,resource_version,action,operation_id,actor_id,reason,result_json,created_at"
                        + " FROM mate_semantic_governance_event"
                        + filter
                        + " ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",
                (rs, rowNum) -> event(rs),
                pageArgs);
        return new GovernancePage(items, total == null ? 0 : total, page, pageSize);
    }

    public record GovernancePage(List<GovernanceEvent> items, long total, int page, int pageSize) {}

    public record GovernanceEvent(
            String id,
            long workspaceId,
            String graphId,
            String resourceKind,
            String resourceId,
            Integer resourceVersion,
            String action,
            String operationId,
            String actorId,
            String reason,
            String resultJson,
            LocalDateTime createdAt) {}

    private static GovernanceEvent event(ResultSet rs) throws SQLException {
        Number version = (Number) rs.getObject("resource_version");
        Integer resourceVersion = version == null ? null : Math.toIntExact(version.longValue());
        return new GovernanceEvent(
                rs.getString("id"),
                rs.getLong("workspace_id"),
                rs.getString("graph_id"),
                rs.getString("resource_kind"),
                rs.getString("resource_id"),
                resourceVersion,
                rs.getString("action"),
                rs.getString("operation_id"),
                rs.getString("actor_id"),
                rs.getString("reason"),
                rs.getString("result_json"),
                rs.getTimestamp("created_at").toLocalDateTime());
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
    }

    private static void requirePositive(long value, String name) {
        if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
    }

    private static String id() {
        return com.baomidou.mybatisplus.core.toolkit.IdWorker.getIdStr();
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(java.time.ZoneOffset.UTC)
                .truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }
}

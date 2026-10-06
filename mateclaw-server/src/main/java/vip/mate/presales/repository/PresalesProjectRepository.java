package vip.mate.presales.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.presales.PresalesListingProjectionV1.Projection;

/** SQL persistence and storage encoding. Callers own authorization and transaction boundaries. */
@Repository
@ConditionalOnProperty(name = "mateclaw.presales.enabled", havingValue = "true")
public class PresalesProjectRepository {
    private final JdbcTemplate jdbc;
    private final PresalesObjectStorage objects;

    public PresalesProjectRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.objects = new PresalesObjectStorage(jdbc);
    }

    public record ProjectRow(
            String id,
            String workspaceId,
            long version,
            String name,
            String status,
            String bodyJson,
            Projection listing) {
        public ProjectRow(
                String id,
                String workspaceId,
                long version,
                String name,
                String status,
                String bodyJson) {
            this(id, workspaceId, version, name, status, bodyJson, null);
        }
    }

    public record OperationReceipt(String requestHash, String responseJson) {}

    /**
     * Immutable original task bytes. Authorization and transaction ownership remain in the service.
     */
    public record TaskPackageRow(
            String workspaceId,
            String projectId,
            String taskId,
            String runId,
            String actorId,
            String employeeId,
            String packageDigest,
            String bodyJson) {}

    public void insertTaskPackage(TaskPackageRow row) {
        jdbc.update(
                "INSERT INTO mate_presales_task_package"
                        + " (workspace_id,project_id,task_id,run_id,actor_id,employee_id,package_digest,body_json)"
                        + " VALUES(?,?,?,?,?,?,?,?)",
                row.workspaceId(),
                row.projectId(),
                row.taskId(),
                row.runId(),
                row.actorId(),
                row.employeeId(),
                row.packageDigest(),
                row.bodyJson());
    }

    public Optional<TaskPackageRow> findTaskPackage(
            String scope, String project, String task, String run) {
        return jdbc
                .query(
                        "SELECT workspace_id,project_id,task_id,run_id,actor_id,employee_id,package_digest,body_json"
                                + " FROM mate_presales_task_package WHERE workspace_id=? AND project_id=? AND task_id=? AND run_id=?",
                        (rs, n) ->
                                new TaskPackageRow(
                                        rs.getString(1),
                                        rs.getString(2),
                                        rs.getString(3),
                                        rs.getString(4),
                                        rs.getString(5),
                                        rs.getString(6),
                                        rs.getString(7),
                                        rs.getString(8)),
                        scope,
                        project,
                        task,
                        run)
                .stream()
                .findFirst();
    }

    private record StoredBody(String id, String body) {}

    public List<String> listBodies(String scope) {
        return jdbc
                .query(
                        "SELECT id,body_json FROM mate_presales_project WHERE workspace_id=? ORDER BY name,id",
                        (row, n) -> new StoredBody(row.getString(1), row.getString(2)),
                        scope)
                .stream()
                .map(row -> objects.expand(scope, row.id(), row.body()))
                .toList();
    }

    /** Server restart inspection; callers own recovery eligibility and transitions. */
    public List<ProjectRow> listRuntimeRows() {
        return jdbc
                .query(
                        "SELECT id,workspace_id,version,name,status,body_json FROM mate_presales_project",
                        PresalesProjectRepository::projectRow)
                .stream()
                .map(this::expandedRow)
                .toList();
    }

    public Optional<ProjectRow> findRuntimeRow(String scope, String id) {
        return jdbc
                .query(
                        "SELECT id,workspace_id,version,name,status,body_json FROM mate_presales_project WHERE id=? AND workspace_id=?",
                        PresalesProjectRepository::projectRow,
                        id,
                        scope)
                .stream()
                .findFirst()
                .map(this::expandedRow);
    }

    private static final String LISTING_SET =
            "listing_contract=?,listing_project_version=?,listing_name_key=?,listing_customer_key=?,"
                    + "listing_status_key=?,listing_owner_key=?,listing_stage_key=?,listing_summary_json=?,"
                    + "listing_decode_failure=?,listing_stage_failure=?,listing_summary_failure=?";

    /**
     * Runtime CAS preserves name/status and atomically persists object revisions. REQUIRED joins a
     * caller transaction, or supplies a short transaction for background recovery/failure cleanup.
     */
    @Transactional
    public int updateRuntimeBody(
            String scope,
            String id,
            long expectedVersion,
            long nextVersion,
            String bodyJson,
            Projection listing) {
        String storedBody = prepareUpdate(scope, id, expectedVersion, bodyJson);
        if (storedBody == null) return 0;
        return jdbc.update(
                "UPDATE mate_presales_project SET body_json=?,version=?,"
                        + LISTING_SET
                        + " WHERE id=? AND workspace_id=? AND version=?",
                parameters(
                        new Object[] {storedBody, nextVersion},
                        listingArguments(listing, nextVersion),
                        new Object[] {id, scope, expectedVersion}));
    }

    private static Object[] listingArguments(Projection p, long version) {
        if (p == null) return new Object[11];
        return new Object[] {
            p.contractVersion(),
            version,
            p.nameKey(),
            p.customerKey(),
            p.statusKey(),
            p.ownerKey(),
            p.stageKey(),
            p.summaryJson(),
            p.decodeFailure(),
            p.stageFailure(),
            p.summaryFailure()
        };
    }

    private static Object[] parameters(Object[]... parts) {
        var out = new ArrayList<Object>();
        for (Object[] part : parts) out.addAll(Arrays.asList(part));
        return out.toArray();
    }

    private static ProjectRow projectRow(ResultSet row, int index) throws SQLException {
        return new ProjectRow(
                row.getString("id"),
                row.getString("workspace_id"),
                row.getLong("version"),
                row.getString("name"),
                row.getString("status"),
                row.getString("body_json"));
    }

    private ProjectRow expandedRow(ProjectRow row) {
        return new ProjectRow(
                row.id(),
                row.workspaceId(),
                row.version(),
                row.name(),
                row.status(),
                objects.expand(row.workspaceId(), row.id(), row.bodyJson()),
                row.listing());
    }

    private String prepareUpdate(String scope, String id, long expected, String body) {
        if (PresalesObjectCodec.isV2(body)) {
            if (!objects.lockVersion(scope, id, expected)) return null;
            return objects.persist(scope, id, body);
        }
        var existing =
                jdbc.queryForList(
                        "SELECT body_json FROM mate_presales_project WHERE workspace_id=? AND id=? FOR UPDATE",
                        String.class,
                        scope,
                        id);
        if (!existing.isEmpty() && PresalesObjectCodec.isV2(existing.getFirst()))
            throw new IllegalStateException("V2 project cannot fall back to aggregate storage");
        return body;
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
                .findFirst()
                .map(body -> objects.expand(scope, id, body, lock));
    }

    public void insert(ProjectRow project) {
        String storedBody =
                PresalesObjectCodec.isV2(project.bodyJson())
                        ? objects.persist(project.workspaceId(), project.id(), project.bodyJson())
                        : project.bodyJson();
        jdbc.update(
                "INSERT INTO mate_presales_project(id,workspace_id,version,name,status,body_json,"
                        + "listing_contract,listing_project_version,listing_name_key,listing_customer_key,"
                        + "listing_status_key,listing_owner_key,listing_stage_key,listing_summary_json,"
                        + "listing_decode_failure,listing_stage_failure,listing_summary_failure) VALUES(?,?,?,?,?,?,"
                        + "?,?,?,?,?,?,?,?,?,?,?)",
                parameters(
                        new Object[] {
                            project.id(),
                            project.workspaceId(),
                            project.version(),
                            project.name(),
                            project.status(),
                            storedBody
                        },
                        listingArguments(project.listing(), project.version())));
    }

    public int update(ProjectRow project, long expectedVersion) {
        String storedBody =
                prepareUpdate(
                        project.workspaceId(), project.id(), expectedVersion, project.bodyJson());
        if (storedBody == null) return 0;
        return jdbc.update(
                "UPDATE mate_presales_project SET version=?,name=?,status=?,body_json=?,"
                        + LISTING_SET
                        + " WHERE id=? AND workspace_id=? AND version=?",
                parameters(
                        new Object[] {
                            project.version(), project.name(), project.status(), storedBody
                        },
                        listingArguments(project.listing(), project.version()),
                        new Object[] {project.id(), project.workspaceId(), expectedVersion}));
    }

    /** Pre-encoded application facts; no JSON interpretation, normalization or authority here. */
    public record ListingQuery(
            String queryKey,
            String statusKey,
            String ownerKey,
            String stageKey,
            long offset,
            int size,
            int contract) {}

    public record ListingPage(
            List<String> summaries, long total, String failureType, String failureDetail) {}

    private record Predicate(String sql, Object[] arguments) {}

    private static Predicate filters(ListingQuery query, boolean includeStage) {
        var sql = new StringBuilder("1=1");
        var arguments = new ArrayList<Object>();
        if (query.queryKey() != null) {
            sql.append(" AND (listing_name_key LIKE ? OR listing_customer_key LIKE ?)");
            String pattern = "%" + query.queryKey() + "%";
            arguments.add(pattern);
            arguments.add(pattern);
        }
        if (query.statusKey() != null) {
            sql.append(" AND listing_status_key=?");
            arguments.add(query.statusKey());
        }
        if (query.ownerKey() != null) {
            sql.append(" AND listing_owner_key=?");
            arguments.add(query.ownerKey());
        }
        if (includeStage && query.stageKey() != null) {
            sql.append(" AND listing_stage_key=?");
            arguments.add(query.stageKey());
        }
        return new Predicate(sql.toString(), arguments.toArray());
    }

    /** One statement binds count, earliest eligible fault and page to the same database read. */
    public ListingPage listProjected(String scope, ListingQuery query) {
        var matching = filters(query, true);
        var preceding = filters(query, false);
        String missing =
                "(listing_contract IS NULL OR listing_contract<>? OR listing_project_version IS NULL OR "
                        + "listing_project_version<>version OR (listing_decode_failure IS NULL AND (listing_name_key "
                        + "IS NULL OR listing_customer_key IS NULL OR listing_status_key IS NULL OR "
                        + "listing_owner_key IS NULL OR (listing_stage_key IS NULL AND listing_stage_failure IS "
                        + "NULL) OR (listing_summary_json IS NULL AND listing_stage_failure IS NULL AND "
                        + "listing_summary_failure IS NULL))))";
        String later =
                "(listing_stage_failure IS NOT NULL OR "
                        + (query.stageKey() == null
                                ? "listing_summary_failure IS NOT NULL"
                                : "(listing_stage_key=? AND listing_summary_failure IS NOT NULL)")
                        + ")";
        String sql =
                "SELECT t.total_count,f.failure_type,f.failure_detail,p.page_id,p.summary_json FROM "
                        + "(SELECT COUNT(*) AS total_count FROM mate_presales_project WHERE workspace_id=? AND "
                        + matching.sql()
                        + ") t LEFT JOIN "
                        + "(SELECT CASE WHEN "
                        + missing
                        + " THEN 'NOT_READY' WHEN listing_decode_failure IS NOT NULL THEN 'DECODE' WHEN listing_stage_failure IS NOT NULL THEN 'STAGE' ELSE 'SUMMARY' END AS failure_type,"
                        + "COALESCE(listing_decode_failure,listing_stage_failure,listing_summary_failure) AS failure_detail FROM mate_presales_project WHERE workspace_id=? AND ("
                        + missing
                        + " OR listing_decode_failure IS NOT NULL OR ("
                        + preceding.sql()
                        + " AND "
                        + later
                        + ")) ORDER BY name,id LIMIT 1) f ON 1=1 LEFT JOIN "
                        + "(SELECT id AS page_id,name AS order_name,id AS order_id,listing_summary_json AS summary_json FROM mate_presales_project WHERE workspace_id=? AND "
                        + matching.sql()
                        + " ORDER BY name,id LIMIT ? OFFSET ?) p ON 1=1 ORDER BY p.order_name,p.order_id";
        Object[] args =
                parameters(
                        new Object[] {scope},
                        matching.arguments(),
                        new Object[] {query.contract(), scope, query.contract()},
                        preceding.arguments(),
                        query.stageKey() == null ? new Object[0] : new Object[] {query.stageKey()},
                        new Object[] {scope},
                        matching.arguments(),
                        new Object[] {query.size(), query.offset()});
        return jdbc.query(
                sql,
                (org.springframework.jdbc.core.ResultSetExtractor<ListingPage>)
                        rows -> {
                            var summaries = new ArrayList<String>();
                            long total = 0;
                            String type = null, detail = null;
                            while (rows.next()) {
                                total = rows.getLong("total_count");
                                type = rows.getString("failure_type");
                                detail = rows.getString("failure_detail");
                                if (rows.getString("page_id") != null)
                                    summaries.add(rows.getString("summary_json"));
                            }
                            return new ListingPage(summaries, total, type, detail);
                        },
                args);
    }

    public Optional<OperationReceipt> findReceipt(String scope, String actor, String operation) {
        return jdbc
                .query(
                        "SELECT request_hash,response_json FROM mate_presales_operation WHERE workspace_id=? AND actor_id=? AND operation_id=? FOR UPDATE",
                        (row, n) -> new OperationReceipt(row.getString(1), row.getString(2)),
                        scope,
                        actor,
                        operation)
                .stream()
                .findFirst()
                .map(
                        receipt ->
                                new OperationReceipt(
                                        receipt.requestHash(),
                                        objects.expandSnapshot(scope, receipt.responseJson())));
    }

    public void insertReceipt(
            String scope, String actor, String operation, String hash, String responseJson) {
        String storedResponse =
                PresalesObjectCodec.isV2(responseJson)
                        ? objects.snapshot(
                                scope,
                                PresalesObjectCodec.id(PresalesObjectCodec.object(responseJson)),
                                responseJson)
                        : responseJson;
        jdbc.update(
                "INSERT INTO mate_presales_operation(workspace_id,actor_id,operation_id,request_hash,response_json) VALUES(?,?,?,?,?)",
                scope,
                actor,
                operation,
                hash,
                storedResponse);
    }

    public void insertRevision(
            String projectId,
            long version,
            String actor,
            String action,
            String bodyJson,
            LocalDateTime createdAt) {
        String storedBody =
                PresalesObjectCodec.isV2(bodyJson)
                        ? objects.snapshot(
                                PresalesObjectCodec.object(bodyJson).path("workspaceId").asText(),
                                projectId,
                                bodyJson)
                        : bodyJson;
        jdbc.update(
                "INSERT INTO mate_presales_revision(project_id,version,actor_id,action,body_json,created_at) VALUES(?,?,?,?,?,?)",
                projectId,
                version,
                actor,
                action,
                storedBody,
                createdAt);
    }

    /**
     * Workspace-scoped historical read resolves the original immutable object revision references.
     */
    public Optional<String> findRevision(String scope, String projectId, long version) {
        return jdbc
                .query(
                        "SELECT r.body_json FROM mate_presales_revision r JOIN mate_presales_project p ON p.id=r.project_id WHERE p.workspace_id=? AND r.project_id=? AND r.version=? FOR UPDATE",
                        (row, n) -> row.getString(1),
                        scope,
                        projectId,
                        version)
                .stream()
                .findFirst()
                .map(body -> objects.expand(scope, projectId, body, true));
    }
}

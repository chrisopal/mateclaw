package vip.mate.semantic.source;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import vip.mate.semantic.graph.GraphApplicationService;
import vip.mate.semantic.graph.repository.GraphMapper;
import vip.mate.semantic.graph.GraphRow;
import vip.mate.semantic.security.SemanticAccessService;
import vip.mate.semantic.web.SemanticApiException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
@ConditionalOnProperty(name = "mateclaw.semantic.enabled", havingValue = "true")
public class ImportJobRunner {
    private static final int MAX_TEXT_BYTES = 2 * 1024 * 1024;
    private final JdbcTemplate jdbc;
    private final GraphApplicationService graphs;
    private final GraphMapper graphMapper;
    private final SemanticAccessService access;

    public ImportJobRunner(JdbcTemplate jdbc, GraphApplicationService graphs, GraphMapper graphMapper,
            SemanticAccessService access) {
        this.jdbc = jdbc;
        this.graphs = graphs;
        this.graphMapper = graphMapper;
        this.access = access;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void run(String scope, String graphId, String jobId) {
        access.require(scope, "member");
        execute(scope, graphId, jobId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void runAsActor(String scope, String graphId, String jobId, String actorId) {
        access.requireActor(scope, actorId, "member");
        execute(scope, graphId, jobId);
    }

    private void execute(String scope, String graphId, String jobId) {
        GraphRow graph = graphs.requireGraph(scope, graphId, true);
        List<Job> rows = jdbc.query(
                "SELECT id,source_kind,source_id,status,created_by,lease_until FROM mate_semantic_import_job WHERE id=? AND graph_id=? FOR UPDATE",
                (rs, n) -> new Job(rs.getString("id"), rs.getString("source_kind"), rs.getString("source_id"),
                        rs.getString("status"), rs.getString("created_by"), rs.getTimestamp("lease_until") == null ? null : rs.getTimestamp("lease_until").toLocalDateTime()),
                jobId, graphId);
        if (rows.isEmpty()) throw new SemanticApiException(404, "NOT_FOUND", "Import job not found");
        Job job = rows.getFirst();
        if ("SUCCEEDED".equals(job.status())) return;
        if ("RUNNING".equals(job.status()) && job.leaseUntil() != null && job.leaseUntil().isAfter(now()))
            throw new SemanticApiException(409, "IMPORT_BUSY", "Import job is already running");
        if (!"QUEUED".equals(job.status()) && !"FAILED".equals(job.status()) && !"RUNNING".equals(job.status()))
            throw new SemanticApiException(409, "IMPORT_NOT_RETRYABLE", "Import job cannot run from its current state");

        LocalDateTime now = now();
        jdbc.update("UPDATE mate_semantic_import_job SET status='RUNNING',attempts=attempts+1,error_message=NULL,lease_owner=?,lease_until=?,updated_at=? WHERE id=?",
                "semantic-" + UUID.randomUUID(), now.plusMinutes(2), now, jobId);
        RawSource raw = readRaw(graph, job);
        if (raw.text().getBytes(StandardCharsets.UTF_8).length > MAX_TEXT_BYTES)
            throw new SemanticApiException(422, "SOURCE_TOO_LARGE", "Source text exceeds 2 MiB");
        long captureVersion = Optional.ofNullable(jdbc.queryForObject(
                "SELECT MAX(capture_version) FROM mate_semantic_source_snapshot WHERE graph_id=? AND source_kind=? AND source_id=?",
                Long.class, graphId, job.sourceKind(), job.sourceId())).orElse(0L) + 1;
        String snapshotId = com.baomidou.mybatisplus.core.toolkit.IdWorker.getIdStr();
        jdbc.update("INSERT INTO mate_semantic_source_snapshot(id,graph_id,source_kind,source_id,source_title,capture_version,text_digest,text_content,created_by,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                snapshotId, graphId, job.sourceKind(), job.sourceId(), raw.title(), captureVersion,
                sha256(raw.text()), raw.text(), job.createdBy(), now);
        if (graphMapper.touch(graphId, graph.getMutationVersion(), now) != 1)
            throw new SemanticApiException(409, "GRAPH_VERSION_CONFLICT", "Graph changed during source capture");
        jdbc.update("UPDATE mate_semantic_import_job SET status='SUCCEEDED',snapshot_id=?,lease_owner=NULL,lease_until=NULL,updated_at=? WHERE id=?",
                snapshotId, now, jobId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(String graphId, String jobId, RuntimeException failure) {
        String message = Objects.toString(failure.getMessage(), failure.getClass().getSimpleName());
        if (message.length() > 512) message = message.substring(0, 512);
        jdbc.update("UPDATE mate_semantic_import_job SET status='FAILED',attempts=attempts+1,error_message=?,lease_owner=NULL,lease_until=NULL,updated_at=? WHERE id=? AND graph_id=? AND status<>'SUCCEEDED'",
                message, now(), jobId, graphId);
    }

    private RawSource readRaw(GraphRow graph, Job job) {
        if (!"WIKI_RAW".equals(job.sourceKind()))
            throw new SemanticApiException(422, "SOURCE_KIND_UNSUPPORTED", "Unsupported source kind");
        List<RawSource> rows = jdbc.query(
                "SELECT title,COALESCE(NULLIF(extracted_text,''),original_content) text_content FROM mate_wiki_raw_material WHERE id=? AND kb_id=? AND deleted=0",
                (rs, n) -> new RawSource(Objects.toString(rs.getString("title"), ""), rs.getString("text_content")),
                Long.valueOf(job.sourceId()), graph.getKbId());
        if (rows.isEmpty() || rows.getFirst().text() == null)
            throw new SemanticApiException(404, "NOT_FOUND", "Source is unavailable at execution time");
        return rows.getFirst();
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private record Job(String id, String sourceKind, String sourceId, String status, String createdBy, LocalDateTime leaseUntil) {}
    private record RawSource(String title, String text) {}
}

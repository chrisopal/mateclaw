package vip.mate.semantic.source;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import vip.mate.semantic.core.evidence.*;
import vip.mate.semantic.core.identity.GraphScope;
import vip.mate.semantic.core.identity.SemanticIds.*;
import vip.mate.semantic.graph.*;
import vip.mate.semantic.security.SemanticAccessService;
import vip.mate.semantic.web.SemanticApiException;
import vip.mate.semantic.web.SourceDtos.*;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.ResultSet;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
@ConditionalOnProperty(name="mateclaw.semantic.enabled", havingValue="true")
public class SourceApplicationService {
    private static final int MAX_TEXT_BYTES = 2 * 1024 * 1024;
    private final JdbcTemplate jdbc;
    private final GraphApplicationService graphs;
    private final SemanticAccessService access;
    private final ImportJobRunner runner;
    private final TransactionTemplate transactions;
    private final EvidenceVerifier verifier = new EvidenceVerifier();

    public SourceApplicationService(JdbcTemplate jdbc, GraphApplicationService graphs, SemanticAccessService access, ImportJobRunner runner, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc; this.graphs = graphs; this.access = access; this.runner = runner; this.transactions = new TransactionTemplate(transactionManager);
    }

    public ImportJob startImport(String scope, String graphId, ImportRequest request) {
        var actor = access.require(scope, "member");
        GraphRow graph = graphs.requireGraph(scope, graphId, false);
        if (!Boolean.TRUE.equals(graph.getEnabled())) throw conflict("GRAPH_DISABLED", "Graph is disabled");
        validateOperation(request == null ? null : request.operationId());
        String kind = request.sourceKind() == null ? "WIKI_RAW" : request.sourceKind();
        if (!"WIKI_RAW".equals(kind)) throw bad("Only WIKI_RAW sources are supported in M2");
        String source = positive(request.sourceRef(), "sourceRef");
        String hash = sha256(kind + ":" + source);
        ImportJob replay = findJob(graphId, request.operationId());
        if (replay != null) {
            String stored = jdbc.queryForObject("SELECT request_hash FROM mate_semantic_import_job WHERE id=?", String.class, replay.id());
            if (!hash.equals(stored)) throw conflict("OPERATION_CONFLICT", "Operation id has different import payload");
            return replay;
        }
        RawSource raw = raw(graph, source);
        byte[] bytes = raw.text().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_TEXT_BYTES) throw new SemanticApiException(422, "SOURCE_TOO_LARGE", "Source text exceeds 2 MiB");
        return enqueueAndRun(scope, graphId, kind, source, request.operationId(), hash, actor.getId().toString(), null, 0);
    }

    public ImportJob retryImport(String scope, String graphId, String jobId, RetryRequest request) {
        var actor = access.require(scope, "member"); graphs.requireGraph(scope, graphId, false);
        validateOperation(request == null ? null : request.operationId());
        ImportJob previous = findJobById(graphId, jobId);
        if (previous == null) throw notFound();
        if (!"FAILED".equals(previous.status())) throw conflict("IMPORT_NOT_RETRYABLE", "Only failed imports can be retried");
        String hash = sha256(previous.sourceKind() + ":" + previous.sourceRef());
        return enqueueAndRun(scope, graphId, previous.sourceKind(), previous.sourceRef(), request.operationId(), hash,
                actor.getId().toString(), previous.id(), previous.attempts());
    }

    private ImportJob enqueueAndRun(String scope,String graphId,String kind,String source,String operation,String hash,String actor,String retryOf,int previousAttempts) {
        String candidateId=id();LocalDateTime created=now();
        String jobId=transactions.execute(status->{
            GraphRow locked=graphs.requireGraph(scope,graphId,true);if(!Boolean.TRUE.equals(locked.getEnabled()))throw conflict("GRAPH_DISABLED","Graph is disabled");
            ImportJob again=findJob(graphId,operation);if(again!=null){String stored=jdbc.queryForObject("SELECT request_hash FROM mate_semantic_import_job WHERE id=?",String.class,again.id());if(!hash.equals(stored))throw conflict("OPERATION_CONFLICT","Operation id has different import payload");return again.id();}
            jdbc.update("INSERT INTO mate_semantic_import_job(id,graph_id,source_kind,source_id,operation_id,request_hash,status,snapshot_id,attempts,error_message,lease_owner,lease_until,retry_of_job_id,created_by,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    candidateId,graphId,kind,source,operation,hash,"QUEUED",null,previousAttempts,null,null,null,retryOf,actor,created,created);return candidateId;
        });
        ImportJob queued=findJobById(graphId,jobId);if(!"QUEUED".equals(queued.status()))return queued;
        try{runner.run(scope,graphId,jobId);}catch(RuntimeException e){runner.fail(graphId,jobId,e);}
        return findJobById(graphId,jobId);
    }

    public ImportJob readImport(String scope, String graphId, String jobId) {
        access.require(scope, "viewer"); graphs.requireGraph(scope, graphId, false);
        ImportJob row = findJobById(graphId, jobId);
        if (row == null) throw notFound();
        return row;
    }

    @Transactional
    public EvidenceView createEvidence(String scope, String graphId, String snapshotId, EvidenceRequest request) {
        var actor = access.require(scope, "member");
        GraphRow graph = graphs.requireGraph(scope, graphId, true);
        validateOperation(request == null ? null : request.operationId());
        EvidenceView replay = findEvidence(graphId, request.operationId());
        if (replay != null) {
            if (!Objects.equals(replay.snapshotId(), snapshotId)
                    || replay.startCodePoint() != requireNumber(request.startCodePoint())
                    || replay.endCodePoint() != requireNumber(request.endCodePoint())
                    || !Objects.equals(replay.exactQuote(), request.exactQuote()))
                throw conflict("OPERATION_CONFLICT", "Operation id has different evidence payload");
            return replay;
        }
        SnapshotText snapshot = snapshotText(graph, snapshotId, true);
        String evidenceId = id();
        Evidence evidence = new Evidence(new EvidenceId(evidenceId), new SnapshotId(snapshotId),
                requireNumber(request.startCodePoint()), requireNumber(request.endCodePoint()), request.exactQuote());
        SourceSnapshot core = new SourceSnapshot(new SnapshotId(snapshotId), scope(graph), snapshot.text(), snapshot.textDigest());
        var report = verifier.verify(core, evidence);
        if (!report.valid()) throw new SemanticApiException(422, report.violations().getFirst().code(), "Evidence does not match immutable snapshot");
        jdbc.update("INSERT INTO mate_semantic_evidence(id,graph_id,snapshot_id,operation_id,start_codepoint,end_codepoint,exact_quote,created_by,created_at) VALUES(?,?,?,?,?,?,?,?,?)",
                evidenceId, graphId, snapshotId, request.operationId(), evidence.startCodePoint(), evidence.endCodePoint(), evidence.exactQuote(), actor.getId().toString(), now());
        return findEvidence(graphId, request.operationId());
    }

    public Page<Snapshot> snapshots(String scope, String graphId) {
        access.require(scope, "viewer"); GraphRow graph = graphs.requireGraph(scope, graphId, false);
        return new Page<>(jdbc.query("SELECT * FROM mate_semantic_source_snapshot WHERE graph_id=? ORDER BY created_at DESC,id", (rs,n) -> snapshot(rs), graphId));
    }

    public Page<SourceView> sources(String scope, String graphId) {
        access.require(scope, "viewer"); GraphRow graph = graphs.requireGraph(scope, graphId, false);
        return new Page<>(jdbc.query("SELECT s.source_kind,s.source_id,MAX(s.source_title) title,COALESCE(g.state,'ACTIVE') state,COUNT(*) snapshot_count FROM mate_semantic_source_snapshot s LEFT JOIN mate_semantic_source_governance g ON g.graph_id=s.graph_id AND g.source_kind=s.source_kind AND g.source_id=s.source_id WHERE s.graph_id=? GROUP BY s.source_kind,s.source_id,g.state ORDER BY MAX(s.created_at) DESC",
                (rs,n) -> new SourceView(rs.getString("source_kind"),rs.getString("source_id"),rs.getString("title"),rs.getString("state"),rs.getLong("snapshot_count")), graphId));
    }

    public SnapshotText text(String scope, String graphId, String snapshotId) {
        access.require(scope, "member"); GraphRow graph = graphs.requireGraph(scope, graphId, false);
        return snapshotText(graph, snapshotId, true);
    }

    public SnapshotText snapshotTextForEvidence(String scope, String graphId, String snapshotId) {
        GraphRow graph = graphs.requireGraph(scope, graphId, false);
        return snapshotText(graph, snapshotId, true);
    }

    private SnapshotText snapshotText(GraphRow graph, String snapshotId, boolean requireCurrentSource) {
        try {
            SnapshotText text = jdbc.queryForObject("SELECT id,text_digest,text_content FROM mate_semantic_source_snapshot WHERE id=? AND graph_id=?",
                    (rs,n) -> new SnapshotText(rs.getString(1),rs.getString(2),rs.getString(3)), snapshotId, graph.getId());
            if (requireCurrentSource) {
                String sourceId = jdbc.queryForObject("SELECT source_id FROM mate_semantic_source_snapshot WHERE id=? AND graph_id=? AND source_kind='WIKI_RAW'", String.class, snapshotId, graph.getId());
                Integer count = sourceId == null ? 0 : jdbc.queryForObject("SELECT COUNT(*) FROM mate_wiki_raw_material WHERE id=? AND kb_id=? AND deleted=0", Integer.class, Long.valueOf(sourceId), graph.getKbId());
                if (count == null || count == 0) throw notFound();
            }
            return text;
        } catch (EmptyResultDataAccessException e) { throw notFound(); }
    }

    private RawSource raw(GraphRow graph, String source) {
        try {
            return jdbc.queryForObject("SELECT title,COALESCE(NULLIF(extracted_text,''),original_content) text_content FROM mate_wiki_raw_material WHERE id=? AND kb_id=? AND deleted=0",
                    (rs,n) -> new RawSource(rs.getString("title"),rs.getString("text_content")), Long.valueOf(source), graph.getKbId());
        } catch (EmptyResultDataAccessException e) { throw notFound(); }
    }
    private ImportJob findJob(String graph, String operation) {
        List<ImportJob> rows = jdbc.query("SELECT * FROM mate_semantic_import_job WHERE graph_id=? AND operation_id=?", (rs,n)->job(rs), graph, operation);
        return rows.isEmpty() ? null : rows.getFirst();
    }
    private ImportJob findJobById(String graph, String id) {
        List<ImportJob> rows = jdbc.query("SELECT * FROM mate_semantic_import_job WHERE graph_id=? AND id=?", (rs,n)->job(rs), graph, id);
        return rows.isEmpty() ? null : rows.getFirst();
    }
    private EvidenceView findEvidence(String graph, String operation) {
        List<EvidenceView> rows = jdbc.query("SELECT * FROM mate_semantic_evidence WHERE graph_id=? AND operation_id=?", (rs,n)->new EvidenceView(rs.getString("id"),rs.getString("snapshot_id"),rs.getInt("start_codepoint"),rs.getInt("end_codepoint"),rs.getString("exact_quote")), graph, operation);
        return rows.isEmpty() ? null : rows.getFirst();
    }
    private static ImportJob job(ResultSet rs) throws java.sql.SQLException { return new ImportJob(rs.getString("id"),rs.getString("graph_id"),rs.getString("source_kind"),rs.getString("source_id"),rs.getString("status"),rs.getString("snapshot_id"),rs.getInt("attempts"),rs.getString("error_message"),rs.getTimestamp("updated_at").toLocalDateTime().toInstant(ZoneOffset.UTC)); }
    private static Snapshot snapshot(ResultSet rs) throws java.sql.SQLException { return new Snapshot(rs.getString("id"),rs.getString("graph_id"),rs.getString("source_kind"),rs.getString("source_id"),rs.getString("source_title"),rs.getLong("capture_version"),rs.getString("text_digest"),rs.getTimestamp("created_at").toLocalDateTime().toInstant(ZoneOffset.UTC)); }
    private static GraphScope scope(GraphRow graph) { return new GraphScope(new WorkspaceId(graph.getWorkspaceId().toString()),new KnowledgeBaseId(graph.getKbId().toString()),new GraphId(graph.getId())); }
    private static void validateOperation(String value) { if (value == null || value.isBlank() || value.length() > 128) throw bad("operationId required"); }
    private static int requireNumber(Integer value) { if (value == null) throw bad("Evidence offsets required"); return value; }
    private static String positive(String value,String name) { if (value==null || !value.matches("[1-9][0-9]*")) throw bad("Positive "+name+" required"); return value; }
    private static String id() { return com.baomidou.mybatisplus.core.toolkit.IdWorker.getIdStr(); }
    private static LocalDateTime now() { return LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS); }
    private static String sha256(String text) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); } catch(NoSuchAlgorithmException e){ throw new IllegalStateException(e); } }
    private static SemanticApiException bad(String message) { return new SemanticApiException(400,"INVALID_REQUEST",message); }
    private static SemanticApiException conflict(String code,String message) { return new SemanticApiException(409,code,message); }
    private static SemanticApiException notFound() { return new SemanticApiException(404,"NOT_FOUND","Semantic source not found in graph"); }
    private record RawSource(String title,String text) { RawSource { if (text == null) throw new SemanticApiException(422,"SOURCE_EMPTY","Source has no extractable text"); } }
}

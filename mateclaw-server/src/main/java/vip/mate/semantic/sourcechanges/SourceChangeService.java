package vip.mate.semantic.sourcechanges;

import static vip.mate.semantic.sourcechanges.SourceChangeDtos.*;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import vip.mate.semantic.core.ontology.OntologyDocument;
import vip.mate.semantic.graph.GraphApplicationService;
import vip.mate.semantic.graph.GraphRow;
import vip.mate.semantic.graph.repository.GraphMapper;
import vip.mate.semantic.security.SemanticAccessService;
import vip.mate.semantic.source.SourceApplicationService;
import vip.mate.semantic.web.SemanticApiException;
import vip.mate.semantic.web.SourceDtos.ImportJob;

/** Durable source-change scan and review queue for business facts. */
@Service
@ConditionalOnProperty(name = "mateclaw.semantic.enabled", havingValue = "true")
public class SourceChangeService {
    private static final String UNAVAILABLE = "SOURCE_UNAVAILABLE";
    private static final Set<String> DECISIONS = Set.of("ACKNOWLEDGE", "REMODEL", "KEEP_HISTORICAL");
    private static final int MAX_SOURCES = 1000;
    private static final int MAX_ITEMS = 10000;

    private final JdbcTemplate jdbc;
    private final GraphApplicationService graphs;
    private final GraphMapper graphMapper;
    private final SemanticAccessService access;
    private final SourceApplicationService sources;
    private final TransactionTemplate transactions;
    private final TransactionTemplate lockTransactions;
    private final TransactionTemplate outsideTransactions;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    public SourceChangeService(JdbcTemplate jdbc, GraphApplicationService graphs, GraphMapper graphMapper,
            SemanticAccessService access, SourceApplicationService sources, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.graphs = graphs;
        this.graphMapper = graphMapper;
        this.access = access;
        this.sources = sources;
        this.transactions = new TransactionTemplate(transactionManager);
        this.outsideTransactions = new TransactionTemplate(transactionManager);
        this.outsideTransactions.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        this.lockTransactions = new TransactionTemplate(transactionManager);
        this.lockTransactions.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Scans one source or every source already captured in the graph. A run is
     * resumable: a RUNNING run is continued and a COMPLETED run is replayed.
     */
    public ScanResult scan(String scope, String graphId, ScanRequest request) {
        var actor = access.require(scope, "member");
        validateOperation(request == null ? null : request.operationId());
        GraphRow graph = graphs.requireGraph(scope, graphId, false);
        String requestDigest = requestDigest(request);
        Run run = findRun(scope, graphId, request.operationId());
        if (run != null && !requestDigest.equals(run.requestDigest()))
            throw conflict("OPERATION_CONFLICT", "Operation id has different scan parameters");
        if (run != null && "COMPLETED".equals(run.status())) return readRun(scope, graphId, run.id());
        if (run == null) {
            if (request.expectedGraphVersion() != null
                    && request.expectedGraphVersion().longValue() != graph.getMutationVersion()) {
                throw conflict("GRAPH_VERSION_CONFLICT", "Graph changed; refresh before scanning");
            }
            run = createRun(graph, request, actor.getId().toString(), requestDigest);
            if (!requestDigest.equals(run.requestDigest()))
                throw conflict("OPERATION_CONFLICT", "Operation id has different scan parameters");
        }

        for (SourceRef source : frozenSources(run)) {
            if (findChange(run.id(), source.kind(), source.ref()) != null) continue;
            scanSource(scope, graphId, graph, run, source, actor.getId().toString());
            graph = graphs.requireGraph(scope, graphId, false);
        }
        jdbc.update("UPDATE mate_semantic_source_change_run SET status='COMPLETED',updated_at=? WHERE id=? AND status='RUNNING'",
                now(), run.id());
        return readRun(scope, graphId, run.id());
    }

    public List<ReviewItem> pending(String scope, String graphId, Integer limit) {
        access.require(scope, "viewer");
        graphs.requireGraph(scope, graphId, false);
        return pendingRows(graphId, limit);
    }

    /** Read-only context boundary for callers that already carry an actor identity. */
    public List<ReviewItem> pendingAsActor(String scope, String actorId, String graphId, Integer limit) {
        access.requireActor(scope, actorId, "viewer");
        graphs.requireGraph(scope, graphId, false);
        return pendingRows(graphId, limit);
    }

    private List<ReviewItem> pendingRows(String graphId, Integer limit) {
        int size = limit == null ? 100 : limit;
        if (size < 1 || size > 500) throw bad("limit must be between 1 and 500");
        return jdbc.query("SELECT * FROM mate_semantic_source_change_item WHERE graph_id=? AND review_state='PENDING' ORDER BY created_at,id LIMIT ?",
                (rs, n) -> item(rs), graphId, size);
    }

    public List<ReviewItem> items(String scope, String graphId, String changeId) {
        access.require(scope, "viewer");
        graphs.requireGraph(scope, graphId, false);
        return jdbc.query("SELECT * FROM mate_semantic_source_change_item WHERE graph_id=? AND change_id=? ORDER BY created_at,id",
                (rs, n) -> item(rs), graphId, changeId);
    }

    @Transactional
    public ReviewItem decide(String scope, String graphId, String itemId, DecideRequest request) {
        var actor = access.require(scope, "admin");
        validateDecision(request);
        GraphRow graph = graphs.requireGraph(scope, graphId, true);
        ReviewItem current = findItem(graphId, itemId);
        if (current == null) throw notFound();
        String decisionDigest;
        try {decisionDigest=OntologyDocument.sha256(json.writeValueAsString(request));}
        catch(Exception e){throw new IllegalStateException(e);}
        if (request.operationId().equals(currentDecisionOperation(graphId, itemId))) {
            String stored=jdbc.queryForObject("SELECT decision_request_digest FROM mate_semantic_source_change_item WHERE id=?",String.class,itemId);
            if(!decisionDigest.equals(stored))throw conflict("OPERATION_CONFLICT","Operation id has different decision parameters");
            return current;
        }
        if (!"PENDING".equals(current.reviewState())) throw conflict("SOURCE_CHANGE_STALE", "Review item is no longer pending");
        if (!Objects.equals(request.expectedObservedDigest(), current.newDigest()))
            throw conflict("SOURCE_CHANGE_STALE", "Observed source digest changed");
        if (request.expectedGraphVersion() == null || request.expectedGraphVersion().longValue() != graph.getMutationVersion())
            throw conflict("GRAPH_VERSION_CONFLICT", "Graph changed; refresh before deciding");
        String currentDigest = currentSourceDigest(graph, current.sourceKind(), current.sourceRef());
        if (!Objects.equals(current.newDigest(), currentDigest))
            throw conflict("SOURCE_CHANGE_STALE", "Source changed after this review item was created");
        LocalDateTime reviewedAt = now();
        if (jdbc.update("UPDATE mate_semantic_source_change_item SET review_state='REVIEWED',decision=?,reason=?,decision_operation_id=?,decision_request_digest=?,reviewed_by=?,reviewed_at=? WHERE id=? AND review_state='PENDING' AND new_digest=?",
                request.decision(), request.reason(), request.operationId(), decisionDigest, actor.getId().toString(), reviewedAt,
                itemId, request.expectedObservedDigest()) != 1) {
            throw conflict("SOURCE_CHANGE_STALE", "Review item changed concurrently");
        }
        if (graphMapper.touch(graphId, graph.getMutationVersion(), reviewedAt) != 1)
            throw conflict("GRAPH_VERSION_CONFLICT", "Graph changed during source review");
        return findItem(graphId, itemId);
    }

    private void scanSource(String scope, String graphId, GraphRow graph, Run run, SourceRef source, String actor) {
        transactions.executeWithoutResult(status -> {
            lockTransactions.executeWithoutResult(lockStatus -> {
                try {
                    jdbc.update("INSERT INTO mate_semantic_source_change_lock(graph_id,source_kind,source_ref,touched_at) VALUES(?,?,?,?)",
                            graphId, source.kind(), source.ref(), now());
                } catch (DuplicateKeyException ignored) { }
            });
            jdbc.queryForObject("SELECT graph_id FROM mate_semantic_source_change_lock WHERE graph_id=? AND source_kind=? AND source_ref=? FOR UPDATE",
                    String.class, graphId, source.kind(), source.ref());
            scanSourceLocked(scope, graphId, graph, run, source, actor);
        });
    }

    private void scanSourceLocked(String scope, String graphId, GraphRow graph, Run run, SourceRef source, String actor) {
        if(findChange(run.id(),source.kind(),source.ref())!=null)return;
        Snapshot old = baseline(run,source);
        Material material = material(graph, source);
        String oldDigest = old == null ? null : old.digest();
        String newDigest = material == null ? UNAVAILABLE : material.digest();
        Set<String> impactedEvidence = old != null && !Objects.equals(oldDigest, newDigest)
                ? impactedEvidence(graphId, source) : Set.of();
        List<Affected> affected = old != null && !Objects.equals(oldDigest, newDigest)
                ? collectAffected(graphId, source, impactedEvidence) : List.of();
        if (affected.size() > MAX_ITEMS)
            throw new SemanticApiException(422, "SOURCE_CHANGE_TOO_LARGE", "Source change affects too many review items");
        String state;
        String newSnapshot = null;
        if (material == null) {
            state = "UNAVAILABLE";
        } else if (old != null && material.digest().equals(old.digest())) {
            newSnapshot = old.id();
            state = hadUnavailable(graphId, source) ? "RESTORED" : "UNCHANGED";
        } else {
            // The import runner commits its own lease and snapshot transactions.
            // Suspend only the review transaction; retain the dedicated source lock.
            ImportJob importJob = outsideTransactions.execute(ignored -> capture(scope, graphId, source, run.id(), material));
            newSnapshot = importJob.snapshotId();
            if (newSnapshot == null || !"SUCCEEDED".equals(importJob.status()))
                throw new SemanticApiException(503, "SOURCE_CAPTURE_FAILED", "Source snapshot capture did not complete");
            // A locking read sees the import worker's committed row even when MySQL's
            // repeatable-read snapshot predates the suspended capture transaction.
            String capturedDigest=jdbc.queryForObject("SELECT text_digest FROM mate_semantic_source_snapshot WHERE id=? FOR UPDATE",String.class,newSnapshot);
            if(!newDigest.equals(capturedDigest))throw conflict("SOURCE_CHANGE_STALE","Source changed during capture; create a fresh scan");
            state = old == null ? "INITIAL" : "CHANGED";
        }
        GraphRow observed = graphs.requireGraph(scope, graphId, true);
        String changeId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO mate_semantic_source_change(id,run_id,graph_id,source_kind,source_ref,old_snapshot_id,new_snapshot_id,old_digest,new_digest,source_state,observed_graph_version,created_by,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                changeId, run.id(), graphId, source.kind(), source.ref(), old == null ? null : old.id(), newSnapshot,
                oldDigest, newDigest, state, observed.getMutationVersion(), actor, now());
        if (old != null && !Objects.equals(oldDigest, newDigest)) {
            jdbc.update("UPDATE mate_semantic_source_change_item SET review_state='STALE' WHERE graph_id=? AND source_kind=? AND source_ref=? AND review_state IN ('PENDING','REVIEWED') AND new_digest<>?",
                    graphId, source.kind(), source.ref(), newDigest);
            enqueueAffected(changeId, graphId, source, newSnapshot, oldDigest, newDigest, state,
                    observed.getMutationVersion(), affected, actor);
        }
    }

    private ImportJob capture(String scope, String graphId, SourceRef source, String runId, Material material) {
        String operation = "source-change-" + runId + "-" + OntologyDocument.sha256(source.kind() + "\u0000" + source.ref()).substring(0, 24);
        ImportJob result = sources.startImport(scope, graphId,
                new vip.mate.semantic.web.SourceDtos.ImportRequest(source.kind(), source.ref(), operation));
        if (!"FAILED".equals(result.status())) return result;
        return sources.retryImport(scope, graphId, result.id(),
                new vip.mate.semantic.web.SourceDtos.RetryRequest(operation + "-retry"));
    }

    private void enqueueAffected(String changeId, String graphId, SourceRef source,
            String newSnapshot, String oldDigest, String newDigest, String sourceState,
            long graphVersion, List<Affected> affected, String actor) {
        if (affected.isEmpty()) return;
        for (Affected item : affected) {
            Integer existing=jdbc.queryForObject("SELECT COUNT(*) FROM mate_semantic_source_change_item WHERE graph_id=? AND source_kind=? AND source_ref=? AND item_kind=? AND item_id=? AND item_revision=? AND old_snapshot_id=? AND new_digest=? AND review_state IN ('PENDING','REVIEWED')",
                Integer.class,graphId,source.kind(),source.ref(),item.kind(),item.id(),item.revision(),item.oldSnapshotId(),newDigest);
            if(existing!=null&&existing>0)continue;
            insertItem(changeId, graphId, source, item.kind(), item.id(), item.revision(), item.oldSnapshotId(), newSnapshot,
                    jdbc.queryForObject("SELECT text_digest FROM mate_semantic_source_snapshot WHERE id=?",String.class,item.oldSnapshotId()), newDigest, sourceState, graphVersion, actor);
        }
    }

    private List<Affected> collectAffected(String graphId, SourceRef source, Set<String> impactedEvidence) {
        Set<String> keys = new LinkedHashSet<>();
        List<Affected> affected = new ArrayList<>();
        jdbc.query("SELECT s.id,r.revision,r.review_status,e.snapshot_id FROM mate_semantic_statement s JOIN mate_semantic_statement_revision r ON r.statement_id=s.id AND r.revision=s.current_revision JOIN mate_semantic_revision_evidence re ON re.statement_id=r.statement_id AND re.revision=r.revision JOIN mate_semantic_evidence e ON e.id=re.evidence_id JOIN mate_semantic_source_snapshot snap ON snap.id=e.snapshot_id WHERE s.graph_id=? AND snap.source_kind=? AND snap.source_id=? AND r.review_status IN ('ACCEPTED','PROPOSED')",
                (rs, n) -> {
                    String kind = "ACCEPTED".equals(rs.getString("review_status")) ? "FACT" : "CANDIDATE";
                    String key = kind + ":" + rs.getString("id") + ":" + rs.getInt("revision") + ":" + rs.getString("snapshot_id");
                    if (keys.add(key)) affected.add(new Affected(kind, rs.getString("id"), rs.getInt("revision"), rs.getString("snapshot_id")));
                    return null;
                }, graphId, source.kind(), source.ref());
        jdbc.query("SELECT id,target_statement_id,expected_revision,payload_json FROM mate_semantic_change_proposal WHERE graph_id=? AND status='PENDING'",
                (rs, n) -> {
                    if (!containsEvidence(rs.getString("payload_json"), impactedEvidence)) return null;
                    for (String oldSnapshot : evidenceSnapshotsForPayload(rs.getString("payload_json"), source)) {
                        String key = "CHANGE_PROPOSAL:" + rs.getString("id") + ":" + rs.getInt("expected_revision") + ":" + oldSnapshot;
                        if (keys.add(key)) affected.add(new Affected("CHANGE_PROPOSAL", rs.getString("id"), rs.getInt("expected_revision"), oldSnapshot));
                    }
                    return null;
                }, graphId);
        jdbc.query("SELECT s.id,s.version,s.payload_json,t.payload_json task_payload FROM mate_semantic_extraction_suggestion s JOIN mate_semantic_extraction_task t ON t.id=s.task_id WHERE t.graph_id=? AND s.status='OPEN'",
                (rs, n) -> {
                    for (String oldSnapshot : sourceSnapshotsForTask(rs.getString("task_payload"), source)) {
                        String key = "EXTRACTION_SUGGESTION:" + rs.getString("id") + ":" + rs.getLong("version") + ":" + oldSnapshot;
                        if (keys.add(key)) affected.add(new Affected("EXTRACTION_SUGGESTION", rs.getString("id"), rs.getLong("version"), oldSnapshot));
                    }
                    return null;
                }, graphId);
        return affected;
    }

    private List<String> evidenceSnapshotsForPayload(String payload, SourceRef source) {
        Set<String> result = new LinkedHashSet<>();
        try {
            JsonNode ids = json.readTree(payload).path("evidenceIds");
            for (JsonNode id : ids) {
                List<String> snapshots = jdbc.query("SELECT s.id FROM mate_semantic_evidence e JOIN mate_semantic_source_snapshot s ON s.id=e.snapshot_id WHERE e.id=? AND s.source_kind=? AND s.source_id=?",
                        (rs, n) -> rs.getString(1), id.asText(), source.kind(), source.ref());
                result.addAll(snapshots);
            }
        } catch (Exception ignored) { }
        return List.copyOf(result);
    }

    private List<String> sourceSnapshotsForTask(String payload, SourceRef source) {
        Set<String> result = new LinkedHashSet<>();
        try {
            JsonNode sourceNode = json.readTree(payload).path("source");
            if (!source.ref().equals(sourceNode.path("sourceRef").asText())) return List.of();
            String snapshot = sourceNode.path("snapshotId").asText(null);
            if (snapshot != null && !snapshot.isBlank()) {
                Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM mate_semantic_source_snapshot WHERE id=? AND source_kind=? AND source_id=?",
                        Integer.class, snapshot, source.kind(), source.ref());
                if (count != null && count > 0) result.add(snapshot);
            }
        } catch (Exception ignored) { }
        return List.copyOf(result);
    }

    private Set<String> impactedEvidence(String graphId, SourceRef source) {
        Set<String> result = new LinkedHashSet<>();
        jdbc.query("SELECT e.id FROM mate_semantic_evidence e JOIN mate_semantic_source_snapshot s ON s.id=e.snapshot_id WHERE e.graph_id=? AND s.source_kind=? AND s.source_id=?",
                (rs, n) -> { result.add(rs.getString(1)); return null; }, graphId, source.kind(), source.ref());
        return result;
    }

    private boolean containsEvidence(String payload, Set<String> ids) {
        try {
            JsonNode values = json.readTree(payload).path("evidenceIds");
            for (JsonNode item : values) if (ids.contains(item.asText())) return true;
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private void insertItem(String changeId, String graphId, SourceRef source, String kind, String itemId, long revision,
            String oldSnapshot, String newSnapshot, String oldDigest, String newDigest, String state, long graphVersion, String actor) {
        try {
            jdbc.update("INSERT INTO mate_semantic_source_change_item(id,change_id,graph_id,source_kind,source_ref,item_kind,item_id,item_revision,old_snapshot_id,new_snapshot_id,old_digest,new_digest,source_state,review_state,observed_graph_version,created_by,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    UUID.randomUUID().toString(), changeId, graphId, source.kind(), source.ref(), kind, itemId, revision,
                    oldSnapshot, newSnapshot, oldDigest, newDigest, state, "PENDING", graphVersion, actor, now());
        } catch (DuplicateKeyException ignored) {
            // The unique change/item/revision key makes concurrent retries harmless.
        }
    }

    private List<SourceRef> sourceRefs(GraphRow graph, ScanRequest request) {
        if (request.sourceKind() != null || request.sourceRef() != null) {
            if (!"WIKI_RAW".equals(request.sourceKind()) || request.sourceRef() == null
                    || !request.sourceRef().matches("[1-9][0-9]*"))
                throw bad("sourceKind WIKI_RAW and sourceRef are required together");
            return List.of(new SourceRef(request.sourceKind(), request.sourceRef()));
        }
        List<SourceRef> refs = jdbc.query("SELECT DISTINCT source_kind,source_id FROM mate_semantic_source_snapshot WHERE graph_id=? ORDER BY source_kind,source_id",
                (rs, n) -> new SourceRef(rs.getString(1), rs.getString(2)), graph.getId());
        if (refs.size() > MAX_SOURCES) throw bad("Source scan limit exceeded");
        return refs;
    }

    private List<SourceRef> frozenSources(Run run) {
        try {
            List<SourceRef> refs=new ArrayList<>();
            json.readTree(run.baselineJson()).fieldNames().forEachRemaining(key->{
                int split=key.indexOf(':');refs.add(new SourceRef(key.substring(0,split),key.substring(split+1)));
            });
            return refs;
        }catch(Exception e){throw new IllegalStateException("Invalid frozen scan sources",e);}
    }
    private String baselineJson(GraphRow graph,ScanRequest request) {
        Map<String,String> ids=new java.util.TreeMap<>();
        for(var ref:sourceRefs(graph,request)) {
            var snapshot=latest(graph.getId(),ref.kind(),ref.ref());
            ids.put(ref.kind()+":"+ref.ref(),snapshot==null?"":snapshot.id());
        }
        try{return json.writeValueAsString(ids);}catch(Exception e){throw new IllegalStateException(e);}
    }
    private Snapshot baseline(Run run,SourceRef source) {
        try {
            var value=json.readTree(run.baselineJson()).get(source.kind()+":"+source.ref());
            if(value==null)throw conflict("SOURCE_SCAN_CONFLICT","Source was not part of the frozen scan");
            if(value.asText().isEmpty())return null;
            return jdbc.queryForObject("SELECT id,text_digest,text_content FROM mate_semantic_source_snapshot WHERE id=? AND graph_id=?",
                (rs,n)->new Snapshot(rs.getString(1),rs.getString(2),rs.getString(3)),value.asText(),run.graphId());
        }catch(SemanticApiException e){throw e;}catch(Exception e){throw new IllegalStateException("Invalid source scan baseline",e);}
    }

    private Run createRun(GraphRow graph, ScanRequest request, String actor, String requestDigest) {
        Run candidate = new Run(UUID.randomUUID().toString(), graph.getId(), request.operationId(), "RUNNING", requestDigest, baselineJson(graph,request));
        try {
            jdbc.update("INSERT INTO mate_semantic_source_change_run(id,graph_id,operation_id,status,expected_graph_version,request_digest,baseline_json,created_by,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                    candidate.id(), graph.getId(), request.operationId(), "RUNNING", request.expectedGraphVersion(), requestDigest, candidate.baselineJson(), actor, now(), now());
            return candidate;
        } catch (DuplicateKeyException e) {
            Run existing = findRun(graph.getWorkspaceId().toString(), graph.getId(), request.operationId());
            if (existing == null) throw conflict("SOURCE_SCAN_CONFLICT", "Source scan changed concurrently");
            return existing;
        }
    }

    private ScanResult readRun(String scope, String graphId, String runId) {
        access.require(scope, "viewer");
        GraphRow graph = graphs.requireGraph(scope, graphId, false);
        List<Change> changes = jdbc.query("SELECT c.*,COUNT(i.id) affected_count FROM mate_semantic_source_change c LEFT JOIN mate_semantic_source_change_item i ON i.change_id=c.id WHERE c.run_id=? GROUP BY c.id ORDER BY c.created_at,c.id",
                (rs, n) -> new Change(rs.getString("id"), rs.getString("source_kind"), rs.getString("source_ref"),
                        rs.getString("old_snapshot_id"), rs.getString("new_snapshot_id"), rs.getString("old_digest"),
                        rs.getString("new_digest"), rs.getString("source_state"), rs.getLong("observed_graph_version"),
                        rs.getInt("affected_count"), instant(rs.getTimestamp("created_at"))), runId);
        return new ScanResult(runId, graphId, graph.getMutationVersion(), changes);
    }

    private ReviewItem findItem(String graphId, String id) {
        List<ReviewItem> rows = jdbc.query("SELECT * FROM mate_semantic_source_change_item WHERE graph_id=? AND id=?",
                (rs, n) -> item(rs), graphId, id);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private String currentDecisionOperation(String graphId, String itemId) {
        List<String> rows = jdbc.query("SELECT decision_operation_id FROM mate_semantic_source_change_item WHERE graph_id=? AND id=?",
                (rs, n) -> rs.getString(1), graphId, itemId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private ReviewItem item(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ReviewItem(rs.getString("id"), rs.getString("change_id"), rs.getString("graph_id"), rs.getString("source_kind"),
                rs.getString("source_ref"), rs.getString("item_kind"), rs.getString("item_id"), (Long) rs.getObject("item_revision"),
                rs.getString("old_snapshot_id"), rs.getString("new_snapshot_id"), rs.getString("old_digest"), rs.getString("new_digest"),
                rs.getString("source_state"), rs.getString("review_state"), rs.getString("decision"), rs.getString("reason"),
                (Long) rs.getObject("observed_graph_version"), instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("reviewed_at")));
    }

    private Run findRun(String scope, String graphId, String operation) {
        List<Run> rows = jdbc.query("SELECT r.id,r.graph_id,r.operation_id,r.status,r.request_digest,r.baseline_json FROM mate_semantic_source_change_run r JOIN mate_semantic_graph g ON g.id=r.graph_id WHERE r.graph_id=? AND r.operation_id=? AND g.workspace_id=?",
                (rs, n) -> new Run(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),rs.getString(6)), graphId, operation, Long.valueOf(scope));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private Change findChange(String runId, String kind, String ref) {
        List<Change> rows = jdbc.query("SELECT c.*,0 affected_count FROM mate_semantic_source_change c WHERE c.run_id=? AND c.source_kind=? AND c.source_ref=?",
                (rs, n) -> new Change(rs.getString("id"), rs.getString("source_kind"), rs.getString("source_ref"), rs.getString("old_snapshot_id"), rs.getString("new_snapshot_id"), rs.getString("old_digest"), rs.getString("new_digest"), rs.getString("source_state"), rs.getLong("observed_graph_version"), 0, instant(rs.getTimestamp("created_at"))), runId, kind, ref);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private Snapshot latest(String graph, String kind, String ref) {
        List<Snapshot> rows = jdbc.query("SELECT id,text_digest,text_content FROM mate_semantic_source_snapshot WHERE graph_id=? AND source_kind=? AND source_id=? ORDER BY capture_version DESC,id DESC LIMIT 1",
                (rs, n) -> new Snapshot(rs.getString(1), rs.getString(2), rs.getString(3)), graph, kind, ref);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private Material material(GraphRow graph, SourceRef source) {
        List<Material> rows = jdbc.query("SELECT COALESCE(NULLIF(extracted_text,''),original_content) AS content FROM mate_wiki_raw_material WHERE id=? AND kb_id=? AND deleted=0",
                (rs, n) -> new Material(Objects.toString(rs.getString(1), "")), Long.valueOf(source.ref()), graph.getKbId());
        if (rows.isEmpty() || rows.getFirst().text().isBlank()) return null;
        return rows.getFirst();
    }

    private String currentSourceDigest(GraphRow graph, String kind, String ref) {
        Material material = material(graph, new SourceRef(kind, ref));
        return material == null ? UNAVAILABLE : material.digest();
    }

    private boolean hadUnavailable(String graph, SourceRef source) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM mate_semantic_source_change WHERE graph_id=? AND source_kind=? AND source_ref=? AND source_state='UNAVAILABLE'",
                Integer.class, graph, source.kind(), source.ref());
        return count != null && count > 0;
    }

    private static Instant instant(java.sql.Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static LocalDateTime now() { return LocalDateTime.now(ZoneOffset.UTC); }
    private static String requestDigest(ScanRequest request) {
        StringBuilder canonical = new StringBuilder();
        append(canonical, request.operationId());
        append(canonical, request.sourceKind());
        append(canonical, request.sourceRef());
        append(canonical, request.expectedGraphVersion());
        return OntologyDocument.sha256(canonical.toString());
    }
    private static void append(StringBuilder target, Object value) {
        String text = value == null ? "" : value.toString();
        target.append(text.length()).append(':').append(text);
    }
    private static String hash(String text) { return OntologyDocument.sha256(text); }
    private static void validateOperation(String value) { if (value == null || value.isBlank() || value.length() > 128) throw bad("operationId required"); }
    private static void validateDecision(DecideRequest request) {
        if (request == null || request.operationId() == null || request.operationId().isBlank() || request.expectedObservedDigest() == null
                || request.expectedGraphVersion() == null || !DECISIONS.contains(request.decision()) || request.reason() == null || request.reason().isBlank())
            throw bad("operationId, expected digest/version, explicit decision and reason are required");
        if (request.reason().length() > 2000) throw bad("reason is too long");
    }
    private static SemanticApiException bad(String text) { return new SemanticApiException(400, "INVALID_SOURCE_CHANGE", text); }
    private static SemanticApiException conflict(String code, String text) { return new SemanticApiException(409, code, text); }
    private static SemanticApiException notFound() { return new SemanticApiException(404, "NOT_FOUND", "Source change item not found"); }

    private record SourceRef(String kind, String ref) {}
    private record Affected(String kind, String id, long revision, String oldSnapshotId) {}
    private record Material(String text) { String digest() { return hash(text); } }
    private record Snapshot(String id, String digest, String text) {}
    private record Run(String id, String graphId, String operationId, String status, String requestDigest, String baselineJson) {}
}

package vip.mate.semantic.ontology;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import vip.mate.semantic.core.conflict.ConflictDetector;
import vip.mate.semantic.core.fact.*;
import vip.mate.semantic.core.identity.SemanticIds.*;
import vip.mate.semantic.core.ontology.*;
import vip.mate.semantic.graph.*;
import vip.mate.semantic.security.SemanticAccessService;
import vip.mate.semantic.statement.SemanticDomainMapper;
import vip.mate.semantic.web.*;
import vip.mate.semantic.web.OntologyImpactDtos.*;
import vip.mate.semantic.web.StatementDtos.ProposeRequest;

import java.time.Instant;
import java.util.*;

@Service
@ConditionalOnProperty(name = "mateclaw.semantic.enabled", havingValue = "true")
public class OntologyImpactService {
    private final org.mybatis.spring.SqlSessionTemplate session;
    private final JdbcTemplate jdbc;
    private final SemanticAccessService access;
    private final GraphApplicationService graphs;
    private final OntologyApplicationService ontologies;
    private final OntologyWireMapper wire;
    private final SemanticDomainMapper domain;

    public OntologyImpactService(
            JdbcTemplate jdbc,
            SemanticAccessService access,
            GraphApplicationService graphs,
            OntologyApplicationService ontologies,
            OntologyWireMapper wire,
            SemanticDomainMapper domain,
            org.mybatis.spring.SqlSessionTemplate session) {
        this.session = session;
        this.jdbc = jdbc;
        this.access = access;
        this.graphs = graphs;
        this.ontologies = ontologies;
        this.wire = wire;
        this.domain = domain;
    }

    @Transactional(readOnly = true, timeout = 5, isolation = Isolation.READ_COMMITTED)
    public UsagePage usage(String scope, String ontologyId, int page, int pageSize) {
        access.require(scope, "viewer");
        ontologies.get(scope, ontologyId);
        if (page < 1 || pageSize < 1 || pageSize > 100) throw bad("Invalid pagination");
        String from =
                " FROM mate_semantic_graph g JOIN mate_semantic_ontology_revision r ON"
                    + " r.id=g.ontology_revision_id JOIN mate_wiki_knowledge_base k ON k.id=g.kb_id"
                    + " AND k.workspace_id=g.workspace_id AND k.deleted=0 WHERE r.ontology_id=? AND"
                    + " g.workspace_id=?";
        long total =
                jdbc.queryForObject(
                        "SELECT COUNT(*)" + from, Long.class, ontologyId, Long.valueOf(scope));
        var items =
                jdbc.query(
                        "SELECT"
                            + " g.id,g.kb_id,k.name,g.ontology_revision_id,r.version,g.enabled,g.mutation_version"
                                + from
                                + " ORDER BY g.id LIMIT ? OFFSET ?",
                        (rs, n) ->
                                new Usage(
                                        rs.getString("id"),
                                        rs.getString("kb_id"),
                                        rs.getString("name"),
                                        rs.getString("ontology_revision_id"),
                                        rs.getInt("version"),
                                        rs.getBoolean("enabled"),
                                        rs.getLong("mutation_version")),
                        ontologyId,
                        Long.valueOf(scope),
                        pageSize,
                        ((long) page - 1) * pageSize);
        session.clearCache();
        access.require(scope, "viewer");
        return new UsagePage(items, total, page, pageSize);
    }

    @Transactional(readOnly = true, timeout = 5, isolation = Isolation.READ_COMMITTED)
    public Report analyze(String scope, String ontologyId, Request request) {
        access.require(scope, "admin");
        if (request == null
                || request.graphId() == null
                || (request.targetRevisionId() == null) == (request.expectedDraftVersion() == null))
            throw bad("Choose one saved draft version or published target revision");
        long deadline = System.nanoTime() + 4_800_000_000L;
        var graph = graphs.requireGraph(scope, request.graphId(), false);
        var source = domain.ontology(graph);
        if (!source.ontologyId().value().equals(ontologyId))
            throw new SemanticApiException(404, "NOT_FOUND", "Graph is not bound to this ontology");
        Target target = target(scope, ontologyId, request);
        wire.reject(wire.violations(target.definition()));
        var revision =
                new OntologyRevision(
                        new OntologyRevisionId(target.id()),
                        new OntologyId(ontologyId),
                        target.version(),
                        wire.core(target.definition()));
        String classification =
                new OntologyChangeClassifier()
                        .classify(source.definition(), revision.definition())
                        .definitionChangeClass()
                        .name();
        var entities = domain.entities(graph);
        if (entities.size() > 1000) throw incomplete();
        var findings = new Findings();
        Set<String> declared = new HashSet<>();
        revision.definition().types().forEach(t -> declared.add(t.key()));
        entities.values().stream()
                .sorted(Comparator.comparing(e -> e.entityId().value()))
                .filter(e -> !declared.contains(e.typeKey()))
                .forEach(
                        e ->
                                findings.add(
                                        new Diagnostic(
                                                "ENTITY",
                                                e.entityId().value(),
                                                null,
                                                "UNKNOWN_ENTITY_TYPE",
                                                "Entity type is absent from target ontology",
                                                e.typeKey())));
        List<Row> rows =
                jdbc.query(
                        "SELECT s.id,r.revision,r.review_status,r.content_json FROM"
                            + " mate_semantic_statement s JOIN mate_semantic_statement_revision r"
                            + " ON r.statement_id=s.id AND r.revision=s.current_revision WHERE"
                            + " s.graph_id=? AND r.review_status IN ('ACCEPTED','PROPOSED') ORDER"
                            + " BY s.id LIMIT 10001",
                        (rs, n) ->
                                new Row(
                                        "STATEMENT",
                                        rs.getString("id"),
                                        rs.getInt("revision"),
                                        null,
                                        rs.getString("content_json")),
                        graph.getId());
        List<Row> proposals =
                jdbc.query(
                        "SELECT id,expected_revision,target_statement_id,payload_json FROM"
                            + " mate_semantic_change_proposal WHERE graph_id=? AND status='PENDING'"
                            + " ORDER BY operation_id LIMIT 10001",
                        (rs, n) ->
                                new Row(
                                        "CHANGE_PROPOSAL",
                                        rs.getString("id"),
                                        rs.getInt("expected_revision"),
                                        rs.getString("target_statement_id"),
                                        rs.getString("payload_json")),
                        graph.getId());
        if (rows.size() > 10000 || proposals.size() > 10000) throw incomplete();
        List<Checked> checked = new ArrayList<>();
        var validator = new StatementValidator();
        for (Row row : concat(rows, proposals)) {
            deadline(deadline);
            try {
                var payload = wire.decode(row.payload(), ProposeRequest.class);
                var original = domain.content(graph, payload);
                var content =
                        new StatementContent(
                                original.scope(),
                                revision.revisionId(),
                                original.subjectId(),
                                original.predicate(),
                                original.value(),
                                original.validity(),
                                original.evidenceIds());
                var report = validator.validate(domain.scope(graph), revision, content, entities);
                if (report.valid()) checked.add(new Checked(row, content));
                else
                    report.violations().stream()
                            .filter(
                                    v ->
                                            v.severity()
                                                    == vip.mate.semantic.core.validation.Violation
                                                            .Severity.ERROR)
                            .forEach(
                                    v ->
                                            findings.add(
                                                    new Diagnostic(
                                                            row.kind(),
                                                            row.id(),
                                                            row.revision(),
                                                            v.code(),
                                                            v.message(),
                                                            payload.predicateKey())));
            } catch (SemanticApiException e) {
                findings.add(
                        new Diagnostic(
                                row.kind(),
                                row.id(),
                                row.revision(),
                                e.code(),
                                "Stored content cannot be evaluated against target ontology",
                                null));
            }
        }
        conflicts(revision, checked, findings, deadline);
        deadline(deadline);
        // READ_COMMITTED also needs a fresh MyBatis session view for the final CAS/auth check.
        session.clearCache();
        var current = graphs.requireGraph(scope, graph.getId(), false);
        if (!graph.getMutationVersion().equals(current.getMutationVersion())
                || !graph.getOntologyRevisionId().equals(current.getOntologyRevisionId()))
            throw stale();
        Target latest;
        try {
            latest = target(scope, ontologyId, request);
        } catch (SemanticApiException e) {
            if (e.status() == 404) throw stale();
            throw e;
        }
        if (!target.equals(latest)) throw stale();
        access.require(scope, "admin");
        return new Report(
                graph.getId(),
                graph.getOntologyRevisionId(),
                target.id(),
                target.draftVersion(),
                OntologyPackageService.definitionDigest(target.definition()),
                graph.getMutationVersion(),
                Instant.now(),
                classification,
                findings.empty() ? "CONFORMS" : "VIOLATIONS",
                entities.size(),
                rows.size(),
                proposals.size(),
                findings.count("ENTITY"),
                findings.count("STATEMENT"),
                findings.count("CHANGE_PROPOSAL"),
                findings.truncated(),
                findings.details());
    }

    private Target target(String scope, String ontologyId, Request request) {
        if (request.expectedDraftVersion() != null) {
            var draft = ontologies.getDraft(scope, ontologyId);
            if (request.expectedDraftVersion() < 1
                    || draft.draftVersion() != request.expectedDraftVersion()) throw stale();
            return new Target(
                    draft.id(), draft.version(), draft.draftVersion(), draft.definition());
        }
        var revision = ontologies.revision(scope, ontologyId, request.targetRevisionId());
        return new Target(revision.id(), revision.version(), null, revision.definition());
    }

    private static List<Row> concat(List<Row> a, List<Row> b) {
        var result = new ArrayList<>(a);
        result.addAll(b);
        return result;
    }

    private record Target(
            String id, int version, Long draftVersion, OntologyDtos.Definition definition) {}

    private record Row(
            String kind, String id, int revision, String targetStatementId, String payload) {}

    private record Checked(Row row, StatementContent content) {}

    private static final class Findings {
        private final Map<String, Set<String>> ids = new HashMap<>();
        private final List<Diagnostic> details = new ArrayList<>();
        private int findings;

        void add(Diagnostic d) {
            ids.computeIfAbsent(d.kind(), k -> new HashSet<>()).add(d.id());
            findings++;
            if (details.size() < 200) details.add(d);
        }

        int count(String kind) {
            return ids.getOrDefault(kind, Set.of()).size();
        }

        boolean empty() {
            return ids.isEmpty();
        }

        boolean truncated() {
            return findings > details.size();
        }

        List<Diagnostic> details() {
            return List.copyOf(details);
        }
    }

    private void conflicts(
            OntologyRevision ontology, List<Checked> rows, Findings findings, long deadline) {
        Set<PredicateRef> single = new HashSet<>();
        ontology.definition().properties().stream()
                .filter(p -> p.multiplicity() == Multiplicity.SINGLE)
                .forEach(p -> single.add(PredicateRef.property(p.key())));
        ontology.definition().relations().stream()
                .filter(r -> r.multiplicity() == Multiplicity.SINGLE)
                .forEach(r -> single.add(PredicateRef.relation(r.key())));
        Map<List<Object>, List<Checked>> groups = new LinkedHashMap<>();
        rows.stream()
                .filter(r -> single.contains(r.content().predicate()))
                .forEach(
                        r ->
                                groups.computeIfAbsent(
                                                List.of(
                                                        r.content().subjectId(),
                                                        r.content().predicate()),
                                                k -> new ArrayList<>())
                                        .add(r));
        var detector = new ConflictDetector();
        for (var group : groups.values()) {
            group.sort(
                    Comparator.comparing(
                            r -> r.content().validity().fromInclusive(),
                            Comparator.nullsFirst(Comparator.naturalOrder())));
            Map<Object, Set<Checked>> active = new LinkedHashMap<>();
            Map<Object, Set<Checked>> unresolved = new LinkedHashMap<>();
            var ending =
                    new PriorityQueue<Checked>(
                            Comparator.comparing(r -> r.content().validity().toExclusive()));
            for (Checked current : group) {
                deadline(deadline);
                Instant start = current.content().validity().fromInclusive();
                // Half-open intervals expire before processing a row beginning at the same instant.
                // UNKNOWN has no endpoints, sorts first and stays active for every later interval.
                while (start != null
                        && !ending.isEmpty()
                        && !ending.peek().content().validity().toExclusive().isAfter(start)) {
                    Checked expired = ending.remove();
                    remove(active, expired);
                    remove(unresolved, expired);
                }
                Object key = valueKey(current.content().value());
                Checked witness = null;
                for (var bucket : active.entrySet()) {
                    if (bucket.getKey().equals(key)) continue;
                    for (Checked other : bucket.getValue()) {
                        deadline(deadline);
                        if (!replacementPair(current.row(), other.row())) {
                            witness = other;
                            break;
                        }
                    }
                    if (witness != null) break;
                }
                if (witness != null) finding(ontology, detector, findings, current, witness);
                // Each unresolved row is removed on its first conflict or expiry; no pair list.
                for (var buckets = unresolved.entrySet().iterator(); buckets.hasNext(); ) {
                    var bucket = buckets.next();
                    if (bucket.getKey().equals(key)) continue;
                    for (var rowsLeft = bucket.getValue().iterator(); rowsLeft.hasNext(); ) {
                        Checked other = rowsLeft.next();
                        deadline(deadline);
                        if (replacementPair(current.row(), other.row())) continue;
                        finding(ontology, detector, findings, other, current);
                        rowsLeft.remove();
                    }
                    if (bucket.getValue().isEmpty()) buckets.remove();
                }
                active.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(current);
                if (witness == null)
                    unresolved.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(current);
                if (current.content().validity().toExclusive() != null) ending.add(current);
            }
        }
    }

    private static void remove(Map<Object, Set<Checked>> buckets, Checked row) {
        Object key = valueKey(row.content().value());
        Set<Checked> bucket = buckets.get(key);
        if (bucket != null) {
            bucket.remove(row);
            if (bucket.isEmpty()) buckets.remove(key);
        }
    }

    private static void finding(
            OntologyRevision ontology,
            ConflictDetector detector,
            Findings findings,
            Checked current,
            Checked witness) {
        detector.compare(ontology, current.content(), witness.content())
                .ifPresent(
                        kind ->
                                findings.add(
                                        new Diagnostic(
                                                current.row().kind(),
                                                current.row().id(),
                                                current.row().revision(),
                                                kind.name(),
                                                "Conflicts with "
                                                        + witness.row().kind()
                                                        + " "
                                                        + witness.row().id()
                                                        + " under target single-value rule",
                                                current.content().predicate().key())));
    }

    private static boolean replacementPair(Row a, Row b) {
        return ("CHANGE_PROPOSAL".equals(a.kind())
                        && "STATEMENT".equals(b.kind())
                        && b.id().equals(a.targetStatementId()))
                || ("CHANGE_PROPOSAL".equals(b.kind())
                        && "STATEMENT".equals(a.kind())
                        && a.id().equals(b.targetStatementId()));
    }

    private static Object valueKey(StatementValue value) {
        return value instanceof StatementValue.DecimalValue d
                ? List.of(d.value().stripTrailingZeros(), Objects.toString(d.unit(), ""))
                : value;
    }

    private static void deadline(long deadline) {
        if (System.nanoTime() > deadline) throw incomplete();
    }

    private static SemanticApiException incomplete() {
        return new SemanticApiException(
                504,
                "IMPACT_INCOMPLETE",
                "Impact analysis did not complete within its resource budget");
    }

    private static SemanticApiException stale() {
        return new SemanticApiException(
                409, "IMPACT_STALE", "Draft or graph changed; analyze again");
    }

    private static SemanticApiException bad(String message) {
        return new SemanticApiException(400, "INVALID_REQUEST", message);
    }
}

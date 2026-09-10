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
        var parsed = wire.parse(ontologyId, target.id(), target.document().source());
        wire.reject(wire.violations(parsed));
        var revision = new OntologyRevision(new OntologyRevisionId(target.id()),
                new OntologyId(ontologyId), target.version(), parsed, target.document().source().policy());
        String classification = source.document().document().documentDigest().equals(parsed.document().documentDigest())
                && source.policy().equals(revision.policy()) ? "UNCHANGED" : "REQUIRES_REVIEW";
        var entities = domain.entities(graph);
        if (entities.size() > 1000) throw incomplete();
        var findings = new Findings();
        Set<String> declared = wire.classIris(parsed);
        entities.values().stream().sorted(Comparator.comparing(e -> e.entityId().value()))
                .forEach(e -> e.assertedTypes().stream().filter(t -> !declared.contains(t)).forEach(t ->
                    findings.add(new Diagnostic("ENTITY", e.entityId().value(), null,
                        "UNKNOWN_ENTITY_TYPE", "Entity type is absent from target ontology", t))));
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
        Map<String,AssertionPayload> assertionCache=new HashMap<>();
        Map<List<String>,vip.mate.semantic.core.validation.ValidationReport> validationCache=new HashMap<>();
        for (Row row : concat(rows, proposals)) {
            deadline(deadline);
            try {
                var payload = wire.decode(row.payload(), ProposeRequest.class);
                var original = domain.content(graph, payload, assertionCache.computeIfAbsent(payload.assertionText(),domain::assertion));
                var content =
                        new StatementContent(
                                original.scope(),
                                revision.revisionId(),
                                original.subjectId(),
                                original.predicate(),
                                original.assertion(),
                                original.validity(),
                                original.evidenceIds());
                var report = validationCache.computeIfAbsent(List.of(payload.subjectId(),payload.assertionText()),
                        key -> domain.validation(revision, graph, content, entities));
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
                                                            original.assertion().predicateIri().orElse(null))));
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
        businessConflicts(revision, checked, entities, findings, deadline);
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
                OntologyPackageService.documentDigest(target.document().source()),
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
                    draft.id(), draft.version(), draft.draftVersion(), draft.document());
        }
        var revision = ontologies.revision(scope, ontologyId, request.targetRevisionId());
        return new Target(revision.id(), revision.version(), null, revision.document());
    }

    private static List<Row> concat(List<Row> a, List<Row> b) {
        var result = new ArrayList<>(a);
        result.addAll(b);
        return result;
    }

    private record Target(
            String id, int version, Long draftVersion, OntologyDtos.DocumentView document) {}

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
        // Only opposite assertions for the same value can be explicit contradictions.
        // Grouping first avoids quadratic work for ordinary multi-valued facts.
        Map<List<Object>, List<Checked>> groups=new LinkedHashMap<>();
        for (var row:rows) {
            var assertion=row.content().assertion();
            if (!assertion.objectAssertion() && !assertion.dataAssertion() && !assertion.identityAssertion()) continue;
            var key=List.<Object>of(row.content().subjectId(),row.content().predicate(),
                    assertion.objectIri(),assertion.literal(),assertion.relatedIndividualIri());
            groups.computeIfAbsent(key,k->new ArrayList<>()).add(row);
        }
        var detector=new ConflictDetector();
        for (var group:groups.values()) {
            var positive=group.stream().filter(r->!oppositeSide(r.content().assertion())).toList();
            var negative=group.stream().filter(r->oppositeSide(r.content().assertion())).toList();
            for (var left:positive) for (var right:negative) {
                deadline(deadline);
                if (replacementPair(left.row(),right.row())) continue;
                detector.compare(ontology,left.content(),right.content()).ifPresent(kind->{
                    for (var item:List.of(left,right)) findings.add(new Diagnostic(item.row().kind(),
                            item.row().id(),item.row().revision(),kind.name(),
                            "Explicit contradictory assertions require review",
                            item.content().assertion().predicateIri().orElse(null)));
                });
            }
        }
    }
    private static boolean oppositeSide(AssertionPayload assertion) {
        return assertion.negative() || assertion.kind()==AssertionPayload.AssertionKind.DIFFERENT_INDIVIDUAL;
    }

    private void businessConflicts(OntologyRevision ontology,List<Checked> rows,Map<EntityId,Entity> entities,
            Findings findings,long deadline) {
        if (ontology.policy().rules().stream().noneMatch(v->v.singleValue())) return;
        Map<List<Object>,List<Checked>> slots=new LinkedHashMap<>();
        for (var row:rows) {
            var content=row.content();var subject=entities.get(content.subjectId());
            if (subject==null || content.predicate().isEmpty() || content.assertion().negative()) continue;
            if (ontology.policy().rules().stream().noneMatch(rule->rule.singleValue()
                    && subject.assertedTypes().contains(rule.classIri())
                    && rule.predicateIri().equals(content.predicate().orElseThrow().iri()))) continue;
            slots.computeIfAbsent(List.of(content.subjectId(),content.predicate()),k->new ArrayList<>()).add(row);
        }
        var detector=new ConflictDetector();
        for (var slot:slots.values()) {
            if (slot.stream().map(v->v.content().assertion()).distinct().limit(2).count()<2) continue;
            for (int i=0;i<slot.size();i++) for (int j=i+1;j<slot.size();j++) {
                deadline(deadline); var left=slot.get(i);var right=slot.get(j);
                if (replacementPair(left.row(),right.row())) continue;
                detector.compare(ontology,left.content(),right.content(),entities.get(left.content().subjectId()).assertedTypes())
                        .ifPresent(kind->{for (var item:List.of(left,right)) findings.add(new Diagnostic(item.row().kind(),
                                item.row().id(),item.row().revision(),kind.name(),"Explicit business single-value policy needs review",
                                item.content().assertion().predicateIri().orElse(null)));});
            }
        }
    }

    private static boolean replacementPair(Row a, Row b) {
        return ("CHANGE_PROPOSAL".equals(a.kind())
                        && "STATEMENT".equals(b.kind())
                        && b.id().equals(a.targetStatementId()))
                || ("CHANGE_PROPOSAL".equals(b.kind())
                        && "STATEMENT".equals(a.kind())
                        && a.id().equals(b.targetStatementId()));
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

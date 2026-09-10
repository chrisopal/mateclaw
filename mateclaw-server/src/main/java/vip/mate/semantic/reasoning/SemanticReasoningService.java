package vip.mate.semantic.reasoning;

import static vip.mate.semantic.reasoning.SemanticReasoningDtos.*;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import vip.mate.semantic.core.ontology.OntologyDocument;
import vip.mate.semantic.core.ontology.OntologyRevision;
import vip.mate.semantic.core.reasoning.ReasoningPort;
import vip.mate.semantic.core.reasoning.ReasoningRequest;
import vip.mate.semantic.core.reasoning.ReasoningRequest.AssertionScope;
import vip.mate.semantic.core.reasoning.ReasoningRequest.FactSnapshot;
import vip.mate.semantic.core.reasoning.ReasoningRequest.Task;
import vip.mate.semantic.core.reasoning.ReasoningResult;
import vip.mate.semantic.graph.GraphRow;
import vip.mate.semantic.query.SemanticQueryDtos.EvidenceResult;
import vip.mate.semantic.query.SemanticQueryService;
import vip.mate.semantic.security.SemanticAccessService;
import vip.mate.semantic.statement.SemanticDomainMapper;
import vip.mate.semantic.statement.StatementApplicationService;
import vip.mate.semantic.web.SemanticApiException;
import vip.mate.semantic.web.StatementDtos.StatementView;

/**
 * Authorized, snapshot-based gateway to the isolated OWL reasoner.
 *
 * <p>The worker runs without a database transaction held open. The graph,
 * pinned revision, accepted facts and their evidence are read before launch,
 * then all authorization and snapshot identities are checked again after the
 * child process exits.</p>
 */
@Service
@ConditionalOnProperty(name = "mateclaw.semantic.enabled", havingValue = "true")
public class SemanticReasoningService {
    private final SemanticQueryService queries;
    private final SemanticDomainMapper domain;
    private final StatementApplicationService statements;
    private final SemanticAccessService access;
    private final ReasoningPort worker;
    private final SemanticReasoningProperties properties;

    public SemanticReasoningService(
            SemanticQueryService queries,
            SemanticDomainMapper domain,
            StatementApplicationService statements,
            SemanticAccessService access,
            ReasoningPort worker,
            SemanticReasoningProperties properties) {
        this.queries = queries;
        this.domain = domain;
        this.statements = statements;
        this.access = access;
        this.worker = worker;
        this.properties = properties;
    }

    public Result reason(String scope, String actorId, Long agentId, String graphId, Request request) {
        return reasonPinned(scope, actorId, agentId, graphId, request).result();
    }

    /** Internal continuation state, never accepted from an HTTP/tool caller. */
    public record PinnedResult(Result result, Request request, String snapshotDigest) {}

    public PinnedResult reasonPinned(String scope, String actorId, Long agentId, String graphId, Request request) {
        access.requireActor(scope, actorId, "viewer");
        if (!properties.isEnabled()) {
            throw new SemanticApiException(409, "REASONING_DISABLED", "Semantic reasoning is disabled");
        }
        Request normalized = normalize(request);
        Snapshot before = snapshot(scope, actorId, agentId, graphId, normalized);
        ReasoningRequest workerRequest = toWorkerRequest(before, normalized);
        ReasoningResult workerResult;
        try {
            workerResult = worker.reason(workerRequest);
        } catch (RuntimeException exception) {
            throw new SemanticApiException(503, "REASONING_UNAVAILABLE",
                    "Reasoning worker did not return a result");
        }
        Snapshot after = snapshot(scope, actorId, agentId, graphId, normalized);
        if (!before.snapshotDigest().equals(after.snapshotDigest())) {
            throw stale();
        }
        return new PinnedResult(result(graphId, before, normalized, workerResult), normalized, before.snapshotDigest());
    }

    /** Rechecks full input evidence and authorization without restarting the worker. */
    public void revalidate(String scope, String actorId, Long agentId, String graphId, PinnedResult pinned) {
        access.requireActor(scope, actorId, "viewer");
        if (!properties.isEnabled()) {
            throw new SemanticApiException(409, "REASONING_DISABLED", "Semantic reasoning is disabled");
        }
        if (!pinned.snapshotDigest().equals(snapshot(scope, actorId, agentId, graphId, pinned.request()).snapshotDigest())) {
            throw stale();
        }
    }

    private Request normalize(Request request) {
        if (request == null) {
            throw bad("Reasoning request is required");
        }
        AssertionScope scope = request.scope() == null ? AssertionScope.TBOX_ONLY : request.scope();
        if (request.task() == null) {
            throw bad("Reasoning task is required");
        }
        var task = request.task();
        if (task == vip.mate.semantic.core.reasoning.ReasoningRequest.TaskKind.INSTANCE_TYPES
                && !absolute(request.individualIri())) {
            throw bad("INSTANCE_TYPES requires an absolute individual IRI");
        }
        if (task == vip.mate.semantic.core.reasoning.ReasoningRequest.TaskKind.AXIOM_ENTAILMENT
                && (request.axiomFunctionalSyntax() == null || request.axiomFunctionalSyntax().isBlank())) {
            throw bad("AXIOM_ENTAILMENT requires an assertion axiom");
        }
        if (request.asOf() != null && request.asOf().isAfter(Instant.now().plusSeconds(60))) {
            throw bad("asOf must not be in the future");
        }
        return new Request(scope, task, request.individualIri(), request.axiomFunctionalSyntax(),
                request.asOf() == null ? Instant.now() : request.asOf());
    }

    private Snapshot snapshot(String scope, String actorId, Long agentId, String graphId, Request request) {
        GraphRow graph = queries.requireAgentGraph(scope, actorId, agentId, graphId);
        OntologyRevision ontology = domain.ontology(graph);
        List<FactInput> facts = request.scope().includesAcceptedFacts()
                ? acceptedFacts(scope, actorId, agentId, graphId, request.asOf())
                : List.of();
        List<String> ontologyABox = request.scope().includesOntologyABox()
                ? ontologyABox(ontology)
                : List.of();
        String digest = snapshotDigest(graph, ontology, request, ontologyABox, facts);
        return new Snapshot(graph, ontology, ontologyABox, facts, digest);
    }

    private List<FactInput> acceptedFacts(String scope, String actorId, Long agentId, String graphId, Instant asOf) {
        List<StatementView> accepted = statements.trustedAsActor(scope, actorId, graphId);
        List<FactInput> result = new ArrayList<>();
        for (StatementView fact : accepted) {
            if (!validAt(fact, asOf)) {
                continue;
            }
            List<EvidenceResult> evidence = new ArrayList<>();
            for (String evidenceId : fact.evidenceIds()) {
                queries.availableEvidenceAsAgent(scope,actorId,agentId,graphId,evidenceId).ifPresent(evidence::add);
            }
            if (evidence.isEmpty()) {
                throw new SemanticApiException(422, "EVIDENCE_REQUIRED", "Accepted facts require evidence");
            }
            evidence.sort(Comparator.comparing(EvidenceResult::id));
            String snapshotId = evidence.stream().map(EvidenceResult::snapshotId).distinct().sorted()
                    .reduce((left, right) -> left + "," + right).orElseThrow();
            String evidenceDigest = digestEvidence(evidence);
            FactSnapshot input = new FactSnapshot(fact.id()+":"+fact.revision(), fact.assertion().functionalSyntax(), snapshotId,
                    fact.createdAt(), Optional.ofNullable(fact.validFrom()), Optional.ofNullable(fact.validTo()),
                    evidenceDigest);
            result.add(new FactInput(input, new FactProvenance(fact.id(), fact.revision(), snapshotId,
                    evidence.stream().map(EvidenceResult::id).collect(java.util.stream.Collectors.toUnmodifiableSet()), evidenceDigest)));
        }
        return List.copyOf(result);
    }

    private ReasoningRequest toWorkerRequest(Snapshot snapshot, Request request) {
        List<FactSnapshot> facts = snapshot.facts().stream().map(FactInput::input).toList();
        List<String> abox = request.scope().includesOntologyABox() ? snapshot.ontologyABox() : List.of();
        Optional<ReasoningRequest.TimeRange> range = Optional.of(new ReasoningRequest.TimeRange(
                Optional.of(request.asOf()), Optional.of(request.asOf().plusNanos(1))));
        Task task = switch (request.task()) {
            case CONSISTENCY -> Task.consistency();
            case CLASSIFICATION -> Task.classification();
            case INSTANCE_TYPES -> Task.instanceTypes(request.individualIri());
            case AXIOM_ENTAILMENT -> Task.entailment(request.axiomFunctionalSyntax());
        };
        return new ReasoningRequest(ReasoningRequest.SCHEMA_VERSION, UUID.randomUUID().toString(),
                snapshot.ontology().document().document(), snapshot.ontology().document().lockedImports(), request.scope(),
                abox, facts, range, task, ReasoningRequest.Engine.hermit());
    }

    private Result result(String graphId, Snapshot snapshot, Request request, ReasoningResult workerResult) {
        return new Result("semantic-reasoning-v1", UUID.randomUUID().toString(), graphId,
                snapshot.graph().getMutationVersion(), snapshot.graph().getOntologyRevisionId(),
                snapshot.ontology().document().document().documentDigest(), snapshot.ontology().document().document().importLockDigest(),
                request.scope(), request.task(), workerResult.engineName(), workerResult.engineVersion(),
                workerResult.requestDigest(), workerResult.status(), workerResult.durationMillis(),
                workerResult.classRelations(), workerResult.individualTypes(),
                snapshot.facts().stream().map(FactInput::provenance).toList(), workerResult.diagnostics(),
                "UNAVAILABLE");
    }

    private static List<String> ontologyABox(OntologyRevision ontology) {
        Set<String> types = Set.of("ClassAssertion", "ObjectPropertyAssertion", "NegativeObjectPropertyAssertion",
                "DataPropertyAssertion", "NegativeDataPropertyAssertion", "SameIndividual", "DifferentIndividuals");
        return ontology.document().axioms().stream().filter(axiom -> types.contains(axiom.axiomType()))
                .map(vip.mate.semantic.core.ontology.OntologyAxiomDescriptor::rendering).toList();
    }

    private static String snapshotDigest(GraphRow graph, OntologyRevision ontology, Request request,
            List<String> ontologyABox, List<FactInput> facts) {
        StringBuilder value = new StringBuilder();
        append(value, graph.getMutationVersion());
        append(value, graph.getOntologyRevisionId());
        append(value, ontology.document().document().documentDigest());
        append(value, ontology.document().document().importLockDigest());
        append(value, request.scope());
        append(value, request.task());
        append(value, request.individualIri());
        append(value, request.axiomFunctionalSyntax());
        append(value, request.asOf());
        ontologyABox.stream().sorted().forEach(item -> append(value, item));
        facts.stream().map(FactInput::input).sorted(Comparator.comparing(FactSnapshot::factId)).forEach(fact -> {
            append(value, fact.factId()); append(value, fact.assertionFunctionalSyntax()); append(value, fact.snapshotId());
            append(value, fact.capturedAt()); append(value, fact.validFrom()); append(value, fact.validTo()); append(value, fact.evidenceDigest());
        });
        facts.stream().map(FactInput::provenance).sorted(Comparator.comparing(FactProvenance::factId))
                .forEach(fact -> append(value, fact.revision()));
        return OntologyDocument.sha256(value.toString());
    }

    private static String digestEvidence(List<EvidenceResult> evidence) {
        StringBuilder canonical=new StringBuilder();
        evidence.stream().sorted(Comparator.comparing(EvidenceResult::id)).forEach(item->{
            append(canonical,item.id());append(canonical,item.snapshotId());append(canonical,item.textDigest());
            append(canonical,item.exactQuote());append(canonical,item.startCodePoint());append(canonical,item.endCodePoint());
        });
        return OntologyDocument.sha256(canonical.toString());
    }

    private static boolean validAt(StatementView fact, Instant at) {
        return !"UNKNOWN".equalsIgnoreCase(fact.validityKind())
                && (fact.validFrom() == null || !at.isBefore(fact.validFrom()))
                && (fact.validTo() == null || at.isBefore(fact.validTo()));
    }

    private static boolean absolute(String value) {
        if (value == null || value.isBlank()) return false;
        try { return URI.create(value).isAbsolute(); } catch (IllegalArgumentException exception) { return false; }
    }

    private static void append(StringBuilder target, Object value) {
        String text=value==null?"":value.toString();
        target.append(text.length()).append(':').append(text);
    }

    private record FactInput(FactSnapshot input, FactProvenance provenance) {}
    private record Snapshot(GraphRow graph, OntologyRevision ontology, List<String> ontologyABox,
            List<FactInput> facts, String snapshotDigest) {}

    private static SemanticApiException bad(String message) {
        return new SemanticApiException(400, "INVALID_REASONING_REQUEST", message);
    }

    private static SemanticApiException stale() {
        return new SemanticApiException(409, "REASONING_STALE",
                "Graph, pinned ontology or accepted evidence changed during reasoning");
    }
}

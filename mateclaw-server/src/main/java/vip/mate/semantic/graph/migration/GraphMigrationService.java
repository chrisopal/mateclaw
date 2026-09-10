package vip.mate.semantic.graph.migration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.semantic.core.fact.AssertionPayload;
import vip.mate.semantic.core.fact.Entity;
import vip.mate.semantic.core.fact.PredicateRef;
import vip.mate.semantic.core.fact.StatementContent;
import vip.mate.semantic.core.fact.Validity;
import vip.mate.semantic.core.conflict.ConflictDetector;
import vip.mate.semantic.core.identity.GraphScope;
import vip.mate.semantic.core.identity.SemanticIds.EntityId;
import vip.mate.semantic.core.identity.SemanticIds.OntologyRevisionId;
import vip.mate.semantic.core.ontology.OntologyDocument;
import vip.mate.semantic.core.ontology.OntologyRevision;
import vip.mate.semantic.core.validation.Violation;
import vip.mate.semantic.graph.EntityRow;
import vip.mate.semantic.graph.GraphApplicationService;
import vip.mate.semantic.graph.GraphOntologyRevisionRow;
import vip.mate.semantic.graph.GraphRow;
import vip.mate.semantic.graph.repository.GraphMapper;
import vip.mate.semantic.governance.SemanticGovernanceService;
import vip.mate.semantic.ontology.OntologyWireMapper;
import vip.mate.semantic.owl.OwlAssertionAdapter;
import vip.mate.semantic.security.SemanticAccessService;
import vip.mate.semantic.statement.SemanticDomainMapper;
import vip.mate.semantic.web.SemanticApiException;
import vip.mate.semantic.web.StatementDtos.ProposeRequest;

import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

import static vip.mate.semantic.graph.migration.GraphMigrationDtos.*;

/** Controlled, staged OWL revision migration for an already populated graph. */
@Service
@ConditionalOnProperty(name = "mateclaw.semantic.enabled", havingValue = "true")
public class GraphMigrationService {
    private static final int MAX_ENTITIES = 1000;
    private static final int MAX_FACTS = 10000;
    private final JdbcTemplate jdbc;
    private final GraphApplicationService graphs;
    private final GraphMapper graphMapper;
    private final SemanticAccessService access;
    private final OntologyWireMapper wire;
    private final OwlAssertionAdapter assertions;
    private final SemanticDomainMapper domain;
    private final SemanticGovernanceService governance;
    private final vip.mate.semantic.ontology.source.OntologySourceReviewService ontologySources;

    public GraphMigrationService(JdbcTemplate jdbc, GraphApplicationService graphs, GraphMapper graphMapper,
            SemanticAccessService access, OntologyWireMapper wire, OwlAssertionAdapter assertions,
            SemanticDomainMapper domain, SemanticGovernanceService governance,
            vip.mate.semantic.ontology.source.OntologySourceReviewService ontologySources) {
        this.jdbc = jdbc;
        this.graphs = graphs;
        this.graphMapper = graphMapper;
        this.access = access;
        this.wire = wire;
        this.assertions = assertions;
        this.domain = domain;
        this.governance = governance;
        this.ontologySources = ontologySources;
    }

    @Transactional
    public PlanView prepare(String scope, String graphId, PrepareRequest request) {
        var actor = access.require(scope, "admin");
        requireOperation(request == null ? null : request.operationId());
        GraphRow graph = graphs.requireGraph(scope, graphId, true);
        String requestDigest = hash(wire.encode(request));
        PlanRow existing = findByOperation(graphId, request.operationId());
        if (existing != null) {
            if (!requestDigest.equals(existing.requestDigest)) conflict("OPERATION_CONFLICT", "Operation id has different migration parameters");
            return view(existing);
        }
        requireExpected(request.expectedGraphVersion(), graph.getMutationVersion());
        requireEnabled(graph);
        if (graphMapper.contentCount(graphId) == 0) conflict("GRAPH_EMPTY", "OWL migration requires a non-empty graph");
        GraphOntologyRevisionRow source = revision(graph, request.sourceRevisionId(), graph.getOntologyRevisionId());
        GraphOntologyRevisionRow target = revision(graph, request.targetRevisionId(), null, true);
        if (!Objects.equals(source.getOntologyId(), target.getOntologyId())) conflict("ONTOLOGY_MISMATCH", "Source and target revisions belong to different ontologies");
        if (Objects.equals(source.getId(), target.getId())) bad("Source and target revisions must differ");
        if (graphMapper.entityCount(graphId) > MAX_ENTITIES)
            conflict("GRAPH_ENTITY_LIMIT", "Graph migration supports at most 1000 entities");
        Long factCount = jdbc.queryForObject("SELECT COUNT(*) FROM mate_semantic_statement s JOIN mate_semantic_statement_revision r ON r.statement_id=s.id AND r.revision=s.current_revision WHERE s.graph_id=? AND r.review_status='ACCEPTED'", Long.class, graphId);
        if (factCount != null && factCount > MAX_FACTS)
            conflict("GRAPH_STATEMENT_LIMIT", "Graph migration supports at most 10000 current facts");
        MappingSet mapping = mappings(request);
        Map<String, String> classes = map(mapping.classes());
        Map<String, String> objectProperties = map(mapping.objectProperties());
        Map<String, String> dataProperties = map(mapping.dataProperties());
        Map<String, String> individuals = map(mapping.individuals());
        Map<String, List<String>> targetSignature = wire.termKinds(target);
        List<Blocker> blockers = new ArrayList<>();
        validateMappingTargets(mapping, targetSignature, blockers);
        FrozenPayload payload = freeze(graph, source, target, classes, objectProperties, dataProperties, individuals, blockers);
        blockers.addAll(governanceBlockers(graph, target.getId()));
        String mappingJson = wire.encode(mapping);
        String payloadJson = wire.encode(payload);
        Impact impact = impact(payload, blockers);
        String impactJson = wire.encode(impact);
        String planDigest = hash(String.join("\u0000", requestDigest, source.getDocumentDigest(), target.getDocumentDigest(),
                source.getImportLockDigest(), target.getImportLockDigest(), mappingJson, payloadJson, impactJson));
        LocalDateTime now = now();
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO mate_semantic_graph_migration_plan(id,graph_id,operation_id,request_digest,plan_digest,status,expected_graph_version,source_revision_id,target_revision_id,source_version,target_version,source_document_digest,target_document_digest,source_import_lock_digest,target_import_lock_digest,mapping_json,payload_json,impact_json,created_by,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, graphId, request.operationId(), requestDigest, planDigest, blockers.isEmpty() ? "PREPARED" : "BLOCKED",
                graph.getMutationVersion(), source.getId(), target.getId(), source.getVersion(), target.getVersion(),
                source.getDocumentDigest(), target.getDocumentDigest(), source.getImportLockDigest(), target.getImportLockDigest(),
                mappingJson, payloadJson, impactJson, actor.getId().toString(), now);
        return view(findById(graphId, id));
    }

    @Transactional(readOnly = true)
    public PlanView get(String scope, String graphId, String planId) {
        access.require(scope, "viewer");
        graphs.requireGraph(scope, graphId, false);
        PlanRow row = findById(graphId, planId);
        if (row == null) throw notFound();
        return view(row);
    }

    @Transactional(readOnly = true)
    public List<TargetRevision> targets(String scope, String graphId) {
        access.require(scope, "viewer");
        GraphRow graph = graphs.requireGraph(scope, graphId, false);
        GraphOntologyRevisionRow source = revision(graph, graph.getOntologyRevisionId(), graph.getOntologyRevisionId());
        return jdbc.query("SELECT id,version,name FROM mate_semantic_ontology_revision WHERE ontology_id=? AND revision_state='PUBLISHED' AND available_for_new_bindings=TRUE AND model_schema=? AND id<>? ORDER BY version,id",
                (rs, n) -> new TargetRevision(rs.getString("id"), rs.getInt("version"), rs.getString("name")),
                source.getOntologyId(), OntologyDocument.MODEL_SCHEMA, source.getId());
    }

    @Transactional
    public PlanView approve(String scope, String graphId, String planId, ApproveRequest request) {
        var actor = access.require(scope, "admin");
        requireOperation(request == null ? null : request.operationId());
        GraphRow graph = graphs.requireGraph(scope, graphId, true);
        PlanRow plan = requirePlan(graphId, planId);
        String digest = hash(wire.encode(request));
        if (request.operationId().equals(plan.approvedOperationId)) {
            if (!digest.equals(plan.approvedRequestDigest)) conflict("OPERATION_CONFLICT", "Approval operation has different parameters");
            return view(plan);
        }
        if (!Objects.equals(request.expectedPlanDigest(), plan.planDigest)) conflict("PLAN_DIGEST_MISMATCH", "Plan changed; reload before approving");
        if (!"PREPARED".equals(plan.status)) conflict("MIGRATION_NOT_PREPARED", "Only a prepared, unblocked plan can be approved");
        requireExpected(plan.expectedGraphVersion, graph.getMutationVersion());
        recheck(graph, plan);
        LocalDateTime now = now();
        if (jdbc.update("UPDATE mate_semantic_graph_migration_plan SET status='APPROVED',approved_operation_id=?,approved_request_digest=?,approved_by=?,approved_at=? WHERE id=? AND graph_id=? AND status='PREPARED'",
                request.operationId(), digest, actor.getId().toString(), now, plan.id, graphId) != 1) conflict("MIGRATION_STATE_CONFLICT", "Migration plan changed concurrently");
        governance.append(graphId, graph.getWorkspaceId(), "GRAPH_MIGRATION", plan.id, null, "OWL_GRAPH_MIGRATION_APPROVE", request.operationId(), actor.getId().toString(), "Approve staged OWL graph migration", wire.encode(request));
        return view(findById(graphId, plan.id));
    }

    @Transactional
    public PlanView execute(String scope, String graphId, String planId, ExecuteRequest request) {
        var actor = access.require(scope, "admin");
        requireOperation(request == null ? null : request.operationId());
        GraphRow graph = graphs.requireGraph(scope, graphId, true);
        PlanRow plan = requirePlan(graphId, planId);
        String digest = hash(wire.encode(request));
        if (request.operationId().equals(plan.executedOperationId)) {
            if (!digest.equals(plan.executedRequestDigest)) conflict("OPERATION_CONFLICT", "Execution operation has different parameters");
            return view(plan);
        }
        if (!Objects.equals(request.expectedPlanDigest(), plan.planDigest)) conflict("PLAN_DIGEST_MISMATCH", "Plan changed; reload before executing");
        if (!"APPROVED".equals(plan.status)) conflict("MIGRATION_NOT_APPROVED", "Migration must be approved before execution");
        requireExpected(plan.expectedGraphVersion, graph.getMutationVersion());
        requireExpected(request.expectedGraphVersion(), graph.getMutationVersion());
        recheck(graph, plan);
        FrozenPayload payload = decode(plan.payloadJson, FrozenPayload.class);
        GraphOntologyRevisionRow target = revision(graph, plan.targetRevisionId, null, true);
        applyEntities(graph, payload.entities(), plan.id);
        for (FrozenFact fact : payload.facts()) appendRevision(graph, fact.statementId(), fact.target(), target.getId(), actor.getId().toString(), "OWL_GRAPH_MIGRATION_EXECUTE");
        long oldVersion = graph.getMutationVersion();
        long newVersion = oldVersion + 1;
        if (jdbc.update("UPDATE mate_semantic_graph SET ontology_revision_id=?,mutation_version=mutation_version+1,updated_at=? WHERE id=? AND mutation_version=?", target.getId(), now(), graphId, oldVersion) != 1) conflict("GRAPH_VERSION_CONFLICT", "Graph changed during migration");
        LocalDateTime at = now();
        if (jdbc.update("UPDATE mate_semantic_graph_migration_plan SET status='EXECUTED',executed_operation_id=?,executed_request_digest=?,executed_graph_version=?,executed_at=? WHERE id=? AND status='APPROVED'", request.operationId(), digest, newVersion, at, plan.id) != 1) conflict("MIGRATION_STATE_CONFLICT", "Migration plan changed concurrently");
        governance.append(graphId, graph.getWorkspaceId(), "GRAPH_MIGRATION", plan.id, null, "OWL_GRAPH_MIGRATION_EXECUTE", request.operationId(), actor.getId().toString(), "Execute staged OWL graph migration", wire.encode(request));
        return view(findById(graphId, plan.id));
    }

    @Transactional
    public PlanView rollback(String scope, String graphId, String planId, RollbackRequest request) {
        var actor = access.require(scope, "admin");
        requireOperation(request == null ? null : request.operationId());
        GraphRow graph = graphs.requireGraph(scope, graphId, true);
        PlanRow plan = requirePlan(graphId, planId);
        String digest = hash(wire.encode(request));
        if (request.operationId().equals(plan.rollbackOperationId)) {
            if (!digest.equals(plan.rollbackRequestDigest)) conflict("OPERATION_CONFLICT", "Rollback operation has different parameters");
            return view(plan);
        }
        if (!Objects.equals(request.expectedPlanDigest(), plan.planDigest)) conflict("PLAN_DIGEST_MISMATCH", "Plan changed; reload before rollback");
        if (!"EXECUTED".equals(plan.status)) conflict("MIGRATION_NOT_EXECUTED", "Only an executed migration can be rolled back");
        if (plan.executedGraphVersion == null || !Objects.equals(plan.executedGraphVersion, graph.getMutationVersion())) conflict("GRAPH_VERSION_CONFLICT", "Graph has changed since migration; rollback is no longer safe");
        requireExpected(request.expectedGraphVersion(), graph.getMutationVersion());
        if (!Objects.equals(graph.getOntologyRevisionId(), plan.targetRevisionId)) conflict("GRAPH_VERSION_CONFLICT", "Graph no longer points at the migration target");
        FrozenPayload payload = decode(plan.payloadJson, FrozenPayload.class);
        GraphOntologyRevisionRow source = revision(graph, plan.sourceRevisionId, null, false);
        restoreEntities(graph, payload.entities(), plan.id);
        for (FrozenFact fact : payload.facts()) appendRevision(graph, fact.statementId(), fact.source(), source.getId(), actor.getId().toString(), "OWL_GRAPH_MIGRATION_ROLLBACK");
        long oldVersion = graph.getMutationVersion();
        long newVersion = oldVersion + 1;
        if (jdbc.update("UPDATE mate_semantic_graph SET ontology_revision_id=?,mutation_version=mutation_version+1,updated_at=? WHERE id=? AND mutation_version=?", source.getId(), now(), graphId, oldVersion) != 1) conflict("GRAPH_VERSION_CONFLICT", "Graph changed during rollback");
        LocalDateTime at = now();
        if (jdbc.update("UPDATE mate_semantic_graph_migration_plan SET status='ROLLED_BACK',rollback_operation_id=?,rollback_request_digest=?,rollback_graph_version=?,rolled_back_at=? WHERE id=? AND status='EXECUTED'", request.operationId(), digest, newVersion, at, plan.id) != 1) conflict("MIGRATION_STATE_CONFLICT", "Migration plan changed concurrently");
        governance.append(graphId, graph.getWorkspaceId(), "GRAPH_MIGRATION", plan.id, null, "OWL_GRAPH_MIGRATION_ROLLBACK", request.operationId(), actor.getId().toString(), "Rollback staged OWL graph migration", wire.encode(request));
        return view(findById(graphId, plan.id));
    }

    private FrozenPayload freeze(GraphRow graph, GraphOntologyRevisionRow source, GraphOntologyRevisionRow target,
            Map<String, String> classes, Map<String, String> objectProperties, Map<String, String> dataProperties,
            Map<String, String> individuals, List<Blocker> blockers) {
        List<EntityRow> rows = graphMapper.entities(graph.getId());
        Map<String, EntityRow> byIri = rows.stream().collect(Collectors.toMap(EntityRow::getIri, x -> x, (a,b)->a, LinkedHashMap::new));
        Set<String> mappedIris = new HashSet<>();
        List<FrozenEntity> entities = new ArrayList<>();
        Set<String> targetClasses = targetClassIris(target);
        for (EntityRow row : rows) {
            String targetIri = individuals.getOrDefault(row.getIri(), row.getIri());
            if (!mappedIris.add(targetIri)) blockers.add(blocker("ENTITY_MERGE_UNSUPPORTED", "individuals", "Multiple entities map to target IRI " + targetIri));
            List<String> oldTypes = decodeTypes(row.getAssertedTypesJson());
            List<String> targetTypes = oldTypes.stream().map(v -> classes.getOrDefault(v, v)).sorted().toList();
            for (String type : targetTypes) if (!targetClasses.contains(type)) blockers.add(blocker("UNKNOWN_TARGET_CLASS", "entities." + row.getId(), "Target ontology does not declare class " + type));
            entities.add(new FrozenEntity(row.getId(), row.getIri(), oldTypes, row.getDisplayName(), targetIri, targetTypes));
        }
        List<FrozenFact> facts = new ArrayList<>();
        List<StatementContent> targetContents = new ArrayList<>();
        OntologyRevision targetOntology = ontology(target);
        jdbc.query("SELECT s.id,r.revision,r.content_json FROM mate_semantic_statement s JOIN mate_semantic_statement_revision r ON r.statement_id=s.id AND r.revision=s.current_revision WHERE s.graph_id=? AND r.review_status='ACCEPTED' ORDER BY s.id", (rs, n) -> {
            // A migration freezes only the current ACCEPTED revision. Rejected or superseded
            // history remains available in the old ontology history and is never interpreted
            // as a new v2 business fact.
            String statementId = rs.getString("id");
            int revision = rs.getInt("revision");
            ProposeRequest sourceRequest = wire.decode(rs.getString("content_json"), ProposeRequest.class);
            try {
                AssertionPayload remapped = assertions.remapRoles(sourceRequest.assertionText(), classes, objectProperties, dataProperties, individuals);
                ProposeRequest targetRequest = new ProposeRequest("migration-fact-" + statementId, sourceRequest.subjectId(), remapped.functionalSyntax(), sourceRequest.validityKind(), sourceRequest.validFrom(), sourceRequest.validTo(), sourceRequest.evidenceIds());
                StatementContent targetContent = targetContent(graph, target, targetRequest, remapped);
                var report = domain.validation(targetOntology, graph, targetContent, targetEntities(graph, entities));
                if (!report.valid()) for (Violation v : report.violations()) if (v.severity() == Violation.Severity.ERROR) blockers.add(blocker(v.code(), "facts." + statementId + "." + v.path(), v.message()));
                facts.add(new FrozenFact(statementId, revision, sourceRequest, targetRequest));
                targetContents.add(targetContent);
            } catch (RuntimeException ex) {
                blockers.add(blocker("ASSERTION_REMAP_INVALID", "facts." + statementId, message(ex)));
            }
            return null;
        }, graph.getId());
        addBusinessPolicyBlockers(targetOntology, targetContents, targetEntities(graph, entities), blockers);
        return new FrozenPayload(List.copyOf(entities), List.copyOf(facts));
    }

    private void addBusinessPolicyBlockers(OntologyRevision target, List<StatementContent> contents,
            Map<EntityId, Entity> entities, List<Blocker> blockers) {
        ConflictDetector detector = new ConflictDetector();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < contents.size(); i++) {
            StatementContent left = contents.get(i);
            Entity subject = entities.get(left.subjectId());
            if (subject == null) continue;
            for (int j = i + 1; j < contents.size(); j++) {
                StatementContent right = contents.get(j);
                if (!left.subjectId().equals(right.subjectId())) continue;
                detector.compare(target, left, right, subject.assertedTypes()).ifPresent(kind -> {
                    if (kind != vip.mate.semantic.core.conflict.ConflictKind.BUSINESS_SINGLE_VALUE
                            && kind != vip.mate.semantic.core.conflict.ConflictKind.BUSINESS_TEMPORAL_UNCERTAINTY) return;
                    String key = kind + "\u0000" + left.subjectId() + "\u0000" + left.predicate().map(PredicateRef::iri).orElse("");
                    if (seen.add(key)) blockers.add(blocker(kind.name(), "facts", "Target business single-value policy would be violated by multiple overlapping values"));
                });
            }
        }
    }

    private StatementContent targetContent(GraphRow graph, GraphOntologyRevisionRow target, ProposeRequest request, AssertionPayload payload) {
        Optional<PredicateRef> predicate = payload.predicateIri().map(iri -> payload.dataAssertion() ? PredicateRef.property(iri) : PredicateRef.relation(iri));
        Validity validity = "UNKNOWN".equals(request.validityKind()) ? Validity.unknown() : Validity.interval(request.validFrom(), request.validTo());
        return new StatementContent(domain.scope(graph), new OntologyRevisionId(target.getId()), new EntityId(request.subjectId()), predicate, payload, validity,
                request.evidenceIds() == null ? Set.of() : request.evidenceIds().stream().map(v -> new vip.mate.semantic.core.identity.SemanticIds.EvidenceId(v)).collect(Collectors.toSet()));
    }

    private Map<EntityId, Entity> targetEntities(GraphRow graph, List<FrozenEntity> frozen) {
        GraphScope scope = domain.scope(graph);
        return frozen.stream().collect(Collectors.toMap(e -> new EntityId(e.entityId()), e -> new Entity(new EntityId(e.entityId()), scope, e.targetIri(), Set.copyOf(e.targetTypes()), e.displayName()), (a,b) -> a, LinkedHashMap::new));
    }

    private OntologyRevision ontology(GraphOntologyRevisionRow row) {
        return new OntologyRevision(new OntologyRevisionId(row.getId()), new vip.mate.semantic.core.identity.SemanticIds.OntologyId(row.getOntologyId()), row.getVersion(), wire.parsed(row), wire.policy(row));
    }

    private void applyEntities(GraphRow graph, List<FrozenEntity> entities, String planId) {
        for (FrozenEntity e : entities) {
            String temporaryIri = "urn:mateclaw:migration:" + planId + ":" + e.entityId();
            jdbc.update("UPDATE mate_semantic_entity SET iri=?,iri_digest=? WHERE id=? AND graph_id=?",
                    temporaryIri, OntologyDocument.sha256(temporaryIri), e.entityId(), graph.getId());
        }
        for (FrozenEntity e : entities) jdbc.update("UPDATE mate_semantic_entity SET iri=?,iri_digest=?,asserted_types_json=? WHERE id=? AND graph_id=?", e.targetIri(), OntologyDocument.sha256(e.targetIri()), wire.encode(e.targetTypes()), e.entityId(), graph.getId());
    }

    private void restoreEntities(GraphRow graph, List<FrozenEntity> entities, String planId) {
        for (FrozenEntity e : entities) {
            String temporaryIri = "urn:mateclaw:rollback:" + planId + ":" + e.entityId();
            jdbc.update("UPDATE mate_semantic_entity SET iri=?,iri_digest=? WHERE id=? AND graph_id=?",
                    temporaryIri, OntologyDocument.sha256(temporaryIri), e.entityId(), graph.getId());
        }
        for (FrozenEntity e : entities) jdbc.update("UPDATE mate_semantic_entity SET iri=?,iri_digest=?,asserted_types_json=? WHERE id=? AND graph_id=?", e.oldIri(), OntologyDocument.sha256(e.oldIri()), wire.encode(e.oldTypes()), e.entityId(), graph.getId());
    }

    private void appendRevision(GraphRow graph, String statementId, ProposeRequest request, String ontologyRevisionId, String actor, String reason) {
        Integer current = jdbc.queryForObject("SELECT current_revision FROM mate_semantic_statement WHERE id=? AND graph_id=? FOR UPDATE", Integer.class, statementId, graph.getId());
        if (current == null) throw notFound();
        int next = current + 1;
        AssertionPayload payload = assertions.parse(request.assertionText());
        String kind = payload.predicateIri().isEmpty() ? "ASSERTION" : payload.literal().isPresent() ? "PROPERTY" : "RELATION";
        LocalDateTime now = now();
        jdbc.update("INSERT INTO mate_semantic_statement_revision(statement_id,revision,graph_id,ontology_revision_id,subject_id,predicate_kind,predicate_iri,assertion_kind,assertion_text,review_status,validity_kind,valid_from,valid_to,value_type,value_text,content_json,actor_id,reason,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                statementId, next, graph.getId(), ontologyRevisionId, request.subjectId(), kind, payload.predicateIri().orElse(null), payload.kind().name(), payload.functionalSyntax(), "ACCEPTED", request.validityKind(), timestamp(request.validFrom()), timestamp(request.validTo()), "OWL_AXIOM", payload.literal().map(AssertionPayload.LiteralValue::lexicalValue).orElse(null), wire.encode(request), actor, reason, now);
        if (request.evidenceIds() != null) for (String evidence : request.evidenceIds()) jdbc.update("INSERT INTO mate_semantic_revision_evidence(statement_id,revision,evidence_id) VALUES(?,?,?)", statementId, next, evidence);
        jdbc.update("UPDATE mate_semantic_statement SET current_revision=? WHERE id=? AND graph_id=? AND current_revision=?", next, statementId, graph.getId(), current);
    }

    private void recheck(GraphRow graph, PlanRow plan) {
        if (!Objects.equals(graph.getOntologyRevisionId(), plan.sourceRevisionId)) conflict("GRAPH_VERSION_CONFLICT", "Graph no longer points at the source revision");
        GraphOntologyRevisionRow source = graphMapper.revision(plan.sourceRevisionId);
        GraphOntologyRevisionRow target = graphMapper.revision(plan.targetRevisionId);
        if (source == null || target == null || !Objects.equals(source.getDocumentDigest(), plan.sourceDocumentDigest)
                || !Objects.equals(target.getDocumentDigest(), plan.targetDocumentDigest)
                || !Objects.equals(source.getImportLockDigest(), plan.sourceImportLockDigest)
                || !Objects.equals(target.getImportLockDigest(), plan.targetImportLockDigest))
            conflict("REVISION_DIGEST_CHANGED", "Pinned ontology document or import lock changed; rebuild the plan");
        if (!"PUBLISHED".equals(target.getRevisionState()) || !Boolean.TRUE.equals(target.getAvailableForNewBindings()))
            conflict("REVISION_UNAVAILABLE", "Target revision is unavailable for new graph bindings");
        if (!governanceBlockers(graph, plan.targetRevisionId).isEmpty()) conflict("MIGRATION_BLOCKED", "Pending source review, proposal, candidate, or extraction work blocks migration");
    }

    private List<Blocker> governanceBlockers(GraphRow graph, String targetRevisionId) {
        String graphId = graph.getId();
        List<Blocker> result = new ArrayList<>();
        ontologySourceBlockers(result, graph, graph.getOntologyRevisionId(), "SOURCE");
        ontologySourceBlockers(result, graph, targetRevisionId, "TARGET");
        countBlocker(result, "M7_SOURCE_REVIEW_PENDING", "sourceChanges", "SELECT COUNT(*) FROM mate_semantic_source_change_item WHERE graph_id=? AND review_state='PENDING'", graphId);
        countBlocker(result, "ONTOLOGY_SOURCE_REVIEW_PENDING", "ontologySourceReviews", "SELECT COUNT(*) FROM mate_semantic_ontology_source_review v JOIN mate_semantic_axiom_source b ON b.id=v.binding_id JOIN mate_semantic_graph g ON g.ontology_revision_id=b.revision_id WHERE g.id=? AND v.review_state='PENDING'", graphId);
        countBlocker(result, "TARGET_ONTOLOGY_SOURCE_REVIEW_PENDING", "targetOntologySourceReviews", "SELECT COUNT(*) FROM mate_semantic_ontology_source_review v JOIN mate_semantic_axiom_source b ON b.id=v.binding_id WHERE b.revision_id=? AND v.review_state='PENDING'", targetRevisionId);
        countBlocker(result, "CHANGE_PROPOSAL_PENDING", "changeProposals", "SELECT COUNT(*) FROM mate_semantic_change_proposal WHERE graph_id=? AND status='PENDING'", graphId);
        countBlocker(result, "CANDIDATE_PENDING", "candidates", "SELECT COUNT(*) FROM mate_semantic_statement s JOIN mate_semantic_statement_revision r ON r.statement_id=s.id AND r.revision=s.current_revision WHERE s.graph_id=? AND r.review_status='PROPOSED'", graphId);
        countBlocker(result, "EXTRACTION_SUGGESTION_PENDING", "extractionSuggestions", "SELECT COUNT(*) FROM mate_semantic_extraction_suggestion s JOIN mate_semantic_extraction_task t ON t.id=s.task_id WHERE t.graph_id=? AND t.status='SUCCEEDED' AND s.status='OPEN'", graphId);
        sourceDigestBlocker(result, graphId);
        return result;
    }

    private void ontologySourceBlockers(List<Blocker> result, GraphRow graph, String revisionId, String side) {
        var revision = graphMapper.revision(revisionId);
        if (revision == null) {
            result.add(blocker(side + "_ONTOLOGY_SOURCE_UNVERIFIED", "ontologySources", "Revision is unavailable"));
            return;
        }
        for (var binding : ontologySources.bindingsForMigration(graph.getWorkspaceId().toString(), revision.getOntologyId(), revisionId)) {
            if ("PENDING".equals(binding.reviewState()) || "REMODEL".equals(binding.reviewState())) {
                result.add(blocker(side + "_ONTOLOGY_SOURCE_REVIEW_PENDING", "ontologySources." + binding.id(),
                        "The current source requires review or remodeling before migration"));
            }
        }
    }

    private void sourceDigestBlocker(List<Blocker> result, String graphId) {
        try {
            Long kb = jdbc.queryForObject("SELECT kb_id FROM mate_semantic_graph WHERE id=?", Long.class, graphId);
            if (kb == null) return;
            jdbc.query("SELECT s.source_kind,s.source_id,s.text_digest FROM mate_semantic_source_snapshot s WHERE s.graph_id=? AND s.capture_version=(SELECT MAX(x.capture_version) FROM mate_semantic_source_snapshot x WHERE x.graph_id=s.graph_id AND x.source_kind=s.source_kind AND x.source_id=s.source_id)", (rs, n) -> {
                if (!"WIKI_RAW".equals(rs.getString("source_kind"))) return null;
                String sourceId = rs.getString("source_id");
                List<String> contents = jdbc.query("SELECT COALESCE(NULLIF(extracted_text,''),original_content) FROM mate_wiki_raw_material WHERE id=? AND kb_id=? AND deleted=0", (inner, i) -> inner.getString(1), Long.valueOf(sourceId), kb);
                if (!contents.isEmpty() && !Objects.equals(rs.getString("text_digest"), OntologyDocument.sha256(Objects.toString(contents.getFirst(), ""))))
                    result.add(blocker("SOURCE_DIGEST_CHANGED", "sources." + sourceId, "A captured source changed after its latest graph snapshot; scan and review it first"));
                return null;
            }, graphId);
        } catch (RuntimeException ignored) {
            result.add(blocker("SOURCE_DIGEST_UNVERIFIED", "sources", "Current source digests could not be verified"));
        }
    }

    private void countBlocker(List<Blocker> result, String code, String path, String sql, String graphId) {
        try { Number count = jdbc.queryForObject(sql, Number.class, graphId); if (count != null && count.longValue() > 0) result.add(blocker(code, path, "Pending work must be resolved before OWL migration")); }
        catch (DataAccessException ignored) { result.add(blocker(code, path, "Pending-work status could not be verified")); }
    }

    private void validateMappingTargets(MappingSet mappings, Map<String, List<String>> signature, List<Blocker> blockers) {
        validateMappingKind(mappings.classes(), "Class", signature, blockers);
        validateMappingKind(mappings.objectProperties(), "ObjectProperty", signature, blockers);
        validateMappingKind(mappings.dataProperties(), "DataProperty", signature, blockers);
        // Individuals are graph identities; collision and assertion checks validate them.
    }

    private void validateMappingKind(List<IriMapping> mappings, String kind,
            Map<String, List<String>> signature, List<Blocker> blockers) {
        for (IriMapping item : mappings) {
            if (!signature.getOrDefault(item.to(), List.of()).contains(kind))
                blockers.add(blocker("UNKNOWN_MAPPING_TARGET", "mapping." + kind,
                        "Target ontology does not contain " + kind + " " + item.to()));
        }
    }

    private Set<String> targetClassIris(GraphOntologyRevisionRow row) { return wire.classIris(row); }

    private Impact impact(FrozenPayload payload, List<Blocker> blockers) {
        int changedEntities = (int) payload.entities().stream().filter(e -> !e.oldIri().equals(e.targetIri()) || !new HashSet<>(e.oldTypes()).equals(new HashSet<>(e.targetTypes()))).count();
        int changedFacts = (int) payload.facts().stream().filter(f -> !f.source().assertionText().equals(f.target().assertionText())).count();
        return new Impact(payload.entities().size(), payload.facts().size(), changedEntities, changedFacts, List.copyOf(blockers), false);
    }

    private PlanView view(PlanRow row) {
        Impact impact = decode(row.impactJson, Impact.class);
        FrozenPayload payload = decode(row.payloadJson, FrozenPayload.class);
        List<EntityPreview> entities = payload.entities().stream().map(e -> new EntityPreview(e.entityId(), e.oldIri(), e.targetIri(), e.oldTypes(), e.targetTypes(), List.of())).toList();
        List<FactPreview> facts = payload.facts().stream().map(f -> new FactPreview(f.statementId(), f.sourceRevision(), f.source().assertionText(), f.target().assertionText(), impact.blockers().isEmpty() ? "READY" : "BLOCKED", List.of())).toList();
        return new PlanView(row.id, row.graphId, row.sourceRevisionId, row.targetRevisionId, row.sourceVersion, row.targetVersion, row.status, row.planDigest,
                row.sourceDocumentDigest, row.targetDocumentDigest, row.sourceImportLockDigest, row.targetImportLockDigest, row.expectedGraphVersion,
                row.executedGraphVersion == null ? 0L : row.executedGraphVersion, impact, entities, facts,
                instant(row.createdAt), instant(row.approvedAt), instant(row.executedAt), instant(row.rolledBackAt));
    }

    private PlanRow requirePlan(String graphId, String id) { PlanRow row = findById(graphId, id); if (row == null) throw notFound(); return row; }
    private PlanRow findById(String graphId, String id) { List<PlanRow> rows = jdbc.query("SELECT * FROM mate_semantic_graph_migration_plan WHERE graph_id=? AND id=?", (rs,n)->plan(rs), graphId, id); return rows.isEmpty()?null:rows.getFirst(); }
    private PlanRow findByOperation(String graphId, String op) { List<PlanRow> rows = jdbc.query("SELECT * FROM mate_semantic_graph_migration_plan WHERE graph_id=? AND operation_id=?", (rs,n)->plan(rs), graphId, op); return rows.isEmpty()?null:rows.getFirst(); }
    private PlanRow plan(ResultSet rs) throws SQLException { return new PlanRow(rs.getString("id"),rs.getString("graph_id"),rs.getString("operation_id"),rs.getString("request_digest"),rs.getString("plan_digest"),rs.getString("status"),rs.getLong("expected_graph_version"),rs.getString("source_revision_id"),rs.getString("target_revision_id"),rs.getInt("source_version"),rs.getInt("target_version"),rs.getString("source_document_digest"),rs.getString("target_document_digest"),rs.getString("source_import_lock_digest"),rs.getString("target_import_lock_digest"),rs.getString("payload_json"),rs.getString("impact_json"),rs.getString("approved_operation_id"),rs.getString("approved_request_digest"),rs.getString("executed_operation_id"),rs.getString("executed_request_digest"),rs.getString("rollback_operation_id"),rs.getString("rollback_request_digest"),getLong(rs,"executed_graph_version"),getLong(rs,"rollback_graph_version"),ts(rs,"created_at"),ts(rs,"approved_at"),ts(rs,"executed_at"),ts(rs,"rolled_back_at")); }
    private static Long getLong(ResultSet rs,String name)throws SQLException{Object v=rs.getObject(name);return v==null?null:((Number)v).longValue();}
    private static LocalDateTime ts(ResultSet rs,String name)throws SQLException{Timestamp t=rs.getTimestamp(name);return t==null?null:t.toLocalDateTime();}

    private GraphOntologyRevisionRow revision(GraphRow graph, String requested, String expected) { return revision(graph, requested, expected, false); }
    private GraphOntologyRevisionRow revision(GraphRow graph, String requested, String expected, boolean requireAvailable) { if (requested == null || requested.isBlank()) bad("revisionId required"); if (expected != null && !expected.equals(requested)) conflict("GRAPH_VERSION_CONFLICT", "Graph is not pinned to the requested source revision"); GraphOntologyRevisionRow row=graphMapper.revision(requested); if(row==null || !Objects.equals(row.getWorkspaceId(),graph.getWorkspaceId()) || !"PUBLISHED".equals(row.getRevisionState())) conflict("REVISION_UNAVAILABLE", "Published revision is required in this workspace"); if(requireAvailable && !Boolean.TRUE.equals(row.getAvailableForNewBindings())) conflict("REVISION_UNAVAILABLE", "Target revision is unavailable for new graph bindings"); if(!OntologyDocument.MODEL_SCHEMA.equals(row.getModelSchema())) conflict("LEGACY_ONTOLOGY_RETIRED", "Only owl-document-v1 revisions can be migrated"); return row; }
    private MappingSet mappings(PrepareRequest request) { return new MappingSet(sorted(request.classes()),sorted(request.objectProperties()),sorted(request.dataProperties()),sorted(request.individuals())); }
    private List<IriMapping> sorted(List<IriMapping> values){ if(values==null)return List.of(); List<IriMapping> result=new ArrayList<>(values); result.sort(Comparator.comparing(IriMapping::from,Comparator.nullsFirst(String::compareTo))); return List.copyOf(result); }
    private Map<String,String> map(List<IriMapping> values){ Map<String,String> result=new LinkedHashMap<>(); for(IriMapping item:values){if(item==null||!absolute(item.from())||!absolute(item.to()))bad("Mapping IRIs must be absolute"); if(result.put(item.from(),item.to())!=null)conflict("DUPLICATE_MAPPING", "Mapping source occurs more than once");} if(new HashSet<>(result.values()).size()!=result.size())conflict("ENTITY_MERGE_UNSUPPORTED", "Multiple source IRIs map to one target IRI"); return Map.copyOf(result); }
    private static boolean absolute(String iri){try{return iri!=null&&!iri.isBlank()&&URI.create(iri).isAbsolute();}catch(RuntimeException e){return false;}}
    private static List<IriMapping> concat(List<IriMapping>... values){List<IriMapping> all=new ArrayList<>();for(List<IriMapping> value:values)all.addAll(value);return all;}
    private List<String> decodeTypes(String json){String[] values=wire.decode(json,String[].class);return Arrays.stream(values).sorted().toList();}
    private static Blocker blocker(String code,String path,String message){return new Blocker(code,path,message);}
    private static String message(Throwable t){return t.getMessage()==null?"Invalid OWL assertion mapping":t.getMessage();}
    private static Instant instant(LocalDateTime value){return value==null?null:value.toInstant(ZoneOffset.UTC);}
    private static Timestamp timestamp(Instant value){return value==null?null:Timestamp.from(value);}
    private static LocalDateTime now(){return LocalDateTime.now(ZoneOffset.UTC);}
    private static void requireOperation(String value){if(value==null||value.isBlank()||value.length()>128)bad("operationId required");}
    private static void requireExpected(Long expected,long actual){if(expected==null||expected<0)bad("expectedGraphVersion required");if(expected!=actual)conflict("GRAPH_VERSION_CONFLICT", "Graph changed; refresh before migration");}
    private static void requireEnabled(GraphRow graph){if(!Boolean.TRUE.equals(graph.getEnabled()))conflict("GRAPH_DISABLED", "Graph is disabled");}
    private static SemanticApiException bad(String message){throw new SemanticApiException(400,"INVALID_REQUEST",message);}
    private static void conflict(String code,String message){throw new SemanticApiException(409,code,message);}
    private static SemanticApiException notFound(){return new SemanticApiException(404,"NOT_FOUND","Migration plan not found in graph");}
    private static String hash(String value){return OntologyDocument.sha256(value);}
    private <T> T decode(String value,Class<T> type){try{return new ObjectMapper().findAndRegisterModules().readValue(value,type);}catch(JsonProcessingException e){throw new IllegalStateException("Stored migration payload is invalid",e);}}

    public record MappingSet(List<IriMapping> classes,List<IriMapping> objectProperties,List<IriMapping> dataProperties,List<IriMapping> individuals){}
    public record FrozenPayload(List<FrozenEntity> entities,List<FrozenFact> facts){}
    public record FrozenEntity(String entityId,String oldIri,List<String> oldTypes,String displayName,String targetIri,List<String> targetTypes){}
    public record FrozenFact(String statementId,int sourceRevision,ProposeRequest source,ProposeRequest target){}
    private record PlanRow(String id,String graphId,String operationId,String requestDigest,String planDigest,String status,long expectedGraphVersion,String sourceRevisionId,String targetRevisionId,int sourceVersion,int targetVersion,String sourceDocumentDigest,String targetDocumentDigest,String sourceImportLockDigest,String targetImportLockDigest,String payloadJson,String impactJson,String approvedOperationId,String approvedRequestDigest,String executedOperationId,String executedRequestDigest,String rollbackOperationId,String rollbackRequestDigest,Long executedGraphVersion,Long rollbackGraphVersion,LocalDateTime createdAt,LocalDateTime approvedAt,LocalDateTime executedAt,LocalDateTime rolledBackAt){}
}

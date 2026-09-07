package vip.mate.semantic.graph;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vip.mate.semantic.ontology.OntologyWireMapper;
import vip.mate.semantic.graph.repository.GraphMapper;
import vip.mate.semantic.security.SemanticAccessService;
import vip.mate.semantic.web.GraphDtos.*;
import vip.mate.semantic.web.OntologyDtos.Definition;
import vip.mate.semantic.web.SemanticApiException;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
@ConditionalOnProperty(name = "mateclaw.semantic.enabled", havingValue = "true")
public class GraphApplicationService {
    private final GraphMapper mapper;
    private final SemanticAccessService access;
    private final OntologyWireMapper wire;

    public GraphApplicationService(GraphMapper mapper, SemanticAccessService access, OntologyWireMapper wire) {
        this.mapper = mapper;
        this.access = access;
        this.wire = wire;
    }

    public Binding get(String scope, String kbId) {
        access.require(scope, "viewer");
        long workspace = workspace(scope), kb = positive(kbId, "knowledgeBaseId");
        requireKb(workspace, kb);
        GraphRow row = mapper.byKnowledgeBase(workspace, kb);
        if (row == null) throw notFound();
        return view(row);
    }

    @Transactional
    public Binding bind(String scope, String kbId, BindRequest request) {
        access.require(scope, "admin");
        if (request == null || request.action() == null)
            throw bad("Binding action required");
        long workspace = workspace(scope), kb = positive(kbId, "knowledgeBaseId");
        requireKb(workspace, kb);
        GraphRow existing = mapper.byKnowledgeBase(workspace, kb);
        if (existing == null) {
            if (request.action() != BindingAction.ENABLE || request.expectedGraphVersion() != null)
                throw conflict("GRAPH_VERSION_CONFLICT", "New binding requires ENABLE without expected version");
            GraphOntologyRevisionRow revision = requireRevision(workspace, request.revisionId(), true);
            GraphRow row = new GraphRow();
            row.setId(id()); row.setWorkspaceId(workspace); row.setKbId(kb);
            row.setOntologyRevisionId(revision.getId()); row.setEnabled(true); row.setMutationVersion(0L);
            row.setCreatedAt(now()); row.setUpdatedAt(row.getCreatedAt());
            try {
                mapper.insert(row);
            } catch (DuplicateKeyException e) {
                throw conflict("GRAPH_ALREADY_BOUND", "Knowledge base already has a semantic graph");
            }
            return view(row, revision);
        }
        GraphRow locked = mapper.lock(existing.getId(), workspace);
        long expected = requireExpected(request.expectedGraphVersion());
        if (!Long.valueOf(expected).equals(locked.getMutationVersion()))
            throw conflict("GRAPH_VERSION_CONFLICT", "Graph binding changed; reload it");
        if (request.action() == BindingAction.REBIND) {
            if (mapper.contentCount(locked.getId()) != 0)
                throw conflict("GRAPH_NOT_EMPTY", "Only an empty graph can change ontology revision");
            locked.setOntologyRevisionId(requireRevision(workspace, request.revisionId(), true).getId());
            locked.setEnabled(true);
        } else if (request.action() == BindingAction.ENABLE) {
            if (request.revisionId() != null && !request.revisionId().equals(locked.getOntologyRevisionId()))
                throw conflict("REBIND_REQUIRED", "Use REBIND to change the pinned ontology revision");
            locked.setEnabled(true);
        } else {
            locked.setEnabled(false);
        }
        locked.setUpdatedAt(now());
        if (mapper.update(locked, expected) != 1)
            throw conflict("GRAPH_VERSION_CONFLICT", "Graph binding changed; reload it");
        locked.setMutationVersion(expected + 1);
        return view(locked);
    }

    public List<Binding> bindings(String scope, String ontologyId) {
        access.require(scope, "viewer");
        return mapper.bindings(ontologyId, workspace(scope)).stream().map(this::view).toList();
    }

    public EntityPage entities(String scope, String graphId) {
        access.require(scope, "viewer");
        GraphRow graph = requireGraph(scope, graphId, false);
        return new EntityPage(mapper.entities(graph.getId()).stream().map(this::entity).toList());
    }

    @Transactional
    public EntityView createEntity(String scope, String graphId, CreateEntity request) {
        var actor = access.require(scope, "member");
        if (request == null || request.typeKey() == null || request.typeKey().isBlank()
                || request.displayName() == null || request.displayName().isBlank())
            throw bad("typeKey and displayName are required");
        if (request.displayName().codePointCount(0, request.displayName().length()) > 256)
            throw new SemanticApiException(422, "INVALID_ENTITY", "displayName exceeds 256 characters");
        GraphRow graph = requireGraph(scope, graphId, true);
        if (!graph.getEnabled()) throw conflict("GRAPH_DISABLED", "Graph is disabled");
        GraphOntologyRevisionRow revision = requireRevision(graph.getWorkspaceId(), graph.getOntologyRevisionId(), false);
        Definition definition = wire.decode(revision.getDefinitionJson(), Definition.class);
        if (definition.types().stream().noneMatch(t -> t.key().equals(request.typeKey())))
            throw new SemanticApiException(422, "UNKNOWN_ENTITY_TYPE", "Entity type is not in the pinned ontology");
        EntityRow row = new EntityRow();
        row.setId(id()); row.setGraphId(graph.getId()); row.setTypeKey(request.typeKey());
        row.setDisplayName(request.displayName()); row.setStatus("ACTIVE");
        row.setCreatedBy(actor.getId().toString()); row.setCreatedAt(now());
        mapper.insertEntity(row);
        if (mapper.touch(graph.getId(), graph.getMutationVersion(), now()) != 1)
            throw conflict("GRAPH_VERSION_CONFLICT", "Graph changed during entity creation");
        return entity(row);
    }

    public GraphRow requireGraph(String scope, String graphId, boolean lock) {
        long workspace = workspace(scope);
        GraphRow row = lock ? mapper.lock(graphId, workspace) : mapper.find(graphId, workspace);
        if (row == null) throw notFound();
        return row;
    }

    private Binding view(GraphRow row) { return view(row, requireRevision(row.getWorkspaceId(), row.getOntologyRevisionId(), false)); }
    private Binding view(GraphRow row, GraphOntologyRevisionRow revision) {
        return new Binding(row.getId(), row.getWorkspaceId().toString(), row.getKbId().toString(),
                row.getOntologyRevisionId(), revision.getVersion(), row.getEnabled(), row.getMutationVersion(),
                mapper.contentCount(row.getId()) == 0, row.getUpdatedAt().toInstant(ZoneOffset.UTC));
    }
    private EntityView entity(EntityRow row) {
        return new EntityView(row.getId(), row.getGraphId(), row.getTypeKey(), row.getDisplayName(), row.getStatus(), row.getCreatedAt().toInstant(ZoneOffset.UTC));
    }
    private GraphOntologyRevisionRow requireRevision(long workspace, String revisionId, boolean mustBeAvailable) {
        if (revisionId == null || revisionId.isBlank()) throw bad("revisionId required");
        GraphOntologyRevisionRow row = mapper.revision(revisionId);
        if (row == null || row.getWorkspaceId() != workspace || !"PUBLISHED".equals(row.getRevisionState())) throw notFound();
        if (mustBeAvailable && !Boolean.TRUE.equals(row.getAvailableForNewBindings()))
            throw conflict("REVISION_UNAVAILABLE", "Ontology revision is closed to new bindings");
        return row;
    }
    private void requireKb(long workspace, long kb) {
        Long owner = mapper.knowledgeBaseWorkspace(kb);
        if (owner == null || owner != workspace) throw notFound();
    }
    private static long requireExpected(Long value) {
        if (value == null || value < 0) throw bad("expectedGraphVersion required");
        return value;
    }
    private static long workspace(String scope) {
        try { return positive(scope, "workspaceId"); } catch (RuntimeException e) { throw bad("Explicit workspace required"); }
    }
    private static long positive(String value, String name) {
        try { long id = Long.parseLong(value); if (id <= 0) throw new NumberFormatException(); return id; }
        catch (RuntimeException e) { throw bad("Positive " + name + " required"); }
    }
    private static String id() { return com.baomidou.mybatisplus.core.toolkit.IdWorker.getIdStr(); }
    private static java.time.LocalDateTime now() { return java.time.LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS); }
    private static SemanticApiException bad(String message) { return new SemanticApiException(400, "INVALID_REQUEST", message); }
    private static SemanticApiException conflict(String code, String message) { return new SemanticApiException(409, code, message); }
    private static SemanticApiException notFound() { return new SemanticApiException(404, "NOT_FOUND", "Semantic resource not found in workspace"); }
}

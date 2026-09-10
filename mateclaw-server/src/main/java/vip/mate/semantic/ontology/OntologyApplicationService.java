package vip.mate.semantic.ontology;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vip.mate.semantic.ontology.repository.OntologyMapper;
import vip.mate.semantic.security.SemanticAccessService;
import vip.mate.semantic.statement.CommandRecordRow;
import vip.mate.semantic.statement.repository.*;
import vip.mate.semantic.web.OntologyDtos.*;
import vip.mate.semantic.web.SemanticApiException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;

@Service
@ConditionalOnProperty(name = "mateclaw.semantic.enabled", havingValue = "true")
public class OntologyApplicationService {
    private final OntologyMapper mapper;
    private final CommandRecordMapper commands;
    private final GovernanceRecordMapper governance;
    private final SemanticAccessService access;
    private final OntologyWireMapper wire;
    private final OntologyAxiomIndex axiomIndex;

    public OntologyApplicationService(
            OntologyMapper mapper,
            CommandRecordMapper commands,
            GovernanceRecordMapper governance,
            SemanticAccessService access,
            OntologyWireMapper wire, OntologyAxiomIndex axiomIndex) {
        this.axiomIndex=axiomIndex;
        this.mapper = mapper;
        this.commands = commands;
        this.governance = governance;
        this.access = access;
        this.wire = wire;
    }

    public Page list(String scope, String query, int page, int pageSize) {
        access.require(scope, "viewer");
        if (page < 1 || pageSize < 1 || pageSize > 100 || query.length() > 128)
            throw new SemanticApiException(400, "INVALID_REQUEST", "Invalid pagination or search");
        String pattern = "%" + query.toLowerCase(Locale.ROOT) + "%";
        return new Page(
                mapper
                        .list(
                                Long.parseLong(scope),
                                pattern,
                                pageSize,
                                ((long) page - 1) * pageSize)
                        .stream()
                        .map(wire::ontology)
                        .toList(),
                mapper.count(Long.parseLong(scope), pattern),
                page,
                pageSize);
    }

    @Transactional
    public OntologyView create(String scope, Metadata request) {
        access.require(scope, "member");
        wire.metadata(request.name(), request.description());
        var row = new OntologyRow();
        row.setId(id());
        row.setWorkspaceId(Long.parseLong(scope));
        row.setName(request.name());
        row.setDescription(request.description());
        row.setUpdatedAt(now());
        mapper.insert(row);
        return wire.ontology(row);
    }

    public OntologyView get(String scope, String id) {
        access.require(scope, "viewer");
        return wire.ontology(parent(scope, id, false));
    }

    @Transactional
    public DraftView createDraft(String scope, String id, CreateDraft request) {
        access.require(scope, "member");
        var parent = parent(scope, id, true);
        if (parent.getDraftId() != null)
            throw conflict("DRAFT_EXISTS", "An active draft already exists");
        OntologyRevisionRow base =
                request.baseRevisionId() == null
                        ? null
                        : published(parent, request.baseRevisionId());
        var row = new OntologyRevisionRow();
        row.setId(id());
        row.setOntologyId(id);
        row.setVersion(
                parent.getLatestVersion() == null
                        ? 1
                        : Math.incrementExact(parent.getLatestVersion()));
        row.setDraftVersion(Math.incrementExact(parent.getDraftCounter()));
        row.setRevisionState("DRAFT");
        row.setDraftSlot(1);
        row.setBaseRevisionId(base == null ? null : base.getId());
        row.setName(base == null ? parent.getName() : base.getName());
        row.setDescription(base == null ? parent.getDescription() : base.getDescription());
        wire.store(row, base == null ? wire.empty(id) : wire.document(base).source());
        row.setAvailableForNewBindings(false);
        mapper.insertDraft(row);
        axiomIndex.synchronize(row);
        if(base!=null)axiomIndex.copyBindings(base.getId(),row);
        parent.setDraftId(row.getId());
        parent.setDraftCounter(row.getDraftVersion());
        touch(parent);
        return wire.draft(row);
    }

    public DraftView getDraft(String scope, String id) {
        access.require(scope, "viewer");
        return wire.draft(draft(parent(scope, id, false)));
    }

    @Transactional
    public DraftView saveDraft(String scope, String id, SaveDraft request) {
        access.require(scope, "member");
        var parent = parent(scope, id, true);
        validateOperation(request.operationId());
        String requestHash = hash(wire.encode(request));
        var previous = commands.find(parent.getWorkspaceId(), request.operationId());
        if (previous != null) {
            if (!"SAVE_ONTOLOGY_DRAFT".equals(previous.getKind()) || !id.equals(previous.getResourceId()) || !requestHash.equals(previous.getPayloadHash()))
                throw conflict("OPERATION_CONFLICT", "Operation id already used with different payload");
            return wire.decode(previous.getResultJson(), DraftView.class);
        }
        var row = draft(parent);
        cas(row, request.expectedDraftVersion());
        wire.metadata(request.name(), request.description());
        wire.store(row, request.document());
        row.setName(request.name());
        row.setDescription(request.description());
        if (mapper.saveDraft(row, request.expectedDraftVersion()) != 1)
            throw conflict("DRAFT_CONFLICT", "Draft has changed");
        axiomIndex.synchronize(row);
        row.setDraftVersion(Math.incrementExact(row.getDraftVersion()));
        parent.setDraftCounter(row.getDraftVersion());
        touch(parent);
        var result = wire.draft(row);
        recordDraftCommand(parent, request.operationId(), "SAVE_ONTOLOGY_DRAFT", requestHash, result);
        return result;
    }

    @Transactional
    public DraftView editDraft(String scope, String id, EditDraft request) {
        access.require(scope, "member");
        var parent = parent(scope, id, true);
        validateOperation(request.operationId());
        String requestHash = hash(wire.encode(request));
        var previous = commands.find(parent.getWorkspaceId(), request.operationId());
        if (previous != null) {
            if (!"EDIT_ONTOLOGY_AXIOMS".equals(previous.getKind()) || !id.equals(previous.getResourceId()) || !requestHash.equals(previous.getPayloadHash()))
                throw conflict("OPERATION_CONFLICT", "Operation id already used with different payload");
            return wire.decode(previous.getResultJson(), DraftView.class);
        }
        var row = draft(parent);
        cas(row, request.expectedDraftVersion());
        wire.edit(row, request.changes());
        if (mapper.saveDraft(row, request.expectedDraftVersion()) != 1) throw conflict("DRAFT_CONFLICT", "Draft has changed");
        axiomIndex.synchronize(row);
        row.setDraftVersion(Math.incrementExact(row.getDraftVersion()));
        parent.setDraftCounter(row.getDraftVersion()); touch(parent);
        var result = wire.draft(row);
        recordDraftCommand(parent, request.operationId(), "EDIT_ONTOLOGY_AXIOMS", requestHash, result);
        return result;
    }

    public byte[] exportDraftDocument(String scope, String id, Long expectedDraftVersion,
            vip.mate.semantic.core.ontology.OntologyDocumentSyntax syntax) {
        access.require(scope, "viewer");
        var row = draft(parent(scope, id, false));
        cas(row, expectedDraftVersion);
        return wire.export(row, syntax);
    }

    public byte[] exportDocument(String scope, String id, String revisionId, vip.mate.semantic.core.ontology.OntologyDocumentSyntax syntax) {
        access.require(scope, "viewer");
        return wire.export(published(parent(scope, id, false), revisionId), syntax);
    }

    private void validateOperation(String operationId) {
        var errors = new ArrayList<Violation>();
        wire.text(operationId, "operationId", 128, true, errors); wire.reject(errors);
    }

    private void recordDraftCommand(OntologyRow parent, String operationId, String kind, String requestHash, DraftView result) {
        var command = new CommandRecordRow();
        command.setId(id()); command.setWorkspaceId(parent.getWorkspaceId());
        command.setOperationId(operationId); command.setKind(kind); command.setResourceId(parent.getId());
        command.setPayloadHash(requestHash); command.setResultJson(wire.encode(result)); command.setCreatedAt(now());
        commands.insert(command);
    }

    @Transactional
    public void discard(String scope, String id, Long expected) {
        access.require(scope, "member");
        var parent = parent(scope, id, true);
        var row = draft(parent);
        cas(row, expected);
        axiomIndex.discard(row.getId());
        if (mapper.deleteDraft(row.getId(), expected) != 1)
            throw conflict("DRAFT_CONFLICT", "Draft has changed");
        parent.setDraftId(null);
        touch(parent);
    }

    @Transactional
    public ValidationView validate(String scope, String id, ValidateDraft request) {
        access.require(scope, "member");
        var row = draft(parent(scope, id, true));
        cas(row, request.expectedDraftVersion());
        var violations = wire.violations(row);
        return new ValidationView(row.getDraftVersion(), violations.stream().noneMatch(v->"ERROR".equals(v.severity())), violations, "OWL 2 DL", "NOT_RUN");
    }

    @Transactional
    public RevisionView publish(String scope, String id, PublishDraft request) {
        var actor = access.require(scope, "admin");
        var parent = parent(scope, id, true);
        var fields = new ArrayList<Violation>();
        wire.text(request.operationId(), "operationId", 128, true, fields);
        wire.text(request.note(), "note", 1000, true, fields);
        wire.reject(fields);
        if (request.expectedDraftVersion() == null || request.expectedDraftVersion() < 1)
            throw new SemanticApiException(
                    400, "INVALID_REQUEST", "Positive expectedDraftVersion required");
        String hash =
                hash(wire.encode(List.of(id, request.expectedDraftVersion(), request.note())));
        // Replay is authorized and scoped, then resolved before looking for a draft that a
        // successful publication consumed.
        var replay = commands.find(parent.getWorkspaceId(), request.operationId());
        if (replay != null) {
            if (!"PUBLISH_ONTOLOGY".equals(replay.getKind()) || !hash.equals(replay.getPayloadHash()) || !id.equals(replay.getResourceId()))
                throw conflict(
                        "OPERATION_CONFLICT", "Operation id already used with different payload");
            return wire.decode(replay.getResultJson(), RevisionView.class);
        }
        var row = draft(parent);
        cas(row, request.expectedDraftVersion());
        wire.reject(wire.violations(row));
        row.setPublishedAt(now());
        row.setPublishedBy(actor.getId().toString());
        row.setPublicationNote(request.note());
        row.setAvailableForNewBindings(true);
        if (mapper.publish(row, request.expectedDraftVersion()) != 1)
            throw conflict("DRAFT_CONFLICT", "Draft has changed");
        parent.setDraftId(null);
        parent.setLatestRevisionId(row.getId());
        parent.setLatestVersion(row.getVersion());
        parent.setName(row.getName());
        parent.setDescription(row.getDescription());
        touch(parent);
        var result = wire.revision(row);
        var command = new CommandRecordRow();
        command.setId(id());
        command.setWorkspaceId(parent.getWorkspaceId());
        command.setOperationId(request.operationId());
        command.setKind("PUBLISH_ONTOLOGY");
        command.setResourceId(id);
        command.setPayloadHash(hash);
        command.setResultJson(wire.encode(result));
        command.setCreatedAt(now());
        commands.insert(command);
        governance.insert(
                id(),
                parent.getWorkspaceId(),
                id,
                row.getId(),
                "PUBLISH_ONTOLOGY",
                actor.getId().toString(),
                wire.encode(Map.of("operationId", request.operationId(), "note", request.note())),
                now());
        return result;
    }

    public List<RevisionView> revisions(String scope, String id) {
        access.require(scope, "viewer");
        parent(scope, id, false);
        return mapper.revisions(id).stream().map(wire::revision).toList();
    }

    public RevisionView revision(String scope, String id, String revision) {
        access.require(scope, "viewer");
        return wire.revision(published(parent(scope, id, false), revision));
    }

    @Transactional
    public RevisionView availability(
            String scope, String id, String revision, Availability request) {
        var actor = access.require(scope, "admin");
        var parent = parent(scope, id, true);
        var row = published(parent, revision);
        if (request.availableForNewBindings() == null)
            throw new SemanticApiException(
                    400, "INVALID_REQUEST", "availableForNewBindings required");
        boolean before = row.getAvailableForNewBindings();
        mapper.availability(row.getId(), request.availableForNewBindings());
        row.setAvailableForNewBindings(request.availableForNewBindings());
        governance.insert(
                id(),
                parent.getWorkspaceId(),
                id,
                revision,
                "SET_REVISION_AVAILABILITY",
                actor.getId().toString(),
                wire.encode(Map.of("before", before, "after", request.availableForNewBindings())),
                now());
        touch(parent);
        return wire.revision(row);
    }

    public Operation operation(String scope, String operation) {
        access.require(scope, "admin");
        var row = commands.find(Long.parseLong(scope), operation);
        if (row == null) throw notFound();
        if (!"PUBLISH_ONTOLOGY".equals(row.getKind())) throw new SemanticApiException(400, "UNSUPPORTED_OPERATION_KIND", "This endpoint returns publication operations only");
        parent(scope, row.getResourceId(), false);
        return new Operation(
                row.getOperationId(),
                row.getKind(),
                row.getResourceId(),
                wire.decode(row.getResultJson(), RevisionView.class));
    }

    public Diff diff(String scope, String id, String from, String to) {
        access.require(scope, "viewer");
        var parent = parent(scope, id, false);
        var before = from == null ? null : published(parent, from);
        var after = Objects.equals(to, parent.getDraftId()) ? draft(parent) : published(parent, to);
        List<Change> changes = new ArrayList<>();
        var beforeAxioms = before == null ? List.<vip.mate.semantic.core.ontology.OntologyAxiomDescriptor>of() : wire.parsed(before).axioms();
        var afterAxioms = wire.parsed(after).axioms();
        compare("axioms", beforeAxioms.stream().map(vip.mate.semantic.core.ontology.OntologyAxiomDescriptor::rendering).toList(), afterAxioms.stream().map(vip.mate.semantic.core.ontology.OntologyAxiomDescriptor::rendering).toList(), Function.identity(), changes);
        change("metadata", "name", before == null ? null : before.getName(), after.getName(), changes);
        change("metadata", "description", before == null ? null : before.getDescription(), after.getDescription(), changes);
        change("document", "source", before == null ? null : before.getDocumentText(), after.getDocumentText(), changes);
        change("document", "imports", before == null ? null : before.getImportLockDigest(), after.getImportLockDigest(), changes);
        change("policy", "businessPolicy", before == null ? null : before.getPolicyJson(), after.getPolicyJson(), changes);
        // Syntactic change is not a proof of semantic compatibility. M8 must compute an impact plan.
        return new Diff(from, to, List.copyOf(changes), changes.isEmpty() ? "UNCHANGED" : "REQUIRES_REVIEW", List.of());
    }

    private <T> void compare(
            String category,
            List<T> before,
            List<T> after,
            Function<T, String> key,
            List<Change> changes) {
        Map<String, T> beforeByKey = new TreeMap<>();
        Map<String, T> afterByKey = new TreeMap<>();
        before.forEach(v -> beforeByKey.put(key.apply(v), v));
        after.forEach(v -> afterByKey.put(key.apply(v), v));
        Set<String> keys = new TreeSet<>(beforeByKey.keySet());
        keys.addAll(afterByKey.keySet());
        for (String k : keys) change(category, k, beforeByKey.get(k), afterByKey.get(k), changes);
    }

    private void change(
            String category, String key, Object before, Object after, List<Change> changes) {
        if (!Objects.equals(before, after))
            changes.add(
                    new Change(
                            before == null ? "ADDED" : after == null ? "REMOVED" : "MODIFIED",
                            category,
                            key,
                            before,
                            after));
    }

    private OntologyRow parent(String scope, String id, boolean lock) {
        var row =
                lock
                        ? mapper.lock(id, Long.parseLong(scope))
                        : mapper.find(id, Long.parseLong(scope));
        if (row == null) throw notFound();
        return row;
    }

    private OntologyRevisionRow draft(OntologyRow parent) {
        if (parent.getDraftId() == null) throw notFound();
        var row = mapper.revision(parent.getDraftId(), parent.getId());
        if (row == null || !"DRAFT".equals(row.getRevisionState())) throw notFound();
        return row;
    }

    private OntologyRevisionRow published(OntologyRow parent, String id) {
        var row = mapper.revision(id, parent.getId());
        if (row == null || !"PUBLISHED".equals(row.getRevisionState())) throw notFound();
        return row;
    }

    private void cas(OntologyRevisionRow row, Long expected) {
        if (expected == null || expected < 1)
            throw new SemanticApiException(
                    400, "INVALID_REQUEST", "Positive expectedDraftVersion required");
        if (!expected.equals(row.getDraftVersion()))
            throw conflict("DRAFT_CONFLICT", "Draft has changed; reload persisted content");
    }

    private void touch(OntologyRow row) {
        row.setUpdatedAt(now());
        mapper.updateParent(row);
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(java.time.ZoneOffset.UTC)
                .truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }

    private static String id() {
        return com.baomidou.mybatisplus.core.toolkit.IdWorker.getIdStr();
    }

    private static SemanticApiException notFound() {
        return new SemanticApiException(
                404, "NOT_FOUND", "Semantic resource not found in workspace");
    }

    private static SemanticApiException conflict(String code, String message) {
        return new SemanticApiException(409, code, message);
    }

    private static String hash(String text) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

package vip.mate.semantic.ontology;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.beans.factory.ObjectProvider;

import vip.mate.semantic.ontology.repository.OntologyMapper;
import vip.mate.semantic.security.SemanticAccessService;
import vip.mate.semantic.reasoning.DraftReasoningService;
import vip.mate.semantic.ontology.source.OntologySourceReviewService;
import vip.mate.semantic.ontology.source.OntologySourceDtos.ValidationState;
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
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final DraftReasoningService draftReasoning;
    private final ObjectProvider<OntologySourceReviewService> sourceProvider;

    public OntologyApplicationService(
            OntologyMapper mapper,
            CommandRecordMapper commands,
            GovernanceRecordMapper governance,
            SemanticAccessService access,
            OntologyWireMapper wire, OntologyAxiomIndex axiomIndex,
            JdbcTemplate jdbc,
            org.springframework.transaction.PlatformTransactionManager transactionManager,
            DraftReasoningService draftReasoning,
            ObjectProvider<OntologySourceReviewService> sourceProvider) {
        this.axiomIndex=axiomIndex;
        this.mapper = mapper;
        this.commands = commands;
        this.governance = governance;
        this.access = access;
        this.wire = wire;
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.draftReasoning = draftReasoning;
        this.sourceProvider = sourceProvider;
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
        requireWritable(parent);
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

    public ProjectionView draftProjection(String scope, String id, Long expectedDraftVersion, int limit) {
        access.require(scope, "viewer");
        requireProjectionLimit(limit);
        var parent = parent(scope, id, false);
        var row = draft(parent);
        cas(row, expectedDraftVersion);
        return projection(row, limit);
    }

    public ProjectionView revisionProjection(String scope, String id, String revisionId, int limit) {
        access.require(scope, "viewer");
        requireProjectionLimit(limit);
        var parent = parent(scope, id, false);
        return projection(published(parent, revisionId), limit);
    }

    private ProjectionView projection(OntologyRevisionRow row, int limit) {
        return new ProjectionView(
                new Snapshot(row.getOntologyId(), row.getId(), "DRAFT".equals(row.getRevisionState()) ? row.getDraftVersion() : null,
                        row.getDocumentDigest(), row.getImportLockDigest()),
                wire.project(row, limit));
    }

    private void requireProjectionLimit(int limit) {
        if (limit < 1 || limit > 2000)
            throw new SemanticApiException(400, "INVALID_REQUEST", "Projection limit must be between 1 and 2000");
    }

    @Transactional
    public DraftView saveDraft(String scope, String id, SaveDraft request) {
        access.require(scope, "member");
        var parent = parent(scope, id, true);
        requireWritable(parent);
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
        return editDraft(scope, id, request, "EDIT_ONTOLOGY_AXIOMS", row -> request.changes());
    }

    @Transactional
    public DraftView editModelDraft(String scope, String id, ModelEditRequest request) {
        access.require(scope, "member");
        return editDraft(scope, id, request, "EDIT_ONTOLOGY_MODEL",
                row -> wire.modelEdits(row, request.operationId(), request.changes()));
    }

    /** Applies a complete business batch and retains the exact item mapping on retry. */
    @Transactional
    public ModelCommandResult applyModelCommands(String scope, String id, ModelEditRequest request) {
        access.require(scope, "member");
        var parent = parent(scope, id, true);
        requireWritable(parent);
        validateOperation(request.operationId());
        String requestHash = hash(wire.encode(request));
        String kind = "APPLY_ONTOLOGY_MODEL_COMMANDS";
        var previous = commands.find(parent.getWorkspaceId(), request.operationId());
        if (previous != null) {
            if (!kind.equals(previous.getKind()) || !id.equals(previous.getResourceId())
                    || !requestHash.equals(previous.getPayloadHash()))
                throw conflict("OPERATION_CONFLICT", "Operation id already used with different payload");
            return wire.decode(previous.getResultJson(), ModelCommandResult.class);
        }
        var row = draft(parent);
        cas(row, request.expectedDraftVersion());
        var batch = wire.compileModelCommands(row, request.operationId(), request.changes());
        wire.edit(row, batch.changes());
        if (mapper.saveDraft(row, request.expectedDraftVersion()) != 1)
            throw conflict("DRAFT_CONFLICT", "Draft has changed");
        axiomIndex.synchronize(row);
        row.setDraftVersion(Math.incrementExact(row.getDraftVersion()));
        parent.setDraftCounter(row.getDraftVersion());
        touch(parent);
        var result = new ModelCommandResult(wire.draft(row), batch.items());
        recordDraftCommand(parent, request.operationId(), kind, requestHash, result);
        return result;
    }

    private DraftView editDraft(
            String scope,
            String id,
            Object request,
            String operationKind,
            Function<OntologyRevisionRow, List<AxiomEdit>> changes) {
        var parent = parent(scope, id, true);
        requireWritable(parent);
        String operationId = request instanceof EditDraft edit ? edit.operationId()
                : ((ModelEditRequest) request).operationId();
        Long expectedDraftVersion = request instanceof EditDraft edit ? edit.expectedDraftVersion()
                : ((ModelEditRequest) request).expectedDraftVersion();
        validateOperation(operationId);
        String requestHash = hash(wire.encode(request));
        var previous = commands.find(parent.getWorkspaceId(), operationId);
        if (previous != null) {
            if (!operationKind.equals(previous.getKind()) || !id.equals(previous.getResourceId()) || !requestHash.equals(previous.getPayloadHash()))
                throw conflict("OPERATION_CONFLICT", "Operation id already used with different payload");
            return wire.decode(previous.getResultJson(), DraftView.class);
        }
        var row = draft(parent);
        cas(row, expectedDraftVersion);
        wire.edit(row, changes.apply(row));
        if (mapper.saveDraft(row, expectedDraftVersion) != 1) throw conflict("DRAFT_CONFLICT", "Draft has changed");
        axiomIndex.synchronize(row);
        row.setDraftVersion(Math.incrementExact(row.getDraftVersion()));
        parent.setDraftCounter(row.getDraftVersion()); touch(parent);
        var result = wire.draft(row);
        recordDraftCommand(parent, operationId, operationKind, requestHash, result);
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

    private void recordDraftCommand(OntologyRow parent, String operationId, String kind, String requestHash, Object result) {
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
        requireWritable(parent);
        var row = draft(parent);
        cas(row, expected);
        axiomIndex.discard(row.getId());
        if (mapper.deleteDraft(row.getId(), expected) != 1)
            throw conflict("DRAFT_CONFLICT", "Draft has changed");
        parent.setDraftId(null);
        touch(parent);
    }

    public ValidationView validate(String scope, String id, ValidateDraft request) {
        var actor = access.require(scope, "member");
        if (request == null || request.expectedDraftVersion() == null || request.expectedDraftVersion() < 1)
            throw new SemanticApiException(400, "INVALID_REQUEST", "Positive expectedDraftVersion required");
        ValidationSnapshot before = validationSnapshot(scope, id, request.expectedDraftVersion());
        List<ValidationCheck> checks = new ArrayList<>();
        checks.add(structureCheck(before.row()));
        checks.add(logicCheck(scope, id, request.expectedDraftVersion()));
        checks.add(policyCheck(before.row()));
        // The source check is the locked source snapshot captured before the worker starts.
        // Re-reading it here would allow a mid-flight source change to be hidden from the report.
        SourceCheck source = before.source();
        checks.add(source.check());

        // The worker is deliberately called through DraftReasoningService. It takes a short
        // snapshot transaction, runs the bounded child process outside that transaction, and
        // rechecks the snapshot before returning.
        String inputDigest = before.inputDigest();
        ValidationSnapshot after;
        try {
            after = validationSnapshot(scope, id, null);
        } catch (SemanticApiException exception) {
            if (exception.status() == 404) {
                throw new SemanticApiException(409, "VALIDATION_STALE", "Draft was removed while validation was running");
            }
            throw exception;
        }
        boolean stale = !inputDigest.equals(after.inputDigest());
        List<Violation> violations = checks.stream().flatMap(check -> check.violations().stream()).toList();
        boolean valid = !stale && checks.stream().allMatch(check -> "PASS".equals(check.status()));
        ValidationView report = new ValidationView(before.row().getDraftVersion(), valid, violations,
                "OWL 2 DL", reasoningStatus(checks), UUID.randomUUID().toString(), inputDigest,
                java.time.Instant.now(), checks, stale);
        access.require(scope, "member");
        return persistValidation(scope, id, before, report, actor.getId().toString());
    }

    /** Returns the persisted report and marks it stale against the current draft/source state. */
    public ValidationView latestValidation(String scope, String id) {
        access.require(scope, "viewer");
        ValidationSnapshot current;
        try {
            current = validationSnapshot(scope, id, null);
        } catch (SemanticApiException exception) {
            if (exception.status() == 404) return null;
            throw exception;
        }
        ValidationRecord record = transactions.execute(status -> latestValidationRecord(scope, id));
        if (record == null) return null;
        boolean stale = !Objects.equals(record.draftRevisionId(), current.row().getId())
                || record.draftVersion() != current.row().getDraftVersion()
                || !record.inputDigest().equals(current.inputDigest());
        return view(record, stale);
    }

    @Transactional
    public RevisionView publish(String scope, String id, PublishDraft request) {
        var actor = access.require(scope, "admin");
        var parent = parent(scope, id, true);
        requireWritable(parent);
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
        ValidationRecord report = latestValidationRecord(scope, id);
        if (report == null)
            throw new SemanticApiException(409, "VALIDATION_REQUIRED", "Run the complete ontology validation before publishing");
        String currentDigest = validationDigest(row, sourceCheck(scope, row).signature());
        if (!Objects.equals(report.draftRevisionId(), row.getId())
                || report.draftVersion() != row.getDraftVersion()
                || !currentDigest.equals(report.inputDigest()))
            throw new SemanticApiException(409, "VALIDATION_STALE", "Validation report is stale; run validation again");
        if (!report.valid() || !allChecksPass(report.checks()))
            throw new SemanticApiException(409, "VALIDATION_FAILED", "All validation checks must pass before publishing", reportViolations(report.checks()));
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

    private ValidationSnapshot validationSnapshot(String scope, String id, Long expectedDraftVersion) {
        ValidationSnapshot result = transactions.execute(status -> {
            OntologyRow parent = mapper.lock(id, Long.parseLong(scope));
            if (parent == null) throw missing();
            requireWritable(parent);
            OntologyRevisionRow row = draft(parent);
            if (expectedDraftVersion != null) cas(row, expectedDraftVersion);
            SourceCheck source = sourceCheck(scope, row);
            return new ValidationSnapshot(row, source, validationDigest(row, source.signature()));
        });
        if (result == null) throw missing();
        return result;
    }

    private ValidationView persistValidation(String scope, String id, ValidationSnapshot snapshot,
            ValidationView report, String actor) {
        return transactions.execute(status -> {
            var verifiedActor = access.require(scope, "member");
            OntologyRow parent = mapper.lock(id, Long.parseLong(scope));
            if (parent == null) throw missing();
            requireWritable(parent);
            OntologyRevisionRow current = draft(parent);
            SourceCheck source = sourceCheck(scope, current);
            String currentDigest = validationDigest(current, source.signature());
            boolean stale = report.stale() || !snapshot.row().getId().equals(current.getId())
                    || !Objects.equals(snapshot.row().getDraftVersion(), current.getDraftVersion())
                    || !report.inputDigest().equals(currentDigest);
            ValidationView persisted = stale ? stale(report) : report;
            java.time.Instant checkedAt = now().toInstant(java.time.ZoneOffset.UTC);
            jdbc.update(
                    "INSERT INTO mate_semantic_ontology_validation_report(id,workspace_id,ontology_id,draft_revision_id,draft_version,input_digest,checks_json,valid,created_by,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                    persisted.reportId(), Long.valueOf(scope), id, snapshot.row().getId(), snapshot.row().getDraftVersion(),
                    persisted.inputDigest(), wire.encode(persisted.checks()), persisted.valid(), verifiedActor.getId().toString(),
                    java.time.LocalDateTime.ofInstant(checkedAt, java.time.ZoneOffset.UTC));
            return new ValidationView(persisted.draftVersion(), persisted.valid(), persisted.violations(), persisted.profile(),
                    persisted.reasoningStatus(), persisted.reportId(), persisted.inputDigest(), checkedAt, persisted.checks(), persisted.stale());
        });
    }

    private ValidationCheck structureCheck(OntologyRevisionRow row) {
        try {
            List<Violation> violations = wire.violations(row);
            return new ValidationCheck("STRUCTURE", violations.stream().anyMatch(v -> "ERROR".equals(v.severity())) ? "FAIL" : "PASS", violations);
        } catch (RuntimeException exception) {
            return new ValidationCheck("STRUCTURE", "ERROR", List.of(problem("STRUCTURE_CHECK_ERROR", message(exception))));
        }
    }

    private ValidationCheck logicCheck(String scope, String id, long draftVersion) {
        try {
            DraftReasoningView result = draftReasoning.reason(scope, id, new ReasonDraft(draftVersion));
            String status = result.status();
            if ("CONSISTENT".equals(status)) return new ValidationCheck("LOGIC", "PASS", List.of(), result.unsatisfiableClasses());
            List<Violation> violations = List.of(problem("LOGIC_" + status, result.message()));
            String checkStatus = switch (status) {
                case "INCONSISTENT", "UNSATISFIABLE" -> "FAIL";
                case "NOT_RUN" -> "NOT_RUN";
                default -> "ERROR";
            };
            return new ValidationCheck("LOGIC", checkStatus, violations, result.unsatisfiableClasses());
        } catch (SemanticApiException exception) {
            return new ValidationCheck("LOGIC", "ERROR", List.of(problem(exception.code(), exception.getMessage())));
        } catch (RuntimeException exception) {
            return new ValidationCheck("LOGIC", "ERROR", List.of(problem("LOGIC_CHECK_ERROR", message(exception))));
        }
    }

    private ValidationCheck policyCheck(OntologyRevisionRow row) {
        List<Violation> violations = new ArrayList<>();
        try {
            var policy = wire.policy(row);
            var parsed = wire.parsed(row);
            var classes = wire.classIris(parsed);
            var termKinds = wire.termKinds(row);
            Set<String> seen = new HashSet<>();
            for (int index = 0; index < policy.rules().size(); index++) {
                var rule = policy.rules().get(index);
                String path = "rules[" + index + "]";
                if (!classes.contains(rule.classIri())) violations.add(problem("POLICY_CLASS_NOT_FOUND", path + ".classIri"));
                List<String> kinds = termKinds.get(rule.predicateIri());
                if (kinds == null || kinds.stream().noneMatch(kind -> "DataProperty".equalsIgnoreCase(kind)))
                    violations.add(problem("POLICY_DATA_PROPERTY_NOT_FOUND", path + ".predicateIri"));
                if (!seen.add(rule.classIri() + "\u0000" + rule.predicateIri())) violations.add(problem("POLICY_DUPLICATE_RULE", path));
            }
            return new ValidationCheck("POLICY", violations.isEmpty() ? "PASS" : "FAIL", violations);
        } catch (RuntimeException exception) {
            return new ValidationCheck("POLICY", "ERROR", List.of(problem("POLICY_CHECK_ERROR", message(exception))));
        }
    }

    private SourceCheck sourceCheck(String scope, OntologyRevisionRow row) {
        List<ValidationState> items = sourceProvider.getObject().validationStates(scope, row.getOntologyId(), row.getId());
        List<Violation> violations = new ArrayList<>();
        List<String> signatures = new ArrayList<>();
        for (ValidationState item : items) {
            signatures.add(String.join(":", Objects.toString(item.bindingId(),""), Objects.toString(item.revisionId(),""),
                    Objects.toString(item.axiomId(),""), Objects.toString(item.sourceSnapshotId(),""),
                    Objects.toString(item.origin(),""), Objects.toString(item.sourceDigest(),""),
                    Objects.toString(item.observedDigest(),""), Objects.toString(item.currentSourceState(),""),
                    Objects.toString(item.reviewState(),""), Objects.toString(item.decision(),"")));
            if (!"CURRENT".equals(item.currentSourceState()))
                violations.add(problem("SOURCE_" + item.currentSourceState(), "binding " + item.bindingId()));
            if (!"REVIEWED".equals(item.reviewState()) || !"ACKNOWLEDGE".equals(item.decision()))
                violations.add(problem("SOURCE_REVIEW_REQUIRED", "binding " + item.bindingId()));
        }
        String signature = String.join("|", signatures);
        return new SourceCheck(new ValidationCheck("SOURCES", violations.isEmpty() ? "PASS" : "FAIL", violations), signature);
    }

    private String validationDigest(OntologyRevisionRow row, String sourceSignature) {
        StringBuilder value = new StringBuilder();
        appendDigest(value, row.getOntologyId()); appendDigest(value, row.getId()); appendDigest(value, row.getDraftVersion());
        appendDigest(value, row.getDocumentDigest()); appendDigest(value, row.getImportLockDigest()); appendDigest(value, row.getDocumentText());
        appendDigest(value, row.getImportsJson()); appendDigest(value, row.getPolicyJson()); appendDigest(value, sourceSignature);
        appendDigest(value, draftReasoning.inputFingerprint());
        return vip.mate.semantic.core.ontology.OntologyDocument.sha256(value.toString());
    }

    private ValidationRecord latestValidationRecord(String scope, String id) {
        List<ValidationRecord> rows = jdbc.query(
                "SELECT id,draft_revision_id,draft_version,input_digest,checks_json,valid,created_at FROM mate_semantic_ontology_validation_report WHERE workspace_id=? AND ontology_id=? ORDER BY created_at DESC,id DESC LIMIT 1",
                (rs, n) -> new ValidationRecord(rs.getString("id"), rs.getString("draft_revision_id"), rs.getLong("draft_version"),
                        rs.getString("input_digest"), decodeChecks(rs.getString("checks_json")),
                        rs.getBoolean("valid"), rs.getTimestamp("created_at").toLocalDateTime().toInstant(java.time.ZoneOffset.UTC)), Long.valueOf(scope), id);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private ValidationView view(ValidationRecord record, boolean stale) {
        List<ValidationCheck> checks = record.checks();
        List<Violation> violations = reportViolations(checks);
        boolean valid = !stale && record.valid() && allChecksPass(checks);
        return new ValidationView(record.draftVersion(), valid, violations, "OWL 2 DL", reasoningStatus(checks),
                record.id(), record.inputDigest(), record.createdAt(), checks, stale);
    }

    private static boolean allChecksPass(List<ValidationCheck> checks) {
        if (checks.size() != 4) return false;
        Set<String> kinds = checks.stream().map(ValidationCheck::kind).collect(java.util.stream.Collectors.toSet());
        return kinds.equals(Set.of("STRUCTURE", "LOGIC", "POLICY", "SOURCES"))
                && checks.stream().allMatch(check -> "PASS".equals(check.status()));
    }

    private static List<Violation> reportViolations(List<ValidationCheck> checks) {
        return checks.stream().flatMap(check -> check.violations().stream()).toList();
    }

    private List<ValidationCheck> decodeChecks(String value) {
        ValidationCheck[] checks = wire.decode(value, ValidationCheck[].class);
        return checks == null ? List.of() : List.of(checks);
    }

    private static String reasoningStatus(List<ValidationCheck> checks) {
        return checks.stream().filter(check -> "LOGIC".equals(check.kind())).map(check -> switch (check.status()) {
            case "PASS" -> "CONSISTENT"; case "FAIL" -> check.violations().stream().anyMatch(v -> "LOGIC_UNSATISFIABLE".equals(v.code())) ? "UNSATISFIABLE" : "INCONSISTENT"; case "NOT_RUN" -> "NOT_RUN"; default -> "ERROR";
        }).findFirst().orElse("NOT_RUN");
    }

    private static ValidationView stale(ValidationView report) {
        return new ValidationView(report.draftVersion(), false, report.violations(), report.profile(), report.reasoningStatus(),
                report.reportId(), report.inputDigest(), report.checkedAt(), report.checks(), true);
    }

    private static Violation problem(String code, String path) { return new Violation(code, path, code, "ERROR"); }
    private static Violation problem(String code) { return problem(code, code); }
    private static String message(Throwable exception) { return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage(); }
    private static void appendDigest(StringBuilder value, Object item) { String text = item == null ? "" : item.toString(); value.append(text.length()).append(':').append(text).append('|'); }
    private static SemanticApiException missing() { return new SemanticApiException(404, "NOT_FOUND", "Semantic resource not found in workspace"); }
    private record ValidationSnapshot(OntologyRevisionRow row, SourceCheck source, String inputDigest) {}
    private record SourceCheck(ValidationCheck check, String signature) {}
    private record ValidationRecord(String id, String draftRevisionId, long draftVersion, String inputDigest,
            List<ValidationCheck> checks, boolean valid, java.time.Instant createdAt) {}

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
        if (request.availableForNewBindings()) requireWritable(parent);
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

    public static void requireWritable(OntologyRow row) {
        if (row.isArchived()) throw conflict("ONTOLOGY_ARCHIVED", "Restore the ontology before modifying it");
    }

    @Transactional
    public OntologyView lifecycle(String scope, String id, LifecycleRequest request, boolean archived) {
        var actor = access.require(scope, "admin");
        var row = parent(scope, id, true);
        if (row.isArchived() == archived) return wire.ontology(row);
        if (request == null || request.expectedUpdatedAt() == null)
            throw new SemanticApiException(400, "INVALID_REQUEST", "expectedUpdatedAt required");
        if (!row.getUpdatedAt().toInstant(java.time.ZoneOffset.UTC).equals(request.expectedUpdatedAt()))
            throw conflict("ONTOLOGY_CONFLICT", "Ontology changed; reload before changing its lifecycle");
        row.setArchived(archived);
        if (archived) for (var revision : mapper.revisions(id)) mapper.availability(revision.getId(), false);
        governance.insert(id(), row.getWorkspaceId(), id, null, archived ? "ARCHIVE_ONTOLOGY" : "RESTORE_ONTOLOGY",
                actor.getId().toString(), wire.encode(Map.of("archived", archived)), now());
        touch(row);
        return wire.ontology(row);
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

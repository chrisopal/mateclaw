package vip.mate.semantic.reasoning;

import static vip.mate.semantic.web.OntologyDtos.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import vip.mate.semantic.core.ontology.OntologyDocument;
import vip.mate.semantic.core.reasoning.ReasoningPort;
import vip.mate.semantic.core.reasoning.ReasoningRequest;
import vip.mate.semantic.core.reasoning.ReasoningResult;
import vip.mate.semantic.ontology.OntologyRevisionRow;
import vip.mate.semantic.ontology.OntologyWireMapper;
import vip.mate.semantic.ontology.repository.OntologyMapper;
import vip.mate.semantic.security.SemanticAccessService;
import vip.mate.semantic.web.SemanticApiException;

/** Coordinates a read-only, snapshot-pinned logical check for an ontology draft. */
@Service
@ConditionalOnProperty(name = "mateclaw.semantic.enabled", havingValue = "true")
public final class DraftReasoningService {
    private final TransactionTemplate transactions;
    private final OntologyMapper mapper;
    private final OntologyWireMapper wire;
    private final SemanticAccessService access;
    private final ReasoningPort worker;
    private final SemanticReasoningProperties properties;

    public DraftReasoningService(OntologyMapper mapper, OntologyWireMapper wire,
            SemanticAccessService access, ReasoningPort worker,
            SemanticReasoningProperties properties,
            org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.transactions = new TransactionTemplate(transactionManager);
        this.mapper = mapper;
        this.wire = wire;
        this.access = access;
        this.worker = worker;
        this.properties = properties;
    }

    /**
     * Reads and pins the draft in a short transaction, then releases the transaction
     * before invoking the isolated worker. No result or ontology state is persisted.
     */
    public DraftReasoningView reason(String scope, String ontologyId, ReasonDraft request) {
        access.require(scope, "member");
        if (!properties.isEnabled()) {
            throw new SemanticApiException(409, "REASONING_DISABLED", "Semantic reasoning is disabled");
        }
        if (request == null || request.expectedDraftVersion() == null
                || request.expectedDraftVersion() < 1) {
            throw new SemanticApiException(400, "INVALID_REQUEST",
                    "Positive expectedDraftVersion required");
        }
        Snapshot before = snapshot(scope, ontologyId, request.expectedDraftVersion(), true);
        ReasoningResult result;
        try {
            result = worker.reason(workerRequest(before));
        } catch (RuntimeException exception) {
            // A worker exception is an execution outcome, not evidence that the draft is valid.
            result = failedResult(before, exception);
        }
        access.require(scope, "member");
        Snapshot after;
        try {
            after = snapshot(scope, ontologyId, null, false);
        } catch (SemanticApiException exception) {
            if (exception.status() == 404) throw stale();
            throw exception;
        }
        if (!before.inputDigest().equals(after.inputDigest())) {
            throw stale();
        }
        return view(before, result);
    }

    private Snapshot snapshot(String scope, String ontologyId, Long expectedDraftVersion, boolean enforceExpected) {
        if (scope == null || ontologyId == null || ontologyId.isBlank()) {
            throw new SemanticApiException(400, "INVALID_REQUEST", "Ontology and workspace are required");
        }
        Snapshot result = transactions.execute(status -> {
            OntologyRevisionRow row = draft(scope, ontologyId);
            if (enforceExpected && !expectedDraftVersion.equals(row.getDraftVersion())) {
                throw new SemanticApiException(409, "DRAFT_CONFLICT",
                        "Draft has changed; reload persisted content");
            }
            var document = wire.document(row);
            var source = document.source();
            OntologyDocument envelope = new OntologyDocument(
                    row.getOntologyId(), row.getId(), document.ontologyIri(),
                    Optional.ofNullable(document.versionIri()), source.syntax(), source.documentText(),
                    document.documentDigest(), document.importLockDigest(), source.modelSchema());
            String policy = wire.encode(source.policy());
            String inputDigest = digest(row, envelope, source, policy);
            return new Snapshot(row.getOntologyId(), row.getId(), row.getDraftVersion(), envelope,
                    source.imports(), policy, inputDigest);
        });
        if (result == null) throw new SemanticApiException(404, "NOT_FOUND", "Semantic resource not found");
        return result;
    }

    private OntologyRevisionRow draft(String scope, String ontologyId) {
        var parent = mapper.lock(ontologyId, Long.parseLong(scope));
        if (parent == null || parent.getDraftId() == null) throw missing();
        OntologyRevisionRow row = mapper.revision(parent.getDraftId(), ontologyId);
        if (row == null || !"DRAFT".equals(row.getRevisionState())) throw missing();
        return row;
    }

    private ReasoningRequest workerRequest(Snapshot snapshot) {
        return new ReasoningRequest(ReasoningRequest.SCHEMA_VERSION, UUID.randomUUID().toString(),
                snapshot.document(), snapshot.imports(), ReasoningRequest.AssertionScope.ONTOLOGY_ABOX,
                List.of(), List.of(), Optional.empty(), ReasoningRequest.Task.classification(),
                ReasoningRequest.Engine.hermit());
    }

    private DraftReasoningView view(Snapshot snapshot, ReasoningResult result) {
        String status = result.status().name();
        Boolean consistent = switch (result.status()) {
            case CONSISTENT, UNSATISFIABLE -> Boolean.TRUE;
            case INCONSISTENT -> Boolean.FALSE;
            default -> null;
        };
        String message = result.diagnostics().isEmpty()
                ? switch (result.status()) {
                    case CONSISTENT -> "Draft is logically consistent";
                    case UNSATISFIABLE -> "Draft is consistent but contains unsatisfiable classes";
                    case INCONSISTENT -> "Draft is logically inconsistent";
                    default -> status;
                }
                : String.join("; ", result.diagnostics());
        return new DraftReasoningView(snapshot.draftVersion(), snapshot.inputDigest(), status,
                consistent, result.unsatisfiableClasses(), message);
    }

    private ReasoningResult failedResult(Snapshot snapshot, RuntimeException exception) {
        return new ReasoningResult(ReasoningResult.SCHEMA_VERSION, UUID.randomUUID().toString(),
                ReasoningResult.ReasoningStatus.FAILED, ReasoningRequest.TaskKind.CLASSIFICATION,
                snapshot.inputDigest(), "worker", "unknown", 0, List.of(), List.of(),
                List.of(message(exception)), List.of(),
                new ReasoningResult.Provenance(ReasoningRequest.AssertionScope.ONTOLOGY_ABOX.name(),
                        ReasoningRequest.TaskKind.CLASSIFICATION.name(), java.time.Instant.now(), "server"));
    }

    private String digest(OntologyRevisionRow row, OntologyDocument document,
            vip.mate.semantic.web.OntologyDtos.DocumentInput source, String policy) {
        StringBuilder value = new StringBuilder();
        append(value, row.getOntologyId());
        append(value, row.getId());
        append(value, row.getDraftVersion());
        append(value, document.documentDigest());
        append(value, document.importLockDigest());
        append(value, document.documentText());
        source.imports().forEach(item -> {
            append(value, item.requestedIri()); append(value, item.resolvedOntologyIri());
            append(value, item.versionIri().orElse("")); append(value, item.syntax());
            append(value, item.documentText()); append(value, item.contentDigest()); append(value, item.artifactId());
        });
        append(value, policy);
        append(value, propertiesFingerprint());
        append(value, ReasoningRequest.AssertionScope.ONTOLOGY_ABOX.name());
        append(value, ReasoningRequest.TaskKind.CLASSIFICATION.name());
        return OntologyDocument.sha256(value.toString());
    }

    private String propertiesFingerprint() {
        return properties.isEnabled() + ":" + properties.getTimeoutSeconds() + ":"
                + properties.getMemoryMb() + ":" + properties.getMaxConcurrency();
    }

    private static void append(StringBuilder value, Object item) {
        String text = item == null ? "" : item.toString();
        value.append(text.length()).append(':').append(text).append('|');
    }

    private static String message(Throwable exception) {
        String value = exception.getMessage();
        return value == null || value.isBlank() ? exception.getClass().getSimpleName() : value;
    }

    private static SemanticApiException missing() {
        return new SemanticApiException(404, "NOT_FOUND", "Semantic resource not found in workspace");
    }

    private static SemanticApiException stale() {
        return new SemanticApiException(409, "REASONING_STALE",
                "Draft, pinned imports, policy or reasoning configuration changed during reasoning");
    }

    private record Snapshot(String ontologyId, String draftId, long draftVersion,
            OntologyDocument document, List<vip.mate.semantic.core.ontology.LockedImport> imports,
            String policy, String inputDigest) {}
}

package vip.mate.semantic.core.reasoning;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** JDK-only outcome of a bounded reasoning request. Failure states are explicit. */
public record ReasoningResult(
        String schemaVersion,
        String requestId,
        ReasoningStatus status,
        ReasoningRequest.TaskKind task,
        String requestDigest,
        String engineName,
        String engineVersion,
        long durationMillis,
        List<ClassRelation> classRelations,
        List<IndividualTypes> individualTypes,
        List<String> diagnostics,
        Provenance provenance) {

    public static final String SCHEMA_VERSION = ReasoningRequest.SCHEMA_VERSION;

    public ReasoningResult {
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("unsupported reasoning result schemaVersion: " + schemaVersion);
        }
        requestId = requireText(requestId, "requestId");
        status = Objects.requireNonNull(status, "status");
        task = Objects.requireNonNull(task, "task");
        requestDigest = requireText(requestDigest, "requestDigest");
        engineName = requireText(engineName, "engineName");
        engineVersion = requireText(engineVersion, "engineVersion");
        if (durationMillis < 0) {
            throw new IllegalArgumentException("durationMillis must not be negative");
        }
        classRelations = List.copyOf(Objects.requireNonNull(classRelations, "classRelations"));
        individualTypes = List.copyOf(Objects.requireNonNull(individualTypes, "individualTypes"));
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
        provenance = Objects.requireNonNull(provenance, "provenance");
    }

    public boolean completedSuccessfully() {
        return status == ReasoningStatus.CONSISTENT
                || status == ReasoningStatus.INCONSISTENT
                || status == ReasoningStatus.ENTAILED
                || status == ReasoningStatus.NOT_ENTAILED
                || status == ReasoningStatus.UNSATISFIABLE;
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    public enum ReasoningStatus {
        CONSISTENT,
        INCONSISTENT,
        ENTAILED,
        NOT_ENTAILED,
        UNSATISFIABLE,
        UNSUPPORTED,
        PARSE_ERROR,
        PROFILE_VIOLATION,
        TIMEOUT,
        RESOURCE_EXHAUSTED,
        FAILED
    }

    public record ClassRelation(String classIri, Set<String> superClassIris) {
        public ClassRelation {
            classIri = requireText(classIri, "classIri");
            superClassIris = Set.copyOf(Objects.requireNonNull(superClassIris, "superClassIris"));
        }
    }

    public record IndividualTypes(String individualIri, Set<String> classIris) {
        public IndividualTypes {
            individualIri = requireText(individualIri, "individualIri");
            classIris = Set.copyOf(Objects.requireNonNull(classIris, "classIris"));
        }
    }

    public record Provenance(
            String scope,
            String task,
            Instant completedAt,
            String workerProtocolVersion) {
        public Provenance {
            scope = requireText(scope, "scope");
            task = requireText(task, "task");
            completedAt = Objects.requireNonNull(completedAt, "completedAt");
            workerProtocolVersion = requireText(workerProtocolVersion, "workerProtocolVersion");
        }
    }
}

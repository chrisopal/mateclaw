package vip.mate.semantic.core.reasoning;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import vip.mate.semantic.core.ontology.LockedImport;
import vip.mate.semantic.core.ontology.OntologyDocument;

/**
 * Immutable, JDK-only input for one bounded OWL reasoning operation.
 *
 * <p>The ontology document and locked import bytes are the fixed TBox closure.
 * Ontology ABox assertions and accepted business facts are separate snapshots;
 * the default scope is TBOX_ONLY, so importing ontology individuals never
 * silently promotes them to accepted business facts.</p>
 */
public record ReasoningRequest(
        String schemaVersion,
        String requestId,
        OntologyDocument document,
        List<LockedImport> lockedImports,
        AssertionScope scope,
        List<String> ontologyABoxAssertions,
        List<FactSnapshot> acceptedFacts,
        Optional<TimeRange> validDuring,
        Task task,
        Engine engine) {

    public static final String SCHEMA_VERSION = "semantic-reasoning-v1";

    public ReasoningRequest {
        schemaVersion = requireText(schemaVersion, "schemaVersion");
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("unsupported reasoning schemaVersion: " + schemaVersion);
        }
        requestId = requireText(requestId, "requestId");
        document = Objects.requireNonNull(document, "document");
        lockedImports = List.copyOf(Objects.requireNonNull(lockedImports, "lockedImports"));
        if (!document.importLockDigest().equals(LockedImport.digest(lockedImports))) {
            throw new IllegalArgumentException("lockedImports do not match document importLockDigest");
        }
        scope = scope == null ? AssertionScope.TBOX_ONLY : scope;
        ontologyABoxAssertions = copyAssertions(ontologyABoxAssertions, "ontologyABoxAssertions");
        acceptedFacts = List.copyOf(Objects.requireNonNull(acceptedFacts, "acceptedFacts"));
        if (!scope.includesOntologyABox() && !ontologyABoxAssertions.isEmpty()) {
            throw new IllegalArgumentException("ontology ABox assertions require an ontology ABox scope");
        }
        if (!scope.includesAcceptedFacts() && !acceptedFacts.isEmpty()) {
            throw new IllegalArgumentException("accepted facts require an accepted-facts scope");
        }
        validDuring = validDuring == null ? Optional.empty() : validDuring;
        task = Objects.requireNonNull(task, "task");
        engine = Objects.requireNonNull(engine, "engine");
    }

    public static ReasoningRequest tboxOnly(
            String requestId, OntologyDocument document, List<LockedImport> imports, Task task) {
        return new ReasoningRequest(SCHEMA_VERSION, requestId, document, imports,
                AssertionScope.TBOX_ONLY, List.of(), List.of(), Optional.empty(), task, Engine.hermit());
    }

    private static List<String> copyAssertions(List<String> values, String name) {
        List<String> copy = List.copyOf(Objects.requireNonNull(values, name));
        copy.forEach(value -> requireText(value, name + " assertion"));
        return copy;
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    public enum AssertionScope {
        TBOX_ONLY(false, false),
        ONTOLOGY_ABOX(true, false),
        ACCEPTED_FACTS(false, true),
        ONTOLOGY_ABOX_AND_ACCEPTED_FACTS(true, true);

        private final boolean ontologyABox;
        private final boolean acceptedFacts;

        AssertionScope(boolean ontologyABox, boolean acceptedFacts) {
            this.ontologyABox = ontologyABox;
            this.acceptedFacts = acceptedFacts;
        }

        public boolean includesOntologyABox() { return ontologyABox; }
        public boolean includesAcceptedFacts() { return acceptedFacts; }
    }

    public record TimeRange(Optional<Instant> fromInclusive, Optional<Instant> toExclusive) {
        public TimeRange {
            fromInclusive = fromInclusive == null ? Optional.empty() : fromInclusive;
            toExclusive = toExclusive == null ? Optional.empty() : toExclusive;
            if (fromInclusive.isPresent() && toExclusive.isPresent()
                    && !fromInclusive.get().isBefore(toExclusive.get())) {
                throw new IllegalArgumentException("validDuring must have an increasing interval");
            }
        }
    }

    /** A fact snapshot is input provenance, never a reasoning acceptance decision. */
    public record FactSnapshot(
            String factId,
            String assertionFunctionalSyntax,
            String snapshotId,
            Instant capturedAt,
            Optional<Instant> validFrom,
            Optional<Instant> validTo,
            String evidenceDigest) {
        public FactSnapshot {
            factId = requireText(factId, "factId");
            assertionFunctionalSyntax = requireText(assertionFunctionalSyntax, "assertionFunctionalSyntax");
            snapshotId = requireText(snapshotId, "snapshotId");
            capturedAt = Objects.requireNonNull(capturedAt, "capturedAt");
            validFrom = validFrom == null ? Optional.empty() : validFrom;
            validTo = validTo == null ? Optional.empty() : validTo;
            if (validFrom.isPresent() && validTo.isPresent() && !validFrom.get().isBefore(validTo.get())) {
                throw new IllegalArgumentException("fact validity interval must be increasing");
            }
            evidenceDigest = requireText(evidenceDigest, "evidenceDigest");
        }
    }

    public record Task(TaskKind kind, Optional<String> classIri,
            Optional<String> individualIri, Optional<String> axiomFunctionalSyntax) {
        public Task {
            kind = Objects.requireNonNull(kind, "kind");
            classIri = normalize(classIri, "classIri");
            individualIri = normalize(individualIri, "individualIri");
            axiomFunctionalSyntax = normalize(axiomFunctionalSyntax, "axiomFunctionalSyntax");
            switch (kind) {
                case CONSISTENCY, CLASSIFICATION -> requireAbsent(kind, individualIri, "individualIri");
                case INSTANCE_TYPES -> requirePresent(kind, individualIri, "individualIri");
                case AXIOM_ENTAILMENT -> requirePresent(kind, axiomFunctionalSyntax, "axiomFunctionalSyntax");
            }
            if (kind != TaskKind.AXIOM_ENTAILMENT && axiomFunctionalSyntax.isPresent()) {
                throw new IllegalArgumentException(kind + " does not accept an axiom query");
            }
            if (kind != TaskKind.INSTANCE_TYPES && individualIri.isPresent()) {
                throw new IllegalArgumentException(kind + " does not accept an individual query");
            }
            if (kind != TaskKind.CLASSIFICATION && classIri.isPresent()) {
                throw new IllegalArgumentException(kind + " does not accept a class query");
            }
        }

        public static Task consistency() {
            return new Task(TaskKind.CONSISTENCY, Optional.empty(), Optional.empty(), Optional.empty());
        }

        public static Task classification() {
            return new Task(TaskKind.CLASSIFICATION, Optional.empty(), Optional.empty(), Optional.empty());
        }

        public static Task instanceTypes(String individualIri) {
            return new Task(TaskKind.INSTANCE_TYPES, Optional.empty(), Optional.of(individualIri), Optional.empty());
        }

        public static Task entailment(String axiomFunctionalSyntax) {
            return new Task(TaskKind.AXIOM_ENTAILMENT, Optional.empty(), Optional.empty(),
                    Optional.of(axiomFunctionalSyntax));
        }

        private static Optional<String> normalize(Optional<String> value, String name) {
            Optional<String> result = value == null ? Optional.empty() : value;
            result.ifPresent(item -> requireText(item, name));
            return result;
        }

        private static void requirePresent(TaskKind kind, Optional<String> value, String name) {
            if (value.isEmpty()) {
                throw new IllegalArgumentException(kind + " requires " + name);
            }
        }

        private static void requireAbsent(TaskKind kind, Optional<String> value, String name) {
            if (value.isPresent()) {
                throw new IllegalArgumentException(kind + " does not accept " + name);
            }
        }
    }

    public enum TaskKind { CONSISTENCY, CLASSIFICATION, INSTANCE_TYPES, AXIOM_ENTAILMENT }

    public record Engine(String name, String version) {
        public Engine {
            name = requireText(name, "engine.name");
            version = requireText(version, "engine.version");
        }

        public static Engine hermit() { return new Engine("HermiT", "1.4.5.519"); }
    }
}

package vip.mate.semantic.owl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.semanticweb.HermiT.Configuration;
import org.semanticweb.HermiT.Reasoner;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.FunctionalSyntaxDocumentFormat;
import org.semanticweb.owlapi.formats.RDFXMLDocumentFormat;
import org.semanticweb.owlapi.io.OWLOntologyDocumentSource;
import org.semanticweb.owlapi.io.StringDocumentSource;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDataPropertyAssertionAxiom;
import org.semanticweb.owlapi.model.OWLDocumentFormat;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLEquivalentClassesAxiom;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyIRIMapper;
import org.semanticweb.owlapi.model.OWLOntologyLoaderConfiguration;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.MissingImportEvent;
import org.semanticweb.owlapi.model.MissingImportHandlingStrategy;
import org.semanticweb.owlapi.model.OWLSubClassOfAxiom;
import org.semanticweb.owlapi.profiles.OWL2DLProfile;
import org.semanticweb.owlapi.profiles.OWLProfileReport;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.vocab.OWL2Datatype;

import vip.mate.semantic.core.ontology.LockedImport;
import vip.mate.semantic.core.ontology.OntologyDocument;
import vip.mate.semantic.core.ontology.OntologyDocumentSyntax;
import vip.mate.semantic.core.reasoning.ReasoningPort;
import vip.mate.semantic.core.reasoning.ReasoningRequest;
import vip.mate.semantic.core.reasoning.ReasoningResult;
import vip.mate.semantic.core.reasoning.ReasoningResult.ClassRelation;
import vip.mate.semantic.core.reasoning.ReasoningResult.IndividualTypes;
import vip.mate.semantic.core.reasoning.ReasoningResult.ReasoningStatus;

/**
 * HermiT-backed reasoning boundary.
 *
 * <p>The public worker executes every request in a fresh JVM. The child reads
 * one versioned request file and writes one versioned result file. This keeps
 * parser/reasoner state and untrusted ontology data outside the server JVM and
 * makes the memory and timeout limits enforceable by the parent process.</p>
 *
 * <p>When running from a packaged Spring Boot jar, the worker detects
 * {@code BOOT-INF/classes} and {@code BOOT-INF/lib} entries, expands them into
 * the per-request child workspace, and launches the child with that ordinary
 * class path. The expanded runtime is deleted with the request workspace.</p>
 */
public final class HermitReasoningWorker implements ReasoningPort {
    public static final String WORKER_PROTOCOL_VERSION = "semantic-reasoning-worker-v1";
    private static final String REQUEST_FILE = "request-v1.json";
    private static final String RESULT_FILE = "result-v1.json";
    private static final int DEFAULT_MEMORY_MB = 512;
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    private final Duration timeout;
    private final int memoryMb;
    private final String javaExecutable;
    private final String classpath;
    private final Semaphore concurrency;

    public HermitReasoningWorker() {
        this(DEFAULT_TIMEOUT, DEFAULT_MEMORY_MB, 1,
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                System.getProperty("java.class.path"));
    }

    /**
     * Creates a worker with explicit child process limits and class path.
     * {@code maxConcurrency} is intentionally bounded to one by default; a
     * larger value is accepted only when an embedding service explicitly
     * chooses to spend the corresponding process resources.
     */
    public HermitReasoningWorker(Duration timeout, int memoryMb, int maxConcurrency,
            String javaExecutable, String classpath) {
        this.timeout = requireTimeout(timeout);
        if (memoryMb < 64) {
            throw new IllegalArgumentException("memoryMb must be at least 64");
        }
        if (maxConcurrency != 1) {
            throw new IllegalArgumentException("maxConcurrency must be exactly 1");
        }
        this.memoryMb = memoryMb;
        this.javaExecutable = requireText(javaExecutable, "javaExecutable");
        this.classpath = requireText(classpath, "classpath");
        this.concurrency = new Semaphore(maxConcurrency, true);
    }

    @Override
    public ReasoningResult reason(ReasoningRequest request) {
        if (request == null) {
            throw new NullPointerException("request");
        }
        String digest = requestDigest(request);
        if (!concurrency.tryAcquire()) {
            return result(request, digest, ReasoningStatus.RESOURCE_EXHAUSTED, 0,
                    List.of("reasoning worker concurrency limit is exhausted"), List.of(), List.of());
        }
        long started = System.nanoTime();
        Path directory = null;
        Process process = null;
        try {
            directory = Files.createTempDirectory("mateclaw-reasoning-");
            Path requestFile = directory.resolve(REQUEST_FILE);
            Path resultFile = directory.resolve(RESULT_FILE);
            writeRequest(requestFile, request);
            String childClasspath = RuntimeClasspath.prepare(classpath, directory.resolve("runtime"));
            process = new ProcessBuilder(javaExecutable,
                    "-Xmx" + memoryMb + "m", "-XX:+ExitOnOutOfMemoryError",
                    "-cp", childClasspath,
                    HermitReasoningWorker.class.getName(),
                    "--child", requestFile.toString(), resultFile.toString())
                    .redirectErrorStream(false)
                    .start();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                destroy(process);
                return result(request, digest, ReasoningStatus.TIMEOUT, elapsed(started),
                        List.of("reasoning child exceeded " + timeout.toMillis() + "ms"), List.of(), List.of());
            }
            String stderr = readLimited(process.getErrorStream(), 4_000);
            if (process.exitValue() != 0 || !Files.isRegularFile(resultFile)) {
                int exitCode = process.exitValue();
                String stdout = readLimited(process.getInputStream(), 4_000);
                String message = "reasoning child exited with code " + exitCode;
                if (!stderr.isBlank()) {
                    message += ": " + stderr;
                }
                if (!stdout.isBlank()) message += ": " + stdout;
                ReasoningStatus status = exitCode == 137 || exitCode == 134
                        || (exitCode == 3 && (stderr + stdout).contains("OutOfMemoryError"))
                        ? ReasoningStatus.RESOURCE_EXHAUSTED : ReasoningStatus.FAILED;
                return result(request, digest, status, elapsed(started),
                        List.of(message), List.of(), List.of());
            }
            ReasoningResult childResult = readResult(resultFile);
            if (!request.requestId().equals(childResult.requestId())
                    || !digest.equals(childResult.requestDigest())
                    || childResult.task() != request.task().kind()) {
                return result(request, digest, ReasoningStatus.FAILED, elapsed(started),
                        List.of("reasoning child result does not match the request envelope"), List.of(), List.of());
            }
            return childResult;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            if (process != null) {
                destroy(process);
            }
            return result(request, digest, ReasoningStatus.TIMEOUT, elapsed(started),
                    List.of("reasoning worker interrupted"), List.of(), List.of());
        } catch (IOException | RuntimeException exception) {
            if (process != null) {
                destroy(process);
            }
            return result(request, digest, ReasoningStatus.FAILED, elapsed(started),
                    List.of(message(exception)), List.of(), List.of());
        } finally {
            concurrency.release();
            if (directory != null) {
                deleteTree(directory);
            }
        }
    }

    /** Entry point used by the isolated child process. */
    public static void main(String[] args) throws Exception {
        if (args.length != 3 || !"--child".equals(args[0])) {
            throw new IllegalArgumentException("usage: HermitReasoningWorker --child request-v1.json result-v1.json");
        }
        Path requestFile = Path.of(args[1]);
        Path resultFile = Path.of(args[2]);
        ReasoningRequest request = readRequest(requestFile);
        ReasoningResult result = evaluate(request);
        Path temporary = resultFile.resolveSibling(resultFile.getFileName() + ".tmp");
        writeResult(temporary, result);
        Files.move(temporary, resultFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    /* The core module stays JDK-only. These small wire codecs avoid making its
       Optional/record model depend on Jackson's optional datatype module. */
    private static void writeRequest(Path path, ReasoningRequest request) throws IOException {
        ObjectNode root = JSON.createObjectNode();
        root.put("schemaVersion", request.schemaVersion());
        root.put("requestId", request.requestId());
        root.set("document", documentNode(request.document()));
        ArrayNode imports = root.putArray("lockedImports");
        request.lockedImports().forEach(locked -> imports.add(importNode(locked)));
        root.put("scope", request.scope().name());
        array(root, "ontologyABoxAssertions", request.ontologyABoxAssertions());
        ArrayNode facts = root.putArray("acceptedFacts");
        request.acceptedFacts().forEach(fact -> facts.add(factNode(fact)));
        request.validDuring().ifPresentOrElse(value -> root.set("validDuring", rangeNode(value)),
                () -> root.putNull("validDuring"));
        root.set("task", taskNode(request.task()));
        ObjectNode engine = root.putObject("engine");
        engine.put("name", request.engine().name());
        engine.put("version", request.engine().version());
        JSON.writeValue(path.toFile(), root);
    }

    private static ReasoningRequest readRequest(Path path) throws IOException {
        JsonNode root = JSON.readTree(path.toFile());
        JsonNode documentNode = required(root, "document");
        OntologyDocument document = new OntologyDocument(
                text(documentNode, "ontologyId"), text(documentNode, "revisionId"),
                text(documentNode, "ontologyIri"), optionalText(documentNode, "versionIri"),
                OntologyDocumentSyntax.valueOf(text(documentNode, "syntax")),
                text(documentNode, "documentText"), text(documentNode, "documentDigest"),
                text(documentNode, "importLockDigest"), text(documentNode, "modelSchema"));
        List<LockedImport> imports = new ArrayList<>();
        for (JsonNode item : required(root, "lockedImports")) {
            imports.add(new LockedImport(text(item, "requestedIri"), text(item, "resolvedOntologyIri"),
                    optionalText(item, "versionIri"), OntologyDocumentSyntax.valueOf(text(item, "syntax")),
                    text(item, "documentText"), text(item, "contentDigest"), text(item, "artifactId")));
        }
        List<String> ontologyABox = new ArrayList<>();
        for (JsonNode item : required(root, "ontologyABoxAssertions")) {
            ontologyABox.add(item.asText());
        }
        List<ReasoningRequest.FactSnapshot> facts = new ArrayList<>();
        for (JsonNode item : required(root, "acceptedFacts")) {
            facts.add(new ReasoningRequest.FactSnapshot(text(item, "factId"),
                    text(item, "assertionFunctionalSyntax"), text(item, "snapshotId"),
                    Instant.parse(text(item, "capturedAt")), optionalInstant(item, "validFrom"),
                    optionalInstant(item, "validTo"), text(item, "evidenceDigest")));
        }
        JsonNode range = root.get("validDuring");
        java.util.Optional<ReasoningRequest.TimeRange> validDuring = range == null || range.isNull()
                ? java.util.Optional.empty()
                : java.util.Optional.of(new ReasoningRequest.TimeRange(
                        optionalInstant(range, "fromInclusive"), optionalInstant(range, "toExclusive")));
        JsonNode task = required(root, "task");
        ReasoningRequest.Task parsedTask = new ReasoningRequest.Task(
                ReasoningRequest.TaskKind.valueOf(text(task, "kind")),
                optionalText(task, "classIri"), optionalText(task, "individualIri"),
                optionalText(task, "axiomFunctionalSyntax"));
        JsonNode engine = required(root, "engine");
        return new ReasoningRequest(text(root, "schemaVersion"), text(root, "requestId"), document, imports,
                ReasoningRequest.AssertionScope.valueOf(text(root, "scope")), ontologyABox, facts,
                validDuring, parsedTask, new ReasoningRequest.Engine(text(engine, "name"), text(engine, "version")));
    }

    private static void writeResult(Path path, ReasoningResult result) throws IOException {
        ObjectNode root = JSON.createObjectNode();
        root.put("schemaVersion", result.schemaVersion());
        root.put("requestId", result.requestId());
        root.put("status", result.status().name());
        root.put("task", result.task().name());
        root.put("requestDigest", result.requestDigest());
        root.put("engineName", result.engineName());
        root.put("engineVersion", result.engineVersion());
        root.put("durationMillis", result.durationMillis());
        ArrayNode relations = root.putArray("classRelations");
        result.classRelations().forEach(relation -> {
            ObjectNode node = relations.addObject();
            node.put("classIri", relation.classIri());
            array(node, "superClassIris", relation.superClassIris());
        });
        ArrayNode individuals = root.putArray("individualTypes");
        result.individualTypes().forEach(value -> {
            ObjectNode node = individuals.addObject();
            node.put("individualIri", value.individualIri());
            array(node, "classIris", value.classIris());
        });
        array(root, "diagnostics", result.diagnostics());
        ObjectNode provenance = root.putObject("provenance");
        provenance.put("scope", result.provenance().scope());
        provenance.put("task", result.provenance().task());
        provenance.put("completedAt", result.provenance().completedAt().toString());
        provenance.put("workerProtocolVersion", result.provenance().workerProtocolVersion());
        JSON.writeValue(path.toFile(), root);
    }

    private static ReasoningResult readResult(Path path) throws IOException {
        JsonNode root = JSON.readTree(path.toFile());
        List<ReasoningResult.ClassRelation> relations = new ArrayList<>();
        for (JsonNode item : required(root, "classRelations")) {
            Set<String> supers = new LinkedHashSet<>();
            for (JsonNode value : required(item, "superClassIris")) {
                supers.add(value.asText());
            }
            relations.add(new ClassRelation(text(item, "classIri"), supers));
        }
        List<ReasoningResult.IndividualTypes> individuals = new ArrayList<>();
        for (JsonNode item : required(root, "individualTypes")) {
            Set<String> types = new LinkedHashSet<>();
            for (JsonNode value : required(item, "classIris")) {
                types.add(value.asText());
            }
            individuals.add(new IndividualTypes(text(item, "individualIri"), types));
        }
        List<String> diagnostics = new ArrayList<>();
        for (JsonNode item : required(root, "diagnostics")) {
            diagnostics.add(item.asText());
        }
        JsonNode provenance = required(root, "provenance");
        return new ReasoningResult(text(root, "schemaVersion"), text(root, "requestId"),
                ReasoningStatus.valueOf(text(root, "status")),
                ReasoningRequest.TaskKind.valueOf(text(root, "task")), text(root, "requestDigest"),
                text(root, "engineName"), text(root, "engineVersion"),
                required(root, "durationMillis").asLong(), relations, individuals, diagnostics,
                new ReasoningResult.Provenance(text(provenance, "scope"), text(provenance, "task"),
                        Instant.parse(text(provenance, "completedAt")),
                        text(provenance, "workerProtocolVersion")));
    }

    private static ObjectNode documentNode(OntologyDocument document) {
        ObjectNode node = JSON.createObjectNode();
        node.put("ontologyId", document.ontologyId());
        node.put("revisionId", document.revisionId());
        node.put("ontologyIri", document.ontologyIri());
        optional(node, "versionIri", document.versionIri());
        node.put("syntax", document.syntax().name());
        node.put("documentText", document.documentText());
        node.put("documentDigest", document.documentDigest());
        node.put("importLockDigest", document.importLockDigest());
        node.put("modelSchema", document.modelSchema());
        return node;
    }

    private static ObjectNode importNode(LockedImport locked) {
        ObjectNode node = JSON.createObjectNode();
        node.put("requestedIri", locked.requestedIri());
        node.put("resolvedOntologyIri", locked.resolvedOntologyIri());
        optional(node, "versionIri", locked.versionIri());
        node.put("syntax", locked.syntax().name());
        node.put("documentText", locked.documentText());
        node.put("contentDigest", locked.contentDigest());
        node.put("artifactId", locked.artifactId());
        return node;
    }

    private static ObjectNode factNode(ReasoningRequest.FactSnapshot fact) {
        ObjectNode node = JSON.createObjectNode();
        node.put("factId", fact.factId());
        node.put("assertionFunctionalSyntax", fact.assertionFunctionalSyntax());
        node.put("snapshotId", fact.snapshotId());
        node.put("capturedAt", fact.capturedAt().toString());
        optional(node, "validFrom", fact.validFrom().map(Instant::toString));
        optional(node, "validTo", fact.validTo().map(Instant::toString));
        node.put("evidenceDigest", fact.evidenceDigest());
        return node;
    }

    private static ObjectNode rangeNode(ReasoningRequest.TimeRange range) {
        ObjectNode node = JSON.createObjectNode();
        optional(node, "fromInclusive", range.fromInclusive().map(Instant::toString));
        optional(node, "toExclusive", range.toExclusive().map(Instant::toString));
        return node;
    }

    private static ObjectNode taskNode(ReasoningRequest.Task task) {
        ObjectNode node = JSON.createObjectNode();
        node.put("kind", task.kind().name());
        optional(node, "classIri", task.classIri());
        optional(node, "individualIri", task.individualIri());
        optional(node, "axiomFunctionalSyntax", task.axiomFunctionalSyntax());
        return node;
    }

    private static void array(ObjectNode parent, String field, Iterable<String> values) {
        ArrayNode array = parent.putArray(field);
        values.forEach(array::add);
    }

    private static void optional(ObjectNode parent, String field, java.util.Optional<String> value) {
        value.ifPresentOrElse(item -> parent.put(field, item), () -> parent.putNull(field));
    }

    private static String text(JsonNode parent, String field) {
        JsonNode value = required(parent, field);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException("wire field " + field + " must be nonblank text");
        }
        return value.asText();
    }

    private static java.util.Optional<String> optionalText(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        return value == null || value.isNull() ? java.util.Optional.empty() : java.util.Optional.of(text(parent, field));
    }

    private static java.util.Optional<Instant> optionalInstant(JsonNode parent, String field) {
        return optionalText(parent, field).map(Instant::parse);
    }

    private static JsonNode required(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || value.isNull()) {
            throw new IllegalArgumentException("wire field is missing: " + field);
        }
        return value;
    }

    static String requestDigest(ReasoningRequest request) {
        StringBuilder canonical = new StringBuilder();
        append(canonical, request.schemaVersion());
        append(canonical, request.requestId());
        append(canonical, request.document().ontologyId());
        append(canonical, request.document().revisionId());
        append(canonical, request.document().ontologyIri());
        append(canonical, request.document().versionIri().orElse(""));
        append(canonical, request.document().documentDigest());
        append(canonical, request.document().importLockDigest());
        append(canonical, request.document().syntax().name());
        append(canonical, request.document().modelSchema());
        append(canonical, request.scope().name());
        request.lockedImports().forEach(locked -> {
            append(canonical, locked.requestedIri());
            append(canonical, locked.resolvedOntologyIri());
            append(canonical, locked.versionIri().orElse(""));
            append(canonical, locked.syntax().name());
            append(canonical, locked.contentDigest());
            append(canonical, locked.artifactId());
        });
        request.ontologyABoxAssertions().forEach(item -> append(canonical, item));
        request.acceptedFacts().forEach(fact -> {
            append(canonical, fact.factId());
            append(canonical, fact.assertionFunctionalSyntax());
            append(canonical, fact.snapshotId());
            append(canonical, fact.capturedAt().toString());
            append(canonical, fact.validFrom().map(Instant::toString).orElse(""));
            append(canonical, fact.validTo().map(Instant::toString).orElse(""));
            append(canonical, fact.evidenceDigest());
        });
        request.validDuring().ifPresent(range -> {
            append(canonical, range.fromInclusive().map(Instant::toString).orElse(""));
            append(canonical, range.toExclusive().map(Instant::toString).orElse(""));
        });
        append(canonical, request.task().kind().name());
        append(canonical, request.task().classIri().orElse(""));
        append(canonical, request.task().individualIri().orElse(""));
        append(canonical, request.task().axiomFunctionalSyntax().orElse(""));
        append(canonical, request.engine().name());
        append(canonical, request.engine().version());
        return OntologyDocument.sha256(canonical.toString());
    }

    private static ReasoningResult evaluate(ReasoningRequest request) {
        String digest = requestDigest(request);
        long started = System.nanoTime();
        try {
            if (!ReasoningRequest.Engine.hermit().equals(request.engine())) {
                return result(request, digest, ReasoningStatus.UNSUPPORTED, elapsed(started),
                        List.of("unsupported reasoning engine: " + request.engine().name() + " "
                                + request.engine().version()), List.of(), List.of());
            }
            try (ReasoningWorkspace workspace = ReasoningWorkspace.create(request)) {
                OWLOntology ontology = workspace.ontology();
                OWLProfileReport profile = new OWL2DLProfile().checkOntology(ontology);
                if (!profile.isInProfile()) {
                    return result(request, digest, ReasoningStatus.PROFILE_VIOLATION, elapsed(started),
                            profile.getViolations().stream().map(Object::toString).toList(), List.of(), List.of());
                }
                java.util.Optional<String> explicitContradiction = explicitTopBottomContradiction(
                        ontology, workspace.factory());
                if (explicitContradiction.isPresent()) {
                    return result(request, digest, ReasoningStatus.INCONSISTENT, elapsed(started),
                            List.of(explicitContradiction.orElseThrow()), List.of(), List.of());
                }
                Configuration configuration = new Configuration();
                configuration.ignoreUnsupportedDatatypes = false;
                configuration.throwInconsistentOntologyException = false;
                OWLReasoner reasoner = new Reasoner.ReasonerFactory().createReasoner(ontology, configuration);
                try {
                    if (!reasoner.isConsistent()) {
                        return result(request, digest, ReasoningStatus.INCONSISTENT, elapsed(started),
                                List.of("ontology and selected assertion scope are inconsistent"), List.of(), List.of());
                    }
                    return switch (request.task().kind()) {
                        case CONSISTENCY -> result(request, digest, ReasoningStatus.CONSISTENT,
                                elapsed(started), List.of(), List.of(), List.of());
                        case CLASSIFICATION -> classify(request, reasoner, ontology, digest, started);
                        case INSTANCE_TYPES -> instanceTypes(request, reasoner, ontology, workspace.factory(), digest, started);
                        case AXIOM_ENTAILMENT -> entailment(request, reasoner, workspace, digest, started);
                    };
                } finally {
                    reasoner.dispose();
                }
            }
        } catch (UnsupportedFailure exception) {
            return result(request, digest, ReasoningStatus.UNSUPPORTED, elapsed(started),
                    List.of(exception.getMessage()), List.of(), List.of());
        } catch (ParseFailure | vip.mate.semantic.core.ontology.OntologyDocumentException exception) {
            return result(request, digest, ReasoningStatus.PARSE_ERROR, elapsed(started),
                    List.of(exception.getMessage()), List.of(), List.of());
        } catch (RuntimeException exception) {
            return result(request, digest, classifyFailure(exception), elapsed(started),
                    List.of(message(exception)), List.of(), List.of());
        }
    }

    /**
     * HermiT 1.4.5 cannot initialize an ontology containing the explicit OWL
     * top/bottom contradiction, although the OWL 2 direct semantics classify
     * it as inconsistent. Recognize only the two direct axiom forms whose
     * semantics are unambiguous; this is not a fallback reasoner.
     */
    private static java.util.Optional<String> explicitTopBottomContradiction(
            OWLOntology ontology, OWLDataFactory factory) {
        OWLClass top = factory.getOWLThing();
        OWLClass bottom = factory.getOWLNothing();
        for (OWLAxiom axiom : ontology.getAxioms()) {
            if (axiom instanceof OWLEquivalentClassesAxiom equivalent
                    && equivalent.getClassExpressions().contains(top)
                    && equivalent.getClassExpressions().contains(bottom)) {
                return java.util.Optional.of("explicit top-bottom contradiction in EquivalentClasses");
            }
            if (axiom instanceof OWLSubClassOfAxiom subClass
                    && subClass.getSubClass().equals(top)
                    && subClass.getSuperClass().equals(bottom)) {
                return java.util.Optional.of("explicit top-bottom contradiction in SubClassOf");
            }
        }
        return java.util.Optional.empty();
    }

    private static ReasoningResult classify(ReasoningRequest request, OWLReasoner reasoner,
            OWLOntology ontology, String digest, long started) {
        List<ClassRelation> relations = ontology.classesInSignature()
                .sorted(Comparator.comparing(item -> item.getIRI().getIRIString()))
                .map(item -> new ClassRelation(item.getIRI().getIRIString(), reasoner.getSuperClasses(item, false)
                        .getFlattened().stream().map(value -> value.getIRI().getIRIString())
                        .collect(Collectors.toCollection(LinkedHashSet::new))))
                .toList();
        return result(request, digest, ReasoningStatus.CONSISTENT, elapsed(started), List.of(), relations, List.of());
    }

    private static ReasoningResult instanceTypes(ReasoningRequest request, OWLReasoner reasoner,
            OWLOntology ontology, OWLDataFactory factory, String digest, long started) {
        String individualIri = request.task().individualIri().orElseThrow();
        if (ontology.individualsInSignature()
                .noneMatch(item -> individualIri.equals(item.getIRI().getIRIString()))) {
            throw new UnsupportedFailure("named individual is not present in the selected assertion scope: "
                    + individualIri);
        }
        OWLNamedIndividual individual = factory.getOWLNamedIndividual(IRI.create(individualIri));
        Set<String> types = reasoner.getTypes(individual, false).getFlattened().stream()
                .map(value -> value.getIRI().getIRIString())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<IndividualTypes> values = List.of(new IndividualTypes(individualIri, types));
        return result(request, digest, ReasoningStatus.CONSISTENT, elapsed(started), List.of(), List.of(), values);
    }

    private static ReasoningResult entailment(ReasoningRequest request, OWLReasoner reasoner,
            ReasoningWorkspace workspace, String digest, long started) {
        OWLAxiom query = parseSingleAxiom(workspace.manager(),
                request.task().axiomFunctionalSyntax().orElseThrow());
        if (!reasoner.isEntailmentCheckingSupported(query.getAxiomType())) {
            throw new UnsupportedFailure("HermiT does not support entailment for " + query.getAxiomType().getName());
        }
        ReasoningStatus status = reasoner.isEntailed(query)
                ? ReasoningStatus.ENTAILED : ReasoningStatus.NOT_ENTAILED;
        return result(request, digest, status, elapsed(started), List.of(), List.of(), List.of());
    }

    private static OWLAxiom parseSingleAxiom(OWLOntologyManager manager, String assertion) {
        if (assertion == null || assertion.isBlank()
                || FunctionalSyntaxGuard.structure(assertion).matches("(?s).*\\bImport\\s*\\(.*")
                || FunctionalSyntaxGuard.structure(assertion).matches("(?s).*\\bOntology\\s*\\(.*")) {
            throw new ParseFailure("reasoning assertion must be one Functional Syntax axiom");
        }
        String source = "Ontology(<urn:mateclaw:reasoning-query> " + assertion + ")";
        try {
            OWLOntology ontology = manager.loadOntologyFromOntologyDocument(
                    new StringDocumentSource(source, IRI.create("urn:mateclaw:reasoning-query"),
                            new FunctionalSyntaxDocumentFormat(), null),
                    new OWLOntologyLoaderConfiguration()
                            .setMissingImportHandlingStrategy(MissingImportHandlingStrategy.THROW_EXCEPTION)
                            .setFollowRedirects(false).setStrict(true));
            try {
                if (!ontology.getImportsDeclarations().isEmpty() || ontology.annotations().findAny().isPresent()) {
                    throw new ParseFailure("reasoning assertion cannot contain imports or annotations");
                }
                List<OWLAxiom> axioms = ontology.getAxioms().stream().toList();
                if (axioms.size() != 1) {
                    throw new ParseFailure("reasoning assertion must contain exactly one axiom");
                }
                return axioms.getFirst();
            } finally {
                manager.removeOntology(ontology);
            }
        } catch (OWLOntologyCreationException exception) {
            throw new ParseFailure("invalid Functional Syntax reasoning assertion: " + exception.getMessage(), exception);
        }
    }

    private static ReasoningResult result(ReasoningRequest request, String digest, ReasoningStatus status,
            long durationMillis, List<String> diagnostics, List<ClassRelation> classRelations,
            List<IndividualTypes> individualTypes) {
        return new ReasoningResult(ReasoningResult.SCHEMA_VERSION, request.requestId(), status,
                request.task().kind(), digest, request.engine().name(), request.engine().version(),
                Math.max(0, durationMillis), classRelations, individualTypes, diagnostics,
                new ReasoningResult.Provenance(request.scope().name(), request.task().kind().name(),
                        Instant.now(), WORKER_PROTOCOL_VERSION));
    }

    private static long elapsed(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    private static ReasoningStatus classifyFailure(RuntimeException exception) {
        String text = message(exception).toLowerCase();
        if (text.contains("unsupported") || text.contains("datatype")) {
            return ReasoningStatus.UNSUPPORTED;
        }
        if (text.contains("parse") || text.contains("syntax") || text.contains("ontology creation")) {
            return ReasoningStatus.PARSE_ERROR;
        }
        return ReasoningStatus.FAILED;
    }

    private static String message(Throwable exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    private static void append(StringBuilder builder, String value) {
        builder.append(value.length()).append(':').append(value).append('|');
    }

    private static String readLimited(InputStream input, int limit) throws IOException {
        byte[] bytes = input.readNBytes(limit);
        return new String(bytes, StandardCharsets.UTF_8).trim();
    }

    private static void destroy(Process process) {
        process.destroy();
        try {
            if (!process.waitFor(250, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private static void deleteTree(Path directory) {
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best effort after the child has exited or been killed.
                }
            });
        } catch (IOException ignored) {
            // Best effort cleanup; the protocol files are temporary and contain no secrets.
        }
    }

    private static Duration requireTimeout(Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        return value;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    /** Expands an executable Spring Boot jar into a normal child class path. */
    private static final class RuntimeClasspath {
        private static final String BOOT_CLASSES = "BOOT-INF/classes/";
        private static final String BOOT_LIB = "BOOT-INF/lib/";

        static String prepare(String configuredClasspath, Path runtimeDirectory) throws IOException {
            String[] configuredEntries = configuredClasspath.split(java.util.regex.Pattern.quote(
                    java.io.File.pathSeparator));
            List<Path> ordinaryEntries = new ArrayList<>();
            List<Path> bootJars = new ArrayList<>();
            for (String configuredEntry : configuredEntries) {
                if (configuredEntry.isBlank()) {
                    continue;
                }
                Path entry = Path.of(configuredEntry);
                if (isBootJar(entry)) {
                    bootJars.add(entry);
                } else {
                    ordinaryEntries.add(entry);
                }
            }
            if (bootJars.isEmpty()) {
                return configuredClasspath;
            }

            Path classesDirectory = runtimeDirectory.resolve("classes");
            Path librariesDirectory = runtimeDirectory.resolve("lib");
            Files.createDirectories(classesDirectory);
            Files.createDirectories(librariesDirectory);
            for (Path bootJar : bootJars) {
                expandBootJar(bootJar, classesDirectory, librariesDirectory);
            }
            List<String> childEntries = new ArrayList<>();
            childEntries.add(classesDirectory.toString());
            try (Stream<Path> libraries = Files.list(librariesDirectory)) {
                libraries.filter(Files::isRegularFile)
                        .sorted()
                        .forEach(path -> childEntries.add(path.toString()));
            }
            ordinaryEntries.stream().map(Path::toString).forEach(childEntries::add);
            return String.join(java.io.File.pathSeparator, childEntries);
        }

        private static boolean isBootJar(Path candidate) {
            if (!Files.isRegularFile(candidate)) {
                return false;
            }
            try (JarFile jar = new JarFile(candidate.toFile())) {
                return jar.stream().anyMatch(entry -> entry.getName().startsWith(BOOT_CLASSES)
                        || entry.getName().startsWith(BOOT_LIB));
            } catch (IOException exception) {
                return false;
            }
        }

        private static void expandBootJar(Path bootJar, Path classesDirectory, Path librariesDirectory)
                throws IOException {
            try (JarFile jar = new JarFile(bootJar.toFile())) {
                jar.stream().filter(entry -> !entry.isDirectory()).forEach(entry -> {
                    try {
                        if (entry.getName().startsWith(BOOT_CLASSES)) {
                            copyEntry(jar, entry, classesDirectory, BOOT_CLASSES);
                        } else if (entry.getName().startsWith(BOOT_LIB)) {
                            copyEntry(jar, entry, librariesDirectory, BOOT_LIB);
                        }
                    } catch (IOException exception) {
                        throw new RuntimeIOException(exception);
                    }
                });
            } catch (RuntimeIOException exception) {
                throw exception.cause;
            }
        }

        private static void copyEntry(JarFile jar, JarEntry entry, Path root, String prefix) throws IOException {
            String relativeName = entry.getName().substring(prefix.length());
            Path destination = root.resolve(relativeName).normalize();
            if (!destination.startsWith(root) || relativeName.isBlank()) {
                throw new IOException("invalid nested runtime entry: " + entry.getName());
            }
            Files.createDirectories(destination.getParent());
            if (Files.exists(destination)) {
                String uniqueName = Integer.toHexString(entry.getName().hashCode()) + "-" + destination.getFileName();
                destination = destination.resolveSibling(uniqueName);
            }
            try (InputStream input = jar.getInputStream(entry)) {
                Files.copy(input, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        }

        private static final class RuntimeIOException extends RuntimeException {
            private final IOException cause;

            RuntimeIOException(IOException cause) {
                super(cause);
                this.cause = cause;
            }
        }
    }

    private static final class ReasoningWorkspace implements AutoCloseable {
        private final Path directory;
        private final OWLOntologyManager manager;
        private final OWLOntology ontology;
        private final OWLDataFactory factory;

        private ReasoningWorkspace(Path directory, OWLOntologyManager manager,
                OWLOntology ontology, OWLDataFactory factory) {
            this.directory = directory;
            this.manager = manager;
            this.ontology = ontology;
            this.factory = factory;
        }

        static ReasoningWorkspace create(ReasoningRequest request) {
            Path directory = null;
            try {
                directory = Files.createTempDirectory("mateclaw-reasoning-owl-");
                Path root = directory.resolve("root" + extension(request.document().syntax()));
                Path missing = directory.resolve("missing");
                Files.writeString(root, RdfXmlInput.normalize(request.document().documentText(), request.document().syntax()), StandardCharsets.UTF_8);
                Files.writeString(missing, "", StandardCharsets.UTF_8);
                Map<IRI, IRI> mappings = new HashMap<>();
                for (int index = 0; index < request.lockedImports().size(); index++) {
                    LockedImport locked = request.lockedImports().get(index);
                    Path artifact = directory.resolve("import-" + index + extension(locked.syntax()));
                    Files.writeString(artifact, RdfXmlInput.normalize(locked.documentText(), locked.syntax()), StandardCharsets.UTF_8);
                    IRI artifactIri = IRI.create(artifact.toUri());
                    mappings.put(IRI.create(locked.requestedIri()), artifactIri);
                    mappings.put(IRI.create(locked.resolvedOntologyIri()), artifactIri);
                }
                OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
                manager.clearIRIMappers();
                AtomicReference<IRI> missingImport = new AtomicReference<>();
                manager.addIRIMapper(new LockedIriMapper(mappings, IRI.create(missing.toUri()), missingImport));
                manager.addMissingImportListener((MissingImportEvent event) ->
                        missingImport.compareAndSet(null, event.getImportedOntologyURI()));
                OWLOntology rootOntology = load(manager, root, request.document().syntax());
                if (missingImport.get() != null) {
                    throw new ParseFailure("locked imports do not contain " + missingImport.get());
                }
                rootOntology.getImportsClosure().forEach(RdfClassExpressions::normalize);
                Set<OWLAxiom> selected = rootOntology.getImportsClosure().stream()
                        .flatMap(member -> member.getAxioms().stream())
                        .filter(axiom -> request.scope().includesOntologyABox()
                                || !AxiomType.ABoxAxiomTypes.contains(axiom.getAxiomType()))
                        .collect(Collectors.toCollection(LinkedHashSet::new));
                if (request.scope().includesOntologyABox()) {
                    request.ontologyABoxAssertions().stream()
                            .map(assertion -> parseABoxAxiom(manager, assertion))
                            .forEach(selected::add);
                }
                if (request.scope().includesAcceptedFacts()) {
                    request.acceptedFacts().stream()
                            .filter(fact -> active(fact, request.validDuring()))
                            .map(fact -> parseABoxAxiom(manager, fact.assertionFunctionalSyntax()))
                            .forEach(selected::add);
                }
                validateLiteralLexicalSpace(selected);
                IRI reasoningIri = IRI.create("urn:mateclaw:reasoning:" + request.requestId());
                OWLOntology ontology = manager.createOntology(selected, reasoningIri);
                return new ReasoningWorkspace(directory, manager, ontology, manager.getOWLDataFactory());
            } catch (OWLOntologyCreationException | IOException exception) {
                if (directory != null) {
                    deleteTree(directory);
                }
                throw new ParseFailure("unable to load locked OWL document: " + message(exception), exception);
            } catch (RuntimeException exception) {
                if (directory != null) {
                    deleteTree(directory);
                }
                throw exception;
            }
        }

        private static OWLAxiom parseABoxAxiom(OWLOntologyManager manager, String assertion) {
            OWLAxiom axiom = parseSingleAxiom(manager, assertion);
            if (!AxiomType.ABoxAxiomTypes.contains(axiom.getAxiomType())) {
                throw new UnsupportedFailure("selected fact is not an OWL ABox assertion: "
                        + axiom.getAxiomType().getName());
            }
            return axiom;
        }

        private static void validateLiteralLexicalSpace(Set<OWLAxiom> axioms) {
            for (OWLAxiom axiom : axioms) {
                if (!(axiom instanceof OWLDataPropertyAssertionAxiom dataAssertion)) {
                    continue;
                }
                var literal = dataAssertion.getObject();
                IRI datatype = literal.getDatatype().getIRI();
                if (OWL2Datatype.isBuiltIn(datatype)
                        && !OWL2Datatype.getDatatype(datatype).isInLexicalSpace(literal.getLiteral())) {
                    throw new ParseFailure("literal is outside the lexical space of " + datatype.getIRIString());
                }
            }
        }

        private static OWLOntology load(OWLOntologyManager manager, Path root, OntologyDocumentSyntax syntax)
                throws OWLOntologyCreationException {
            OWLDocumentFormat format = syntax == OntologyDocumentSyntax.FUNCTIONAL
                    ? new FunctionalSyntaxDocumentFormat() : new RDFXMLDocumentFormat();
            OWLOntologyDocumentSource source = new StringDocumentSource(
                    read(root), IRI.create(root.toUri()), format, null);
            OWLOntologyLoaderConfiguration configuration = new OWLOntologyLoaderConfiguration()
                    .setMissingImportHandlingStrategy(MissingImportHandlingStrategy.THROW_EXCEPTION)
                    .setFollowRedirects(false).setConnectionTimeout(1000)
                    .setStrict(syntax == OntologyDocumentSyntax.FUNCTIONAL);
            return manager.loadOntologyFromOntologyDocument(source, configuration);
        }

        private static String read(Path path) {
            try {
                return Files.readString(path, StandardCharsets.UTF_8);
            } catch (IOException exception) {
                throw new ParseFailure("unable to read isolated OWL document", exception);
            }
        }

        private static boolean active(ReasoningRequest.FactSnapshot fact,
                java.util.Optional<ReasoningRequest.TimeRange> requested) {
            if (requested.isEmpty()) {
                return true;
            }
            ReasoningRequest.TimeRange range = requested.orElseThrow();
            boolean startsBeforeEnd = range.toExclusive().isEmpty()
                    || fact.validFrom().isEmpty()
                    || fact.validFrom().orElseThrow().isBefore(range.toExclusive().orElseThrow());
            boolean endsAfterStart = range.fromInclusive().isEmpty()
                    || fact.validTo().isEmpty()
                    || fact.validTo().orElseThrow().isAfter(range.fromInclusive().orElseThrow());
            return startsBeforeEnd && endsAfterStart;
        }

        OWLOntology ontology() { return ontology; }
        OWLOntologyManager manager() { return manager; }
        OWLDataFactory factory() { return factory; }

        @Override
        public void close() {
            manager.clearOntologies();
            deleteTree(directory);
        }
    }

    private static final class LockedIriMapper implements OWLOntologyIRIMapper {
        private final Map<IRI, IRI> mappings;
        private final IRI missingDocument;
        private final AtomicReference<IRI> missingImport;

        private LockedIriMapper(Map<IRI, IRI> mappings, IRI missingDocument,
                AtomicReference<IRI> missingImport) {
            this.mappings = Map.copyOf(mappings);
            this.missingDocument = missingDocument;
            this.missingImport = missingImport;
        }

        @Override
        public IRI getDocumentIRI(IRI ontologyIRI) {
            IRI mapped = mappings.get(ontologyIRI);
            if (mapped != null) {
                return mapped;
            }
            missingImport.compareAndSet(null, ontologyIRI);
            return missingDocument;
        }
    }

    private static String extension(OntologyDocumentSyntax syntax) {
        return syntax == OntologyDocumentSyntax.FUNCTIONAL ? ".ofn" : ".rdf";
    }

    private static class ParseFailure extends RuntimeException {
        ParseFailure(String message) { super(message); }
        ParseFailure(String message, Throwable cause) { super(message, cause); }
    }

    private static class UnsupportedFailure extends RuntimeException {
        UnsupportedFailure(String message) { super(message); }
    }

}

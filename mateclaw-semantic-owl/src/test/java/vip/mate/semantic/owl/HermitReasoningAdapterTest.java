package vip.mate.semantic.owl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import vip.mate.semantic.core.ontology.LockedImport;
import vip.mate.semantic.core.ontology.OntologyDocument;
import vip.mate.semantic.core.ontology.OntologyDocumentSyntax;
import vip.mate.semantic.core.reasoning.ReasoningRequest;
import vip.mate.semantic.core.reasoning.ReasoningRequest.AssertionScope;
import vip.mate.semantic.core.reasoning.ReasoningRequest.FactSnapshot;
import vip.mate.semantic.core.reasoning.ReasoningResult;
import vip.mate.semantic.core.reasoning.ReasoningResult.ReasoningStatus;

class HermitReasoningAdapterTest {
    private static final String C = "urn:test:C";
    private static final String D = "urn:test:D";
    private static final String P = "urn:test:p";
    private static final String I = "urn:test:i";

    @Test
    void annotationHierarchyDomainRangeAndNestedMetadataDoNotAddLogicalConsequences() {
        String declarations="Declaration(Class(<urn:test:C>)) Declaration(Class(<urn:test:D>)) "
            + "Declaration(Class(<urn:test:MetadataDomain>)) Declaration(AnnotationProperty(<urn:test:note>)) "
            + "Declaration(AnnotationProperty(<urn:test:parentNote>)) ";
        String baseline="Ontology(<urn:test:annotation-semantics> "+declarations
            + "SubClassOf(<urn:test:C> <urn:test:D>))";
        String annotated="Ontology(<urn:test:annotation-semantics> "
            + "Annotation(Annotation(<urn:test:note> \"nested provenance\") <urn:test:note> \"ontology metadata\") "
            + declarations
            + "SubClassOf(Annotation(<urn:test:note> \"axiom metadata\") <urn:test:C> <urn:test:D>) "
            + "SubAnnotationPropertyOf(<urn:test:note> <urn:test:parentNote>) "
            + "AnnotationPropertyDomain(<urn:test:parentNote> <urn:test:MetadataDomain>) "
            + "AnnotationPropertyRange(<urn:test:parentNote> <urn:test:MetadataDomain>) "
            + "AnnotationAssertion(<urn:test:note> <urn:test:C> <urn:test:D>))";
        var worker=new HermitReasoningWorker();
        var plain=worker.reason(request(baseline,ReasoningRequest.Task.classification()));
        var metadata=worker.reason(request(annotated,ReasoningRequest.Task.classification()));
        assertEquals(ReasoningStatus.CONSISTENT,plain.status(),()->plain.diagnostics().toString());
        assertEquals(ReasoningStatus.CONSISTENT,metadata.status(),()->metadata.diagnostics().toString());
        assertEquals(plain.classRelations(),metadata.classRelations(),
            "OWL-072..078 annotations must not introduce superclass consequences");
        var domain=worker.reason(request(annotated,ReasoningRequest.Task.entailment(
            "SubClassOf(<urn:test:C> <urn:test:MetadataDomain>)")));
        assertEquals(ReasoningStatus.NOT_ENTAILED,domain.status(),()->domain.diagnostics().toString());
    }

    @Test
    void launchesTheRealChildAndClassifiesThePinnedTbox() {
        String document = "Ontology(<urn:test:reasoning> "
                + "Declaration(Class(<" + C + ">)) "
                + "Declaration(Class(<" + D + ">)) "
                + "SubClassOf(<" + C + "> <" + D + ">))";

        var result = new HermitReasoningWorker().reason(
                request(document, ReasoningRequest.Task.classification()));

        assertEquals(ReasoningStatus.CONSISTENT, result.status(), () -> result.diagnostics().toString());
        assertTrue(result.classRelations().stream().anyMatch(relation ->
                relation.classIri().equals(C) && relation.superClassIris().contains(D)),
                () -> result.classRelations().toString());
        assertEquals("semantic-reasoning-worker-v1", result.provenance().workerProtocolVersion());
    }

    @Test
    void defaultScopeExcludesOntologyAboxAndExplicitScopeIncludesIt() {
        String document = "Ontology(<urn:test:reasoning> "
                + "Declaration(Class(<" + C + ">)) "
                + "ClassAssertion(<" + C + "> <" + I + ">) "
                + "DifferentIndividuals(<" + I + "> <urn:test:j>) "
                + "SameIndividual(<" + I + "> <urn:test:j>))";
        OntologyDocument ontology = ontology(document);

        var defaultResult = new HermitReasoningWorker().reason(new ReasoningRequest(
                ReasoningRequest.SCHEMA_VERSION, "default-scope", ontology, List.of(),
                AssertionScope.TBOX_ONLY, List.of(), List.of(), Optional.empty(),
                ReasoningRequest.Task.consistency(), ReasoningRequest.Engine.hermit()));
        var aboxResult = new HermitReasoningWorker().reason(new ReasoningRequest(
                ReasoningRequest.SCHEMA_VERSION, "abox-scope", ontology, List.of(),
                AssertionScope.ONTOLOGY_ABOX, List.of(), List.of(), Optional.empty(),
                ReasoningRequest.Task.consistency(), ReasoningRequest.Engine.hermit()));

        assertEquals(ReasoningStatus.CONSISTENT, defaultResult.status(),
                () -> defaultResult.diagnostics().toString());
        assertEquals(ReasoningStatus.INCONSISTENT, aboxResult.status(),
                () -> aboxResult.diagnostics().toString());
    }

    @Test
    void recognizesExplicitTopBottomContradictionsWithoutInvokingHermiT() {
        String binary = "Ontology(<urn:test:top-bottom> "
                + "EquivalentClasses(<http://www.w3.org/2002/07/owl#Thing> "
                + "<http://www.w3.org/2002/07/owl#Nothing>))";
        String multiOperand = "Ontology(<urn:test:top-bottom-multi> "
                + "Declaration(Class(<urn:test:Middle>)) "
                + "EquivalentClasses(<http://www.w3.org/2002/07/owl#Thing> "
                + "<urn:test:Middle> <http://www.w3.org/2002/07/owl#Nothing>))";

        assertTopBottomInconsistent(binary, AssertionScope.TBOX_ONLY);
        assertTopBottomInconsistent(binary, AssertionScope.ONTOLOGY_ABOX);
        assertTopBottomInconsistent(multiOperand, AssertionScope.TBOX_ONLY);
    }

    @Test
    void doesNotTreatARelatedButConsistentEquivalenceAsTopBottomContradiction() {
        String document = "Ontology(<urn:test:top-bottom-negative> "
                + "Declaration(Class(<urn:test:Middle>)) "
                + "EquivalentClasses(<http://www.w3.org/2002/07/owl#Thing> <urn:test:Middle>))";

        var result = new HermitReasoningWorker().reason(new ReasoningRequest(
                ReasoningRequest.SCHEMA_VERSION, "top-bottom-negative", ontology(document), List.of(),
                AssertionScope.TBOX_ONLY, List.of(), List.of(), Optional.empty(),
                ReasoningRequest.Task.consistency(), ReasoningRequest.Engine.hermit()));

        assertEquals(ReasoningStatus.CONSISTENT, result.status(), () -> result.diagnostics().toString());
    }

    @Test
    void keepsTopBottomDetectionScopedToSelectedOntologyAbox() {
        String document = "Ontology(<urn:test:top-bottom-scope> "
                + "Declaration(Class(<http://www.w3.org/2002/07/owl#Nothing>)) "
                + "ClassAssertion(<http://www.w3.org/2002/07/owl#Nothing> <urn:test:i>))";

        var tbox = new HermitReasoningWorker().reason(new ReasoningRequest(
                ReasoningRequest.SCHEMA_VERSION, "top-bottom-scope-tbox", ontology(document), List.of(),
                AssertionScope.TBOX_ONLY, List.of(), List.of(), Optional.empty(),
                ReasoningRequest.Task.consistency(), ReasoningRequest.Engine.hermit()));
        var abox = new HermitReasoningWorker().reason(new ReasoningRequest(
                ReasoningRequest.SCHEMA_VERSION, "top-bottom-scope-abox", ontology(document), List.of(),
                AssertionScope.ONTOLOGY_ABOX, List.of(), List.of(), Optional.empty(),
                ReasoningRequest.Task.consistency(), ReasoningRequest.Engine.hermit()));

        assertEquals(ReasoningStatus.CONSISTENT, tbox.status(), () -> tbox.diagnostics().toString());
        assertEquals(ReasoningStatus.INCONSISTENT, abox.status(), () -> abox.diagnostics().toString());
    }

    @Test
    void acceptedFactsAreASeparateExplicitReasoningScope() {
        String document = "Ontology(<urn:test:reasoning> "
                + "Declaration(Class(<" + C + ">)) "
                + "Declaration(ObjectProperty(<" + P + ">)))";
        FactSnapshot fact = new FactSnapshot("fact-1",
                "ObjectPropertyAssertion(<" + P + "> <" + I + "> <urn:test:j>)",
                "snapshot-1", Instant.parse("2026-09-09T00:00:00Z"), Optional.empty(), Optional.empty(), "evidence");
        FactSnapshot contradiction = new FactSnapshot("fact-2",
                "NegativeObjectPropertyAssertion(<" + P + "> <" + I + "> <urn:test:j>)",
                "snapshot-1", Instant.parse("2026-09-09T00:00:00Z"), Optional.empty(), Optional.empty(), "evidence");

        var result = new HermitReasoningWorker().reason(new ReasoningRequest(
                ReasoningRequest.SCHEMA_VERSION, "accepted-facts", ontology(document), List.of(),
                AssertionScope.ACCEPTED_FACTS, List.of(), List.of(fact, contradiction), Optional.empty(),
                ReasoningRequest.Task.consistency(), ReasoningRequest.Engine.hermit()));

        assertEquals(ReasoningStatus.INCONSISTENT, result.status(), () -> result.diagnostics().toString());
    }

    @Test
    void checksEntailmentOnlyAfterConsistencyAndAcceptedFactSelection() {
        String document = "Ontology(<urn:test:reasoning> "
                + "Declaration(Class(<" + C + ">)) "
                + "Declaration(Class(<" + D + ">)) "
                + "SubClassOf(<" + C + "> <" + D + ">))";
        FactSnapshot fact = new FactSnapshot("fact-1", "ClassAssertion(<" + C + "> <" + I + ">)",
                "snapshot-1", Instant.parse("2026-09-09T00:00:00Z"), Optional.empty(), Optional.empty(), "evidence");
        ReasoningRequest.Task query = ReasoningRequest.Task.entailment(
                "ClassAssertion(<" + D + "> <" + I + ">)");

        var result = new HermitReasoningWorker().reason(new ReasoningRequest(
                ReasoningRequest.SCHEMA_VERSION, "entailment", ontology(document), List.of(),
                AssertionScope.ACCEPTED_FACTS, List.of(), List.of(fact), Optional.empty(), query,
                ReasoningRequest.Engine.hermit()));

        assertEquals(ReasoningStatus.ENTAILED, result.status(), () -> result.diagnostics().toString());
    }

    @Test
    void returnsNamedIndividualTypesFromTheSelectedFacts() {
        String document = "Ontology(<urn:test:reasoning> Declaration(Class(<" + C + ">)))";
        FactSnapshot fact = new FactSnapshot("fact-1", "ClassAssertion(<" + C + "> <" + I + ">)",
                "snapshot-1", Instant.parse("2026-09-09T00:00:00Z"), Optional.empty(), Optional.empty(), "evidence");

        var result = new HermitReasoningWorker().reason(new ReasoningRequest(
                ReasoningRequest.SCHEMA_VERSION, "instance-types", ontology(document), List.of(),
                AssertionScope.ACCEPTED_FACTS, List.of(), List.of(fact), Optional.empty(),
                ReasoningRequest.Task.instanceTypes(I), ReasoningRequest.Engine.hermit()));

        assertEquals(ReasoningStatus.CONSISTENT, result.status(), () -> result.diagnostics().toString());
        assertTrue(result.individualTypes().getFirst().classIris().contains(C));
    }

    @Test
    void invalidOntologyProfileIsAnExplicitFailure() {
        String document = "Ontology(<urn:test:reasoning> "
                + "Declaration(Class(<" + C + ">)) "
                + "Declaration(ObjectProperty(<" + P + ">)) "
                + "TransitiveObjectProperty(<" + P + ">) "
                + "SubClassOf(ObjectMinCardinality(2 <" + P + "> <" + C + ">) ObjectMaxCardinality(1 <" + P + "> <" + C + ">)))";

        var result = new HermitReasoningWorker().reason(
                request(document, ReasoningRequest.Task.consistency()));

        assertEquals(ReasoningStatus.PROFILE_VIOLATION, result.status(), () -> result.diagnostics().toString());
    }

    @Test
    void unpinnedImportsFailInsideTheIsolatedLoader() {
        String document = "Ontology(<urn:test:reasoning> Import(<https://example.invalid/remote.owl>))";

        var result = new HermitReasoningWorker().reason(
                request(document, ReasoningRequest.Task.consistency()));

        assertEquals(ReasoningStatus.PARSE_ERROR, result.status(), () -> result.diagnostics().toString());
        assertTrue(result.diagnostics().stream().anyMatch(value -> value.contains("locked imports")));
    }

    @Test
    void invalidBuiltInLiteralIsNeverSilentlyAccepted() {
        String dataProperty = "urn:test:reading";
        String document = "Ontology(<urn:test:reasoning> Declaration(DataProperty(<" + dataProperty + ">)))";
        FactSnapshot fact = new FactSnapshot("fact-invalid", "DataPropertyAssertion(<" + dataProperty
                + "> <" + I + "> \"not-an-integer\"^^<http://www.w3.org/2001/XMLSchema#integer>)",
                "snapshot-1", Instant.parse("2026-09-09T00:00:00Z"), Optional.empty(), Optional.empty(), "evidence");

        var result = new HermitReasoningWorker().reason(new ReasoningRequest(
                ReasoningRequest.SCHEMA_VERSION, "invalid-literal", ontology(document), List.of(),
                AssertionScope.ACCEPTED_FACTS, List.of(), List.of(fact), Optional.empty(),
                ReasoningRequest.Task.consistency(), ReasoningRequest.Engine.hermit()));

        assertTrue(result.status() == ReasoningStatus.PARSE_ERROR || result.status() == ReasoningStatus.UNSUPPORTED,
                () -> result.status() + " " + result.diagnostics());
    }

    @Test
    void timeoutKillsTheChildAndReleasesTheSingleWorkerSlot() throws Exception {
        Path script = Files.createTempFile("mateclaw-reasoning-child-", ".sh");
        Path pidFile = Files.createTempFile("mateclaw-reasoning-pid-", ".txt");
        Files.writeString(script, "#!/bin/sh\necho $$ > '" + pidFile + "'\nwhile :; do :; done\n",
                StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(script, EnumSet.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
        try {
            HermitReasoningWorker worker = new HermitReasoningWorker(java.time.Duration.ofSeconds(5),
                    512, 1, script.toString(), "unused-classpath");
            var executor = Executors.newSingleThreadExecutor();
            Future<?> future = executor.submit(() -> worker.reason(
                    request("Ontology(<urn:test:reasoning>)", ReasoningRequest.Task.consistency())));
            waitForPid(pidFile);
            var result = (ReasoningResult) future.get(8, TimeUnit.SECONDS);
            executor.shutdownNow();
            assertEquals(ReasoningStatus.TIMEOUT, result.status(), () -> result.diagnostics().toString());
            long pid = Long.parseLong(Files.readString(pidFile).trim());
            assertTrue(waitForExit(pid), "timed-out child process is still alive: " + pid);
        } finally {
            Files.deleteIfExists(script);
            Files.deleteIfExists(pidFile);
        }
    }

    @Test
    void actualHeapExhaustionReturnsResourceStatusAndReleasesSlot() throws Exception {
        Path script = Files.createTempFile("mateclaw-reasoning-oom-", ".sh");
        String javaPath = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String cp = System.getProperty("java.class.path");
        Files.writeString(script, "#!/bin/sh\nexec '" + javaPath.replace("'", "'\"'\"'")
                + "' -Xmx16m -XX:+ExitOnOutOfMemoryError -cp '" + cp.replace("'", "'\"'\"'")
                + "' '" + HeapExhaustionChild.class.getName() + "'\n");
        Files.setPosixFilePermissions(script, EnumSet.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
        try {
            HermitReasoningWorker worker = new HermitReasoningWorker(java.time.Duration.ofSeconds(10),
                    512, 1, script.toString(), cp);
            for (int attempt = 0; attempt < 2; attempt++) {
                var result = worker.reason(request("Ontology(<urn:test:reasoning>)", ReasoningRequest.Task.consistency()));
                assertEquals(ReasoningStatus.RESOURCE_EXHAUSTED, result.status(), () -> result.diagnostics().toString());
                assertTrue(result.diagnostics().toString().contains("OutOfMemoryError"));
            }
            Files.writeString(script, "#!/bin/sh\nexit 3\n");
            assertEquals(ReasoningStatus.FAILED, worker.reason(
                    request("Ontology(<urn:test:reasoning>)", ReasoningRequest.Task.consistency())).status(),
                    "exit code 3 without JVM OOM evidence must remain an ordinary failure");
        } finally {
            Files.deleteIfExists(script);
        }
    }

    public static class HeapExhaustionChild {
        public static void main(String[] args) {
            byte[] allocation = new byte[64 * 1024 * 1024];
            System.out.println(allocation.length);
        }
    }

    @Test
    void secondConcurrentRequestGetsAnExplicitResourceStatus() throws Exception {
        Path script = Files.createTempFile("mateclaw-reasoning-child-", ".sh");
        Path pidFile = Files.createTempFile("mateclaw-reasoning-pid-", ".txt");
        Files.writeString(script, "#!/bin/sh\necho $$ > '" + pidFile + "'\nwhile :; do :; done\n",
                StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(script, EnumSet.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
        var executor = Executors.newSingleThreadExecutor();
        try {
            HermitReasoningWorker worker = new HermitReasoningWorker(java.time.Duration.ofSeconds(2),
                    512, 1, script.toString(), "unused-classpath");
            Future<?> first = executor.submit(() -> worker.reason(
                    request("Ontology(<urn:test:reasoning>)", ReasoningRequest.Task.consistency())));
            waitForPid(pidFile);
            var second = worker.reason(request("Ontology(<urn:test:reasoning>)", ReasoningRequest.Task.consistency()));
            assertEquals(ReasoningStatus.RESOURCE_EXHAUSTED, second.status());
            assertEquals(ReasoningStatus.TIMEOUT, ((ReasoningResult) first.get(4, TimeUnit.SECONDS)).status());
        } finally {
            executor.shutdownNow();
            Files.deleteIfExists(script);
            Files.deleteIfExists(pidFile);
        }
    }

    @Test
    void runsThePinnedSem01ToSem08ReasoningDistinctions() {
        assertStatus("open-world", AssertionScope.TBOX_ONLY, ReasoningRequest.Task.consistency(),
                ReasoningStatus.CONSISTENT);
        var openWorld = new HermitReasoningWorker().reason(new ReasoningRequest(
                ReasoningRequest.SCHEMA_VERSION, "sem-open-world-query", ontology(fixture("open-world")), List.of(),
                AssertionScope.ONTOLOGY_ABOX, List.of(), List.of(), Optional.empty(),
                ReasoningRequest.Task.entailment("ClassAssertion(<urn:test:B> <urn:test:a>)"),
                ReasoningRequest.Engine.hermit()));
        assertEquals(ReasoningStatus.NOT_ENTAILED, openWorld.status(),
                () -> openWorld.diagnostics().toString());
        assertStatus("functional-identity", AssertionScope.ONTOLOGY_ABOX, ReasoningRequest.Task.consistency(),
                ReasoningStatus.CONSISTENT);
        assertStatus("functional-distinct", AssertionScope.ONTOLOGY_ABOX, ReasoningRequest.Task.consistency(),
                ReasoningStatus.INCONSISTENT);
        assertStatus("empty-class", AssertionScope.ONTOLOGY_ABOX, ReasoningRequest.Task.consistency(),
                ReasoningStatus.CONSISTENT);
        assertStatus("data-conflict", AssertionScope.ONTOLOGY_ABOX, ReasoningRequest.Task.consistency(),
                ReasoningStatus.INCONSISTENT);
        assertStatus("equal-data-values", AssertionScope.ONTOLOGY_ABOX, ReasoningRequest.Task.consistency(),
                ReasoningStatus.CONSISTENT);
        assertStatus("negative-assertion", AssertionScope.ONTOLOGY_ABOX, ReasoningRequest.Task.consistency(),
                ReasoningStatus.INCONSISTENT);
        assertStatus("non-simple-cardinality", AssertionScope.TBOX_ONLY, ReasoningRequest.Task.consistency(),
                ReasoningStatus.PROFILE_VIOLATION);
        assertStatus("invalid-literal", AssertionScope.ONTOLOGY_ABOX, ReasoningRequest.Task.consistency(),
                ReasoningStatus.PARSE_ERROR);

        String domainChain = fixture("domain-chain");
        var domain = new HermitReasoningWorker().reason(new ReasoningRequest(
                ReasoningRequest.SCHEMA_VERSION, "sem-domain", ontology(domainChain), List.of(),
                AssertionScope.ONTOLOGY_ABOX, List.of(), List.of(), Optional.empty(),
                ReasoningRequest.Task.entailment("ClassAssertion(<urn:test:A> <urn:test:a>)"),
                ReasoningRequest.Engine.hermit()));
        assertEquals(ReasoningStatus.ENTAILED, domain.status(), () -> domain.diagnostics().toString());
        var chain = new HermitReasoningWorker().reason(new ReasoningRequest(
                ReasoningRequest.SCHEMA_VERSION, "sem-chain", ontology(domainChain), List.of(),
                AssertionScope.ONTOLOGY_ABOX, List.of(), List.of(), Optional.empty(),
                ReasoningRequest.Task.entailment(
                        "ObjectPropertyAssertion(<urn:test:r> <urn:test:a> <urn:test:c>)"),
                ReasoningRequest.Engine.hermit()));
        assertEquals(ReasoningStatus.ENTAILED, chain.status(), () -> chain.diagnostics().toString());
    }

    @Test
    void handlesExistentialUniversalCardinalityAndSameAsSemantics() {
        String document = "Ontology(<urn:test:expressions> "
                + "Declaration(Class(<urn:test:A>)) Declaration(Class(<urn:test:B>)) "
                + "Declaration(ObjectProperty(<urn:test:p>)) "
                + "SubClassOf(<urn:test:A> ObjectSomeValuesFrom(<urn:test:p> <urn:test:B>)) "
                + "SubClassOf(<urn:test:A> ObjectAllValuesFrom(<urn:test:p> <urn:test:B>)) "
                + "FunctionalObjectProperty(<urn:test:p>) "
                + "ClassAssertion(<urn:test:A> <urn:test:i>) "
                + "ObjectPropertyAssertion(<urn:test:p> <urn:test:i> <urn:test:j>) "
                + "SameIndividual(<urn:test:j> <urn:test:k>))";

        var result = new HermitReasoningWorker().reason(new ReasoningRequest(
                ReasoningRequest.SCHEMA_VERSION, "expression-entailment", ontology(document), List.of(),
                AssertionScope.ONTOLOGY_ABOX, List.of(), List.of(), Optional.empty(),
                ReasoningRequest.Task.entailment(
                        "ClassAssertion(<urn:test:B> <urn:test:k>)"), ReasoningRequest.Engine.hermit()));

        assertEquals(ReasoningStatus.ENTAILED, result.status(), () -> result.diagnostics().toString());
    }

    @Test
    void expandsSpringBootStyleNestedJarBeforeLaunchingTheChild() throws Exception {
        Path bootJar = Files.createTempFile("mateclaw-semantic-boot-", ".jar");
        Set<Path> before = workerTempDirectories();
        try {
            createBootStyleJar(bootJar);
            HermitReasoningWorker worker = new HermitReasoningWorker(java.time.Duration.ofSeconds(30),
                    512, 1, Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    bootJar.toString());
            var result = worker.reason(request("Ontology(<urn:test:reasoning> "
                    + "Declaration(Class(<urn:test:C>)) Declaration(Class(<urn:test:D>)) "
                    + "SubClassOf(<urn:test:C> <urn:test:D>))", ReasoningRequest.Task.classification()));
            assertEquals(ReasoningStatus.CONSISTENT, result.status(), () -> result.diagnostics().toString());
            assertTrue(result.classRelations().stream().anyMatch(value ->
                    value.classIri().equals("urn:test:C") && value.superClassIris().contains("urn:test:D")));
            assertEquals(before, workerTempDirectories(), "child runtime/request workspace leaked after completion");
        } finally {
            Files.deleteIfExists(bootJar);
        }
    }

    private static ReasoningRequest request(String text, ReasoningRequest.Task task) {
        return new ReasoningRequest(ReasoningRequest.SCHEMA_VERSION, "request-1", ontology(text), List.of(),
                AssertionScope.TBOX_ONLY, List.of(), List.of(), Optional.empty(), task,
                ReasoningRequest.Engine.hermit());
    }

    private static OntologyDocument ontology(String text) {
        return OntologyDocument.fromText("test", "revision", "urn:test:reasoning", Optional.empty(),
                OntologyDocumentSyntax.FUNCTIONAL, text, LockedImport.digest(List.of()));
    }

    private static void assertStatus(String fixture, AssertionScope scope, ReasoningRequest.Task task,
            ReasoningStatus expected) {
        var result = new HermitReasoningWorker().reason(new ReasoningRequest(
                ReasoningRequest.SCHEMA_VERSION, "sem-" + fixture, ontology(fixture(fixture)), List.of(),
                scope, List.of(), List.of(), Optional.empty(), task, ReasoningRequest.Engine.hermit()));
        assertEquals(expected, result.status(), fixture + ": " + result.diagnostics());
    }

    private static void assertTopBottomInconsistent(String document, AssertionScope scope) {
        var result = new HermitReasoningWorker().reason(new ReasoningRequest(
                ReasoningRequest.SCHEMA_VERSION, "top-bottom-" + scope.name(), ontology(document), List.of(),
                scope, List.of(), List.of(), Optional.empty(), ReasoningRequest.Task.consistency(),
                ReasoningRequest.Engine.hermit()));

        assertEquals(ReasoningStatus.INCONSISTENT, result.status(), () -> result.diagnostics().toString());
        assertTrue(result.diagnostics().stream().anyMatch(value -> value.contains("top-bottom")),
                () -> result.diagnostics().toString());
    }

    private static String fixture(String name) {
        try {
            return Files.readString(Path.of("..", "docs", "validation", "semantic-owl-01", "fixtures",
                    name + ".ofn"), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new AssertionError("unable to read fixture " + name, exception);
        }
    }

    private static void waitForPid(Path pidFile) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline && Files.size(pidFile) == 0) {
            Thread.sleep(10);
        }
        assertTrue(Files.size(pidFile) > 0, "child did not write its pid");
    }

    private static boolean waitForExit(long pid) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            if (ProcessHandle.of(pid).map(handle -> !handle.isAlive()).orElse(true)) {
                return true;
            }
            Thread.sleep(20);
        }
        return ProcessHandle.of(pid).map(handle -> !handle.isAlive()).orElse(true);
    }

    private static Set<Path> workerTempDirectories() throws Exception {
        try (Stream<Path> paths = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return paths.filter(path -> path.getFileName().toString().startsWith("mateclaw-reasoning-"))
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
    }

    private static void createBootStyleJar(Path destination) throws Exception {
        Path moduleClasses = Path.of("target", "classes");
        Path coreClasses = Path.of("..", "mateclaw-semantic-core", "target", "classes");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(destination))) {
            addDirectory(output, moduleClasses, "BOOT-INF/classes/");
            addDirectory(output, coreClasses, "BOOT-INF/classes/");
            Set<String> names = new java.util.HashSet<>();
            for (String entry : System.getProperty("java.class.path")
                    .split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
                Path path = Path.of(entry);
                if (!Files.isRegularFile(path) || !entry.endsWith(".jar")) {
                    continue;
                }
                String name = path.getFileName().toString();
                if (!names.add(name)) {
                    continue;
                }
                output.putNextEntry(new JarEntry("BOOT-INF/lib/" + name));
                Files.copy(path, output);
                output.closeEntry();
            }
        }
    }

    private static void addDirectory(JarOutputStream output, Path root, String prefix) throws Exception {
        if (!Files.isDirectory(root)) {
            throw new AssertionError("compiled classes directory missing: " + root.toAbsolutePath());
        }
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(Files::isRegularFile).forEach(path -> {
                try {
                    String relative = root.relativize(path).toString().replace(java.io.File.separatorChar, '/');
                    output.putNextEntry(new JarEntry(prefix + relative));
                    Files.copy(path, output);
                    output.closeEntry();
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            });
        }
    }
}

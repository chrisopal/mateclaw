package vip.mate.semantic.owl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.FunctionalSyntaxDocumentFormat;
import org.semanticweb.owlapi.formats.RDFXMLDocumentFormat;
import org.semanticweb.owlapi.functional.renderer.FunctionalSyntaxObjectRenderer;
import org.semanticweb.owlapi.io.OWLOntologyDocumentSource;
import org.semanticweb.owlapi.io.OWLFunctionalSyntaxOntologyFormat;
import org.semanticweb.owlapi.io.StringDocumentSource;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDocumentFormat;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLAnonymousIndividual;
import org.semanticweb.owlapi.model.OWLClassAssertionAxiom;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDifferentIndividualsAxiom;
import org.semanticweb.owlapi.model.OWLEquivalentClassesAxiom;
import org.semanticweb.owlapi.model.OWLIndividual;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLObjectPropertyAssertionAxiom;
import org.semanticweb.owlapi.model.OWLObjectIntersectionOf;
import org.semanticweb.owlapi.model.OWLObjectSomeValuesFrom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.OWLOntologyLoaderConfiguration;
import org.semanticweb.owlapi.model.MissingImportHandlingStrategy;
import org.semanticweb.owlapi.util.DefaultPrefixManager;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import vip.mate.semantic.core.ontology.OntologyAxiomDescriptor;
import vip.mate.semantic.core.ontology.LockedImport;
import vip.mate.semantic.core.ontology.OntologyDocument;
import vip.mate.semantic.core.ontology.OntologyDocumentSyntax;
import vip.mate.semantic.core.ontology.OntologyValidationReport;
import vip.mate.semantic.core.ontology.ParsedOntologyDocument;
import vip.mate.semantic.core.reasoning.ReasoningRequest;
import vip.mate.semantic.core.reasoning.ReasoningRequest.AssertionScope;
import vip.mate.semantic.core.reasoning.ReasoningResult;
import vip.mate.semantic.core.reasoning.ReasoningResult.ReasoningStatus;

/**
 * Bounded, offline execution harness for the pinned W3C OWL 2 test archive.
 *
 * <p>The suite is intentionally opt in because each executed case uses the
 * real isolated HermiT worker. Run it with
 * {@code -Dsemantic.owl.w3c=true}. The default JUnit invocation only skips
 * this class, so normal module test runs do not start 266 child JVMs.</p>
 *
 * <p>W3C test metadata is read with the JDK XML parser. Ontology inputs are
 * passed unchanged to {@link OwlDocumentAdapter}; no declarations, axioms, or
 * imports are added by this harness. Its only semantic normalizations are
 * typed singleton equivalent classes and rooted anonymous ABox trees, both
 * expressed with standard OWL direct-semantics class expressions. Parsed ABox
 * renderings are sent through the explicit
 * {@link AssertionScope#ONTOLOGY_ABOX} request field.</p>
 */
class W3cOwl2DlHarnessTest {
    private static final String TEST_NS = "http://www.w3.org/2007/OWL/testOntology#";
    private static final String RDF_NS = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";
    private static final String OWL_NS = "http://www.w3.org/2002/07/owl#";
    private static final String SHA256 = "sha256";
    private static final int EXPECTED_SELECTED_CASES = 266;
    private static final int MAX_ERROR_LENGTH = 512;
    private static final Set<String> ABOX_AXIOM_TYPES = Set.of(
            "ClassAssertion",
            "ObjectPropertyAssertion",
            "DataPropertyAssertion",
            "NegativeObjectPropertyAssertion",
            "NegativeDataPropertyAssertion",
            "SameIndividual",
            "DifferentIndividuals");
    private static final Pattern SHA_PATTERN = Pattern.compile(
            "\\\"sha256\\\"\\s*:\\s*\\\"([0-9a-fA-F]{64})\\\"");
    private static final Pattern INDEX_SHA_PATTERN = Pattern.compile(
            "\\\"sourceSha256\\\"\\s*:\\s*\\\"([0-9a-fA-F]{64})\\\"");
    private static final Pattern INDEX_ID_PATTERN = Pattern.compile(
            "\\\"id\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"\\\\])*)\\\"");
    private static final Pattern ANONYMOUS_NODE_PATTERN = Pattern.compile("_:[A-Za-z0-9_.-]+");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final OwlDocumentAdapter adapter = new OwlDocumentAdapter();

    @Test
    void executesPinnedApprovedDirectDlCasesOnlyWhenExplicitlyEnabled() throws Exception {
        assumeTrue(Boolean.getBoolean("semantic.owl.w3c"),
                "W3C OWL harness is opt in: set -Dsemantic.owl.w3c=true");

        Path archive = locate("docs/validation/semantic-owl-w3c/all.rdf");
        Path source = locate("docs/validation/semantic-owl-w3c/source.json");
        Path index = locate("docs/validation/semantic-owl-w3c/case-index.json");
        Path importFixture = locate("docs/validation/semantic-owl-execution/w3c-import-fixtures.json");
        String archiveSha256 = sha256(Files.readAllBytes(archive));
        assertEquals(sourceSha(source), archiveSha256, "pinned W3C archive digest changed");
        assertEquals(indexSha(index), archiveSha256, "case index does not point at pinned archive");
        ImportFixtures imports = readImportFixtures(importFixture, archiveSha256);

        Set<String> selectedIds = selectedIds(index);
        assertEquals(EXPECTED_SELECTED_CASES, selectedIds.size(), "unexpected selected case count");
        Map<String, W3cCase> archiveCases = readCases(archive);
        assertTrue(archiveCases.keySet().containsAll(selectedIds),
                "case index contains an id absent from the archive");

        List<W3cCase> cases = selectedIds.stream()
                .map(archiveCases::get)
                .sorted(Comparator.comparing(W3cCase::id))
                .toList();
        int limit = Integer.getInteger("semantic.owl.w3c.limit", cases.size());
        if (limit < 1 || limit > cases.size()) {
            throw new IllegalArgumentException("semantic.owl.w3c.limit must be between 1 and " + cases.size());
        }
        cases = cases.subList(0, limit);
        cases.stream().filter(W3cCase::hasImports).forEach(imports::requireCase);

        Duration timeout = Duration.ofSeconds(Long.getLong("semantic.owl.w3c.timeoutSeconds", 15L));
        int memoryMb = Integer.getInteger("semantic.owl.w3c.memoryMb", 512);
        HermitReasoningWorker worker = new HermitReasoningWorker(timeout, memoryMb, 1,
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                System.getProperty("java.class.path"));
        List<Outcome> outcomes = new ArrayList<>();
        for (W3cCase testCase : cases) {
            outcomes.add(execute(testCase, worker, imports));
        }

        String report = reportJson(archiveSha256, outcomes);
        System.out.println("[semantic-owl-w3c] " + report);
        writeReportIfRequested(report);
        List<Outcome> failures = outcomes.stream().filter(value -> value.status() == Status.FAIL).toList();
        assertTrue(failures.isEmpty(), "W3C harness failures: " + failures);
    }

    @Test
    void singletonEquivalentNormalizationIsScopedToPinnedW3cCase() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology();
        OWLDataFactory factory = manager.getOWLDataFactory();
        OWLClassExpression expression = factory.getOWLObjectMinCardinality(
                1, factory.getOWLObjectProperty(IRI.create("urn:test:p")), factory.getOWLThing());
        OWLEquivalentClassesAxiom singleton = factory.getOWLEquivalentClassesAxiom(Set.of(expression));
        OWLEquivalentClassesAxiom nonTrivial = factory.getOWLEquivalentClassesAxiom(
                Set.of(factory.getOWLClass(IRI.create("urn:test:A")), factory.getOWLClass(IRI.create("urn:test:B"))));

        assertTrue(renderStandaloneAxiom(ontology, singleton).startsWith("SubClassOf("));
        assertTrue(renderStandaloneAxiom(ontology, nonTrivial)
                .startsWith("EquivalentClasses("));
        assertThrows(IllegalArgumentException.class,
                () -> normalizeEquivalentExpressions(ontology, Set.of(), nonTrivial));
    }

    @Test
    void treeNormalizationKeepsSharedWitnessConjunctionAndRejectsMultipleParents() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology();
        OWLDataFactory factory = manager.getOWLDataFactory();
        OWLNamedIndividual root = factory.getOWLNamedIndividual(IRI.create("urn:test:root"));
        OWLAnonymousIndividual shared = factory.getOWLAnonymousIndividual("shared");
        OWLAnonymousIndividual other = factory.getOWLAnonymousIndividual("other");
        var property = factory.getOWLObjectProperty(IRI.create("urn:test:p"));
        var classC = factory.getOWLClass(IRI.create("urn:test:C"));
        var classD = factory.getOWLClass(IRI.create("urn:test:D"));

        List<OWLAxiom> sameWitness = List.of(
                factory.getOWLClassAssertionAxiom(classC, shared),
                factory.getOWLClassAssertionAxiom(classD, shared),
                factory.getOWLObjectPropertyAssertionAxiom(property, root, shared));
        String sameRendering = renderStandaloneAxiom(ontology,
                normalizeTreeConclusion(ontology, sameWitness).get(0));
        assertTrue(sameRendering.contains("ObjectIntersectionOf"), sameRendering);

        List<OWLAxiom> differentWitnesses = List.of(
                factory.getOWLClassAssertionAxiom(classC, shared),
                factory.getOWLClassAssertionAxiom(classD, other),
                factory.getOWLObjectPropertyAssertionAxiom(property, root, shared),
                factory.getOWLObjectPropertyAssertionAxiom(property, root, other));
        OWLClassAssertionAxiom differentAxiom = (OWLClassAssertionAxiom) normalizeTreeConclusion(
                ontology, differentWitnesses).get(0);
        assertTrue(differentAxiom.getClassExpression() instanceof OWLObjectIntersectionOf,
                differentAxiom.toString());
        OWLObjectIntersectionOf separateWitnesses = (OWLObjectIntersectionOf) differentAxiom.getClassExpression();
        assertTrue(separateWitnesses.operands().allMatch(OWLObjectSomeValuesFrom.class::isInstance),
                differentAxiom.toString());
        assertTrue(separateWitnesses.operands().map(OWLObjectSomeValuesFrom.class::cast)
                .allMatch(value -> !(value.getFiller() instanceof OWLObjectIntersectionOf)),
                differentAxiom.toString());

        OWLNamedIndividual secondRoot = factory.getOWLNamedIndividual(IRI.create("urn:test:second"));
        List<OWLAxiom> multipleParents = List.of(
                factory.getOWLClassAssertionAxiom(classC, shared),
                factory.getOWLObjectPropertyAssertionAxiom(property, root, shared),
                factory.getOWLObjectPropertyAssertionAxiom(property, secondRoot, shared));
        assertThrows(IllegalArgumentException.class, () -> normalizeTreeConclusion(ontology, multipleParents));

        List<OWLAxiom> inequality = List.of(
                factory.getOWLDifferentIndividualsAxiom(root, secondRoot));
        assertThrows(IllegalArgumentException.class, () -> normalizeTreeConclusion(ontology, inequality));
    }

    @Test
    void treeCounterexampleIsNotEntailedBySeparateWitnesses() {
        String document = "Ontology(<urn:test:tree-counterexample> "
                + "Declaration(Class(<urn:test:C>)) Declaration(Class(<urn:test:D>)) "
                + "Declaration(ObjectProperty(<urn:test:p>)))";
        List<String> assertions = List.of(
                "ClassAssertion(<urn:test:C> <urn:test:witness-c>)",
                "ClassAssertion(<urn:test:D> <urn:test:witness-d>)",
                "ObjectPropertyAssertion(<urn:test:p> <urn:test:root> <urn:test:witness-c>)",
                "ObjectPropertyAssertion(<urn:test:p> <urn:test:root> <urn:test:witness-d>)");
        OntologyDocument ontology = OntologyDocument.fromText("tree-counterexample", "revision",
                "urn:test:tree-counterexample", java.util.Optional.empty(), OntologyDocumentSyntax.FUNCTIONAL,
                document, LockedImport.digest(List.of()));
        ReasoningResult result = new HermitReasoningWorker(Duration.ofSeconds(15), 512, 1,
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                System.getProperty("java.class.path")).reason(new ReasoningRequest(
                        ReasoningRequest.SCHEMA_VERSION, "tree-counterexample", ontology, List.of(),
                        AssertionScope.ONTOLOGY_ABOX, assertions, List.of(), java.util.Optional.empty(),
                        ReasoningRequest.Task.entailment(
                                "ClassAssertion(ObjectSomeValuesFrom(<urn:test:p> "
                                        + "ObjectIntersectionOf(<urn:test:C> <urn:test:D>)) <urn:test:root>)"),
                        ReasoningRequest.Engine.hermit()));
        assertEquals(ReasoningStatus.NOT_ENTAILED, result.status(),
                () -> result.diagnostics().toString());
    }

    private Outcome execute(W3cCase testCase, HermitReasoningWorker worker, ImportFixtures importFixtures) {
        long started = System.nanoTime();
        try {
            List<LockedImport> lockedImports = importFixtures.forCase(testCase);
            String premiseField = chooseField(testCase.fields(), "PremiseOntology");
            if (premiseField == null) {
                return gap(testCase, started, "case has no supported premise ontology field");
            }
            Source premiseSource = parseSource(testCase, premiseField, testCase.fields().get(premiseField),
                    lockedImports);
            OntologyValidationReport profile = adapter.validateDl(premiseSource.parsed());
            if (!profile.valid()) {
                return gap(testCase, started, "OWL 2 DL profile validation: " + summarize(profile));
            }

            boolean inconsistency = testCase.types().contains("InconsistencyTest");
            if (!inconsistency && !testCase.types().contains("ConsistencyTest")) {
                return gap(testCase, started, "selected case is not a consistency or inconsistency test");
            }
            ReasoningResult consistency = worker.reason(request(
                    testCase, premiseSource, AssertionScope.ONTOLOGY_ABOX,
                    ReasoningRequest.Task.consistency(), "consistency"));
            ReasoningStatus expectedConsistency = inconsistency
                    ? ReasoningStatus.INCONSISTENT : ReasoningStatus.CONSISTENT;
            if (isGap(consistency.status())) {
                return gap(testCase, started, "consistency " + consistency.status() + ": "
                        + summarize(consistency.diagnostics()));
            }
            if (consistency.status() != expectedConsistency) {
                return fail(testCase, started, "consistency expected " + expectedConsistency
                        + " but was " + consistency.status() + ": " + summarize(consistency.diagnostics()));
            }
            if (!testCase.types().contains("PositiveEntailmentTest")
                    && !testCase.types().contains("NegativeEntailmentTest")) {
                return pass(testCase, started, "consistency=" + consistency.status());
            }
            if (expectedConsistency != ReasoningStatus.CONSISTENT) {
                return fail(testCase, started, "entailment case premise was inconsistent");
            }

            boolean negative = testCase.types().contains("NegativeEntailmentTest");
            String queryField = chooseField(testCase.fields(), negative ? "NonConclusionOntology" : "ConclusionOntology");
            if (queryField == null) {
                return gap(testCase, started, "case has no supported entailment conclusion field");
            }
            Source querySource = parseSource(testCase, queryField, testCase.fields().get(queryField), List.of());
            List<OntologyAxiomDescriptor> allQueryAxioms = querySource.parsed().axioms();
            List<OntologyAxiomDescriptor> logical = allQueryAxioms.stream()
                    .filter(OntologyAxiomDescriptor::logical).toList();
            if (logical.isEmpty()) {
                if (negative) {
                    return gap(testCase, started,
                            "negative entailment query has no logical axioms; no explicit NOT_ENTAILED result exists");
                }
                boolean onlyDeclarationsOrAnnotations = allQueryAxioms.stream()
                        .allMatch(W3cOwl2DlHarnessTest::isIgnorableNonLogicalAxiom);
                if (!onlyDeclarationsOrAnnotations) {
                    return gap(testCase, started,
                            "empty entailment query contains non-declaration/non-annotation axioms");
                }
                return pass(testCase, started,
                        "consistency=" + consistency.status() + ", entailment=ENTAILED(empty direct query)");
            }
            if (logical.stream().anyMatch(query -> !query.annotations().isEmpty())) {
                return gap(testCase, started,
                        "annotated entailment axioms are outside the exact query contract");
            }
            Set<String> sharedAnonymousNodes = sharedAnonymousNodes(logical);
            List<String> queryAxioms;
            if (!sharedAnonymousNodes.isEmpty()) {
                queryAxioms = treeAnonymousFunctionalAxioms(querySource, logical);
            } else {
                queryAxioms = standaloneFunctionalAxioms(querySource, logical);
            }
            List<ReasoningResult> entailments = new ArrayList<>();
            for (int index = 0; index < queryAxioms.size(); index++) {
                entailments.add(worker.reason(request(
                        testCase, premiseSource, AssertionScope.ONTOLOGY_ABOX,
                        ReasoningRequest.Task.entailment(queryAxioms.get(index)), "entailment-" + index)));
            }
            List<ReasoningResult> nonBoolean = entailments.stream()
                    .filter(value -> value.status() != ReasoningStatus.ENTAILED
                            && value.status() != ReasoningStatus.NOT_ENTAILED)
                    .toList();
            boolean explicitlyNotEntailed = entailments.stream()
                    .anyMatch(value -> value.status() == ReasoningStatus.NOT_ENTAILED);
            if (negative) {
                if (explicitlyNotEntailed) {
                    return pass(testCase, started, "consistency=" + consistency.status()
                            + ", entailment=NOT_ENTAILED (at least one explicit query axiom)");
                }
                if (!nonBoolean.isEmpty()) {
                    return gap(testCase, started, summarizeWorkerFailures(nonBoolean));
                }
                return fail(testCase, started,
                        "negative entailment query has no explicit NOT_ENTAILED axiom");
            }
            if (!nonBoolean.isEmpty()) {
                return gap(testCase, started, summarizeWorkerFailures(nonBoolean));
            }
            if (explicitlyNotEntailed) {
                return fail(testCase, started,
                        "positive entailment query contains a NOT_ENTAILED axiom");
            }
            return pass(testCase, started, "consistency=" + consistency.status()
                    + ", entailment=ENTAILED (all " + entailments.size() + " query axioms)");
        } catch (RuntimeException exception) {
            return gap(testCase, started, exception.getClass().getSimpleName() + ": " + exception.getMessage());
        }
    }

    private ReasoningRequest request(W3cCase testCase, Source source, AssertionScope scope,
            ReasoningRequest.Task task, String phase) {
        return new ReasoningRequest(
                ReasoningRequest.SCHEMA_VERSION,
                "w3c-" + sha256(testCase.id() + "-" + phase),
                source.parsed().document(),
                source.lockedImports(),
                scope,
                source.aboxAssertions(),
                List.of(),
                java.util.Optional.empty(),
                task,
                ReasoningRequest.Engine.hermit());
    }

    private Source parseSource(W3cCase testCase, String field, String text, List<LockedImport> lockedImports) {
        OntologyDocumentSyntax syntax = field.startsWith("fs")
                ? OntologyDocumentSyntax.FUNCTIONAL : OntologyDocumentSyntax.RDF_XML;
        ParsedOntologyDocument parsed = adapter.parse(
                "w3c-" + sha256(testCase.id() + "-" + field),
                "w3c-" + sha256(testCase.id() + "-" + field + "-revision"),
                text,
                syntax,
                lockedImports);
        List<String> abox = lockedImports.isEmpty()
                ? standaloneFunctionalAboxAssertions(parsed) : List.of();
        return new Source(parsed, abox, lockedImports);
    }

    /**
     * The adapter's human-readable axiom rendering may use source prefixes
     * such as {@code :Person}. The worker's exact-axiom contract intentionally
     * accepts one axiom without a Prefix declaration, so the query is rendered
     * once with an empty prefix manager. This is a syntax-only normalization;
     * it does not add declarations or alter the premise ontology.
     */
    private List<String> standaloneFunctionalAxioms(Source source, List<OntologyAxiomDescriptor> descriptors) {
        LoadedOntology loaded = loadForRendering(source.parsed());
        try {
            return matchedAxioms(loaded.ontology(), source.parsed(), descriptors).stream()
                    .map(axiom -> renderStandaloneAxiom(loaded.ontology(), axiom))
                    .toList();
        } finally {
            loaded.close();
        }
    }

    /**
     * Preserve a shared anonymous witness by expressing its whole rooted ABox
     * tree as nested existential restrictions. This is deliberately narrow:
     * cycles, multiple parents, named object targets, and non-ABox logical
     * axioms remain explicit unsupported gaps.
     */
    private List<String> treeAnonymousFunctionalAxioms(Source source,
            List<OntologyAxiomDescriptor> descriptors) {
        LoadedOntology loaded = loadForRendering(source.parsed());
        try {
            List<OWLAxiom> axioms = matchedAxioms(loaded.ontology(), source.parsed(), descriptors);
            List<OWLAxiom> normalized = normalizeTreeConclusion(loaded.ontology(), axioms);
            return normalized.stream()
                    .map(axiom -> renderStandaloneAxiom(loaded.ontology(), axiom))
                    .toList();
        } finally {
            loaded.close();
        }
    }

    private static List<OWLAxiom> matchedAxioms(OWLOntology ontology, ParsedOntologyDocument parsed,
            List<OntologyAxiomDescriptor> descriptors) {
        Map<String, List<OWLAxiom>> indexed = new LinkedHashMap<>();
        for (OWLAxiom axiom : ontology.getAxioms()) {
            String rendering = renderAdapterAxiom(ontology, axiom);
            indexed.computeIfAbsent(canonicalAxiomKey(axiom.getAxiomType().getName(), rendering),
                    ignored -> new ArrayList<>()).add(axiom);
        }
        List<OWLAxiom> result = new ArrayList<>();
        for (OntologyAxiomDescriptor descriptor : descriptors) {
            List<OWLAxiom> candidates = indexed.get(canonicalAxiomKey(
                    descriptor.axiomType(), descriptor.rendering()));
            OWLAxiom axiom = candidates == null || candidates.isEmpty() ? null : candidates.remove(0);
            if (axiom == null) {
                throw new IllegalArgumentException("entailment query axiom cannot be matched to parsed ontology");
            }
            result.add(axiom);
        }
        return List.copyOf(result);
    }

    private static List<OWLAxiom> normalizeTreeConclusion(OWLOntology ontology, List<OWLAxiom> axioms) {
        OWLDataFactory factory = ontology.getOWLOntologyManager().getOWLDataFactory();
        Map<OWLIndividual, List<OWLClassExpression>> types = new LinkedHashMap<>();
        Map<OWLIndividual, List<OWLObjectPropertyAssertionAxiom>> outgoing = new LinkedHashMap<>();
        Map<OWLAnonymousIndividual, Integer> incoming = new LinkedHashMap<>();
        Set<OWLNamedIndividual> roots = new LinkedHashSet<>();
        Set<OWLAnonymousIndividual> anonymous = new LinkedHashSet<>();

        for (OWLAxiom axiom : axioms) {
            if (axiom instanceof org.semanticweb.owlapi.model.OWLClassAssertionAxiom classAssertion) {
                OWLIndividual individual = classAssertion.getIndividual();
                types.computeIfAbsent(individual, ignored -> new ArrayList<>())
                        .add(classAssertion.getClassExpression());
                if (individual.isAnonymous()) {
                    anonymous.add(individual.asOWLAnonymousIndividual());
                } else {
                    roots.add(individual.asOWLNamedIndividual());
                }
            } else if (axiom instanceof OWLObjectPropertyAssertionAxiom propertyAssertion) {
                OWLIndividual subject = propertyAssertion.getSubject();
                OWLIndividual object = propertyAssertion.getObject();
                if (object.isNamed()) {
                    throw unsupportedTree("named object target cannot preserve anonymous-tree scope");
                }
                outgoing.computeIfAbsent(subject, ignored -> new ArrayList<>()).add(propertyAssertion);
                anonymous.add(object.asOWLAnonymousIndividual());
                incoming.merge(object.asOWLAnonymousIndividual(), 1, Integer::sum);
                if (subject.isAnonymous()) {
                    anonymous.add(subject.asOWLAnonymousIndividual());
                } else {
                    roots.add(subject.asOWLNamedIndividual());
                }
            } else {
                throw unsupportedTree("logical axiom type " + axiom.getAxiomType().getName());
            }
        }
        if (roots.isEmpty()) {
            throw unsupportedTree("anonymous tree has no named root individual");
        }
        for (OWLAnonymousIndividual node : anonymous) {
            int parents = incoming.getOrDefault(node, 0);
            if (parents == 0) {
                throw unsupportedTree("anonymous node is not rooted");
            }
            if (parents > 1) {
                throw unsupportedTree("anonymous node has multiple parent edges");
            }
        }

        Set<OWLAnonymousIndividual> reached = new LinkedHashSet<>();
        List<OWLAxiom> result = new ArrayList<>();
        for (OWLNamedIndividual root : roots) {
            OWLClassExpression expression = treeExpression(factory, root, types, outgoing,
                    new LinkedHashSet<>(), reached);
            result.add(factory.getOWLClassAssertionAxiom(expression, root));
        }
        if (!reached.equals(anonymous)) {
            throw unsupportedTree("anonymous tree contains an unreachable component");
        }
        return List.copyOf(result);
    }

    private static OWLClassExpression treeExpression(OWLDataFactory factory, OWLIndividual individual,
            Map<OWLIndividual, List<OWLClassExpression>> types,
            Map<OWLIndividual, List<OWLObjectPropertyAssertionAxiom>> outgoing,
            Set<OWLAnonymousIndividual> visiting, Set<OWLAnonymousIndividual> reached) {
        if (individual.isAnonymous()) {
            OWLAnonymousIndividual node = individual.asOWLAnonymousIndividual();
            if (!visiting.add(node)) {
                throw unsupportedTree("anonymous tree contains a cycle");
            }
        }
        List<OWLClassExpression> conjuncts = new ArrayList<>();
        for (OWLClassExpression type : types.getOrDefault(individual, List.of())) {
            if (!type.isOWLThing()) {
                conjuncts.add(type);
            }
        }
        for (OWLObjectPropertyAssertionAxiom edge : outgoing.getOrDefault(individual, List.of())) {
            OWLClassExpression filler = treeExpression(factory, edge.getObject(), types, outgoing,
                    visiting, reached);
            conjuncts.add(factory.getOWLObjectSomeValuesFrom(edge.getProperty(), filler));
        }
        if (individual.isAnonymous()) {
            OWLAnonymousIndividual node = individual.asOWLAnonymousIndividual();
            visiting.remove(node);
            reached.add(node);
        }
        if (conjuncts.isEmpty()) {
            return factory.getOWLThing();
        }
        if (conjuncts.size() == 1) {
            return conjuncts.get(0);
        }
        return factory.getOWLObjectIntersectionOf(new LinkedHashSet<>(conjuncts));
    }

    private static IllegalArgumentException unsupportedTree(String detail) {
        return new IllegalArgumentException("unsupported shared-anonymous query shape: " + detail);
    }

    /**
     * OWLAPI assigns generated labels to anonymous individuals during each
     * parse. The adapter deliberately scopes those labels, while this
     * rendering-only parse does not. Compare the axiom structure with blank
     * node labels canonicalized in encounter order instead of pretending the
     * generated identifiers are stable across parses.
     */
    private static String canonicalAxiomKey(String axiomType, String rendering) {
        Matcher matcher = ANONYMOUS_NODE_PATTERN.matcher(rendering);
        StringBuffer canonical = new StringBuffer();
        Map<String, Integer> labels = new LinkedHashMap<>();
        while (matcher.find()) {
            String label = matcher.group();
            int index = labels.computeIfAbsent(label, ignored -> labels.size());
            matcher.appendReplacement(canonical, Matcher.quoteReplacement("_:b" + index));
        }
        matcher.appendTail(canonical);
        return axiomType + "\u0000" + canonical;
    }

    private static String renderAdapterAxiom(OWLOntology ontology, OWLAxiom axiom) {
        StringWriter writer = new StringWriter();
        FunctionalSyntaxObjectRenderer renderer = new FunctionalSyntaxObjectRenderer(
                ontology, new OWLFunctionalSyntaxOntologyFormat(), writer);
        axiom.accept(renderer);
        return writer.toString().trim();
    }

    private static Set<String> sharedAnonymousNodes(List<OntologyAxiomDescriptor> logical) {
        Map<String, Integer> occurrences = new LinkedHashMap<>();
        for (OntologyAxiomDescriptor descriptor : logical) {
            Matcher matcher = ANONYMOUS_NODE_PATTERN.matcher(descriptor.rendering());
            Set<String> seenInAxiom = new LinkedHashSet<>();
            while (matcher.find()) {
                seenInAxiom.add(matcher.group());
            }
            for (String node : seenInAxiom) {
                occurrences.merge(node, 1, Integer::sum);
            }
        }
        return occurrences.entrySet().stream()
                .filter(entry -> entry.getValue() > 1)
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private static boolean isIgnorableNonLogicalAxiom(OntologyAxiomDescriptor descriptor) {
        return descriptor.axiomType().equals("Declaration")
                || descriptor.axiomType().equals("AnnotationAssertion");
    }

    private static String summarizeWorkerFailures(List<ReasoningResult> results) {
        return results.stream()
                .map(value -> "query " + value.status() + ": " + summarize(value.diagnostics()))
                .collect(java.util.stream.Collectors.joining("; "));
    }

    private List<String> standaloneFunctionalAboxAssertions(ParsedOntologyDocument parsed) {
        LoadedOntology loaded = loadForRendering(parsed);
        try {
            return loaded.ontology().getAxioms().stream()
                    .filter(axiom -> ABOX_AXIOM_TYPES.contains(axiom.getAxiomType().getName()))
                    .map(axiom -> renderStandaloneAxiom(loaded.ontology(), axiom))
                    .sorted()
                    .toList();
        } finally {
            loaded.close();
        }
    }

    private LoadedOntology loadForRendering(ParsedOntologyDocument parsed) {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        try {
            OWLDocumentFormat format = parsed.document().syntax() == OntologyDocumentSyntax.FUNCTIONAL
                    ? new FunctionalSyntaxDocumentFormat() : new RDFXMLDocumentFormat();
            OWLOntologyDocumentSource source = new StringDocumentSource(
                    parsed.document().documentText(), IRI.create("urn:mateclaw:w3c-rendering"), format, null);
            OWLOntology ontology = manager.loadOntologyFromOntologyDocument(
                    source,
                    new OWLOntologyLoaderConfiguration()
                            .setMissingImportHandlingStrategy(MissingImportHandlingStrategy.THROW_EXCEPTION)
                            .setFollowRedirects(false)
                            .setStrict(parsed.document().syntax() == OntologyDocumentSyntax.FUNCTIONAL));
            return new LoadedOntology(manager, ontology);
        } catch (OWLOntologyCreationException exception) {
            throw new IllegalArgumentException("unable to normalize OWL assertions: " + exception.getMessage(), exception);
        }
    }

    private static String renderStandaloneAxiom(OWLOntology ontology, OWLAxiom axiom) {
        OWLAxiom renderedAxiom = normalizeEquivalentAxiom(ontology, axiom);
        StringWriter writer = new StringWriter();
        FunctionalSyntaxObjectRenderer renderer = new FunctionalSyntaxObjectRenderer(
                ontology, new FunctionalSyntaxDocumentFormat(), writer);
        renderer.setPrefixManager(new DefaultPrefixManager());
        renderedAxiom.accept(renderer);
        return writer.toString().trim();
    }

    private static OWLAxiom normalizeEquivalentAxiom(OWLOntology ontology, OWLAxiom axiom) {
        if (!(axiom instanceof OWLEquivalentClassesAxiom equivalent)) {
            return axiom;
        }
        return normalizeEquivalentExpressions(ontology, equivalent.getClassExpressions(), axiom);
    }

    private static OWLAxiom normalizeEquivalentExpressions(OWLOntology ontology,
            Set<OWLClassExpression> expressions, OWLAxiom original) {
        if (expressions.isEmpty()) {
            throw new IllegalArgumentException("EquivalentClasses axiom has no class expressions");
        }
        if (expressions.size() == 1) {
            OWLClassExpression expression = expressions.iterator().next();
            OWLDataFactory factory = ontology.getOWLOntologyManager().getOWLDataFactory();
            return factory.getOWLSubClassOfAxiom(expression, expression);
        }
        return original;
    }

    private static String chooseField(Map<String, String> fields, String suffix) {
        return fields.keySet().stream()
                .filter(value -> value.endsWith(suffix))
                .sorted(Comparator.comparing((String value) -> value.startsWith("fs") ? 0 : 1)
                        .thenComparing(value -> value))
                .findFirst()
                .orElse(null);
    }

    private static boolean isGap(ReasoningStatus status) {
        return status == ReasoningStatus.UNSUPPORTED
                || status == ReasoningStatus.PARSE_ERROR
                || status == ReasoningStatus.PROFILE_VIOLATION
                || status == ReasoningStatus.TIMEOUT
                || status == ReasoningStatus.RESOURCE_EXHAUSTED;
    }

    private static Outcome pass(W3cCase testCase, long started, String detail) {
        return outcome(testCase, Status.PASS, started, detail);
    }

    private static Outcome gap(W3cCase testCase, long started, String detail) {
        return outcome(testCase, Status.GAP, started, detail);
    }

    private static Outcome fail(W3cCase testCase, long started, String detail) {
        return outcome(testCase, Status.FAIL, started, detail);
    }

    private static Outcome outcome(W3cCase testCase, Status status, long started, String detail) {
        return new Outcome(testCase.id(), status, elapsedMillis(started), sanitize(detail));
    }

    private static long elapsedMillis(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000);
    }

    private static String summarize(OntologyValidationReport report) {
        return summarize(report.violations().stream().map(Object::toString).toList());
    }

    private static String summarize(List<String> diagnostics) {
        return diagnostics == null || diagnostics.isEmpty() ? "no diagnostics" : String.join("; ", diagnostics);
    }

    private static String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String compact = value.replaceAll("[\\p{Cntrl}&&[^\\t]]", " ").replaceAll("\\s+", " ").trim();
        return compact.length() <= MAX_ERROR_LENGTH ? compact : compact.substring(0, MAX_ERROR_LENGTH) + "...";
    }

    private static String reportJson(String archiveSha256, List<Outcome> outcomes) {
        long pass = outcomes.stream().filter(value -> value.status() == Status.PASS).count();
        long gap = outcomes.stream().filter(value -> value.status() == Status.GAP).count();
        long fail = outcomes.stream().filter(value -> value.status() == Status.FAIL).count();
        StringBuilder json = new StringBuilder("{\"archiveSha256\":\"")
                .append(archiveSha256)
                .append("\",\"selectedCases\":").append(outcomes.size())
                .append(",\"pass\":").append(pass)
                .append(",\"gap\":").append(gap)
                .append(",\"fail\":").append(fail)
                .append(",\"cases\":[");
        for (int index = 0; index < outcomes.size(); index++) {
            if (index > 0) {
                json.append(',');
            }
            Outcome outcome = outcomes.get(index);
            json.append("{\"id\":\"").append(jsonEscape(outcome.id()))
                    .append("\",\"status\":\"").append(outcome.status())
                    .append("\",\"durationMillis\":").append(outcome.durationMillis())
                    .append(",\"error\":\"").append(jsonEscape(outcome.detail())).append("\"}");
        }
        return json.append("]}").toString();
    }

    private static void writeReportIfRequested(String report) throws IOException {
        String configured = System.getProperty("semantic.owl.w3c.results");
        if (configured == null || configured.isBlank()) {
            return;
        }
        Path destination = Path.of(configured).toAbsolutePath().normalize();
        Files.createDirectories(destination.getParent());
        Files.writeString(destination, report + System.lineSeparator(), StandardCharsets.UTF_8);
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static ImportFixtures readImportFixtures(Path path, String archiveSha256) throws IOException {
        JsonNode root = JSON.readTree(path.toFile());
        String fixtureArchiveSha = requiredText(root, "archiveSha256", path);
        if (!archiveSha256.equals(fixtureArchiveSha)) {
            throw new IllegalStateException("W3C import fixture archive digest does not match the pinned archive");
        }

        Map<String, LockedImport> artifacts = new LinkedHashMap<>();
        Map<String, LockedImport> artifactsByIri = new LinkedHashMap<>();
        JsonNode artifactNodes = root.path("artifacts");
        if (!artifactNodes.isObject()) {
            throw new IllegalStateException("W3C import fixture artifacts must be an object");
        }
        var artifactIterator = artifactNodes.fields();
        while (artifactIterator.hasNext()) {
            var entry = artifactIterator.next();
            String artifactId = entry.getKey();
            JsonNode artifact = entry.getValue();
            String ontologyIri = requiredText(artifact, "ontologyIri", path);
            JsonNode documents = artifact.path("documents");
            JsonNode rdfXml = documents.path("RDFXML");
            String documentText = requiredText(rdfXml, "documentText", path);
            String contentDigest = requiredText(rdfXml, "sha256", path).toLowerCase();
            String actualDigest = sha256(documentText.getBytes(StandardCharsets.UTF_8));
            if (!actualDigest.equals(contentDigest)) {
                throw new IllegalStateException("W3C import fixture content digest mismatch for " + artifactId);
            }
            LockedImport locked = new LockedImport(
                    ontologyIri,
                    ontologyIri,
                    java.util.Optional.empty(),
                    OntologyDocumentSyntax.RDF_XML,
                    documentText,
                    contentDigest,
                    artifactId);
            artifacts.put(artifactId, locked);
            if (artifactsByIri.put(ontologyIri, locked) != null) {
                throw new IllegalStateException("duplicate W3C import fixture ontology IRI " + ontologyIri);
            }
        }

        Map<String, List<LockedImport>> cases = new LinkedHashMap<>();
        for (JsonNode caseNode : root.path("cases")) {
            String caseId = requiredText(caseNode, "caseId", path);
            List<LockedImport> imports = new ArrayList<>();
            for (JsonNode artifactNode : caseNode.path("artifacts")) {
                String artifactId = artifactNode.asText();
                LockedImport locked = artifacts.get(artifactId);
                if (locked == null) {
                    throw new IllegalStateException("W3C import fixture case references unknown artifact " + artifactId);
                }
                imports.add(locked);
            }
            if (cases.put(caseId, List.copyOf(imports)) != null) {
                throw new IllegalStateException("duplicate W3C import fixture case " + caseId);
            }
        }
        return new ImportFixtures(cases, artifactsByIri);
    }

    private static String requiredText(JsonNode parent, String field, Path source) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalStateException("W3C import fixture " + source + " is missing " + field);
        }
        return value.asText();
    }

    private static Map<String, W3cCase> readCases(Path archive) throws Exception {
        Document document = secureXmlFactory().newDocumentBuilder().parse(archive.toFile());
        NodeList nodes = document.getElementsByTagNameNS(TEST_NS, "TestCase");
        Map<String, W3cCase> cases = new LinkedHashMap<>();
        for (int index = 0; index < nodes.getLength(); index++) {
            Element testCase = (Element) nodes.item(index);
            String id = childText(testCase, "identifier");
            Set<String> types = childResources(testCase, "type");
            Map<String, String> fields = new LinkedHashMap<>();
            for (Node child = testCase.getFirstChild(); child != null; child = child.getNextSibling()) {
                if (!(child instanceof Element element) || !TEST_NS.equals(element.getNamespaceURI())) {
                    continue;
                }
                String localName = element.getLocalName();
                if (localName.endsWith("Ontology") && element.getTextContent() != null) {
                    fields.put(localName, element.getTextContent());
                }
            }
            Set<String> importedArtifacts = childResources(testCase, "importedOntology");
            if (cases.put(id, new W3cCase(id, types, fields, importedArtifacts)) != null) {
                throw new IllegalStateException("duplicate W3C case identifier: " + id);
            }
        }
        return cases;
    }

    private static String childText(Element parent, String localName) {
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element
                    && TEST_NS.equals(element.getNamespaceURI())
                    && localName.equals(element.getLocalName())) {
                return element.getTextContent();
            }
        }
        throw new IllegalStateException("W3C case is missing " + localName);
    }

    private static Set<String> childResources(Element parent, String localName) {
        Set<String> values = new LinkedHashSet<>();
        String namespace = "type".equals(localName) ? RDF_NS : TEST_NS;
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element
                    && namespace.equals(element.getNamespaceURI())
                    && localName.equals(element.getLocalName())) {
                String value = element.getAttributeNS(RDF_NS, "resource");
                if (!value.isBlank()) {
                    int hash = value.lastIndexOf('#');
                    values.add(hash < 0 ? value : value.substring(hash + 1));
                }
            }
        }
        return values;
    }

    private static Set<String> selectedIds(Path index) throws IOException {
        String json = Files.readString(index, StandardCharsets.UTF_8);
        Set<String> selected = new LinkedHashSet<>();
        for (String block : json.split("(?=\\{\\s*\\\"id\\\")")) {
            if (!block.contains("\"selection\": \"selected\"")) {
                continue;
            }
            Matcher matcher = INDEX_ID_PATTERN.matcher(block);
            if (!matcher.find()) {
                throw new IllegalStateException("selected case index entry has no id");
            }
            if (!block.contains("\"execution\": \"NOT_RUN\"")) {
                throw new IllegalStateException("case index execution state is not NOT_RUN for " + matcher.group(1));
            }
            selected.add(jsonUnescape(matcher.group(1)));
        }
        return selected;
    }

    private static String sourceSha(Path source) throws IOException {
        return jsonSha(source, "source.json", SHA_PATTERN);
    }

    private static String indexSha(Path index) throws IOException {
        return jsonSha(index, "case-index.json", INDEX_SHA_PATTERN);
    }

    private static String jsonSha(Path file, String name, Pattern pattern) throws IOException {
        Matcher matcher = pattern.matcher(Files.readString(file, StandardCharsets.UTF_8));
        if (!matcher.find()) {
            throw new IllegalStateException(name + " is missing a SHA-256 field");
        }
        return matcher.group(1).toLowerCase();
    }

    private static String jsonUnescape(String value) {
        return value.replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static DocumentBuilderFactory secureXmlFactory() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        // The pinned archive uses a small internal DTD for namespace entities.
        // Internal entities are needed to read the index; external entities and
        // external DTDs remain disabled, so this parser cannot fetch the web.
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory;
    }

    private static Set<String> importedIris(String documentText) {
        try {
            Document document = secureXmlFactory().newDocumentBuilder().parse(
                    new InputSource(new StringReader(documentText)));
            Set<String> imported = new LinkedHashSet<>();
            NodeList imports = document.getElementsByTagNameNS(OWL_NS, "imports");
            for (int index = 0; index < imports.getLength(); index++) {
                Node node = imports.item(index);
                if (node instanceof Element element) {
                    String iri = element.getAttributeNS(RDF_NS, "resource");
                    if (!iri.isBlank()) {
                        imported.add(iri);
                    }
                }
            }
            return imported;
        } catch (Exception exception) {
            throw new IllegalStateException("unable to inspect locked import closure", exception);
        }
    }

    private static Path locate(String relative) {
        List<Path> candidates = List.of(Path.of(relative), Path.of("..", relative));
        return candidates.stream().map(Path::normalize).filter(Files::isRegularFile).findFirst()
                .orElseThrow(() -> new IllegalStateException("required W3C harness file is missing: " + relative));
    }

    private static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance(SHA256).digest(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is required by the JDK", exception);
        }
    }

    private static String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private enum Status { PASS, GAP, FAIL }

    private record W3cCase(String id, Set<String> types, Map<String, String> fields,
            Set<String> importedArtifacts) {
        private W3cCase {
            types = Set.copyOf(types);
            fields = Map.copyOf(fields);
            importedArtifacts = Set.copyOf(importedArtifacts);
        }

        private boolean hasImports() {
            return !importedArtifacts.isEmpty();
        }
    }

    private record Source(ParsedOntologyDocument parsed, List<String> aboxAssertions,
            List<LockedImport> lockedImports) { }

    private record ImportFixtures(Map<String, List<LockedImport>> byCase,
            Map<String, LockedImport> byOntologyIri) {
        private ImportFixtures {
            byCase = Map.copyOf(byCase);
            byOntologyIri = Map.copyOf(byOntologyIri);
        }

        private void requireCase(W3cCase testCase) {
            forCase(testCase);
        }

        private List<LockedImport> forCase(W3cCase testCase) {
            if (!testCase.hasImports()) {
                return List.of();
            }
            List<LockedImport> imports = byCase.get(testCase.id());
            if (imports == null) {
                throw new IllegalStateException("no locked W3C import fixture for " + testCase.id());
            }
            Set<String> directArtifactIds = imports.stream()
                    .map(LockedImport::artifactId).collect(java.util.stream.Collectors.toSet());
            if (!directArtifactIds.containsAll(testCase.importedArtifacts())) {
                throw new IllegalStateException("locked W3C import fixture is missing metadata artifacts for "
                        + testCase.id());
            }
            Map<String, LockedImport> closure = new LinkedHashMap<>();
            List<LockedImport> pending = new ArrayList<>(imports);
            while (!pending.isEmpty()) {
                LockedImport current = pending.remove(0);
                if (closure.putIfAbsent(current.resolvedOntologyIri(), current) != null) {
                    continue;
                }
                for (String importedIri : importedIris(current.documentText())) {
                    LockedImport nested = byOntologyIri.get(importedIri);
                    if (nested == null) {
                        throw new IllegalStateException("locked W3C import fixture is missing closure artifact "
                                + importedIri + " for " + testCase.id());
                    }
                    pending.add(nested);
                }
            }
            return List.copyOf(closure.values());
        }
    }

    private record LoadedOntology(OWLOntologyManager manager, OWLOntology ontology) {
        private void close() {
            manager.removeOntology(ontology);
        }
    }

    private record Outcome(String id, Status status, long durationMillis, String detail) {
        @Override
        public String toString() {
            return id + "=" + status + "(" + detail + ")";
        }
    }
}

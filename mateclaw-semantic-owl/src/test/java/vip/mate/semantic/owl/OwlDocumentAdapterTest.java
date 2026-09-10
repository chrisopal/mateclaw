package vip.mate.semantic.owl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;

import vip.mate.semantic.core.ontology.LockedImport;
import vip.mate.semantic.core.ontology.OntologyAxiomChange;
import vip.mate.semantic.core.ontology.OntologyAxiomDescriptor;
import vip.mate.semantic.core.ontology.OntologyDocument;
import vip.mate.semantic.core.ontology.OntologyDocumentException;
import vip.mate.semantic.core.ontology.OntologyDocumentSyntax;
import vip.mate.semantic.core.ontology.OntologyValidationReport;
import vip.mate.semantic.core.ontology.ParsedOntologyDocument;

class OwlDocumentAdapterTest {
    private static final Pattern MATRIX_ROW = Pattern.compile(
            "\\{\\s*\\\"id\\\"\\s*:\\s*\\\"(OWL-\\d+)\\\".*?"
                    + "\\\"construct\\\"\\s*:\\s*\\\"([^\\\"]+)\\\".*?"
                    + "\\\"positive_spec\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"\\\\])*)\\\"",
            Pattern.DOTALL);
    private static final Pattern ANONYMOUS_NODE = Pattern.compile("_:[A-Za-z0-9_.-]+");
    private final OwlDocumentAdapter adapter = new OwlDocumentAdapter();
    private final Path fixtures = Path.of("..", "docs", "validation", "semantic-owl-01", "fixtures").normalize();

    @Test
    void distinctAnonymousIndividualsAndLiteralNodeTextNeverShareAxiomIds() {
        var parsed=parse("Ontology(<urn:test:fixture> Declaration(Class(<urn:test:C>)) ClassAssertion(<urn:test:C> _:a) ClassAssertion(<urn:test:C> _:b) AnnotationAssertion(<http://www.w3.org/2000/01/rdf-schema#label> <urn:test:C> \"_:a\") AnnotationAssertion(<http://www.w3.org/2000/01/rdf-schema#label> <urn:test:C> \"_:b\"))",OntologyDocumentSyntax.FUNCTIONAL);
        assertEquals(parsed.axioms().size(),parsed.axioms().stream().map(OntologyAxiomDescriptor::axiomId).distinct().count());
        var removed=parsed.axioms().stream().filter(a->a.axiomType().equals("ClassAssertion")).findFirst().orElseThrow();
        var after=adapter.applyAxiomChanges(parsed,List.of(new OntologyAxiomChange.Remove(removed.axiomId())));
        assertEquals(1,after.axioms().stream().filter(a->a.axiomType().equals("ClassAssertion")).count());
    }

    @Test
    void parsesFunctionalFixtureAndRoundTripsThroughRdfXml() throws Exception {
        String source = fixture("domain-chain.ofn");
        ParsedOntologyDocument parsed = parse(source, OntologyDocumentSyntax.FUNCTIONAL);

        assertEquals("urn:test:fixture", parsed.parsedOntologyIri());
        assertTrue(parsed.axioms().stream().anyMatch(axiom -> axiom.axiomType().equals("ObjectPropertyDomain")));
        assertTrue(parsed.axioms().stream().anyMatch(axiom -> axiom.signatureIris().contains("urn:test:p")));
        assertTrue(adapter.classIris(parsed).contains("urn:test:A"));

        byte[] rdfXml = adapter.export(parsed, OntologyDocumentSyntax.RDF_XML);
        assertTrue(new String(rdfXml, StandardCharsets.UTF_8).contains("rdf:RDF"));
        ParsedOntologyDocument fromRdfXml = parse(
                new String(rdfXml, StandardCharsets.UTF_8), OntologyDocumentSyntax.RDF_XML);
        assertEquals(parsed.parsedOntologyIri(), fromRdfXml.parsedOntologyIri());
        assertEquals(structure(parsed), structure(fromRdfXml));

        byte[] functional = adapter.export(fromRdfXml, OntologyDocumentSyntax.FUNCTIONAL);
        ParsedOntologyDocument fromFunctional = parse(
                new String(functional, StandardCharsets.UTF_8), OntologyDocumentSyntax.FUNCTIONAL);
        assertEquals(structure(parsed), structure(fromFunctional));
    }

    @Test
    void preservesAxiomAnnotationsAndSupportsAddRemoveCommands() {
        String source = """
                Prefix(:=<urn:test:>)
                Prefix(rdfs:=<http://www.w3.org/2000/01/rdf-schema#>)
                Ontology(<urn:test:annotated>
                  Declaration(Class(:A))
                  Declaration(Class(:B))
                  Declaration(Class(:C))
                  AnnotationAssertion(rdfs:label :A "Original label")
                  SubClassOf(Annotation(rdfs:comment "axiom note") :A :B)
                )
                """;
        ParsedOntologyDocument parsed = parse(source, OntologyDocumentSyntax.FUNCTIONAL);
        OntologyAxiomDescriptor subclass = parsed.axioms().stream()
                .filter(axiom -> axiom.axiomType().equals("SubClassOf"))
                .findFirst().orElseThrow();
        assertEquals(1, subclass.annotations().size());
        assertTrue(parsed.axioms().stream().anyMatch(axiom -> axiom.rendering().contains("Original label")));

        ParsedOntologyDocument edited = adapter.applyAxiomChanges(parsed, List.of(
                new OntologyAxiomChange.Remove(subclass.axiomId()),
                new OntologyAxiomChange.Add(
                        "SubClassOf(<urn:test:A> ObjectSomeValuesFrom(<urn:test:p> <urn:test:B>))")));
        assertTrue(edited.axioms().stream().noneMatch(axiom -> axiom.axiomId().equals(subclass.axiomId())));
        assertTrue(edited.axioms().stream().anyMatch(axiom ->
                axiom.rendering().contains("ObjectSomeValuesFrom")));
        assertTrue(edited.axioms().stream().anyMatch(axiom -> axiom.rendering().contains("Original label")));
        assertFalse(edited.document().documentDigest().equals(parsed.document().documentDigest()));
    }

    @Test
    void projectsStableLiteralLabelsFromNamedIriSubjects() {
        String source = """
                Prefix(:=<urn:test:>)
                Prefix(rdfs:=<http://www.w3.org/2000/01/rdf-schema#>)
                Prefix(skos:=<http://www.w3.org/2004/02/skos/core#>)
                Ontology(<urn:test:labels>
                  Declaration(Class(:A))
                  AnnotationAssertion(rdfs:label :A "设备")
                  AnnotationAssertion(skos:prefLabel :A "Alpha")
                  AnnotationAssertion(skos:altLabel :A "设备")
                  AnnotationAssertion(rdfs:label _:anonymous "ignored")
                )
                """;
        ParsedOntologyDocument parsed = parse(source, OntologyDocumentSyntax.FUNCTIONAL);
        assertEquals(Map.of("urn:test:A", List.of("Alpha", "设备")), adapter.termLabels(parsed));
    }

    @Test
    void preservesOntologyAndNestedAnnotationsAndKeepsUnchangedAxiomIdAcrossEdit() {
        String source = """
                Prefix(:=<urn:test:>)
                Prefix(rdfs:=<http://www.w3.org/2000/01/rdf-schema#>)
                Ontology(<urn:test:nested>
                  Annotation(rdfs:comment "ontology note")
                  Declaration(Class(:A))
                  Declaration(Class(:B))
                  Declaration(AnnotationProperty(:note))
                  SubClassOf(Annotation(Annotation(:note "reviewed") rdfs:comment "axiom note") :A :B)
                )
                """;
        ParsedOntologyDocument parsed = parse(source, OntologyDocumentSyntax.FUNCTIONAL);
        assertEquals(1, parsed.ontologyAnnotations().size());
        assertTrue(parsed.ontologyAnnotations().getFirst().nestedAnnotations().isEmpty());
        OntologyAxiomDescriptor subclass = parsed.axioms().stream()
                .filter(axiom -> axiom.axiomType().equals("SubClassOf"))
                .findFirst().orElseThrow();
        assertEquals(1, subclass.annotations().size());
        assertEquals(1, subclass.annotations().getFirst().nestedAnnotations().size());

        ParsedOntologyDocument edited = adapter.applyAxiomChanges(parsed, List.of(
                new OntologyAxiomChange.Add("ClassAssertion(<urn:test:A> <urn:test:a>)")));
        OntologyAxiomDescriptor unchanged = edited.axioms().stream()
                .filter(axiom -> axiom.rendering().equals(subclass.rendering()))
                .findFirst().orElseThrow();
        assertEquals(subclass.axiomId(), unchanged.axiomId());
        assertEquals(parsed.ontologyAnnotations(), edited.ontologyAnnotations());
        assertEquals(subclass.annotations(), unchanged.annotations());
    }

    @Test
    void rejectsZeroOrMultipleAxiomsInSingleAddCommand() {
        ParsedOntologyDocument parsed = parse(
                "Ontology(<urn:test:add-target> Declaration(Class(<urn:test:A>)))",
                OntologyDocumentSyntax.FUNCTIONAL);
        OntologyDocumentException empty = assertThrows(OntologyDocumentException.class,
                () -> adapter.applyAxiomChanges(parsed, List.of(new OntologyAxiomChange.Add(
                        "Annotation(rdfs:comment \"metadata only\")"))));
        assertEquals(OntologyDocumentException.Kind.CHANGE_ERROR, empty.kind());
        OntologyDocumentException multiple = assertThrows(OntologyDocumentException.class,
                () -> adapter.applyAxiomChanges(parsed, List.of(new OntologyAxiomChange.Add(
                "Declaration(Class(<urn:test:B>)) Declaration(Class(<urn:test:C>))"))));
        assertEquals(OntologyDocumentException.Kind.CHANGE_ERROR, multiple.kind());

        OntologyDocumentException ignoredMetadata = assertThrows(OntologyDocumentException.class,
                () -> adapter.applyAxiomChanges(parsed, List.of(new OntologyAxiomChange.Add(
                        "Annotation(rdfs:comment \"metadata\") Declaration(Class(<urn:test:D>))"))));
        assertEquals(OntologyDocumentException.Kind.CHANGE_ERROR, ignoredMetadata.kind());

        OntologyDocumentException ignoredImport = assertThrows(OntologyDocumentException.class,
                () -> adapter.applyAxiomChanges(parsed, List.of(new OntologyAxiomChange.Add(
                        "Import(<urn:test:unwanted>) Declaration(Class(<urn:test:E>))"))));
        assertEquals(OntologyDocumentException.Kind.CHANGE_ERROR, ignoredImport.kind());
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("matrixRows")
    void coversEveryConcreteCapabilityMatrixConstructWithRoundTripAndEdit(
            String rowId, String construct, String positiveSpec) {
        List<LockedImport> locks = rowId.equals("OWL-003") ? matrixImportLocks() : List.of();
        String source = matrixSource(rowId, positiveSpec);
        ParsedOntologyDocument parsed = adapter.parse(
                "matrix-" + rowId, "revision-matrix", source, OntologyDocumentSyntax.FUNCTIONAL, locks);
        assertTrue(parsed.document().documentDigest().length() == 64, rowId);

        byte[] rdfXml = adapter.export(parsed, OntologyDocumentSyntax.RDF_XML);
        ParsedOntologyDocument rdfRoundTrip = adapter.parse(
                "matrix-" + rowId, "revision-matrix", new String(rdfXml, StandardCharsets.UTF_8),
                OntologyDocumentSyntax.RDF_XML, locks);
        assertEquals(canonicalStructure(parsed), canonicalStructure(rdfRoundTrip), rowId + " RDF/XML");
        assertEquals(parsed.ontologyAnnotations(), rdfRoundTrip.ontologyAnnotations(), rowId + " ontology annotations");

        byte[] functional = adapter.export(rdfRoundTrip, OntologyDocumentSyntax.FUNCTIONAL);
        ParsedOntologyDocument functionalRoundTrip = adapter.parse(
                "matrix-" + rowId, "revision-matrix", new String(functional, StandardCharsets.UTF_8),
                OntologyDocumentSyntax.FUNCTIONAL, locks);
        assertEquals(canonicalStructure(parsed), canonicalStructure(functionalRoundTrip), rowId + " Functional");
        assertEquals(parsed.ontologyAnnotations(), functionalRoundTrip.ontologyAnnotations(), rowId + " ontology annotations");

        ParsedOntologyDocument edited = adapter.applyAxiomChanges(parsed, List.of(
                new OntologyAxiomChange.Add("SubClassOf(<urn:test:A> <urn:test:B>)")));
        assertTrue(edited.axioms().stream().anyMatch(axiom ->
                axiom.axiomType().equals("SubClassOf")
                        && axiom.rendering().contains("<urn:test:A>")
                        && axiom.rendering().contains("<urn:test:B>")), rowId + " edit");
        assertTrue(canonicalStructure(edited).containsAll(canonicalStructure(parsed)), rowId + " edit preservation");
        String marker = "matrix-edit-" + rowId;
        ParsedOntologyDocument annotated = adapter.applyAxiomChanges(parsed, List.of(
                new OntologyAxiomChange.Add("AnnotationAssertion(<http://www.w3.org/2000/01/rdf-schema#comment> <urn:test:A> \"" + marker + "\")")));
        OntologyAxiomDescriptor added = annotated.axioms().stream()
                .filter(axiom -> axiom.rendering().contains(marker)).findFirst().orElseThrow();
        ParsedOntologyDocument restored = adapter.applyAxiomChanges(annotated,
                List.of(new OntologyAxiomChange.Remove(added.axiomId())));
        assertEquals(canonicalStructure(parsed), canonicalStructure(restored), rowId + " remove preserves every original axiom");
        assertEquals(parsed.parsedOntologyIri(), restored.parsedOntologyIri(), rowId + " ontology identity");
        assertEquals(parsed.ontologyAnnotations(), restored.ontologyAnnotations(), rowId + " remove preserves ontology annotations");
        assertEquals(parsed.document().importLockDigest(), restored.document().importLockDigest(), rowId + " remove preserves import lock");

    }

    @ParameterizedTest(name = "missing operands: {0}")
    @MethodSource("incompleteClassExpressions")
    void rejectsIncompleteClassExpressions(String rowId, String expression) {
        OntologyDocumentException failure = assertThrows(OntologyDocumentException.class,
                () -> parse(matrixSourceEnvelope(rowId, "EquivalentClasses(:C " + expression + ")"),
                        OntologyDocumentSyntax.FUNCTIONAL), rowId);
        assertEquals(OntologyDocumentException.Kind.PARSE_ERROR, failure.kind(), rowId);
    }

    @ParameterizedTest(name = "missing data range operands: {0}")
    @MethodSource("incompleteDataRanges")
    void rejectsIncompleteDataRanges(String rowId, String range) {
        OntologyDocumentException failure = assertThrows(OntologyDocumentException.class,
                () -> parse(matrixSourceEnvelope(rowId, "DataPropertyRange(:d " + range + ")"),
                        OntologyDocumentSyntax.FUNCTIONAL), rowId);
        assertEquals(OntologyDocumentException.Kind.PARSE_ERROR, failure.kind(), rowId);
    }

    private static Stream<Arguments> incompleteDataRanges() {
        return Stream.of(
                Arguments.of("OWL-033", "DataIntersectionOf()"),
                Arguments.of("OWL-034", "DataUnionOf()"),
                Arguments.of("OWL-035", "DataComplementOf()"),
                Arguments.of("OWL-036", "DataOneOf()"),
                Arguments.of("OWL-037", "DatatypeRestriction(xsd:decimal xsd:minInclusive)"));
    }

    private static Stream<Arguments> incompleteClassExpressions() {
        return Stream.of(
                Arguments.of("OWL-015", "ObjectIntersectionOf()"),
                Arguments.of("OWL-016", "ObjectUnionOf()"),
                Arguments.of("OWL-017", "ObjectComplementOf()"),
                Arguments.of("OWL-018", "ObjectOneOf()"),
                Arguments.of("OWL-019", "ObjectSomeValuesFrom(:p)"),
                Arguments.of("OWL-020", "ObjectAllValuesFrom(:p)"),
                Arguments.of("OWL-021", "ObjectHasValue(:p)"),
                Arguments.of("OWL-022", "ObjectHasSelf()"),
                Arguments.of("OWL-023", "ObjectMinCardinality(2)"),
                Arguments.of("OWL-024", "ObjectMaxCardinality(1)"),
                Arguments.of("OWL-025", "ObjectExactCardinality(1)"),
                Arguments.of("OWL-026", "DataSomeValuesFrom(:d)"),
                Arguments.of("OWL-027", "DataAllValuesFrom(:d)"),
                Arguments.of("OWL-028", "DataHasValue(:d)"),
                Arguments.of("OWL-029", "DataMinCardinality(1)"),
                Arguments.of("OWL-030", "DataMaxCardinality(1)"),
                Arguments.of("OWL-031", "DataExactCardinality(1)"));
    }

    @Test
    void preservesAnonymousIndividualIsomorphismAndStableBindingWithinFunctionalDocument() {
        String source = """
                Prefix(:=<urn:test:>)
                Ontology(<urn:test:anonymous>
                  Declaration(Class(:A))
                  Declaration(ObjectProperty(:p))
                  ClassAssertion(:A _:first)
                  ObjectPropertyAssertion(:p _:first _:second)
                )
                """;
        ParsedOntologyDocument parsed = parse(source, OntologyDocumentSyntax.FUNCTIONAL);
        ParsedOntologyDocument roundTrip = parse(
                new String(adapter.export(parsed, OntologyDocumentSyntax.RDF_XML), StandardCharsets.UTF_8),
                OntologyDocumentSyntax.RDF_XML);
        assertEquals(canonicalStructure(parsed), canonicalStructure(roundTrip));
        Map<String, String> idsByCanonicalRendering = parsed.axioms().stream()
                .collect(Collectors.toMap(axiom -> canonicalAnonymous(axiom.rendering()),
                        OntologyAxiomDescriptor::axiomId));
        // RDF/XML is an exchange format and may rename blank nodes. Binding IDs belong to
        // the authoritative Functional document; external reimports require provenance review.
        var again=adapter.parse(parsed.document().ontologyId(),parsed.document().revisionId(),
                new String(adapter.export(parsed,OntologyDocumentSyntax.FUNCTIONAL),StandardCharsets.UTF_8),
                OntologyDocumentSyntax.FUNCTIONAL,List.of());
        again.axioms().forEach(axiom -> assertEquals(
                idsByCanonicalRendering.get(canonicalAnonymous(axiom.rendering())),axiom.axiomId()));
    }

    @Test
    void rejectsWrongResolvedVersionAndDuplicateResolvedOntologyContent() {
        String imported = "Ontology(<urn:test:import> <urn:test:import:v1>\n"
                + "Declaration(Class(<urn:test:Imported>)))";
        LockedImport wrongVersion = LockedImport.fromText(
                "urn:test:requested", "urn:test:import", Optional.of("urn:test:import:v2"),
                OntologyDocumentSyntax.FUNCTIONAL, imported, "artifact-wrong-version");
        String root = "Ontology(<urn:test:root> Import(<urn:test:requested>))";
        OntologyDocument wrongVersionDocument = OntologyDocument.fromText(
                "ontology-1", "revision-1", "urn:test:root", Optional.empty(), OntologyDocumentSyntax.FUNCTIONAL,
                root, LockedImport.digest(List.of(wrongVersion)));
        OntologyDocumentException versionError = assertThrows(OntologyDocumentException.class,
                () -> adapter.parse(wrongVersionDocument, OntologyDocumentSyntax.FUNCTIONAL, List.of(wrongVersion)));
        assertEquals(OntologyDocumentException.Kind.IMPORT_MISSING, versionError.kind());

        LockedImport first = LockedImport.fromText(
                "urn:test:req-a", "urn:test:duplicate", Optional.empty(), OntologyDocumentSyntax.FUNCTIONAL,
                "Ontology(<urn:test:duplicate> Declaration(Class(<urn:test:A>)))", "a");
        LockedImport second = LockedImport.fromText(
                "urn:test:req-b", "urn:test:duplicate", Optional.empty(), OntologyDocumentSyntax.FUNCTIONAL,
                "Ontology(<urn:test:duplicate> Declaration(Class(<urn:test:B>)))", "b");
        OntologyDocument duplicateDocument = OntologyDocument.fromText(
                "ontology-1", "revision-1", "urn:test:root", Optional.empty(), OntologyDocumentSyntax.FUNCTIONAL,
                "Ontology(<urn:test:root> Import(<urn:test:req-a>))", LockedImport.digest(List.of(first, second)));
        OntologyDocumentException duplicateError = assertThrows(OntologyDocumentException.class,
                () -> adapter.parse(duplicateDocument, OntologyDocumentSyntax.FUNCTIONAL, List.of(first, second)));
        assertEquals(OntologyDocumentException.Kind.IMPORT_DIGEST_MISMATCH, duplicateError.kind());
    }

    @Test
    void accountsForAllProseCapabilityBoundariesWithDedicatedFixtures() throws Exception {
        String json = Files.readString(Path.of("..", "docs", "validation", "semantic-owl-01", "capability-matrix.json"));
        Matcher matcher = MATRIX_ROW.matcher(json);
        Set<String> proseRows = new java.util.LinkedHashSet<>();
        int totalRows = 0;
        while (matcher.find()) {
            totalRows++;
            String positiveSpec = jsonUnescape(matcher.group(3));
            if (positiveSpec.startsWith("非") || positiveSpec.endsWith("检查")
                    || positiveSpec.contains("保留词") || positiveSpec.contains("声明一致性")
                    || positiveSpec.contains("标准 datatype") || positiveSpec.contains("RDF/XML")
                    || positiveSpec.contains("循环/缺失") || positiveSpec.contains("三种对象")
                    || positiveSpec.contains("一元数据")) {
                proseRows.add(matcher.group(1));
            }
        }
        assertEquals(87, totalRows);
        assertEquals(Set.of("OWL-079", "OWL-080", "OWL-081", "OWL-082", "OWL-083",
                "OWL-084", "OWL-085", "OWL-086", "OWL-087"), proseRows);

        ParsedOntologyDocument nonSimple = parse(fixture("non-simple-cardinality.ofn"), OntologyDocumentSyntax.FUNCTIONAL);
        assertFalse(adapter.validateDl(nonSimple).valid());
        ParsedOntologyDocument cardinalities = parse("""
                Prefix(:=<urn:test:>)
                Ontology(<urn:test:cardinality>
                  Declaration(Class(:A)) Declaration(ObjectProperty(:p))
                  EquivalentClasses(:A ObjectMinCardinality(1 :p))
                  EquivalentClasses(:A ObjectMinCardinality(1 :p :A))
                )
                """, OntologyDocumentSyntax.FUNCTIONAL);
        assertEquals(4, cardinalities.axioms().size());
        assertFalse(adapter.export(nonSimple, OntologyDocumentSyntax.RDF_XML).length == 0);
    }

    @Test
    void preservesNestedOntologyAnnotationWhenPlacedBeforeOntologyAxioms() {
        String nestedOntologyAnnotation = matrixSourceEnvelope(
                "OWL-074", "Annotation(Annotation(:note \"reviewed\") rdfs:comment \"note\")");
        ParsedOntologyDocument ontologyAnnotated = parse(nestedOntologyAnnotation, OntologyDocumentSyntax.FUNCTIONAL);
        assertEquals(1, ontologyAnnotated.ontologyAnnotations().size());
        assertEquals(1, ontologyAnnotated.ontologyAnnotations().getFirst().nestedAnnotations().size());

        String supportedAxiomEncoding = """
                Prefix(:=<urn:test:>)
                Prefix(rdfs:=<http://www.w3.org/2000/01/rdf-schema#>)
                Ontology(<urn:test:nested-axiom>
                  Declaration(Class(:A)) Declaration(Class(:B)) Declaration(AnnotationProperty(:note))
                  SubClassOf(Annotation(Annotation(:note "reviewed") rdfs:comment "note") :A :B)
                )
                """;
        ParsedOntologyDocument parsed = parse(supportedAxiomEncoding, OntologyDocumentSyntax.FUNCTIONAL);
        assertEquals(1, parsed.axioms().getLast().annotations().getFirst().nestedAnnotations().size());
    }

    @Test
    void loadsCyclicTransitiveImportClosureFromPinnedArtifacts() {
        String a = "Ontology(<urn:test:A> Import(<urn:test:B>) Declaration(Class(<urn:test:AClass>)))";
        String b = "Ontology(<urn:test:B> Import(<urn:test:A>) Declaration(Class(<urn:test:BClass>)))";
        LockedImport lockA = LockedImport.fromText(
                "urn:test:A", "urn:test:A", Optional.empty(), OntologyDocumentSyntax.FUNCTIONAL, a, "a");
        LockedImport lockB = LockedImport.fromText(
                "urn:test:B", "urn:test:B", Optional.empty(), OntologyDocumentSyntax.FUNCTIONAL, b, "b");
        String root = "Ontology(<urn:test:root> Import(<urn:test:A>) Declaration(Class(<urn:test:Root>)))";
        OntologyDocument document = OntologyDocument.fromText(
                "ontology-1", "revision-1", "urn:test:root", Optional.empty(), OntologyDocumentSyntax.FUNCTIONAL,
                root, LockedImport.digest(List.of(lockA, lockB)));
        ParsedOntologyDocument parsed = adapter.parse(document, OntologyDocumentSyntax.FUNCTIONAL, List.of(lockA, lockB));
        assertEquals(Set.of("urn:test:A"), Set.copyOf(parsed.imports()));
        assertEquals(2, parsed.lockedImports().size());
    }

    @ParameterizedTest(name="global DL restriction: {0}")
    @MethodSource("globalRestrictionCases")
    void reportsIllegalGlobalCombinationsWithoutDeletingAxioms(String label,String body) {
        var parsed=parse(matrixSourceEnvelope("OWL-079",body),OntologyDocumentSyntax.FUNCTIONAL);
        var before=canonicalStructure(parsed);
        var report=adapter.validateDl(parsed);
        assertFalse(report.valid(),label);
        assertTrue(report.violations().stream().anyMatch(v->v.code().equals("PROFILE_VIOLATION")
            &&!v.path().isBlank()&&!v.message().isBlank()),label+" must provide a named finding");
        assertEquals(before,canonicalStructure(parsed),label+" validation must not repair by deletion");
        var exported=adapter.export(parsed,OntologyDocumentSyntax.FUNCTIONAL);
        var reparsed=parse(new String(exported,StandardCharsets.UTF_8),OntologyDocumentSyntax.FUNCTIONAL);
        assertEquals(before,canonicalStructure(reparsed),label+" invalid draft must remain recoverable");
        assertFalse(adapter.validateDl(reparsed).valid(),label+" export cannot hide the violation");
    }
    @ParameterizedTest(name="reserved vocabulary: {0}")
    @org.junit.jupiter.params.provider.ValueSource(strings={
        "Declaration(Class(<http://www.w3.org/2002/07/owl#topObjectProperty>))",
        "Declaration(DataProperty(<http://www.w3.org/2002/07/owl#Thing>))",
        "Declaration(Datatype(<http://www.w3.org/2002/07/owl#Thing>))"
    })
    void rejectsReservedVocabularyInTheWrongEntityCategory(String declaration) {
        var parsed=parse("Ontology(<urn:test:reserved> "+declaration+")",OntologyDocumentSyntax.FUNCTIONAL);
        var report=adapter.validateDl(parsed);
        assertFalse(report.valid(),declaration);
        assertTrue(report.violations().stream().anyMatch(v->v.code().equals("PROFILE_VIOLATION")&&!v.message().isBlank()));
        assertEquals(1,parsed.axioms().size(),"Invalid declaration remains available for repair");
        var valid=parse("Ontology(<urn:test:reserved-valid> SubClassOf(<http://www.w3.org/2002/07/owl#Nothing> <http://www.w3.org/2002/07/owl#Thing>))",OntologyDocumentSyntax.FUNCTIONAL);
        assertTrue(adapter.validateDl(valid).valid(),"Legitimate builtin classes must remain usable");
    }

    @Test
    void distinguishesRegularPropertyChainsFromMutualStrictOrderingCycles() {
        String regular="SubObjectPropertyOf(ObjectPropertyChain(:p :q) :r)";
        var allowed=parse(matrixSourceEnvelope("OWL-080",regular),OntologyDocumentSyntax.FUNCTIONAL);
        assertTrue(adapter.validateDl(allowed).valid());
        String cycle=regular+" SubObjectPropertyOf(ObjectPropertyChain(:r :p) :q)";
        var rejected=parse(matrixSourceEnvelope("OWL-080",cycle),OntologyDocumentSyntax.FUNCTIONAL);
        var report=adapter.validateDl(rejected);
        assertFalse(report.valid(),"The property order cannot require q < r and r < q");
        assertTrue(report.violations().stream().anyMatch(v->v.code().equals("PROFILE_VIOLATION")
            &&v.message().toLowerCase(java.util.Locale.ROOT).contains("cycle")),()->report.violations().toString());
        assertEquals(2,rejected.axioms().stream().filter(v->v.axiomType().equals("SubPropertyChainOf")).count());
        var transitive=parse(matrixSourceEnvelope("OWL-080","SubObjectPropertyOf(ObjectPropertyChain(:p :p) :p)"),OntologyDocumentSyntax.FUNCTIONAL);
        assertTrue(adapter.validateDl(transitive).valid(),"Legal transitivity exception must not be rejected as a generic graph cycle");
    }

    private static Stream<Arguments> globalRestrictionCases() {
        return Stream.of(
            Arguments.of("non-simple functional","TransitiveObjectProperty(:p) FunctionalObjectProperty(:p)"),
            Arguments.of("non-simple inverse-functional","TransitiveObjectProperty(:p) InverseFunctionalObjectProperty(:p)"),
            Arguments.of("non-simple asymmetric","TransitiveObjectProperty(:p) AsymmetricObjectProperty(:p)"),
            Arguments.of("non-simple irreflexive","TransitiveObjectProperty(:p) IrreflexiveObjectProperty(:p)"),
            Arguments.of("non-simple disjoint","TransitiveObjectProperty(:p) DisjointObjectProperties(:p :q)"),
            Arguments.of("non-simple self","TransitiveObjectProperty(:p) SubClassOf(:A ObjectHasSelf(:p))"),
            Arguments.of("object-data property punning","Declaration(DataProperty(:p))"),
            Arguments.of("cyclic datatype definition","DatatypeDefinition(:D :D)"));
    }

    @Test
    void reportsProfileViolationAndInvalidDatatypeLexicalForm() {
        ParsedOntologyDocument nonSimple = parse(fixture("non-simple-cardinality.ofn"), OntologyDocumentSyntax.FUNCTIONAL);
        OntologyValidationReport profile = adapter.validateDl(nonSimple);
        assertFalse(profile.valid());
        assertTrue(profile.violations().stream().anyMatch(v -> v.code().equals("PROFILE_VIOLATION")));

        ParsedOntologyDocument invalidLiteral = parse(fixture("invalid-literal.ofn"), OntologyDocumentSyntax.FUNCTIONAL);
        OntologyValidationReport literal = adapter.validateDl(invalidLiteral);
        assertFalse(literal.valid());
        assertTrue(literal.violations().stream().anyMatch(v -> v.code().equals("INVALID_LITERAL")));
    }

    @ParameterizedTest(name = "facet compatibility: {0}")
    @MethodSource("facetCompatibilityCases")
    void checksFacetCompatibilityWithoutDroppingTheRestriction(String datatype, String facet, String literal, boolean valid) {
        String text = matrixSourceEnvelope("OWL-037", "DataPropertyRange(:d DatatypeRestriction("
                + datatype + " " + facet + " " + literal + "))");
        ParsedOntologyDocument parsed = parse(text, OntologyDocumentSyntax.FUNCTIONAL);
        OntologyValidationReport report = adapter.validateDl(parsed);
        assertEquals(valid, report.valid(), datatype + " " + facet + ": " + report.violations());
        if (!valid) assertTrue(report.violations().stream().anyMatch(v -> v.code().equals("PROFILE_VIOLATION")));
        assertTrue(parsed.axioms().stream().anyMatch(a -> a.rendering().contains("DatatypeRestriction")));
    }

    private static Stream<Arguments> facetCompatibilityCases() {
        return Stream.of(
                Arguments.of("xsd:decimal", "xsd:minInclusive", "\"0\"^^xsd:decimal", true),
                Arguments.of("xsd:decimal", "xsd:length", "\"2\"^^xsd:integer", false),
                Arguments.of("xsd:string", "xsd:length", "\"2\"^^xsd:integer", true),
                Arguments.of("xsd:string", "xsd:minInclusive", "\"a\"^^xsd:string", false));
    }

    @Test
    void parsesQualityAndInventorySchemaFixturesWithoutSemanticLoss() throws Exception {
        Path serverFixtures = Path.of("..", "mateclaw-server", "src", "test", "resources", "semantic", "owl")
                .normalize();
        for (String name : List.of("quality.ofn", "inventory.ofn")) {
            ParsedOntologyDocument parsed = parse(Files.readString(serverFixtures.resolve(name)),
                    OntologyDocumentSyntax.FUNCTIONAL);
            assertTrue(parsed.axioms().size() > 10, name);
            assertTrue(adapter.validateDl(parsed).valid(), name);
            ParsedOntologyDocument roundTrip = parse(
                    new String(adapter.export(parsed, OntologyDocumentSyntax.FUNCTIONAL), StandardCharsets.UTF_8),
                    OntologyDocumentSyntax.FUNCTIONAL);
            assertEquals(structure(parsed), structure(roundTrip), name);
        }
    }

    @Test
    void refusesUnpinnedImportsAndInfersNamedOrAnonymousIdentitySafely() {
        String missingImport = """
                Ontology(<urn:test:root>
                  Import(<urn:test:unlocked>)
                )
                """;
        OntologyDocumentException exception = assertThrows(OntologyDocumentException.class,
                () -> parse(missingImport, OntologyDocumentSyntax.FUNCTIONAL));
        assertEquals(OntologyDocumentException.Kind.IMPORT_MISSING, exception.kind());

        String anonymous = "Ontology(Declaration(Class(<urn:test:A>)))";
        ParsedOntologyDocument inferred = adapter.parse(
                "ontology-1", "revision-1", anonymous, OntologyDocumentSyntax.FUNCTIONAL, List.of());
        assertTrue(inferred.document().ontologyIri().startsWith("urn:mateclaw:unbound:"));
        assertEquals(inferred.document().ontologyIri(), inferred.parsedOntologyIri());
    }

    @Test
    void validatesDeclarationsAgainstTheRootImportsClosure() {
        var lock = LockedImport.fromText("urn:test:dependency", "urn:test:dependency", Optional.empty(),
                OntologyDocumentSyntax.FUNCTIONAL,
                "Ontology(<urn:test:dependency> Declaration(Class(<urn:test:A>)) SubClassOf(<urn:test:A> <urn:test:B>))", "closure-dependency");
        var parsed = adapter.parse("root", "revision", "Ontology(<urn:test:root> Import(<urn:test:dependency>) Declaration(Class(<urn:test:B>)))",
                OntologyDocumentSyntax.FUNCTIONAL, List.of(lock));
        assertTrue(adapter.validateDl(parsed).valid(), adapter.validateDl(parsed).toString());
        var invalid = adapter.parse("root", "revision", "Ontology(<urn:test:root> Import(<urn:test:dependency>))",
                OntologyDocumentSyntax.FUNCTIONAL, List.of(lock));
        assertFalse(adapter.validateDl(invalid).valid());
    }

    @Test
    void loadsOnlyThePinnedImportClosureAndChecksResolvedVersion() {
        String imported = "Ontology(<urn:test:import> <urn:test:import:v1>\n"
                + "Declaration(Class(<urn:test:Imported>)))";
        LockedImport lock = LockedImport.fromText(
                "urn:test:requested", "urn:test:import", Optional.of("urn:test:import:v1"),
                OntologyDocumentSyntax.FUNCTIONAL, imported, "artifact-import-v1");
        String root = "Ontology(<urn:test:root>\nImport(<urn:test:requested>)\n"
                + "Declaration(Class(<urn:test:Root>)))";
        OntologyDocument document = OntologyDocument.fromText(
                "ontology-1", "revision-1", "urn:test:root", Optional.empty(),
                OntologyDocumentSyntax.FUNCTIONAL, root, LockedImport.digest(List.of(lock)));

        ParsedOntologyDocument parsed = adapter.parse(document, OntologyDocumentSyntax.FUNCTIONAL, List.of(lock));
        assertEquals(List.of("urn:test:requested"), parsed.imports());
        assertEquals("urn:test:import", parsed.lockedImports().getFirst().resolvedOntologyIri());
    }

    @Test
    void internalEntitiesParseWithoutChangingAuthoritativeDocument() {
        String xml = "<!DOCTYPE rdf:RDF [<!ENTITY ns 'urn:test:'>]>"
                + "<rdf:RDF xmlns:rdf='http://www.w3.org/1999/02/22-rdf-syntax-ns#' xmlns:owl='http://www.w3.org/2002/07/owl#'>"
                + "<owl:Ontology rdf:about='&ns;internal'/><owl:Class rdf:about='&ns;Equipment'/></rdf:RDF>";
        ParsedOntologyDocument parsed = parse(xml, OntologyDocumentSyntax.RDF_XML);
        assertEquals(xml, parsed.document().documentText());
        assertEquals(OntologyDocument.sha256(xml), parsed.document().documentDigest());
        assertTrue(adapter.classIris(parsed).contains("urn:test:Equipment"));
        var lock = LockedImport.fromText("urn:test:internal", "urn:test:internal", Optional.empty(),
                OntologyDocumentSyntax.RDF_XML, xml, "internal-artifact");
        String root = "Ontology(<urn:test:root> Import(<urn:test:internal>))";
        var imported = adapter.parse("ontology-root", "revision-root", root, OntologyDocumentSyntax.FUNCTIONAL, List.of(lock));
        assertTrue(adapter.classIris(imported).contains("urn:test:Equipment"));
        assertEquals(xml, imported.lockedImports().getFirst().documentText());

    }

    @Test
    void blocksExternalEntityDeclarationsBeforeRdfParser() {
        String xml = "<?xml version=\"1.0\"?><!DOCTYPE rdf:RDF [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>"
                + "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"/>";
        OntologyDocumentException exception = assertThrows(OntologyDocumentException.class,
                () -> parse(xml, OntologyDocumentSyntax.RDF_XML));
        assertEquals(OntologyDocumentException.Kind.PARSE_ERROR, exception.kind());
    }

    private ParsedOntologyDocument parse(String text, OntologyDocumentSyntax syntax) {
        return adapter.parse("ontology-1", "revision-1", text, syntax, List.of());
    }

    private String fixture(String name) {
        try {
            return Files.readString(fixtures.resolve(name));
        } catch (Exception exception) {
            throw new IllegalStateException("fixture unavailable: " + name, exception);
        }
    }

    private Set<String> structure(ParsedOntologyDocument document) {
        return document.axioms().stream()
                .map(axiom -> axiom.axiomType() + "|" + axiom.rendering()
                        + "|" + String.join(",", axiom.signatureIris()))
                .collect(Collectors.toSet());
    }

    private Set<String> canonicalStructure(ParsedOntologyDocument document) {
        return document.axioms().stream()
                .map(axiom -> axiom.axiomType() + "|" + canonicalAnonymous(axiom.rendering())
                        + "|" + String.join(",", axiom.signatureIris()))
                .collect(Collectors.toSet());
    }

    private static String canonicalAnonymous(String rendering) {
        Matcher matcher = ANONYMOUS_NODE.matcher(rendering);
        Map<String, String> replacements = new java.util.LinkedHashMap<>();
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String replacement = replacements.computeIfAbsent(
                    matcher.group(), ignored -> "_:anon" + (replacements.size() + 1));
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static Stream<Arguments> matrixRows() throws Exception {
        String json = Files.readString(Path.of("..", "docs", "validation", "semantic-owl-01", "capability-matrix.json"));
        Matcher matcher = MATRIX_ROW.matcher(json);
        List<Arguments> rows = new ArrayList<>();
        while (matcher.find()) {
            String id = matcher.group(1);
            if (Integer.parseInt(id.substring(4)) <= 78) {
                rows.add(Arguments.of(id, matcher.group(2), jsonUnescape(matcher.group(3))));
            }
        }
        assertEquals(78, rows.size(), "all concrete capability matrix rows must be exercised");
        return rows.stream();
    }

    private static String jsonUnescape(String text) {
        return text.replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static String matrixSource(String rowId, String positiveSpec) {
        if (rowId.equals("OWL-001") || rowId.equals("OWL-002")) {
            return positiveSpec;
        }
        String body = rowId.equals("OWL-003") || rowId.equals("OWL-004") ? "" : positiveSpec;
        return matrixSourceEnvelope(rowId, body);
    }

    private static String matrixSourceEnvelope(String rowId, String body) {
        String prefix = "Prefix(:=<urn:test:>)\n";
        String imports = rowId.equals("OWL-003") ? "Import(<urn:test:import:v1>)\n" : "";
        boolean ontologyAnnotation = rowId.equals("OWL-072") || rowId.equals("OWL-074");
        String ontologyAnnotations = ontologyAnnotation ? body + "\n" : "";
        String bodyAfterDeclarations = ontologyAnnotation ? "" : body;
        return prefix
                + "Prefix(xsd:=<http://www.w3.org/2001/XMLSchema#>)\n"
                + "Prefix(rdfs:=<http://www.w3.org/2000/01/rdf-schema#>)\n"
                + "Ontology(<urn:test:matrix:o>\n"
                + imports
                + ontologyAnnotations
                + "Declaration(Class(<urn:test:A>))\n"
                + "Declaration(Class(<urn:test:B>))\n"
                + "Declaration(Class(<urn:test:C>))\n"
                + "Declaration(Datatype(<urn:test:D>))\n"
                + "Declaration(ObjectProperty(<urn:test:p>))\n"
                + "Declaration(ObjectProperty(<urn:test:q>))\n"
                + "Declaration(ObjectProperty(<urn:test:r>))\n"
                + "Declaration(DataProperty(<urn:test:d>))\n"
                + "Declaration(DataProperty(<urn:test:e>))\n"
                + "Declaration(AnnotationProperty(<urn:test:note>))\n"
                + "Declaration(NamedIndividual(<urn:test:a>))\n"
                + "Declaration(NamedIndividual(<urn:test:b>))\n"
                + "Declaration(NamedIndividual(<urn:test:c>))\n"
                + (bodyAfterDeclarations.isBlank() ? "" : bodyAfterDeclarations + "\n")
                + ")";
    }

    private static List<LockedImport> matrixImportLocks() {
        String imported = "Ontology(<urn:test:import:v1>\nDeclaration(Class(<urn:test:Imported>)))";
        return List.of(LockedImport.fromText(
                "urn:test:import:v1", "urn:test:import:v1", Optional.empty(),
                OntologyDocumentSyntax.FUNCTIONAL, imported, "matrix-import"));
    }
}

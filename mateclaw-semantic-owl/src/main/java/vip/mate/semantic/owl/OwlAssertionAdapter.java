package vip.mate.semantic.owl;

import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.FunctionalSyntaxDocumentFormat;
import org.semanticweb.owlapi.functional.renderer.FunctionalSyntaxObjectRenderer;
import org.semanticweb.owlapi.io.OWLFunctionalSyntaxOntologyFormat;
import org.semanticweb.owlapi.io.OWLOntologyDocumentSource;
import org.semanticweb.owlapi.io.StringDocumentSource;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLClassAssertionAxiom;
import org.semanticweb.owlapi.model.OWLDataPropertyAssertionAxiom;
import org.semanticweb.owlapi.model.OWLDataPropertyExpression;
import org.semanticweb.owlapi.model.OWLDifferentIndividualsAxiom;
import org.semanticweb.owlapi.model.OWLDeclarationAxiom;
import org.semanticweb.owlapi.model.OWLIndividual;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLNegativeDataPropertyAssertionAxiom;
import org.semanticweb.owlapi.model.OWLNegativeObjectPropertyAssertionAxiom;
import org.semanticweb.owlapi.model.OWLObjectPropertyAssertionAxiom;
import org.semanticweb.owlapi.model.OWLObjectPropertyExpression;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyIRIMapper;
import org.semanticweb.owlapi.model.OWLOntologyLoaderConfiguration;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.OWLSameIndividualAxiom;
import org.semanticweb.owlapi.model.OWLDocumentFormat;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.profiles.OWL2DLProfile;
import org.semanticweb.owlapi.profiles.OWLProfileReport;
import org.semanticweb.owlapi.profiles.OWLProfileViolation;
import org.semanticweb.owlapi.util.OWLObjectDuplicator;
import org.semanticweb.owlapi.vocab.OWL2Datatype;

import vip.mate.semantic.core.fact.AssertionPayload;
import vip.mate.semantic.core.fact.AssertionValidationPort;
import vip.mate.semantic.core.fact.Entity;
import vip.mate.semantic.core.fact.PredicateRef;
import vip.mate.semantic.core.fact.StatementContent;
import vip.mate.semantic.core.identity.SemanticIds.EntityId;
import vip.mate.semantic.core.ontology.LockedImport;
import vip.mate.semantic.core.ontology.OntologyDocument;
import vip.mate.semantic.core.ontology.OntologyDocumentSyntax;
import vip.mate.semantic.core.ontology.OntologyRevision;
import vip.mate.semantic.core.validation.ValidationReport;
import vip.mate.semantic.core.validation.Violation;

/** OWLAPI-backed validation and derivation of the JDK-only assertion envelope. */
public final class OwlAssertionAdapter implements AssertionValidationPort {
    private static final String ASSERTION_ONTOLOGY_IRI = "urn:mateclaw:assertion";
    private static final Pattern IMPORT = Pattern.compile("(?i)\\bImport\\s*\\(");
    private static final Pattern ONTOLOGY = Pattern.compile("(?i)\\bOntology\\s*\\(");
    private static final Pattern DOCTYPE = Pattern.compile("(?i)<!doctype");

    /** Parse one named-subject ABox assertion and derive its indexes from OWLAPI. */
    public AssertionPayload parse(String functionalSyntax) {
        String source = requireSyntax(functionalSyntax);
        if (IMPORT.matcher(FunctionalSyntaxGuard.structure(source)).find()) {
            throw new IllegalArgumentException("imports are not allowed in a business assertion");
        }
        if (ONTOLOGY.matcher(FunctionalSyntaxGuard.structure(source)).find()) {
            throw new IllegalArgumentException("parse expects one assertion, not an Ontology wrapper");
        }
        try (AssertionWorkspace workspace = AssertionWorkspace.create(source)) {
            OWLOntology ontology = load(workspace.manager(), workspace.source(), workspace.missingImport());
            if (!ontology.getImportsDeclarations().isEmpty()) {
                throw new IllegalArgumentException("imports are not allowed in a business assertion");
            }
            if (ontology.annotations().findAny().isPresent()) {
                throw new IllegalArgumentException("ontology annotations are not allowed in a business assertion");
            }
            List<org.semanticweb.owlapi.model.OWLAxiom> axioms = ontology.getAxioms().stream().toList();
            if (axioms.size() != 1) {
                throw new IllegalArgumentException("business assertion must contain exactly one axiom");
            }
            return derive(ontology, axioms.getFirst(), source);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (OWLOntologyCreationException | IOException exception) {
            throw new IllegalArgumentException("invalid Functional Syntax assertion: " + exception.getMessage(), exception);
        }
    }

    /**
     * Remap named individuals in one assertion through the OWLAPI object model.
     * Class, property, datatype, and literal IRIs are deliberately left unchanged.
     */
    public AssertionPayload remapIndividuals(String functionalSyntax, Map<String, String> iriMappings) {
        return remapRoles(functionalSyntax,Map.of(),Map.of(),Map.of(),iriMappings);
    }

    /** Role-specific mappings preserve OWL punning and literal/annotation values. */
    public AssertionPayload remapRoles(String functionalSyntax,Map<String,String> classes,
            Map<String,String> objectProperties,Map<String,String> dataProperties,Map<String,String> individuals) {
        String source=requireSyntax(functionalSyntax);
        for(var mappings:List.of(classes,objectProperties,dataProperties,individuals)) {
            requireNonNull(mappings,"IRI mappings");
            mappings.forEach((from,to)-> {
                if(from==null||to==null||!IRI.create(from).isAbsolute()||!IRI.create(to).isAbsolute())
                    throw new IllegalArgumentException("IRI mappings must use absolute IRIs");
            });
        }
        if (IMPORT.matcher(FunctionalSyntaxGuard.structure(source)).find() || ONTOLOGY.matcher(FunctionalSyntaxGuard.structure(source)).find()) {
            return parse(source);
        }
        try (AssertionWorkspace workspace = AssertionWorkspace.create(source)) {
            OWLOntology ontology = load(workspace.manager(), workspace.source(), workspace.missingImport());
            List<org.semanticweb.owlapi.model.OWLAxiom> axioms = ontology.getAxioms().stream().toList();
            if (axioms.size() != 1 || workspace.missingImport().get() != null) {
                throw new IllegalArgumentException("business assertion must contain exactly one axiom and no imports");
            }
            Map<OWLEntity, IRI> entityMappings = new HashMap<>();
            ontology.signature().forEach(entity -> {
                Map<String,String> mappings=entity.isOWLClass()?classes:entity.isOWLObjectProperty()?objectProperties:
                    entity.isOWLDataProperty()?dataProperties:entity.isOWLNamedIndividual()?individuals:Map.of();
                String mapped=mappings.get(entity.getIRI().getIRIString());
                if(mapped!=null)entityMappings.put(entity,IRI.create(mapped));
            });
            if (entityMappings.isEmpty()) {
                return derive(ontology, axioms.getFirst(), source);
            }
            org.semanticweb.owlapi.model.OWLAxiom remapped =
                    new OWLObjectDuplicator(entityMappings, workspace.manager()).duplicateObject(axioms.getFirst());
            OWLOntology remappedOntology = workspace.manager().createOntology(
                    Set.of(remapped), IRI.create("urn:mateclaw:remapped-assertion"));
            try {
                return derive(remappedOntology, remapped, null);
            } finally {
                workspace.manager().removeOntology(remappedOntology);
            }
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (OWLOntologyCreationException | IOException exception) {
            throw new IllegalArgumentException("invalid Functional Syntax assertion: " + exception.getMessage(), exception);
        }
    }

    @Override
    public Optional<String> businessUnit(AssertionPayload assertion) {
        String source=requireSyntax(assertion.functionalSyntax());
        try (AssertionWorkspace workspace=AssertionWorkspace.create(source)) {
            var ontology=load(workspace.manager(),workspace.source(),workspace.missingImport());
            var units=ontology.axioms().flatMap(a->a.annotations())
                    .filter(a->a.getProperty().getIRI().getIRIString().equals("urn:mateclaw:semantic:unit"))
                    .toList();
            if (units.isEmpty()) return Optional.empty();
            if (units.size()!=1 || !(units.getFirst().getValue() instanceof OWLLiteral literal)
                    || literal.hasLang() || !literal.getDatatype().getIRI().getIRIString().equals("http://www.w3.org/2001/XMLSchema#string")
                    || literal.getLiteral().isBlank()) {
                throw new IllegalArgumentException("One non-empty xsd:string unit annotation required");
            }
            return Optional.of(literal.getLiteral());
        } catch (OWLOntologyCreationException | IOException e) {
            throw new IllegalArgumentException("Invalid annotated assertion",e);
        }
    }

    @Override
    public ValidationReport validate(
            OntologyRevision ontology,
            StatementContent candidate,
            Map<EntityId, Entity> referencedEntities) {
        List<Violation> violations = new ArrayList<>();
        requireNonNull(candidate, "candidate");
        requireNonNull(referencedEntities, "referencedEntities");
        AssertionPayload actual;
        try {
            actual = parse(candidate.assertion().functionalSyntax());
        } catch (IllegalArgumentException exception) {
            return report("OWL_SYNTAX_INVALID", "assertion.functionalSyntax", exception.getMessage());
        }
        compareIndexes(candidate, actual, violations);
        resolveReferencedIndividuals(actual, referencedEntities, violations);
        validateAgainstOntology(ontology, actual, violations);
        return new ValidationReport(violations);
    }

    private static AssertionPayload derive(OWLOntology ontology,
            org.semanticweb.owlapi.model.OWLAxiom axiom, String authoritativeSyntax) {
        Set<String> signature = axiom.getSignature().stream()
                .map(entity -> entity.getIRI().getIRIString())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        String rendering = authoritativeSyntax == null ? render(ontology, axiom) : authoritativeSyntax;
        if (axiom instanceof OWLClassAssertionAxiom classAssertion) {
            String subject = namedIndividual(classAssertion.getIndividual(), "class assertion subject");
            String expression = render(ontology, classAssertion.getClassExpression());
            return AssertionPayload.classAssertion(rendering, subject, expression, signature);
        }
        if (axiom instanceof OWLObjectPropertyAssertionAxiom objectAssertion) {
            return AssertionPayload.objectPropertyAssertion(rendering,
                    namedIndividual(objectAssertion.getSubject(), "object assertion subject"),
                    namedObjectProperty(objectAssertion.getProperty(), "object assertion property"),
                    namedIndividual(objectAssertion.getObject(), "object assertion object"), false, signature);
        }
        if (axiom instanceof OWLNegativeObjectPropertyAssertionAxiom negativeObjectAssertion) {
            return AssertionPayload.objectPropertyAssertion(rendering,
                    namedIndividual(negativeObjectAssertion.getSubject(), "negative object assertion subject"),
                    namedObjectProperty(negativeObjectAssertion.getProperty(), "negative object assertion property"),
                    namedIndividual(negativeObjectAssertion.getObject(), "negative object assertion object"), true, signature);
        }
        if (axiom instanceof OWLDataPropertyAssertionAxiom dataAssertion) {
            return AssertionPayload.dataPropertyAssertion(rendering,
                    namedIndividual(dataAssertion.getSubject(), "data assertion subject"),
                    namedDataProperty(dataAssertion.getProperty(), "data assertion property"),
                    literal(dataAssertion.getObject()), false, signature);
        }
        if (axiom instanceof OWLNegativeDataPropertyAssertionAxiom negativeDataAssertion) {
            return AssertionPayload.dataPropertyAssertion(rendering,
                    namedIndividual(negativeDataAssertion.getSubject(), "negative data assertion subject"),
                    namedDataProperty(negativeDataAssertion.getProperty(), "negative data assertion property"),
                    literal(negativeDataAssertion.getObject()), true, signature);
        }
        if (axiom instanceof OWLSameIndividualAxiom sameIndividual) {
            List<OWLNamedIndividual> individuals = namedPair(sameIndividual.getIndividuals(), "same individual");
            return AssertionPayload.individualIdentity(rendering,
                    individuals.getFirst().getIRI().getIRIString(), individuals.get(1).getIRI().getIRIString(), false, signature);
        }
        if (axiom instanceof OWLDifferentIndividualsAxiom differentIndividuals) {
            List<OWLNamedIndividual> individuals = namedPair(
                    differentIndividuals.getIndividuals(), "different individual");
            return AssertionPayload.individualIdentity(rendering,
                    individuals.getFirst().getIRI().getIRIString(), individuals.get(1).getIRI().getIRIString(), true, signature);
        }
        throw new IllegalArgumentException("unsupported ABox axiom type: " + axiom.getAxiomType().getName());
    }

    private static void compareIndexes(StatementContent candidate, AssertionPayload actual,
            List<Violation> violations) {
        AssertionPayload expected = candidate.assertion();
        if (!expected.functionalSyntax().equals(actual.functionalSyntax())
                || expected.kind() != actual.kind()
                || !expected.signatureIris().equals(actual.signatureIris())
                || !expected.subjectIri().equals(actual.subjectIri())
                || !expected.predicateIri().equals(actual.predicateIri())
                || !expected.objectIri().equals(actual.objectIri())
                || !expected.literal().equals(actual.literal())
                || !expected.classExpressionFunctionalSyntax().equals(actual.classExpressionFunctionalSyntax())
                || !expected.relatedIndividualIri().equals(actual.relatedIndividualIri())) {
            add(violations, "ASSERTION_INDEX_MISMATCH", "assertion",
                    "derived assertion indexes do not match the Functional Syntax axiom");
        }
        Optional<String> actualPredicate = actual.predicateIri();
        Optional<PredicateRef> suppliedPredicate = candidate.predicate();
        if (actualPredicate.isPresent()
                ? suppliedPredicate.isEmpty() || !actualPredicate.get().equals(suppliedPredicate.orElseThrow().iri())
                : suppliedPredicate.isPresent()) {
            add(violations, "PREDICATE_MISMATCH", "predicate",
                    "statement predicate does not match the parsed assertion");
        }
    }

    private static void resolveReferencedIndividuals(AssertionPayload assertion,
            Map<EntityId, Entity> entities, List<Violation> violations) {
        List<String> iris = new ArrayList<>();
        assertion.subjectIri().ifPresent(iris::add);
        assertion.objectIri().ifPresent(iris::add);
        assertion.relatedIndividualIri().ifPresent(iris::add);
        for (String iri : iris) {
            if (entities.values().stream().noneMatch(entity -> entity != null && iri.equals(entity.iri()))) {
                add(violations, "UNKNOWN_ENTITY", "assertion", "named individual IRI is not a referenced entity: " + iri);
            }
        }
    }

    private static void validateAgainstOntology(
            OntologyRevision revision, AssertionPayload assertion, List<Violation> violations) {
        if (revision == null) {
            add(violations, "REQUIRED", "ontology", "ontology revision is required");
            return;
        }
        try (OntologyWorkspace workspace = OntologyWorkspace.create(revision.document().document(),
                revision.document().lockedImports())) {
            OWLOntology ontology = load(workspace.manager(), workspace.source(), workspace.missingImport());
            if (workspace.missingImport().get() != null) {
                add(violations, "IMPORT_MISSING", "ontology.document", "pinned import is not available");
                return;
            }
            OWLProfileReport profile = new OWL2DLProfile().checkOntology(ontology);
            for (OWLProfileViolation violation : profile.getViolations()) {
                add(violations, "OWL_PROFILE_INVALID", "ontology.document", violation.toString());
            }
            validatePropertyKind(ontology, assertion, violations);
            assertion.literal().ifPresent(value -> validateLiteral(value, violations));
        } catch (IllegalArgumentException | IOException | OWLOntologyCreationException exception) {
            add(violations, "OWL_ONTOLOGY_INVALID", "ontology.document", exception.getMessage());
        }
    }

    private static void validatePropertyKind(OWLOntology ontology, AssertionPayload assertion,
            List<Violation> violations) {
        if (!assertion.predicateIri().isPresent()) {
            return;
        }
        String predicate = assertion.predicateIri().orElseThrow();
        boolean object = ontology.getImportsClosure().stream().flatMap(item -> item.getAxioms().stream())
                .filter(OWLDeclarationAxiom.class::isInstance).map(OWLDeclarationAxiom.class::cast)
                .map(OWLDeclarationAxiom::getEntity)
                .anyMatch(entity -> entity instanceof OWLObjectProperty
                        && predicate.equals(entity.getIRI().getIRIString()));
        boolean data = ontology.getImportsClosure().stream().flatMap(item -> item.getAxioms().stream())
                .filter(OWLDeclarationAxiom.class::isInstance).map(OWLDeclarationAxiom.class::cast)
                .map(OWLDeclarationAxiom::getEntity)
                .anyMatch(entity -> entity instanceof OWLDataProperty
                        && predicate.equals(entity.getIRI().getIRIString()));
        if (assertion.objectAssertion() && !object) {
            add(violations, data ? "PROPERTY_KIND_MISMATCH" : "PROPERTY_NOT_DECLARED",
                    "assertion.predicateIri", "predicate is not declared as an object property");
        } else if (assertion.dataAssertion() && !data) {
            add(violations, object ? "PROPERTY_KIND_MISMATCH" : "PROPERTY_NOT_DECLARED",
                    "assertion.predicateIri", "predicate is not declared as a data property");
        }
    }

    private static void validateLiteral(AssertionPayload.LiteralValue literal, List<Violation> violations) {
        try {
            IRI datatype = IRI.create(literal.datatypeIri());
            if (OWL2Datatype.isBuiltIn(datatype)
                    && !OWL2Datatype.getDatatype(datatype).isInLexicalSpace(literal.lexicalValue())) {
                add(violations, "INVALID_LITERAL", "assertion.literal", "literal is outside datatype lexical space");
            }
        } catch (IllegalArgumentException exception) {
            add(violations, "INVALID_LITERAL", "assertion.literal", "literal datatype is not supported");
        }
    }

    private static OWLOntology load(OWLOntologyManager manager, OWLOntologyDocumentSource source,
            AtomicReference<IRI> missingImport) throws OWLOntologyCreationException {
        OWLOntologyLoaderConfiguration configuration = new OWLOntologyLoaderConfiguration()
                .setMissingImportHandlingStrategy(org.semanticweb.owlapi.model.MissingImportHandlingStrategy.THROW_EXCEPTION)
                .setFollowRedirects(false)
                .setConnectionTimeout(1000)
                .setStrict(true);
        return manager.loadOntologyFromOntologyDocument(source, configuration);
    }

    private static String namedIndividual(OWLIndividual individual, String context) {
        if (!individual.isNamed()) {
            throw new IllegalArgumentException(context + " must be a named individual");
        }
        return individual.asOWLNamedIndividual().getIRI().getIRIString();
    }

    private static List<OWLNamedIndividual> namedPair(Set<OWLIndividual> individuals, String context) {
        if (individuals.size() != 2 || individuals.stream().anyMatch(item -> !item.isNamed())) {
            throw new IllegalArgumentException(context + " assertion must contain exactly two named individuals");
        }
        return individuals.stream().map(OWLIndividual::asOWLNamedIndividual)
                .sorted(Comparator.comparing(item -> item.getIRI().getIRIString())).toList();
    }

    private static String namedObjectProperty(OWLObjectPropertyExpression expression, String context) {
        if (expression.isAnonymous()) {
            throw new IllegalArgumentException(context + " must be a named object property");
        }
        return expression.asOWLObjectProperty().getIRI().getIRIString();
    }

    private static String namedDataProperty(OWLDataPropertyExpression expression, String context) {
        if (expression.isAnonymous()) {
            throw new IllegalArgumentException(context + " must be a named data property");
        }
        return expression.asOWLDataProperty().getIRI().getIRIString();
    }

    private static AssertionPayload.LiteralValue literal(OWLLiteral literal) {
        return new AssertionPayload.LiteralValue(literal.getLiteral(),
                literal.getDatatype().getIRI().getIRIString(),
                literal.hasLang() ? Optional.of(literal.getLang()) : Optional.empty());
    }

    private static String render(OWLOntology ontology, org.semanticweb.owlapi.model.OWLObject object) {
        StringWriter writer = new StringWriter();
        FunctionalSyntaxObjectRenderer renderer = new FunctionalSyntaxObjectRenderer(
                ontology, new OWLFunctionalSyntaxOntologyFormat(), writer);
        object.accept(renderer);
        return writer.toString().trim();
    }

    private static String requireSyntax(String source) {
        requireNonNull(source, "functionalSyntax");
        if (source.isBlank()) {
            throw new IllegalArgumentException("functionalSyntax must not be blank");
        }
        return source.trim();
    }

    private static ValidationReport report(String code, String path, String message) {
        return new ValidationReport(List.of(new Violation(code, path,
                Violation.Severity.ERROR, message == null ? code : message)));
    }

    private static void add(List<Violation> violations, String code, String path, String message) {
        violations.add(new Violation(code, path, Violation.Severity.ERROR, message));
    }

    private static final class AssertionWorkspace implements AutoCloseable {
        private final Path directory;
        private final OWLOntologyManager manager;
        private final AtomicReference<IRI> missingImport;
        private final OWLOntologyDocumentSource source;

        private AssertionWorkspace(Path directory, OWLOntologyManager manager,
                AtomicReference<IRI> missingImport, OWLOntologyDocumentSource source) {
            this.directory = directory;
            this.manager = manager;
            this.missingImport = missingImport;
            this.source = source;
        }

        static AssertionWorkspace create(String assertion) throws IOException {
            Path directory = Files.createTempDirectory("mateclaw-assertion-");
            Path missing = directory.resolve("blocked-import.ofn");
            Files.writeString(missing, "", StandardCharsets.UTF_8);
            OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
            manager.clearIRIMappers();
            AtomicReference<IRI> missingImport = new AtomicReference<>();
            manager.addIRIMapper(new BlockedImportMapper(IRI.create(missing.toUri()), missingImport));
            manager.addMissingImportListener(event -> missingImport.compareAndSet(null, event.getImportedOntologyURI()));
            String wrapped = "Ontology(<" + ASSERTION_ONTOLOGY_IRI + ">\n" + assertion + "\n)";
            return new AssertionWorkspace(directory, manager, missingImport,
                    new StringDocumentSource(wrapped, IRI.create(directory.resolve("assertion.ofn").toUri()),
                            new FunctionalSyntaxDocumentFormat(), null));
        }

        OWLOntologyManager manager() { return manager; }
        AtomicReference<IRI> missingImport() { return missingImport; }
        OWLOntologyDocumentSource source() { return source; }

        @Override
        public void close() throws IOException {
            manager.clearOntologies();
            try (var paths = Files.walk(directory)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (IOException ignored) { }
                });
            }
        }
    }

    private static final class OntologyWorkspace implements AutoCloseable {
        private final Path directory;
        private final OWLOntologyManager manager;
        private final AtomicReference<IRI> missingImport;
        private final OWLOntologyDocumentSource source;

        private OntologyWorkspace(Path directory, OWLOntologyManager manager,
                AtomicReference<IRI> missingImport, OWLOntologyDocumentSource source) {
            this.directory = directory;
            this.manager = manager;
            this.missingImport = missingImport;
            this.source = source;
        }

        static OntologyWorkspace create(OntologyDocument document, List<LockedImport> imports) throws IOException {
            if (document.syntax() == OntologyDocumentSyntax.RDF_XML && DOCTYPE.matcher(document.documentText()).find()) {
                throw new IllegalArgumentException("RDF/XML external entities and DOCTYPE declarations are disabled");
            }
            Path directory = Files.createTempDirectory("mateclaw-ontology-");
            Path missing = directory.resolve("blocked-import.ofn");
            Files.writeString(missing, "", StandardCharsets.UTF_8);
            Map<IRI, IRI> mappings = new HashMap<>();
            for (int index = 0; index < imports.size(); index++) {
                LockedImport locked = imports.get(index);
                if (locked.syntax() == OntologyDocumentSyntax.RDF_XML && DOCTYPE.matcher(locked.documentText()).find()) {
                    throw new IllegalArgumentException("RDF/XML external entities and DOCTYPE declarations are disabled in imports");
                }
                Path artifact = directory.resolve("import-" + index
                        + (locked.syntax() == OntologyDocumentSyntax.FUNCTIONAL ? ".ofn" : ".rdf"));
                Files.writeString(artifact, locked.documentText(), StandardCharsets.UTF_8);
                IRI artifactIri = IRI.create(artifact.toUri());
                mappings.put(IRI.create(locked.requestedIri()), artifactIri);
                mappings.put(IRI.create(locked.resolvedOntologyIri()), artifactIri);
            }
            OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
            manager.clearIRIMappers();
            AtomicReference<IRI> missingImport = new AtomicReference<>();
            manager.addIRIMapper(new BlockedImportMapper(mappings, IRI.create(missing.toUri()), missingImport));
            manager.addMissingImportListener(event -> missingImport.compareAndSet(null, event.getImportedOntologyURI()));
            OWLDocumentFormat format = document.syntax() == OntologyDocumentSyntax.FUNCTIONAL
                    ? new FunctionalSyntaxDocumentFormat() : new org.semanticweb.owlapi.formats.RDFXMLDocumentFormat();
            return new OntologyWorkspace(directory, manager, missingImport,
                    new StringDocumentSource(document.documentText(),
                            IRI.create(directory.resolve("root" + (document.syntax() == OntologyDocumentSyntax.FUNCTIONAL ? ".ofn" : ".rdf")).toUri()),
                            format, null));
        }

        OWLOntologyManager manager() { return manager; }
        AtomicReference<IRI> missingImport() { return missingImport; }
        OWLOntologyDocumentSource source() { return source; }

        @Override
        public void close() throws IOException {
            manager.clearOntologies();
            try (var paths = Files.walk(directory)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (IOException ignored) { }
                });
            }
        }
    }

    private static final class BlockedImportMapper implements OWLOntologyIRIMapper {
        private final IRI missingDocument;
        private final AtomicReference<IRI> missingImport;

        private BlockedImportMapper(IRI missingDocument, AtomicReference<IRI> missingImport) {
            this.missingDocument = missingDocument;
            this.missingImport = missingImport;
        }

        private BlockedImportMapper(Map<IRI, IRI> mappings, IRI missingDocument,
                AtomicReference<IRI> missingImport) {
            this.mappings = Map.copyOf(mappings);
            this.missingDocument = missingDocument;
            this.missingImport = missingImport;
        }

        private Map<IRI, IRI> mappings = Map.of();

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
}

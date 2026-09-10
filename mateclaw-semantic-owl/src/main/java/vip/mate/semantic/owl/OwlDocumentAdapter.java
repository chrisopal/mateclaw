package vip.mate.semantic.owl;

import static java.util.Objects.requireNonNull;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.semanticweb.owlapi.formats.FunctionalSyntaxDocumentFormat;
import org.semanticweb.owlapi.formats.RDFXMLDocumentFormat;
import org.semanticweb.owlapi.functional.renderer.FunctionalSyntaxObjectRenderer;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.io.OWLFunctionalSyntaxOntologyFormat;
import org.semanticweb.owlapi.io.OWLOntologyDocumentSource;
import org.semanticweb.owlapi.io.StringDocumentSource;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.MissingImportEvent;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLAnnotationValue;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyIRIMapper;
import org.semanticweb.owlapi.model.OWLOntologyLoaderConfiguration;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.OWLDocumentFormat;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLObject;
import org.semanticweb.owlapi.model.OWLImportsDeclaration;
import org.semanticweb.owlapi.profiles.OWL2DLProfile;
import org.semanticweb.owlapi.profiles.OWLProfileReport;
import org.semanticweb.owlapi.profiles.OWLProfileViolation;
import org.semanticweb.owlapi.vocab.OWL2Datatype;

import vip.mate.semantic.core.ontology.LockedImport;
import vip.mate.semantic.core.ontology.OntologyAxiomChange;
import vip.mate.semantic.core.ontology.OntologyAxiomDescriptor;
import vip.mate.semantic.core.ontology.OntologyAnnotationDescriptor;
import vip.mate.semantic.core.ontology.OntologyDocument;
import vip.mate.semantic.core.ontology.OntologyDocumentException;
import vip.mate.semantic.core.ontology.OntologyDocumentPort;
import vip.mate.semantic.core.ontology.OntologyDocumentSyntax;
import vip.mate.semantic.core.ontology.OntologyValidationReport;
import vip.mate.semantic.core.ontology.OntologyViolation;
import vip.mate.semantic.core.ontology.ParsedOntologyDocument;

/** OWLAPI 5.5.1 implementation of the JDK-only document port. */
public final class OwlDocumentAdapter implements OntologyDocumentPort {
    private static final String ROOT_DOCUMENT_NAME = "root-document";
    private static final String MISSING_DOCUMENT_NAME = "missing-import";
    private static final Pattern ANONYMOUS_NODE = Pattern.compile("_:[A-Za-z0-9_.-]+");

    @Override
    public ParsedOntologyDocument parse(
            OntologyDocument document,
            OntologyDocumentSyntax syntax,
            List<LockedImport> lockedImports) {
        requireNonNull(document, "document");
        requireNonNull(syntax, "syntax");
        if (document.syntax() != syntax) {
            throw new IllegalArgumentException("document syntax and parse syntax must match");
        }
        List<LockedImport> imports = List.copyOf(requireNonNull(lockedImports, "lockedImports"));
        if (imports.stream().map(LockedImport::requestedIri).distinct().count() != imports.size()) {
            throw new OntologyDocumentException(
                    OntologyDocumentException.Kind.IMPORT_DIGEST_MISMATCH,
                    "locked imports contain duplicate requested IRIs");
        }
        validateResolvedImportContent(imports);
        if (!document.importLockDigest().equals(LockedImport.digest(imports))) {
            throw new OntologyDocumentException(
                    OntologyDocumentException.Kind.IMPORT_DIGEST_MISMATCH,
                    "document importLockDigest does not match the supplied locked imports");
        }

        try (DocumentWorkspace workspace = DocumentWorkspace.create(document, imports)) {
            OWLOntology ontology = load(workspace.manager(), workspace.rootSource(), workspace.missingImport(), syntax);
            return describe(document, ontology, imports);
        } catch (OntologyDocumentException exception) {
            throw exception;
        } catch (OWLOntologyCreationException exception) {
            throw parseException(exception, null);
        } catch (IOException exception) {
            throw new OntologyDocumentException(
                    OntologyDocumentException.Kind.PARSE_ERROR,
                    "unable to prepare the isolated OWL document workspace", exception);
        }
    }

    @Override
    public OntologyValidationReport validateDl(ParsedOntologyDocument document) {
        requireNonNull(document, "document");
        List<OntologyViolation> violations = new ArrayList<>();
        try (DocumentWorkspace workspace = DocumentWorkspace.create(document.document(), document.lockedImports())) {
            OWLOntology ontology = load(
                    workspace.manager(), workspace.rootSource(), workspace.missingImport(), document.document().syntax());
            Set<OWLOntology> closure = ontology.getImportsClosure();
            // OWL 2 DL structural restrictions apply to the root axiom closure.
            // Checking each imported document in isolation loses declarations supplied by its importer.
            OWLOntologyManager profileManager = OWLManager.createOWLOntologyManager();
            OWLOntology profileOntology = profileManager.createOntology(ontology.getOntologyID());
            profileManager.addAxioms(profileOntology, closure.stream().flatMap(OWLOntology::axioms));
            closure.stream().flatMap(OWLOntology::annotations).distinct().forEach(annotation ->
                    profileManager.applyChange(new org.semanticweb.owlapi.model.AddOntologyAnnotation(profileOntology, annotation)));
            OWLProfileReport report = new OWL2DLProfile().checkOntology(profileOntology);
            for (OWLProfileViolation violation : report.getViolations()) {
                violations.add(new OntologyViolation("PROFILE_VIOLATION", violationPath(violation),
                        OntologyViolation.Severity.ERROR, violation.toString()));
            }
            for (OWLOntology member : closure) collectLiteralViolations(member, violations);
        } catch (OntologyDocumentException exception) {
            violations.add(new OntologyViolation(
                    exception.kind().name(), "$", OntologyViolation.Severity.ERROR, exception.getMessage()));
        } catch (OWLOntologyCreationException | IOException exception) {
            violations.add(new OntologyViolation(
                    "PARSE_ERROR", "$", OntologyViolation.Severity.ERROR, exception.getMessage()));
        }
        return new OntologyValidationReport("OWL 2 DL", violations);
    }

    @Override
    public Set<String> classIris(ParsedOntologyDocument document) {
        requireNonNull(document, "document");
        try (DocumentWorkspace workspace = DocumentWorkspace.create(document.document(), document.lockedImports())) {
            OWLOntology ontology = load(
                    workspace.manager(), workspace.rootSource(), workspace.missingImport(), document.document().syntax());
            return ontology.getImportsClosure().stream()
                    .flatMap(member -> member.classesInSignature())
                    .map(entity -> entity.getIRI().getIRIString())
                    .collect(Collectors.toUnmodifiableSet());
        } catch (OntologyDocumentException exception) {
            throw exception;
        } catch (OWLOntologyCreationException | IOException exception) {
            throw new OntologyDocumentException(
                    OntologyDocumentException.Kind.PARSE_ERROR,
                    "unable to read class signature from OWL document", exception);
        }
    }

    @Override
    public Map<String,List<String>> termKinds(ParsedOntologyDocument document) {
        try (DocumentWorkspace workspace=DocumentWorkspace.create(document.document(),document.lockedImports())) {
            var ontology=load(workspace.manager(),workspace.rootSource(),workspace.missingImport(),document.document().syntax());
            Map<String,Set<String>> kinds=new TreeMap<>();
            ontology.getImportsClosure().forEach(member -> member.signature().forEach(entity ->
                kinds.computeIfAbsent(entity.getIRI().getIRIString(),ignored -> new TreeSet<>()).add(entity.getEntityType().getName())));
            Map<String,List<String>> result=new LinkedHashMap<>();kinds.forEach((iri,types)->result.put(iri,List.copyOf(types)));
            return Collections.unmodifiableMap(result);
        } catch (OWLOntologyCreationException | IOException exception) {
            throw new OntologyDocumentException(OntologyDocumentException.Kind.PARSE_ERROR,"Unable to read typed signature",exception);
        }
    }

    @Override
    public Map<String, List<String>> termLabels(ParsedOntologyDocument document) {
        requireNonNull(document, "document");
        try (DocumentWorkspace workspace = DocumentWorkspace.create(document.document(), document.lockedImports())) {
            OWLOntology ontology = load(
                    workspace.manager(), workspace.rootSource(), workspace.missingImport(), document.document().syntax());
            Set<String> labelProperties = Set.of(
                    "http://www.w3.org/2000/01/rdf-schema#label",
                    "http://www.w3.org/2004/02/skos/core#prefLabel",
                    "http://www.w3.org/2004/02/skos/core#altLabel");
            Map<String, Set<String>> labels = new TreeMap<>();
            for (OWLOntology member : ontology.getImportsClosure()) {
                member.getAxioms().stream()
                        .filter(OWLAnnotationAssertionAxiom.class::isInstance)
                        .map(OWLAnnotationAssertionAxiom.class::cast)
                        .forEach(assertion -> {
                            if (!(assertion.getSubject() instanceof IRI subject)
                                    || !labelProperties.contains(assertion.getProperty().getIRI().getIRIString())
                                    || !(assertion.getValue() instanceof OWLLiteral literal)) {
                                return;
                            }
                            labels.computeIfAbsent(subject.getIRIString(), ignored -> new TreeSet<>())
                                    .add(literal.getLiteral());
                        });
            }
            Map<String, List<String>> result = new LinkedHashMap<>();
            labels.forEach((iri, values) -> result.put(iri, List.copyOf(values)));
            return Collections.unmodifiableMap(result);
        } catch (OntologyDocumentException exception) {
            throw exception;
        } catch (OWLOntologyCreationException | IOException exception) {
            throw new OntologyDocumentException(
                    OntologyDocumentException.Kind.PARSE_ERROR,
                    "unable to read annotation labels from OWL document", exception);
        }
    }

    @Override
    public ParsedOntologyDocument applyAxiomChanges(
            ParsedOntologyDocument document,
            List<OntologyAxiomChange> commands) {
        requireNonNull(document, "document");
        List<OntologyAxiomChange> changes = List.copyOf(requireNonNull(commands, "commands"));
        try (DocumentWorkspace workspace = DocumentWorkspace.create(document.document(), document.lockedImports())) {
            OWLOntology ontology = load(
                    workspace.manager(), workspace.rootSource(), workspace.missingImport(), document.document().syntax());
            Map<String, OWLAxiom> axiomById = indexAxioms(ontology, document.document().revisionId());
            List<OWLAxiom> additions = new ArrayList<>();
            for (OntologyAxiomChange change : changes) {
                if (change instanceof OntologyAxiomChange.Remove remove) {
                    OWLAxiom axiom = axiomById.get(remove.axiomId());
                    if (axiom == null) {
                        throw new OntologyDocumentException(
                                OntologyDocumentException.Kind.CHANGE_ERROR,
                                "unknown axiomId: " + remove.axiomId());
                    }
                    ontology.removeAxiom(axiom);
                } else if (change instanceof OntologyAxiomChange.Add add) {
                    List<OWLAxiom> parsed = parseAxiom(
                            workspace.manager(), add.functionalSyntax(), document.document().documentText());
                    if (parsed.size() != 1) {
                        throw new OntologyDocumentException(
                                OntologyDocumentException.Kind.CHANGE_ERROR,
                                "an Add change must contain exactly one axiom; parsed " + parsed.size());
                    }
                    additions.add(parsed.getFirst());
                }
            }
            ontology.addAxioms(additions);
            scopeAnonymousIndividuals(ontology);
            byte[] exported = save(ontology, document.document().syntax());
            String text = new String(exported, StandardCharsets.UTF_8);
            OntologyDocument changedDocument = OntologyDocument.fromText(
                    document.document().ontologyId(),
                    document.document().revisionId(),
                    document.document().ontologyIri(),
                    document.document().versionIri(),
                    document.document().syntax(),
                    text,
                    document.document().importLockDigest());
            return describe(changedDocument, ontology, document.lockedImports());
        } catch (OntologyDocumentException exception) {
            throw exception;
        } catch (OWLOntologyCreationException | IOException exception) {
            throw new OntologyDocumentException(
                    OntologyDocumentException.Kind.CHANGE_ERROR,
                    "unable to apply OWL axiom changes", exception);
        }
    }

    @Override
    public byte[] export(ParsedOntologyDocument document, OntologyDocumentSyntax syntax) {
        requireNonNull(document, "document");
        requireNonNull(syntax, "syntax");
        try (DocumentWorkspace workspace = DocumentWorkspace.create(document.document(), document.lockedImports())) {
            OWLOntology ontology = load(
                    workspace.manager(), workspace.rootSource(), workspace.missingImport(), document.document().syntax());
            return save(ontology, syntax);
        } catch (OntologyDocumentException exception) {
            throw exception;
        } catch (OWLOntologyCreationException | IOException exception) {
            throw new OntologyDocumentException(
                    OntologyDocumentException.Kind.EXPORT_ERROR,
                    "unable to export OWL document as " + syntax, exception);
        }
    }

    private static OWLOntology load(
            OWLOntologyManager manager,
            OWLOntologyDocumentSource source,
            AtomicReference<IRI> missingImport,
            OntologyDocumentSyntax syntax) throws OWLOntologyCreationException {
        OWLOntologyLoaderConfiguration configuration = new OWLOntologyLoaderConfiguration()
                .setMissingImportHandlingStrategy(
                        org.semanticweb.owlapi.model.MissingImportHandlingStrategy.THROW_EXCEPTION)
                .setFollowRedirects(false)
                .setConnectionTimeout(1000)
                .setLoadAnnotationAxioms(true)
                // OWLAPI's RDF/XML parser drops legal anonymous restrictions in strict mode
                // (notably data ranges/cardinalities); Functional Syntax remains strict.
                .setStrict(syntax == OntologyDocumentSyntax.FUNCTIONAL)
                .setReportStackTraces(true);
        try {
            OWLOntology root = manager.loadOntologyFromOntologyDocument(source, configuration);
            // Anonymous labels are local to each ontology document, including the locked imports.
            for (OWLOntology item : root.getImportsClosure()) {
                RdfClassExpressions.normalize(item);
                scopeAnonymousIndividuals(item);
            }
            return root;
        } catch (OWLOntologyCreationException exception) {
            if (missingImport.get() != null) {
                throw new OntologyDocumentException(
                        OntologyDocumentException.Kind.IMPORT_MISSING,
                        "locked imports do not contain " + missingImport.get(), exception);
            }
            throw exception;
        }
    }

    private static ParsedOntologyDocument describe(
            OntologyDocument document,
            OWLOntology ontology,
            List<LockedImport> imports) {
        Optional<String> parsedOntologyIriOptional = ontology.getOntologyID().getOntologyIRI()
                .map(IRI::getIRIString)
                ;
        String parsedOntologyIri = parsedOntologyIriOptional.orElse(document.ontologyIri());
        boolean managedUnboundIri = document.ontologyIri().startsWith("urn:mateclaw:unbound:");
        if (!managedUnboundIri && !document.ontologyIri().equals(parsedOntologyIri)) {
            throw new OntologyDocumentException(
                    OntologyDocumentException.Kind.PARSE_ERROR,
                    "document ontologyIri " + document.ontologyIri()
                            + " does not match parsed ontology IRI " + parsedOntologyIri);
        }
        Optional<String> parsedVersionIri = ontology.getOntologyID().getVersionIRI().map(IRI::getIRIString);
        if (!managedUnboundIri && !document.versionIri().equals(parsedVersionIri)) {
            throw new OntologyDocumentException(
                    OntologyDocumentException.Kind.PARSE_ERROR,
                    "document versionIri does not match the parsed ontology version IRI");
        }
        validateImportLock(ontology, imports);
        List<OWLAxiom> axioms = sortedAxioms(ontology);
        List<OntologyAxiomDescriptor> descriptors = new ArrayList<>(axioms.size());
        for (OWLAxiom axiom : axioms) {
            String rendering = renderAxiom(ontology, axiom);
            String axiomId = stableAxiomId(document.revisionId(), rendering);
            descriptors.add(new OntologyAxiomDescriptor(
                    axiomId,
                    axiom.getAxiomType().getName(),
                    rendering,
                    axiom.getSignature().stream().map(entity -> entity.getIRI().getIRIString())
                            .collect(Collectors.toCollection(java.util.LinkedHashSet::new)),
                    axiom.annotations().map(OwlDocumentAdapter::describeAnnotation).toList(),
                    axiom.isLogicalAxiom()));
        }
        List<String> importedIris = ontology.importsDeclarations()
                .map(OWLImportsDeclaration::getIRI)
                .map(IRI::getIRIString)
                .sorted()
                .toList();
        List<OntologyAnnotationDescriptor> ontologyAnnotations = ontology.annotations()
                .map(OwlDocumentAdapter::describeAnnotation)
                .toList();
        OntologyDocument documentWithParsedIdentity = managedUnboundIri && parsedOntologyIriOptional.isPresent()
                ? document.withOntologyIri(parsedOntologyIri, parsedVersionIri)
                : document;
        return new ParsedOntologyDocument(
                documentWithParsedIdentity,
                parsedOntologyIri,
                ontology.getOntologyID().getVersionIRI().map(IRI::getIRIString),
                importedIris,
                imports,
                ontologyAnnotations,
                descriptors);
    }

    private static void validateImportLock(OWLOntology ontology, List<LockedImport> imports) {
        Map<String, LockedImport> locks = imports.stream()
                .collect(Collectors.toMap(LockedImport::requestedIri, value -> value, (left, right) -> left));
        Set<String> resolvedOntologyIris = ontology.getImportsClosure().stream()
                .map(member -> member.getOntologyID().getOntologyIRI().map(IRI::getIRIString).orElse(null))
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        Set<String> requestedIris = ontology.getImportsClosure().stream()
                .flatMap(member -> member.importsDeclarations())
                .map(OWLImportsDeclaration::getIRI)
                .map(IRI::getIRIString)
                .collect(Collectors.toSet());
        for (String requestedIri : requestedIris) {
            LockedImport lock = locks.get(requestedIri);
            if (lock == null) {
                throw new OntologyDocumentException(
                        OntologyDocumentException.Kind.IMPORT_MISSING,
                        "import is not present in the pinned lock: " + requestedIri);
            }
            if (!resolvedOntologyIris.contains(lock.resolvedOntologyIri())) {
                throw new OntologyDocumentException(
                        OntologyDocumentException.Kind.IMPORT_MISSING,
                        "pinned import did not resolve to " + lock.resolvedOntologyIri());
            }
            if (lock.versionIri().isPresent()) {
                boolean versionMatches = ontology.getImportsClosure().stream()
                        .filter(member -> member.getOntologyID().getOntologyIRI()
                                .map(IRI::getIRIString).filter(lock.resolvedOntologyIri()::equals).isPresent())
                        .anyMatch(member -> member.getOntologyID().getVersionIRI()
                                .map(IRI::getIRIString).filter(lock.versionIri().get()::equals).isPresent());
                if (!versionMatches) {
                    throw new OntologyDocumentException(
                            OntologyDocumentException.Kind.IMPORT_MISSING,
                            "pinned import version does not match " + lock.versionIri().get());
                }
            }
        }
    }

    private static List<OWLAxiom> sortedAxioms(OWLOntology ontology) {
        return ontology.getAxioms().stream()
                .sorted(Comparator.comparing(axiom -> renderAxiom(ontology, axiom)))
                .toList();
    }

    private static Map<String, OWLAxiom> indexAxioms(OWLOntology ontology, String revisionId) {
        Map<String, OWLAxiom> result = new HashMap<>();
        for (OWLAxiom axiom : sortedAxioms(ontology)) {
            String id = stableAxiomId(revisionId, renderAxiom(ontology, axiom));
            result.put(id, axiom);
        }
        return result;
    }

    private static String stableAxiomId(String revisionId, String rendering) {
        return OntologyDocument.sha256(revisionId + "\u0000" + rendering);
    }

    private static void scopeAnonymousIndividuals(OWLOntology ontology) {
        var manager=ontology.getOWLOntologyManager();
        String scope="mc"+OntologyDocument.sha256(ontology.getOntologyID().getOntologyIRI()
                .map(IRI::getIRIString).orElse("root"))+"_";
        var duplicator=new org.semanticweb.owlapi.util.OWLObjectDuplicator(manager) {
            @Override public org.semanticweb.owlapi.model.OWLAnonymousIndividual visit(org.semanticweb.owlapi.model.OWLAnonymousIndividual value) {
                String label=value.getID().getID().replaceFirst("^_:","");
                return manager.getOWLDataFactory().getOWLAnonymousIndividual(label.startsWith(scope)?label:scope+label);
            }
        };
        var originals=new ArrayList<>(ontology.getAxioms());
        for(var axiom:originals) {
            OWLAxiom scoped=duplicator.duplicateObject(axiom);
            if(!scoped.equals(axiom)){manager.removeAxiom(ontology,axiom);manager.addAxiom(ontology,scoped);}
        }
        for(var annotation:new ArrayList<>(ontology.getAnnotations())) {
            OWLAnnotation scoped=duplicator.duplicateObject(annotation);
            if(!scoped.equals(annotation)) {
                manager.applyChange(new org.semanticweb.owlapi.model.RemoveOntologyAnnotation(ontology,annotation));
                manager.applyChange(new org.semanticweb.owlapi.model.AddOntologyAnnotation(ontology,scoped));
            }
        }
    }

    private static void validateResolvedImportContent(List<LockedImport> imports) {
        Map<String, String> contentByResolvedIri = new HashMap<>();
        for (LockedImport locked : imports) {
            String previous = contentByResolvedIri.putIfAbsent(
                    locked.resolvedOntologyIri(), locked.contentDigest());
            if (previous != null && !previous.equals(locked.contentDigest())) {
                throw new OntologyDocumentException(
                        OntologyDocumentException.Kind.IMPORT_DIGEST_MISMATCH,
                        "multiple pinned imports resolve to " + locked.resolvedOntologyIri()
                                + " with different content");
            }
        }
    }

    private static List<OWLAxiom> parseAxiom(
            OWLOntologyManager manager,
            String functionalSyntax,
            String originalDocument) throws OWLOntologyCreationException {
        String wrapped = prefixBlock(originalDocument)
                + "Ontology(<urn:mateclaw:axiom-change>\n" + functionalSyntax + "\n)";
        OWLOntology changes = manager.loadOntologyFromOntologyDocument(
                new StringDocumentSource(wrapped, IRI.create("urn:mateclaw:axiom-change-document")),
                new OWLOntologyLoaderConfiguration()
                        .setMissingImportHandlingStrategy(
                        org.semanticweb.owlapi.model.MissingImportHandlingStrategy.THROW_EXCEPTION)
                        .setStrict(true));
        if (changes.importsDeclarations().findAny().isPresent()) {
            manager.removeOntology(changes);
            throw new OntologyDocumentException(
                    OntologyDocumentException.Kind.CHANGE_ERROR,
                    "an Add change must contain one axiom only; imports are not allowed");
        }
        if (changes.annotations().findAny().isPresent()) {
            manager.removeOntology(changes);
            throw new OntologyDocumentException(
                    OntologyDocumentException.Kind.CHANGE_ERROR,
                    "an Add change must contain one axiom only; ontology annotations are not allowed");
        }
        List<OWLAxiom> result = new ArrayList<>(changes.getAxioms());
        manager.removeOntology(changes);
        return result;
    }

    private static byte[] save(OWLOntology ontology, OntologyDocumentSyntax syntax) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            OWLDocumentFormat format = syntax == OntologyDocumentSyntax.FUNCTIONAL
                    ? new FunctionalSyntaxDocumentFormat()
                    : new RDFXMLDocumentFormat();
            ontology.getOWLOntologyManager().saveOntology(ontology, format, output);
            return output.toByteArray();
        } catch (Exception exception) {
            throw new OntologyDocumentException(
                    OntologyDocumentException.Kind.EXPORT_ERROR,
                    "OWLAPI could not serialize the ontology", exception);
        }
    }

    private static String renderAxiom(OWLOntology ontology, OWLAxiom axiom) {
        StringWriter writer = new StringWriter();
        FunctionalSyntaxObjectRenderer renderer = new FunctionalSyntaxObjectRenderer(
                ontology, new OWLFunctionalSyntaxOntologyFormat(), writer);
        axiom.accept(renderer);
        return writer.toString().trim();
    }

    private static OntologyAnnotationDescriptor describeAnnotation(OWLAnnotation annotation) {
        return new OntologyAnnotationDescriptor(
                annotation.getProperty().getIRI().getIRIString(),
                annotationValueRendering(annotation.getValue()),
                annotation.annotations().map(OwlDocumentAdapter::describeAnnotation).toList());
    }

    private static String annotationValueRendering(OWLAnnotationValue value) {
        if (value instanceof IRI iri) {
            return "<" + iri.getIRIString() + ">";
        }
        if (value instanceof OWLLiteral literal) {
            return literal.toString();
        }
        return value.toString();
    }

    private static String violationPath(OWLProfileViolation violation) {
        if (violation.getAxiom() == null) {
            return "$";
        }
        return violation.getAxiom().getAxiomType().getName();
    }

    private static void collectLiteralViolations(OWLOntology ontology, List<OntologyViolation> violations) {
        for (OWLAxiom axiom : ontology.getAxioms()) {
            Deque<OWLObject> pending = new ArrayDeque<>();
            pending.add(axiom);
            Set<OWLObject> visited = new HashSet<>();
            while (!pending.isEmpty()) {
                OWLObject object = pending.removeFirst();
                if (!visited.add(object)) {
                    continue;
                }
                if (object instanceof OWLLiteral literal) {
                    IRI datatypeIri = literal.getDatatype().getIRI();
                    if (OWL2Datatype.isBuiltIn(datatypeIri)) {
                        OWL2Datatype datatype = OWL2Datatype.getDatatype(datatypeIri);
                        if (!datatype.isInLexicalSpace(literal.getLiteral())) {
                            violations.add(new OntologyViolation(
                                    "INVALID_LITERAL", axiom.getAxiomType().getName(),
                                    OntologyViolation.Severity.ERROR,
                                    "literal is not in the lexical space of " + datatypeIri.getIRIString()));
                        }
                    }
                }
                object.components().filter(OWLObject.class::isInstance)
                        .map(OWLObject.class::cast).forEach(pending::addLast);
            }
        }
    }

    private static OntologyDocumentException parseException(
            OWLOntologyCreationException exception,
            IRI missingImport) {
        return new OntologyDocumentException(
                missingImport == null
                        ? OntologyDocumentException.Kind.PARSE_ERROR
                        : OntologyDocumentException.Kind.IMPORT_MISSING,
                missingImport == null ? "OWL document could not be parsed" : "missing locked import " + missingImport,
                exception);
    }

    private static String prefixBlock(String text) {
        return text.lines()
                .map(String::trim)
                .filter(line -> line.startsWith("Prefix(") && line.endsWith(")"))
                .collect(Collectors.joining("\n", "", "\n"));
    }

    private static final class DocumentWorkspace implements AutoCloseable {
        private final Path directory;
        private final OWLOntologyManager manager;
        private final AtomicReference<IRI> missingImport;
        private final OWLOntologyDocumentSource rootSource;

        private DocumentWorkspace(
                Path directory,
                OWLOntologyManager manager,
                AtomicReference<IRI> missingImport,
                OWLOntologyDocumentSource rootSource) {
            this.directory = directory;
            this.manager = manager;
            this.missingImport = missingImport;
            this.rootSource = rootSource;
        }

        static DocumentWorkspace create(OntologyDocument document, List<LockedImport> imports) throws IOException {
            String rootText = RdfXmlInput.normalize(document.documentText(), document.syntax());
            List<String> importTexts = imports.stream().map(lock -> RdfXmlInput.normalize(lock.documentText(), lock.syntax())).toList();
            Path directory = Files.createTempDirectory("mateclaw-owl-");
            Path root = directory.resolve(ROOT_DOCUMENT_NAME + extension(document.syntax()));
            Files.writeString(root, rootText, StandardCharsets.UTF_8);
            Path missing = directory.resolve(MISSING_DOCUMENT_NAME);
            Files.writeString(missing, "", StandardCharsets.UTF_8);
            Map<IRI, IRI> mappings = new HashMap<>();
            for (int index = 0; index < imports.size(); index++) {
                LockedImport locked = imports.get(index);
                Path artifact = directory.resolve("import-" + index + extension(locked.syntax()));
                Files.writeString(artifact, importTexts.get(index), StandardCharsets.UTF_8);
                IRI artifactIri = IRI.create(artifact.toUri());
                mappings.put(IRI.create(locked.requestedIri()), artifactIri);
                mappings.put(IRI.create(locked.resolvedOntologyIri()), artifactIri);
            }
            OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
            manager.getOntologyConfigurator().withRemapAllAnonymousIndividualsIds(false).withSaveIdsForAllAnonymousIndividuals(true);
            manager.clearIRIMappers();
            AtomicReference<IRI> missingImport = new AtomicReference<>();
            manager.addIRIMapper(new LockedIriMapper(mappings, IRI.create(missing.toUri()), missingImport));
            manager.addMissingImportListener((MissingImportEvent event) -> missingImport.compareAndSet(null, event.getImportedOntologyURI()));
            OntologyDocumentSyntax syntax = document.syntax();
            OWLDocumentFormat format = syntax == OntologyDocumentSyntax.FUNCTIONAL
                    ? new FunctionalSyntaxDocumentFormat()
                    : new RDFXMLDocumentFormat();
            OWLOntologyDocumentSource source = new StringDocumentSource(
                    rootText, IRI.create(root.toUri()), format, null);
            return new DocumentWorkspace(directory, manager, missingImport, source);
        }

        OWLOntologyManager manager() {
            return manager;
        }

        AtomicReference<IRI> missingImport() {
            return missingImport;
        }

        OWLOntologyDocumentSource rootSource() {
            return rootSource;
        }

        @Override
        public void close() throws IOException {
            manager.clearOntologies();
            try (var paths = Files.walk(directory)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException exception) {
                        // Best effort cleanup; the ontology was already detached from the manager.
                    }
                });
            }
        }
    }

    private static final class LockedIriMapper implements OWLOntologyIRIMapper {
        private final Map<IRI, IRI> mappings;
        private final IRI missingDocument;
        private final AtomicReference<IRI> missingImport;

        private LockedIriMapper(Map<IRI, IRI> mappings, IRI missingDocument, AtomicReference<IRI> missingImport) {
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
}

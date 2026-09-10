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
import java.util.Collection;
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
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLDataPropertyCharacteristicAxiom;
import org.semanticweb.owlapi.model.OWLDataPropertyDomainAxiom;
import org.semanticweb.owlapi.model.OWLDataPropertyRangeAxiom;
import org.semanticweb.owlapi.model.OWLDataRange;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLDatatype;
import org.semanticweb.owlapi.model.OWLDataAllValuesFrom;
import org.semanticweb.owlapi.model.OWLDataExactCardinality;
import org.semanticweb.owlapi.model.OWLDataCardinalityRestriction;
import org.semanticweb.owlapi.model.OWLDataIntersectionOf;
import org.semanticweb.owlapi.model.OWLDataMaxCardinality;
import org.semanticweb.owlapi.model.OWLDataMinCardinality;
import org.semanticweb.owlapi.model.OWLDataSomeValuesFrom;
import org.semanticweb.owlapi.model.OWLDataUnionOf;
import org.semanticweb.owlapi.model.OWLDataComplementOf;
import org.semanticweb.owlapi.model.OWLNaryDataRange;
import org.semanticweb.owlapi.model.OWLDeclarationAxiom;
import org.semanticweb.owlapi.model.OWLDisjointClassesAxiom;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLEquivalentClassesAxiom;
import org.semanticweb.owlapi.model.OWLInverseObjectPropertiesAxiom;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLObjectPropertyCharacteristicAxiom;
import org.semanticweb.owlapi.model.OWLObjectPropertyDomainAxiom;
import org.semanticweb.owlapi.model.OWLObjectPropertyRangeAxiom;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLAnnotationProperty;
import org.semanticweb.owlapi.model.OWLSubClassOfAxiom;
import org.semanticweb.owlapi.model.OWLSubPropertyChainOfAxiom;
import org.semanticweb.owlapi.model.OWLObjectAllValuesFrom;
import org.semanticweb.owlapi.model.OWLObjectComplementOf;
import org.semanticweb.owlapi.model.OWLObjectExactCardinality;
import org.semanticweb.owlapi.model.OWLObjectCardinalityRestriction;
import org.semanticweb.owlapi.model.OWLObjectIntersectionOf;
import org.semanticweb.owlapi.model.OWLObjectMaxCardinality;
import org.semanticweb.owlapi.model.OWLObjectMinCardinality;
import org.semanticweb.owlapi.model.OWLObjectSomeValuesFrom;
import org.semanticweb.owlapi.model.OWLObjectUnionOf;
import org.semanticweb.owlapi.model.OWLPropertyExpression;
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
import vip.mate.semantic.core.ontology.OntologyDisplayProjection;
import vip.mate.semantic.core.ontology.OntologyDisplayProjection.AxiomRef;
import vip.mate.semantic.core.ontology.OntologyDisplayProjection.Coverage;
import vip.mate.semantic.core.ontology.OntologyDisplayProjection.Edge;
import vip.mate.semantic.core.ontology.OntologyDisplayProjection.Expression;
import vip.mate.semantic.core.ontology.OntologyDisplayProjection.ExpressionOperand;
import vip.mate.semantic.core.ontology.OntologyDisplayProjection.Label;
import vip.mate.semantic.core.ontology.OntologyDisplayProjection.Node;
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
    public OntologyDisplayProjection project(ParsedOntologyDocument document, int limit) {
        requireNonNull(document, "document");
        if (limit < 1 || limit > 2000) {
            throw new IllegalArgumentException("projection limit must be between 1 and 2000");
        }
        try (DocumentWorkspace workspace = DocumentWorkspace.create(document.document(), document.lockedImports())) {
            OWLOntology root = load(
                    workspace.manager(), workspace.rootSource(), workspace.missingImport(), document.document().syntax());
            List<ProjectionEntry> entries = projectionEntries(root, document, document.lockedImports());
            int total = entries.size();
            List<ProjectionEntry> returned = entries.stream().limit(limit).toList();
            Map<String, MutableNode> nodeMap = new TreeMap<>();
            Map<String, List<Label>> labelsByIri = new TreeMap<>();
            List<Edge> edges = new ArrayList<>();
            Set<String> graphCappedRefs = new HashSet<>();
            Set<String> unappliedLabelRefs = new HashSet<>();
            Set<String> expressionCappedRefs = new HashSet<>();
            List<Expression> expressions = new ArrayList<>();
            ExpressionState expressionState = new ExpressionState(
                    limit, 32, expressions, expressionCappedRefs, graphCappedRefs);
            for (ProjectionEntry entry : returned) {
                if (entry.imported()) {
                    continue;
                }
                OWLAxiom axiom = entry.axiom();
                String refId = entry.refId();
                if (axiom instanceof OWLDeclarationAxiom declaration) {
                    addEntity(nodeMap, declaration.getEntity(), refId);
                } else if (axiom instanceof OWLAnnotationAssertionAxiom assertion
                        && isDisplayLabel(assertion)) {
                    if (assertion.getSubject() instanceof IRI subject
                            && assertion.getValue() instanceof OWLLiteral literal) {
                        labelsByIri.computeIfAbsent(subject.getIRIString(), ignored -> new ArrayList<>()).add(new Label(
                                literal.getLiteral(), literal.getLang(), refId));
                    }
                } else if (axiom instanceof OWLSubClassOfAxiom subclass) {
                    addNamedClassEdge(
                            nodeMap, edges, subclass.getSubClass(), subclass.getSuperClass(),
                            "subClassOf", "", refId);
                } else if (axiom instanceof OWLObjectPropertyDomainAxiom domain) {
                    addNamedPropertyClassEdge(
                            nodeMap, edges, domain.getProperty(), domain.getDomain(), "domain", refId);
                } else if (axiom instanceof OWLDataPropertyDomainAxiom domain) {
                    addNamedPropertyClassEdge(
                            nodeMap, edges, domain.getProperty(), domain.getDomain(), "domain", refId);
                } else if (axiom instanceof OWLObjectPropertyRangeAxiom range) {
                    addNamedPropertyClassEdge(
                            nodeMap, edges, range.getProperty(), range.getRange(), "range", refId);
                } else if (axiom instanceof OWLDataPropertyRangeAxiom range) {
                    addNamedPropertyDataRangeEdge(
                            nodeMap, edges, range.getProperty(), range.getRange(), "range", refId);
                } else if (axiom instanceof OWLEquivalentClassesAxiom equivalent) {
                    if (equivalent.getClassExpressions().size() > limit) {
                        graphCappedRefs.add(refId);
                    }
                    addNamedClassGroup(
                            nodeMap, edges, equivalent.getClassExpressions(), "equivalentClasses", refId, limit);
                } else if (axiom instanceof OWLDisjointClassesAxiom disjoint) {
                    if (disjoint.getClassExpressions().size() > limit) {
                        graphCappedRefs.add(refId);
                    }
                    addNamedClassGroup(
                            nodeMap, edges, disjoint.getClassExpressions(), "disjointClasses", refId, limit);
                } else if (axiom instanceof OWLInverseObjectPropertiesAxiom inverse) {
                    addNamedPropertyEdge(
                            nodeMap, edges, inverse.getFirstProperty(), inverse.getSecondProperty(),
                            "inverseOf", refId);
                } else if (axiom instanceof OWLObjectPropertyCharacteristicAxiom characteristic) {
                    addNamedPropertyFeature(nodeMap, characteristic.getProperty(), refId,
                            axiom.getAxiomType().getName());
                } else if (axiom instanceof OWLDataPropertyCharacteristicAxiom characteristic) {
                    addNamedPropertyFeature(nodeMap, characteristic.getProperty(), refId,
                            axiom.getAxiomType().getName());
                }
                projectExpressions(axiom, refId, nodeMap, expressionState, root);
            }

            labelsByIri.forEach((iri, labels) -> {
                List<MutableNode> targets = nodeMap.values().stream().filter(node -> node.iri.equals(iri)).toList();
                if (targets.isEmpty()) {
                    labels.stream().map(Label::axiomId).forEach(unappliedLabelRefs::add);
                }
                targets.forEach(node -> {
                    labels.stream().sorted(Comparator.comparing(Label::value)
                                    .thenComparing(Label::language).thenComparing(Label::axiomId))
                            .forEach(label -> {
                                node.labels.add(label);
                                node.axiomIds.add(label.axiomId());
                            });
                });
            });

            if (nodeMap.size() > limit || edges.size() > limit) {
                List<String> droppedNodeKeys = nodeMap.keySet().stream().skip(limit).toList();
                droppedNodeKeys.stream().map(nodeMap::get).filter(java.util.Objects::nonNull)
                        .flatMap(node -> node.axiomIds.stream()).forEach(graphCappedRefs::add);
                droppedNodeKeys.forEach(nodeMap::remove);
                if (edges.size() > limit) {
                    edges.subList(limit, edges.size()).stream()
                            .map(Edge::axiomId).forEach(graphCappedRefs::add);
                    edges.subList(limit, edges.size()).clear();
                }
                Set<String> nodeIds = nodeMap.values().stream().map(node -> node.id).collect(Collectors.toSet());
                edges.removeIf(edge -> {
                    boolean dropped = !nodeIds.contains(edge.source()) || !nodeIds.contains(edge.target());
                    if (dropped) {
                        graphCappedRefs.add(edge.axiomId());
                    }
                    return dropped;
                });
            }

            Set<String> nodeIds = nodeMap.values().stream().map(node -> node.id).collect(Collectors.toSet());
            Set<String> expressionIds = expressions.stream().map(Expression::id).collect(Collectors.toSet());
            expressions = new ArrayList<>(sanitizeExpressions(
                    expressions, nodeIds, expressionIds, graphCappedRefs, expressionCappedRefs));

            List<Node> nodes = nodeMap.values().stream()
                    .map(MutableNode::freeze)
                    .sorted(Comparator.comparing(Node::id))
                    .toList();
            edges.sort(Comparator.comparing(Edge::id));
            List<AxiomRef> refs = returned.stream()
                    .map(entry -> entry.toRef(graphCappedRefs.contains(entry.refId()),
                            unappliedLabelRefs.contains(entry.refId()),
                            expressionCappedRefs.contains(entry.refId())))
                    .toList();
            expressions.sort(Comparator.comparing(Expression::id));
            return new OntologyDisplayProjection(
                    OntologyDisplayProjection.SCHEMA_VERSION,
                    document.document().documentDigest(),
                    document.document().importLockDigest(),
                    nodes,
                    List.copyOf(edges),
                    refs,
                    expressions,
                    new Coverage(total, refs.size(), total > refs.size()
                            || !graphCappedRefs.isEmpty() || !expressionCappedRefs.isEmpty(),
                            "ROOT_ONLY_IMPORTS_COLLAPSED", document.lockedImports().size()));
        } catch (OntologyDocumentException exception) {
            throw exception;
        } catch (OWLOntologyCreationException | IOException exception) {
            throw new OntologyDocumentException(
                    OntologyDocumentException.Kind.PARSE_ERROR,
                    "unable to project OWL document", exception);
        }
    }

    private static List<ProjectionEntry> projectionEntries(
            OWLOntology root, ParsedOntologyDocument document, List<LockedImport> locks) {
        Map<String, LockedImport> byResolvedIri = locks.stream()
                .collect(Collectors.toMap(LockedImport::resolvedOntologyIri, value -> value, (left, right) -> left));
        List<ProjectionEntry> result = new ArrayList<>();
        addProjectionEntries(result, root, document.document().revisionId(), false, document.document().revisionId());
        root.getImportsClosure().stream()
                .filter(member -> !member.equals(root))
                .map(member -> new ImportedMember(member,
                        member.getOntologyID().getOntologyIRI().map(IRI::getIRIString).orElse("")))
                .sorted(Comparator.comparing(ImportedMember::ontologyIri))
                .forEach(member -> {
                    LockedImport lock = byResolvedIri.get(member.ontologyIri());
                    String artifactId = lock == null ? member.ontologyIri() : lock.artifactId();
                    addProjectionEntries(result, member.ontology(), artifactId, true, artifactId);
                });
        return result.stream()
                .sorted(Comparator.comparing(ProjectionEntry::imported)
                        .thenComparing(ProjectionEntry::artifactId)
                        .thenComparing(ProjectionEntry::rendering))
                .toList();
    }

    private static void projectExpressions(
            OWLAxiom axiom,
            String refId,
            Map<String, MutableNode> nodes,
            ExpressionState state,
            OWLOntology ontology) {
        if (!(axiom instanceof OWLSubClassOfAxiom
                || axiom instanceof OWLEquivalentClassesAxiom
                || axiom instanceof OWLDisjointClassesAxiom
                || axiom instanceof OWLSubPropertyChainOfAxiom
                || axiom instanceof OWLObjectPropertyDomainAxiom
                || axiom instanceof OWLObjectPropertyRangeAxiom
                || axiom instanceof OWLDataPropertyDomainAxiom
                || axiom instanceof OWLDataPropertyRangeAxiom)) {
            return;
        }
        if (!state.beginRoot(refId)) {
            return;
        }
        List<ExpressionOperand> operands = new ArrayList<>();
        if (axiom instanceof OWLSubClassOfAxiom subclass) {
            operands.add(expressionOperand("subClass", 0, subclass.getSubClass(), "axiom/subClass", refId, nodes, state, ontology, 1));
            operands.add(expressionOperand("superClass", 1, subclass.getSuperClass(), "axiom/superClass", refId, nodes, state, ontology, 1));
            addExpression(refId, "axiom", "SubClassOf", operands, state);
        } else if (axiom instanceof OWLEquivalentClassesAxiom equivalent) {
            List<OWLClassExpression> members = equivalent.getClassExpressions().stream()
                    .sorted(Comparator.comparing(value -> renderObject(ontology, value)))
                    .limit(state.maxExpressions).toList();
            for (int position = 0; position < members.size(); position++) {
                operands.add(expressionOperand("member", position, members.get(position),
                        "axiom/member[" + position + "]", refId, nodes, state, ontology, 1));
            }
            addExpression(refId, "axiom", "EquivalentClasses", operands, state);
        } else if (axiom instanceof OWLDisjointClassesAxiom disjoint) {
            List<OWLClassExpression> members = disjoint.getClassExpressions().stream()
                    .sorted(Comparator.comparing(value -> renderObject(ontology, value)))
                    .limit(state.maxExpressions).toList();
            for (int position = 0; position < members.size(); position++) {
                operands.add(expressionOperand("member", position, members.get(position),
                        "axiom/member[" + position + "]", refId, nodes, state, ontology, 1));
            }
            addExpression(refId, "axiom", "DisjointClasses", operands, state);
        } else if (axiom instanceof OWLSubPropertyChainOfAxiom chain) {
            int position = 0;
            for (var property : chain.getPropertyChain()) {
                if (position >= state.maxExpressions) {
                    state.expressionCappedRefs.add(refId);
                    break;
                }
                operands.add(propertyOperand("step", position, property,
                        "axiom/step[" + position + "]", refId, nodes, state));
                position++;
            }
            operands.add(propertyOperand("superProperty", position, chain.getSuperProperty(),
                    "axiom/superProperty", refId, nodes, state));
            addExpression(refId, "axiom", "SubPropertyChainOf", operands, state);
        } else if (axiom instanceof OWLObjectPropertyDomainAxiom domain) {
            operands.add(propertyOperand("property", 0, domain.getProperty(),
                    "axiom/property", refId, nodes, state));
            operands.add(expressionOperand("domain", 1, domain.getDomain(),
                    "axiom/domain", refId, nodes, state, ontology, 1));
            addExpression(refId, "axiom", "ObjectPropertyDomain", operands, state);
        } else if (axiom instanceof OWLObjectPropertyRangeAxiom range) {
            operands.add(propertyOperand("property", 0, range.getProperty(),
                    "axiom/property", refId, nodes, state));
            operands.add(expressionOperand("range", 1, range.getRange(),
                    "axiom/range", refId, nodes, state, ontology, 1));
            addExpression(refId, "axiom", "ObjectPropertyRange", operands, state);
        } else if (axiom instanceof OWLDataPropertyDomainAxiom domain) {
            operands.add(propertyOperand("property", 0, domain.getProperty(),
                    "axiom/property", refId, nodes, state));
            operands.add(expressionOperand("domain", 1, domain.getDomain(),
                    "axiom/domain", refId, nodes, state, ontology, 1));
            addExpression(refId, "axiom", "DataPropertyDomain", operands, state);
        } else if (axiom instanceof OWLDataPropertyRangeAxiom range) {
            operands.add(propertyOperand("property", 0, range.getProperty(),
                    "axiom/property", refId, nodes, state));
            operands.add(dataOperand("range", 1, range.getRange(),
                    "axiom/range", refId, nodes, state, ontology, 1));
            addExpression(refId, "axiom", "DataPropertyRange", operands, state);
        }
    }

    private static ExpressionOperand expressionOperand(
            String role, int position, OWLClassExpression value, String path, String axiomId,
            Map<String, MutableNode> nodes, ExpressionState state, OWLOntology ontology, int depth) {
        String target = classExpressionTarget(value, path, axiomId, nodes, state, ontology, depth);
        return new ExpressionOperand(role, position, target,
                target == null ? (state.isCapped(axiomId)
                        ? state.reasonForMissingTarget(axiomId) : "UNSUPPORTED_EXPRESSION") : null);
    }

    private static ExpressionOperand dataOperand(
            String role, int position, OWLDataRange value, String path, String axiomId,
            Map<String, MutableNode> nodes, ExpressionState state, OWLOntology ontology, int depth) {
        String target = dataRangeTarget(value, path, axiomId, nodes, state, ontology, depth);
        return new ExpressionOperand(role, position, target,
                target == null ? (state.isCapped(axiomId)
                        ? state.reasonForMissingTarget(axiomId) : "UNSUPPORTED_EXPRESSION") : null);
    }

    private static ExpressionOperand propertyOperand(
            String role, int position, OWLPropertyExpression value, String path, String axiomId,
            Map<String, MutableNode> nodes, ExpressionState state) {
        if (isNamedProperty(value)) {
            String target = addExpressionEntity(nodes, namedProperty(value), axiomId, state);
            return new ExpressionOperand(role, position, target,
                    target == null ? state.reasonForMissingTarget(axiomId) : null);
        }
        return new ExpressionOperand(role, position, null, "UNSUPPORTED_PROPERTY_EXPRESSION");
    }

    private static String classExpressionTarget(
            OWLClassExpression value, String path, String axiomId, Map<String, MutableNode> nodes,
            ExpressionState state, OWLOntology ontology, int depth) {
        if (value.isOWLClass()) {
            return addExpressionEntity(nodes, value.asOWLClass(), axiomId, state);
        }
        if (!state.beginNestedExpression(axiomId)) {
            return null;
        }
        if (!state.withinDepth(depth, axiomId)) {
            return null;
        }
        List<ExpressionOperand> operands = new ArrayList<>();
        String operator;
        if (value instanceof OWLObjectIntersectionOf intersection) {
            operator = "ObjectIntersectionOf";
            if (intersection.getOperands().size() > state.maxExpressions) {
                state.expressionCappedRefs.add(axiomId);
            }
            List<OWLClassExpression> children = intersection.getOperands().stream()
                    .sorted(Comparator.comparing(child -> renderObject(ontology, child)))
                    .limit(state.maxExpressions).toList();
            for (int position = 0; position < children.size(); position++) {
                operands.add(expressionOperand("operand", position, children.get(position),
                        path + "/operand[" + position + "]", axiomId, nodes, state, ontology, depth + 1));
            }
        } else if (value instanceof OWLObjectUnionOf union) {
            operator = "ObjectUnionOf";
            if (union.getOperands().size() > state.maxExpressions) {
                state.expressionCappedRefs.add(axiomId);
            }
            List<OWLClassExpression> children = union.getOperands().stream()
                    .sorted(Comparator.comparing(child -> renderObject(ontology, child)))
                    .limit(state.maxExpressions).toList();
            for (int position = 0; position < children.size(); position++) {
                operands.add(expressionOperand("operand", position, children.get(position),
                        path + "/operand[" + position + "]", axiomId, nodes, state, ontology, depth + 1));
            }
        } else if (value instanceof OWLObjectComplementOf complement) {
            operator = "ObjectComplementOf";
            operands.add(expressionOperand("operand", 0, complement.getOperand(),
                    path + "/operand", axiomId, nodes, state, ontology, depth + 1));
        } else if (value instanceof OWLObjectSomeValuesFrom some) {
            operator = "ObjectSomeValuesFrom";
            operands.add(propertyOperand("property", 0, some.getProperty(), path + "/property", axiomId, nodes, state));
            operands.add(expressionOperand("filler", 1, some.getFiller(), path + "/filler",
                    axiomId, nodes, state, ontology, depth + 1));
        } else if (value instanceof OWLObjectAllValuesFrom all) {
            operator = "ObjectAllValuesFrom";
            operands.add(propertyOperand("property", 0, all.getProperty(), path + "/property", axiomId, nodes, state));
            operands.add(expressionOperand("filler", 1, all.getFiller(), path + "/filler",
                    axiomId, nodes, state, ontology, depth + 1));
        } else if (value instanceof OWLObjectCardinalityRestriction cardinality) {
            operator = value instanceof OWLObjectMinCardinality ? "ObjectMinCardinality"
                    : value instanceof OWLObjectMaxCardinality ? "ObjectMaxCardinality" : "ObjectExactCardinality";
            operands.add(propertyOperand("property", 0, cardinality.getProperty(), path + "/property", axiomId, nodes, state));
            operands.add(new ExpressionOperand("cardinality", 1, null, Integer.toString(cardinality.getCardinality())));
            if (cardinality.isQualified()) {
                operands.add(expressionOperand("filler", 2, cardinality.getFiller(), path + "/filler",
                        axiomId, nodes, state, ontology, depth + 1));
            }
        } else if (value instanceof OWLDataSomeValuesFrom some) {
            operator = "DataSomeValuesFrom";
            operands.add(propertyOperand("property", 0, some.getProperty(), path + "/property", axiomId, nodes, state));
            operands.add(dataOperand("filler", 1, some.getFiller(), path + "/filler",
                    axiomId, nodes, state, ontology, depth + 1));
        } else if (value instanceof OWLDataAllValuesFrom all) {
            operator = "DataAllValuesFrom";
            operands.add(propertyOperand("property", 0, all.getProperty(), path + "/property", axiomId, nodes, state));
            operands.add(dataOperand("filler", 1, all.getFiller(), path + "/filler",
                    axiomId, nodes, state, ontology, depth + 1));
        } else if (value instanceof OWLDataCardinalityRestriction cardinality) {
            operator = value instanceof OWLDataMinCardinality ? "DataMinCardinality"
                    : value instanceof OWLDataMaxCardinality ? "DataMaxCardinality" : "DataExactCardinality";
            operands.add(propertyOperand("property", 0, cardinality.getProperty(), path + "/property", axiomId, nodes, state));
            operands.add(new ExpressionOperand("cardinality", 1, null, Integer.toString(cardinality.getCardinality())));
            if (cardinality.isQualified()) {
                operands.add(dataOperand("filler", 2, cardinality.getFiller(), path + "/filler",
                        axiomId, nodes, state, ontology, depth + 1));
            }
        } else {
            return null;
        }
        return addExpression(axiomId, path, operator, operands, state);
    }

    private static String dataRangeTarget(
            OWLDataRange value, String path, String axiomId, Map<String, MutableNode> nodes,
            ExpressionState state, OWLOntology ontology, int depth) {
        if (value.isOWLDatatype()) {
            return addExpressionEntity(nodes, value.asOWLDatatype(), axiomId, state);
        }
        if (!state.beginNestedExpression(axiomId)) {
            return null;
        }
        if (!state.withinDepth(depth, axiomId)) {
            return null;
        }
        String operator;
        List<ExpressionOperand> operands = new ArrayList<>();
        if (value instanceof OWLDataIntersectionOf intersection) {
            operator = "DataIntersectionOf";
            if (intersection.getOperands().size() > state.maxExpressions) {
                state.expressionCappedRefs.add(axiomId);
            }
            List<OWLDataRange> children = intersection.getOperands().stream()
                    .sorted(Comparator.comparing(child -> renderObject(ontology, child)))
                    .limit(state.maxExpressions).toList();
            for (int position = 0; position < children.size(); position++) {
                operands.add(dataOperand("operand", position, children.get(position),
                        path + "/operand[" + position + "]", axiomId, nodes, state, ontology, depth + 1));
            }
        } else if (value instanceof OWLDataUnionOf union) {
            operator = "DataUnionOf";
            if (union.getOperands().size() > state.maxExpressions) {
                state.expressionCappedRefs.add(axiomId);
            }
            List<OWLDataRange> children = union.getOperands().stream()
                    .sorted(Comparator.comparing(child -> renderObject(ontology, child)))
                    .limit(state.maxExpressions).toList();
            for (int position = 0; position < children.size(); position++) {
                operands.add(dataOperand("operand", position, children.get(position),
                        path + "/operand[" + position + "]", axiomId, nodes, state, ontology, depth + 1));
            }
        } else if (value instanceof OWLDataComplementOf complement) {
            operator = "DataComplementOf";
            operands.add(dataOperand("operand", 0, complement.getDataRange(), path + "/operand",
                    axiomId, nodes, state, ontology, depth + 1));
        } else {
            return null;
        }
        return addExpression(axiomId, path, operator, operands, state);
    }

    private static String addExpression(
            String axiomId, String path, String operator, List<ExpressionOperand> operands, ExpressionState state) {
        boolean root = path.equals("axiom");
        if ((!root && state.expressions.size() >= state.maxExpressions - state.rootReservation)
                || (root && state.expressions.size() >= state.maxExpressions)) {
            state.expressionCappedRefs.add(axiomId);
            return null;
        }
        String id = axiomId + ":" + path;
        state.expressions.add(new Expression(id, axiomId, path, operator, operands));
        if (root) {
            state.rootReservation = 0;
        }
        return id;
    }

    private static String addExpressionEntity(
            Map<String, MutableNode> nodes, OWLEntity entity, String axiomId, ExpressionState state) {
        String id = entityId(entity);
        if (!nodes.containsKey(id) && nodes.size() >= state.maxNodes) {
            state.graphCappedRefs.add(axiomId);
            return null;
        }
        return addEntity(nodes, entity, axiomId);
    }

    private static List<Expression> sanitizeExpressions(
            List<Expression> source,
            Set<String> nodeIds,
            Set<String> expressionIds,
            Set<String> graphCappedRefs,
            Set<String> expressionCappedRefs) {
        return source.stream().map(expression -> {
            boolean changed = false;
            List<ExpressionOperand> operands = new ArrayList<>(expression.operands().size());
            for (ExpressionOperand operand : expression.operands()) {
                String target = operand.targetId();
                if (target != null && !nodeIds.contains(target) && !expressionIds.contains(target)) {
                    changed = true;
                    if (target.startsWith("class:") || target.startsWith("objectProperty:")
                            || target.startsWith("dataProperty:") || target.startsWith("datatype:")
                            || target.startsWith("individual:") || target.startsWith("annotationProperty:")) {
                        graphCappedRefs.add(expression.axiomId());
                    } else {
                        expressionCappedRefs.add(expression.axiomId());
                    }
                    operands.add(new ExpressionOperand(
                            operand.role(), operand.position(), null,
                            operand.value() == null ? "TARGET_NOT_PROJECTED" : operand.value()));
                } else {
                    operands.add(operand);
                }
            }
            return changed
                    ? new Expression(expression.id(), expression.axiomId(), expression.path(),
                            expression.operator(), operands)
                    : expression;
        }).toList();
    }

    private static String renderObject(OWLOntology ontology, OWLObject value) {
        StringWriter writer = new StringWriter();
        FunctionalSyntaxObjectRenderer renderer = new FunctionalSyntaxObjectRenderer(
                ontology, new OWLFunctionalSyntaxOntologyFormat(), writer);
        value.accept(renderer);
        return writer.toString().trim();
    }

    private static boolean supportsClassExpression(OWLClassExpression value) {
        return supportsClassExpression(value, 0, 32);
    }

    private static boolean supportsClassExpression(OWLClassExpression value, int depth, int maxDepth) {
        if (value.isOWLClass()) return true;
        if (depth >= maxDepth) return false;
        if (value instanceof OWLObjectIntersectionOf valueSet) return valueSet.getOperands().size() <= 2000 && valueSet.getOperands().stream()
                .allMatch(child -> supportsClassExpression(child, depth + 1, maxDepth));
        if (value instanceof OWLObjectUnionOf valueSet) return valueSet.getOperands().size() <= 2000 && valueSet.getOperands().stream()
                .allMatch(child -> supportsClassExpression(child, depth + 1, maxDepth));
        if (value instanceof OWLObjectComplementOf complement) return supportsClassExpression(complement.getOperand(), depth + 1, maxDepth);
        if (value instanceof OWLObjectSomeValuesFrom some) return isNamedProperty(some.getProperty())
                && supportsClassExpression(some.getFiller(), depth + 1, maxDepth);
        if (value instanceof OWLObjectAllValuesFrom all) return isNamedProperty(all.getProperty())
                && supportsClassExpression(all.getFiller(), depth + 1, maxDepth);
        if (value instanceof OWLObjectCardinalityRestriction cardinality) {
            return isNamedProperty(cardinality.getProperty()) && (!cardinality.isQualified()
                    || supportsClassExpression(cardinality.getFiller(), depth + 1, maxDepth));
        }
        if (value instanceof OWLDataSomeValuesFrom some) return isNamedProperty(some.getProperty())
                && supportsDataRange(some.getFiller(), depth + 1, maxDepth);
        if (value instanceof OWLDataAllValuesFrom all) return isNamedProperty(all.getProperty())
                && supportsDataRange(all.getFiller(), depth + 1, maxDepth);
        if (value instanceof OWLDataCardinalityRestriction cardinality) {
            return isNamedProperty(cardinality.getProperty()) && (!cardinality.isQualified()
                    || supportsDataRange(cardinality.getFiller(), depth + 1, maxDepth));
        }
        return false;
    }

    private static boolean supportsDataRange(OWLDataRange value) {
        return supportsDataRange(value, 0, 32);
    }

    private static boolean supportsDataRange(OWLDataRange value, int depth, int maxDepth) {
        if (value.isOWLDatatype()) return true;
        if (depth >= maxDepth) return false;
        if (value instanceof OWLNaryDataRange range) return range.getOperands().size() <= 2000 && range.getOperands().stream()
                .allMatch(child -> supportsDataRange(child, depth + 1, maxDepth));
        if (value instanceof OWLDataComplementOf complement) return supportsDataRange(complement.getDataRange(), depth + 1, maxDepth);
        return false;
    }

    private static final class ExpressionState {
        private final int maxExpressions;
        private final int maxNodes;
        private final int maxDepth;
        private final List<Expression> expressions;
        private final Set<String> expressionCappedRefs;
        private final Set<String> graphCappedRefs;
        private int rootReservation;
        private int visitBudget;

        private ExpressionState(
                int maxExpressions,
                int maxDepth,
                List<Expression> expressions,
                Set<String> expressionCappedRefs,
                Set<String> graphCappedRefs) {
            this.maxExpressions = maxExpressions;
            this.maxNodes = maxExpressions;
            this.maxDepth = maxDepth;
            this.expressions = expressions;
            this.expressionCappedRefs = expressionCappedRefs;
            this.graphCappedRefs = graphCappedRefs;
            this.visitBudget = maxExpressions;
        }

        private boolean beginRoot(String axiomId) {
            if (expressions.size() >= maxExpressions || visitBudget <= 0) {
                expressionCappedRefs.add(axiomId);
                return false;
            }
            rootReservation = 1;
            visitBudget--;
            return true;
        }

        private boolean beginNestedExpression(String axiomId) {
            if (visitBudget <= 0) {
                expressionCappedRefs.add(axiomId);
                return false;
            }
            visitBudget--;
            return true;
        }

        private boolean withinDepth(int depth, String axiomId) {
            if (depth >= maxDepth) {
                expressionCappedRefs.add(axiomId);
                return false;
            }
            return true;
        }

        private boolean isCapped(String axiomId) {
            return expressionCappedRefs.contains(axiomId) || graphCappedRefs.contains(axiomId);
        }

        private String reasonForMissingTarget(String axiomId) {
            return graphCappedRefs.contains(axiomId) ? "GRAPH_LIMIT_REACHED" : "EXPRESSION_LIMIT_REACHED";
        }
    }

    private static void addProjectionEntries(
            List<ProjectionEntry> result,
            OWLOntology ontology,
            String artifactId,
            boolean imported,
            String identitySeed) {
        for (OWLAxiom axiom : sortedAxioms(ontology)) {
            String rendering = renderAxiom(ontology, axiom);
            String localId = stableAxiomId(identitySeed, rendering);
            String refId = artifactId + ":" + localId;
            ProjectionStatus status = imported
                    ? new ProjectionStatus("NOT_RENDERED", "IMPORT_COLLAPSED")
                    : projectionStatus(axiom);
            result.add(new ProjectionEntry(
                    axiom, imported, artifactId, localId, refId, rendering,
                    status.status(), status.reason()));
        }
    }

    private static ProjectionStatus projectionStatus(OWLAxiom axiom) {
        boolean partial = false;
        String reason = "";
        if (axiom instanceof OWLDeclarationAxiom) {
            // Declarations are always named by definition.
        } else if (axiom instanceof OWLAnnotationAssertionAxiom assertion) {
            if (!isDisplayLabel(assertion)) {
                return unsupportedStatus(axiom, "UNSUPPORTED_ANNOTATION_ASSERTION");
            }
            if (!(assertion.getSubject() instanceof IRI)
                    || !(assertion.getValue() instanceof OWLLiteral)) {
                return unsupportedStatus(axiom, "LABEL_SUBJECT_OR_VALUE_NOT_NAMED_LITERAL");
            }
        } else if (axiom instanceof OWLSubClassOfAxiom subclass) {
            partial = !(supportsClassExpression(subclass.getSubClass())
                    && supportsClassExpression(subclass.getSuperClass()));
            reason = partial ? "UNSUPPORTED_CLASS_EXPRESSION" : "";
        } else if (axiom instanceof OWLSubPropertyChainOfAxiom chain) {
            partial = chain.getPropertyChain().isEmpty()
                    || chain.getPropertyChain().stream().anyMatch(property -> !isNamedProperty(property))
                    || !isNamedProperty(chain.getSuperProperty());
            reason = partial ? "ANONYMOUS_PROPERTY_EXPRESSION_NOT_RENDERED" : "";
        } else if (axiom instanceof OWLObjectPropertyDomainAxiom domain) {
            partial = !(isNamedProperty(domain.getProperty()) && supportsClassExpression(domain.getDomain()));
            reason = partial ? "UNSUPPORTED_PROPERTY_OR_CLASS_EXPRESSION" : "";
        } else if (axiom instanceof OWLDataPropertyDomainAxiom domain) {
            partial = !(isNamedProperty(domain.getProperty()) && supportsClassExpression(domain.getDomain()));
            reason = partial ? "UNSUPPORTED_PROPERTY_OR_CLASS_EXPRESSION" : "";
        } else if (axiom instanceof OWLObjectPropertyRangeAxiom range) {
            partial = !(isNamedProperty(range.getProperty()) && supportsClassExpression(range.getRange()));
            reason = partial ? "UNSUPPORTED_PROPERTY_OR_CLASS_EXPRESSION" : "";
        } else if (axiom instanceof OWLDataPropertyRangeAxiom range) {
            partial = !(isNamedProperty(range.getProperty()) && supportsDataRange(range.getRange()));
            reason = partial ? "UNSUPPORTED_PROPERTY_OR_DATATYPE_EXPRESSION" : "";
        } else if (axiom instanceof OWLEquivalentClassesAxiom equivalent) {
            partial = equivalent.getClassExpressions().size() < 2
                    || equivalent.getClassExpressions().stream().anyMatch(expression -> !supportsClassExpression(expression));
            reason = partial ? "UNSUPPORTED_CLASS_EXPRESSION" : "";
            if (!partial && equivalent.getClassExpressions().size() > 2) {
                partial = true;
                reason = "PAIRWISE_RELATIONS_COLLAPSED";
            }
        } else if (axiom instanceof OWLDisjointClassesAxiom disjoint) {
            partial = disjoint.getClassExpressions().size() < 2
                    || disjoint.getClassExpressions().stream().anyMatch(expression -> !supportsClassExpression(expression));
            reason = partial ? "UNSUPPORTED_CLASS_EXPRESSION" : "";
            if (!partial && disjoint.getClassExpressions().size() > 2) {
                partial = true;
                reason = "PAIRWISE_RELATIONS_COLLAPSED";
            }
        } else if (axiom instanceof OWLInverseObjectPropertiesAxiom inverse) {
            partial = !(isNamedProperty(inverse.getFirstProperty()) && isNamedProperty(inverse.getSecondProperty()));
            reason = partial ? "ANONYMOUS_PROPERTY_EXPRESSION_NOT_RENDERED" : "";
        } else if (axiom instanceof OWLObjectPropertyCharacteristicAxiom characteristic) {
            partial = !isNamedProperty(characteristic.getProperty());
            reason = partial ? "ANONYMOUS_PROPERTY_EXPRESSION_NOT_RENDERED" : "";
        } else if (axiom instanceof OWLDataPropertyCharacteristicAxiom characteristic) {
            partial = !isNamedProperty(characteristic.getProperty());
            reason = partial ? "ANONYMOUS_PROPERTY_EXPRESSION_NOT_RENDERED" : "";
        } else {
            return unsupportedStatus(axiom, "UNSUPPORTED_AXIOM_TYPE");
        }
        if (axiom.annotations().findAny().isPresent()) {
            partial = true;
            reason = reason.isBlank() ? "AXIOM_ANNOTATIONS_NOT_DISPLAYED" : reason + ";AXIOM_ANNOTATIONS_NOT_DISPLAYED";
        }
        return new ProjectionStatus(partial ? "PARTIAL" : "FULL", reason);
    }

    private static ProjectionStatus unsupportedStatus(OWLAxiom axiom, String reason) {
        if (axiom.annotations().findAny().isPresent()) {
            return new ProjectionStatus("PARTIAL", reason + ";AXIOM_ANNOTATIONS_NOT_DISPLAYED");
        }
        return new ProjectionStatus("NOT_RENDERED", reason);
    }

    private static boolean isDisplayLabel(OWLAnnotationAssertionAxiom assertion) {
        return Set.of(
                        "http://www.w3.org/2000/01/rdf-schema#label",
                        "http://www.w3.org/2004/02/skos/core#prefLabel",
                        "http://www.w3.org/2004/02/skos/core#altLabel")
                .contains(assertion.getProperty().getIRI().getIRIString());
    }

    private static boolean addNamedClassEdge(
            Map<String, MutableNode> nodes,
            List<Edge> edges,
            OWLClassExpression source,
            OWLClassExpression target,
            String kind,
            String label,
            String axiomId) {
        if (!source.isOWLClass() || !target.isOWLClass()) {
            addNamedEntities(nodes, source, axiomId);
            addNamedEntities(nodes, target, axiomId);
            return false;
        }
        String sourceId = addEntity(nodes, source.asOWLClass(), axiomId);
        String targetId = addEntity(nodes, target.asOWLClass(), axiomId);
        addEdge(edges, kind, sourceId, targetId, label, axiomId);
        return true;
    }

    private static boolean addNamedPropertyClassEdge(
            Map<String, MutableNode> nodes,
            List<Edge> edges,
            OWLPropertyExpression property,
            OWLClassExpression target,
            String kind,
            String axiomId) {
        if (!isNamedProperty(property) || !target.isOWLClass()) {
            addNamedEntities(nodes, property, axiomId);
            addNamedEntities(nodes, target, axiomId);
            return false;
        }
        String sourceId = addEntity(nodes, namedProperty(property), axiomId);
        String targetId = addEntity(nodes, target.asOWLClass(), axiomId);
        if ("domain".equals(kind)) {
            addEdge(edges, kind, targetId, sourceId, "", axiomId);
        } else {
            addEdge(edges, kind, sourceId, targetId, "", axiomId);
        }
        return true;
    }

    private static boolean addNamedPropertyDataRangeEdge(
            Map<String, MutableNode> nodes,
            List<Edge> edges,
            OWLPropertyExpression property,
            OWLDataRange target,
            String kind,
            String axiomId) {
        if (!isNamedProperty(property) || !target.isOWLDatatype()) {
            addNamedEntities(nodes, property, axiomId);
            addNamedEntities(nodes, target, axiomId);
            return false;
        }
        String sourceId = addEntity(nodes, namedProperty(property), axiomId);
        String targetId = addEntity(nodes, target.asOWLDatatype(), axiomId);
        addEdge(edges, kind, sourceId, targetId, "", axiomId);
        return true;
    }

    private static boolean addNamedPropertyEdge(
            Map<String, MutableNode> nodes,
            List<Edge> edges,
            OWLPropertyExpression source,
            OWLPropertyExpression target,
            String kind,
            String axiomId) {
        if (!isNamedProperty(source) || !isNamedProperty(target)) {
            addNamedEntities(nodes, source, axiomId);
            addNamedEntities(nodes, target, axiomId);
            return false;
        }
        String left = addEntity(nodes, namedProperty(source), axiomId);
        String right = addEntity(nodes, namedProperty(target), axiomId);
        if (left.compareTo(right) > 0) {
            String swap = left;
            left = right;
            right = swap;
        }
        addEdge(edges, kind, left, right, "", axiomId);
        return true;
    }

    private static boolean addNamedClassGroup(
            Map<String, MutableNode> nodes,
            List<Edge> edges,
            Collection<OWLClassExpression> expressions,
            String kind,
            String axiomId,
            int graphLimit) {
        List<OWLClass> named = expressions.stream()
                .filter(OWLClassExpression::isOWLClass)
                .map(OWLClassExpression::asOWLClass)
                .sorted(Comparator.comparing(value -> value.getIRI().getIRIString()))
                .limit(graphLimit)
                .toList();
        named.forEach(value -> addEntity(nodes, value, axiomId));
        for (int index = 1; index < named.size(); index++) {
            addEdge(edges, kind,
                    entityId(named.get(index - 1)), entityId(named.get(index)), "", axiomId);
        }
        return named.size() != expressions.size() || named.size() < 2 || named.size() > 2;
    }

    private static boolean addNamedPropertyFeature(
            Map<String, MutableNode> nodes,
            OWLPropertyExpression characteristic,
            String axiomId,
            String feature) {
        if (!isNamedProperty(characteristic)) {
            addNamedEntities(nodes, characteristic, axiomId);
            return false;
        }
        OWLEntity entity = namedProperty(characteristic);
        MutableNode node = nodes.computeIfAbsent(entityId(entity), ignored ->
                new MutableNode(entityId(entity), entity.getIRI().getIRIString(), entityKind(entity)));
        node.axiomIds.add(axiomId);
        node.features.add(feature);
        return true;
    }

    private static boolean isNamedProperty(OWLPropertyExpression property) {
        return property != null && !property.isAnonymous();
    }

    private static OWLEntity namedProperty(OWLPropertyExpression property) {
        if (property.isObjectPropertyExpression()) {
            return property.asObjectPropertyExpression().getNamedProperty();
        }
        return property.asOWLDataProperty();
    }

    private static void addNamedEntities(Map<String, MutableNode> nodes, OWLObject object, String axiomId) {
        object.signature().forEach(entity -> addEntity(nodes, entity, axiomId));
    }

    private static String addEntity(Map<String, MutableNode> nodes, OWLEntity entity, String axiomId) {
        String iri = entity.getIRI().getIRIString();
        MutableNode node = nodes.computeIfAbsent(entityId(entity), ignored ->
                new MutableNode(entityId(entity), iri, entityKind(entity)));
        node.axiomIds.add(axiomId);
        return node.id;
    }

    private static String entityId(OWLEntity entity) {
        return entityKind(entity) + ":" + entity.getIRI().getIRIString();
    }

    private static String entityKind(OWLEntity entity) {
        if (entity instanceof OWLClass) return "class";
        if (entity instanceof OWLObjectProperty) return "objectProperty";
        if (entity instanceof OWLDataProperty) return "dataProperty";
        if (entity instanceof OWLNamedIndividual) return "individual";
        if (entity instanceof OWLDatatype) return "datatype";
        if (entity instanceof OWLAnnotationProperty) return "annotationProperty";
        return entity.getEntityType().getName();
    }

    private static void addEdge(List<Edge> edges, String kind, String source, String target, String label, String axiomId) {
        String id = kind + ":" + source + ":" + target + ":" + axiomId;
        edges.add(new Edge(id, source, target, kind, label, axiomId));
    }

    private record ImportedMember(OWLOntology ontology, String ontologyIri) {}

    private record ProjectionStatus(String status, String reason) {}

    private record ProjectionEntry(
            OWLAxiom axiom,
            boolean imported,
            String artifactId,
            String axiomId,
            String refId,
            String rendering,
            String status,
            String reason) {
        AxiomRef toRef() {
            return toRef(false, false, false);
        }

        AxiomRef toRef(boolean graphCapped, boolean labelNotProjected, boolean expressionCapped) {
            String actualStatus = status;
            String actualReason = reason;
            if (graphCapped && !imported) {
                actualStatus = "PARTIAL";
                actualReason = actualReason.isBlank() ? "GRAPH_LIMIT_REACHED" : actualReason + ";GRAPH_LIMIT_REACHED";
            }
            if (labelNotProjected && !imported) {
                actualStatus = "NOT_RENDERED";
                actualReason = actualReason.isBlank()
                        ? "LABEL_SUBJECT_NOT_PROJECTED" : actualReason + ";LABEL_SUBJECT_NOT_PROJECTED";
            }
            if (expressionCapped && !imported) {
                actualStatus = "PARTIAL";
                actualReason = actualReason.isBlank()
                        ? "EXPRESSION_LIMIT_REACHED" : actualReason + ";EXPRESSION_LIMIT_REACHED";
            }
            return new AxiomRef(refId, artifactId, axiomId, imported, rendering,
                    axiom.getAxiomType().getName(), actualStatus, actualReason);
        }
    }

    private static final class MutableNode {
        private final String id;
        private final String iri;
        private final String kind;
        private final List<Label> labels = new ArrayList<>();
        private final Set<String> axiomIds = new TreeSet<>();
        private final Set<String> features = new TreeSet<>();

        private MutableNode(String id, String iri, String kind) {
            this.id = id;
            this.iri = iri;
            this.kind = kind;
        }

        private Node freeze() {
            return new Node(id, iri, kind, labels, List.copyOf(axiomIds), List.copyOf(features), false);
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

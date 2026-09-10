package vip.mate.semantic.owl;

import java.util.List;
import org.junit.jupiter.api.Test;
import vip.mate.semantic.core.ontology.OntologyDocumentSyntax;
import static org.junit.jupiter.api.Assertions.*;

class RdfClassExpressionsTest {
    private final OwlDocumentAdapter adapter = new OwlDocumentAdapter();

    @Test void singletonRdfIntersectionAndUnionBecomeTheirMember() {
        for (String operator : List.of("intersectionOf", "unionOf")) {
            String xml = "<rdf:RDF xmlns:rdf='http://www.w3.org/1999/02/22-rdf-syntax-ns#' xmlns:owl='http://www.w3.org/2002/07/owl#'>"
                    + "<owl:Ontology rdf:about='urn:test:lists'/><owl:Class rdf:about='urn:test:A'><owl:equivalentClass><owl:Class>"
                    + "<owl:" + operator + " rdf:parseType='Collection'><owl:Class rdf:about='urn:test:B'/></owl:" + operator + ">"
                    + "</owl:Class></owl:equivalentClass></owl:Class></rdf:RDF>";
            var parsed = adapter.parse("test", "revision", xml, OntologyDocumentSyntax.RDF_XML, List.of());
            assertTrue(adapter.validateDl(parsed).valid());
            String text = new String(adapter.export(parsed, OntologyDocumentSyntax.FUNCTIONAL), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(text.contains("EquivalentClasses("));
            assertFalse(text.contains("ObjectIntersectionOf("));
            assertFalse(text.contains("ObjectUnionOf("));
        }
    }

    @Test void nestedExpressionIsSimplifiedButFunctionalInputIsNot() throws Exception {
        var manager = org.semanticweb.owlapi.apibinding.OWLManager.createOWLOntologyManager();
        var factory = manager.getOWLDataFactory();
        var b = factory.getOWLClass(org.semanticweb.owlapi.model.IRI.create("urn:test:B"));
        var wrapped = factory.getOWLObjectComplementOf(factory.getOWLObjectIntersectionOf(b));
        var axiom = factory.getOWLClassAssertionAxiom(wrapped,
                factory.getOWLNamedIndividual(org.semanticweb.owlapi.model.IRI.create("urn:test:i")));
        var ontology = manager.createOntology(java.util.Set.of(axiom));
        manager.setOntologyFormat(ontology, new org.semanticweb.owlapi.formats.FunctionalSyntaxDocumentFormat());
        RdfClassExpressions.normalize(ontology);
        assertTrue(ontology.containsAxiom(axiom));
        manager.setOntologyFormat(ontology, new org.semanticweb.owlapi.formats.RDFXMLDocumentFormat());
        RdfClassExpressions.normalize(ontology);
        assertTrue(ontology.containsAxiom(factory.getOWLClassAssertionAxiom(factory.getOWLObjectComplementOf(b),
                factory.getOWLNamedIndividual(org.semanticweb.owlapi.model.IRI.create("urn:test:i")))));
    }

    @Test void simplificationPreservesOtherConstructsAndAnnotationsInTheSameAxiom() throws Exception {
        var manager = org.semanticweb.owlapi.apibinding.OWLManager.createOWLOntologyManager();
        var factory = manager.getOWLDataFactory();
        var b = factory.getOWLClass(org.semanticweb.owlapi.model.IRI.create("urn:test:B"));
        var nominal = factory.getOWLObjectOneOf(factory.getOWLNamedIndividual(org.semanticweb.owlapi.model.IRI.create("urn:test:i")));
        var annotation = factory.getOWLAnnotation(factory.getRDFSLabel(), factory.getOWLLiteral("retained"));
        var axiom = factory.getOWLEquivalentClassesAxiom(java.util.Set.of(factory.getOWLObjectIntersectionOf(b), nominal), java.util.Set.of(annotation));
        var ontology = manager.createOntology(java.util.Set.of(axiom));
        manager.setOntologyFormat(ontology, new org.semanticweb.owlapi.formats.RDFXMLDocumentFormat());
        RdfClassExpressions.normalize(ontology);
        assertTrue(ontology.containsAxiom(factory.getOWLEquivalentClassesAxiom(java.util.Set.of(b, nominal), java.util.Set.of(annotation))));
    }
}

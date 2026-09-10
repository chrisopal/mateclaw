package vip.mate.semantic.owl;

import org.semanticweb.owlapi.formats.RDFXMLDocumentFormat;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.util.OWLObjectTransformer;

/** RDF lists with one distinct member denote that member, including duplicate members. */
final class RdfClassExpressions {
    private RdfClassExpressions() {}

    static void normalize(OWLOntology ontology) {
        var manager = ontology.getOWLOntologyManager();
        if (!(manager.getOntologyFormat(ontology) instanceof RDFXMLDocumentFormat)) return;
        var transformer = new OWLObjectTransformer<OWLClassExpression>(
                value -> true, RdfClassExpressions::simplify,
                manager.getOWLDataFactory(), OWLClassExpression.class);
        var changes = ontology.axioms()
                .filter(axiom -> axiom.nestedClassExpressions().anyMatch(expression ->
                        expression instanceof OWLNaryBooleanClassExpression group && group.operands().count() == 1))
                .flatMap(axiom -> transformer.change(axiom).stream())
                .map(change -> change.createOntologyChange(ontology)).toList();
        manager.applyChanges(changes);
    }

    private static OWLClassExpression simplify(OWLClassExpression expression) {
        while (expression instanceof OWLNaryBooleanClassExpression group && group.operands().count() == 1) {
            expression = group.operands().findFirst().orElseThrow();
        }
        return expression;
    }
}

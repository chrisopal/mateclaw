package vip.mate.semantic.core.ontology;

import java.util.List;
import java.util.Set;
import java.util.Objects;

/** A read-only index entry for one parsed axiom. */
public record OntologyAxiomDescriptor(
        String axiomId,
        String axiomType,
        String rendering,
        Set<String> signatureIris,
        List<OntologyAnnotationDescriptor> annotations,
        boolean logical) {

    public OntologyAxiomDescriptor {
        axiomId = Objects.requireNonNull(axiomId, "axiomId");
        axiomType = Objects.requireNonNull(axiomType, "axiomType");
        rendering = Objects.requireNonNull(rendering, "rendering");
        signatureIris = Set.copyOf(Objects.requireNonNull(signatureIris, "signatureIris"));
        annotations = List.copyOf(Objects.requireNonNull(annotations, "annotations"));
    }
}

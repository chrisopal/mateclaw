package vip.mate.semantic.core.ontology;

import java.util.List;
import java.util.Objects;

/** A standard-rendered OWL annotation, including nested annotations. */
public record OntologyAnnotationDescriptor(
        String propertyIri,
        String valueRendering,
        List<OntologyAnnotationDescriptor> nestedAnnotations) {

    public OntologyAnnotationDescriptor {
        propertyIri = Objects.requireNonNull(propertyIri, "propertyIri");
        valueRendering = Objects.requireNonNull(valueRendering, "valueRendering");
        nestedAnnotations = List.copyOf(Objects.requireNonNull(nestedAnnotations, "nestedAnnotations"));
    }
}

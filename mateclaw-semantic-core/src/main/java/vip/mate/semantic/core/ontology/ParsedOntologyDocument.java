package vip.mate.semantic.core.ontology;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Parsed document metadata and axiom index. OWLAPI objects never cross this boundary. */
public record ParsedOntologyDocument(
        OntologyDocument document,
        String parsedOntologyIri,
        Optional<String> parsedVersionIri,
        List<String> imports,
        List<LockedImport> lockedImports,
        List<OntologyAnnotationDescriptor> ontologyAnnotations,
        List<OntologyAxiomDescriptor> axioms) {

    public ParsedOntologyDocument {
        document = Objects.requireNonNull(document, "document");
        parsedOntologyIri = Objects.requireNonNull(parsedOntologyIri, "parsedOntologyIri");
        parsedVersionIri = parsedVersionIri == null ? Optional.empty() : parsedVersionIri;
        imports = List.copyOf(Objects.requireNonNull(imports, "imports"));
        lockedImports = List.copyOf(Objects.requireNonNull(lockedImports, "lockedImports"));
        ontologyAnnotations = List.copyOf(Objects.requireNonNull(ontologyAnnotations, "ontologyAnnotations"));
        axioms = List.copyOf(Objects.requireNonNull(axioms, "axioms"));
    }
}

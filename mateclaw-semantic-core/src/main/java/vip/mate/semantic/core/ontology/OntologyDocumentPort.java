package vip.mate.semantic.core.ontology;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** JDK-only port implemented by the OWLAPI adapter. */
public interface OntologyDocumentPort {
    /** Convenience entry point for sources where ontology/version IRI must be inferred by the parser. */
    default ParsedOntologyDocument parse(
            String ontologyId,
            String revisionId,
            String documentText,
            OntologyDocumentSyntax syntax,
            List<LockedImport> lockedImports) {
        return parse(
                OntologyDocument.fromUnboundText(
                        ontologyId, revisionId, syntax, documentText, LockedImport.digest(lockedImports)),
                syntax,
                lockedImports);
    }

    ParsedOntologyDocument parse(
            OntologyDocument document,
            OntologyDocumentSyntax syntax,
            List<LockedImport> lockedImports);

    OntologyValidationReport validateDl(ParsedOntologyDocument document);

    /** Returns class IRIs from the parsed ontology and every pinned import. */
    Set<String> classIris(ParsedOntologyDocument document);

    /** Returns stable labels for named IRI subjects across the pinned import closure. */
    Map<String, List<String>> termLabels(ParsedOntologyDocument document);

    Map<String, List<String>> termKinds(ParsedOntologyDocument document);

    ParsedOntologyDocument applyAxiomChanges(
            ParsedOntologyDocument document,
            List<OntologyAxiomChange> commands);

    byte[] export(ParsedOntologyDocument document, OntologyDocumentSyntax syntax);
}

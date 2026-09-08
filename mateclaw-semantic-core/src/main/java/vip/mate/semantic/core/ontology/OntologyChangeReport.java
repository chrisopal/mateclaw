package vip.mate.semantic.core.ontology;

import java.util.List;
import java.util.Objects;

/** Complete deterministic classification of one ontology definition transition. */
public record OntologyChangeReport(
        DefinitionChangeClass definitionChangeClass, List<TermChange> termChanges) {

    public OntologyChangeReport {
        Objects.requireNonNull(definitionChangeClass, "definitionChangeClass");
        Objects.requireNonNull(termChanges, "termChanges");
        termChanges = List.copyOf(termChanges);
    }

    public List<TermChange> changes() {
        return termChanges;
    }

    public boolean changed() {
        return !termChanges.isEmpty();
    }
}

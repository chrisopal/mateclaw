package vip.mate.semantic.core.ontology;

import java.util.List;
import java.util.Objects;

/** Deterministic compatibility finding for one stable term key. */
public record TermChange(
        TermKind kind,
        String key,
        DefinitionChangeClass definitionChangeClass,
        List<String> reasons) {

    public TermChange {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(definitionChangeClass, "definitionChangeClass");
        Objects.requireNonNull(reasons, "reasons");
        reasons = List.copyOf(reasons);
    }

    /** Short accessor for callers that use the category name directly. */
    public DefinitionChangeClass changeClass() {
        return definitionChangeClass;
    }
}

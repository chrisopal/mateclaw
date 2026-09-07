package vip.mate.semantic.core.ontology;

import java.util.List;
import java.util.Objects;

/** Immutable content of one ontology revision. */
public record OntologyDefinition(
        List<EntityTypeDefinition> types,
        List<PropertyDefinition> properties,
        List<RelationDefinition> relations) {

    public OntologyDefinition {
        Objects.requireNonNull(types, "types");
        Objects.requireNonNull(properties, "properties");
        Objects.requireNonNull(relations, "relations");
        types = List.copyOf(types);
        properties = List.copyOf(properties);
        relations = List.copyOf(relations);
    }
}

package vip.mate.semantic.core.ontology;

import java.util.Optional;

/** Definition of a typed property owned by an entity type. */
public record PropertyDefinition(
        String key,
        String label,
        String description,
        String ownerTypeKey,
        ValueType valueType,
        Multiplicity multiplicity,
        Optional<String> fixedUnit) {
}

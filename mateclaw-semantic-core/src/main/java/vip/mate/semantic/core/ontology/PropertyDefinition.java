package vip.mate.semantic.core.ontology;

import java.util.List;
import java.util.Optional;

/** Definition of a typed property owned by an entity type. */
public record PropertyDefinition(
        String key,
        String label,
        String description,
        String ownerTypeKey,
        ValueType valueType,
        Multiplicity multiplicity,
        Optional<String> fixedUnit,
        List<String> aliases,
        boolean deprecated,
        PropertyConstraints constraints) {

    public PropertyDefinition {
        aliases = AliasNormalizer.normalize(aliases);
    }

    /** Backward-compatible constructor for definitions without M4 metadata. */
    public PropertyDefinition(
            String key,
            String label,
            String description,
            String ownerTypeKey,
            ValueType valueType,
            Multiplicity multiplicity,
            Optional<String> fixedUnit) {
        this(key, label, description, ownerTypeKey, valueType, multiplicity, fixedUnit, List.of(), false, null);
    }
}

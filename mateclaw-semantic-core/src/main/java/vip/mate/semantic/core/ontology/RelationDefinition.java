package vip.mate.semantic.core.ontology;

import java.util.List;

/** Definition of a directed relation between entity types. */
public record RelationDefinition(
        String key,
        String label,
        String description,
        String sourceTypeKey,
        String targetTypeKey,
        Multiplicity multiplicity,
        List<String> aliases,
        boolean deprecated) {

    public RelationDefinition {
        aliases = AliasNormalizer.normalize(aliases);
    }

    /** Backward-compatible constructor for definitions without M4 metadata. */
    public RelationDefinition(
            String key,
            String label,
            String description,
            String sourceTypeKey,
            String targetTypeKey,
            Multiplicity multiplicity) {
        this(key, label, description, sourceTypeKey, targetTypeKey, multiplicity, List.of(), false);
    }
}

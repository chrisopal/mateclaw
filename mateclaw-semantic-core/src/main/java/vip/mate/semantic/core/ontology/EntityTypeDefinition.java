package vip.mate.semantic.core.ontology;

import java.util.List;

/** Definition of one entity type. */
public record EntityTypeDefinition(
        String key,
        String label,
        String description,
        List<String> aliases,
        boolean deprecated) {

    public EntityTypeDefinition {
        aliases = AliasNormalizer.normalize(aliases);
    }

    /** Backward-compatible constructor for definitions without M4 metadata. */
    public EntityTypeDefinition(String key, String label, String description) {
        this(key, label, description, List.of(), false);
    }

}

package vip.mate.semantic.core.ontology;

/** Definition of a directed relation between entity types. */
public record RelationDefinition(
        String key,
        String label,
        String description,
        String sourceTypeKey,
        String targetTypeKey,
        Multiplicity multiplicity) {
}

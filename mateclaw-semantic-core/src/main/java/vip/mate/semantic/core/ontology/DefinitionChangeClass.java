package vip.mate.semantic.core.ontology;

/** Definition-level compatibility category for a change between ontology revisions. */
public enum DefinitionChangeClass {
    ANNOTATION,
    ADDITIVE_OR_WIDENING,
    POTENTIALLY_BREAKING,
    BREAKING
}

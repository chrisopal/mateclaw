package vip.mate.semantic.core.ontology;

/** A bounded document edit command. */
public sealed interface OntologyAxiomChange
        permits OntologyAxiomChange.Add, OntologyAxiomChange.Remove {

    record Add(String functionalSyntax) implements OntologyAxiomChange {
        public Add {
            if (functionalSyntax == null || functionalSyntax.isBlank()) {
                throw new IllegalArgumentException("functionalSyntax must not be blank");
            }
        }
    }

    record Remove(String axiomId) implements OntologyAxiomChange {
        public Remove {
            if (axiomId == null || axiomId.isBlank()) {
                throw new IllegalArgumentException("axiomId must not be blank");
            }
        }
    }
}

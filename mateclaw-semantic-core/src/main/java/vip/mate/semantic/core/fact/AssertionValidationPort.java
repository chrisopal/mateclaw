package vip.mate.semantic.core.fact;

import java.util.Map;

import vip.mate.semantic.core.identity.SemanticIds.EntityId;
import vip.mate.semantic.core.ontology.OntologyRevision;
import vip.mate.semantic.core.validation.ValidationReport;

/**
 * Adapter seam for validating Functional Syntax and its derived indexes.
 * Implementations belong outside core and may use a standard OWL library.
 */
@FunctionalInterface
public interface AssertionValidationPort {

    /** Explicit governance metadata from standard axiom annotations, never inferred units. */
    default java.util.Optional<String> businessUnit(AssertionPayload assertion) {
        return java.util.Optional.empty();
    }

    ValidationReport validate(
            OntologyRevision ontology,
            StatementContent candidate,
            Map<EntityId, Entity> referencedEntities);
}

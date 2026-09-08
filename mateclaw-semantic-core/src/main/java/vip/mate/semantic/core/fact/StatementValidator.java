package vip.mate.semantic.core.fact;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import vip.mate.semantic.core.identity.GraphScope;
import vip.mate.semantic.core.identity.SemanticIds.EntityId;
import vip.mate.semantic.core.ontology.OntologyRevision;
import vip.mate.semantic.core.ontology.PropertyDefinition;
import vip.mate.semantic.core.ontology.PropertyConstraints;
import vip.mate.semantic.core.ontology.RelationDefinition;
import vip.mate.semantic.core.ontology.ValueType;
import vip.mate.semantic.core.validation.ValidationReport;
import vip.mate.semantic.core.validation.Violation;

/** Deterministic validation of a statement against one ontology revision. */
public final class StatementValidator {

    public ValidationReport validate(
            GraphScope scope,
            OntologyRevision ontology,
            StatementContent candidate,
            Map<EntityId, Entity> referencedEntities) {
        List<Violation> violations = new ArrayList<>();
        if (scope == null) {
            add(violations, "REQUIRED", "scope", "graph scope is required");
        }
        if (ontology == null) {
            add(violations, "REQUIRED", "ontology", "ontology revision is required");
        }
        if (candidate == null) {
            add(violations, "REQUIRED", "$", "statement content is required");
            return new ValidationReport(violations);
        }
        if (referencedEntities == null) {
            add(violations, "REQUIRED", "referencedEntities", "referenced entities are required");
        }
        if (scope != null && !scope.equals(candidate.scope())) {
            add(violations, "SCOPE_MISMATCH", "scope", "statement scope does not match the target graph");
        }
        if (ontology == null) {
            return new ValidationReport(violations);
        }
        if (!ontology.revisionId().equals(candidate.ontologyRevisionId())) {
            add(violations, "ONTOLOGY_REVISION_MISMATCH", "ontologyRevisionId",
                    "statement references a different ontology revision");
        }

        Map<EntityId, Entity> entities = referencedEntities == null ? Map.of() : referencedEntities;
        Entity subject = entities.get(candidate.subjectId());
        if (subject == null) {
            add(violations, "UNKNOWN_SUBJECT", "subjectId", "statement subject is not in the graph");
        } else {
            if (!subject.scope().equals(candidate.scope())) {
                add(violations, "SCOPE_MISMATCH", "subjectId", "subject is in a different graph scope");
            }
            validatePredicate(ontology, candidate, subject, entities, violations);
        }
        if (subject == null) {
            validatePredicate(ontology, candidate, null, entities, violations);
        }
        return new ValidationReport(violations);
    }

    private static void validatePredicate(
            OntologyRevision ontology,
            StatementContent candidate,
            Entity subject,
            Map<EntityId, Entity> entities,
            List<Violation> violations) {
        PredicateRef predicate = candidate.predicate();
        if (predicate instanceof PredicateRef.PropertyRef propertyRef) {
            Optional<PropertyDefinition> definition = ontology.definition().properties().stream()
                    .filter(item -> item != null && item.key().equals(propertyRef.key()))
                    .findFirst();
            if (definition.isEmpty()) {
                add(violations, "UNKNOWN_PREDICATE", "predicate", "property predicate is not declared");
                return;
            }
            PropertyDefinition property = definition.orElseThrow();
            if (subject != null && !property.ownerTypeKey().equals(subject.typeKey())) {
                add(violations, "SUBJECT_TYPE_MISMATCH", "subjectId", "subject type does not own this property");
            }
            validateLiteralValue(
                    property.valueType(), property.fixedUnit(), property.constraints(), candidate.value(), "value", violations);
            return;
        }
        PredicateRef.RelationRef relationRef = (PredicateRef.RelationRef) predicate;
        Optional<RelationDefinition> definition = ontology.definition().relations().stream()
                .filter(item -> item != null && item.key().equals(relationRef.key()))
                .findFirst();
        if (definition.isEmpty()) {
            add(violations, "UNKNOWN_PREDICATE", "predicate", "relation predicate is not declared");
            return;
        }
        RelationDefinition relation = definition.orElseThrow();
        if (subject != null && !relation.sourceTypeKey().equals(subject.typeKey())) {
            add(violations, "SUBJECT_TYPE_MISMATCH", "subjectId", "subject type is not a relation source");
        }
        if (!(candidate.value() instanceof StatementValue.EntityValue entityValue)) {
            add(violations, "VALUE_TYPE_MISMATCH", "value", "relation value must be an entity reference");
            return;
        }
        Entity target = entities.get(entityValue.entityId());
        if (target == null) {
            add(violations, "UNKNOWN_ENTITY", "value.entityId", "relation target is not in the graph");
        } else {
            if (!target.scope().equals(candidate.scope())) {
                add(violations, "SCOPE_MISMATCH", "value.entityId", "target is in a different graph scope");
            }
            if (!relation.targetTypeKey().equals(target.typeKey())) {
                add(violations, "VALUE_ENTITY_TYPE_MISMATCH", "value.entityId",
                        "relation target type does not match the ontology");
            }
        }
    }

    private static void validateLiteralValue(
            ValueType expected,
            Optional<String> fixedUnit,
            PropertyConstraints constraints,
            StatementValue value,
            String path,
            List<Violation> violations) {
        if (expected == null) {
            add(violations, "INVALID_PREDICATE", path, "property value type is undefined");
            return;
        }
        boolean matches = switch (expected) {
            case TEXT -> value instanceof StatementValue.TextValue;
            case DECIMAL -> value instanceof StatementValue.DecimalValue;
            case BOOLEAN -> value instanceof StatementValue.BooleanValue;
            case DATE -> value instanceof StatementValue.DateValue;
            case INSTANT -> value instanceof StatementValue.InstantValue;
        };
        if (!matches) {
            add(violations, "VALUE_TYPE_MISMATCH", path, "value does not match the property type");
            return;
        }
        if (value instanceof StatementValue.DecimalValue decimal
                && fixedUnit != null && fixedUnit.isPresent()
                && !fixedUnit.orElseThrow().equals(decimal.unit())) {
            add(violations, "UNIT_MISMATCH", path + ".unit", "decimal unit does not match the fixed unit");
        }
        validateConstraints(expected, constraints, value, path, violations);
    }

    private static void validateConstraints(
            ValueType expected,
            PropertyConstraints constraints,
            StatementValue value,
            String path,
            List<Violation> violations) {
        if (constraints == null) {
            return;
        }
        if (expected == ValueType.TEXT && value instanceof StatementValue.TextValue text
                && constraints.allowedValues() != null
                && !constraints.allowedValues().contains(text.value())) {
            add(violations, "VALUE_NOT_ALLOWED", path,
                    "text value is not one of the declared allowed values");
        }
        if (expected == ValueType.DECIMAL && value instanceof StatementValue.DecimalValue decimal) {
            if (constraints.minimum() != null) {
                BigDecimal minimum = parseBound(constraints.minimum());
                if (minimum != null && decimal.value().compareTo(minimum) < 0) {
                    add(violations, "VALUE_BELOW_MINIMUM", path,
                            "decimal value is below the declared minimum");
                }
            }
            if (constraints.maximum() != null) {
                BigDecimal maximum = parseBound(constraints.maximum());
                if (maximum != null && decimal.value().compareTo(maximum) > 0) {
                    add(violations, "VALUE_ABOVE_MAXIMUM", path,
                            "decimal value is above the declared maximum");
                }
            }
        }
    }

    private static BigDecimal parseBound(String value) {
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static void add(List<Violation> violations, String code, String path, String message) {
        violations.add(new Violation(code, path, Violation.Severity.ERROR, message));
    }
}

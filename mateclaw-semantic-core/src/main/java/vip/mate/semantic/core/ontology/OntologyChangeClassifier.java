package vip.mate.semantic.core.ontology;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Framework-independent, deterministic classifier for changes between two ontology definitions.
 * Stable term keys are the only identity used; labels and aliases never imply a rename.
 */
public final class OntologyChangeClassifier {

    public OntologyChangeReport classify(OntologyDefinition source, OntologyDefinition target) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        List<TermChange> changes = new ArrayList<>();
        classifyTypes(source.types(), target.types(), changes);
        classifyProperties(source.properties(), target.properties(), changes);
        classifyRelations(source.relations(), target.relations(), changes);
        DefinitionChangeClass overall =
                changes.stream()
                        .map(TermChange::definitionChangeClass)
                        .max(OntologyChangeClassifier::compareSeverity)
                        .orElse(DefinitionChangeClass.ANNOTATION);
        return new OntologyChangeReport(overall, changes);
    }

    private static void classifyTypes(
            List<EntityTypeDefinition> source,
            List<EntityTypeDefinition> target,
            List<TermChange> changes) {
        Map<String, EntityTypeDefinition> before = byKey(source);
        Map<String, EntityTypeDefinition> after = byKey(target);
        classifyKeys(TermKind.TYPE, before, after, changes, OntologyChangeClassifier::typeDelta);
    }

    private static void classifyProperties(
            List<PropertyDefinition> source,
            List<PropertyDefinition> target,
            List<TermChange> changes) {
        Map<String, PropertyDefinition> before = byKey(source);
        Map<String, PropertyDefinition> after = byKey(target);
        classifyKeys(
                TermKind.PROPERTY, before, after, changes, OntologyChangeClassifier::propertyDelta);
    }

    private static void classifyRelations(
            List<RelationDefinition> source,
            List<RelationDefinition> target,
            List<TermChange> changes) {
        Map<String, RelationDefinition> before = byKey(source);
        Map<String, RelationDefinition> after = byKey(target);
        classifyKeys(
                TermKind.RELATION, before, after, changes, OntologyChangeClassifier::relationDelta);
    }

    private static <T> void classifyKeys(
            TermKind kind,
            Map<String, T> source,
            Map<String, T> target,
            List<TermChange> changes,
            Delta<T> delta) {
        Set<String> keys = new java.util.LinkedHashSet<>(source.keySet());
        keys.addAll(target.keySet());
        for (String key : keys) {
            T oldTerm = source.get(key);
            T newTerm = target.get(key);
            DefinitionChangeClass category;
            List<String> reasons;
            if (oldTerm == null) {
                category = DefinitionChangeClass.ADDITIVE_OR_WIDENING;
                reasons = List.of("TERM_ADDED");
            } else if (newTerm == null) {
                category = DefinitionChangeClass.BREAKING;
                reasons = List.of("TERM_REMOVED");
            } else {
                DeltaResult result = delta.compare(oldTerm, newTerm);
                category = result.category();
                reasons = result.reasons();
            }
            if (oldTerm == null
                    || newTerm == null
                    || category != DefinitionChangeClass.ANNOTATION
                    || !reasons.isEmpty()) {
                changes.add(new TermChange(kind, key, category, reasons));
            }
        }
    }

    private static DeltaResult propertyDelta(
            PropertyDefinition oldTerm, PropertyDefinition newTerm) {
        DefinitionChangeClass category = DefinitionChangeClass.ANNOTATION;
        List<String> reasons = new ArrayList<>();
        addAnnotationReasons(oldTerm, newTerm, reasons);
        if (!Objects.equals(oldTerm.valueType(), newTerm.valueType())) {
            return new DeltaResult(
                    combine(
                            category,
                            DefinitionChangeClass.BREAKING,
                            reasons,
                            "VALUE_TYPE_CHANGED"),
                    reasons);
        }
        if (!Objects.equals(oldTerm.ownerTypeKey(), newTerm.ownerTypeKey())) {
            return new DeltaResult(
                    combine(
                            category,
                            DefinitionChangeClass.BREAKING,
                            reasons,
                            "OWNER_TYPE_CHANGED"),
                    reasons);
        }
        if (!Objects.equals(oldTerm.fixedUnit(), newTerm.fixedUnit())) {
            return new DeltaResult(
                    combine(
                            category,
                            DefinitionChangeClass.BREAKING,
                            reasons,
                            "FIXED_UNIT_CHANGED"),
                    reasons);
        }
        if (oldTerm.multiplicity() != newTerm.multiplicity()) {
            DefinitionChangeClass multiplicityClass =
                    oldTerm.multiplicity() == Multiplicity.MULTI
                            ? DefinitionChangeClass.POTENTIALLY_BREAKING
                            : DefinitionChangeClass.ADDITIVE_OR_WIDENING;
            category = combine(category, multiplicityClass, reasons, "MULTIPLICITY_CHANGED");
        }
        ConstraintDelta constraints =
                constraintDelta(oldTerm.constraints(), newTerm.constraints(), oldTerm.valueType());
        category = combine(category, constraints.category(), reasons, constraints.reason());
        return new DeltaResult(category, reasons);
    }

    private static DeltaResult typeDelta(
            EntityTypeDefinition oldTerm, EntityTypeDefinition newTerm) {
        List<String> reasons = new ArrayList<>();
        if (!Objects.equals(oldTerm.label(), newTerm.label())) reasons.add("LABEL_CHANGED");
        if (!Objects.equals(oldTerm.description(), newTerm.description()))
            reasons.add("DESCRIPTION_CHANGED");
        if (!Objects.equals(oldTerm.aliases(), newTerm.aliases())) reasons.add("ALIASES_CHANGED");
        if (oldTerm.deprecated() != newTerm.deprecated()) reasons.add("DEPRECATION_CHANGED");
        return new DeltaResult(DefinitionChangeClass.ANNOTATION, reasons);
    }

    private static DeltaResult relationDelta(
            RelationDefinition oldTerm, RelationDefinition newTerm) {
        DefinitionChangeClass category = DefinitionChangeClass.ANNOTATION;
        List<String> reasons = new ArrayList<>();
        if (!Objects.equals(oldTerm.label(), newTerm.label())) reasons.add("LABEL_CHANGED");
        if (!Objects.equals(oldTerm.description(), newTerm.description()))
            reasons.add("DESCRIPTION_CHANGED");
        if (!Objects.equals(oldTerm.aliases(), newTerm.aliases())) reasons.add("ALIASES_CHANGED");
        if (oldTerm.deprecated() != newTerm.deprecated()) reasons.add("DEPRECATION_CHANGED");
        if (!Objects.equals(oldTerm.sourceTypeKey(), newTerm.sourceTypeKey())) {
            return new DeltaResult(
                    combine(
                            category,
                            DefinitionChangeClass.BREAKING,
                            reasons,
                            "SOURCE_TYPE_CHANGED"),
                    reasons);
        }
        if (!Objects.equals(oldTerm.targetTypeKey(), newTerm.targetTypeKey())) {
            return new DeltaResult(
                    combine(
                            category,
                            DefinitionChangeClass.BREAKING,
                            reasons,
                            "TARGET_TYPE_CHANGED"),
                    reasons);
        }
        if (oldTerm.multiplicity() != newTerm.multiplicity()) {
            category =
                    combine(
                            category,
                            oldTerm.multiplicity() == Multiplicity.MULTI
                                    ? DefinitionChangeClass.POTENTIALLY_BREAKING
                                    : DefinitionChangeClass.ADDITIVE_OR_WIDENING,
                            reasons,
                            "MULTIPLICITY_CHANGED");
        }
        return new DeltaResult(category, reasons);
    }

    private static void addAnnotationReasons(
            PropertyDefinition oldTerm, PropertyDefinition newTerm, List<String> reasons) {
        if (!Objects.equals(oldTerm.label(), newTerm.label())) reasons.add("LABEL_CHANGED");
        if (!Objects.equals(oldTerm.description(), newTerm.description()))
            reasons.add("DESCRIPTION_CHANGED");
        if (!Objects.equals(oldTerm.aliases(), newTerm.aliases())) reasons.add("ALIASES_CHANGED");
        if (oldTerm.deprecated() != newTerm.deprecated()) reasons.add("DEPRECATION_CHANGED");
    }

    private static ConstraintDelta constraintDelta(
            PropertyConstraints oldConstraints,
            PropertyConstraints newConstraints,
            ValueType valueType) {
        if (sameConstraints(oldConstraints, newConstraints, valueType)) {
            return new ConstraintDelta(DefinitionChangeClass.ANNOTATION, null);
        }
        if (valueType == ValueType.TEXT) {
            Set<String> oldValues =
                    toSet(oldConstraints == null ? null : oldConstraints.allowedValues());
            Set<String> newValues =
                    toSet(newConstraints == null ? null : newConstraints.allowedValues());
            if (oldValues == null && newValues == null)
                return new ConstraintDelta(DefinitionChangeClass.ANNOTATION, null);
            if (oldValues == null || newValues == null) {
                return new ConstraintDelta(
                        newValues == null
                                ? DefinitionChangeClass.ADDITIVE_OR_WIDENING
                                : DefinitionChangeClass.POTENTIALLY_BREAKING,
                        "ENUMERATION_CHANGED");
            }
            if (newValues.containsAll(oldValues) && newValues.size() > oldValues.size()) {
                return new ConstraintDelta(
                        DefinitionChangeClass.ADDITIVE_OR_WIDENING, "ENUMERATION_EXPANDED");
            }
            if (oldValues.containsAll(newValues) && oldValues.size() > newValues.size()) {
                return new ConstraintDelta(
                        DefinitionChangeClass.POTENTIALLY_BREAKING, "ENUMERATION_REDUCED");
            }
            return new ConstraintDelta(
                    DefinitionChangeClass.POTENTIALLY_BREAKING, "ENUMERATION_CHANGED");
        }
        if (valueType == ValueType.DECIMAL) {
            String reason = decimalConstraintReason(oldConstraints, newConstraints);
            if (reason == null) return new ConstraintDelta(DefinitionChangeClass.ANNOTATION, null);
            return new ConstraintDelta(
                    decimalChangeIsWidening(oldConstraints, newConstraints)
                            ? DefinitionChangeClass.ADDITIVE_OR_WIDENING
                            : DefinitionChangeClass.POTENTIALLY_BREAKING,
                    reason);
        }
        return new ConstraintDelta(DefinitionChangeClass.BREAKING, "CONSTRAINTS_CHANGED");
    }

    private static String decimalConstraintReason(
            PropertyConstraints oldConstraints, PropertyConstraints newConstraints) {
        String oldMinimum = oldConstraints == null ? null : oldConstraints.minimum();
        String newMinimum = newConstraints == null ? null : newConstraints.minimum();
        String oldMaximum = oldConstraints == null ? null : oldConstraints.maximum();
        String newMaximum = newConstraints == null ? null : newConstraints.maximum();
        return Objects.equals(oldMinimum, newMinimum) && Objects.equals(oldMaximum, newMaximum)
                ? null
                : "DECIMAL_RANGE_CHANGED";
    }

    private static boolean decimalChangeIsWidening(
            PropertyConstraints oldConstraints, PropertyConstraints newConstraints) {
        BigDecimal oldMinimum = parse(oldConstraints == null ? null : oldConstraints.minimum());
        BigDecimal newMinimum = parse(newConstraints == null ? null : newConstraints.minimum());
        BigDecimal oldMaximum = parse(oldConstraints == null ? null : oldConstraints.maximum());
        BigDecimal newMaximum = parse(newConstraints == null ? null : newConstraints.maximum());
        boolean minimumWidened =
                oldMinimum != null && newMinimum == null
                        || oldMinimum != null
                                && newMinimum != null
                                && newMinimum.compareTo(oldMinimum) <= 0
                        || oldMinimum == null && newMinimum == null;
        boolean maximumWidened =
                oldMaximum != null && newMaximum == null
                        || oldMaximum != null
                                && newMaximum != null
                                && newMaximum.compareTo(oldMaximum) >= 0
                        || oldMaximum == null && newMaximum == null;
        return minimumWidened && maximumWidened;
    }

    private static BigDecimal parse(String value) {
        if (value == null) return null;
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static boolean sameConstraints(
            PropertyConstraints oldConstraints,
            PropertyConstraints newConstraints,
            ValueType valueType) {
        if (oldConstraints == null) oldConstraints = new PropertyConstraints(null, null, null);
        if (newConstraints == null) newConstraints = new PropertyConstraints(null, null, null);
        if (valueType == ValueType.TEXT) {
            return Objects.equals(
                    toSet(oldConstraints.allowedValues()), toSet(newConstraints.allowedValues()));
        }
        if (valueType == ValueType.DECIMAL) {
            return equalDecimal(oldConstraints.minimum(), newConstraints.minimum())
                    && equalDecimal(oldConstraints.maximum(), newConstraints.maximum());
        }
        return Objects.equals(oldConstraints, newConstraints);
    }

    private static boolean equalDecimal(String left, String right) {
        BigDecimal leftValue = parse(left);
        BigDecimal rightValue = parse(right);
        return leftValue != null && rightValue != null
                ? leftValue.compareTo(rightValue) == 0
                : Objects.equals(left, right);
    }

    private static Set<String> toSet(List<String> values) {
        return values == null ? null : new HashSet<>(values);
    }

    private static DefinitionChangeClass combine(
            DefinitionChangeClass current,
            DefinitionChangeClass candidate,
            List<String> reasons,
            String reason) {
        if (reason != null && !reasons.contains(reason)) reasons.add(reason);
        return currentSeverity(current) >= currentSeverity(candidate) ? current : candidate;
    }

    private static int compareSeverity(DefinitionChangeClass left, DefinitionChangeClass right) {
        return Integer.compare(currentSeverity(left), currentSeverity(right));
    }

    private static int currentSeverity(DefinitionChangeClass category) {
        return switch (category) {
            case ANNOTATION -> 0;
            case ADDITIVE_OR_WIDENING -> 1;
            case POTENTIALLY_BREAKING -> 2;
            case BREAKING -> 3;
        };
    }

    private static <T> Map<String, T> byKey(List<T> terms) {
        Map<String, T> result = new LinkedHashMap<>();
        for (T term : terms) {
            if (term instanceof EntityTypeDefinition type) result.put(type.key(), term);
            else if (term instanceof PropertyDefinition property) result.put(property.key(), term);
            else if (term instanceof RelationDefinition relation) result.put(relation.key(), term);
        }
        return result;
    }

    private interface Delta<T> {
        DeltaResult compare(T oldTerm, T newTerm);
    }

    private record DeltaResult(DefinitionChangeClass category, List<String> reasons) {
        private DeltaResult {
            reasons = List.copyOf(reasons);
        }
    }

    private record ConstraintDelta(DefinitionChangeClass category, String reason) {}
}

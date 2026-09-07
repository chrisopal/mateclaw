package vip.mate.semantic.core.ontology;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import vip.mate.semantic.core.validation.ValidationReport;
import vip.mate.semantic.core.validation.Violation;

/** Deterministic validation for ontology revision content. */
public final class OntologyValidator {

    private static final int MAX_TYPES = 100;
    private static final int MAX_PREDICATES = 500;
    private static final int MAX_KEY_LENGTH = 64;
    private static final int MAX_LABEL_LENGTH = 128;
    private static final int MAX_DESCRIPTION_LENGTH = 1000;
    private static final int MAX_UNIT_LENGTH = 32;
    private static final Pattern KEY_PATTERN = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");

    public ValidationReport validate(OntologyDefinition definition) {
        List<Violation> violations = new ArrayList<>();
        if (definition == null) {
            add(violations, "REQUIRED", "$", "ontology definition is required");
            return new ValidationReport(violations);
        }

        validateCollectionSize(definition.types(), "types", MAX_TYPES, violations);
        long predicateCount = (long) definition.properties().size() + definition.relations().size();
        if (predicateCount > MAX_PREDICATES) {
            add(violations, "TOO_MANY_DEFINITIONS", "$",
                    "property and relation definitions exceed the combined maximum size");
        }
        if (definition.types().isEmpty()) {
            add(violations, "EMPTY_TYPES", "types", "at least one entity type is required");
        }

        Set<String> typeKeys = new HashSet<>();
        Set<String> propertyKeys = new HashSet<>();
        Set<String> relationKeys = new HashSet<>();
        for (int i = 0; i < definition.types().size(); i++) {
            EntityTypeDefinition type = definition.types().get(i);
            String path = "types[" + i + "]";
            if (type == null) {
                add(violations, "REQUIRED", path, "entity type is required");
                continue;
            }
            validateCommon(type.key(), type.label(), type.description(), path, violations);
            registerKey(type.key(), path + ".key", typeKeys, violations);
        }

        for (int i = 0; i < definition.properties().size(); i++) {
            PropertyDefinition property = definition.properties().get(i);
            String path = "properties[" + i + "]";
            if (property == null) {
                add(violations, "REQUIRED", path, "property definition is required");
                continue;
            }
            validateCommon(property.key(), property.label(), property.description(), path, violations);
            registerKey(property.key(), path + ".key", propertyKeys, violations);
            validateTypeReference(property.ownerTypeKey(), path + ".ownerTypeKey", typeKeys, violations);
            requireValue(property.valueType(), path + ".valueType", "property value type is required", violations);
            requireValue(property.multiplicity(), path + ".multiplicity", "property multiplicity is required", violations);
            validateUnit(property.fixedUnit(), property.valueType(), path + ".fixedUnit", violations);
        }

        for (int i = 0; i < definition.relations().size(); i++) {
            RelationDefinition relation = definition.relations().get(i);
            String path = "relations[" + i + "]";
            if (relation == null) {
                add(violations, "REQUIRED", path, "relation definition is required");
                continue;
            }
            validateCommon(relation.key(), relation.label(), relation.description(), path, violations);
            registerKey(relation.key(), path + ".key", relationKeys, violations);
            validateTypeReference(relation.sourceTypeKey(), path + ".sourceTypeKey", typeKeys, violations);
            validateTypeReference(relation.targetTypeKey(), path + ".targetTypeKey", typeKeys, violations);
            requireValue(relation.multiplicity(), path + ".multiplicity", "relation multiplicity is required", violations);
        }

        return new ValidationReport(violations);
    }

    private static void validateCommon(
            String key, String label, String description, String path, List<Violation> violations) {
        validateKey(key, path + ".key", violations);
        validateRequiredText(label, path + ".label", MAX_LABEL_LENGTH, violations);
        validateOptionalText(description, path + ".description", MAX_DESCRIPTION_LENGTH, violations);
    }

    private static void validateKey(String key, String path, List<Violation> violations) {
        if (!hasText(key)) {
            add(violations, "REQUIRED", path, "key is required");
            return;
        }
        if (codePointLength(key) > MAX_KEY_LENGTH) {
            add(violations, "FIELD_TOO_LONG", path, "key exceeds the maximum length");
        }
        if (!KEY_PATTERN.matcher(key).matches()) {
            add(violations, "INVALID_KEY", path, "key must start with a letter and contain only letters, digits, or underscore");
        }
    }

    private static void validateRequiredText(
            String value, String path, int maximumLength, List<Violation> violations) {
        if (!hasText(value)) {
            add(violations, "REQUIRED", path, "value is required");
            return;
        }
        if (codePointLength(value) > maximumLength) {
            add(violations, "FIELD_TOO_LONG", path, "value exceeds the maximum length");
        }
    }

    private static void validateOptionalText(
            String value, String path, int maximumLength, List<Violation> violations) {
        if (value == null) {
            add(violations, "REQUIRED", path, "value is required");
        } else if (codePointLength(value) > maximumLength) {
            add(violations, "FIELD_TOO_LONG", path, "value exceeds the maximum length");
        }
    }

    private static void validateTypeReference(
            String key, String path, Set<String> typeKeys, List<Violation> violations) {
        if (!hasText(key)) {
            add(violations, "REQUIRED", path, "type key is required");
            return;
        }
        if (codePointLength(key) > MAX_KEY_LENGTH) {
            add(violations, "FIELD_TOO_LONG", path, "type key exceeds the maximum length");
        }
        if (!KEY_PATTERN.matcher(key).matches()) {
            add(violations, "INVALID_KEY", path, "type key has an invalid format");
        }
        if (!typeKeys.contains(key)) {
            add(violations, "UNKNOWN_TYPE", path, "referenced entity type does not exist");
        }
    }

    private static void validateUnit(
            Optional<String> fixedUnit, ValueType valueType, String path, List<Violation> violations) {
        if (fixedUnit == null) {
            add(violations, "REQUIRED", path, "fixed unit option is required");
            return;
        }
        if (fixedUnit.isEmpty()) {
            return;
        }
        String unit = fixedUnit.orElseThrow();
        if (!hasText(unit)) {
            add(violations, "REQUIRED", path, "fixed unit must not be blank");
        } else if (codePointLength(unit) > MAX_UNIT_LENGTH) {
            add(violations, "FIELD_TOO_LONG", path, "fixed unit exceeds the maximum length");
        }
        if (valueType != null && valueType != ValueType.DECIMAL) {
            add(violations, "UNIT_REQUIRES_DECIMAL", path, "fixed unit is only supported for decimal properties");
        }
    }

    private static void registerKey(
            String key, String path, Set<String> keys, List<Violation> violations) {
        if (hasText(key) && !keys.add(key)) {
            add(violations, "DUPLICATE_KEY", path, "definition key is already in use");
        }
    }

    private static void validateCollectionSize(
            List<?> values, String path, int maximumSize, List<Violation> violations) {
        if (values.size() > maximumSize) {
            add(violations, "TOO_MANY_DEFINITIONS", path, "definition collection exceeds the maximum size");
        }
    }

    private static void requireValue(
            Object value, String path, String message, List<Violation> violations) {
        if (value == null) {
            add(violations, "REQUIRED", path, message);
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static int codePointLength(String value) {
        return value.codePointCount(0, value.length());
    }

    private static void add(List<Violation> violations, String code, String path, String message) {
        violations.add(new Violation(code, path, Violation.Severity.ERROR, message));
    }
}

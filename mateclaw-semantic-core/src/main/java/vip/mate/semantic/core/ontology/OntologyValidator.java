package vip.mate.semantic.core.ontology;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
    private static final int MAX_ALIASES = 10;
    private static final int MAX_ALIAS_LENGTH = 128;
    private static final int MAX_ALLOWED_VALUES = 100;
    private static final int MAX_CONSTRAINT_SIGNIFICANT_DIGITS = 38;
    private static final int MAX_CONSTRAINT_FRACTIONAL_DIGITS = 12;
    private static final Pattern KEY_PATTERN = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");
    private static final Pattern DECIMAL_PATTERN = Pattern.compile("[+-]?\\d+(?:\\.\\d+)?");

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
        List<TermForAliasCheck> types = new ArrayList<>();
        List<TermForAliasCheck> properties = new ArrayList<>();
        List<TermForAliasCheck> relations = new ArrayList<>();
        for (int i = 0; i < definition.types().size(); i++) {
            EntityTypeDefinition type = definition.types().get(i);
            String path = "types[" + i + "]";
            if (type == null) {
                add(violations, "REQUIRED", path, "entity type is required");
                continue;
            }
            validateCommon(type.key(), type.label(), type.description(), path, violations);
            validateAliases(type.aliases(), path + ".aliases", violations);
            registerKey(type.key(), path + ".key", typeKeys, violations);
            types.add(new TermForAliasCheck(type.key(), type.label(), type.aliases(), path));
        }

        for (int i = 0; i < definition.properties().size(); i++) {
            PropertyDefinition property = definition.properties().get(i);
            String path = "properties[" + i + "]";
            if (property == null) {
                add(violations, "REQUIRED", path, "property definition is required");
                continue;
            }
            validateCommon(property.key(), property.label(), property.description(), path, violations);
            validateAliases(property.aliases(), path + ".aliases", violations);
            registerKey(property.key(), path + ".key", propertyKeys, violations);
            validateTypeReference(property.ownerTypeKey(), path + ".ownerTypeKey", typeKeys, violations);
            requireValue(property.valueType(), path + ".valueType", "property value type is required", violations);
            requireValue(property.multiplicity(), path + ".multiplicity", "property multiplicity is required", violations);
            validateUnit(property.fixedUnit(), property.valueType(), path + ".fixedUnit", violations);
            validateConstraints(property.constraints(), property.valueType(), path + ".constraints", violations);
            properties.add(new TermForAliasCheck(property.key(), property.label(), property.aliases(), path));
        }

        for (int i = 0; i < definition.relations().size(); i++) {
            RelationDefinition relation = definition.relations().get(i);
            String path = "relations[" + i + "]";
            if (relation == null) {
                add(violations, "REQUIRED", path, "relation definition is required");
                continue;
            }
            validateCommon(relation.key(), relation.label(), relation.description(), path, violations);
            validateAliases(relation.aliases(), path + ".aliases", violations);
            registerKey(relation.key(), path + ".key", relationKeys, violations);
            validateTypeReference(relation.sourceTypeKey(), path + ".sourceTypeKey", typeKeys, violations);
            validateTypeReference(relation.targetTypeKey(), path + ".targetTypeKey", typeKeys, violations);
            requireValue(relation.multiplicity(), path + ".multiplicity", "relation multiplicity is required", violations);
            relations.add(new TermForAliasCheck(relation.key(), relation.label(), relation.aliases(), path));
        }

        validateAliasAmbiguity(types, violations);
        validateAliasAmbiguity(properties, violations);
        validateAliasAmbiguity(relations, violations);

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

    private static void validateAliases(List<String> aliases, String path, List<Violation> violations) {
        if (aliases == null) {
            return;
        }
        if (aliases.size() > MAX_ALIASES) {
            add(violations, "TOO_MANY_ALIASES", path, "term aliases exceed the maximum size");
        }
        for (int i = 0; i < aliases.size(); i++) {
            String alias = aliases.get(i);
            if (alias == null || alias.isBlank()) {
                add(violations, "INVALID_ALIAS", path + "[" + i + "]", "alias must not be blank");
            } else if (codePointLength(alias) > MAX_ALIAS_LENGTH) {
                add(violations, "FIELD_TOO_LONG", path + "[" + i + "]", "alias exceeds the maximum length");
            }
        }
    }

    private static void validateAliasAmbiguity(
            List<TermForAliasCheck> terms, List<Violation> violations) {
        Map<String, List<TermForAliasCheck>> owners = new HashMap<>();
        for (TermForAliasCheck term : terms) {
            addAliasOwner(owners, term.key(), term);
            addAliasOwner(owners, term.label(), term);
            for (String alias : term.aliases()) {
                addAliasOwner(owners, alias, term);
            }
        }
        for (TermForAliasCheck term : terms) {
            for (int i = 0; i < term.aliases().size(); i++) {
                String alias = term.aliases().get(i);
                List<TermForAliasCheck> matchingTerms = owners.getOrDefault(alias, List.of());
                if (matchingTerms.stream().anyMatch(other -> other != term)) {
                    addWarning(violations, "AMBIGUOUS_ALIAS", term.path() + ".aliases[" + i + "]",
                            "alias matches another term key, label, or alias");
                }
            }
        }
    }

    private static void addAliasOwner(
            Map<String, List<TermForAliasCheck>> owners, String token, TermForAliasCheck term) {
        if (token == null || token.isEmpty()) {
            return;
        }
        owners.computeIfAbsent(token, ignored -> new ArrayList<>()).add(term);
    }

    private static void validateConstraints(
            PropertyConstraints constraints,
            ValueType valueType,
            String path,
            List<Violation> violations) {
        if (constraints == null) {
            return;
        }
        if (valueType != ValueType.TEXT && constraints.allowedValues() != null) {
            add(violations, "CONSTRAINT_NOT_APPLICABLE", path + ".allowedValues",
                    "allowed values are only supported for text properties");
        }
        if (valueType != ValueType.DECIMAL) {
            if (constraints.minimum() != null) {
                add(violations, "CONSTRAINT_NOT_APPLICABLE", path + ".minimum",
                        "minimum is only supported for decimal properties");
            }
            if (constraints.maximum() != null) {
                add(violations, "CONSTRAINT_NOT_APPLICABLE", path + ".maximum",
                        "maximum is only supported for decimal properties");
            }
        }
        if (valueType == ValueType.TEXT) {
            validateAllowedValues(constraints.allowedValues(), path + ".allowedValues", violations);
        }
        if (valueType == ValueType.DECIMAL) {
            BigDecimal minimum = validateDecimalBound(constraints.minimum(), path + ".minimum", violations);
            BigDecimal maximum = validateDecimalBound(constraints.maximum(), path + ".maximum", violations);
            if (minimum != null && maximum != null && minimum.compareTo(maximum) > 0) {
                add(violations, "INVALID_CONSTRAINT_RANGE", path,
                        "minimum must be less than or equal to maximum");
            }
        }
    }

    private static void validateAllowedValues(
            List<String> allowedValues, String path, List<Violation> violations) {
        if (allowedValues == null) {
            return;
        }
        if (allowedValues.isEmpty()) {
            add(violations, "INVALID_ALLOWED_VALUES", path, "allowed values must not be empty");
        } else if (allowedValues.size() > MAX_ALLOWED_VALUES) {
            add(violations, "TOO_MANY_ALLOWED_VALUES", path,
                    "allowed values exceed the maximum size");
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < allowedValues.size(); i++) {
            String value = allowedValues.get(i);
            String valuePath = path + "[" + i + "]";
            if (value == null || value.isEmpty()) {
                add(violations, "INVALID_ALLOWED_VALUE", valuePath,
                        "allowed value must not be empty");
            } else {
                if (codePointLength(value) > MAX_ALIAS_LENGTH) {
                    add(violations, "FIELD_TOO_LONG", valuePath,
                            "allowed value exceeds the maximum length");
                }
                if (!seen.add(value)) {
                    add(violations, "DUPLICATE_ALLOWED_VALUE", valuePath,
                            "allowed value is duplicated");
                }
            }
        }
    }

    private static BigDecimal validateDecimalBound(
            String bound, String path, List<Violation> violations) {
        if (bound == null) {
            return null;
        }
        if (bound.isEmpty() || !DECIMAL_PATTERN.matcher(bound).matches()) {
            add(violations, "INVALID_DECIMAL_BOUND", path,
                    "decimal bound must use plain decimal notation");
            return null;
        }
        BigDecimal decimal;
        try {
            decimal = new BigDecimal(bound);
        } catch (NumberFormatException exception) {
            add(violations, "INVALID_DECIMAL_BOUND", path,
                    "decimal bound must be a valid decimal");
            return null;
        }
        if (decimal.precision() > MAX_CONSTRAINT_SIGNIFICANT_DIGITS) {
            add(violations, "DECIMAL_BOUND_TOO_PRECISE", path,
                    "decimal bound exceeds the significant digit limit");
        }
        if (Math.max(decimal.scale(), 0) > MAX_CONSTRAINT_FRACTIONAL_DIGITS) {
            add(violations, "DECIMAL_BOUND_TOO_PRECISE", path,
                    "decimal bound exceeds the fractional digit limit");
        }
        return decimal;
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

    private static void addWarning(List<Violation> violations, String code, String path, String message) {
        violations.add(new Violation(code, path, Violation.Severity.WARNING, message));
    }

    private record TermForAliasCheck(
            String key, String label, List<String> aliases, String path) {
    }
}

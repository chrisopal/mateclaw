package vip.mate.semantic.core.policy;

import java.net.URI;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import vip.mate.semantic.core.validation.ValidationReport;
import vip.mate.semantic.core.validation.Violation;

/** Versioned input policies, separate from OWL axioms and open-world reasoning. */
public record BusinessPolicySet(String version, List<Rule> rules) {
    public BusinessPolicySet {
        requireText(version, "policy version");
        rules = List.copyOf(rules);
    }

    public record Rule(String classIri, String predicateIri, boolean required,
                       String unit, Set<String> allowedLexicalValues, boolean singleValue) {
        public Rule(String classIri,String predicateIri,boolean required,String unit,Set<String> allowedLexicalValues) {
            this(classIri,predicateIri,required,unit,allowedLexicalValues,false);
        }
        public Rule {
            requireIri(classIri);
            requireIri(predicateIri);
            if (unit != null) requireText(unit, "unit");
            allowedLexicalValues = Set.copyOf(allowedLexicalValues);
        }
    }

    public record Literal(String lexicalValue, String datatypeIri, String unit) {
        public Literal {
            Objects.requireNonNull(lexicalValue, "literal");
            requireIri(datatypeIri);
            if (unit != null) requireText(unit, "unit");
        }
    }

    /** Partial fact submissions never turn a missing property into a required-field failure. */
    public ValidationReport validate(Set<String> classIris, Map<String, List<Literal>> properties,
                                     boolean completeSubmission) {
        Objects.requireNonNull(classIris, "classIris");
        Objects.requireNonNull(properties, "properties");
        var violations = new ArrayList<Violation>();
        for (Rule rule : rules) {
            if (!classIris.contains(rule.classIri())) continue;
            List<Literal> values = properties.getOrDefault(rule.predicateIri(), List.of());
            if (completeSubmission && rule.required() && values.isEmpty()) {
                add(violations, "BUSINESS_REQUIRED", rule.predicateIri(), "Required by business policy " + version);
            }
            for (Literal value : values) {
                if (rule.unit() != null && !rule.unit().equals(value.unit())) {
                    add(violations, "BUSINESS_UNIT_MISMATCH", rule.predicateIri(), "Expected unit " + rule.unit());
                }
                if (!rule.allowedLexicalValues().isEmpty()
                        && !rule.allowedLexicalValues().contains(value.lexicalValue())) {
                    add(violations, "BUSINESS_VALUE_NOT_ALLOWED", rule.predicateIri(), "Value is not in the business enumeration");
                }
            }
            if (completeSubmission && rule.singleValue() && hasDistinctValues(values)) {
                add(violations, "BUSINESS_SINGLE_VALUE", rule.predicateIri(),
                        "Only one distinct value is allowed by business policy " + version);
            }
        }
        return new ValidationReport(violations);
    }

    private static boolean hasDistinctValues(List<Literal> values) {
        for (int i = 0; i < values.size(); i++) {
            for (int j = i + 1; j < values.size(); j++) {
                if (!sameValue(values.get(i), values.get(j))) return true;
            }
        }
        return false;
    }

    /** Compare the value space where the policy contract has a JDK representation. */
    private static boolean sameValue(Literal left, Literal right) {
        if (!Objects.equals(left.datatypeIri(), right.datatypeIri())
                || !Objects.equals(left.unit(), right.unit())) return false;
        String datatype = left.datatypeIri();
        try {
            if (datatype.equals("http://www.w3.org/2001/XMLSchema#decimal")
                    || datatype.equals("http://www.w3.org/2001/XMLSchema#integer")
                    || datatype.equals("http://www.w3.org/2001/XMLSchema#long")
                    || datatype.equals("http://www.w3.org/2001/XMLSchema#int")
                    || datatype.equals("http://www.w3.org/2001/XMLSchema#short")
                    || datatype.equals("http://www.w3.org/2001/XMLSchema#byte")) {
                return new BigDecimal(left.lexicalValue()).compareTo(new BigDecimal(right.lexicalValue())) == 0;
            }
            if (datatype.equals("http://www.w3.org/2001/XMLSchema#boolean")) {
                return parseBoolean(left.lexicalValue()) == parseBoolean(right.lexicalValue());
            }
        } catch (NumberFormatException ignored) {
            return false;
        }
        return left.lexicalValue().equals(right.lexicalValue());
    }

    private static boolean parseBoolean(String value) {
        return switch (value) {
            case "true", "1" -> true;
            case "false", "0" -> false;
            default -> throw new NumberFormatException("Invalid xsd:boolean lexical value");
        };
    }

    private static void add(List<Violation> violations, String code, String path, String message) {
        violations.add(new Violation(code, path, Violation.Severity.ERROR, message));
    }

    private static void requireIri(String value) {
        requireText(value, "IRI");
        if (!URI.create(value).isAbsolute()) throw new IllegalArgumentException("Absolute IRI required");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " required");
    }
}

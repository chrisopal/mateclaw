package vip.mate.semantic.core.fact;

import java.net.URI;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * JDK-only business assertion envelope.
 *
 * <p>The Functional Syntax text is authoritative. The remaining fields are
 * derived indexes used for governance, lookup, and conflict comparison; an
 * OWL adapter must verify that they agree with the text before accepting the
 * assertion.</p>
 */
public record AssertionPayload(
        AssertionKind kind,
        String functionalSyntax,
        Set<String> signatureIris,
        Optional<String> subjectIri,
        Optional<String> predicateIri,
        Optional<String> objectIri,
        Optional<LiteralValue> literal,
        Optional<String> classExpressionFunctionalSyntax,
        Optional<String> relatedIndividualIri) {

    public AssertionPayload {
        kind = Objects.requireNonNull(kind, "kind");
        functionalSyntax = requireText(functionalSyntax, "functionalSyntax");
        signatureIris = Set.copyOf(Objects.requireNonNull(signatureIris, "signatureIris"));
        signatureIris.forEach(AssertionPayload::requireAbsoluteIri);
        subjectIri = normalizeIri(subjectIri, "subjectIri");
        predicateIri = normalizeIri(predicateIri, "predicateIri");
        objectIri = normalizeIri(objectIri, "objectIri");
        literal = literal == null ? Optional.empty() : literal;
        classExpressionFunctionalSyntax = normalizeText(
                classExpressionFunctionalSyntax, "classExpressionFunctionalSyntax");
        relatedIndividualIri = normalizeIri(relatedIndividualIri, "relatedIndividualIri");
        validateShape(kind, subjectIri, predicateIri, objectIri, literal,
                classExpressionFunctionalSyntax, relatedIndividualIri);
    }

    public static AssertionPayload classAssertion(
            String functionalSyntax,
            String subjectIri,
            String classExpressionFunctionalSyntax,
            Set<String> signatureIris) {
        return new AssertionPayload(
                AssertionKind.CLASS_ASSERTION,
                functionalSyntax,
                signatureIris,
                Optional.of(subjectIri),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(classExpressionFunctionalSyntax),
                Optional.empty());
    }

    public static AssertionPayload objectPropertyAssertion(
            String functionalSyntax,
            String subjectIri,
            String predicateIri,
            String objectIri,
            boolean negative,
            Set<String> signatureIris) {
        return new AssertionPayload(
                negative ? AssertionKind.NEGATIVE_OBJECT_PROPERTY : AssertionKind.POSITIVE_OBJECT_PROPERTY,
                functionalSyntax,
                signatureIris,
                Optional.of(subjectIri),
                Optional.of(predicateIri),
                Optional.of(objectIri),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    public static AssertionPayload dataPropertyAssertion(
            String functionalSyntax,
            String subjectIri,
            String predicateIri,
            LiteralValue literal,
            boolean negative,
            Set<String> signatureIris) {
        return new AssertionPayload(
                negative ? AssertionKind.NEGATIVE_DATA_PROPERTY : AssertionKind.POSITIVE_DATA_PROPERTY,
                functionalSyntax,
                signatureIris,
                Optional.of(subjectIri),
                Optional.of(predicateIri),
                Optional.empty(),
                Optional.of(literal),
                Optional.empty(),
                Optional.empty());
    }

    public static AssertionPayload individualIdentity(
            String functionalSyntax,
            String subjectIri,
            String relatedIndividualIri,
            boolean different,
            Set<String> signatureIris) {
        return new AssertionPayload(
                different ? AssertionKind.DIFFERENT_INDIVIDUAL : AssertionKind.SAME_INDIVIDUAL,
                functionalSyntax,
                signatureIris,
                Optional.of(subjectIri),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(relatedIndividualIri));
    }

    public boolean negative() {
        return kind == AssertionKind.NEGATIVE_OBJECT_PROPERTY
                || kind == AssertionKind.NEGATIVE_DATA_PROPERTY;
    }

    public boolean objectAssertion() {
        return kind == AssertionKind.POSITIVE_OBJECT_PROPERTY
                || kind == AssertionKind.NEGATIVE_OBJECT_PROPERTY;
    }

    public boolean dataAssertion() {
        return kind == AssertionKind.POSITIVE_DATA_PROPERTY
                || kind == AssertionKind.NEGATIVE_DATA_PROPERTY;
    }

    public boolean identityAssertion() {
        return kind == AssertionKind.SAME_INDIVIDUAL
                || kind == AssertionKind.DIFFERENT_INDIVIDUAL;
    }

    public enum AssertionKind {
        CLASS_ASSERTION,
        POSITIVE_OBJECT_PROPERTY,
        NEGATIVE_OBJECT_PROPERTY,
        POSITIVE_DATA_PROPERTY,
        NEGATIVE_DATA_PROPERTY,
        SAME_INDIVIDUAL,
        DIFFERENT_INDIVIDUAL
    }

    public record LiteralValue(String lexicalValue, String datatypeIri, Optional<String> languageTag) {
        public LiteralValue {
            lexicalValue = Objects.requireNonNull(lexicalValue, "lexicalValue");
            datatypeIri = requireText(datatypeIri, "datatypeIri");
            requireAbsoluteIri(datatypeIri);
            languageTag = languageTag == null ? Optional.empty() : languageTag;
            languageTag.ifPresent(value -> {
                if (value.isBlank()) {
                    throw new IllegalArgumentException("languageTag must not be blank");
                }
            });
        }

        public LiteralValue(String lexicalValue, String datatypeIri) {
            this(lexicalValue, datatypeIri, Optional.empty());
        }
    }

    private static void validateShape(
            AssertionKind kind,
            Optional<String> subjectIri,
            Optional<String> predicateIri,
            Optional<String> objectIri,
            Optional<LiteralValue> literal,
            Optional<String> classExpressionFunctionalSyntax,
            Optional<String> relatedIndividualIri) {
        switch (kind) {
            case CLASS_ASSERTION -> requireShape("class assertion requires a subject and class expression",
                    subjectIri.isPresent(), classExpressionFunctionalSyntax.isPresent(),
                    predicateIri.isEmpty(), objectIri.isEmpty(), literal.isEmpty(), relatedIndividualIri.isEmpty());
            case POSITIVE_OBJECT_PROPERTY, NEGATIVE_OBJECT_PROPERTY -> requireShape(
                    "object property assertion requires subject, predicate, and object IRIs",
                    subjectIri.isPresent(), predicateIri.isPresent(), objectIri.isPresent(),
                    literal.isEmpty(), classExpressionFunctionalSyntax.isEmpty(), relatedIndividualIri.isEmpty());
            case POSITIVE_DATA_PROPERTY, NEGATIVE_DATA_PROPERTY -> requireShape(
                    "data property assertion requires subject, predicate, and literal",
                    subjectIri.isPresent(), predicateIri.isPresent(), literal.isPresent(),
                    objectIri.isEmpty(), classExpressionFunctionalSyntax.isEmpty(), relatedIndividualIri.isEmpty());
            case SAME_INDIVIDUAL, DIFFERENT_INDIVIDUAL -> requireShape(
                    "individual identity assertion requires two individual IRIs",
                    subjectIri.isPresent(), relatedIndividualIri.isPresent(),
                    predicateIri.isEmpty(), objectIri.isEmpty(), literal.isEmpty(),
                    classExpressionFunctionalSyntax.isEmpty());
        }
    }

    private static void requireShape(String message, boolean... checks) {
        for (boolean check : checks) {
            if (!check) {
                throw new IllegalArgumentException(message);
            }
        }
    }

    private static Optional<String> normalizeIri(Optional<String> value, String name) {
        Optional<String> normalized = value == null ? Optional.empty() : value;
        normalized.ifPresent(item -> {
            requireText(item, name);
            requireAbsoluteIri(item);
        });
        return normalized;
    }

    private static Optional<String> normalizeText(Optional<String> value, String name) {
        Optional<String> normalized = value == null ? Optional.empty() : value;
        normalized.ifPresent(item -> requireText(item, name));
        return normalized;
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static void requireAbsoluteIri(String value) {
        try {
            if (!URI.create(value).isAbsolute()) {
                throw new IllegalArgumentException("absolute IRI required: " + value);
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("invalid absolute IRI: " + value, exception);
        }
    }
}

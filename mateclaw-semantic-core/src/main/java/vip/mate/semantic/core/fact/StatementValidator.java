package vip.mate.semantic.core.fact;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import vip.mate.semantic.core.identity.GraphScope;
import vip.mate.semantic.core.identity.SemanticIds.EntityId;
import vip.mate.semantic.core.ontology.OntologyRevision;
import vip.mate.semantic.core.validation.ValidationReport;
import vip.mate.semantic.core.validation.Violation;

/**
 * Governance validation for one business assertion.
 *
 * <p>OWL syntax, profile, and type checking are delegated to the injected
 * adapter port. Core only checks graph/revision ownership and that the derived
 * indexes are internally consistent with known entities.</p>
 */
public final class StatementValidator {

    private final AssertionValidationPort assertionValidationPort;

    public StatementValidator(AssertionValidationPort assertionValidationPort) {
        this.assertionValidationPort = Objects.requireNonNull(
                assertionValidationPort, "assertionValidationPort");
    }

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
        Map<EntityId, Entity> entities = referencedEntities == null ? Map.of() : referencedEntities;
        if (scope != null && !scope.equals(candidate.scope())) {
            add(violations, "SCOPE_MISMATCH", "scope", "statement scope does not match the target graph");
        }
        if (ontology != null && !ontology.revisionId().equals(candidate.ontologyRevisionId())) {
            add(violations, "ONTOLOGY_REVISION_MISMATCH", "ontologyRevisionId",
                    "statement references a different ontology revision");
        }

        Entity subject = entities.get(candidate.subjectId());
        if (subject == null) {
            add(violations, "UNKNOWN_SUBJECT", "subjectId", "statement subject is not in the graph");
        } else {
            if (!subject.scope().equals(candidate.scope())) {
                add(violations, "SCOPE_MISMATCH", "subjectId", "subject is in a different graph scope");
            }
            candidate.assertion().subjectIri().ifPresent(subjectIri -> {
                if (!subject.iri().equals(subjectIri)) {
                    add(violations, "SUBJECT_IRI_MISMATCH", "assertion.subjectIri",
                            "assertion subject IRI does not match the referenced entity");
                }
            });
        }
        validateAssertionIndexes(candidate, subject, entities, violations);

        if (ontology != null) {
            ValidationReport adapterReport = assertionValidationPort.validate(ontology, candidate, entities);
            violations.addAll(adapterReport.violations());
            if (adapterReport.valid() && subject != null
                    && candidate.assertion().kind() == AssertionPayload.AssertionKind.POSITIVE_DATA_PROPERTY) {
                var literal=candidate.assertion().literal().orElseThrow();
                try {
                    var value=new vip.mate.semantic.core.policy.BusinessPolicySet.Literal(
                            literal.lexicalValue(),literal.datatypeIri(),
                            assertionValidationPort.businessUnit(candidate.assertion()).orElse(null));
                    violations.addAll(ontology.policy().validate(subject.assertedTypes(),
                            Map.of(candidate.assertion().predicateIri().orElseThrow(),List.of(value)),false).violations());
                } catch (IllegalArgumentException e) {
                    add(violations,"BUSINESS_UNIT_INVALID","assertion","Explicit unit annotation is invalid");
                }
            }
        }
        return new ValidationReport(violations);
    }

    private static void validateAssertionIndexes(
            StatementContent candidate,
            Entity subject,
            Map<EntityId, Entity> entities,
            List<Violation> violations) {
        AssertionPayload assertion = candidate.assertion();
        assertion.subjectIri().ifPresent(subjectIri -> requireSignature(
                assertion, subjectIri, "assertion.subjectIri", violations));
        assertion.predicateIri().ifPresent(predicateIri -> {
            requireSignature(assertion, predicateIri, "assertion.predicateIri", violations);
            Optional<PredicateRef> predicate = candidate.predicate();
            if (predicate.isEmpty() || !predicate.orElseThrow().iri().equals(predicateIri)) {
                add(violations, "PREDICATE_MISMATCH", "predicate",
                        "statement predicate does not match assertion predicate IRI");
            }
        });
        if (assertion.objectAssertion()) {
            assertion.objectIri().ifPresent(objectIri -> {
                requireSignature(assertion, objectIri, "assertion.objectIri", violations);
                requireEntityIri(entities, objectIri, "assertion.objectIri", violations);
            });
        }
        assertion.relatedIndividualIri().ifPresent(relatedIri -> {
            requireSignature(assertion, relatedIri, "assertion.relatedIndividualIri", violations);
            requireEntityIri(entities, relatedIri, "assertion.relatedIndividualIri", violations);
        });
        if (assertion.predicateIri().isEmpty() && candidate.predicate().isPresent()) {
            add(violations, "UNEXPECTED_PREDICATE", "predicate",
                    "class and individual identity assertions do not carry a business predicate");
        }
        if (subject != null && !assertion.signatureIris().contains(subject.iri())) {
            add(violations, "SIGNATURE_MISSING_SUBJECT", "assertion.signatureIris",
                    "assertion signature must contain the subject IRI");
        }
    }

    private static void requireEntityIri(
            Map<EntityId, Entity> entities, String iri, String path, List<Violation> violations) {
        boolean known = entities.values().stream().anyMatch(entity -> entity != null && iri.equals(entity.iri()));
        if (!known) {
            add(violations, "UNKNOWN_ENTITY", path, "assertion individual IRI is not in the graph");
        }
    }

    private static void requireSignature(
            AssertionPayload assertion, String iri, String path, List<Violation> violations) {
        if (!assertion.signatureIris().contains(iri)) {
            add(violations, "SIGNATURE_MISSING_IRI", path,
                    "assertion signature does not contain the indexed IRI");
        }
    }

    private static void add(List<Violation> violations, String code, String path, String message) {
        violations.add(new Violation(code, path, Violation.Severity.ERROR, message));
    }
}

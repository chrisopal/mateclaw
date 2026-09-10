package vip.mate.semantic.object;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.semantic.core.fact.AssertionPayload;
import vip.mate.semantic.core.identity.SemanticIds.EntityId;
import vip.mate.semantic.core.policy.BusinessPolicySet;
import vip.mate.semantic.core.validation.ValidationReport;
import vip.mate.semantic.core.validation.Violation;
import vip.mate.semantic.graph.GraphApplicationService;
import vip.mate.semantic.graph.GraphRow;
import vip.mate.semantic.security.SemanticAccessService;
import vip.mate.semantic.statement.SemanticDomainMapper;
import vip.mate.semantic.web.OntologyDtos;
import vip.mate.semantic.web.SemanticApiException;
import vip.mate.semantic.web.StatementDtos;
import vip.mate.semantic.owl.OwlAssertionAdapter;

import static vip.mate.semantic.object.CompleteObjectValidationDtos.*;

/**
 * Validates one complete object against a pinned graph revision without creating facts or
 * completion state. Partial statement proposals keep their existing open-world behaviour.
 */
@Service
@ConditionalOnProperty(name = "mateclaw.semantic.enabled", havingValue = "true")
public class CompleteObjectValidationService {
    static final int MAX_ASSERTIONS = 100;
    static final int MAX_ASSERTION_BYTES = 100_000;
    static final int MAX_BATCH_BYTES = 1_048_576;

    private final GraphApplicationService graphs;
    private final SemanticAccessService access;
    private final SemanticDomainMapper domain;
    private final OwlAssertionAdapter assertions;

    public CompleteObjectValidationService(GraphApplicationService graphs,
            SemanticAccessService access, SemanticDomainMapper domain,
            OwlAssertionAdapter assertions) {
        this.graphs = Objects.requireNonNull(graphs);
        this.access = Objects.requireNonNull(access);
        this.domain = Objects.requireNonNull(domain);
        this.assertions = Objects.requireNonNull(assertions);
    }

    // The graph row is locked for the duration of the validation so the CAS snapshot is stable.
    @Transactional
    public ValidationView validate(String scope, String graphId, ValidationRequest request) {
        access.require(scope, "member");
        validateRequest(request);
        GraphRow graph = graphs.requireGraph(scope, graphId, true);
        if (!Boolean.TRUE.equals(graph.getEnabled())) {
            throw conflict("GRAPH_DISABLED", "Graph is disabled");
        }
        if (!request.expectedGraphVersion().equals(graph.getMutationVersion())) {
            throw conflict("GRAPH_VERSION_CONFLICT", "Graph changed; reload the complete object");
        }
        if (!request.expectedOntologyRevisionId().equals(graph.getOntologyRevisionId())) {
            throw conflict("ONTOLOGY_REVISION_CONFLICT", "Graph ontology revision changed; reload the complete object");
        }

        Map<EntityId, vip.mate.semantic.core.fact.Entity> entities = domain.entities(graph);
        EntityId entityId = new EntityId(request.entityId());
        vip.mate.semantic.core.fact.Entity entity = entities.get(entityId);
        if (entity == null) throw new SemanticApiException(404, "NOT_FOUND", "Entity not found in graph");

        var ontology = domain.ontology(graph);
        List<Violation> violations = new ArrayList<>();
        Map<String, List<BusinessPolicySet.Literal>> properties = new LinkedHashMap<>();
        Map<String, List<String>> propertyPaths = new HashMap<>();
        Map<String, List<String>> objectPropertyPaths = new HashMap<>();
        for (int index = 0; index < request.assertions().size(); index++) {
            String text = request.assertions().get(index);
            String path = "assertions[" + index + "]";
            AssertionPayload payload;
            try {
                payload = assertions.parse(text);
            } catch (IllegalArgumentException exception) {
                violations.add(new Violation("OWL_SYNTAX_INVALID", path, Violation.Severity.ERROR,
                        safeMessage(exception)));
                continue;
            }
            try {
                StatementDtos.ProposeRequest statement = new StatementDtos.ProposeRequest(
                        "complete-object-validation-" + index,
                        request.entityId(), text, "UNKNOWN", null, null, List.of());
                var content = domain.content(graph, statement, payload);
                violations.addAll(domain.validation(ontology, graph, content, entities).violations().stream()
                        .filter(v -> !isPolicyAggregate(v.code()))
                        .map(v -> prefix(v, path)).toList());
                if (payload.kind() == AssertionPayload.AssertionKind.POSITIVE_OBJECT_PROPERTY) {
                    objectPropertyPaths.computeIfAbsent(payload.predicateIri().orElseThrow(), ignored -> new ArrayList<>())
                            .add(path);
                }
                if (payload.kind() == AssertionPayload.AssertionKind.POSITIVE_DATA_PROPERTY) {
                    String predicate = payload.predicateIri().orElseThrow();
                    String unit;
                    try {
                        unit = assertions.businessUnit(payload).orElse(null);
                    } catch (IllegalArgumentException exception) {
                        violations.add(new Violation("BUSINESS_UNIT_INVALID", path,
                                Violation.Severity.ERROR, safeMessage(exception)));
                        continue;
                    }
                    var literal = payload.literal().orElseThrow();
                    properties.computeIfAbsent(predicate, ignored -> new ArrayList<>())
                            .add(new BusinessPolicySet.Literal(literal.lexicalValue(), literal.datatypeIri(), unit));
                    propertyPaths.computeIfAbsent(predicate, ignored -> new ArrayList<>()).add(path);
                }
            } catch (SemanticApiException exception) {
                violations.add(new Violation(exception.code(), path, Violation.Severity.ERROR,
                        safeMessage(exception)));
            } catch (RuntimeException exception) {
                violations.add(new Violation("INVALID_ASSERTION", path, Violation.Severity.ERROR,
                        safeMessage(exception)));
            }
        }

        ValidationReport policy = ontology.policy().validate(entity.assertedTypes(), properties, true);
        violations.addAll(policy.violations().stream().map(v -> policyPath(v, propertyPaths)).toList());
        for (BusinessPolicySet.Rule rule : ontology.policy().rules()) {
            if (rule.required() && objectPropertyPaths.containsKey(rule.predicateIri())
                    && !properties.containsKey(rule.predicateIri())) {
                for (String path : objectPropertyPaths.get(rule.predicateIri())) {
                    violations.add(new Violation("BUSINESS_OBJECT_PROPERTY_UNSUPPORTED", path,
                            Violation.Severity.ERROR,
                            "Complete-object business policy rules require a data literal; object property values are not accepted"));
                }
            }
        }
        return new ValidationView(graph.getId(), graph.getMutationVersion(), graph.getOntologyRevisionId(),
                ontology.policy().version(), request.entityId(), violations.stream()
                        .noneMatch(v -> v.severity() == Violation.Severity.ERROR), violations.stream()
                        .map(this::wireViolation).toList());
    }

    private static void validateRequest(ValidationRequest request) {
        if (request == null || request.expectedGraphVersion() == null
                || request.expectedGraphVersion() < 0
                || request.expectedOntologyRevisionId() == null
                || request.expectedOntologyRevisionId().isBlank()
                || request.expectedOntologyRevisionId().length() > 256
                || request.entityId() == null || request.entityId().isBlank()
                || request.entityId().length() > 256
                || request.assertions() == null
                || request.assertions().size() > MAX_ASSERTIONS) {
            throw bad("expected graph/revision, entityId, and one to " + MAX_ASSERTIONS + " assertions are required");
        }
        long bytes = 0;
        for (String assertion : request.assertions()) {
            if (assertion == null || assertion.isBlank()) throw bad("Assertion text must not be blank");
            int length = assertion.getBytes(StandardCharsets.UTF_8).length;
            if (length > MAX_ASSERTION_BYTES) throw new SemanticApiException(413, "ASSERTION_TOO_LARGE", "Assertion exceeds 100000 bytes");
            bytes += length;
        }
        if (bytes > MAX_BATCH_BYTES) throw new SemanticApiException(413, "ASSERTIONS_TOO_LARGE", "Assertions exceed 1 MiB");
    }

    private OntologyDtos.Violation wireViolation(Violation violation) {
        return new OntologyDtos.Violation(violation.code(), violation.path(), violation.message(), violation.severity().name());
    }

    private static Violation prefix(Violation value, String path) {
        return new Violation(value.code(), path + "." + value.path(), value.severity(), value.message());
    }

    private static Violation policyPath(Violation value, Map<String, List<String>> paths) {
        List<String> matches = paths.getOrDefault(value.path(), List.of());
        return matches.isEmpty() ? value : new Violation(value.code(), matches.getFirst() + "." + value.path(), value.severity(), value.message());
    }

    private static boolean isPolicyAggregate(String code) {
        return code.equals("BUSINESS_REQUIRED") || code.equals("BUSINESS_UNIT_MISMATCH")
                || code.equals("BUSINESS_VALUE_NOT_ALLOWED") || code.equals("BUSINESS_SINGLE_VALUE");
    }

    private static String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? "Assertion validation failed" : message;
    }

    private static SemanticApiException bad(String message) { return new SemanticApiException(400, "INVALID_REQUEST", message); }
    private static SemanticApiException conflict(String code, String message) { return new SemanticApiException(409, code, message); }
}

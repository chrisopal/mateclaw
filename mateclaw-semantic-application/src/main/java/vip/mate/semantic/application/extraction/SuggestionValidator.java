package vip.mate.semantic.application.extraction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import vip.mate.semantic.core.fact.*;
import vip.mate.semantic.core.identity.GraphScope;
import vip.mate.semantic.core.identity.SemanticIds.EntityId;
import vip.mate.semantic.core.ontology.OntologyRevision;
import vip.mate.semantic.core.validation.ValidationReport;
import vip.mate.semantic.core.validation.Violation;
import static vip.mate.semantic.application.extraction.ExtractionContracts.*;

/** Evidence checks and delegation to the one core statement rule authority. */
public final class SuggestionValidator {
    public static final int MAX_SUGGESTIONS = 200;

    public boolean matches(String snapshot, Quote quote) {
        if (snapshot == null || quote == null || quote.exactQuote() == null
                || quote.startCodePoint() < 0 || quote.endCodePoint() <= quote.startCodePoint()
                || quote.endCodePoint() > snapshot.codePointCount(0, snapshot.length())) {
            return false;
        }
        int begin = snapshot.offsetByCodePoints(0, quote.startCodePoint());
        int end = snapshot.offsetByCodePoints(0, quote.endCodePoint());
        return snapshot.substring(begin, end).equals(quote.exactQuote());
    }

    public static void requireWithinLimit(int count) {
        if (count < 0 || count > MAX_SUGGESTIONS) {
            throw new IllegalArgumentException("suggestion count exceeds extraction limits");
        }
    }

    public ValidationReport validate(GraphScope scope, OntologyRevision ontology, StatementContent content,
                                     Map<EntityId, Entity> entities, String snapshot, List<Quote> quotes) {
        List<Violation> violations = new ArrayList<>(new StatementValidator()
                .validate(scope, ontology, content, entities).violations());
        if (quotes == null || quotes.isEmpty()) {
            violations.add(error("EVIDENCE_REQUIRED", "quotes", "at least one exact source quote is required"));
        } else {
            for (int i = 0; i < quotes.size(); i++) {
                if (!matches(snapshot, quotes.get(i))) {
                    violations.add(error("QUOTE_MISMATCH", "quotes[" + i + "]", "quote does not match the source snapshot"));
                }
            }
        }
        return new ValidationReport(violations);
    }

    /** Temporary entities are used only to validate model terminology, never to resolve business identity. */
    public ValidationReport validateRaw(GraphScope scope, OntologyRevision ontology, RawSuggestion raw,
                                        String snapshot) {
        if (raw == null || raw.subject() == null || raw.predicate() == null || ontology == null || scope == null) {
            return new ValidationReport(List.of(error("INVALID_SUGGESTION", "$", "suggestion structure is incomplete")));
        }
        try {
            EntityId subjectId = new EntityId("1");
            Map<EntityId, Entity> entities = new HashMap<>();
            entities.put(subjectId, new Entity(subjectId, scope, raw.subject().typeKey(), raw.subject().name()));
            StatementValue value = raw.value();
            if (raw.predicate() instanceof PredicateRef.RelationRef) {
                if (raw.target() == null || value != null) {
                    return new ValidationReport(List.of(error("INVALID_SUGGESTION", "target", "relation requires a target mention and no literal value")));
                }
                EntityId targetId = new EntityId("2");
                entities.put(targetId, new Entity(targetId, scope, raw.target().typeKey(), raw.target().name()));
                value = new StatementValue.EntityValue(targetId);
            } else if (raw.target() != null || value == null || value instanceof StatementValue.EntityValue) {
                return new ValidationReport(List.of(error("INVALID_SUGGESTION", "value", "property requires a core literal value and no target")));
            }
            StatementContent candidate = new StatementContent(scope, ontology.revisionId(), subjectId,
                    raw.predicate(), value, raw.validity(), Set.of());
            return validate(scope, ontology, candidate, entities, snapshot, raw.quotes());
        } catch (IllegalArgumentException | NullPointerException exception) {
            return new ValidationReport(List.of(error("INVALID_SUGGESTION", "$", "suggestion structure is invalid")));
        }
    }

    private static Violation error(String code, String path, String message) {
        return new Violation(code, path, Violation.Severity.ERROR, message);
    }
}

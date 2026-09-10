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
    private final AssertionValidationPort assertions;
    public SuggestionValidator(AssertionValidationPort assertions) { this.assertions = java.util.Objects.requireNonNull(assertions); }

    public static boolean matches(String snapshot, Quote quote) {
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
        List<Violation> violations = new ArrayList<>(new StatementValidator(assertions)
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
        if (raw == null || raw.subject() == null || raw.assertion() == null || ontology == null || scope == null)
            return new ValidationReport(List.of(error("INVALID_SUGGESTION", "$", "suggestion structure is incomplete")));
        try {
            EntityId subjectId = new EntityId("1");
            Map<EntityId, Entity> entities = new HashMap<>();
            entities.put(subjectId,new Entity(subjectId,scope,raw.subject().temporaryRef(),raw.subject().typeIris(),raw.subject().name()));
            if (raw.target()!=null) {
                EntityId targetId=new EntityId("2");
                entities.put(targetId,new Entity(targetId,scope,raw.target().temporaryRef(),raw.target().typeIris(),raw.target().name()));
            }
            java.util.Optional<PredicateRef> predicate=raw.assertion().predicateIri().map(iri -> raw.assertion().literal().isPresent() ? PredicateRef.property(iri) : PredicateRef.relation(iri));
            StatementContent candidate=new StatementContent(scope,ontology.revisionId(),subjectId,predicate,raw.assertion(),raw.validity(),Set.of());
            return validate(scope,ontology,candidate,entities,snapshot,raw.quotes());
        } catch(IllegalArgumentException | NullPointerException exception) {
            return new ValidationReport(List.of(error("INVALID_SUGGESTION", "$", "suggestion structure is invalid")));
        }
    }

    private static Violation error(String code, String path, String message) {
        return new Violation(code, path, Violation.Severity.ERROR, message);
    }
}

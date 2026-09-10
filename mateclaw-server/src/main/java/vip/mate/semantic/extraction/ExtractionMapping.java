package vip.mate.semantic.extraction;

import static vip.mate.semantic.application.extraction.ExtractionContracts.*;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import vip.mate.semantic.core.fact.AssertionPayload;
import vip.mate.semantic.core.fact.Entity;
import vip.mate.semantic.core.fact.StatementContent;
import vip.mate.semantic.core.fact.Validity;
import vip.mate.semantic.core.identity.SemanticIds.EntityId;
import vip.mate.semantic.owl.OwlAssertionAdapter;

final class ExtractionMapping {
    private ExtractionMapping() {}

    static RawSuggestion raw(ExtractionDtos.EditRequest edit, OwlAssertionAdapter assertions) {
        Objects.requireNonNull(edit, "edit");
        Objects.requireNonNull(assertions, "assertions");
        if (!Set.of("UNKNOWN", "INTERVAL").contains(edit.validityKind())
                || (edit.status() != null && !Set.of("OPEN", "IGNORED").contains(edit.status()))) {
            throw new IllegalArgumentException("invalid suggestion enum");
        }
        if ("UNKNOWN".equals(edit.validityKind())
                && (edit.validFrom() != null || edit.validTo() != null)) {
            throw new IllegalArgumentException("unknown time cannot have endpoints");
        }
        ObjectMention subject = mention("subject", edit.subjectIri(), edit.subjectTypeIris(), edit.subjectName());
        AssertionPayload assertion = assertions.parse(edit.assertionText());
        if (!assertion.subjectIri().filter(subject.temporaryRef()::equals).isPresent()) {
            throw new IllegalArgumentException("assertion subject does not match subjectIri");
        }

        Optional<String> targetIri = assertion.objectIri().or(() -> assertion.relatedIndividualIri());
        ObjectMention target = null;
        if (targetIri.isPresent()) {
            if (edit.targetIri() == null || !targetIri.get().equals(edit.targetIri())) {
                throw new IllegalArgumentException("assertion target does not match targetIri");
            }
            target = mention("target", edit.targetIri(), edit.targetTypeIris(), edit.targetName());
        } else if (edit.targetIri() != null || edit.targetEntityId() != null
                || (edit.targetTypeIris() != null && !edit.targetTypeIris().isEmpty())
                || edit.targetName() != null) {
            throw new IllegalArgumentException("target mention is not allowed for this assertion");
        }
        Validity validity = "UNKNOWN".equals(edit.validityKind())
                ? Validity.unknown() : Validity.interval(edit.validFrom(), edit.validTo());
        List<Quote> quotes = edit.quotes() == null ? List.of() : edit.quotes().stream()
                .map(q -> new Quote(q.startCodePoint(), q.endCodePoint(), q.exactQuote())).toList();
        return new RawSuggestion(subject, assertion, target, validity, quotes);
    }

    static ExtractionDtos.SuggestionView view(Suggestion suggestion, Receipt receipt) {
        return view(suggestion, receipt, null, Map.of());
    }

    static ExtractionDtos.SuggestionView view(Suggestion suggestion, Receipt receipt, String pending,
            Map<EntityId, Entity> entities) {
        RawSuggestion raw = suggestion.content();
        StatementContent mapped = suggestion.mappedContent();
        AssertionPayload assertion = raw.assertion();
        String targetIri = assertion.objectIri().or(() -> assertion.relatedIndividualIri()).orElse(null);
        ObjectMention target = raw.target();
        String targetEntityId = targetIri == null ? null : entities.entrySet().stream()
                .filter(entry -> targetIri.equals(entry.getValue().iri()))
                .map(entry -> entry.getKey().value()).findFirst().orElse(null);
        return new ExtractionDtos.SuggestionView(
                suggestion.suggestionId(), suggestion.editVersion(), suggestion.status().name(),
                raw.subject().temporaryRef(), raw.subject().typeIris(), raw.subject().name(),
                mapped == null ? null : mapped.subjectId().value(), assertion.functionalSyntax(),
                target == null ? null : target.temporaryRef(),
                target == null ? Set.of() : target.typeIris(), target == null ? null : target.name(), targetEntityId,
                raw.validity().kind().name(), raw.validity().fromInclusive(), raw.validity().toExclusive(),
                raw.quotes().stream().map(q -> new ExtractionDtos.Quote(
                        q.startCodePoint(), q.endCodePoint(), q.exactQuote())).toList(),
                suggestion.diagnostics().stream().map(v -> v.code()).toList(),
                receipt == null ? null : receipt.submission().statementId(), pending);
    }

    private static ObjectMention mention(String temporaryRef, String iri, Set<String> typeIris, String name) {
        if (iri == null || iri.isBlank() || !URI.create(iri).isAbsolute()) {
            throw new IllegalArgumentException(temporaryRef + " IRI must be absolute");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException(temporaryRef + " name is required");
        }
        Set<String> types = typeIris == null ? Set.of() : Set.copyOf(typeIris);
        for (String type : types) {
            if (type == null || type.isBlank() || !URI.create(type).isAbsolute()) {
                throw new IllegalArgumentException("type IRIs must be absolute");
            }
        }
        return new ObjectMention(iri, types, name);
    }
}

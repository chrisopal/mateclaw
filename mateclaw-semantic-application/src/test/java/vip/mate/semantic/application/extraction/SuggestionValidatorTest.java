package vip.mate.semantic.application.extraction;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static vip.mate.semantic.application.extraction.ExtractionContracts.*;
class SuggestionValidatorTest {
    @Test void reusesCoreValueRangeAndUnitRulesForUnmappedSuggestions() {
        var scope = new vip.mate.semantic.core.identity.GraphScope(
            new vip.mate.semantic.core.identity.SemanticIds.WorkspaceId("1"),
            new vip.mate.semantic.core.identity.SemanticIds.KnowledgeBaseId("2"),
            new vip.mate.semantic.core.identity.SemanticIds.GraphId("3"));
        var ontology = new vip.mate.semantic.core.ontology.OntologyRevision(
            new vip.mate.semantic.core.identity.SemanticIds.OntologyRevisionId("4"),
            new vip.mate.semantic.core.identity.SemanticIds.OntologyId("5"), 1,
            new vip.mate.semantic.core.ontology.OntologyDefinition(java.util.List.of(
                new vip.mate.semantic.core.ontology.EntityTypeDefinition("Equipment", "设备", "")),
                java.util.List.of(new vip.mate.semantic.core.ontology.PropertyDefinition("reading", "读数", "", "Equipment",
                vip.mate.semantic.core.ontology.ValueType.DECIMAL, vip.mate.semantic.core.ontology.Multiplicity.SINGLE,
                java.util.Optional.of("mm"), java.util.List.of(), false,
                new vip.mate.semantic.core.ontology.PropertyConstraints(null, "0", "1"))), java.util.List.of()));
        var raw = new RawSuggestion(new ObjectMention("local-1", "Equipment", "设备甲"),
            vip.mate.semantic.core.fact.PredicateRef.property("reading"),
            new vip.mate.semantic.core.fact.StatementValue.DecimalValue("2", "V"), null, null,
            java.util.List.of(new Quote(0, 1, "2")));
        var report = new SuggestionValidator().validateRaw(scope, ontology, raw, "2");
        assertTrue(report.violations().stream().anyMatch(v -> v.code().equals("VALUE_ABOVE_MAXIMUM")));
        assertTrue(report.violations().stream().anyMatch(v -> v.code().equals("UNIT_MISMATCH")));
        assertTrue(raw.validity().isUnknown());
    }

    @Test void quoteUsesCodePoints() {
        assertTrue(new SuggestionValidator().matches("甲😀乙", new Quote(1, 2, "😀")));
        assertFalse(new SuggestionValidator().matches("甲😀乙", new Quote(1, 2, "乙")));
    }
    @Test void rejectsInvalidAndEmptyRanges() {
        var validator = new SuggestionValidator();
        for (var quote : java.util.List.of(new Quote(-1, 1, "甲"), new Quote(2, 1, ""), new Quote(0, 4, "甲"), new Quote(1, 1, ""), new Quote(0, Integer.MAX_VALUE, "甲"))) {
            assertFalse(validator.matches("甲😀乙", quote));
        }
        assertFalse(validator.matches(null, new Quote(0, 1, "甲")));
        assertFalse(validator.matches("甲", null));
    }
    @Test void refusesTooManySuggestionsWithoutTruncating() {
        assertDoesNotThrow(() -> SuggestionValidator.requireWithinLimit(200));
        assertThrows(IllegalArgumentException.class, () -> SuggestionValidator.requireWithinLimit(201));
        assertThrows(IllegalArgumentException.class, () -> SuggestionValidator.requireWithinLimit(-1));
    }
}

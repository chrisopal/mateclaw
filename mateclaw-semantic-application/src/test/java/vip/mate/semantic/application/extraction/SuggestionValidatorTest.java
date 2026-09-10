package vip.mate.semantic.application.extraction;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static vip.mate.semantic.application.extraction.ExtractionContracts.*;
class SuggestionValidatorTest {
    @Test void delegatesOntologyValidationAndPreservesDiagnostics() {
        var fixture=new ExtractionCoordinatorTest();
        var raw=new RawSuggestion(new ObjectMention(OwlExtractionFixtures.SUBJECT,
            java.util.Set.of(OwlExtractionFixtures.TYPE),"Machine"),OwlExtractionFixtures.assertion(),null,null,
            java.util.List.of(new Quote(0,1,"2")));
        var called=new java.util.concurrent.atomic.AtomicBoolean();
        var validator=new SuggestionValidator((ontology,content,entities)->{
            called.set(true);
            assertEquals(raw.assertion(),content.assertion());
            assertEquals(OwlExtractionFixtures.SUBJECT,entities.get(content.subjectId()).iri());
            return new vip.mate.semantic.core.validation.ValidationReport(java.util.List.of(
                new vip.mate.semantic.core.validation.Violation("INVALID_LITERAL","assertion",
                    vip.mate.semantic.core.validation.Violation.Severity.ERROR,"Rejected by standard adapter")));
        });
        var report=validator.validateRaw(fixture.scope,fixture.ontology,raw,"2");
        assertTrue(called.get());
        assertTrue(report.violations().stream().anyMatch(v->v.code().equals("INVALID_LITERAL")));
        assertTrue(raw.validity().isUnknown());
    }

    @Test void quoteUsesCodePoints() {
        assertTrue(SuggestionValidator.matches("甲😀乙", new Quote(1, 2, "😀")));
        assertFalse(SuggestionValidator.matches("甲😀乙", new Quote(1, 2, "乙")));
    }
    @Test void rejectsInvalidAndEmptyRanges() {
        var validator = new SuggestionValidator(OwlExtractionFixtures.VALID);
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

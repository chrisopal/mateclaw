package vip.mate.semantic.extraction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import vip.mate.semantic.core.fact.AssertionPayload;
import vip.mate.semantic.owl.OwlAssertionAdapter;

class ExtractionMappingTest {
    private static final String SUBJECT = "urn:mateclaw:extraction:subject";
    private static final String TARGET = "urn:mateclaw:extraction:target";
    private static final String PROPERTY = "urn:test:hasPart";

    @Test
    void mapsTemporaryObjectMentionAndKeepsAssertionAuthority() {
        var request = new ExtractionDtos.EditRequest(1L, "op", "OPEN", SUBJECT,
                Set.of("urn:test:Equipment"), "machine", null,
                "ObjectPropertyAssertion(<" + PROPERTY + "> <" + SUBJECT + "> <" + TARGET + ">)",
                TARGET, Set.of("urn:test:Part"), "cover", "entity-2", "UNKNOWN", null, null,
                List.of(new ExtractionDtos.Quote(0, 7, "machine")));

        var raw = ExtractionMapping.raw(request, new OwlAssertionAdapter());

        assertEquals(AssertionPayload.AssertionKind.POSITIVE_OBJECT_PROPERTY, raw.assertion().kind());
        assertEquals(SUBJECT, raw.subject().temporaryRef());
        assertEquals(TARGET, raw.target().temporaryRef());
        assertEquals(PROPERTY, raw.assertion().predicateIri().orElseThrow());
    }

    @Test
    void rejectsMentionFieldsThatDoNotMatchParsedAxiom() {
        var request = new ExtractionDtos.EditRequest(1L, "op", "OPEN", SUBJECT,
                Set.of(), "machine", null,
                "ObjectPropertyAssertion(<" + PROPERTY + "> <urn:test:other> <" + TARGET + ">)",
                TARGET, Set.of(), "cover", "entity-2", "UNKNOWN", null, null, List.of());

        assertThrows(IllegalArgumentException.class,
                () -> ExtractionMapping.raw(request, new OwlAssertionAdapter()));
    }
}

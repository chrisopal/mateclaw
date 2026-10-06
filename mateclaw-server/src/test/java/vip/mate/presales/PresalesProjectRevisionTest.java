package vip.mate.presales;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PresalesProjectRevisionTest {
    private final ObjectMapper json = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(longs = {1, 2147483647L, 2147483648L, 9007199254740991L})
    void numericAndHistoricalTextAreExactAndPersistedNodeIdentityMatches(long value)
            throws Exception {
        for (var node :
                new com.fasterxml.jackson.databind.JsonNode[] {
                    json.readTree(Long.toString(value)),
                    json.getNodeFactory().textNode(" " + value + " ")
                }) {
            assertEquals(value, PresalesProjectRevision.positiveRevision(node));
            assertTrue(PresalesProjectRevision.matchesRevision(node, value));
            assertFalse(PresalesProjectRevision.matchesRevision(node, value - 1));
        }
        var envelope = json.createObjectNode();
        envelope.set("projectVersion", PresalesProjectRevision.number(value));
        assertEquals(envelope, json.readTree(json.writeValueAsString(envelope)));
        if (value < PresalesProjectRevision.MAX_VALUE)
            assertEquals(value + 1, PresalesProjectRevision.nextRevision(value));
        else
            assertEquals(
                    "VERSION_EXHAUSTED",
                    assertThrows(
                                    PresalesRejected.class,
                                    () -> PresalesProjectRevision.nextRevision(value))
                            .code());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "0",
                "-1",
                "9007199254740992",
                "9223372036854775808",
                "1.0",
                "1.5",
                "\"9007199254740992\"",
                "\"1e0\"",
                "\"1.5\"",
                "null",
                "true",
                "{}",
                "[]"
            })
    void badStoredProjectVersionsNeverMatch(String raw) throws Exception {
        var node = json.readTree(raw);
        assertNull(PresalesProjectRevision.positiveRevision(node));
        assertFalse(PresalesProjectRevision.matchesRevision(node, 1));
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, 0, 9007199254740992L, Long.MAX_VALUE, Long.MIN_VALUE})
    void invalidNumbersCannotAdvanceOrCreateToolAuthority(long value) {
        assertEquals(
                "VERSION_CONFLICT",
                assertThrows(
                                PresalesRejected.class,
                                () -> PresalesProjectRevision.nextRevision(value))
                        .code());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new PresalesToolScope(
                                "w",
                                "a",
                                "p",
                                "t",
                                "r",
                                "o",
                                java.util.List.of(),
                                "employee",
                                value));
    }
}

package vip.mate.presales;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PresalesProjectItemsTest {
    private final ObjectMapper json = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(strings = {"2147483647", "4294967297", "1.5", "\"1.5\"", "0", "null", "{}", "[]"})
    void invalidOrExhaustedRevisionLeavesBothItemAndRequestUntouched(String raw) throws Exception {
        for (boolean immutable : new boolean[] {false, true}) {
            var p = json.createObjectNode();
            var old = p.putArray("items").addObject().put("id", "old");
            old.set("version", json.readTree(raw));
            var value = json.createObjectNode().put("id", "old").put("title", "new");
            var before = p.deepCopy();
            var input = value.deepCopy();
            var error =
                    assertThrows(
                            PresalesRejected.class,
                            () ->
                                    PresalesProjectItems.saveItem(
                                            p, "items", value, "actor", immutable));
            assertEquals(409, error.status());
            assertEquals(
                    raw.equals("2147483647") ? "VERSION_EXHAUSTED" : "VERSION_CONFLICT",
                    error.code());
            assertEquals(before, p);
            assertEquals(input, value);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void lastItemRevisionRemainsPositiveAndPreservesReplacementPolicy(boolean immutable) {
        var p = json.createObjectNode();
        p.putArray("items").addObject().put("id", "old").put("version", Integer.MAX_VALUE - 1);
        var before = p.path("items").get(0).deepCopy();
        var value = json.createObjectNode().put("id", "old");
        PresalesProjectItems.saveItem(p, "items", value, "actor", immutable);
        assertEquals(Integer.MAX_VALUE, value.path("version").intValue());
        assertEquals(immutable ? 2 : 1, p.path("items").size());
        if (immutable) assertEquals(before, p.path("items").get(0));
        else assertEquals("old", value.path("id").asText());
    }

    @Test
    void mutableReplacementMovesToEndAndRetainsStringIdentity() {
        var p = json.createObjectNode();
        p.putArray("requirements").addObject().put("id", "9007199254740993001").put("version", 2);
        p.withArray("requirements").addObject().put("id", "second");
        var value =
                json.createObjectNode().put("id", "9007199254740993001").put("extension", "keep");
        var before = LocalDateTime.now(java.time.ZoneOffset.UTC);
        PresalesProjectItems.saveItem(p, "requirements", value, "actor", false);
        assertEquals(2, p.path("requirements").size());
        assertEquals("second", p.path("requirements").get(0).path("id").asText());
        assertSame(value, p.path("requirements").get(1));
        assertEquals("9007199254740993001", value.path("id").asText());
        assertTrue(value.path("id").isTextual());
        assertEquals(3, value.path("version").asInt());
        assertEquals("actor", value.path("authorId").asText());
        assertEquals("keep", value.path("extension").asText());
        assertFalse(LocalDateTime.parse(value.path("createdAt").asText()).isBefore(before));
    }

    @Test
    void immutableReplacementAppendsWithoutMutatingOldRevision() {
        var p = json.createObjectNode();
        var old =
                p.putArray("solutions")
                        .addObject()
                        .put("id", "original")
                        .put("version", 5)
                        .put("title", "old");
        var frozen = old.deepCopy();
        var v = json.createObjectNode().put("id", "original");
        PresalesProjectItems.saveItem(p, "solutions", v, "actor", true);
        assertEquals(2, p.path("solutions").size());
        assertEquals(frozen, p.path("solutions").get(0));
        assertEquals("original", v.path("previousId").asText());
        assertNotEquals("original", v.path("id").asText());
        assertDoesNotThrow(() -> java.util.UUID.fromString(v.path("id").asText()));
        assertEquals(6, v.path("version").asInt());
    }

    @Test
    void missingItemAndWrongShapeKeepDistinctFailureSemantics() {
        var p = json.createObjectNode();
        var missing =
                assertThrows(
                        PresalesRejected.class,
                        () -> PresalesProjectItems.find(p, "tasks", "missing"));
        assertEquals(404, missing.status());
        assertEquals("NOT_FOUND", missing.code());
        assertEquals("tasks item not found", missing.getMessage());
        assertTrue(p.path("tasks").isArray());
        var v = json.createObjectNode().put("id", "missing");
        assertThrows(
                PresalesRejected.class,
                () -> PresalesProjectItems.saveItem(p, "tasks", v, "a", false));
        assertEquals(json.createObjectNode().put("id", "missing"), v);
        assertEquals(0, p.path("tasks").size());
        p.put("tasks", "invalid");
        assertThrows(UnsupportedOperationException.class, () -> p.withArray("tasks"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> PresalesProjectItems.find(p, "tasks", "id"));
    }

    @Test
    void enumCoercionAndUtf16LengthRemainLegacyCompatible() {
        var v = json.createObjectNode().putNull("status");
        PresalesProjectItems.enumValue(v, "status", Set.of("OPEN", "ANSWERED"), "OPEN");
        assertEquals("OPEN", v.path("status").asText());
        v.put("status", "open");
        var e =
                assertThrows(
                        PresalesRejected.class,
                        () -> PresalesProjectItems.enumValue(v, "status", Set.of("OPEN"), "OPEN"));
        assertEquals(400, e.status());
        assertEquals("INVALID_REQUEST", e.code());
        assertEquals("Invalid status", e.getMessage());
        assertDoesNotThrow(() -> PresalesProjectItems.text("😀", "title", 2));
        assertThrows(PresalesRejected.class, () -> PresalesProjectItems.text("😀", "title", 1));
    }
}

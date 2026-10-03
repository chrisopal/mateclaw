package vip.mate.presales;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;

class PresalesProjectListingTest {
    private final ObjectMapper json = new ObjectMapper();
    private static final List<String> SOURCES =
            List.of(
                    "materials",
                    "requirements",
                    "clarifications",
                    "baselines",
                    "fitGaps",
                    "cases",
                    "solutions",
                    "reviews",
                    "reviewDrafts",
                    "releases",
                    "tasks",
                    "contextCards");

    private ObjectNode project(String id, String name, String customer) {
        var p =
                json.createObjectNode()
                        .put("id", id)
                        .put("name", name)
                        .put("customer", customer)
                        .put("status", "ACTIVE")
                        .put("ownerId", "owner");
        for (var source : SOURCES) p.putArray(source);
        return p;
    }

    @Test
    void stageUsesExistingPriorityAndCallerSeesOriginalMissingArrayBehavior() {
        var p = json.createObjectNode().put("status", "ARCHIVED");
        assertEquals("ARCHIVED", PresalesProjectListing.stage(p));
        assertFalse(p.has("releases"));
        p.put("status", "ACTIVE");
        assertEquals("DISCOVERY", PresalesProjectListing.stage(p));
        for (var key : List.of("releases", "solutions", "baselines", "requirements"))
            assertTrue(p.path(key).isArray(), key);
        for (var entry :
                List.of(
                        "requirements:REQUIREMENTS",
                        "baselines:BASELINED",
                        "solutions:SOLUTION",
                        "releases:RELEASE")) {
            String[] parts = entry.split(":");
            p.withArray(parts[0]).addObject();
            assertEquals(parts[1], PresalesProjectListing.stage(p));
        }
        p.put("status", "ARCHIVED");
        assertEquals("ARCHIVED", PresalesProjectListing.stage(p));
    }

    @Test
    void summariesCopyHistoricalMetadataButRemoveOriginalSourceArrays() {
        var p = project("9007199254740993001", "Original", "Customer");
        p.putObject("legacyExtension").put("text", "原文 Ω");
        p.withArray("clarifications").addObject().put("status", "ANSWERED");
        p.withArray("clarifications").addObject().put("status", "OPEN");
        p.withArray("clarifications").addObject();
        p.withArray("solutions").addObject().put("version", 8);
        p.withArray("solutions").addObject().put("version", 2);
        var before = p.deepCopy();
        var page =
                PresalesProjectListing.page(
                        List.of(p).stream(),
                        new PresalesProjectListing.Criteria(null, null, null, null, 1, 20),
                        SOURCES);
        var summary = page.items().getFirst();
        assertEquals(before, p);
        assertEquals(2, summary.path("openClarificationCount").asInt());
        assertEquals(2, summary.path("latestSolutionVersion").asInt());
        assertEquals("SOLUTION", summary.path("stage").asText());
        for (String key : SOURCES) assertFalse(summary.has(key), key);
        ((ObjectNode) summary.path("legacyExtension")).put("text", "changed");
        assertEquals("原文 Ω", p.path("legacyExtension").path("text").asText());
        assertTrue(summary.path("id").isTextual());
    }

    @Test
    void rootLocaleSearchAndExactFiltersPreserveInputOrder() {
        var first = project("first", "İSTANBUL", "original");
        var second = project("second", "Other", "İSTANBUL");
        var excluded = project("third", "İSTANBUL", "other").put("ownerId", "OTHER");
        var page =
                PresalesProjectListing.page(
                        List.of(first, second, excluded).stream(),
                        new PresalesProjectListing.Criteria(
                                "i\u0307stanbul", "ACTIVE", "owner", "DISCOVERY", 1, 20),
                        SOURCES);
        assertEquals(2, page.total());
        assertEquals(
                List.of("first", "second"),
                page.items().stream().map(p -> p.path("id").asText()).toList());
        assertEquals(
                0,
                PresalesProjectListing.page(
                                List.of(first).stream(),
                                new PresalesProjectListing.Criteria(
                                        null, "active", null, null, 1, 20),
                                SOURCES)
                        .total());
    }

    @Test
    void totalCountsFilteredItemsBeforePagingAndLargePageDoesNotOverflow() {
        var first = project("first", "Find one", "Customer");
        var second = project("second", "Find two", "Customer");
        var third = project("third", "Other", "Customer");
        var page =
                PresalesProjectListing.page(
                        List.of(first, second, third).stream(),
                        new PresalesProjectListing.Criteria("find", "", "", "", 2, 1),
                        SOURCES);
        assertEquals(2, page.total());
        assertEquals(2, page.page());
        assertEquals(1, page.pageSize());
        assertEquals("second", page.items().getFirst().path("id").asText());
        var empty =
                PresalesProjectListing.page(
                        List.of(first, second).stream(),
                        new PresalesProjectListing.Criteria(
                                null, null, null, null, Integer.MAX_VALUE, 100),
                        SOURCES);
        assertEquals(2, empty.total());
        assertTrue(empty.items().isEmpty());
    }

    @Test
    void malformedStageStopsBeforeDecodingLaterHistoricalRows() {
        var malformed =
                json.createObjectNode().put("status", "ACTIVE").put("releases", "wrong-shape");
        var decoded = new java.util.concurrent.atomic.AtomicInteger();
        var rows =
                java.util.stream.Stream.of(malformed, json.createObjectNode())
                        .map(
                                p -> {
                                    if (decoded.incrementAndGet() > 1)
                                        throw new IllegalStateException("later-row-decode");
                                    return p;
                                });
        var failure =
                assertThrows(
                        RuntimeException.class,
                        () ->
                                PresalesProjectListing.page(
                                        rows,
                                        new PresalesProjectListing.Criteria(
                                                null, null, null, "DISCOVERY", 1, 20),
                                        SOURCES));
        assertTrue(failure.getMessage().contains("releases"));
        assertEquals(1, decoded.get());
    }

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> substringCases() {
        return java.util.stream.Stream.of(
                org.junit.jupiter.params.provider.Arguments.of("A%_\\B", "%_\\", true),
                org.junit.jupiter.params.provider.Arguments.of("A%B", "A_B", false),
                org.junit.jupiter.params.provider.Arguments.of("Café", "cafe", false),
                org.junit.jupiter.params.provider.Arguments.of("e\u0301", "é", false),
                org.junit.jupiter.params.provider.Arguments.of("Σ", "ς", false),
                org.junit.jupiter.params.provider.Arguments.of("Σ", "σ", true),
                org.junit.jupiter.params.provider.Arguments.of("İ", "i", true),
                org.junit.jupiter.params.provider.Arguments.of("İ", "i\u0307", true),
                org.junit.jupiter.params.provider.Arguments.of("İ", "ı", false),
                org.junit.jupiter.params.provider.Arguments.of("I", "ı", false),
                org.junit.jupiter.params.provider.Arguments.of("ß", "SS", false),
                org.junit.jupiter.params.provider.Arguments.of("\ud83d\ude00", "\ud83d", true),
                org.junit.jupiter.params.provider.Arguments.of("\ud83d\ude00", "\ude00", true),
                org.junit.jupiter.params.provider.Arguments.of("x\ud800y", "?", false),
                org.junit.jupiter.params.provider.Arguments.of("x\ud800y", "\ud800", true),
                org.junit.jupiter.params.provider.Arguments.of(" Title ", " title ", true),
                org.junit.jupiter.params.provider.Arguments.of(" Title ", "  ", false),
                org.junit.jupiter.params.provider.Arguments.of("", "", true));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("substringCases")
    void substringIsLiteralRootLocaleUtf16WithoutDatabaseCollation(
            String name, String query, boolean matches) {
        var p = project("original", name, "");
        var page =
                PresalesProjectListing.page(
                        List.of(p).stream(),
                        new PresalesProjectListing.Criteria(query, null, null, null, 1, 20),
                        SOURCES);
        assertEquals(matches ? 1 : 0, page.total());
    }

    @Test
    void nullAndUnknownMetadataRemainRawWhileComputedKeysAreReplaced() {
        var p = project("00007", "raw", "customer");
        p.putNull("ownerId").putNull("customer");
        p.put("stage", "raw-stage")
                .put("openClarificationCount", -99)
                .put("latestSolutionVersion", -99);
        p.putObject("futureExtension").putNull("value").put("original", " Ω ");
        p.withArray("clarifications").addObject().put("status", "answered");
        p.withArray("clarifications").addNull();
        var before = p.deepCopy();
        var page =
                PresalesProjectListing.page(
                        List.of(p).stream(),
                        new PresalesProjectListing.Criteria("null", null, "null", null, 1, 20),
                        SOURCES);
        assertEquals(1, page.total());
        var result = page.items().getFirst();
        assertTrue(result.path("customer").isNull());
        assertTrue(result.path("ownerId").isNull());
        assertEquals("DISCOVERY", result.path("stage").asText());
        assertEquals(2, result.path("openClarificationCount").asInt());
        assertEquals(0, result.path("latestSolutionVersion").asInt());
        assertEquals(before.path("futureExtension"), result.path("futureExtension"));
        assertEquals(before, p);
    }

    @Test
    void storageEscapesOnlyIsolatedSurrogatesWithoutNormalizingJsonOrBusinessText()
            throws Exception {
        String normal = " {\"value\":\" Ω Café e\u0301 😀 \\\\uD800 \"} ";
        assertEquals(normal, PresalesListingProjectionV1.storageJson(normal));
        var body = project("id", "x\ud800y\udc00z😀", "Customer");
        String encoded = json.writeValueAsString(body);
        String stored = PresalesListingProjectionV1.storageJson(encoded);
        assertTrue(stored.contains("\\ud800"));
        assertTrue(stored.contains("\\udc00"));
        assertTrue(stored.contains("😀"));
        assertEquals(body, json.readTree(stored));
        assertEquals(stored, PresalesListingProjectionV1.storageJson(stored));
    }
}

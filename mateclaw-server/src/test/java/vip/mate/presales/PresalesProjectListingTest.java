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
}

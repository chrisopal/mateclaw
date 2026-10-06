package vip.mate.presales.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import vip.mate.presales.PresalesListingProjectionV1;

/** Pure storage encoding; public business versions are never storage revision numbers. */
final class PresalesObjectCodec {
    static final String OBJECT_REFS = "_objectRefs";
    static final String SECTION_REFS = "_sectionRefs";
    static final List<String> COLLECTIONS =
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
    private static final ObjectMapper JSON = new ObjectMapper();

    private PresalesObjectCodec() {}

    static boolean isV2(String body) {
        try {
            return JSON.readTree(body).path("storageVersion").asInt() == 2;
        } catch (JsonProcessingException | NullPointerException malformedLegacyBody) {
            return false;
        }
    }

    static ObjectNode object(String body) {
        try {
            if (JSON.readTree(body) instanceof ObjectNode node) return node;
        } catch (JsonProcessingException malformed) {
            throw new IllegalStateException("Invalid V2 storage JSON", malformed);
        }
        throw new IllegalStateException("V2 storage object required");
    }

    static String encode(JsonNode value) {
        return PresalesListingProjectionV1.storageJson(value.toString());
    }

    static String id(JsonNode item) {
        if (!item.path("id").isTextual() || item.path("id").asText().isBlank())
            throw new IllegalStateException("V2 object identity required");
        return item.path("id").asText();
    }

    static long revision(JsonNode ref) {
        var value = ref.path("revision");
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 1)
            throw new IllegalStateException("V2 object revision required");
        return value.longValue();
    }

    static ObjectNode reference(String id, long revision) {
        return JSON.createObjectNode().put("id", id).put("revision", revision);
    }
}

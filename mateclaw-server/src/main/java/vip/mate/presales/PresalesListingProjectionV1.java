package vip.mate.presales;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Locale;

/** Frozen listing contract used by live writes and V218. Future semantics require a new version. */
public final class PresalesListingProjectionV1 {
    private PresalesListingProjectionV1() {}

    public static final int CONTRACT_VERSION = 1;
    public static final List<String> SOURCE_COLLECTIONS =
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
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    /** Derived SQL facts; they are neither business authority nor a source-access credential. */
    public record Projection(
            int contractVersion,
            String nameKey,
            String customerKey,
            String statusKey,
            String ownerKey,
            String stageKey,
            String summaryJson,
            String decodeFailure,
            String stageFailure,
            String summaryFailure) {}

    /**
     * Encode UTF-16 units, not Unicode code points or UTF-8, to preserve String.contains exactly.
     */
    public static String key(String text) {
        var out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            int c = text.charAt(i);
            out.append('/')
                    .append(HEX[(c >>> 12) & 15])
                    .append(HEX[(c >>> 8) & 15])
                    .append(HEX[(c >>> 4) & 15])
                    .append(HEX[c & 15]);
        }
        return out.append('/').toString();
    }

    public static String searchKey(String text) {
        return key(text.toLowerCase(Locale.ROOT));
    }

    /** Preserve normal JSON bytes; escape isolated UTF-16 units before UTF-8 JDBC transport. */
    public static String storageJson(String encoded) {
        var out = new StringBuilder(encoded.length());
        for (int i = 0; i < encoded.length(); i++) {
            char c = encoded.charAt(i);
            if (Character.isHighSurrogate(c)
                    && i + 1 < encoded.length()
                    && Character.isLowSurrogate(encoded.charAt(i + 1))) {
                out.append(c).append(encoded.charAt(++i));
            } else if (Character.isSurrogate(c)) {
                out.append("\\u")
                        .append(HEX[(c >>> 12) & 15])
                        .append(HEX[(c >>> 8) & 15])
                        .append(HEX[(c >>> 4) & 15])
                        .append(HEX[c & 15]);
            } else out.append(c);
        }
        return out.toString();
    }

    public static Projection fromBody(String body, ObjectMapper json) {
        final ObjectNode p;
        try {
            p = (ObjectNode) json.readTree(body);
            if (p == null) throw new NullPointerException();
        } catch (Exception e) {
            return new Projection(
                    CONTRACT_VERSION,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    e.getClass().getSimpleName(),
                    null,
                    null);
        }
        String name = searchKey(p.path("name").asText()),
                customer = searchKey(p.path("customer").asText());
        String status = key(p.path("status").asText()), owner = key(p.path("ownerId").asText());
        final String stage;
        try {
            stage = stage(p.deepCopy());
        } catch (RuntimeException e) {
            return new Projection(
                    CONTRACT_VERSION,
                    name,
                    customer,
                    status,
                    owner,
                    null,
                    null,
                    null,
                    failureField(e),
                    null);
        }
        try {
            String summary =
                    storageJson(json.writeValueAsString(summary(p.deepCopy(), SOURCE_COLLECTIONS)));
            return new Projection(
                    CONTRACT_VERSION,
                    name,
                    customer,
                    status,
                    owner,
                    key(stage),
                    summary,
                    null,
                    null,
                    null);
        } catch (Exception e) {
            return new Projection(
                    CONTRACT_VERSION,
                    name,
                    customer,
                    status,
                    owner,
                    key(stage),
                    null,
                    null,
                    null,
                    failureField(e));
        }
    }

    private static String failureField(Exception e) {
        String message = e.getMessage();
        if (message != null)
            for (String field :
                    List.of("releases", "solutions", "baselines", "requirements", "clarifications"))
                if (message.contains(field)) return field;
        return "structure";
    }

    static ObjectNode summary(ObjectNode p, List<String> sourceCollections) {
        ObjectNode summary = p.deepCopy();
        for (String key : sourceCollections) summary.remove(key);
        summary.put("stage", stage(p))
                .put(
                        "openClarificationCount",
                        java.util.stream.StreamSupport.stream(
                                        p.withArray("clarifications").spliterator(), false)
                                .filter(c -> !"ANSWERED".equals(c.path("status").asText()))
                                .count())
                .put(
                        "latestSolutionVersion",
                        p.withArray("solutions").isEmpty()
                                ? 0
                                : p.withArray("solutions")
                                        .get(p.withArray("solutions").size() - 1)
                                        .path("version")
                                        .asInt());
        return summary;
    }

    static String stage(ObjectNode p) {
        if ("ARCHIVED".equals(p.path("status").asText())) return "ARCHIVED";
        if (!p.withArray("releases").isEmpty()) return "RELEASE";
        if (!p.withArray("solutions").isEmpty()) return "SOLUTION";
        if (!p.withArray("baselines").isEmpty()) return "BASELINED";
        if (!p.withArray("requirements").isEmpty()) return "REQUIREMENTS";
        return "DISCOVERY";
    }
}

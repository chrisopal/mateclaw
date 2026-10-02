package vip.mate.presales;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import vip.mate.presales.PresalesDtos.Page;

/** Domain list projection only. Callers own authorization, decoding and pagination validation. */
final class PresalesProjectListing {
    private PresalesProjectListing() {}

    record Criteria(
            String query, String status, String ownerId, String stageFilter, int page, int size) {}

    static Page page(
            Stream<ObjectNode> projects, Criteria criteria, List<String> sourceCollections) {
        String query = criteria.query(),
                status = criteria.status(),
                ownerId = criteria.ownerId(),
                stageFilter = criteria.stageFilter();
        int page = criteria.page(), size = criteria.size();
        String q = query == null ? "" : query.toLowerCase(Locale.ROOT);
        var all =
                projects.filter(
                                p ->
                                        p.path("name").asText().toLowerCase(Locale.ROOT).contains(q)
                                                || p.path("customer")
                                                        .asText()
                                                        .toLowerCase(Locale.ROOT)
                                                        .contains(q))
                        .filter(
                                p ->
                                        status == null
                                                || status.isBlank()
                                                || status.equals(p.path("status").asText()))
                        .filter(
                                p ->
                                        ownerId == null
                                                || ownerId.isBlank()
                                                || ownerId.equals(p.path("ownerId").asText()))
                        .filter(
                                p ->
                                        stageFilter == null
                                                || stageFilter.isBlank()
                                                || stageFilter.equals(stage(p)))
                        .map(p -> summary(p, sourceCollections))
                        .toList();
        return new Page(
                all.stream().skip((long) (page - 1) * size).limit(size).toList(),
                all.size(),
                page,
                size);
    }

    private static ObjectNode summary(ObjectNode p, List<String> sourceCollections) {
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

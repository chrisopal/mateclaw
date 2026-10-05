package vip.mate.presales;

import static vip.mate.presales.PresalesProjectItems.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

/** Draft, coverage and release rules over an already-authorized project, without persistence. */
final class PresalesSolutionPolicy {
    private final ObjectMapper json;

    PresalesSolutionPolicy(ObjectMapper json) {
        this.json = json;
    }

    void prepare(ObjectNode p, ObjectNode value, boolean employeeResult) {
        text(value.path("title").asText(), "title", 1000);
        if (!value.path("sections").isArray() || value.path("sections").isEmpty())
            throw bad("Solution sections required");
        for (var section : value.path("sections")) {
            text(section.path("title").asText(), "section title", 1000);
            text(section.path("text").asText(), "section text", 100000);
        }
        String base = value.path("baselineId").asText();
        if (!employeeResult && value.has("presentation"))
            throw new PresalesRejected(
                    422, "PRESENTATION_METADATA_UNTRUSTED", "PRESENTATION_METADATA_UNTRUSTED");
        ObjectNode baseline = null;
        if (!base.isBlank()) {
            baseline = find(p, "baselines", base);
            Integer baselineVersion = positiveRevision(baseline.path("version"));
            if (baselineVersion == null
                    || (value.has("baselineVersion")
                            && !matchesRevision(value.path("baselineVersion"), baselineVersion)))
                throw conflict("BASELINE_STALE", "Solution baseline version is stale");
            value.put("baselineVersion", baselineVersion);
        }
        validateProjectSourceRefs(p, value);
        if (base.isBlank()) value.remove("baselineVersion");
        value.put("provisional", base.isBlank());
        value.set("coverage", coverage(p, value));
        var latestFits = new LinkedHashMap<String, String>();
        for (var fit : p.withArray("fitGaps"))
            latestFits.put(fit.path("requirementId").asText(), fit.path("id").asText());
        value.set("fitGapRefs", json.valueToTree(latestFits.values()));
    }

    private void validateProjectSourceRefs(ObjectNode p, ObjectNode value) {
        Set<String> allowed = new HashSet<>();
        for (var task : p.withArray("tasks"))
            for (var source : task.path("contextSnapshot").path("sources"))
                allowed.add(source.path("sourceRef").asText());
        for (var baseline : p.withArray("baselines"))
            for (var reference : baseline.path("references"))
                for (var source : reference.path("sources"))
                    allowed.add(source.path("sourceRef").asText());
        validateSourceRefs(value.path("sourceRefs"), allowed);
        for (var section : value.path("sections"))
            validateSourceRefs(section.path("sourceRefs"), allowed);
    }

    private void validateSourceRefs(JsonNode refs, Set<String> allowed) {
        if (refs.isMissingNode()) return;
        if (!refs.isArray() || refs.size() > 100)
            throw new PresalesRejected(422, "MODEL_FORMAT", "MODEL_FORMAT");
        for (var ref : refs)
            if (!ref.isTextual() || !allowed.contains(ref.asText()))
                throw new PresalesRejected(
                        422, "INVALID_SOURCE_REFERENCE", "INVALID_SOURCE_REFERENCE");
    }

    ObjectNode coverage(ObjectNode p, ObjectNode solution) {
        String baselineId = solution.path("baselineId").asText();
        Map<String, String> scopes = new LinkedHashMap<>();
        if (!baselineId.isBlank())
            for (var ref : find(p, "baselines", baselineId).path("references"))
                scopes.put(ref.path("requirementId").asText(), ref.path("scope").asText());
        else
            for (var ref : p.withArray("requirements"))
                scopes.put(ref.path("id").asText(), ref.path("scope").asText());
        Set<String> linked = new HashSet<>();
        for (var section : solution.path("sections"))
            for (var ref : section.path("requirementRefs")) {
                if (!ref.isTextual() || !scopes.containsKey(ref.asText()))
                    throw bad("Section requirementRefs must belong to selected baseline");
                linked.add(ref.asText());
            }
        Map<String, ObjectNode> responses = new LinkedHashMap<>();
        for (var response : solution.path("requirementResponses")) {
            if (!(response instanceof ObjectNode item))
                throw bad("Requirement response object required");
            String rid = item.path("requirementId").asText();
            if (!scopes.containsKey(rid) || responses.containsKey(rid))
                throw bad("Unique baseline requirement response required");
            enumValue(
                    item,
                    "status",
                    Set.of("FULL", "PARTIAL", "CONDITIONAL", "EXCLUDED", "UNHANDLED"),
                    "UNHANDLED");
            if ("EXCLUDED".equals(item.path("status").asText()) && !"OUT".equals(scopes.get(rid)))
                throw bad("Only out-of-scope requirements can be EXCLUDED");
            if (Set.of("FULL", "PARTIAL", "CONDITIONAL").contains(item.path("status").asText())
                    && !linked.contains(rid)) throw bad("Handled response must link to a section");
            if (!"FULL".equals(item.path("status").asText())
                    && !"UNHANDLED".equals(item.path("status").asText()))
                text(item.path("reason").asText(), "response reason", 10000);
            responses.put(rid, item);
        }
        ObjectNode result = json.createObjectNode();
        ArrayNode list = result.putArray("responses");
        int total = 0, handled = 0;
        for (var entry : scopes.entrySet()) {
            ObjectNode response = responses.get(entry.getKey());
            if (response == null)
                response =
                        json.createObjectNode()
                                .put("requirementId", entry.getKey())
                                .put("status", "UNHANDLED");
            list.add(response.deepCopy());
            if ("IN".equals(entry.getValue())) {
                total++;
                if (Set.of("FULL", "PARTIAL", "CONDITIONAL")
                        .contains(response.path("status").asText())) handled++;
            }
        }
        result.put("applicable", total > 0).put("totalIn", total).put("handledIn", handled);
        if (total == 0) result.putNull("percentage");
        else result.put("percentage", Math.round(handled * 10000.0 / total) / 100.0);
        return result;
    }

    ObjectNode releaseBaseline(ObjectNode p, ObjectNode solution) {
        if (solution.path("provisional").asBoolean(true))
            throw conflict("BASELINE_REQUIRED", "Provisional solutions cannot be released");
        var baseline = find(p, "baselines", solution.path("baselineId").asText());
        var coverage = coverage(p, solution);
        if (coverage.path("handledIn").asInt() != coverage.path("totalIn").asInt())
            throw conflict(
                    "REQUIREMENTS_UNHANDLED",
                    "Every in-scope requirement needs an explicit linked response before release");
        if (!baseline.equals(p.withArray("baselines").get(p.withArray("baselines").size() - 1)))
            throw conflict("BASELINE_STALE", "Solution uses an older baseline");
        if (baseline.path("references").size() != p.withArray("requirements").size())
            throw conflict("BASELINE_STALE", "Requirements added after baseline approval");
        return baseline;
    }

    String releaseReviewId(ObjectNode p, ObjectNode solution) {
        boolean reviewed = false;
        String reviewId = "";
        for (var review : p.withArray("reviews"))
            if ("HUMAN_REVIEW".equals(review.path("kind").asText())
                    && !"UNTRUSTED_DRAFT".equals(review.path("authority").asText())
                    && solution.path("id").asText().equals(review.path("solutionId").asText())
                    && !solution.path("authorId")
                            .asText()
                            .equals(review.path("authorId").asText())) {
                boolean blocked = false;
                for (var issue : review.path("issues"))
                    if (!"RESOLVED".equals(issue.path("status").asText())
                            && "BLOCKER".equals(issue.path("severity").asText())) blocked = true;
                reviewed = !blocked;
                reviewId = review.path("id").asText();
            }
        if (!reviewed)
            throw conflict(
                    "INDEPENDENT_REVIEW_REQUIRED",
                    "A separate reviewer must inspect the exact solution and resolve blockers");
        return reviewId;
    }

    private static PresalesRejected bad(String message) {
        return new PresalesRejected(400, "INVALID_REQUEST", message);
    }

    private static PresalesRejected conflict(String code, String message) {
        return new PresalesRejected(409, code, message);
    }
}

package vip.mate.presales;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;

/** Legacy wire adapter; patch only owned fields so opaque fields and their order survive. */
final class PresalesReviewCodec {
    private PresalesReviewCodec() {}

    static PresalesReviewSave.Draft decode(ObjectNode value) {
        List<PresalesReviewSave.IssueDraft> issues = null;
        if (value.path("issues").isArray()) {
            issues = new ArrayList<>();
            for (var issue : value.path("issues")) {
                issues.add(
                        issue instanceof ObjectNode
                                ? new PresalesReviewSave.IssueDraft(
                                        issue.path("severity").asText("WARNING"),
                                        issue.path("status").asText("OPEN"))
                                : null);
            }
        }
        return new PresalesReviewSave.Draft(
                value.path("solutionId").asText(), value.path("summary").asText(), issues);
    }

    static void apply(ObjectNode value, List<PresalesReviewSave.Issue> issues) {
        for (int i = 0; i < issues.size(); i++) {
            var issue = issues.get(i);
            ((ObjectNode) value.path("issues").get(i))
                    .put("severity", issue.severity().name())
                    .put("status", issue.status().name());
        }
        value.remove("authority");
        value.put("kind", "HUMAN_REVIEW").put("authority", "HUMAN_REVIEW");
    }
}

package vip.mate.presales;

import static vip.mate.presales.PresalesProjectItems.find;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;

/** Candidate handoff content only; the caller owns authorization, publication and persistence. */
final class PresalesReleaseSnapshot {
    private final ObjectMapper json;

    PresalesReleaseSnapshot(ObjectMapper json) {
        this.json = json;
    }

    ObjectNode candidate(
            String scope, ObjectNode project, ObjectNode solution, ObjectNode release) {
        ObjectNode handoff =
                json.createObjectNode()
                        .put("schemaVersion", 1)
                        .put("engagementId", project.path("id").asText())
                        .put("caseRef", project.path("id").asText())
                        .put("workspaceId", scope)
                        .put("historicalClarificationsAvailable", true)
                        .put("customerConfirmationStatus", "UNCONFIRMED")
                        .put("accessPolicy", "WORKSPACE_REAUTHORIZE_ON_READ");
        var baseline = find(project, "baselines", release.path("baselineId").asText());
        handoff.set("baseline", baseline.deepCopy());
        handoff.set("solution", solution.deepCopy());
        handoff.set("release", release.deepCopy());
        ArrayNode chosenFits = handoff.putArray("fitGaps");
        for (var fitId : solution.path("fitGapRefs"))
            chosenFits.add(find(project, "fitGaps", fitId.asText()).deepCopy());
        handoff.set("clarifications", project.path("clarifications").deepCopy());
        handoff.set("sourceRefs", baseline.path("references").deepCopy());
        handoff.set("materials", project.path("materials").deepCopy());
        ArrayNode risks = handoff.putArray("risksAndUnknowns");
        for (var fit : chosenFits)
            if (Set.of("UNKNOWN", "GAP", "EXTEND", "PARTNER").contains(fit.path("status").asText()))
                risks.add(fit.deepCopy());
        for (var response : solution.path("coverage").path("responses"))
            if (!"FULL".equals(response.path("status").asText())) risks.add(response.deepCopy());
        return handoff;
    }
}

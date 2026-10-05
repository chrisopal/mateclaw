package vip.mate.presales;

import static vip.mate.presales.PresalesProjectItems.saveItem;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Set;

/**
 * Converts an already-authorized accepted employee result into untrusted project drafts. The
 * application service owns task acceptance, authority checks and transactional persistence.
 */
final class PresalesEmployeeResultProjection {
    private final ObjectMapper json;
    private final PresalesSolutionPolicy solutionPolicy;

    PresalesEmployeeResultProjection(ObjectMapper json, PresalesSolutionPolicy solutionPolicy) {
        this.json = json;
        this.solutionPolicy = solutionPolicy;
    }

    void apply(ObjectNode p, ObjectNode task, String actor) {
        String skill = task.path("skill").asText();
        ObjectNode result = (ObjectNode) task.path("result");
        String taskId = task.path("id").asText();
        String agentId = task.path("agentId").asText();
        if (Set.of("S1", "S2").contains(skill)) {
            for (var item : result.path("items")) {
                if (!"CLARIFICATION".equals(item.path("kind").asText())) {
                    ObjectNode draft = ((ObjectNode) item).deepCopy();
                    draft.remove(List.of("id", "approved", "customerConfirmationStatus"));
                    draft.put("proposedByTaskId", taskId)
                            .put("agentId", agentId)
                            .put("authority", "UNTRUSTED_DRAFT");
                    if ("S2".equals(skill)) {
                        draft.put("scope", "UNKNOWN")
                                .put("priority", "MEDIUM")
                                .put("customerConfirmationStatus", "UNCONFIRMED");
                        saveItem(p, "requirements", draft, actor, false);
                    } else saveItem(p, "contextCards", draft, actor, false);
                }
                if ("CLARIFICATION".equals(item.path("kind").asText())) {
                    ObjectNode clarification =
                            json.createObjectNode()
                                    .put("question", item.path("text").asText())
                                    .put("status", "OPEN")
                                    .put("proposedByTaskId", taskId)
                                    .put("agentId", agentId)
                                    .put("authority", "UNTRUSTED_DRAFT");
                    clarification.set("sourceRefs", item.path("sourceRefs").deepCopy());
                    saveItem(p, "clarifications", clarification, actor, false);
                }
            }
            return;
        }
        switch (skill) {
            case "S3" -> {
                for (var item : result.path("capabilityMaps")) {
                    ObjectNode draft = ((ObjectNode) item).deepCopy();
                    draft.put("proposedByTaskId", taskId)
                            .put("agentId", agentId)
                            .put("authority", "UNTRUSTED_DRAFT")
                            .put("kind", "CAPABILITY_MAP");
                    saveItem(p, "fitGaps", draft, actor, true);
                }
            }
            case "S4" -> {
                for (var item : result.path("cases")) {
                    ObjectNode draft = ((ObjectNode) item).deepCopy();
                    draft.put("proposedByTaskId", taskId)
                            .put("agentId", agentId)
                            .put("authority", "UNTRUSTED_DRAFT")
                            .put("kind", "CASE_MATCH");
                    saveItem(p, "cases", draft, actor, true);
                }
            }
            case "S5", "S6" -> {
                JsonNode node =
                        result.has("solution")
                                ? result.path("solution")
                                : result.path("solutionDraft");
                ObjectNode draft = ((ObjectNode) node).deepCopy();
                draft.put("proposedByTaskId", taskId)
                        .put("agentId", agentId)
                        .put("authority", "UNTRUSTED_DRAFT");
                solutionPolicy.prepare(p, draft, true);
                saveItem(p, "solutions", draft, actor, true);
            }
            case "S7" -> {
                JsonNode node =
                        result.has("review") ? result.path("review") : result.path("reviewDraft");
                ObjectNode draft = ((ObjectNode) node).deepCopy();
                draft.put("proposedByTaskId", taskId)
                        .put("agentId", agentId)
                        .put("authority", "UNTRUSTED_DRAFT")
                        .put("kind", "AI_REVIEW_DRAFT");
                saveItem(p, "reviewDrafts", draft, actor, true);
            }
            default -> {}
        }
    }
}

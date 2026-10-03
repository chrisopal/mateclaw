package vip.mate.presales;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import vip.mate.presales.PresalesDtos.CommandKind;

/**
 * Shapes of new public command fields only; domain policy, authority and defaults stay in service.
 */
final class PresalesCommandPayload {
    private PresalesCommandPayload() {}

    static void validate(CommandKind action, ObjectNode value) {
        switch (action) {
            case UPDATE_PROJECT ->
                    strings(
                            value,
                            "",
                            "name",
                            "customer",
                            "ownerId",
                            "agentId",
                            "industry",
                            "goal");
            case BIND_MATERIAL -> {
                strings(value, "", "id", "kbId", "graphId");
                defaultedStrings(value, "", "role");
            }
            case SAVE_REQUIREMENT -> {
                strings(
                        value,
                        "",
                        "id",
                        "title",
                        "description",
                        "originKind",
                        "statementId",
                        "graphId",
                        "proposedByTaskId");
                defaultedStrings(value, "", "priority", "scope");
                JsonNode revision = value.get("statementRevision");
                if (revision != null && !revision.isTextual())
                    integer(revision, "statementRevision");
            }
            case SAVE_CLARIFICATION -> {
                strings(
                        value,
                        "",
                        "id",
                        "question",
                        "requirementId",
                        "impact",
                        "ownerId",
                        "answer",
                        "answerSourceId");
                defaultedStrings(value, "", "status");
            }
            case UNBIND_MATERIAL -> strings(value, "", "id");
            case CANCEL_AI_TASK -> strings(value, "", "taskId");
            case SAVE_AI_TASK -> {
                strings(value, "", "id", "skill");
                defaultedStrings(value, "", "status");
                for (String key : new String[] {"result", "contextSnapshot"})
                    if (value.has(key)) object(value.get(key), key);
            }
            case SAVE_CONTEXT -> {
                strings(value, "", "id", "title", "text", "originKind");
                stringArray(value, "", "sourceRefs");
            }
            case SAVE_REVIEW -> {
                strings(value, "", "id", "solutionId", "summary");
                if (value.has("issues")) {
                    array(value.get("issues"), "issues");
                    for (int i = 0; i < value.get("issues").size(); i++) {
                        String path = "issues[" + i + "].";
                        ObjectNode issue = object(value.get("issues").get(i), "issues[" + i + "]");
                        strings(issue, path, "id", "description", "text");
                        defaultedStrings(issue, path, "severity", "status");
                    }
                }
            }
            case CREATE_RELEASE -> strings(value, "", "id", "solutionId", "purpose");
            case APPROVE_RELEASE -> strings(value, "", "releaseId", "reason");
            case PUBLISH_RELEASE -> strings(value, "", "releaseId");
            case APPROVE_BASELINE -> strings(value, "", "id", "reason");
            case SAVE_FIT_GAP -> {
                strings(value, "", "id", "requirementId", "reason", "productVersion", "graphId");
                defaultedStrings(value, "", "status");
                stringArray(value, "", "evidenceIds");
            }
            case SAVE_SOLUTION -> {
                strings(value, "", "id", "title", "baselineId");
                if (value.has("baselineVersion"))
                    integer(value.get("baselineVersion"), "baselineVersion");
                if (value.has("sections")) {
                    array(value.get("sections"), "sections");
                    for (int i = 0; i < value.get("sections").size(); i++) {
                        String path = "sections[" + i + "].";
                        ObjectNode section =
                                object(value.get("sections").get(i), "sections[" + i + "]");
                        strings(section, path, "id", "title", "text");
                        stringArray(section, path, "requirementRefs");
                    }
                }
                if (value.has("requirementResponses")) {
                    array(value.get("requirementResponses"), "requirementResponses");
                    for (int i = 0; i < value.get("requirementResponses").size(); i++) {
                        String path = "requirementResponses[" + i + "].";
                        ObjectNode response =
                                object(
                                        value.get("requirementResponses").get(i),
                                        "requirementResponses[" + i + "]");
                        strings(response, path, "id", "requirementId", "reason");
                        defaultedStrings(response, path, "status");
                        stringArray(response, path, "evidenceIds");
                    }
                }
                // presentation and sourceRefs keep the existing policy's 422 errors and order.
            }
            case ARCHIVE, UNKNOWN -> {
                // ARCHIVE ignores its payload; unknown actions retain the service's rejection.
            }
        }
    }

    private static void strings(ObjectNode value, String path, String... keys) {
        for (String key : keys)
            if (value.has(key) && !value.get(key).isTextual()) invalid(path + key);
    }

    private static void defaultedStrings(ObjectNode value, String path, String... keys) {
        for (String key : keys)
            if (value.has(key) && !value.get(key).isNull() && !value.get(key).isTextual())
                invalid(path + key);
    }

    private static void stringArray(ObjectNode value, String path, String key) {
        if (!value.has(key)) return;
        JsonNode items = value.get(key);
        array(items, path + key);
        for (int i = 0; i < items.size(); i++)
            if (!items.get(i).isTextual()) invalid(path + key + "[" + i + "]");
    }

    private static ObjectNode object(JsonNode value, String path) {
        if (!(value instanceof ObjectNode)) invalid(path);
        return (ObjectNode) value;
    }

    private static void array(JsonNode value, String path) {
        if (!value.isArray()) invalid(path);
    }

    private static void integer(JsonNode value, String path) {
        if (!value.isIntegralNumber()
                || !value.canConvertToLong()
                || value.longValue() < 0
                || value.longValue() > 9007199254740991L) invalid(path);
    }

    private static void invalid(String path) {
        throw new PresalesProjectItems.Rejected(
                400, "INVALID_REQUEST", "Invalid payload field: " + path);
    }
}

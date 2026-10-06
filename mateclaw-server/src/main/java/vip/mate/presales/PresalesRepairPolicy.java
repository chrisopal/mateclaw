package vip.mate.presales;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import vip.mate.presales.PresalesDtos.Command;

/** Pure repair eligibility and source-restricted response policy; callers own authorization. */
final class PresalesRepairPolicy {
    private static final List<String> REPAIR_METADATA_FIELDS =
            List.of(
                    "id",
                    "workspaceId",
                    "version",
                    "name",
                    "customer",
                    "ownerId",
                    "industry",
                    "goal",
                    "status",
                    "stage",
                    "agentId",
                    "agentName",
                    "createdBy",
                    "createdAt",
                    "updatedBy",
                    "updatedAt");
    private static final Set<String> REPAIR_BIND_FIELDS = Set.of("id", "kbId", "graphId", "role");
    private static final List<String> SOURCE_COLLECTIONS =
            PresalesListingProjectionV1.SOURCE_COLLECTIONS;
    private final ObjectMapper json;

    PresalesRepairPolicy(ObjectMapper json) {
        this.json = json;
    }

    boolean allows(Command command) {
        if (command == null || command.payload() == null || !command.payload().isObject())
            return false;
        Set<String> keys = new HashSet<>();
        command.payload().fieldNames().forEachRemaining(keys::add);
        return switch (command.parsedAction().kind()) {
            case UPDATE_PROJECT ->
                    keys.equals(Set.of("agentId"))
                            && command.payload().path("agentId").isTextual()
                            && !command.payload().path("agentId").asText().isBlank();
            case BIND_MATERIAL ->
                    keys.stream().allMatch(REPAIR_BIND_FIELDS::contains)
                            && command.payload().path("kbId").isTextual()
                            && !command.payload().path("kbId").asText().isBlank();
            case UNBIND_MATERIAL ->
                    keys.equals(Set.of("id"))
                            && command.payload().path("id").isTextual()
                            && !command.payload().path("id").asText().isBlank();
            default -> false;
        };
    }

    ObjectNode restrictedView(ObjectNode project) {
        ObjectNode view = json.createObjectNode();
        for (String field : REPAIR_METADATA_FIELDS)
            if (project.has(field)) view.set(field, project.path(field).deepCopy());
        if (!view.has("stage")) view.put("stage", PresalesProjectListing.stage(project.deepCopy()));
        for (String collection : SOURCE_COLLECTIONS) view.putArray(collection);
        ArrayNode bindings = view.putArray("repairBindings");
        for (var material : project.path("materials")) {
            String id = material.path("id").asText();
            if (!id.isBlank()) {
                String role = material.path("role").asText();
                bindings.addObject()
                        .put("id", id)
                        .put(
                                "role",
                                Set.of("PROJECT", "PRODUCT", "CASE").contains(role)
                                        ? role
                                        : "UNKNOWN");
            }
        }
        view.put("sourceAccessRestricted", true);
        return view;
    }
}

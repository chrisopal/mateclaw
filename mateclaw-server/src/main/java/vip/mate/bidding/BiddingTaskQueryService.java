package vip.mate.bidding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

/** Authorizes the complete task detail before any stored snapshot is returned. */
@Service
public class BiddingTaskQueryService {
    private final BiddingTaskService tasks;
    private final BiddingTaskReadRepository repository;
    private final BiddingMaterials materials;
    private final ObjectMapper json;

    public BiddingTaskQueryService(
            BiddingTaskService tasks,
            BiddingTaskReadRepository repository,
            BiddingMaterials materials,
            ObjectMapper json) {
        this.tasks = tasks;
        this.repository = repository;
        this.materials = materials;
        this.json = json;
    }

    public ObjectNode details(BiddingTypes.Scope scope, String taskId) {
        ObjectNode details = tasks.taskDetails(scope, taskId);
        String projectId = details.path("projectId").asText();
        BiddingTypes.Scope taskScope =
                new BiddingTypes.Scope(scope.workspaceId(), scope.actorId(), projectId);
        BiddingTaskReadRepository.TaskAccess access;
        try {
            access = repository.findAccess(scope.workspaceId(), projectId, taskId);
        } catch (IllegalStateException invalidProject) {
            throw BiddingAccess.error(
                    500, "PROJECT_STATE_INVALID", "Project bindings are unavailable");
        }
        if (access == null || access.project() == null)
            throw BiddingAccess.error(404, "NOT_FOUND", "Task not found");
        String agentId = access.agentId();
        if (agentId == null || agentId.isBlank())
            throw BiddingAccess.error(403, "EMPLOYEE_UNAVAILABLE", "Bound employee is unavailable");
        JsonNode project = access.project();
        if (project != null && !(project.path("bindings") instanceof ObjectNode))
            throw BiddingAccess.error(
                    500, "PROJECT_STATE_INVALID", "Project bindings are unavailable");
        String reviewerId =
                project == null
                        ? ""
                        : project.path("bindings").path("reviewer").path("agentId").asText("");
        JsonNode snapshot = details.path("snapshot");
        // The immutable target identifies historical review tasks even after rebinding
        // or skill package changes; never downgrade their authorization to writer ACL.
        boolean reviewTask =
                snapshot.path("_bidding").path("targetId").asText().startsWith("review:");
        if (reviewTask) {
            if (reviewerId.isBlank() || !reviewerId.equals(agentId))
                throw BiddingAccess.error(
                        403,
                        "REVIEWER_MATERIAL_UNAVAILABLE",
                        "Review task is no longer assigned to the bound reviewer");
            for (JsonNode ref : snapshot.path("inputRefs"))
                materials.requireReviewerReadable(
                        taskScope, reviewerId, json.convertValue(ref, BiddingTypes.Ref.class));
            for (JsonNode material :
                    snapshot.path("input").path("evidenceSnapshot").path("materials").path("items"))
                materials.requireReviewerReadable(
                        taskScope,
                        reviewerId,
                        json.convertValue(material.path("ref"), BiddingTypes.Ref.class));
        } else {
            materials.requireReadable(taskScope, agentId, snapshot.path("inputRefs"));
            for (JsonNode material :
                    snapshot.path("input").path("evidenceSnapshot").path("materials").path("items"))
                materials.requireReadable(
                        taskScope,
                        agentId,
                        json.convertValue(material.path("ref"), BiddingTypes.Ref.class));
        }
        return details;
    }
}

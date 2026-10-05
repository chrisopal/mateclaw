package vip.mate.presales;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** New queued-task wire contract; not a decoder or migration for historical task records. */
@JsonInclude(JsonInclude.Include.ALWAYS)
@JsonPropertyOrder({
    "operationId",
    "requestHash",
    "skill",
    "agentId",
    "agentName",
    "taskGoal",
    "status",
    "queueState",
    "queuedAt",
    "runId",
    "needsHumanReview",
    "modelConfigId",
    "configDigest",
    "skillName",
    "skillDigest",
    "presentationDigest",
    "conversationId",
    "contextSnapshot"
})
record PresalesQueuedTask(
        String operationId,
        String requestHash,
        String skill,
        String agentId,
        String agentName,
        String taskGoal,
        Status status,
        QueueState queueState,
        String queuedAt,
        String runId,
        boolean needsHumanReview,
        String modelConfigId,
        String configDigest,
        String skillName,
        String skillDigest,
        String presentationDigest,
        String conversationId,
        ObjectNode contextSnapshot) {
    enum Status {
        RUNNING
    }

    enum QueueState {
        QUEUED
    }

    PresalesQueuedTask {
        contextSnapshot = contextSnapshot == null ? null : contextSnapshot.deepCopy();
    }

    @Override
    public ObjectNode contextSnapshot() {
        return contextSnapshot == null ? null : contextSnapshot.deepCopy();
    }
}

package vip.mate.presales;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;

/** Trusted package fixtures; hostile public HTTP requests remain separate. */
final class PresalesTaskPackageFixtures {
    static PresalesTaskPackage original(String skill) {
        return PresalesTaskPackage.create(
                skill, Map.of("SKILL.md", PresalesModelAdapter.readSkill(skill)), null);
    }

    static PresalesEmployeeRuntime.CapturedExecution capture(
            String skill, String model, String config) {
        var original = original(skill);
        return new PresalesEmployeeRuntime.CapturedExecution(
                new PresalesEmployeeRuntime.Pin(
                        model,
                        config,
                        original.skillName(),
                        original.skillDigest(),
                        original.presentationDigest()),
                original);
    }

    static ObjectNode queue(
            PresalesService service,
            PresalesEmployeeRuntime runtime,
            ObjectMapper json,
            String scope,
            String projectId,
            PresalesDtos.Command command) {
        var task = command.payload().deepCopy();
        var captured =
                runtime.capture(scope, task.path("agentId").asText(), task.path("skill").asText());
        var pin = captured.pin();
        task.put("modelConfigId", pin.modelConfigId())
                .put("configDigest", pin.configDigest())
                .put("skillName", pin.skillName())
                .put("skillDigest", pin.skillDigest())
                .put("presentationDigest", pin.presentationDigest());
        var queued =
                new PresalesQueuedTask(
                        task.path("operationId").asText(),
                        task.path("requestHash").asText(),
                        task.path("skill").asText(),
                        task.path("agentId").asText(),
                        task.path("agentName").asText(),
                        task.path("taskGoal").asText(),
                        PresalesQueuedTask.Status.RUNNING,
                        PresalesQueuedTask.QueueState.QUEUED,
                        task.path("queuedAt").asText(),
                        task.path("runId").asText(),
                        true,
                        pin.modelConfigId(),
                        pin.configDigest(),
                        pin.skillName(),
                        pin.skillDigest(),
                        pin.presentationDigest(),
                        task.path("conversationId").asText(),
                        (ObjectNode) task.path("contextSnapshot"));
        return service.queueEmployeeTask(
                scope,
                projectId,
                command.expectedVersion(),
                command.operationId(),
                queued,
                captured.skillPackage());
    }
}

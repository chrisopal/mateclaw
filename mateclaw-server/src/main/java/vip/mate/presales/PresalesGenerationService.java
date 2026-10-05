package vip.mate.presales;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "mateclaw.presales.enabled", havingValue = "true")
public class PresalesGenerationService {
    private static final Set<String> SKILLS =
            Set.of("S1", "S2", "S3", "S4", "S5", "S6", "S7", "S8");
    private final PresalesService service;
    private final PresalesAccess access;
    private final PresalesContextProvider contexts;
    private final PresalesEmployeeRuntime model;
    private final ObjectMapper json;
    private final PresalesGenerationCoordinator coordinator;

    public PresalesGenerationService(
            PresalesService service,
            PresalesAccess access,
            PresalesContextProvider contexts,
            PresalesEmployeeRuntime model,
            ObjectMapper json,
            PresalesGenerationCoordinator coordinator) {
        this.service = service;
        this.access = access;
        this.contexts = contexts;
        this.model = model;
        this.json = json;
        this.coordinator = coordinator;
    }

    public List<PresalesDtos.Employee> employees(String scope) {
        access.require(scope, "viewer");
        return model.employees(scope);
    }

    /** Persist the task and return immediately; the coordinator owns all model work. */
    public ObjectNode generate(String scope, String id, PresalesDtos.Generate input) {
        String actor = access.require(scope, "member");
        if (input == null
                || input.expectedVersion() == null
                || input.operationId() == null
                || input.operationId().isBlank()
                || input.operationId().length() > 100
                || !SKILLS.contains(Objects.toString(input.skill(), ""))
                || input.taskGoal() == null
                || input.taskGoal().isBlank()
                || input.taskGoal().length() > 5000)
            throw PresalesModelAdapter.error(400, "INVALID_REQUEST");
        ObjectNode project = service.get(scope, id);
        String requestHash;
        try {
            requestHash = PresalesArtifactRenderer.digest(json.writeValueAsBytes(input));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        for (var previous : project.path("tasks"))
            if (input.operationId().equals(previous.path("operationId").asText())) {
                if (!requestHash.equals(previous.path("requestHash").asText()))
                    throw PresalesModelAdapter.error(409, "OPERATION_CONFLICT");
                // A retry returns the durable task envelope and never submits another model run.
                return project;
            }
        if (!PresalesProjectRevision.matchesRevision(
                project.path("version"), input.expectedVersion()))
            throw PresalesModelAdapter.error(409, "VERSION_CONFLICT");
        long acceptedVersion;
        try {
            acceptedVersion = PresalesProjectRevision.nextRevision(input.expectedVersion());
            // Starting a real run also needs room to persist its terminal state.
            PresalesProjectRevision.nextRevision(acceptedVersion);
        } catch (PresalesRejected rejection) {
            throw PresalesModelAdapter.error(rejection.status(), rejection.code());
        }
        var employee = model.require(scope, project.path("agentId").asText());
        ObjectNode snapshot = contexts.snapshot(scope, project, input.skill(), input.taskGoal());
        var pin = model.pin(scope, employee.getId().toString(), input.skill());
        // SAVE_AI_TASK advances the project version exactly once. Persist that accepted
        // version in the task snapshot so tool-time revalidation can compare it.
        snapshot.set("projectVersion", PresalesProjectRevision.number(acceptedVersion));
        String runId = UUID.randomUUID().toString();
        var queuedTask =
                new PresalesQueuedTask(
                        input.operationId(),
                        requestHash,
                        input.skill(),
                        employee.getId().toString(),
                        employee.getName(),
                        input.taskGoal(),
                        PresalesQueuedTask.Status.RUNNING,
                        PresalesQueuedTask.QueueState.QUEUED,
                        Instant.now().toString(),
                        runId,
                        true,
                        pin.modelConfigId(),
                        pin.configDigest(),
                        pin.skillName(),
                        pin.skillDigest(),
                        pin.presentationDigest(),
                        "presales:" + scope + ":" + id + ":" + runId,
                        snapshot);
        ObjectNode task = json.valueToTree(queuedTask);
        project =
                service.command(
                        scope,
                        id,
                        new PresalesDtos.Command(
                                input.expectedVersion(),
                                input.operationId() + ":start",
                                "SAVE_AI_TASK",
                                task));
        ObjectNode stored =
                (ObjectNode) project.path("tasks").get(project.path("tasks").size() - 1);
        task = stored.deepCopy();
        if (!PresalesProjectRevision.matchesRevision(project.path("version"), acceptedVersion)
                || !PresalesProjectRevision.matchesRevision(
                        snapshot.path("projectVersion"), acceptedVersion))
            throw PresalesModelAdapter.error(409, "VERSION_CONFLICT");
        coordinator.enqueue(
                new PresalesGenerationCoordinator.Submission(
                        scope,
                        actor,
                        id,
                        input.operationId(),
                        input.skill(),
                        input.taskGoal(),
                        task,
                        snapshot,
                        acceptedVersion));
        return project;
    }

    /** Reserve cancellation before changing the durable task state. */
    public ObjectNode cancel(String scope, String id, String taskId, PresalesDtos.Cancel input) {
        access.require(scope, "member");
        ObjectNode project = service.get(scope, id);
        ObjectNode task = service.find(project, "tasks", taskId);
        if (!"RUNNING".equals(task.path("status").asText()))
            throw PresalesModelAdapter.error(409, "TASK_STATE");
        Long version = PresalesProjectRevision.positiveRevision(project.path("version"));
        if (version == null) throw PresalesModelAdapter.error(409, "VERSION_CONFLICT");
        String operationId =
                input == null || input.operationId() == null || input.operationId().isBlank()
                        ? "cancel:" + taskId
                        : input.operationId();
        if (operationId.length() > 100) throw PresalesModelAdapter.error(400, "INVALID_REQUEST");
        coordinator.requestCancellation(scope, id, taskId);
        try {
            return service.command(
                    scope,
                    id,
                    new PresalesDtos.Command(
                            version,
                            operationId,
                            "CANCEL_AI_TASK",
                            json.createObjectNode().put("taskId", taskId)));
        } catch (RuntimeException e) {
            coordinator.clearCancellation(scope, id, taskId);
            throw e;
        }
    }
}

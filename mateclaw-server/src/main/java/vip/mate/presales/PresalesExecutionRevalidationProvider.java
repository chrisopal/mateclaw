package vip.mate.presales;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import vip.mate.agent.execution.ProjectExecutionOptions;
import vip.mate.agent.execution.ProjectExecutionRevalidationProvider;

/** Rechecks the persisted task, actor, employee, model pin, and sources at each tool boundary. */
@Component
@ConditionalOnProperty(name = "mateclaw.presales.enabled", havingValue = "true")
public class PresalesExecutionRevalidationProvider implements ProjectExecutionRevalidationProvider {
    private final PresalesAccess access;
    private final PresalesService service;
    private final PresalesEmployeeRuntime runtime;
    private final PresalesContextProvider contexts;

    public PresalesExecutionRevalidationProvider(
            PresalesAccess access,
            PresalesService service,
            PresalesEmployeeRuntime runtime,
            PresalesContextProvider contexts) {
        this.access = access;
        this.service = service;
        this.runtime = runtime;
        this.contexts = contexts;
    }

    @Override
    public boolean supports(ProjectExecutionOptions options) {
        return options != null && options.toolPolicy() instanceof PresalesToolScope;
    }

    @Override
    public void requireActive(ProjectExecutionOptions options) {
        if (!supports(options)) throw PresalesModelAdapter.error(403, "TASK_SCOPE_CHANGED");
        PresalesToolScope scope = (PresalesToolScope) options.toolPolicy();
        access.requireActor(scope.workspaceId(), scope.actorId(), "member");
        ObjectNode project =
                service.getForExecution(scope.workspaceId(), scope.projectId(), scope.actorId());
        if (!PresalesProjectRevision.matchesRevision(
                        project.path("version"), scope.projectVersion())
                || !scope.employeeId().equals(project.path("agentId").asText()))
            throw PresalesModelAdapter.error(409, "PROJECT_CHANGED_DURING_GENERATION");
        ObjectNode task = service.find(project, "tasks", scope.taskId());
        if (!"RUNNING".equals(task.path("status").asText())
                || !scope.runId().equals(task.path("runId").asText())
                || !scope.operationId().equals(task.path("operationId").asText())
                || !scope.runId().equals(options.attemptId())
                || !scope.employeeId().equals(task.path("agentId").asText())
                || !(task.path("contextSnapshot") instanceof ObjectNode snapshot)
                || !PresalesProjectRevision.matchesRevision(
                        snapshot.path("projectVersion"), scope.projectVersion())
                || !scope.projectId().equals(snapshot.path("caseRef").asText())
                || !scope.inputRefs().equals(PresalesToolScope.inputRefs(snapshot))
                || !task.path("conversationId")
                        .asText()
                        .equals(
                                "presales:"
                                        + scope.workspaceId()
                                        + ":"
                                        + scope.projectId()
                                        + ":"
                                        + scope.runId()))
            throw PresalesModelAdapter.error(409, "TASK_SCOPE_CHANGED");
        var pin = runtime.pin(scope.workspaceId(), scope.employeeId(), task.path("skill").asText());
        if (!pin.modelConfigId().equals(options.modelConfigId())
                || !pin.configDigest().equals(options.configDigest())
                || !pin.skillName().equals(options.skillName())
                || !pin.skillDigest().equals(options.skillDigest())
                || !pin.modelConfigId().equals(task.path("modelConfigId").asText())
                || !pin.configDigest().equals(task.path("configDigest").asText())
                || !pin.skillName().equals(task.path("skillName").asText())
                || !pin.skillDigest().equals(task.path("skillDigest").asText())
                || !pin.presentationDigest().equals(task.path("presentationDigest").asText()))
            throw PresalesModelAdapter.error(409, "EXECUTION_PIN_CHANGED");
        contexts.revalidate(scope.workspaceId(), project, snapshot);
    }
}

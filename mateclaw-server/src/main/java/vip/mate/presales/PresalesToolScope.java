package vip.mate.presales;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Set;
import vip.mate.agent.execution.ProjectToolPolicy;

/** Immutable identity carried by one server-created presales task execution. */
public record PresalesToolScope(
        String workspaceId,
        String actorId,
        String projectId,
        String taskId,
        String runId,
        String operationId,
        List<String> inputRefs,
        String employeeId,
        long projectVersion,
        String dependencyDigest)
        implements ProjectToolPolicy {
    private static final Set<String> ALLOWED = PresalesToolPolicy.PROJECT_VISIBLE_TOOLS;

    public PresalesToolScope(
            String workspaceId,
            String actorId,
            String projectId,
            String taskId,
            String runId,
            String operationId,
            List<String> inputRefs,
            String employeeId,
            long projectVersion) {
        this(
                workspaceId,
                actorId,
                projectId,
                taskId,
                runId,
                operationId,
                inputRefs,
                employeeId,
                projectVersion,
                "");
    }

    public PresalesToolScope {
        inputRefs = inputRefs == null ? List.of() : List.copyOf(inputRefs);
        if (dependencyDigest == null
                || (!dependencyDigest.isEmpty() && !dependencyDigest.matches("v2:[0-9a-f]{64}"))
                || workspaceId == null
                || workspaceId.isBlank()
                || actorId == null
                || actorId.isBlank()
                || projectId == null
                || projectId.isBlank()
                || taskId == null
                || taskId.isBlank()
                || runId == null
                || runId.isBlank()
                || operationId == null
                || operationId.isBlank()
                || inputRefs.stream().anyMatch(ref -> ref.isBlank())
                || employeeId == null
                || employeeId.isBlank()
                || projectVersion < 1
                || projectVersion > PresalesProjectRevision.MAX_VALUE) {
            throw new IllegalArgumentException("Incomplete presales task scope");
        }
    }

    static List<String> inputRefs(ObjectNode snapshot) {
        return java.util.stream.StreamSupport.stream(snapshot.path("sources").spliterator(), false)
                .map(source -> source.path("sourceRef").asText())
                .toList();
    }

    @Override
    public void require(String toolName, String arguments) {
        if (!ALLOWED.contains(toolName)) throw new SecurityException("Tool outside presales scope");
        if (arguments != null && arguments.length() > 64_000)
            throw new SecurityException("Presales tool arguments exceed the task limit");
    }
}

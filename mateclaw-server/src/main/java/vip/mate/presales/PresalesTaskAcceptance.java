package vip.mate.presales;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import vip.mate.workspace.core.service.ProjectAuthorityFence;

/**
 * Candidate acceptance inside the caller's project transaction, before any durable result write.
 */
final class PresalesTaskAcceptance {
    private final ObjectProvider<PresalesEmployeeRuntime> employees;
    private final ProjectAuthorityFence authorityFence;

    PresalesTaskAcceptance(
            ObjectProvider<PresalesEmployeeRuntime> employees,
            ProjectAuthorityFence authorityFence) {
        this.employees = employees;
        this.authorityFence = authorityFence;
    }

    void prepare(
            String scope, String actor, ObjectNode p, ObjectNode value, boolean employeeResult) {
        prepare(scope, actor, p, value, employeeResult, false);
    }

    void prepare(
            String scope,
            String actor,
            ObjectNode p,
            ObjectNode value,
            boolean employeeResult,
            boolean queued) {
        if (!employeeResult && !queued) {
            if (executionClaim(value)) throw PresalesModelAdapter.error(400, "TASK_SERVER_OWNED");
            for (var task : p.path("tasks"))
                if (value.hasNonNull("id")
                        && value.path("id").asText().equals(task.path("id").asText())
                        && executionClaim((ObjectNode) task))
                    throw PresalesModelAdapter.error(409, "TASK_SERVER_OWNED");
        }
        value.put("status", value.path("status").asText("DRAFT"))
                .put("authority", "UNTRUSTED_DRAFT");
        PresalesProjectItems.enumValue(
                value, "status", Set.of("RUNNING", "SUCCEEDED", "FAILED", "DRAFT"), "DRAFT");
        if (employeeResult && !"SUCCEEDED".equals(value.path("status").asText())) {
            if (!"FAILED".equals(value.path("status").asText())
                    || !matchesRunningTask(
                            PresalesProjectItems.find(p, "tasks", value.path("id").asText()),
                            value)) throw PresalesModelAdapter.error(409, "TASK_SCOPE_CHANGED");
        }
        if (employeeResult && "SUCCEEDED".equals(value.path("status").asText())) {
            if (!(value.path("result") instanceof ObjectNode result))
                throw PresalesModelAdapter.error(422, "MODEL_FORMAT");
            if (!(value.path("contextSnapshot") instanceof ObjectNode snapshot))
                throw PresalesModelAdapter.error(409, "TASK_SCOPE_CHANGED");
            if (employees.getIfAvailable() == null)
                throw PresalesModelAdapter.error(409, "EMPLOYEE_UNAVAILABLE");
            // Keep revocation writers behind this transaction until the candidate is
            // committed or rolled back. The project row alone does not protect authority
            // held in user, employee, model, and source rows.
            ObjectNode activeTask =
                    PresalesProjectItems.find(p, "tasks", value.path("id").asText());
            if (!(activeTask.path("contextSnapshot") instanceof ObjectNode activeSnapshot))
                throw PresalesModelAdapter.error(409, "TASK_SCOPE_CHANGED");
            if (!matchesRunningTask(activeTask, value))
                throw PresalesModelAdapter.error(409, "TASK_SCOPE_CHANGED");
            lockResultAuthority(scope, actor, activeTask, activeSnapshot);
            employees.getObject().revalidate(scope, actor, value, snapshot);
            PresalesModelAdapter.validate(result, snapshot, value.path("skill").asText());
        }
    }

    private static boolean executionClaim(ObjectNode task) {
        if ("RUNNING".equals(task.path("status").asText())) return true;
        for (String key :
                List.of(
                        "runId",
                        "conversationId",
                        "modelConfigId",
                        "configDigest",
                        "skillName",
                        "skillDigest",
                        "presentationDigest",
                        "packageDigest",
                        "skillPackage",
                        "serverCreated",
                        "queueState",
                        "queuedAt",
                        "operationId",
                        "requestHash")) if (task.has(key)) return true;
        return false;
    }

    private void lockResultAuthority(
            String scope, String actor, ObjectNode task, ObjectNode snapshot) {
        var sources = new ArrayList<ProjectAuthorityFence.Source>();
        for (var source : snapshot.path("sources")) {
            sources.add(
                    new ProjectAuthorityFence.Source(
                            source.path("kbId").asText(),
                            source.path("sourceRef").asText(),
                            source.path("graphId").asText()));
        }
        try {
            if (authorityFence.lockForResult(
                    scope,
                    List.of(actor, snapshot.path("actorId").asText()),
                    task.path("agentId").asText(),
                    task.path("modelConfigId").asText(),
                    sources)) return;
        } catch (IllegalArgumentException invalid) {
            throw PresalesModelAdapter.error(409, "TASK_SCOPE_CHANGED");
        }
        throw PresalesModelAdapter.error(409, "EXECUTION_AUTHORITY_CHANGED");
    }

    private static boolean matchesRunningTask(ObjectNode activeTask, ObjectNode candidate) {
        if (!"RUNNING".equals(activeTask.path("status").asText())) return false;
        ObjectNode durableIdentity = activeTask.deepCopy();
        ObjectNode candidateIdentity = candidate.deepCopy();
        var outputFields =
                "FAILED".equals(candidate.path("status").asText())
                        ? List.of("status", "finishedAt", "result", "error", "rejectedOutput")
                        : List.of("status", "finishedAt", "result");
        durableIdentity.remove(outputFields);
        candidateIdentity.remove(outputFields);
        return durableIdentity.equals(candidateIdentity);
    }
}

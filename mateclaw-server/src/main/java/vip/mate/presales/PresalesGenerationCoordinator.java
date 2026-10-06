package vip.mate.presales;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import vip.mate.presales.repository.PresalesProjectRepository;
import vip.mate.semantic.web.SemanticApiException;

/**
 * Owns the durable boundary around a presales employee run.
 *
 * <p>The controller commits the input task first and this component then runs the model on the
 * async executor. Every worker operation carries the original workspace and actor; the global async
 * executor propagates the request security context, while the explicit values keep the project and
 * conversation scope auditable.
 */
@Component
@ConditionalOnProperty(name = "mateclaw.presales.enabled", havingValue = "true")
public class PresalesGenerationCoordinator {
    private static final Logger log = LoggerFactory.getLogger(PresalesGenerationCoordinator.class);
    private static final String INTERRUPTED_BY_RESTART = "INTERRUPTED_BY_RESTART";
    private static final String TASK_RUNNING = "RUNNING";
    private static final String TASK_CANCELLED = "CANCELLED";
    // Single-instance recovery: any run queued before this process cannot still be executing here.
    private final Instant processStartedAt = Instant.now();

    private final PresalesService service;
    private final PresalesContextProvider contexts;
    private final PresalesEmployeeRuntime model;
    private final ObjectMapper json;
    private final PresalesProjectRepository projects;
    private final ObjectProvider<PresalesPresentationHook> presentation;
    private final Map<String, Boolean> active = new ConcurrentHashMap<>();
    private final Map<String, Boolean> cancellationRequested = new ConcurrentHashMap<>();

    @Autowired
    public PresalesGenerationCoordinator(
            PresalesService service,
            PresalesContextProvider contexts,
            PresalesEmployeeRuntime model,
            ObjectMapper json,
            PresalesProjectRepository projects,
            ObjectProvider<PresalesPresentationHook> presentation) {
        this.service = service;
        this.contexts = contexts;
        this.model = model;
        this.json = json;
        this.projects = projects;
        this.presentation = presentation;
    }

    /** Constructor used by focused unit tests that do not exercise restart recovery. */
    public PresalesGenerationCoordinator(
            PresalesService service,
            PresalesContextProvider contexts,
            PresalesEmployeeRuntime model,
            ObjectMapper json,
            ObjectProvider<PresalesPresentationHook> presentation) {
        this(service, contexts, model, json, null, presentation);
    }

    public record Submission(
            String scope,
            String actor,
            String projectId,
            String operationId,
            String skill,
            String taskGoal,
            ObjectNode task,
            ObjectNode snapshot,
            long acceptedVersion) {}

    /**
     * Submit after the RUNNING task has been durably saved. @Async is intentionally on this public
     * boundary so Spring's DelegatingSecurityContextTaskExecutor carries the authenticated actor.
     */
    @Async
    public void enqueue(Submission submission) {
        String key =
                key(
                        submission.scope(),
                        submission.projectId(),
                        submission.task().path("id").asText());
        if (active.putIfAbsent(key, Boolean.TRUE) != null) return;
        try {
            process(submission);
        } finally {
            active.remove(key);
            cancellationRequested.remove(key);
        }
    }

    /** Package-private synchronous seam for deterministic coordinator tests. */
    void process(Submission submission) {
        String taskId = submission.task().path("id").asText();
        String key = key(submission.scope(), submission.projectId(), taskId);
        ObjectNode task = submission.task().deepCopy();
        if (isCancelled(submission.scope(), submission.projectId(), taskId, key)) return;
        try {
            ObjectNode current = service.get(submission.scope(), submission.projectId());
            ObjectNode live = service.find(current, "tasks", taskId);
            if (!matchesAcceptedRun(live, submission)) return;

            ObjectNode result =
                    model.execute(
                            submission.scope(),
                            submission.actor(),
                            task.path("agentId").asText(),
                            task.path("conversationId").asText(),
                            model.instructionsForTask(
                                    submission.scope(),
                                    submission.actor(),
                                    task,
                                    submission.snapshot()),
                            task,
                            submission.snapshot().deepCopy());
            if ("S6".equals(submission.skill())) {
                PresalesPresentationHook hook = presentation.getIfAvailable();
                if (hook == null) throw PresalesModelAdapter.error(409, "PRESENTATION_UNAVAILABLE");
                result =
                        hook.prepare(
                                result,
                                submission.scope(),
                                submission.projectId(),
                                task.path("runId").asText(),
                                task.path("presentationDigest").asText(),
                                task.path("agentId").asText(),
                                model.originalPackage(
                                                submission.scope(),
                                                submission.actor(),
                                                task,
                                                submission.snapshot())
                                        .presentation());
                if (result == null) throw PresalesModelAdapter.error(422, "PRESENTATION_FAILED");
            }
            if (isCancelled(submission.scope(), submission.projectId(), taskId, key)) return;
            model.revalidate(submission.scope(), submission.actor(), task, submission.snapshot());
            contexts.revalidate(
                    submission.scope(),
                    service.get(submission.scope(), submission.projectId()),
                    submission.snapshot());
            task.put("status", "SUCCEEDED").put("finishedAt", Instant.now().toString());
            task.set("result", result);
        } catch (SemanticApiException e) {
            if (e instanceof PresalesOutputRejected rejected)
                task.set("rejectedOutput", rejected.rejected());
            task.put("status", "FAILED")
                    .put("error", e.code())
                    .put("finishedAt", Instant.now().toString());
            task.remove("result");
        } catch (RuntimeException e) {
            log.warn(
                    "Presales employee run failed: project={} task={}",
                    submission.projectId(),
                    taskId,
                    e);
            task.put("status", "FAILED")
                    .put("error", "EMPLOYEE_RUNTIME_FAILED")
                    .put("finishedAt", Instant.now().toString());
            task.remove("result");
        }
        persistTerminal(submission, task, key);
    }

    /** Reserve cancellation before the HTTP command changes the durable task state. */
    public void requestCancellation(String scope, String projectId, String taskId) {
        cancellationRequested.put(key(scope, projectId, taskId), Boolean.TRUE);
    }

    public void clearCancellation(String scope, String projectId, String taskId) {
        cancellationRequested.remove(key(scope, projectId, taskId));
    }

    private boolean isCancelled(String scope, String projectId, String taskId, String key) {
        if (cancellationRequested.containsKey(key)) return true;
        try {
            ObjectNode project = service.get(scope, projectId);
            return TASK_CANCELLED.equals(
                    service.find(project, "tasks", taskId).path("status").asText());
        } catch (SemanticApiException e) {
            return false;
        }
    }

    private void persistTerminal(Submission submission, ObjectNode task, String key) {
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                ObjectNode current = service.get(submission.scope(), submission.projectId());
                ObjectNode live = service.find(current, "tasks", task.path("id").asText());
                if (!matchesAcceptedRun(live, submission) || cancellationRequested.containsKey(key))
                    return;
                Long version = PresalesProjectRevision.positiveRevision(current.path("version"));
                if (version == null) throw PresalesModelAdapter.error(409, "VERSION_CONFLICT");
                if (version != submission.acceptedVersion()) {
                    task.put("status", "FAILED").put("error", "PROJECT_CHANGED_DURING_GENERATION");
                    task.remove("result");
                }
                service.saveEmployeeTask(
                        submission.scope(),
                        submission.projectId(),
                        new PresalesDtos.Command(
                                version,
                                submission.operationId() + ":finish",
                                "SAVE_AI_TASK",
                                task));
                return;
            } catch (SemanticApiException e) {
                if ("VERSION_CONFLICT".equals(e.code()) && attempt < 4) continue;
                task.put("status", "FAILED")
                        .put("error", "TERMINAL_PERSISTENCE_FAILED")
                        .remove("result");
                persistFailureWithoutActor(submission, task);
                return;
            } catch (RuntimeException e) {
                task.put("status", "FAILED")
                        .put("error", "TERMINAL_PERSISTENCE_FAILED")
                        .remove("result");
                persistFailureWithoutActor(submission, task);
                return;
            }
        }
    }

    /** Last-resort server-side CAS when the original actor context has disappeared. */
    private void persistFailureWithoutActor(Submission submission, ObjectNode task) {
        if (projects == null) return;
        String key = key(submission.scope(), submission.projectId(), task.path("id").asText());
        try {
            for (int attempt = 0; attempt < 3; attempt++) {
                if (cancellationRequested.containsKey(key)) return;
                var row = projects.findRuntimeRow(submission.scope(), submission.projectId());
                if (row.isEmpty()) return;
                long version = row.get().version();
                ObjectNode project =
                        (ObjectNode) json.readTree(Objects.toString(row.get().bodyJson(), "{}"));
                ObjectNode live = null;
                for (JsonNode candidate : project.path("tasks")) {
                    if (task.path("id").asText().equals(candidate.path("id").asText())) {
                        live = (ObjectNode) candidate;
                        break;
                    }
                }
                if (!matchesAcceptedRun(live, submission) || cancellationRequested.containsKey(key))
                    return;
                if (!PresalesProjectRevision.matchesRevision(project.path("version"), version))
                    throw new PresalesRejected(
                            409, "VERSION_CONFLICT", "Stored project versions do not match");
                long nextVersion = PresalesProjectRevision.nextRevision(version);
                // A concurrent project edit invalidates the model snapshot; keep the task terminal
                // and
                // discard its output rather than overwriting the user's newer project body.
                String error =
                        version == submission.acceptedVersion()
                                ? "TERMINAL_PERSISTENCE_FAILED"
                                : "PROJECT_CHANGED_DURING_GENERATION";
                task.put("status", "FAILED").put("error", error).remove("result");
                live.remove("result");
                live.setAll(task);
                project.set("version", PresalesProjectRevision.number(nextVersion));
                if (cancellationRequested.containsKey(key)) return;
                String body =
                        PresalesListingProjectionV1.storageJson(json.writeValueAsString(project));
                int changed =
                        projects.updateRuntimeBody(
                                submission.scope(),
                                submission.projectId(),
                                version,
                                nextVersion,
                                body,
                                PresalesListingProjectionV1.fromBody(body, json));
                if (changed == 1) return;
            }
        } catch (Exception e) {
            log.warn(
                    "Unable to persist terminal presales failure without actor: project={} task={}",
                    submission.projectId(),
                    task.path("id").asText(),
                    e);
        }
    }

    /**
     * A process restart cannot resume a model stream safely. Mark persisted RUNNING tasks terminal
     * before accepting new work. This direct compare-and-swap only changes the task envelope and
     * preserves the project JSON collections; no model or employee call is made during recovery.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverStaleTasks() {
        if (projects == null) return;
        for (var row : projects.listRuntimeRows()) recoverStaleProject(row);
    }

    private void recoverStaleProject(PresalesProjectRepository.ProjectRow row) {
        String projectId = row.id();
        String scope = row.workspaceId();
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                long version = row.version();
                ObjectNode project =
                        (ObjectNode) json.readTree(Objects.toString(row.bodyJson(), "{}"));
                boolean changed = false;
                for (JsonNode node : project.path("tasks")) {
                    if (node instanceof ObjectNode task
                            && TASK_RUNNING.equals(task.path("status").asText())
                            && isRecoveryStale(task, scope, projectId)) {
                        task.put("status", "FAILED")
                                .put("error", INTERRUPTED_BY_RESTART)
                                .put("finishedAt", Instant.now().toString());
                        task.remove("result");
                        changed = true;
                    }
                }
                if (!changed) return;
                if (!PresalesProjectRevision.matchesRevision(project.path("version"), version))
                    throw new PresalesRejected(
                            409, "VERSION_CONFLICT", "Stored project versions do not match");
                long nextVersion = PresalesProjectRevision.nextRevision(version);
                project.set("version", PresalesProjectRevision.number(nextVersion));
                String body =
                        PresalesListingProjectionV1.storageJson(json.writeValueAsString(project));
                if (projects.updateRuntimeBody(
                                scope,
                                projectId,
                                version,
                                nextVersion,
                                body,
                                PresalesListingProjectionV1.fromBody(body, json))
                        == 1) return;
                // Re-evaluate task eligibility in the newest body; never retry the mutated
                // snapshot, which could overwrite edits or a newly queued run.
                if (attempt < 2) {
                    var current = projects.findRuntimeRow(scope, projectId);
                    if (current.isEmpty()) return;
                    row = current.get();
                }
            } catch (Exception e) {
                log.warn("Unable to recover interrupted presales tasks: project={}", projectId, e);
                return;
            }
        }
        log.warn("Presales restart recovery exhausted 3 CAS attempts: project={}", projectId);
    }

    private boolean isRecoveryStale(ObjectNode task, String scope, String projectId) {
        if (active.containsKey(key(scope, projectId, task.path("id").asText()))) return false;
        String queuedAt = task.path("queuedAt").asText("");
        if (queuedAt.isBlank()) return true;
        try {
            return Instant.parse(queuedAt).isBefore(processStartedAt);
        } catch (RuntimeException e) {
            return true;
        }
    }

    private static boolean matchesAcceptedRun(ObjectNode live, Submission submission) {
        return live != null
                && TASK_RUNNING.equals(live.path("status").asText())
                && live.equals(submission.task());
    }

    private static String key(String scope, String projectId, String taskId) {
        return String.join(
                "/",
                Objects.toString(scope, ""),
                Objects.toString(projectId, ""),
                Objects.toString(taskId, ""));
    }
}

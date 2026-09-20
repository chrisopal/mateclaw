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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import vip.mate.semantic.web.SemanticApiException;

/**
 * Owns the durable boundary around a presales employee run.
 *
 * <p>The controller commits the input task first and this component then runs the model on the
 * async executor. Every worker operation carries the original workspace and actor; the global
 * async executor propagates the request security context, while the explicit values keep the
 * project and conversation scope auditable.
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
  private final JdbcTemplate jdbc;
  private final ObjectProvider<PresalesPresentationHook> presentation;
  private final Map<String, Boolean> active = new ConcurrentHashMap<>();
  private final Map<String, Boolean> cancellationRequested = new ConcurrentHashMap<>();

  @Autowired
  public PresalesGenerationCoordinator(
      PresalesService service,
      PresalesContextProvider contexts,
      PresalesEmployeeRuntime model,
      ObjectMapper json,
      JdbcTemplate jdbc,
      ObjectProvider<PresalesPresentationHook> presentation) {
    this.service = service;
    this.contexts = contexts;
    this.model = model;
    this.json = json;
    this.jdbc = jdbc;
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
      int acceptedVersion) {}

  /**
   * Submit after the RUNNING task has been durably saved. @Async is intentionally on this public
   * boundary so Spring's DelegatingSecurityContextTaskExecutor carries the authenticated actor.
   */
  @Async
  public void enqueue(Submission submission) {
    String key = key(submission.scope(), submission.projectId(), submission.task().path("id").asText());
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
      if (!TASK_RUNNING.equals(live.path("status").asText())) return;

      ObjectNode result =
          model.execute(
              submission.scope(),
              submission.actor(),
              task.path("agentId").asText(),
              task.path("conversationId").asText(),
              PresalesModelAdapter.instructions(submission.skill()),
              submission.snapshot().deepCopy());
      if ("S6".equals(submission.skill())) {
        PresalesPresentationHook hook = presentation.getIfAvailable();
        if (hook == null) throw PresalesModelAdapter.error(409, "PRESENTATION_UNAVAILABLE");
        result = hook.prepare(result, submission.scope(), submission.projectId(), task.path("runId").asText());
        if (result == null) throw PresalesModelAdapter.error(422, "PRESENTATION_FAILED");
      }
      if (isCancelled(submission.scope(), submission.projectId(), taskId, key)) return;
      contexts.revalidate(submission.scope(), service.get(submission.scope(), submission.projectId()), submission.snapshot());
      task.put("status", "SUCCEEDED").put("finishedAt", Instant.now().toString());
      task.set("result", result);
    } catch (SemanticApiException e) {
      if(e instanceof PresalesOutputRejected rejected) task.set("rejectedOutput",rejected.rejected());
      task.put("status", "FAILED").put("error", e.code()).put("finishedAt", Instant.now().toString());
      task.remove("result");
    } catch (RuntimeException e) {
      log.warn("Presales employee run failed: project={} task={}", submission.projectId(), taskId, e);
      task.put("status", "FAILED").put("error", "EMPLOYEE_RUNTIME_FAILED").put("finishedAt", Instant.now().toString());
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
      return TASK_CANCELLED.equals(service.find(project, "tasks", taskId).path("status").asText());
    } catch (SemanticApiException e) {
      return false;
    }
  }

  private void persistTerminal(Submission submission, ObjectNode task, String key) {
    for (int attempt = 0; attempt < 5; attempt++) {
      try {
        ObjectNode current = service.get(submission.scope(), submission.projectId());
        ObjectNode live = service.find(current, "tasks", task.path("id").asText());
        if (TASK_CANCELLED.equals(live.path("status").asText()) || cancellationRequested.containsKey(key)) return;
        if (current.path("version").asInt() != submission.acceptedVersion()) {
          task.put("status", "FAILED").put("error", "PROJECT_CHANGED_DURING_GENERATION");
          task.remove("result");
        }
        service.saveEmployeeTask(
            submission.scope(),
            submission.projectId(),
            new PresalesDtos.Command(
                current.path("version").asInt(),
                submission.operationId() + ":finish",
                "SAVE_AI_TASK",
                task));
        return;
      } catch (SemanticApiException e) {
        if ("VERSION_CONFLICT".equals(e.code()) && attempt < 4) continue;
        task.put("status", "FAILED").put("error", "TERMINAL_PERSISTENCE_FAILED").remove("result");
        persistFailureWithoutActor(submission, task);
        return;
      } catch (RuntimeException e) {
        task.put("status", "FAILED").put("error", "TERMINAL_PERSISTENCE_FAILED").remove("result");
        persistFailureWithoutActor(submission, task);
        return;
      }
    }
  }

  /** Last-resort server-side CAS when the original actor context has disappeared. */
  private void persistFailureWithoutActor(Submission submission, ObjectNode task) {
    if (jdbc == null) return;
    try {
      for (int attempt = 0; attempt < 3; attempt++) {
        var rows = jdbc.queryForList(
            "SELECT version,body_json FROM mate_presales_project WHERE id=? AND workspace_id=?",
            submission.projectId(), submission.scope());
        if (rows.size() != 1) return;
        int version = ((Number) rows.getFirst().get("version")).intValue();
        ObjectNode project = (ObjectNode) json.readTree(Objects.toString(rows.getFirst().get("body_json"), "{}"));
        ObjectNode live = null;
        for (JsonNode candidate : project.path("tasks")) {
          if (task.path("id").asText().equals(candidate.path("id").asText())) { live = (ObjectNode) candidate; break; }
        }
        if (live == null || TASK_CANCELLED.equals(live.path("status").asText())) return;
        // A concurrent project edit invalidates the model snapshot; keep the task terminal and
        // discard its output rather than overwriting the user's newer project body.
        String error = version == submission.acceptedVersion()
            ? "TERMINAL_PERSISTENCE_FAILED"
            : "PROJECT_CHANGED_DURING_GENERATION";
        task.put("status", "FAILED").put("error", error).remove("result");
        for (JsonNode candidate : project.path("tasks")) {
          if (task.path("id").asText().equals(candidate.path("id").asText())) {
            ((ObjectNode) candidate).setAll(task);
            break;
          }
        }
        int nextVersion = version + 1;
        project.put("version", nextVersion);
        int changed = jdbc.update(
            "UPDATE mate_presales_project SET body_json=?,version=? WHERE id=? AND workspace_id=? AND version=?",
            json.writeValueAsString(project), nextVersion, submission.projectId(), submission.scope(), version);
        if (changed == 1) return;
      }
    } catch (Exception e) {
      log.warn("Unable to persist terminal presales failure without actor: project={} task={}", submission.projectId(), task.path("id").asText(), e);
    }
  }

  /**
   * A process restart cannot resume a model stream safely. Mark persisted RUNNING tasks terminal
   * before accepting new work. This direct compare-and-swap only changes the task envelope and
   * preserves the project JSON collections; no model or employee call is made during recovery.
   */
  @EventListener(ApplicationReadyEvent.class)
  public void recoverStaleTasks() {
    if (jdbc == null) return;
    for (Map<String, Object> row : jdbc.queryForList("SELECT id,workspace_id,version,body_json FROM mate_presales_project")) {
      String projectId = Objects.toString(row.get("id"), "");
      String scope = Objects.toString(row.get("workspace_id"), "");
      int version = ((Number) row.get("version")).intValue();
      ObjectNode project;
      try {
        project = (ObjectNode) json.readTree(Objects.toString(row.get("body_json"), "{}"));
      } catch (Exception e) {
        log.warn("Unable to inspect presales project during recovery: {}", projectId, e);
        continue;
      }
      boolean changed = false;
      for (JsonNode node : project.path("tasks")) {
        if (node instanceof ObjectNode task
            && TASK_RUNNING.equals(task.path("status").asText())
            && isRecoveryStale(task, scope, projectId)) {
          task.put("status", "FAILED").put("error", INTERRUPTED_BY_RESTART).put("finishedAt", Instant.now().toString());
          task.remove("result");
          changed = true;
        }
      }
      if (!changed) continue;
      int nextVersion = version + 1;
      project.put("version", nextVersion);
      try {
        String body = json.writeValueAsString(project);
        jdbc.update(
            "UPDATE mate_presales_project SET body_json=?,version=? WHERE id=? AND workspace_id=? AND version=?",
            body, nextVersion, projectId, scope, version);
      } catch (Exception e) {
        log.warn("Unable to persist interrupted presales tasks: project={}", projectId, e);
      }
    }
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

  private static String key(String scope, String projectId, String taskId) {
    return String.join("/", Objects.toString(scope, ""), Objects.toString(projectId, ""), Objects.toString(taskId, ""));
  }
}

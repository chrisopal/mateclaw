package vip.mate.presales;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import vip.mate.presales.repository.PresalesRenderTaskRepository;
import vip.mate.presales.repository.PresalesRenderTaskRepository.RenderTask;

/** Durable synchronous rendering with independently committed preparation and acceptance. */
final class PresalesRenderExecution {
    private final PresalesRenderTaskRepository tasks;
    private final PresalesArtifacts artifacts;
    private final ObjectMapper json;
    private final TransactionTemplate outside;
    private final TransactionTemplate shortTransaction;

    record Prepared(String actor, String requestHash, ObjectNode input, ObjectNode replay) {}

    private record Attempt(Prepared prepared, RenderTask task) {}

    PresalesRenderExecution(
            PresalesRenderTaskRepository tasks,
            PresalesArtifacts artifacts,
            ObjectMapper json,
            PlatformTransactionManager transactions) {
        this.tasks = tasks;
        this.artifacts = artifacts;
        this.json = json;
        outside = new TransactionTemplate(transactions);
        outside.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        shortTransaction = new TransactionTemplate(transactions);
        shortTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        shortTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    ObjectNode execute(
            String scope,
            String projectId,
            PresalesDtos.Command command,
            Supplier<Prepared> prepare,
            BiFunction<Prepared, List<PresalesArtifacts.RenderedFile>, ObjectNode> accept) {
        return outside.execute(
                ignored -> {
                    Attempt attempt =
                            shortTransaction.execute(
                                    status -> {
                                        Prepared prepared = prepare.get();
                                        if (prepared.replay() != null)
                                            return new Attempt(prepared, null);
                                        return new Attempt(
                                                prepared,
                                                acquire(scope, projectId, command, prepared));
                                    });
                    if (attempt.task() == null) return attempt.prepared().replay();
                    var task = attempt.task();
                    try {
                        ObjectNode frozen = decode(task.inputJson());
                        var files = artifacts.render((ObjectNode) frozen.path("artifacts"));
                        return shortTransaction.execute(
                                status -> {
                                    // Domain preparation locks project then authority before the
                                    // task row.
                                    Prepared current = prepare.get();
                                    if (current.replay() != null) return current.replay();
                                    var active =
                                            tasks.findTask(task.taskId(), true)
                                                    .orElseThrow(() -> staleAttempt());
                                    if (!"RUNNING".equals(active.status())
                                            || !task.attemptId().equals(active.attemptId())
                                            || !active.leaseUntil().isAfter(now()))
                                        throw staleAttempt();
                                    if (!frozen.equals(current.input()))
                                        throw conflict(
                                                "RENDER_INPUT_CHANGED",
                                                "Render input changed; review before retrying");
                                    ObjectNode response = accept.apply(current, files);
                                    if (tasks.finish(
                                                    task.taskId(),
                                                    task.attemptId(),
                                                    "SUCCEEDED",
                                                    now())
                                            != 1) throw staleAttempt();
                                    return response;
                                });
                    } catch (RuntimeException | Error failure) {
                        try {
                            shortTransaction.executeWithoutResult(
                                    status ->
                                            tasks.finish(
                                                    task.taskId(),
                                                    task.attemptId(),
                                                    "FAILED",
                                                    now()));
                        } catch (RuntimeException cleanup) {
                            failure.addSuppressed(cleanup);
                        }
                        throw failure;
                    }
                });
    }

    private RenderTask acquire(
            String scope, String projectId, PresalesDtos.Command command, Prepared prepared) {
        var existing = tasks.findOperation(scope, prepared.actor(), command.operationId(), true);
        LocalDateTime now = now();
        if (existing.isPresent()) {
            var task = existing.get();
            if (!task.requestHash().equals(prepared.requestHash()))
                throw conflict("OPERATION_CONFLICT", "Operation id reused with different input");
            if (!decode(task.inputJson()).equals(prepared.input()))
                throw conflict(
                        "RENDER_INPUT_CHANGED", "Render input changed; review before retrying");
            String nextAttempt = UUID.randomUUID().toString();
            if (tasks.claim(task.taskId(), task.attemptId(), nextAttempt, now, now.plusMinutes(10))
                    != 1)
                throw conflict("RENDER_IN_PROGRESS", "Release rendering is already in progress");
            return tasks.findTask(task.taskId(), false).orElseThrow();
        }
        var task =
                new RenderTask(
                        UUID.randomUUID().toString(),
                        scope,
                        prepared.actor(),
                        command.operationId(),
                        projectId,
                        command.expectedVersion(),
                        prepared.requestHash(),
                        UUID.randomUUID().toString(),
                        1,
                        "RUNNING",
                        prepared.input().toString(),
                        now.plusMinutes(10),
                        now,
                        now);
        tasks.insertTask(task);
        return task;
    }

    private ObjectNode decode(String value) {
        try {
            return (ObjectNode) json.readTree(value);
        } catch (Exception invalid) {
            throw new IllegalStateException("Invalid persisted render input", invalid);
        }
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private static PresalesRejected staleAttempt() {
        return conflict("RENDER_ATTEMPT_EXPIRED", "Render attempt is no longer active");
    }

    private static PresalesRejected conflict(String code, String message) {
        return new PresalesRejected(409, code, message);
    }
}

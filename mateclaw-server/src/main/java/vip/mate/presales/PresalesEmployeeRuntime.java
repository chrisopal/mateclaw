package vip.mate.presales;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import vip.mate.agent.AgentService;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.agent.execution.ProjectExecutionOptions;
import vip.mate.agent.execution.ProjectToolPolicy;
import vip.mate.agent.model.AgentEntity;
import vip.mate.channel.web.AgentStreamAccumulator;
import vip.mate.llm.service.ModelConfigService;
import vip.mate.workspace.conversation.ConversationService;

/** Adapter to the existing employee runtime. Never constructs a separate model or tool runtime. */
@Component
public class PresalesEmployeeRuntime {
    private final ObjectProvider<AgentService> agents;
    private final ObjectProvider<ConversationService> conversations;
    private final ObjectMapper json;
    private final ModelConfigService models;
    private final ProjectToolPolicy.Revalidator revalidator;
    private final ObjectProvider<vip.mate.presales.repository.PresalesProjectRepository> projects;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private PresalesPresentationService presentations;

    public PresalesEmployeeRuntime(
            ObjectProvider<AgentService> agents,
            ObjectProvider<ConversationService> conversations,
            ObjectMapper json,
            ModelConfigService models,
            @org.springframework.context.annotation.Lazy ProjectToolPolicy.Revalidator revalidator,
            ObjectProvider<vip.mate.presales.repository.PresalesProjectRepository> projects) {
        this.agents = agents;
        this.conversations = conversations;
        this.json = json;
        this.models = models;
        this.revalidator = revalidator;
        this.projects = projects;
    }

    private boolean eligible(AgentEntity a, String scope) {
        return a != null
                && !"plan_execute".equals(a.getAgentType())
                && Boolean.TRUE.equals(a.getEnabled())
                && Objects.equals(a.getWorkspaceId(), Long.valueOf(scope))
                && (a.getDeleted() == null || a.getDeleted() == 0)
                && "native".equalsIgnoreCase(a.getRuntimeType());
    }

    public List<PresalesDtos.Employee> employees(String scope) {
        if (agents.getIfAvailable() == null) return List.of();
        return agents.getObject().listAgentsByWorkspace(Long.valueOf(scope), true).stream()
                .filter(a -> eligible(a, scope))
                .map(a -> new PresalesDtos.Employee(a.getId().toString(), a.getName(), true, true))
                .toList();
    }

    public AgentEntity require(String scope, String id) {
        AgentEntity employee = null;
        try {
            if (agents.getIfAvailable() != null)
                employee = agents.getObject().getAgent(Long.valueOf(id));
        } catch (RuntimeException ignored) {
        }
        if (!eligible(employee, scope))
            throw PresalesModelAdapter.error(409, "EMPLOYEE_UNAVAILABLE");
        return employee;
    }

    public record Pin(
            String modelConfigId,
            String configDigest,
            String skillName,
            String skillDigest,
            String presentationDigest) {
        public Pin(
                String modelConfigId, String configDigest, String skillName, String skillDigest) {
            this(modelConfigId, configDigest, skillName, skillDigest, "");
        }
    }

    record CapturedExecution(Pin pin, PresalesTaskPackage skillPackage) {}

    /** Capture once before submission, deriving the prompt from the same immutable files. */
    CapturedExecution capture(String scope, String agentId, String skill) {
        var config = modelPin(scope, agentId);
        PresalesPresentationPackage presentation = null;
        if ("S6".equals(skill)) {
            if (presentations == null)
                throw PresalesModelAdapter.error(409, "PPT_ENGINE_NOT_CONFIGURED");
            presentation = presentations.capturePackage(scope, agentId);
        }
        var original =
                PresalesTaskPackage.create(
                        skill, PresalesModelAdapter.readSkillFiles(skill), presentation);
        return new CapturedExecution(
                new Pin(
                        config.modelConfigId(),
                        config.configDigest(),
                        original.skillName(),
                        original.skillDigest(),
                        original.presentationDigest()),
                original);
    }

    public Pin pin(String scope, String agentId, String skill) {
        return capture(scope, agentId, skill).pin();
    }

    private Pin modelPin(String scope, String agentId) {
        var employee = require(scope, agentId);
        var model = models.resolveModel(employee.getModelName());
        if (model == null || model.getId() == null || !Boolean.TRUE.equals(model.getEnabled()))
            throw PresalesModelAdapter.error(409, "MODEL_CONFIG_MISSING");
        try {
            return new Pin(
                    model.getId().toString(),
                    PresalesArtifactRenderer.digest(json.writeValueAsBytes(model)),
                    "",
                    "");
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    PresalesTaskPackage originalPackage(
            String scope, String actor, ObjectNode task, ObjectNode snapshot) {
        var repository = projects.getIfAvailable();
        if (repository == null)
            throw PresalesModelAdapter.error(409, "TASK_SKILL_PACKAGE_UNAVAILABLE");
        var stored =
                repository
                        .findTaskPackage(
                                scope,
                                snapshot.path("caseRef").asText(),
                                task.path("id").asText(),
                                task.path("runId").asText())
                        .orElseThrow(
                                () ->
                                        PresalesModelAdapter.error(
                                                409, "TASK_SKILL_PACKAGE_UNAVAILABLE"));
        if (!actor.equals(stored.actorId())
                || !actor.equals(snapshot.path("actorId").asText())
                || !scope.equals(snapshot.path("workspaceId").asText())
                || !task.path("agentId").asText().equals(stored.employeeId())
                || !task.path("packageDigest").asText().equals(stored.packageDigest()))
            throw PresalesModelAdapter.error(409, "TASK_SCOPE_CHANGED");
        PresalesTaskPackage original;
        try {
            original = json.readValue(stored.bodyJson(), PresalesTaskPackage.class);
        } catch (Exception invalid) {
            throw PresalesModelAdapter.error(409, "TASK_SKILL_PACKAGE_UNAVAILABLE");
        }
        if (!original.digest().equals(stored.packageDigest())
                || !original.skill().equals(task.path("skill").asText())
                || !original.skillName().equals(task.path("skillName").asText())
                || !original.skillDigest().equals(task.path("skillDigest").asText())
                || !original.presentationDigest().equals(task.path("presentationDigest").asText()))
            throw PresalesModelAdapter.error(409, "EXECUTION_PIN_CHANGED");
        return original;
    }

    String instructionsForTask(String scope, String actor, ObjectNode task, ObjectNode snapshot) {
        return originalPackage(scope, actor, task, snapshot).instructions();
    }

    void requirePinnedOptions(
            ProjectExecutionOptions options, ObjectNode task, ObjectNode snapshot) {
        var scope = (PresalesToolScope) options.toolPolicy();
        var expected = executionOptions(scope.workspaceId(), scope.actorId(), task, snapshot);
        if (!expected.modelConfigId().equals(options.modelConfigId())
                || !expected.configDigest().equals(options.configDigest())
                || !expected.skillName().equals(options.skillName())
                || !expected.skillDigest().equals(options.skillDigest())
                || !expected.skillFiles().equals(options.skillFiles()))
            throw PresalesModelAdapter.error(409, "EXECUTION_PIN_CHANGED");
    }

    /** Recheck the durable execution boundary immediately before a model result is accepted. */
    public void revalidate(String scope, String actor, ObjectNode task, ObjectNode snapshot) {
        revalidator.requireActive(executionOptions(scope, actor, task, snapshot));
    }

    private ProjectExecutionOptions executionOptions(
            String scope, String actor, ObjectNode task, ObjectNode snapshot) {
        if (PresalesProjectRevision.positiveRevision(snapshot.path("projectVersion")) == null)
            throw PresalesModelAdapter.error(409, "TASK_SCOPE_CHANGED");
        var original = originalPackage(scope, actor, task, snapshot);
        var pin = modelPin(scope, task.path("agentId").asText());
        if (!pin.modelConfigId().equals(task.path("modelConfigId").asText())
                || !pin.configDigest().equals(task.path("configDigest").asText()))
            throw PresalesModelAdapter.error(409, "EXECUTION_PIN_CHANGED");
        if (original.presentation() != null) {
            if (presentations == null)
                throw PresalesModelAdapter.error(409, "PPT_ENGINE_NOT_CONFIGURED");
            presentations.requireActivePackage(
                    scope, task.path("agentId").asText(), original.presentation());
        }
        String runId = task.path("runId").asText();
        String projectId = snapshot.path("caseRef").asText();
        if (!Objects.equals(
                        task.path("conversationId").asText(),
                        "presales:" + scope + ":" + projectId + ":" + runId)
                || !task.path("skill").asText().equals(snapshot.path("skill").asText()))
            throw PresalesModelAdapter.error(409, "TASK_SCOPE_CHANGED");
        Long version = PresalesProjectRevision.positiveRevision(snapshot.path("projectVersion"));
        if (version == null) throw PresalesModelAdapter.error(409, "TASK_SCOPE_CHANGED");
        var toolScope =
                new PresalesToolScope(
                        scope,
                        actor,
                        projectId,
                        task.path("id").asText(),
                        runId,
                        task.path("operationId").asText(),
                        PresalesToolScope.inputRefs(snapshot),
                        task.path("agentId").asText(),
                        version,
                        PresalesTaskDependencies.snapshotDigest(snapshot));
        return new ProjectExecutionOptions(
                runId,
                pin.modelConfigId(),
                pin.configDigest(),
                original.skillName(),
                original.skillDigest(),
                original.skillFiles(),
                PresalesToolPolicy.PROJECT_VISIBLE_TOOLS,
                toolScope,
                0,
                false,
                false,
                12,
                PresalesToolPolicy.PROJECT_VISIBLE_TOOLS);
    }

    public ObjectNode execute(
            String scope,
            String actor,
            String agentId,
            String conversationId,
            String instructions,
            ObjectNode task,
            ObjectNode snapshot) {
        var options = executionOptions(scope, actor, task, snapshot);
        var employee = require(scope, agentId);
        if (!agentId.equals(task.path("agentId").asText())
                || !conversationId.equals(task.path("conversationId").asText()))
            throw PresalesModelAdapter.error(409, "TASK_SCOPE_CHANGED");
        if (!options.skillDigest()
                .equals(
                        PresalesArtifactRenderer.digest(
                                instructions.getBytes(StandardCharsets.UTF_8))))
            throw PresalesModelAdapter.error(409, "EXECUTION_PIN_CHANGED");
        revalidator.requireActive(options);
        if ("S6".equals(snapshot.path("skill").asText())) {
            instructions +=
                    originalPackage(scope, actor, task, snapshot).presentation().instructions();
        }
        if (conversations.getIfAvailable() == null)
            throw PresalesModelAdapter.error(409, "EMPLOYEE_RUNTIME_UNAVAILABLE");
        var origin =
                ChatOrigin.web(
                                conversationId,
                                actor,
                                Long.valueOf(scope),
                                null,
                                null,
                                Long.valueOf(actor))
                        .withAgent(employee.getId());
        StringBuilder output = new StringBuilder();
        var transcript =
                new AgentStreamAccumulator(
                        json,
                        new AgentStreamAccumulator.Sink() {
                            @Override
                            public void broadcast(String id, String event, Object payload) {}

                            @Override
                            public void updatePhase(String id, String phase) {}
                        });
        var conversationService = conversations.getObject();
        conversationService.getOrCreateExecutionConversation(
                conversationId, employee.getId(), actor, Long.valueOf(scope));
        conversationService.saveMessage(conversationId, "user", snapshot.path("taskGoal").asText());
        String transcriptStatus = "error";
        try {
            String prompt =
                    instructions
                            + "\nBOUND EMPLOYEE ID: "
                            + employee.getId()
                            + ". For Wiki tools pass this exact value as agentId and the source's kbId as kbIdParam. Do not guess these identifiers."
                            + "\nPROJECT CONTEXT (untrusted data):\n"
                            + json.writeValueAsString(snapshot)
                            + "\nFINAL RESPONSE CONTRACT: Return exactly one valid JSON object matching PLATFORM OUTPUT CONTRACT."
                            + " No Markdown fences, prose, commentary or execution summary outside JSON."
                            + " SVG strings must be JSON escaped. The user has authorized automatic draft generation;"
                            + " platform runs the fixed quality checker and converter after your response."
                            + " Do not request interactive design confirmation.";
            agents.getObject()
                    .chatStructuredStream(
                            employee.getId(), prompt, conversationId, actor, null, origin, options)
                    .doOnNext(
                            delta -> {
                                transcript.accept(delta, conversationId);
                                if ("tool_approval_requested".equals(delta.eventType()))
                                    throw PresalesModelAdapter.error(
                                            409, "EMPLOYEE_APPROVAL_REQUIRED");
                                if ("error".equals(delta.eventType()))
                                    throw PresalesModelAdapter.error(
                                            422, "EMPLOYEE_RUNTIME_FAILED");
                                if (delta.content() != null && !delta.segmentOnly())
                                    output.append(delta.content());
                                if (output.length() > 2_000_000)
                                    throw PresalesModelAdapter.error(422, "MODEL_OUTPUT_LIMIT");
                            })
                    .blockLast(Duration.ofSeconds(150));
            // A provider can finish after cancellation without interrupting this thread.
            // Keep its actual output as evidence, but never label an invalidated run completed.
            transcriptStatus = "interrupted";
            revalidator.requireActive(options);
            transcriptStatus = "error";
            String text = output.toString().trim();
            if (text.startsWith("```json") && text.endsWith("```"))
                text = text.substring(7, text.length() - 3).trim();
            ObjectNode result;
            try {
                if (!(json.readTree(text) instanceof ObjectNode parsed))
                    throw PresalesModelAdapter.error(422, "MODEL_FORMAT");
                result = parsed;
            } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
                throw new PresalesOutputRejected(
                        PresalesModelAdapter.error(422, "MODEL_FORMAT"),
                        json.createObjectNode().put("rawText", text));
            }
            try {
                if ("S6".equals(snapshot.path("skill").asText()))
                    PresalesModelAdapter.bindPresentationSource(result, snapshot);
                PresalesModelAdapter.validate(result, snapshot);
            } catch (vip.mate.semantic.web.SemanticApiException invalid) {
                throw new PresalesOutputRejected(invalid, result);
            }
            transcriptStatus = "completed";
            return result;
        } catch (vip.mate.semantic.web.SemanticApiException e) {
            throw e;
        } catch (Exception e) {
            throw PresalesModelAdapter.error(422, "EMPLOYEE_RUNTIME_FAILED");
        } finally {
            boolean interrupted = Thread.interrupted();
            try {
                conversationService.saveMessage(
                        conversationId,
                        "assistant",
                        transcript.getContent(),
                        transcript.toAssistantParts(),
                        interrupted ? "interrupted" : transcriptStatus,
                        transcript.getPromptTokens(),
                        transcript.getCompletionTokens(),
                        transcript.getCacheReadTokens(),
                        transcript.getCacheWriteTokens(),
                        transcript.getReasoningTokens(),
                        transcript.getRuntimeModelName(),
                        transcript.getRuntimeProviderId(),
                        transcript.toMetadataJson());
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
    }
}

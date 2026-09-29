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

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private PresalesPresentationService presentations;

    public PresalesEmployeeRuntime(
            ObjectProvider<AgentService> agents,
            ObjectProvider<ConversationService> conversations,
            ObjectMapper json,
            ModelConfigService models,
            @org.springframework.context.annotation.Lazy
                    ProjectToolPolicy.Revalidator revalidator) {
        this.agents = agents;
        this.conversations = conversations;
        this.json = json;
        this.models = models;
        this.revalidator = revalidator;
    }

    private boolean eligible(AgentEntity a, String scope) {
        return a != null
                && !"plan_execute".equals(a.getAgentType())
                && Boolean.TRUE.equals(a.getEnabled())
                && Objects.equals(a.getWorkspaceId(), Long.valueOf(scope))
                && (a.getDeleted() == null || a.getDeleted() == 0)
                && "native".equalsIgnoreCase(a.getRuntimeType());
    }

    public List<Map<String, Object>> employees(String scope) {
        if (agents.getIfAvailable() == null) return List.of();
        return agents.getObject().listAgentsByWorkspace(Long.valueOf(scope), true).stream()
                .filter(a -> eligible(a, scope))
                .map(
                        a ->
                                Map.<String, Object>of(
                                        "id",
                                        a.getId().toString(),
                                        "name",
                                        a.getName(),
                                        "enabled",
                                        true,
                                        "available",
                                        true))
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

    /** Resolve the same server-owned model and classpath skill at submit and execution time. */
    public Pin pin(String scope, String agentId, String skill) {
        var employee = require(scope, agentId);
        var model = models.resolveModel(employee.getModelName());
        if (model == null || model.getId() == null || !Boolean.TRUE.equals(model.getEnabled()))
            throw PresalesModelAdapter.error(409, "MODEL_CONFIG_MISSING");
        try {
            String instructions = PresalesModelAdapter.instructions(skill);
            return new Pin(
                    model.getId().toString(),
                    PresalesArtifactRenderer.digest(json.writeValueAsBytes(model)),
                    PresalesModelAdapter.skillName(skill),
                    PresalesArtifactRenderer.digest(instructions.getBytes(StandardCharsets.UTF_8)),
                    "S6".equals(skill) ? presentationPin(scope, agentId).digest() : "");
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Unable to pin presales model configuration", e);
        }
    }

    private PresalesPresentationService.ExecutionPin presentationPin(String scope, String agentId) {
        if (presentations == null)
            throw PresalesModelAdapter.error(409, "PPT_ENGINE_NOT_CONFIGURED");
        return presentations.executionPin(scope, agentId);
    }

    /** Recheck the durable execution boundary immediately before a model result is accepted. */
    public void revalidate(String scope, String actor, ObjectNode task, ObjectNode snapshot) {
        revalidator.requireActive(executionOptions(scope, actor, task, snapshot));
    }

    private ProjectExecutionOptions executionOptions(
            String scope, String actor, ObjectNode task, ObjectNode snapshot) {
        var pin = pin(scope, task.path("agentId").asText(), task.path("skill").asText());
        if (!pin.modelConfigId().equals(task.path("modelConfigId").asText())
                || !pin.configDigest().equals(task.path("configDigest").asText())
                || !pin.skillName().equals(task.path("skillName").asText())
                || !pin.skillDigest().equals(task.path("skillDigest").asText())
                || !pin.presentationDigest().equals(task.path("presentationDigest").asText()))
            throw PresalesModelAdapter.error(409, "EXECUTION_PIN_CHANGED");
        String runId = task.path("runId").asText();
        String projectId = snapshot.path("caseRef").asText();
        if (!Objects.equals(
                        task.path("conversationId").asText(),
                        "presales:" + scope + ":" + projectId + ":" + runId)
                || !task.path("skill").asText().equals(snapshot.path("skill").asText()))
            throw PresalesModelAdapter.error(409, "TASK_SCOPE_CHANGED");
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
                        snapshot.path("projectVersion").asInt());
        return new ProjectExecutionOptions(
                runId,
                pin.modelConfigId(),
                pin.configDigest(),
                pin.skillName(),
                pin.skillDigest(),
                Map.of("SKILL.md", PresalesModelAdapter.readSkill(task.path("skill").asText())),
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
            var presentation = presentationPin(scope, agentId);
            if (!presentation.digest().equals(task.path("presentationDigest").asText()))
                throw PresalesModelAdapter.error(409, "EXECUTION_PIN_CHANGED");
            instructions += presentation.instructions();
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
        try {
            conversations
                    .getObject()
                    .getOrCreateConversation(
                            conversationId, employee.getId(), actor, Long.valueOf(scope));
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
            return result;
        } catch (vip.mate.semantic.web.SemanticApiException e) {
            throw e;
        } catch (Exception e) {
            throw PresalesModelAdapter.error(422, "EMPLOYEE_RUNTIME_FAILED");
        }
    }
}

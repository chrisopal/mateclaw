package vip.mate.bidding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.Map;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import vip.mate.agent.AgentService;

/** Project employee execution boundary and strict stream result collector. */
@Service
public class BiddingEmployeeRuntime {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_OUTPUT_BYTES = 2 * 1024 * 1024;
    private static final Duration EXECUTION_TIMEOUT = Duration.ofSeconds(300);

    private final BiddingAccess access;
    private final BiddingDependencies dependencies;
    private final BiddingEmployeeBindings employeeBindings;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final vip.mate.agent.AgentService agents;
    private final vip.mate.workspace.conversation.ConversationService conversations;
    private final vip.mate.agent.repository.AgentMapper agentMapper;

    public BiddingEmployeeRuntime(BiddingAccess access, BiddingDependencies dependencies,
            BiddingEmployeeBindings employeeBindings,
            org.springframework.jdbc.core.JdbcTemplate jdbc, vip.mate.agent.AgentService agents,
            vip.mate.workspace.conversation.ConversationService conversations,
            vip.mate.agent.repository.AgentMapper agentMapper) {
        this.access = access;
        this.dependencies = dependencies;
        this.employeeBindings = employeeBindings;
        this.jdbc = jdbc;
        this.agents = agents;
        this.conversations = conversations;
        this.agentMapper = agentMapper;
    }

    /** Validates the active server-owned attempt before any model/tool work. */
    public BiddingTypes.Claim claim(org.springframework.ai.chat.model.ToolContext context) {
        BiddingTypes.Claim claim = BiddingToolScope.claim(context);
        requireActive(claim);
        return claim;
    }

    public void requireActive(BiddingTypes.Claim claim) {
        access.requireActor(claim.scope(), claim.scope().actorId());
        String token = jdbc.query("SELECT a.token FROM mate_bidding_attempt a JOIN mate_bidding_task t ON t.id=a.task_id "
                        + "WHERE a.id=? AND a.task_id=? AND a.token=? AND a.state='RUNNING' "
                        + "AND t.status='RUNNING' AND t.active_attempt_id=a.id AND t.workspace_id=? AND t.project_id=?",
                rs -> rs.next() ? rs.getString(1) : null, claim.attemptId(), claim.taskId(), claim.token(),
                claim.scope().workspaceId(), claim.scope().projectId());
        if (token == null) throw BiddingAccess.error(409, "ATTEMPT_STALE", "Task attempt is no longer active");
        employeeBindings.validate(claim.scope(), claim.agentId(), claim.configDigest());
        String currentModel = employeeBindings.modelConfigId(claim.scope(), claim.agentId());
        if (!java.util.Objects.equals(currentModel, claim.modelConfigId()))
            throw BiddingAccess.error(409, "EMPLOYEE_CONFIG_CHANGED", "数字员工模型配置已变化");
        validatePinnedSkill(claim);
        dependencies.validate(claim.scope(), claim.inputRefs());
    }

    private void validatePinnedSkill(BiddingTypes.Claim claim) {
        String stored = jdbc.query("SELECT files_json FROM mate_bidding_skill_package WHERE workspace_id=? AND project_id=? AND skill_id=? AND version=? AND digest=?",
                rs -> rs.next() ? rs.getString(1) : null, claim.scope().workspaceId(), claim.scope().projectId(),
                claim.skill().skillId(), claim.skill().version(), claim.skill().digest());
        if (stored == null) throw BiddingAccess.error(409, "SKILL_PIN_STALE", "固定技能包已变化");
        try {
            Map<String, String> persisted = JSON.readValue(stored,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() {});
            if (!persisted.equals(claim.skill().files()))
                throw BiddingAccess.error(409, "SKILL_PIN_STALE", "固定技能包内容不匹配");
        } catch (BiddingApiException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Invalid pinned skill package", e);
        }
    }

    public BiddingTypes.Execution execute(BiddingTypes.Claim claim) {
        requireActive(claim);
        long agentId = BiddingAccess.parse(claim.agentId(), "EMPLOYEE_UNAVAILABLE");
        var agent = agentMapper.selectById(agentId);
        if (agent == null || !Boolean.TRUE.equals(agent.getEnabled())
                || !Long.valueOf(BiddingAccess.parse(claim.scope().workspaceId(), "WORKSPACE_REQUIRED")).equals(agent.getWorkspaceId())
                || !"native".equalsIgnoreCase(agent.getRuntimeType()) || "plan_execute".equals(agent.getAgentType())) {
            return failure("EMPLOYEE_UNAVAILABLE", "PERMANENT", false, false, false);
        }
        String conversationId = "bidding:" + claim.scope().projectId() + ":" + claim.attemptId();
        conversations.getOrCreateConversation(conversationId, agentId, claim.scope().actorId(),
                BiddingAccess.parse(claim.scope().workspaceId(), "WORKSPACE_REQUIRED"));
        var files = claim.skill().files();
        var options = new vip.mate.agent.execution.ProjectExecutionOptions(claim.attemptId(), claim.modelConfigId(),
                claim.configDigest(), skillName(files), claim.skill().digest(), files,
                java.util.Set.of("load_skill", "readSkillFile", "bidding_read_source"),
                new BiddingToolScope(claim), 0, false, false, 12);
        var origin = new vip.mate.agent.context.ChatOrigin(agentId, conversationId, claim.scope().actorId(),
                BiddingAccess.parse(claim.scope().workspaceId(), "WORKSPACE_REQUIRED"), null, null, null,
                false, null, null, null, null, null);
        String prompt;
        try { prompt = JSON.writeValueAsString(Map.of("task", claim.input(), "references", claim.inputRefs())); }
        catch (Exception e) { return failure("VALIDATION_FAILED", "VALIDATION", false, false, false); }
        try {
            BiddingTypes.Execution result = readResult(agents.chatStructuredStream(agentId, prompt, conversationId,
                    claim.scope().actorId(), null, origin, options).timeout(EXECUTION_TIMEOUT),
                    claim.skill().digest(), claim.configDigest());
            try { requireActive(claim); }
            catch (BiddingApiException revoked) {
                return failure(revoked.code(), "PERMANENT", true, result.failure() == null, false);
            }
            return result;
        } catch (vip.mate.exception.MateClawException e) {
            String code = e.getMsgKey() != null && e.getMsgKey().contains("project_model")
                    ? "MODEL_RUNTIME_UNSUPPORTED" : "EMPLOYEE_UNAVAILABLE";
            return failure(code, "PERMANENT", false, false, false);
        }
    }

    public static BiddingTypes.Execution readResult(Flux<AgentService.StreamDelta> stream,
            String expectedSkillDigest, String expectedConfigDigest) {
        final Object[] state = new Object[5];
        final StringBuilder output = new StringBuilder();
        try {
            stream.doOnNext(delta -> {
                if (delta == null) return;
                if (delta.isEvent()) {
                    Map<String, Object> data = delta.eventData() == null ? Map.of() : delta.eventData();
                    switch (delta.eventType()) {
                        case "project_skill_loaded" -> state[0] = data.get("digest");
                        // Preserve the originating node's precise classification; the graph
                        // may append a generic terminal failure after a more useful one.
                        case "project_execution_failed" -> { if (state[1] == null) state[1] = data; }
                        case "project_execution_completed" -> state[2] = data;
                        default -> { }
                    }
                } else if (delta.kind() == vip.mate.agent.ContentKind.FINAL_ANSWER && delta.content() != null) {
                    output.append(delta.content());
                    if (output.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_OUTPUT_BYTES)
                        throw new OutputLimitException();
                }
            }).blockLast(EXECUTION_TIMEOUT);
        } catch (OutputLimitException e) {
            return failure("OUTPUT_LIMIT", "VALIDATION", true, true, false);
        } catch (Exception e) {
            if (hasCause(e, OutputLimitException.class))
                return failure("OUTPUT_LIMIT", "VALIDATION", true, true, false);
            return failure(hasCause(e, java.util.concurrent.TimeoutException.class) ? "EXECUTION_TIMEOUT" : "STREAM_INCOMPLETE",
                    "TRANSIENT", true, output.length() > 0, false);
        }
        if (state[1] instanceof Map<?, ?> failed) {
            return failure(String.valueOf(failed.containsKey("code") ? failed.get("code") : "STREAM_INCOMPLETE"),
                    String.valueOf(failed.containsKey("category") ? failed.get("category") : "TRANSIENT"),
                    Boolean.TRUE.equals(failed.get("resultUnknown")), Boolean.TRUE.equals(failed.get("partial")),
                    Boolean.TRUE.equals(failed.get("stopped")));
        }
        if (!(state[0] instanceof String skillDigest) || !skillDigest.equals(expectedSkillDigest))
            return failure("SKILL_NOT_LOADED", "PERMANENT", false, false, false);
        if (!(state[2] instanceof Map<?, ?> completed)
                || !expectedConfigDigest.equals(completed.get("configDigest"))
                || !expectedSkillDigest.equals(completed.get("skillDigest")))
            return failure("EXECUTION_NOT_COMPLETED", "TRANSIENT", true, output.length() > 0, false);
        try {
            if (output.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_OUTPUT_BYTES)
                return failure("OUTPUT_LIMIT", "VALIDATION", true, true, false);
            ObjectNode payload = (ObjectNode) JSON.readTree(output.toString());
            if (payload == null) throw new IllegalArgumentException("empty output");
            return new BiddingTypes.Execution(payload, null, skillDigest, expectedConfigDigest, null);
        } catch (Exception e) {
            return failure("OUTPUT_INVALID", "VALIDATION", false, false, false);
        }
    }

    private static BiddingTypes.Execution failure(String code, String category, boolean unknown,
            boolean partial, boolean stopped) {
        return new BiddingTypes.Execution(null, new BiddingTypes.Failure(code, category, null, unknown, partial, stopped),
                null, null, null);
    }

    private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        for (Throwable current = error; current != null; current = current.getCause())
            if (type.isInstance(current)) return true;
        return false;
    }

    private static String skillName(Map<String, String> files) {
        String markdown = files.getOrDefault("SKILL.md", "");
        var matcher = java.util.regex.Pattern.compile("(?m)^name:\\s*['\"]?([^'\"\\r\\n]+)").matcher(markdown);
        if (matcher.find()) return matcher.group(1).trim();
        matcher = java.util.regex.Pattern.compile("(?m)^#\\s+(.+)$").matcher(markdown);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    private static final class OutputLimitException extends RuntimeException { }
}

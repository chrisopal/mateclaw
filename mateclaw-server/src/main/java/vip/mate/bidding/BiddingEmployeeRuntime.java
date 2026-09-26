package vip.mate.bidding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.Map;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import vip.mate.agent.AgentService;

/** Project employee execution boundary and strict stream result collector. */
@Service
public class BiddingEmployeeRuntime implements vip.mate.agent.execution.ProjectToolPolicy.Revalidator {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final java.util.Set<String> SCHEMA_KEYS = java.util.Set.of(
            "$schema", "$id", "$comment", "title", "description", "default", "examples",
            "type", "required", "properties", "additionalProperties", "items", "enum", "const",
            "minLength", "maxLength", "pattern", "minimum", "maximum", "minItems", "maxItems");
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
            @Lazy BiddingEmployeeBindings employeeBindings,
            org.springframework.jdbc.core.JdbcTemplate jdbc, @Lazy vip.mate.agent.AgentService agents,
            @Lazy vip.mate.workspace.conversation.ConversationService conversations,
            @Lazy vip.mate.agent.repository.AgentMapper agentMapper) {
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

    @Override public void requireActive(vip.mate.agent.execution.ProjectExecutionOptions options) {
        if (options == null || !(options.toolPolicy() instanceof BiddingToolScope scope))
            throw BiddingAccess.error(403, "CLAIM_REQUIRED", "Trusted active task claim is required");
        requireActive(scope.claim());
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
                java.util.Set.of("load_skill", "readSkillFile", "bidding_read_source", "bidding_read_sources"),
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
                    claim.skill().digest(), claim.configDigest(), claim.skill().files().get("output.schema.json"));
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
        return readResult(stream, expectedSkillDigest, expectedConfigDigest, "{\"type\":\"object\"}");
    }

    public static BiddingTypes.Execution readResult(Flux<AgentService.StreamDelta> stream,
            String expectedSkillDigest, String expectedConfigDigest, String pinnedOutputSchema) {
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
            if (state[1] instanceof Map<?, ?> failed) return failureFromEvent(failed, output.toString());
            return failure(hasCause(e, java.util.concurrent.TimeoutException.class) ? "EXECUTION_TIMEOUT" : "STREAM_INCOMPLETE",
                    "TRANSIENT", true, output.length() > 0, false);
        }
        if (state[1] instanceof Map<?, ?> failed) {
            return failureFromEvent(failed, output.toString());
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
            if (pinnedOutputSchema == null || pinnedOutputSchema.isBlank())
                return failure("OUTPUT_SCHEMA_MISSING", "VALIDATION", false, false, false);
            var schema = JSON.readTree(pinnedOutputSchema);
            JsonNode parsed = parseOutputJson(output.toString());
            if (parsed == null || !parsed.isObject() || !validSchema(schema)
                    || !validAgainstSchema(parsed, schema))
                return failure("OUTPUT_INVALID", "VALIDATION", false, false, false,
                        rejectedOutput(output.toString()));
            ObjectNode payload = (ObjectNode) parsed;
            return new BiddingTypes.Execution(payload, null, skillDigest, expectedConfigDigest, null);
        } catch (Exception e) {
            return failure("OUTPUT_INVALID", "VALIDATION", false, false, false,
                    rejectedOutput(output.toString()));
        }
    }

    /**
     * Accept the model's contract JSON as-is, with one conservative recovery
     * for prose-wrapped responses: exactly one {@code ```json} fenced block.
     * The surrounding prose is ignored, but multiple, unclosed, or non-json
     * fences remain invalid so an ambiguous answer can never pass validation.
     */
    private static JsonNode parseOutputJson(String raw) throws Exception {
        try {
            return readStrictJson(raw);
        } catch (Exception ignored) {
            // Fall through only when the complete answer is not JSON. A valid
            // scalar/array is still rejected by the existing object check.
        }

        int opening = raw.indexOf("```");
        if (opening < 0) return null;
        int closing = raw.indexOf("```", opening + 3);
        if (closing < 0 || raw.indexOf("```", closing + 3) >= 0) return null;

        int headerEnd = raw.indexOf('\n', opening + 3);
        if (headerEnd < 0 || !"json".equals(raw.substring(opening + 3, headerEnd).trim())) return null;
        String fencedJson = raw.substring(headerEnd + 1, closing).trim();
        JsonNode parsed = readStrictJson(fencedJson);
        return parsed != null && parsed.isObject() ? parsed : null;
    }

    private static JsonNode readStrictJson(String value) throws Exception {
        try (var parser = JSON.getFactory().createParser(value)) {
            JsonNode parsed = JSON.readTree(parser);
            if (parser.nextToken() != null) throw new IllegalArgumentException("Trailing JSON content");
            return parsed;
        }
    }

    private static boolean validAgainstSchema(JsonNode value, JsonNode schema) {
        JsonNode type = schema.get("type");
        if (type != null && !matchesType(value, type)) return false;
        JsonNode required = schema.get("required");
        if (required != null) {
            if (!required.isArray() || !value.isObject()) return false;
            for (JsonNode field : required) if (!field.isTextual() || !value.has(field.asText())) return false;
        }
        JsonNode properties = schema.get("properties");
        if (properties != null) {
            if (!properties.isObject() || !value.isObject()) return false;
            var fields = properties.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (value.has(field.getKey()) && !validAgainstSchema(value.get(field.getKey()), field.getValue())) return false;
            }
        }
        JsonNode additional = schema.get("additionalProperties");
        if (additional != null && !additional.isBoolean()) return false;
        if (Boolean.FALSE.equals(additional == null ? null : additional.booleanValue()) && value.isObject()) {
            var names = value.fieldNames();
            while (names.hasNext()) if (properties == null || !properties.has(names.next())) return false;
        }
        JsonNode items = schema.get("items");
        if (items != null && value.isArray()) for (JsonNode item : value)
            if (!validAgainstSchema(item, items)) return false;
        JsonNode choices = schema.get("enum");
        if (choices != null && (!choices.isArray() || !contains(choices, value))) return false;
        JsonNode constant = schema.get("const");
        if (constant != null && !constant.equals(value)) return false;
        if (value.isTextual()) {
            int length = value.textValue().codePointCount(0, value.textValue().length());
            if (schema.has("minLength") && length < schema.path("minLength").asInt()) return false;
            if (schema.has("maxLength") && length > schema.path("maxLength").asInt()) return false;
            if (schema.has("pattern") && !java.util.regex.Pattern.compile(schema.path("pattern").asText())
                    .matcher(value.asText()).find()) return false;
        }
        if (value.isNumber()) {
            if (schema.has("minimum") && value.decimalValue().compareTo(schema.path("minimum").decimalValue()) < 0) return false;
            if (schema.has("maximum") && value.decimalValue().compareTo(schema.path("maximum").decimalValue()) > 0) return false;
        }
        if (value.isArray()) {
            if (schema.has("minItems") && value.size() < schema.path("minItems").asInt()) return false;
            if (schema.has("maxItems") && value.size() > schema.path("maxItems").asInt()) return false;
        }
        return true;
    }

    private static boolean validSchema(JsonNode schema) {
        if (schema == null || !schema.isObject()) return false;
        var keys = schema.fieldNames();
        while (keys.hasNext()) if (!SCHEMA_KEYS.contains(keys.next())) return false;
        JsonNode type = schema.get("type");
        if (type != null) {
            if (type.isTextual()) {
                if (!isSchemaType(type.asText())) return false;
            } else if (type.isArray() && !type.isEmpty()) {
                for (JsonNode candidate : type) if (!candidate.isTextual() || !isSchemaType(candidate.asText())) return false;
            } else return false;
        }
        JsonNode required = schema.get("required");
        if (required != null) {
            if (!required.isArray()) return false;
            for (JsonNode field : required) if (!field.isTextual()) return false;
        }
        JsonNode properties = schema.get("properties");
        if (properties != null) {
            if (!properties.isObject()) return false;
            var fields = properties.elements();
            while (fields.hasNext()) if (!validSchema(fields.next())) return false;
        }
        JsonNode additional = schema.get("additionalProperties");
        if (additional != null && !additional.isBoolean()) return false;
        JsonNode items = schema.get("items");
        if (items != null && !validSchema(items)) return false;
        JsonNode choices = schema.get("enum");
        if (choices != null && !choices.isArray()) return false;
        for (String key : java.util.List.of("minLength", "maxLength", "minItems", "maxItems"))
            if (schema.has(key) && (!schema.path(key).isIntegralNumber() || schema.path(key).asInt() < 0)) return false;
        for (String key : java.util.List.of("minimum", "maximum"))
            if (schema.has(key) && !schema.path(key).isNumber()) return false;
        if (schema.has("pattern")) {
            if (!schema.path("pattern").isTextual()) return false;
            try { java.util.regex.Pattern.compile(schema.path("pattern").asText()); }
            catch (java.util.regex.PatternSyntaxException invalid) { return false; }
        }
        return true;
    }

    private static boolean isSchemaType(String type) {
        return java.util.Set.of("object", "array", "string", "number", "integer", "boolean", "null").contains(type);
    }

    private static boolean matchesType(JsonNode value, JsonNode type) {
        if (type.isArray()) { for (JsonNode candidate : type) if (matchesType(value, candidate)) return true; return false; }
        if (!type.isTextual()) return false;
        return switch (type.asText()) {
            case "object" -> value.isObject(); case "array" -> value.isArray(); case "string" -> value.isTextual();
            case "number" -> value.isNumber(); case "integer" -> value.isIntegralNumber();
            case "boolean" -> value.isBoolean(); case "null" -> value.isNull(); default -> false;
        };
    }

    private static boolean contains(JsonNode array, JsonNode value) {
        for (JsonNode candidate : array) if (candidate.equals(value)) return true;
        return false;
    }

    private static BiddingTypes.Execution failure(String code, String category, boolean unknown,
            boolean partial, boolean stopped) {
        return failure(code, category, unknown, partial, stopped, null);
    }

    private static BiddingTypes.Execution failure(String code, String category, boolean unknown,
            boolean partial, boolean stopped, String rejectedOutput) {
        return new BiddingTypes.Execution(null, new BiddingTypes.Failure(code, category, null, unknown, partial, stopped),
                null, null, rejectedOutput);
    }

    private static BiddingTypes.Execution failureFromEvent(Map<?, ?> failed, String output) {
        String code = String.valueOf(failed.containsKey("code") ? failed.get("code") : "STREAM_INCOMPLETE");
        return failure(code,
                String.valueOf(failed.containsKey("category") ? failed.get("category") : "TRANSIENT"),
                Boolean.TRUE.equals(failed.get("resultUnknown")), Boolean.TRUE.equals(failed.get("partial")),
                Boolean.TRUE.equals(failed.get("stopped")),
                "OUTPUT_INVALID".equals(code) ? rejectedOutput(output) : null);
    }

    private static String rejectedOutput(String output) {
        if (output == null || output.isEmpty()) return null;
        return output.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= MAX_OUTPUT_BYTES ? output : null;
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

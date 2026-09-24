package vip.mate.bidding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.agent.AgentService;
import vip.mate.agent.binding.service.AgentBindingService;
import vip.mate.agent.model.AgentEntity;
import vip.mate.skill.runtime.SkillRuntimeService;
import vip.mate.skill.runtime.model.ResolvedSkill;

@Service
@Lazy
public class BiddingEmployeeBindings {
    private static final List<String> ROLES = List.of("analyst", "writer", "reviewer");
    private final AgentService agents;
    private final AgentBindingService agentBindings;
    private final SkillRuntimeService skills;
    private final BiddingSkillPackages packages;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final BiddingAccess access;

    public BiddingEmployeeBindings(AgentService agents, AgentBindingService agentBindings,
            SkillRuntimeService skills, BiddingSkillPackages packages,
            JdbcTemplate jdbc, ObjectMapper json, BiddingAccess access) {
        this.agents = agents; this.agentBindings = agentBindings; this.skills = skills;
        this.packages = packages; this.jdbc = jdbc; this.json = json; this.access = access;
    }

    @Transactional
    public ObjectNode assign(BiddingTypes.Scope scope, BiddingTypes.Command command) {
        if (command == null || !"ASSIGN_EMPLOYEES".equals(command.action()) || command.payload() == null
                || command.operationId() == null || command.operationId().isBlank() || command.operationId().length() > 128
                || command.expected() == null)
            throw BiddingAccess.error(400, "INVALID_REQUEST", "岗位绑定请求无效");
        access.requireApprover(scope);
        jdbc.query("SELECT id FROM mate_bidding_project WHERE workspace_id=? AND id=? FOR UPDATE", (rs, n) -> rs.getString(1), scope.workspaceId(), scope.projectId())
                .stream().findFirst().orElseThrow(() -> BiddingAccess.error(404, "NOT_FOUND", "项目不存在"));
        String requestDigest = digest(Map.of("action", command.action(), "expected", command.expected(), "payload", command.payload()));
        var oldOperation = jdbc.query("SELECT request_digest,result_json FROM mate_bidding_operation WHERE workspace_id=? AND actor_id=? AND operation_id=?",
                (rs, n) -> Map.entry(rs.getString(1), rs.getString(2)), scope.workspaceId(), scope.actorId(), command.operationId()).stream().findFirst().orElse(null);
        if (oldOperation != null) {
            if (!oldOperation.getKey().equals(requestDigest)) throw BiddingAccess.error(409, "OPERATION_CONFLICT", "operationId 已用于不同请求");
            try { return (ObjectNode) json.readTree(oldOperation.getValue()); }
            catch (Exception e) { throw new IllegalStateException("Invalid stored employee assignment operation", e); }
        }
        ObjectNode project = project(scope);
        BiddingTypes.Ref expected = command.expected();
        if (!matches(project, expected)) throw BiddingAccess.error(409, "VERSION_CONFLICT", "项目已更新，请刷新后重试");
        ObjectNode payload = command.payload();
        Map<String, Long> selected = new LinkedHashMap<>();
        for (String role : ROLES) {
            String field = role + "AgentId";
            long id = identifier(payload.path(field).asText(null), "INVALID_EMPLOYEE");
            AgentEntity employee = requireEmployee(id, BiddingAccess.parse(scope.workspaceId(), "WORKSPACE_REQUIRED"));
            requireModel(id, employee);
            List<ResolvedSkill> authorized = authorizedActiveSkills(id, BiddingAccess.parse(scope.workspaceId(), "WORKSPACE_REQUIRED"));
            if (authorized.isEmpty()) throw BiddingAccess.error(422, "EMPLOYEE_SKILL_UNAVAILABLE", "数字员工缺少已授权且可用的技能");
            selected.put(role, id);
        }
        if (selected.get("writer").equals(selected.get("reviewer")))
            throw BiddingAccess.error(422, "REVIEWER_MUST_DIFFER", "审核员工必须与编写员工不同");

        ObjectNode assigned = json.createObjectNode();
        for (String role : ROLES) {
            long id = selected.get(role);
            ObjectNode binding = assigned.putObject(role);
            binding.put("agentId", Long.toString(id));
            ArrayNode pins = binding.putArray("skillPins");
            for (ResolvedSkill skill : authorizedActiveSkills(id, Long.parseLong(scope.workspaceId()))) {
                BiddingTypes.SkillPin pin = packages.pin(scope, Long.toString(id), Long.toString(skill.getId()));
                ObjectNode ref = pins.addObject(); ref.put("skillId", pin.skillId()); ref.put("digest", pin.digest());
            }
            binding.put("configDigest", configDigest(scope, Long.toString(id)));
            binding.put("modelConfigId", modelConfigId(scope, Long.toString(id)));
        }
        project.set("bindings", assigned);
        long nextVersion = project.path("version").asLong() + 1;
        project.put("version", nextVersion);
        ObjectNode ref = project.putObject("ref");
        ref.put("kind", "project"); ref.put("id", project.path("id").asText()); ref.put("version", nextVersion);
        ref.put("digest", digest(Map.of("workspaceId", project.path("workspaceId").asText(), "name", project.path("name").asText(),
                "lotName", project.path("lotName").asText(), "ownerId", project.path("ownerId").asText(),
                "version", nextVersion, "stage", project.path("stage").asText())));
        String oldJson = readProjectJson(scope);
        int changed = jdbc.update("UPDATE mate_bidding_project SET body_json=?,version=?,updated_at=? WHERE workspace_id=? AND id=? AND version=? AND body_json=?",
                write(project), nextVersion, Timestamp.from(Instant.now()), scope.workspaceId(), scope.projectId(), nextVersion - 1, oldJson);
        if (changed != 1) throw BiddingAccess.error(409, "VERSION_CONFLICT", "项目已更新，请刷新后重试");
        ObjectNode result = json.createObjectNode(); result.set("ref", project.path("ref")); result.set("result", project);
        jdbc.update("INSERT INTO mate_bidding_operation(workspace_id,actor_id,operation_id,request_digest,result_json,created_at) VALUES(?,?,?,?,?,?)",
                scope.workspaceId(), scope.actorId(), command.operationId(), requestDigest, write(result), Timestamp.from(Instant.now()));
        return result;
    }

    public String resolve(BiddingTypes.Scope scope, String role) {
        if (!ROLES.contains(role)) throw BiddingAccess.error(400, "INVALID_ROLE", "未知数字员工岗位");
        return project(scope).path("bindings").path(role).path("agentId").asText(null);
    }

    public String modelConfigId(BiddingTypes.Scope scope, String agentId) {
        AgentEntity employee = requireEmployee(identifier(agentId, "INVALID_EMPLOYEE"), BiddingAccess.parse(scope.workspaceId(), "WORKSPACE_REQUIRED"));
        Long modelId = resolveModelId(employee);
        return modelId == null ? null : Long.toString(modelId);
    }

    public String configDigest(BiddingTypes.Scope scope, String agentId) {
        long id = identifier(agentId, "INVALID_EMPLOYEE");
        AgentEntity employee = requireEmployee(id, BiddingAccess.parse(scope.workspaceId(), "WORKSPACE_REQUIRED"));
        ModelRow model = modelRow(employee);
        Set<String> tools = agentBindings.getEffectiveToolNames(id);
        Set<Long> boundSkills = agentBindings.getBoundSkillIds(id);
        List<String> skillPins = packageDigests(scope, id, boundSkills);
        Map<String, Object> config = new TreeMap<>();
        config.put("modelConfigId", model == null ? null : model.id());
        config.put("modelUpdatedAt", model == null ? null : model.updatedAt());
        config.put("modelName", model == null ? null : model.modelName());
        config.put("runtimeType", employee.getRuntimeType());
        config.put("tools", tools == null ? null : tools.stream().sorted().toList());
        config.put("skillIds", boundSkills == null ? null : boundSkills.stream().sorted().toList());
        config.put("skillPinDigests", skillPins);
        return digest(config);
    }

    public void validate(BiddingTypes.Scope scope, String agentId, String expectedDigest) {
        long id = identifier(agentId, "INVALID_EMPLOYEE");
        AgentEntity agent = requireEmployee(id, BiddingAccess.parse(scope.workspaceId(), "WORKSPACE_REQUIRED"));
        requireModel(id, agent);
        if (expectedDigest == null || !expectedDigest.equals(configDigest(scope, agentId)))
            throw BiddingAccess.error(409, "EMPLOYEE_CONFIG_CHANGED", "数字员工配置已变化，请重新绑定");
    }

    public ArrayNode employees(BiddingTypes.Scope scope) {
        long workspace = BiddingAccess.parse(scope.workspaceId(), "WORKSPACE_REQUIRED");
        ArrayNode result = json.createArrayNode();
        for (AgentEntity agent : agents.listAgentsByWorkspace(workspace, null)) {
            ObjectNode row = result.addObject(); row.put("id", Long.toString(agent.getId())); row.put("name", agent.getName());
            ArrayNode reasons = row.putArray("unavailableReasons");
            try {
                requireEmployee(agent.getId(), workspace);
                requireModel(agent.getId(), agent);
                if (authorizedActiveSkills(agent.getId(), workspace).isEmpty()) reasons.add("缺少已授权且可用的技能");
            } catch (BiddingApiException e) { reasons.add(e.getMessage()); }
            row.put("available", reasons.isEmpty());
        }
        return result;
    }

    private AgentEntity requireEmployee(long id, long workspace) {
        AgentEntity agent;
        try { agent = agents.getAgent(id); } catch (RuntimeException e) { throw BiddingAccess.error(422, "EMPLOYEE_UNAVAILABLE", "数字员工不存在"); }
        if (!Long.valueOf(workspace).equals(agent.getWorkspaceId()) || !Boolean.TRUE.equals(agent.getEnabled())
                || (agent.getDeleted() != null && agent.getDeleted() != 0)
                || !"native".equalsIgnoreCase(agent.getRuntimeType()) || "plan_execute".equals(agent.getAgentType()))
            throw BiddingAccess.error(422, "EMPLOYEE_UNAVAILABLE", "数字员工必须为当前工作空间启用的 native react 员工");
        return agent;
    }

    private List<ResolvedSkill> authorizedActiveSkills(long agentId, long workspace) {
        Set<Long> allowed = agentBindings.getBoundSkillIds(agentId);
        return skills.getActiveSkills(workspace).stream()
                .filter(skill -> skill.getId() != null && (allowed == null || allowed.contains(skill.getId())))
                .filter(skill -> {
                    ResolvedSkill current = skills.findActiveSkill(skill.getName(), workspace);
                    return current != null && current.getId() != null && current.getId().equals(skill.getId()) && SkillRuntimeService.passesActiveGate(current);
                }).toList();
    }

    private void requireModel(long agentId, AgentEntity employee) {
        if (modelRow(employee) == null) throw BiddingAccess.error(422, "MODEL_CONFIG_MISSING", "数字员工未配置可用的对话模型");
    }

    private Long resolveModelId(AgentEntity employee) { ModelRow row = modelRow(employee); return row == null ? null : row.id(); }
    private ModelRow modelRow(AgentEntity employee) {
        String modelName = employee.getModelName();
        if (modelName == null || modelName.isBlank()) {
            return jdbc.query("SELECT id,model_name,update_time FROM mate_model_config WHERE is_default=TRUE AND enabled=TRUE AND deleted=0 AND (model_type IS NULL OR model_type='chat') ORDER BY id LIMIT 1",
                    (rs, n) -> new ModelRow(rs.getLong(1), rs.getString(2), rs.getTimestamp(3) == null ? null : rs.getTimestamp(3).toInstant().toString())).stream().findFirst().orElse(null);
        }
        return jdbc.query("SELECT id,model_name,update_time FROM mate_model_config WHERE model_name=? AND enabled=TRUE AND deleted=0 AND (model_type IS NULL OR model_type='chat') ORDER BY id LIMIT 1",
                (rs, n) -> new ModelRow(rs.getLong(1), rs.getString(2), rs.getTimestamp(3) == null ? null : rs.getTimestamp(3).toInstant().toString()), modelName).stream().findFirst().orElse(null);
    }

    private List<String> packageDigests(BiddingTypes.Scope scope, long agentId, Set<Long> allowedSkills) {
        List<ResolvedSkill> active = authorizedActiveSkills(agentId, Long.parseLong(scope.workspaceId())).stream()
                .filter(skill -> allowedSkills == null || allowedSkills.contains(skill.getId())).toList();
        List<String> ids = active.stream().map(s -> Long.toString(s.getId())).toList();
        if (ids.isEmpty()) return List.of();
        String marks = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        List<String> found = jdbc.query("SELECT digest FROM mate_bidding_skill_package WHERE workspace_id=? AND project_id=? AND skill_id IN (" + marks + ") ORDER BY skill_id,created_at,digest",
                (rs, n) -> rs.getString(1), concat(scope.workspaceId(), scope.projectId(), ids).toArray());
        return List.copyOf(found);
    }

    private List<Object> concat(String workspace, String project, List<String> ids) {
        List<Object> args = new ArrayList<>(); args.add(workspace); args.add(project); args.addAll(ids); return args;
    }

    private ObjectNode project(BiddingTypes.Scope scope) {
        String raw = readProjectJson(scope);
        try { return (ObjectNode) json.readTree(raw); } catch (Exception e) { throw new IllegalStateException("Invalid bidding project", e); }
    }
    private String readProjectJson(BiddingTypes.Scope scope) {
        return jdbc.query("SELECT body_json FROM mate_bidding_project WHERE workspace_id=? AND id=?", (rs, n) -> rs.getString(1), scope.workspaceId(), scope.projectId())
                .stream().findFirst().orElseThrow(() -> BiddingAccess.error(404, "NOT_FOUND", "项目不存在"));
    }
    private boolean matches(ObjectNode current, BiddingTypes.Ref expected) {
        return expected != null && "project".equals(expected.kind()) && current.path("id").asText().equals(expected.id())
                && current.path("version").asLong() == expected.version() && current.path("ref").path("digest").asText().equals(expected.digest());
    }
    private String write(Object value) { try { return json.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException(e); } }
    private static long identifier(String value, String code) {
        try { long result = Long.parseLong(value); if (result > 0) return result; } catch (Exception ignored) { }
        throw BiddingAccess.error(400, code, "无效的数字员工标识");
    }
    private String digest(Object value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(canonical(json.valueToTree(value))))); }
        catch (Exception e) { throw new IllegalStateException("Unable to digest employee configuration", e); }
    }
    private JsonNode canonical(JsonNode node) {
        if (node.isObject()) {
            ObjectNode sorted = json.createObjectNode();
            java.util.TreeSet<String> names = new java.util.TreeSet<>(); node.fieldNames().forEachRemaining(names::add);
            for (String name : names) sorted.set(name, canonical(node.get(name)));
            return sorted;
        }
        if (node.isArray()) { var array = json.createArrayNode(); node.forEach(item -> array.add(canonical(item))); return array; }
        return node;
    }
    private record ModelRow(long id, String modelName, String updatedAt) { }
}

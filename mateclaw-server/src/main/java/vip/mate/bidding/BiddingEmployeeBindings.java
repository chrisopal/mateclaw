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
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.model.ModelProviderEntity;
import vip.mate.llm.routing.ProviderRouter;
import vip.mate.llm.service.ModelConfigService;
import vip.mate.llm.service.ModelProviderService;
import vip.mate.skill.runtime.SkillRuntimeService;
import vip.mate.skill.runtime.model.ResolvedSkill;

@Service
@Lazy
public class BiddingEmployeeBindings {
    private static final List<String> ROLES = List.of("analyst", "writer", "reviewer");
    private static final Map<String, List<String>> REQUIRED_SKILLS = Map.of(
            "analyst", List.of("bidding-tender-profile", "bidding-elimination-analysis", "bidding-requirement-analysis", "bidding-scoring-analysis"),
            "writer", List.of("bidding-outline-planning", "bidding-technical-writing", "bidding-document-export"),
            "reviewer", List.of("bidding-technical-review"));
    private final AgentService agents;
    private final AgentBindingService agentBindings;
    private final SkillRuntimeService skills;
    private final BiddingSkillPackages packages;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final BiddingAccess access;
    private final ModelConfigService modelConfigs;
    private final ModelProviderService modelProviders;
    private final ProviderRouter providerRouter;

    public BiddingEmployeeBindings(AgentService agents, AgentBindingService agentBindings,
            SkillRuntimeService skills, BiddingSkillPackages packages,
            JdbcTemplate jdbc, ObjectMapper json, BiddingAccess access,
            ModelConfigService modelConfigs, ModelProviderService modelProviders, ProviderRouter providerRouter) {
        this.agents = agents; this.agentBindings = agentBindings; this.skills = skills;
        this.packages = packages; this.jdbc = jdbc; this.json = json; this.access = access;
        this.modelConfigs = modelConfigs; this.modelProviders = modelProviders; this.providerRouter = providerRouter;
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
        if ("ARCHIVED".equals(project.path("stage").asText()))
            throw BiddingAccess.error(409, "PROJECT_ARCHIVED", "已归档项目不能新增岗位绑定");
        BiddingTypes.Ref expected = command.expected();
        if (!matches(project, expected)) throw BiddingAccess.error(409, "VERSION_CONFLICT", "项目已更新，请刷新后重试");
        ObjectNode payload = command.payload();
        Map<String, Long> selected = new LinkedHashMap<>();
        for (String role : ROLES) {
            String field = role + "AgentId";
            if (!payload.has(field)) throw BiddingAccess.error(400, "INVALID_REQUEST", "岗位绑定请求缺少" + field);
            JsonNode requested = payload.get(field);
            if (requested == null || requested.isNull()) { selected.put(role, null); continue; }
            long id = identifier(requested.asText(null), "INVALID_EMPLOYEE");
            AgentEntity employee = requireEmployee(id, BiddingAccess.parse(scope.workspaceId(), "WORKSPACE_REQUIRED"));
            requireModel(id, employee);
            requiredRoleSkills(role, id, BiddingAccess.parse(scope.workspaceId(), "WORKSPACE_REQUIRED"));
            selected.put(role, id);
        }
        if (selected.get("writer") != null && selected.get("writer").equals(selected.get("reviewer")))
            throw BiddingAccess.error(422, "REVIEWER_MUST_DIFFER", "审核员工必须与编写员工不同");

        ObjectNode assigned = json.createObjectNode();
        for (String role : ROLES) {
            ObjectNode binding = assigned.putObject(role);
            ArrayNode pins = binding.putArray("skillPins");
            Long assignedId = selected.get(role);
            if (assignedId == null) {
                binding.putNull("agentId"); binding.putNull("configDigest"); binding.putNull("modelConfigId");
                continue;
            }
            long id = assignedId;
            binding.put("agentId", Long.toString(id));
            for (ResolvedSkill skill : requiredRoleSkills(role, id, Long.parseLong(scope.workspaceId()))) {
                BiddingTypes.SkillPin pin = packages.pin(scope, Long.toString(id), Long.toString(skill.getId()));
                ObjectNode ref = pins.addObject(); ref.put("skillId", pin.skillId()); ref.put("digest", pin.digest());
            }
        }
        // A single employee may occupy multiple roles. Pin every role's required packages
        // before snapshotting any employee digest, since the digest covers project pins.
        for (String role : ROLES) {
            Long assignedId = selected.get(role);
            if (assignedId == null) continue;
            ObjectNode binding = (ObjectNode) assigned.path(role);
            binding.put("configDigest", configDigest(scope, Long.toString(assignedId)));
            binding.put("modelConfigId", modelConfigId(scope, Long.toString(assignedId)));
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
        ModelRow model = modelRow(employee);
        return model == null ? null : Long.toString(model.id());
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
        config.put("modelProvider", model == null ? null : model.providerId());
        config.put("providerUpdatedAt", model == null ? null : model.providerUpdatedAt());
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
            ArrayNode visibleSkills = row.putArray("skills");
            List<ResolvedSkill> employeeSkills = availableGrantedSkills(agent.getId(), workspace);
            for (ResolvedSkill skill : employeeSkills) {
                ObjectNode item = visibleSkills.addObject(); item.put("id", Long.toString(skill.getId())); item.put("name", skill.getName());
            }
            ObjectNode roles = row.putObject("roles");
            Set<String> allReasons = new java.util.LinkedHashSet<>();
            boolean employeeReady = true;
            try {
                requireEmployee(agent.getId(), workspace);
                if (modelRow(agent) == null) throw BiddingAccess.error(422, "MODEL_CONFIG_MISSING", "未配置可用的对话模型");
            } catch (BiddingApiException e) { employeeReady = false; allReasons.add(e.getMessage()); }
            boolean anyRoleReady = false;
            for (String role : ROLES) {
                ObjectNode status = roles.putObject(role); ArrayNode missing = status.putArray("missingSkills");
                if (!employeeReady) { status.put("available", false); status.put("reason", String.join("；", allReasons)); continue; }
                for (String requiredName : REQUIRED_SKILLS.get(role)) {
                    ResolvedSkill skill = skills.findActiveSkill(requiredName, workspace);
                    if (skill == null || skill.getId() == null || !SkillRuntimeService.passesActiveGate(skill)
                            || !isGranted(agent.getId(), skill.getId())) {
                        missing.add(requiredName); allReasons.add("缺少技能 " + requiredName);
                    }
                }
                boolean ready = missing.isEmpty(); status.put("available", ready);
                if (!ready) status.put("reason", "岗位必需技能未就绪");
                else anyRoleReady = true;
            }
            row.put("available", employeeReady && anyRoleReady);
            ArrayNode reasons = row.putArray("unavailableReasons"); allReasons.forEach(reasons::add);
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

    private List<ResolvedSkill> requiredRoleSkills(String role, long agentId, long workspace) {
        List<ResolvedSkill> found = new ArrayList<>();
        for (String name : REQUIRED_SKILLS.get(role)) {
            ResolvedSkill skill = skills.findActiveSkill(name, workspace);
            if (skill == null || skill.getId() == null || !SkillRuntimeService.passesActiveGate(skill) || !isGranted(agentId, skill.getId()))
                throw BiddingAccess.error(422, "EMPLOYEE_SKILL_UNAVAILABLE", "岗位缺少已授权且可用的必需技能: " + name);
            found.add(skill);
        }
        return List.copyOf(found);
    }

    private boolean isGranted(long agentId, long skillId) { Set<Long> allowed = agentBindings.getBoundSkillIds(agentId); return allowed == null || allowed.contains(skillId); }
    private List<ResolvedSkill> availableGrantedSkills(long agentId, long workspace) {
        Set<Long> allowed = agentBindings.getBoundSkillIds(agentId);
        return skills.getActiveSkills(workspace).stream().filter(s -> s.getId() != null && (allowed == null || allowed.contains(s.getId())))
                .filter(s -> SkillRuntimeService.passesActiveGate(s)).toList();
    }

    private void requireModel(long agentId, AgentEntity employee) {
        if (modelRow(employee) == null) throw BiddingAccess.error(422, "MODEL_CONFIG_MISSING", "数字员工未配置可用的对话模型");
    }

    private ModelRow modelRow(AgentEntity employee) {
        ModelConfigEntity resolved;
        try { resolved = modelConfigs.resolveModel(employee.getModelName()); }
        catch (RuntimeException unavailable) { return null; }
        if (resolved == null) return null;
        boolean explicitAgentModel = employee.getModelName() != null && employee.getModelName().equalsIgnoreCase(resolved.getModelName());
        ModelConfigEntity selected = resolved;
        if (!explicitAgentModel) {
            try { ModelConfigEntity routed = providerRouter.selectPrimary(employee.getId(), resolved); if (routed != null) selected = routed; }
            catch (RuntimeException ignored) { selected = resolved; }
        }
        try {
            if (!modelProviders.isProviderConfigured(selected.getProvider())) {
                selected = modelConfigs.listByType("chat").stream()
                        .filter(m -> Boolean.TRUE.equals(m.getEnabled()))
                        .filter(m -> { try { return modelProviders.isProviderConfigured(m.getProvider()); } catch (RuntimeException e) { return false; } })
                        .findFirst().orElse(null);
            }
        } catch (RuntimeException unavailable) { return null; }
        if (selected == null) return null;
        ModelProviderEntity provider;
        try { provider = modelProviders.getProviderConfig(selected.getProvider()); }
        catch (RuntimeException unavailable) { return null; }
        return new ModelRow(selected.getId(), selected.getModelName(), selected.getUpdateTime() == null ? null : selected.getUpdateTime().toString(),
                selected.getProvider(), provider.getUpdateTime() == null ? null : provider.getUpdateTime().toString());
    }

    private List<String> packageDigests(BiddingTypes.Scope scope, long agentId, Set<Long> allowedSkills) {
        List<ResolvedSkill> active = availableGrantedSkills(agentId, Long.parseLong(scope.workspaceId())).stream()
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
    private record ModelRow(long id, String modelName, String updatedAt, String providerId, String providerUpdatedAt) { }
}

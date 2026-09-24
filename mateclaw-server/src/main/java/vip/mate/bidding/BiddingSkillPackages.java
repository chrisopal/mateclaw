package vip.mate.bidding;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import vip.mate.agent.AgentService;
import vip.mate.agent.binding.service.AgentBindingService;
import vip.mate.agent.model.AgentEntity;
import vip.mate.skill.runtime.SkillRuntimeService;
import vip.mate.skill.runtime.model.ResolvedSkill;

@Service
@Lazy
public class BiddingSkillPackages {
    private static final long MAX_PACKAGE_BYTES = 2L * 1024 * 1024;
    private final AgentService agents;
    private final AgentBindingService bindings;
    private final SkillRuntimeService skills;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public BiddingSkillPackages(AgentService agents, AgentBindingService bindings,
            SkillRuntimeService skills, JdbcTemplate jdbc, ObjectMapper json) {
        this.agents = agents;
        this.bindings = bindings;
        this.skills = skills;
        this.jdbc = jdbc;
        this.json = json;
    }

    public BiddingTypes.SkillPin pin(BiddingTypes.Scope scope, String agentId, String skillId) {
        long workspace = BiddingAccess.parse(scope.workspaceId(), "WORKSPACE_REQUIRED");
        long agent = identifier(agentId, "INVALID_AGENT");
        long skill = identifier(skillId, "INVALID_SKILL");
        employee(agent, workspace);
        Set<Long> allowed = bindings.getBoundSkillIds(agent);
        if (allowed != null && !allowed.contains(skill)) throw unavailable("技能未授予该数字员工");
        ResolvedSkill active = skills.findActiveSkillById(skill, workspace);
        if (active == null) throw unavailable("技能不可用或不在当前工作空间");
        ResolvedSkill current = skills.findActiveSkill(active.getName(), workspace);
        if (current == null || current.getId() == null || current.getId() != skill || !SkillRuntimeService.passesActiveGate(current))
            throw unavailable("技能不可用或不在当前工作空间");
        Map<String, String> files = readActivePackage(current);
        String digest = digest(files);
        String version = current.getCreateTime() == null ? "sha256:" + digest : current.getCreateTime().toString();
        String stored = jsonWrite(files);
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM mate_bidding_skill_package WHERE workspace_id=? AND project_id=? AND skill_id=? AND digest=?",
                Integer.class, scope.workspaceId(), scope.projectId(), Long.toString(skill), digest);
        if (count == null || count == 0) {
            try {
                jdbc.update("INSERT INTO mate_bidding_skill_package(id,workspace_id,project_id,skill_id,version,digest,files_json,created_at) VALUES(?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",
                        UUID.randomUUID().toString(), scope.workspaceId(), scope.projectId(), Long.toString(skill), version, digest, stored);
            } catch (DuplicateKeyException conflict) {
                // A concurrent identical pin is already immutable and is read back below.
            }
        }
        return new BiddingTypes.SkillPin(Long.toString(skill), version, digest, Map.copyOf(files));
    }

    public String read(BiddingTypes.Scope scope, String digest, String relativePath) {
        String path = safeRelativePath(relativePath);
        if (digest == null || !digest.matches("[a-f0-9]{64}")) throw denied();
        var rows = jdbc.query("SELECT files_json FROM mate_bidding_skill_package WHERE workspace_id=? AND project_id=? AND digest=?",
                (rs, rowNum) -> rs.getString(1), scope.workspaceId(), scope.projectId(), digest);
        if (rows.isEmpty()) throw denied();
        try {
            Map<String, String> files = json.readValue(rows.getFirst(), new TypeReference<>() {});
            String content = files.get(path);
            if (content == null) throw denied();
            return content;
        } catch (IOException e) {
            throw new IllegalStateException("Invalid stored bidding skill package", e);
        }
    }

    public static String digest(Map<String, String> files) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (var entry : new TreeMap<>(files).entrySet()) {
                byte[] path = entry.getKey().replace('\\', '/').getBytes(StandardCharsets.UTF_8);
                byte[] content = entry.getValue().getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(path.length).array());
                digest.update(path);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(content.length).array());
                digest.update(content);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            throw new IllegalStateException("Unable to digest skill package", e);
        }
    }

    static String safeRelativePath(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) throw denied();
        try {
            Path supplied = Path.of(relativePath);
            Path normalized = supplied.normalize();
            if (supplied.isAbsolute() || normalized.startsWith("..") || normalized.toString().isBlank()
                    || !supplied.equals(normalized)) throw denied();
            return normalized.toString().replace('\\', '/');
        } catch (RuntimeException e) {
            if (e instanceof BiddingApiException api) throw api;
            throw denied();
        }
    }

    private Map<String, String> readActivePackage(ResolvedSkill skill) {
        Map<String, String> files = new LinkedHashMap<>();
        Path root = skill.getSkillDir();
        if (root == null) {
            String content = skill.getContent();
            if (content == null) throw unavailable("技能包缺少 SKILL.md");
            files.put("SKILL.md", content);
        } else {
            Path base = root.toAbsolutePath().normalize();
            if (!Files.isDirectory(base, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(base)) throw unavailable("技能目录不可读取");
            try {
                Files.walkFileTree(base, new SimpleFileVisitor<>() {
                    @Override public java.nio.file.FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        if (!dir.equals(base) && excluded(dir.getFileName().toString())) return java.nio.file.FileVisitResult.SKIP_SUBTREE;
                        if (Files.isSymbolicLink(dir)) return java.nio.file.FileVisitResult.SKIP_SUBTREE;
                        return java.nio.file.FileVisitResult.CONTINUE;
                    }
                    @Override public java.nio.file.FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        if (!attrs.isRegularFile() || Files.isSymbolicLink(file)) return java.nio.file.FileVisitResult.CONTINUE;
                        String relative = base.relativize(file).toString().replace('\\', '/');
                        if (included(relative)) {
                            byte[] bytes = Files.readAllBytes(file);
                            add(files, relative, new String(bytes, StandardCharsets.UTF_8));
                        }
                        return java.nio.file.FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                throw unavailable("技能包读取失败");
            }
        }
        if (!files.containsKey("SKILL.md")) throw unavailable("技能包缺少 SKILL.md");
        long size = files.entrySet().stream().mapToLong(e -> e.getKey().getBytes(StandardCharsets.UTF_8).length + e.getValue().getBytes(StandardCharsets.UTF_8).length).sum();
        if (size > MAX_PACKAGE_BYTES) throw new BiddingApiException(413, "SKILL_PACKAGE_LIMIT", "技能包超过2MiB上限");
        return Map.copyOf(new TreeMap<>(files));
    }

    private static void add(Map<String, String> files, String path, String content) {
        long size = path.getBytes(StandardCharsets.UTF_8).length + content.getBytes(StandardCharsets.UTF_8).length;
        long current = files.entrySet().stream().mapToLong(e -> e.getKey().getBytes(StandardCharsets.UTF_8).length + e.getValue().getBytes(StandardCharsets.UTF_8).length).sum();
        if (current + size > MAX_PACKAGE_BYTES) throw new BiddingApiException(413, "SKILL_PACKAGE_LIMIT", "技能包超过2MiB上限");
        files.put(path, content);
    }

    private static boolean included(String path) {
        if (excluded(path)) return false;
        return path.equals("SKILL.md") || path.equals("input.schema.json") || path.equals("output.schema.json")
                || path.startsWith("references/") || path.startsWith("examples/");
    }
    private static boolean excluded(String path) {
        return path.equals(".git") || path.equals(".cache") || path.equals("__pycache__") || path.equals("node_modules")
                || path.startsWith(".") && !path.equals(".well-known");
    }
    private AgentEntity employee(long id, long workspace) {
        AgentEntity agent;
        try { agent = agents.getAgent(id); } catch (RuntimeException e) { throw new BiddingApiException(422, "EMPLOYEE_UNAVAILABLE", "数字员工不可用"); }
        if (!Long.valueOf(workspace).equals(agent.getWorkspaceId()) || !Boolean.TRUE.equals(agent.getEnabled())
                || (agent.getDeleted() != null && agent.getDeleted() != 0)
                || !"native".equalsIgnoreCase(agent.getRuntimeType()) || "plan_execute".equals(agent.getAgentType()))
            throw new BiddingApiException(422, "EMPLOYEE_UNAVAILABLE", "数字员工必须为当前工作空间启用的 native react 员工");
        return agent;
    }
    private static long identifier(String value, String code) {
        try { long result = Long.parseLong(value); if (result > 0) return result; } catch (Exception ignored) { }
        throw new BiddingApiException(400, code, "无效的数字员工或技能标识");
    }
    private String jsonWrite(Object value) { try { return json.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException(e); } }
    private static BiddingApiException denied() { return new BiddingApiException(403, "SKILL_PATH_DENIED", "技能文件不可访问"); }
    private static BiddingApiException unavailable(String message) { return new BiddingApiException(422, "SKILL_UNAVAILABLE", message); }
}

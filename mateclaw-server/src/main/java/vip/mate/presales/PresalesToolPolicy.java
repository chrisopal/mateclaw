package vip.mate.presales;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import vip.mate.agent.context.ChatOrigin;

/**
 * Server-side allowlist and project-source boundary for presales tool calls.
 *
 * <p>The model prompt and the callback catalog are only disclosures. This policy
 * is evaluated immediately before the normal guard/approval path and therefore
 * remains authoritative when a model emits a deferred tool call or an approval
 * replay.</p>
 */
@Component
@ConditionalOnProperty(name = "mateclaw.presales.enabled", havingValue = "true")
public class PresalesToolPolicy {
  public static final Set<String> PROJECT_VISIBLE_TOOLS = Set.of(
      "web_search",
      "load_skill",
      "readSkillFile",
      "listSkillFiles",
      "listAvailableSkills",
      "project_document_read",
      "wiki_read_page",
      "wiki_list_pages",
      "wiki_search_pages",
      "wiki_semantic_search",
      "wiki_trace_source",
      "wiki_read_many",
      "wiki_related_pages",
      "wiki_explain_relation");

  private static final Pattern PRESALES_CONVERSATION =
      Pattern.compile("^presales:([^:]+):([^:]+):([^:]+)$");

  private final JdbcTemplate jdbc;
  private final ObjectMapper json;

  public PresalesToolPolicy(JdbcTemplate jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  public record Decision(boolean allowed, String reason) {
    public static Decision allow() {
      return new Decision(true, "");
    }

    public static Decision deny(String reason) {
      return new Decision(false, reason);
    }
  }

  /** Returns whether a callback may be advertised in a project-scoped run. */
  public static boolean isProjectVisibleTool(String toolName) {
    return toolName != null && PROJECT_VISIBLE_TOOLS.contains(toolName);
  }

  /**
   * Evaluate a tool call. Non-presales conversations are deliberately a no-op;
   * their existing callback, guard, and approval policies remain unchanged.
   */
  public Decision evaluate(String toolName, String arguments, ChatOrigin origin) {
    String conversationId = origin == null ? null : origin.conversationId();
    if (conversationId == null || !conversationId.startsWith("presales:")) {
      return Decision.allow();
    }
    if (!isProjectVisibleTool(toolName)) {
      return Decision.deny(
          "PRESALES_TOOL_NOT_ALLOWED: this project-scoped run only permits web search, bound skill loading, and read-only retrieval.");
    }
    if (origin == null || origin.agentId() == null) {
      return Decision.deny("PRESALES_PROJECT_SCOPE: bound employee identity is required.");
    }

    JsonNode args;
    try {
      args = json.readTree(arguments == null || arguments.isBlank() ? "{}" : arguments);
    } catch (Exception e) {
      return Decision.deny("PRESALES_TOOL_SCOPE: invalid tool arguments.");
    }
    ProjectScope project = projectScope(conversationId);
    if (project == null || !project.agentId().equals(origin.agentId().toString())) {
      return Decision.deny("PRESALES_PROJECT_SCOPE: project run is unavailable for this employee.");
    }
    if ("web_search".equals(toolName)
        || "load_skill".equals(toolName)
        || "readSkillFile".equals(toolName)
        || "listSkillFiles".equals(toolName)
        || "listAvailableSkills".equals(toolName)) {
      return Decision.allow();
    }
    String agentId = text(args, "agentId");
    if ("project_document_read".equals(toolName)) {
      if (origin != null && origin.agentId() != null
          && !project.agentId().equals(origin.agentId().toString())) {
        return Decision.deny(
            "PRESALES_PROJECT_SCOPE: document retrieval must use the bound presales employee.");
      }
    } else if (agentId.isBlank() || !agentId.equals(project.agentId())) {
      return Decision.deny(
          "PRESALES_PROJECT_SCOPE: Wiki retrieval must use the bound presales employee.");
    }
    String kbId = text(args, "kbIdParam");
    if (kbId.isBlank()) kbId = text(args, "kbId");

    if ("project_document_read".equals(toolName)) {
      String sourceRef = text(args, "sourceRef");
      if (sourceRef.isBlank()) {
        return Decision.deny("PRESALES_PROJECT_SCOPE: sourceRef is required.");
      }
      if (kbId.isBlank()) {
        return Decision.deny("PRESALES_PROJECT_SCOPE: project_document_read requires kbId.");
      }
      if (!project.kbIds().contains(kbId) || !project.sourceRefs().contains(sourceRef)) {
        return Decision.deny("PRESALES_PROJECT_SCOPE: document is not part of this project snapshot.");
      }
      return employeeCanReadKb(project, kbId)
          ? Decision.allow()
          : Decision.deny("PRESALES_PROJECT_SCOPE: knowledge base is not visible to the bound employee.");
    }

    if (kbId.isBlank()) {
      String kbName = text(args, "kbName");
      if (kbName.isBlank()) {
        return Decision.deny(
            "PRESALES_PROJECT_SCOPE: Wiki retrieval requires an explicit project-bound kbId or kbName.");
      }
      Set<String> matching = new LinkedHashSet<>();
      for (String boundId : project.kbIds()) {
        String name = jdbc.query(
                "SELECT name FROM mate_wiki_knowledge_base WHERE id=? AND workspace_id=? AND deleted=0",
                rs -> rs.next() ? rs.getString(1) : null,
                boundId,
                project.workspaceId());
        if (kbName.equals(name)) matching.add(boundId);
      }
      if (matching.size() != 1) {
        return Decision.deny(
            "PRESALES_PROJECT_SCOPE: kbName is not an unambiguous project-bound knowledge base.");
      }
      return Decision.allow();
    }
    if (!project.kbIds().contains(kbId)) {
      return Decision.deny(
          "PRESALES_PROJECT_SCOPE: the requested knowledge base is not bound to this project.");
    }
    return employeeCanReadKb(project, kbId)
        ? Decision.allow()
        : Decision.deny("PRESALES_PROJECT_SCOPE: knowledge base is unavailable to the bound employee.");
  }

  /** Mirrors WikiKnowledgeBaseService's workspace-plus-optional-agent-scope rule. */
  private boolean employeeCanReadKb(ProjectScope project, String kbId) {
    try {
      Integer visible = jdbc.queryForObject(
          "SELECT COUNT(*) FROM mate_wiki_knowledge_base WHERE id=? AND workspace_id=? AND deleted=0",
          Integer.class,
          kbId,
          project.workspaceId());
      if (visible == null || visible != 1) return false;

      Boolean disabled = jdbc.queryForObject(
          "SELECT wiki_disabled FROM mate_agent WHERE id=? AND workspace_id=?",
          Boolean.class,
          project.agentId(),
          project.workspaceId());
      if (disabled == null || Boolean.TRUE.equals(disabled)) return false;

      Integer scoped = jdbc.queryForObject(
          "SELECT COUNT(*) FROM mate_agent_wiki_kb WHERE agent_id=? AND enabled=TRUE AND deleted=0",
          Integer.class,
          project.agentId());
      if (scoped == null || scoped == 0) return true;
      Integer bound = jdbc.queryForObject(
          "SELECT COUNT(*) FROM mate_agent_wiki_kb WHERE agent_id=? AND kb_id=? AND enabled=TRUE AND deleted=0",
          Integer.class,
          project.agentId(),
          kbId);
      return bound != null && bound == 1;
    } catch (Exception ignored) {
      return false;
    }
  }

  /** Fail closed for legacy/unit executor construction without this bean wired. */
  public static Decision failClosed(String toolName, ChatOrigin origin) {
    if (origin != null && origin.conversationId() != null
        && origin.conversationId().startsWith("presales:")) {
      return Decision.deny("PRESALES_PROJECT_SCOPE: server project policy is unavailable.");
    }
    return Decision.allow();
  }

  private ProjectScope projectScope(String conversationId) {
    var match = PRESALES_CONVERSATION.matcher(conversationId);
    if (!match.matches()) return null;
    String workspaceId = match.group(1);
    String projectId = match.group(2);
    String runId = match.group(3);
    try {
      String body = jdbc.queryForObject(
          "SELECT body_json FROM mate_presales_project WHERE id=? AND workspace_id=?",
          String.class,
          projectId,
          workspaceId);
      JsonNode value = json.readTree(body);
      String agentId = value.path("agentId").asText("");
      if (agentId.isBlank()) return null;
      Set<String> kbIds = new LinkedHashSet<>();
      for (JsonNode material : value.path("materials")) {
        String kbId = material.path("kbId").asText("").trim();
        if (!kbId.isBlank()) kbIds.add(kbId);
      }
      Set<String> sourceRefs = new LinkedHashSet<>();
      boolean activeRun = false;
      for (JsonNode task : value.path("tasks")) {
        if (!runId.equals(task.path("runId").asText())) continue;
        activeRun = "RUNNING".equals(task.path("status").asText());
        for (JsonNode source : task.path("contextSnapshot").path("sources")) {
          String sourceRef = source.path("sourceRef").asText("").trim();
          if (!sourceRef.isBlank()) sourceRefs.add(sourceRef);
        }
      }
      if (!activeRun) return null;
      return new ProjectScope(workspaceId, agentId, Set.copyOf(kbIds), Set.copyOf(sourceRefs));
    } catch (Exception ignored) {
      return null;
    }
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node == null ? null : node.get(field);
    return value == null || value.isNull() ? "" : value.asText("").trim();
  }

  private record ProjectScope(String workspaceId, String agentId, Set<String> kbIds,
                              Set<String> sourceRefs) {}
}

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
import vip.mate.agent.execution.ProjectConversationToolBoundary;
import vip.mate.presales.repository.PresalesProjectRepository;
import vip.mate.workspace.core.service.ProjectSourceAccess;

/**
 * Server-side allowlist and project-source boundary for presales tool calls.
 *
 * <p>The model prompt and the callback catalog are only disclosures. This policy is evaluated
 * immediately before the normal guard/approval path and therefore remains authoritative when a
 * model emits a deferred tool call or an approval replay.
 */
@Component
@ConditionalOnProperty(name = "mateclaw.presales.enabled", havingValue = "true")
public class PresalesToolPolicy implements ProjectConversationToolBoundary {
    public static final Set<String> PROJECT_VISIBLE_TOOLS =
            Set.of(
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
    private final ProjectSourceAccess sourceAccess;
    private final PresalesProjectRepository projects;

    public PresalesToolPolicy(
            JdbcTemplate jdbc,
            ObjectMapper json,
            ProjectSourceAccess sourceAccess,
            PresalesProjectRepository projects) {
        this.jdbc = jdbc;
        this.json = json;
        this.sourceAccess = sourceAccess;
        this.projects = projects;
    }

    @Override
    public Set<String> visibleTools() {
        return PROJECT_VISIBLE_TOOLS;
    }

    /** Returns whether a callback may be advertised in a project-scoped run. */
    public static boolean isProjectVisibleTool(String toolName) {
        return toolName != null && PROJECT_VISIBLE_TOOLS.contains(toolName);
    }

    /**
     * Evaluate a tool call. Non-presales conversations are deliberately a no-op; their existing
     * callback, guard, and approval policies remain unchanged.
     */
    @Override
    public ProjectConversationToolBoundary.Decision evaluate(
            String toolName, String arguments, ChatOrigin origin) {
        String conversationId = origin == null ? null : origin.conversationId();
        if (conversationId == null || !conversationId.startsWith("presales:")) {
            return ProjectConversationToolBoundary.Decision.allow();
        }
        if (!isProjectVisibleTool(toolName)) {
            return ProjectConversationToolBoundary.Decision.deny(
                    "PRESALES_TOOL_NOT_ALLOWED: this project-scoped run only permits web search, bound skill loading, and read-only retrieval.");
        }
        if (origin == null || origin.agentId() == null) {
            return ProjectConversationToolBoundary.Decision.deny(
                    "PRESALES_PROJECT_SCOPE: bound employee identity is required.");
        }

        JsonNode args;
        try {
            args = json.readTree(arguments == null || arguments.isBlank() ? "{}" : arguments);
        } catch (Exception e) {
            return ProjectConversationToolBoundary.Decision.deny(
                    "PRESALES_TOOL_SCOPE: invalid tool arguments.");
        }
        ProjectScope project = projectScope(conversationId);
        if (project == null || !project.agentId().equals(origin.agentId().toString())) {
            return ProjectConversationToolBoundary.Decision.deny(
                    "PRESALES_PROJECT_SCOPE: project run is unavailable for this employee.");
        }
        if ("web_search".equals(toolName)
                || "load_skill".equals(toolName)
                || "readSkillFile".equals(toolName)
                || "listSkillFiles".equals(toolName)
                || "listAvailableSkills".equals(toolName)) {
            return ProjectConversationToolBoundary.Decision.allow();
        }
        String agentId = text(args, "agentId");
        if ("project_document_read".equals(toolName)) {
            if (origin != null
                    && origin.agentId() != null
                    && !project.agentId().equals(origin.agentId().toString())) {
                return ProjectConversationToolBoundary.Decision.deny(
                        "PRESALES_PROJECT_SCOPE: document retrieval must use the bound presales employee.");
            }
        } else if (agentId.isBlank() || !agentId.equals(project.agentId())) {
            return ProjectConversationToolBoundary.Decision.deny(
                    "PRESALES_PROJECT_SCOPE: Wiki retrieval must use the bound presales employee.");
        }
        String kbId = text(args, "kbIdParam");
        if (kbId.isBlank()) kbId = text(args, "kbId");

        if ("project_document_read".equals(toolName)) {
            String sourceRef = text(args, "sourceRef");
            if (sourceRef.isBlank()) {
                return ProjectConversationToolBoundary.Decision.deny(
                        "PRESALES_PROJECT_SCOPE: sourceRef is required.");
            }
            if (kbId.isBlank()) {
                return ProjectConversationToolBoundary.Decision.deny(
                        "PRESALES_PROJECT_SCOPE: project_document_read requires kbId.");
            }
            if (!project.kbIds().contains(kbId) || !project.sourceRefs().contains(sourceRef)) {
                return ProjectConversationToolBoundary.Decision.deny(
                        "PRESALES_PROJECT_SCOPE: document is not part of this project snapshot.");
            }
            return employeeCanReadKb(project, kbId)
                    ? ProjectConversationToolBoundary.Decision.allow()
                    : ProjectConversationToolBoundary.Decision.deny(
                            "PRESALES_PROJECT_SCOPE: knowledge base is not visible to the bound employee.");
        }

        if (kbId.isBlank()) {
            String kbName = text(args, "kbName");
            if (kbName.isBlank()) {
                return ProjectConversationToolBoundary.Decision.deny(
                        "PRESALES_PROJECT_SCOPE: Wiki retrieval requires an explicit project-bound kbId or kbName.");
            }
            Set<String> matching = new LinkedHashSet<>();
            for (String boundId : project.kbIds()) {
                String name =
                        jdbc.query(
                                "SELECT name FROM mate_wiki_knowledge_base WHERE id=? AND workspace_id=? AND deleted=0",
                                rs -> rs.next() ? rs.getString(1) : null,
                                boundId,
                                project.workspaceId());
                if (kbName.equals(name)) matching.add(boundId);
            }
            if (matching.size() != 1) {
                return ProjectConversationToolBoundary.Decision.deny(
                        "PRESALES_PROJECT_SCOPE: kbName is not an unambiguous project-bound knowledge base.");
            }
            return employeeCanReadKb(project, matching.iterator().next())
                    ? ProjectConversationToolBoundary.Decision.allow()
                    : ProjectConversationToolBoundary.Decision.deny(
                            "PRESALES_PROJECT_SCOPE: knowledge base is unavailable to the bound employee.");
        }
        if (!project.kbIds().contains(kbId)) {
            return ProjectConversationToolBoundary.Decision.deny(
                    "PRESALES_PROJECT_SCOPE: the requested knowledge base is not bound to this project.");
        }
        return employeeCanReadKb(project, kbId)
                ? ProjectConversationToolBoundary.Decision.allow()
                : ProjectConversationToolBoundary.Decision.deny(
                        "PRESALES_PROJECT_SCOPE: knowledge base is unavailable to the bound employee.");
    }

    private boolean employeeCanReadKb(ProjectScope project, String kbId) {
        try {
            return sourceAccess.canEmployeeReadKb(project.workspaceId(), project.agentId(), kbId);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private ProjectScope projectScope(String conversationId) {
        var match = PRESALES_CONVERSATION.matcher(conversationId);
        if (!match.matches()) return null;
        String workspaceId = match.group(1);
        String projectId = match.group(2);
        String runId = match.group(3);
        try {
            String body = projects.findBody(workspaceId, projectId, false).orElseThrow();
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
            return new ProjectScope(
                    workspaceId, agentId, Set.copyOf(kbIds), Set.copyOf(sourceRefs));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? "" : value.asText("").trim();
    }

    private record ProjectScope(
            String workspaceId, String agentId, Set<String> kbIds, Set<String> sourceRefs) {}
}

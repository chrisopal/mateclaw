package vip.mate.presales;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import vip.mate.agent.context.ChatOrigin;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Read a document already stored in a knowledge base bound to the presales project. */
@Component
@ConditionalOnProperty(name = "mateclaw.presales.enabled", havingValue = "true")
public class PresalesProjectDocumentTool {
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final PresalesToolPolicy policy;

  public PresalesProjectDocumentTool(JdbcTemplate jdbc, ObjectMapper json, PresalesToolPolicy policy) {
    this.jdbc = jdbc;
    this.json = json;
    this.policy = policy;
  }

  @Tool(name = "project_document_read", description = """
      Read the extracted text of one document source already bound to this presales project.
      Pass the exact sourceRef and kbId from the project snapshot. This tool never accepts a
      filesystem path and never reads a knowledge base outside the current project binding.
      """)
  public String read(
      @ToolParam(description = "Exact sourceRef from the project snapshot") String sourceRef,
      @ToolParam(description = "Exact project-bound knowledge-base id from the project snapshot") String kbId,
      @ToolParam(description = "Maximum characters to return, default 30000, maximum 50000", required = false)
      Integer maxChars,
      @Nullable ToolContext ctx) {
    ChatOrigin origin = ChatOrigin.from(ctx);
    String conversationId = origin.conversationId();
    String[] parts = conversationId == null ? new String[0] : conversationId.split(":", 4);
    if (parts.length != 4 || !"presales".equals(parts[0])) {
      return error("PRESALES_PROJECT_SCOPE: project conversation required");
    }
    if (sourceRef == null || sourceRef.isBlank() || kbId == null || kbId.isBlank()) {
      return error("sourceRef and kbId are required");
    }
    ObjectNode request = json.createObjectNode().put("sourceRef", sourceRef).put("kbId", kbId);
    PresalesToolPolicy.Decision decision = policy.evaluate("project_document_read", request.toString(), origin);
    if (!decision.allowed()) return error(decision.reason());
    int limit = maxChars == null || maxChars <= 0 ? 30_000 : Math.min(maxChars, 50_000);
    try {
      String body = jdbc.queryForObject(
          "SELECT body_json FROM mate_presales_project WHERE id=? AND workspace_id=?",
          String.class, parts[2], parts[1]);
      var project = json.readTree(body);
      boolean bound = false;
      for (var material : project.path("materials")) {
        if (kbId.equals(material.path("kbId").asText())) {
          bound = true;
          break;
        }
      }
      if (!bound) return error("PRESALES_PROJECT_SCOPE: knowledge base is not bound to this project");
      boolean sourceInSnapshot = false;
      for (var task : project.path("tasks")) {
        if (!parts[3].equals(task.path("runId").asText())) continue;
        if (!"RUNNING".equals(task.path("status").asText())) return error("PRESALES_PROJECT_SCOPE: project run is not active");
        for (var source : task.path("contextSnapshot").path("sources")) {
          if (sourceRef.equals(source.path("sourceRef").asText())) {
            sourceInSnapshot = true;
            break;
          }
        }
      }
      if (!sourceInSnapshot) return error("PRESALES_PROJECT_SCOPE: document is not part of this project snapshot");
      var rows = jdbc.queryForList(
          "SELECT r.title,r.source_type,r.mime_type,"
              + "COALESCE(NULLIF(r.extracted_text,''),r.original_content) AS source_text,"
              + "r.content_hash FROM mate_wiki_raw_material r "
              + "JOIN mate_wiki_knowledge_base k ON k.id=r.kb_id "
              + "WHERE r.id=? AND r.kb_id=? AND k.workspace_id=? "
              + "AND r.deleted=0 AND k.deleted=0",
          sourceRef, kbId, parts[1]);
      if (rows.size() != 1) return error("Document source is unavailable in this project");
      Map<String, Object> row = rows.getFirst();
      String text = java.util.Objects.toString(row.get("source_text"), "");
      boolean truncated = text.length() > limit;
      String returned = truncated ? text.substring(0, limit) : text;
      ObjectNode result = json.createObjectNode()
          .put("sourceRef", sourceRef)
          .put("kbId", kbId)
          .put("title", java.util.Objects.toString(row.get("title"), ""))
          .put("sourceType", java.util.Objects.toString(row.get("source_type"), ""))
          .put("mimeType", java.util.Objects.toString(row.get("mime_type"), ""))
          .put("text", returned)
          .put("truncated", truncated);
      String hash = java.util.Objects.toString(row.get("content_hash"), "");
      if (hash.isBlank()) hash = PresalesArtifactRenderer.digest(text.getBytes(StandardCharsets.UTF_8));
      result.put("digest", hash);
      return result.toString();
    } catch (Exception e) {
      return error("Document source is unavailable in this project");
    }
  }

  private String error(String message) {
    return json.createObjectNode().put("error", message).toString();
  }
}

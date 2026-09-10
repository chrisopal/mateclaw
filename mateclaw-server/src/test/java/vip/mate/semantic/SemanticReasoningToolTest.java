package vip.mate.semantic;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.beans.factory.annotation.Autowired;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.databind.JsonNode;

import vip.mate.agent.context.ChatOrigin;
import vip.mate.semantic.tool.SemanticReasoningTool;
import vip.mate.semantic.support.SemanticHttpFixture;

/** Covers the trusted host identity and read-only tool surface. */
class SemanticReasoningToolTest extends SemanticHttpFixture {
    @Autowired SemanticReasoningTool tool;

    @Test
    void usesHostPrincipalAndDefaultsToTboxScope() throws Exception {
        String ontology = create();
        JsonNode draft = draft(ontology);
        JsonNode saved = call("PUT", "/ontologies/" + ontology + "/draft", "member", workspace,
                saveBody(draft.path("draftVersion").asLong(), owlDocument(
                        "Ontology(<urn:test:tool> Declaration(Class(<urn:test:Equipment>)))")), 200);
        String revision = publish(ontology, saved.path("draftVersion").asLong(), UUID.randomUUID().toString())
                .path("id").asText();
        String kb = IdWorker.getIdStr();
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("INSERT INTO mate_wiki_knowledge_base(id,name,description,status,page_count,raw_count,workspace_id,create_time,update_time,deleted) VALUES(?,?,?,?,?,?,?,?,?,0)",
                Long.valueOf(kb), "Tool KB", "", "active", 0, 0, Long.valueOf(workspace), now, now);
        String graph = call("PUT", "/knowledge-bases/" + kb + "/binding", "owner", workspace,
                Map.of("action", "ENABLE", "revisionId", revision), 200).path("graphId").asText();
        Long agent = agentWithKnowledgeBase(kb);
        Long viewer = jdbc.queryForObject(
                "SELECT user_id FROM mate_workspace_member WHERE workspace_id=? AND role='viewer' AND deleted=0",
                Long.class, Long.valueOf(workspace));
        ToolContext context = ChatOrigin.web("semantic-reasoning-test", "unused", Long.valueOf(workspace),
                null, null, viewer).withAgent(agent).toToolContext();

        var result = tool.semantic_reason(graph, null, "classification", null, null,
                Instant.parse("2026-01-01T00:00:00Z"), context);
        assertEquals("TBOX_ONLY", result.scope().name());
        assertEquals(revision, result.ontologyRevisionId());
        assertEquals("UNAVAILABLE", result.explanationStatus());
        assertFalse(ToolCallbacks.from(tool)[0].getToolDefinition().inputSchema().contains("actorId"));
        assertFalse(ToolCallbacks.from(tool)[0].getToolDefinition().inputSchema().contains("workspaceId"));
    }
}

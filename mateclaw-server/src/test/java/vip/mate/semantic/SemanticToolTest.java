package vip.mate.semantic;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.security.core.context.SecurityContextHolder;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.semantic.tool.SemanticTool;
import vip.mate.semantic.support.SemanticHttpFixture;
import vip.mate.semantic.web.SemanticApiException;
import java.time.LocalDateTime;
import java.util.*;

class SemanticToolTest extends SemanticHttpFixture {
    @Autowired SemanticTool tool;

    private Long viewerId() {
        return jdbc.queryForObject("SELECT user_id FROM mate_workspace_member WHERE workspace_id=? AND role='viewer' AND deleted=0", Long.class, Long.valueOf(workspace));
    }
    private ToolContext context() {
        return ChatOrigin.web("semantic-test", "unused-display-identity", Long.valueOf(workspace), null, null, viewerId()).toToolContext();
    }
    @Test
    void hostIdentityIsRequiredAndCurrentAuthorizationIsRevalidated() {
        assertEquals(401, assertThrows(SemanticApiException.class, () -> tool.semantic_search("301", "P-101", 5, new ToolContext(Map.of()))).status());
        assertEquals(401, assertThrows(SemanticApiException.class, () -> tool.semantic_search("301", "P-101", 5,
                ChatOrigin.web("test", viewerId().toString(), Long.valueOf(workspace), null).toToolContext())).status());
        assertEquals(400, assertThrows(SemanticApiException.class, () -> tool.semantic_search("301", "P-101", 5,
                ChatOrigin.web("test", "viewer", null, null, null, viewerId()).toToolContext())).status());
        assertEquals(401, assertThrows(SemanticApiException.class, () -> tool.semantic_search("301", "P-101", 5,
                ChatOrigin.cron("test", Long.valueOf(workspace), null, null, null).toToolContext())).status());
        assertEquals(401, assertThrows(SemanticApiException.class, () -> tool.semantic_search("301", "P-101", 5,
                ChatOrigin.web("test", "viewer", Long.valueOf(workspace), null, null, viewerId()).withSender("viewer", "feishu", "chat").toToolContext())).status());
        ToolContext captured = context();
        jdbc.update("UPDATE mate_workspace_member SET deleted=1 WHERE workspace_id=? AND user_id=?", Long.valueOf(workspace), viewerId());
        assertEquals(403, assertThrows(SemanticApiException.class, () -> tool.semantic_search("301", "P-101", 5, captured)).status());
    }
    @Test
    void toolUsesTrustedEvidenceChainWithoutHttpSecurityContext() throws Exception {
        Fixture f=graph("设备😀额定380V");
        JsonNode evidence=call("POST", "/graphs/"+f.graph+"/snapshots/"+f.snapshot+"/evidence", "member", workspace,
                Map.of("operationId", "ev", "startCodePoint",5,"endCodePoint",9,"exactQuote","380V"),200);
        JsonNode statement=propose(f,"380",evidence.path("id").asText(),"propose",200);
        Long agentId = agentWithKnowledgeBase(f.kb);
        ToolContext ctx = ChatOrigin.from(context()).withAgent(agentId).toToolContext();
        assertEquals(401, assertThrows(SemanticApiException.class, () -> tool.semantic_search(f.graph, "P-101", 5, context())).status());
        SecurityContextHolder.clearContext();
        assertTrue(tool.semantic_search(f.graph,"P-101",5,ctx).facts().isEmpty());
        call("POST","/graphs/"+f.graph+"/statements/"+statement.path("id").asText()+"/review","owner",workspace,
                Map.of("expectedRevision",1,"action","ACCEPT","reason","verified","operationId","accept"),200);
        SecurityContextHolder.clearContext();
        var result=tool.semantic_search(f.graph,"P-101",5,ctx);
        assertEquals(1,result.facts().size());
        assertEquals(statement.path("id").asText(),result.facts().getFirst().id());
        assertEquals(2,result.facts().getFirst().revision());
        assertEquals(List.of(evidence.path("id").asText()),result.facts().getFirst().evidenceIds());
        assertNotNull(UUID.fromString(result.traceId()));
        var callback=org.springframework.ai.support.ToolCallbacks.from(tool)[0];
        assertFalse(callback.getToolDefinition().inputSchema().contains("requesterUserId"));
        JsonNode rawResult=json.readTree(callback.call(json.writeValueAsString(Map.of("graphId",f.graph,"query","P-101","limit",5)),ctx));
        assertEquals(statement.path("id").asText(),rawResult.path("facts").get(0).path("id").asText());
        assertEquals(evidence.path("id").asText(),rawResult.path("facts").get(0).path("evidenceIds").get(0).asText());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        Long otherAgent = agentWithKnowledgeBase(kb());
        assertEquals(404, assertThrows(SemanticApiException.class, () -> tool.semantic_search(f.graph, "P-101", 5,
                ChatOrigin.from(ctx).withAgent(otherAgent).toToolContext())).status());
        org.mockito.Mockito.when(wikiKnowledgeBases.findVisibleById(agentId, Long.valueOf(f.kb))).thenReturn(null);
        assertEquals(404, assertThrows(SemanticApiException.class, () -> tool.semantic_search(f.graph, "P-101", 5, ctx)).status());
        var restoredKb = new vip.mate.wiki.model.WikiKnowledgeBaseEntity(); restoredKb.setId(Long.valueOf(f.kb));
        org.mockito.Mockito.when(wikiKnowledgeBases.findVisibleById(agentId, Long.valueOf(f.kb))).thenReturn(restoredKb);
        jdbc.update("UPDATE mate_agent SET enabled=FALSE WHERE id=?", agentId);
        assertEquals(403, assertThrows(SemanticApiException.class, () -> tool.semantic_search(f.graph, "P-101", 5, ctx)).status());
        jdbc.update("UPDATE mate_agent SET enabled=TRUE WHERE id=?", agentId);
        // A global admin still cannot use a graph under the wrong explicit scope.
        Long owner=jdbc.queryForObject("SELECT user_id FROM mate_workspace_member WHERE workspace_id=? AND role='owner' AND deleted=0",Long.class,Long.valueOf(workspace));
        assertThrows(SemanticApiException.class,()->tool.semantic_search(f.graph,"P-101",5,
                ChatOrigin.web("test","owner",Long.valueOf(otherWorkspace),null,null,owner).toToolContext()));
        jdbc.update("UPDATE mate_wiki_raw_material SET deleted=1 WHERE id=?",Long.valueOf(f.raw));
        assertTrue(tool.semantic_search(f.graph,"P-101",5,ctx).facts().isEmpty());
    }
    private JsonNode propose(Fixture f, String value, String evidence, String operation, int status) throws Exception {
        return call("POST", "/graphs/" + f.graph + "/statements", "member", workspace,
                Map.of("operationId", operation, "subjectId", f.entity, "assertionText", "DataPropertyAssertion(<urn:test:voltage> <urn:test:P-101> \""+value+"\"^^<http://www.w3.org/2001/XMLSchema#decimal>)", "validityKind", "INTERVAL", "evidenceIds", List.of(evidence)), status);
    }

    private Fixture graph(String text) throws Exception {
        String kb = kb(); String ontology = create(); JsonNode draft = draft(ontology); JsonNode saved = save(ontology, draft.path("draftVersion").asLong());
        JsonNode revision = publish(ontology, saved.path("draftVersion").asLong(), "publish-" + UUID.randomUUID());
        JsonNode binding = call("PUT", "/knowledge-bases/" + kb + "/binding", "owner", workspace, Map.of("action", "ENABLE", "revisionId", revision.path("id").asText()), 200);
        JsonNode entity = call("POST", "/graphs/" + binding.path("graphId").asText() + "/entities", "member", workspace, Map.of("iri", "urn:test:P-101", "assertedTypes", java.util.Set.of("urn:test:Equipment"), "displayName", "P-101"), 200);
        String raw = raw(kb, text);
        String importOperation = "import-" + UUID.randomUUID();
        JsonNode imported = call("POST", "/graphs/" + binding.path("graphId").asText() + "/imports", "member", workspace, Map.of("sourceKind", "WIKI_RAW", "sourceRef", raw, "operationId", importOperation), 200);
        return new Fixture(kb, binding.path("graphId").asText(), entity.path("id").asText(), raw, imported.path("snapshotId").asText(), imported.path("id").asText(), importOperation);
    }
    private String kb() {String id=com.baomidou.mybatisplus.core.toolkit.IdWorker.getIdStr();LocalDateTime now=LocalDateTime.now();jdbc.update("INSERT INTO mate_wiki_knowledge_base(id,name,description,status,page_count,raw_count,workspace_id,create_time,update_time,deleted) VALUES(?,?,?,?,?,?,?,?,?,0)",Long.valueOf(id),"Semantic KB","","active",0,0,Long.valueOf(workspace),now,now);return id;}
    private String raw(String kb,String text){String id=com.baomidou.mybatisplus.core.toolkit.IdWorker.getIdStr();LocalDateTime now=LocalDateTime.now();jdbc.update("INSERT INTO mate_wiki_raw_material(id,kb_id,title,source_type,original_content,file_size,processing_status,create_time,update_time,deleted) VALUES(?,?,?,?,?,?,?,?,?,0)",Long.valueOf(id),Long.valueOf(kb),"repair note","text",text,text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,"completed",now,now);return id;}
    private record Fixture(String kb,String graph,String entity,String raw,String snapshot,String importJob,String importOperation){}
}

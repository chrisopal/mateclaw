package vip.mate.semantic;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;

import vip.mate.semantic.support.SemanticHttpFixture;

import java.time.LocalDateTime;
import java.util.Map;

class SemanticGraphBindingTest extends SemanticHttpFixture {
    @Test
    void graphPinsPublishedRevisionAndRejectsRebindAfterEntityCreation() throws Exception {
        String kb = createKnowledgeBase(workspace);
        String ontology = create();
        JsonNode draft = draft(ontology);
        JsonNode saved = save(ontology, draft.path("draftVersion").asLong());
        JsonNode revision = publish(ontology, saved.path("draftVersion").asLong(), "publish-graph");

        JsonNode binding =
                call(
                        "PUT",
                        "/knowledge-bases/" + kb + "/binding",
                        "owner",
                        workspace,
                        Map.of("action", "ENABLE", "revisionId", revision.path("id").asText()),
                        200);
        assertEquals(revision.path("id").asText(), binding.path("ontologyRevisionId").asText());
        assertEquals(0, binding.path("graphVersion").asLong());

        JsonNode entity =
                call(
                        "POST",
                        "/graphs/" + binding.path("graphId").asText() + "/entities",
                        "member",
                        workspace,
                        Map.of("typeKey", "Equipment", "displayName", "P-101"),
                        200);
        assertEquals("P-101", entity.path("displayName").asText());

        call(
                "PUT",
                "/knowledge-bases/" + kb + "/binding",
                "owner",
                workspace,
                Map.of(
                        "action",
                        "REBIND",
                        "revisionId",
                        revision.path("id").asText(),
                        "expectedGraphVersion",
                        1),
                409);
        JsonNode persisted =
                call("GET", "/knowledge-bases/" + kb + "/binding", "viewer", workspace, null, 200);
        assertEquals(revision.path("id").asText(), persisted.path("ontologyRevisionId").asText());
    }

    @Test
    void bindingRejectsCrossWorkspaceAndUnavailableRevision() throws Exception {
        String kb = createKnowledgeBase(workspace);
        String ontology = create();
        JsonNode draft = draft(ontology);
        JsonNode saved = save(ontology, draft.path("draftVersion").asLong());
        JsonNode revision = publish(ontology, saved.path("draftVersion").asLong(), "publish-stopped");
        call(
                "PATCH",
                "/ontologies/" + ontology + "/revisions/" + revision.path("id").asText() + "/availability",
                "owner",
                workspace,
                Map.of("availableForNewBindings", false),
                200);

        call(
                "PUT",
                "/knowledge-bases/" + kb + "/binding",
                "owner",
                workspace,
                Map.of("action", "ENABLE", "revisionId", revision.path("id").asText()),
                409);
        call(
                "PUT",
                "/knowledge-bases/" + kb + "/binding",
                "owner",
                otherWorkspace,
                Map.of("action", "ENABLE", "revisionId", revision.path("id").asText()),
                404);
    }

    private String createKnowledgeBase(String scope) {
        String id = com.baomidou.mybatisplus.core.toolkit.IdWorker.getIdStr();
        LocalDateTime now = LocalDateTime.now();
        jdbc.update(
                "INSERT INTO mate_wiki_knowledge_base(id,name,description,status,page_count,raw_count,workspace_id,create_time,update_time,deleted) VALUES(?,?,?,?,?,?,?,?,?,0)",
                Long.valueOf(id),
                "Semantic KB",
                "",
                "active",
                0,
                0,
                Long.valueOf(scope),
                now,
                now);
        return id;
    }
}

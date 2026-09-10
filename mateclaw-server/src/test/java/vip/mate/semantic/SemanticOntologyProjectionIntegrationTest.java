package vip.mate.semantic;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vip.mate.semantic.support.SemanticHttpFixture;

class SemanticOntologyProjectionIntegrationTest extends SemanticHttpFixture {
    @Test
    void draftProjectionPinsVersionAndHonorsBoundedLimit() throws Exception {
        String id = create();
        JsonNode draft = draft(id);
        long version = draft.path("draftVersion").asLong();

        JsonNode projection = call("GET", "/ontologies/" + id + "/draft/projection?expectedDraftVersion=" + version + "&limit=1", "viewer", workspace, null, 200);

        assertEquals(id, projection.path("snapshot").path("ontologyId").asText());
        assertEquals(draft.path("id").asText(), projection.path("snapshot").path("revisionId").asText());
        assertEquals(version, projection.path("snapshot").path("draftVersion").asLong());
        assertTrue(projection.path("snapshot").path("documentDigest").isTextual());
        assertTrue(projection.path("snapshot").path("importLockDigest").isTextual());
        assertEquals("ontology-display-v1", projection.path("projection").path("schemaVersion").asText());
        assertTrue(projection.path("projection").path("coverage").path("returned").asInt() <= 1);
        assertEquals(version, call("GET", "/ontologies/" + id + "/draft", "viewer", workspace, null, 200).path("draftVersion").asLong());
    }

    @Test
    void projectionRejectsMissingOrOutOfRangeVersionAndLimit() throws Exception {
        String id = create();
        draft(id);
        call("GET", "/ontologies/" + id + "/draft/projection", "viewer", workspace, null, 400);
        call("GET", "/ontologies/" + id + "/draft/projection?expectedDraftVersion=1&limit=0", "viewer", workspace, null, 400);
        call("GET", "/ontologies/" + id + "/draft/projection?expectedDraftVersion=1&limit=2001", "viewer", workspace, null, 400);
        call("GET", "/ontologies/" + id + "/draft/projection?expectedDraftVersion=99", "viewer", workspace, null, 409);
    }

    @Test
    void publishedProjectionIsReadOnlyAndRequiresSameOntologyRevision() throws Exception {
        String id = create();
        JsonNode d = draft(id);
        JsonNode saved = save(id, d.path("draftVersion").asLong());
        JsonNode revision = publish(id, saved.path("draftVersion").asLong(), "projection-" + UUID.randomUUID());

        JsonNode projection = call("GET", "/ontologies/" + id + "/revisions/" + revision.path("id").asText() + "/projection?limit=2000", "viewer", workspace, null, 200);
        assertEquals(id, projection.path("snapshot").path("ontologyId").asText());
        assertEquals(revision.path("id").asText(), projection.path("snapshot").path("revisionId").asText());
        assertTrue(projection.path("snapshot").path("draftVersion").isNull());
        assertEquals(revision.path("document").path("documentDigest").asText(), projection.path("snapshot").path("documentDigest").asText());

        String other = create();
        call("GET", "/ontologies/" + other + "/revisions/" + revision.path("id").asText() + "/projection", "viewer", workspace, null, 404);
    }

    @Test
    void projectionHonorsWorkspaceAuthorization() throws Exception {
        String id = create();
        draft(id);
        call("GET", "/ontologies/" + id + "/draft/projection?expectedDraftVersion=1", "viewer", otherWorkspace, null, 403);
        call("GET", "/ontologies/" + id + "/draft/projection?expectedDraftVersion=1", "viewer", workspace, null, 200);
    }
}

package vip.mate.semantic;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vip.mate.semantic.support.SemanticHttpFixture;

class SemanticOntologyProjectionIntegrationTest extends SemanticHttpFixture {
    @Test
    void complexProjectionRoundTripsSourceReferencesWithoutChangingDraft() throws Exception {
        String id = create();
        JsonNode initial = draft(id);
        JsonNode saved = call("PUT", "/ontologies/" + id + "/draft", "member", workspace,
                saveBody(initial.path("draftVersion").asLong(), owlDocument("""
                    Ontology(<urn:test:expressions>
                      Declaration(Class(<urn:test:Machine>))
                      Declaration(Class(<urn:test:Probe>))
                      Declaration(ObjectProperty(<urn:test:uses>))
                      SubClassOf(<urn:test:Machine>
                        ObjectAllValuesFrom(<urn:test:uses> ObjectComplementOf(<urn:test:Probe>))))
                    """)), 200);
        long version = saved.path("draftVersion").asLong();
        JsonNode view = call("GET", "/ontologies/" + id + "/draft/projection?expectedDraftVersion=" + version,
                "viewer", workspace, null, 200).path("projection");
        assertTrue(view.path("expressions").isArray());
        assertTrue(view.path("expressions").size() >= 3);
        for (JsonNode expression : view.path("expressions")) {
            assertFalse(expression.path("path").asText().isBlank());
            assertFalse(expression.path("operator").asText().isBlank());
            assertTrue(expression.path("operands").isArray());
            boolean found = false;
            for (JsonNode ref : view.path("axiomRefs")) {
                if (ref.path("id").asText().equals(expression.path("axiomId").asText())) {
                    found = true;
                    assertEquals("FULL", ref.path("status").asText());
                    assertEquals(saved.path("id").asText(), ref.path("artifactId").asText());
                }
            }
            assertTrue(found, "Expression must reference a returned source axiom");
        }
        JsonNode readback = call("GET", "/ontologies/" + id + "/draft", "viewer", workspace, null, 200);
        assertEquals(version, readback.path("draftVersion").asLong());
        assertEquals(saved.path("document").path("documentDigest"), readback.path("document").path("documentDigest"));
    }

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

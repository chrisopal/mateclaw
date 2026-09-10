package vip.mate.semantic;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import vip.mate.semantic.support.SemanticHttpFixture;

import java.util.*;
import java.util.concurrent.*;

class SemanticOntologyIntegrationTest extends SemanticHttpFixture {
    @Test
    void draftExportPinsVersionAndRemainsSeparateFromPublishedExport() throws Exception {
        String id = create(); var initial = draft(id); var saved = save(id, initial.path("draftVersion").asLong());
        String path = "/ontologies/" + id + "/draft/document?expectedDraftVersion=" + saved.path("draftVersion").asLong();
        for (String syntax : List.of("FUNCTIONAL", "RDF_XML")) {
            var response = request("GET", path + "&syntax=" + syntax, "viewer", workspace, null);
            assertEquals(200, response.getStatus());
            assertTrue(response.getContentAsByteArray().length > 0);
        }
        call("GET", path, "viewer", otherWorkspace, null, 403);
        call("GET", path, "owner", otherWorkspace, null, 404);
        call("GET", "/ontologies/" + id + "/draft/document?expectedDraftVersion=1", "viewer", workspace, null, 409);
        call("GET", "/ontologies/" + id + "/revisions/" + initial.path("id").asText() + "/document", "viewer", workspace, null, 404);
        assertEquals(saved, call("GET", "/ontologies/" + id + "/draft", "viewer", workspace, null, 200));
    }

    @Test
    void axiomEditsPreserveIndexAndReplayAndDiscardDeletesProjection() throws Exception {
        String id=create(); var first=draft(id); var saved=save(id,1);
        String revision=first.path("id").asText();
        var oldIds=jdbc.query("SELECT axiom_id FROM mate_semantic_ontology_axiom WHERE revision_id=? ORDER BY axiom_id",(rs,n)->rs.getString(1),revision);
        assertFalse(oldIds.isEmpty());
        var edit=Map.of("expectedDraftVersion",2,"operationId",UUID.randomUUID().toString(),"changes",List.of(
            Map.of("kind","ADD","functionalSyntax","SubClassOf(<urn:test:Equipment> <http://www.w3.org/2002/07/owl#Thing>)")));
        var changed=call("PATCH","/ontologies/"+id+"/draft/axioms","member",workspace,edit,200);
        assertEquals(changed,call("PATCH","/ontologies/"+id+"/draft/axioms","member",workspace,edit,200));
        var after=jdbc.query("SELECT axiom_id FROM mate_semantic_ontology_axiom WHERE revision_id=?",(rs,n)->rs.getString(1),revision);
        assertTrue(after.containsAll(oldIds)); assertEquals(oldIds.size()+1,after.size());
        call("PATCH","/ontologies/"+id+"/draft/axioms","member",workspace,
            Map.of("expectedDraftVersion",3,"operationId",UUID.randomUUID().toString(),"changes",List.of(Map.of("kind","ADD"))),422);
        assertEquals(3,call("GET","/ontologies/"+id+"/draft","member",workspace,null,200).path("draftVersion").asInt());
        call("DELETE","/ontologies/"+id+"/draft?expectedDraftVersion=3","member",workspace,null,200);
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM mate_semantic_ontology_axiom WHERE revision_id=?",Integer.class,revision));
    }

    @Test
    void discardedDraftTokenCannotOverwriteReplacementDraft() throws Exception {
        String id = create();
        var original = draft(id);
        long stale = original.path("draftVersion").asLong();
        call(
                "DELETE",
                "/ontologies/" + id + "/draft?expectedDraftVersion=" + stale,
                "member",
                workspace,
                null,
                200);
        var replacement = draft(id);
        call(
                "PUT",
                "/ontologies/" + id + "/draft",
                "member",
                workspace,
                saveBody(stale, definition()),
                409);
        assertTrue(replacement.path("draftVersion").asLong() > stale);
        save(id, replacement.path("draftVersion").asLong());
    }

    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private vip.mate.semantic.statement.repository.GovernanceRecordMapper governance;

    @Test
    void concurrentOperationReuseAcrossOntologiesReturnsConflictAndRollsBackLoser()
            throws Exception {
        String first = create(), second = create();
        draft(first);
        draft(second);
        save(first, 1);
        save(second, 1);
        String op = "shared-" + UUID.randomUUID();
        var barrier = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a =
                    pool.submit(
                            () -> {
                                barrier.await();
                                return request(
                                                "POST",
                                                "/ontologies/" + first + "/draft/publish",
                                                "owner",
                                                workspace,
                                                publishBody(2, op))
                                        .getStatus();
                            });
            var b =
                    pool.submit(
                            () -> {
                                barrier.await();
                                return request(
                                                "POST",
                                                "/ontologies/" + second + "/draft/publish",
                                                "owner",
                                                workspace,
                                                publishBody(2, op))
                                        .getStatus();
                            });
            var codes = new ArrayList<>(List.of(a.get(), b.get()));
            Collections.sort(codes);
            assertEquals(List.of(200, 409), codes);
        }
        assertEquals(
                1,
                jdbc.queryForObject(
                        "select count(*) from mate_semantic_ontology_revision where ontology_id in"
                                + " (?,?) and revision_state='PUBLISHED'",
                        Integer.class,
                        first,
                        second));
    }

    @Test
    void wireCountersAreNumbersWithHostJacksonConfiguration() throws Exception {
        String id = create();
        assertTrue(
                call("GET", "/ontologies/" + id, "viewer", workspace, null, 200)
                        .path("updatedAt")
                        .asText()
                        .endsWith("Z"));
        var draft = draft(id);
        assertTrue(draft.path("draftVersion").isIntegralNumber());
        assertTrue(draft.path("version").isIntegralNumber());
        save(id, 1);
        var report =
                call(
                        "POST",
                        "/ontologies/" + id + "/draft/validate",
                        "member",
                        workspace,
                        Map.of("expectedDraftVersion", 2),
                        200);
        assertTrue(report.path("draftVersion").isIntegralNumber());
        var page = call("GET", "/ontologies", "viewer", workspace, null, 200);
        assertTrue(page.path("total").isIntegralNumber());
        assertTrue(page.path("page").isIntegralNumber());
    }

    @Test
    void wireIdsMatchCoreIdentityAndNullNoteFailsBeforeMutation() throws Exception {
        String id = create();
        assertEquals(id, new vip.mate.semantic.core.identity.SemanticIds.OntologyId(id).value());
        var d = draft(id);
        assertEquals(
                d.path("id").asText(),
                new vip.mate.semantic.core.identity.SemanticIds.OntologyRevisionId(
                                d.path("id").asText())
                        .value());
        save(id, 1);
        var request = new HashMap<>(publishBody(2, "null-note"));
        request.put("note", null);
        call("POST", "/ontologies/" + id + "/draft/publish", "owner", workspace, request, 422);
        request.put("note", "  ");
        call("POST", "/ontologies/" + id + "/draft/publish", "owner", workspace, request, 422);
    }

    @Test
    void governanceFailureRollsBackPublicationAndAvailability() throws Exception {
        String id = create();
        draft(id);
        save(id, 1);
        var v1 = publish(id, 2, "first-" + UUID.randomUUID());
        var secondDraft =
                call(
                        "POST",
                        "/ontologies/" + id + "/draft",
                        "member",
                        workspace,
                        Map.of("baseRevisionId", v1.path("id").asText()),
                        200);
        long secondVersion =
                save(id, secondDraft.path("draftVersion").asLong()).path("draftVersion").asLong();
        String op = "rollback-" + UUID.randomUUID();
        org.mockito.Mockito.doThrow(new IllegalStateException("test governance fault"))
                .when(governance)
                .insert(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any());
        call(
                "POST",
                "/ontologies/" + id + "/draft/publish",
                "owner",
                workspace,
                publishBody(secondVersion, op),
                500);
        call(
                "PATCH",
                "/ontologies/" + id + "/revisions/" + v1.path("id").asText() + "/availability",
                "owner",
                workspace,
                Map.of("availableForNewBindings", false),
                500);
        assertEquals(
                1,
                jdbc.queryForObject(
                        "select count(*) from mate_semantic_ontology_revision where ontology_id=?"
                                + " and revision_state='PUBLISHED'",
                        Integer.class,
                        id));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "select count(*) from mate_semantic_command_record where operation_id=?",
                        Integer.class,
                        op));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "select count(*) from mate_semantic_governance_record where ontology_id=?",
                        Integer.class,
                        id));
        assertEquals(
                secondVersion,
                call("GET", "/ontologies/" + id + "/draft", "member", workspace, null, 200)
                        .path("draftVersion")
                        .asLong());
        assertTrue(
                call(
                                "GET",
                                "/ontologies/" + id + "/revisions/" + v1.path("id").asText(),
                                "viewer",
                                workspace,
                                null,
                                200)
                        .path("availableForNewBindings")
                        .asBoolean());
        org.mockito.Mockito.reset(governance);
        assertEquals(2, publish(id, secondVersion, op).path("version").asInt());
    }

    @Test
    void incompleteReferencesSaveButValidationAndPublishReject() throws Exception {
        String id = create();
        draft(id);
        var broken = owlDocument("Ontology(<urn:test:broken> Declaration(Class(<urn:test:C>)) Declaration(ObjectProperty(<urn:test:p>)) TransitiveObjectProperty(<urn:test:p>) SubClassOf(<urn:test:C> ObjectMaxCardinality(1 <urn:test:p>)))");
        call("PUT", "/ontologies/" + id + "/draft", "member", workspace, saveBody(1, broken), 200);
        var validation =
                call(
                        "POST",
                        "/ontologies/" + id + "/draft/validate",
                        "member",
                        workspace,
                        Map.of("expectedDraftVersion", 2),
                        200);
        assertFalse(validation.path("valid").asBoolean());
        assertFalse(validation.path("violations").isEmpty());
        assertFalse(
                call(
                                "POST",
                                "/ontologies/" + id + "/draft/publish",
                                "owner",
                                workspace,
                                publishBody(2, "bad-ref"),
                                422)
                        .path("fieldErrors")
                        .isEmpty());
    }

    @Test
    void invalidWireTypesAndBudgetsAreRejected() throws Exception {
        String id = create();
        draft(id);
        call(
                "POST",
                "/ontologies",
                "member",
                workspace,
                Map.of("name", 12, "description", ""),
                400);
        for (Object syntax : List.of("NOT_A_SYNTAX", 0)) {
            var broken=new HashMap<>(definition()); broken.put("syntax",syntax);
            call("PUT","/ontologies/"+id+"/draft","member",workspace,saveBody(1,broken),400);
        }
        call(
                "POST",
                "/ontologies",
                "member",
                workspace,
                Map.of("name", "x".repeat(129), "description", ""),
                422);
        var broken = new HashMap<>(definition());
        broken.put("documentText", "Ontology( malformed");
        call("PUT", "/ontologies/" + id + "/draft", "member", workspace, saveBody(1, broken), 422);
        var coerced = new HashMap<>(saveBody(1, definition()));
        coerced.put("expectedDraftVersion", 1.5);
        call("PUT", "/ontologies/" + id + "/draft", "member", workspace, coerced, 400);
    }

    @Test
    void diffUsesPersistedKeysAndAvailabilityPreservesContentAndDiscardUsesCas() throws Exception {
        String id = create();
        var initial = draft(id);
        call("POST", "/ontologies/" + id + "/draft", "member", workspace, Map.of(), 409);
        save(id, 1);
        String op = "full-" + UUID.randomUUID();
        var v1 = publish(id, 2, op);
        assertEquals(
                v1, call("GET", "/operations/" + op, "owner", workspace, null, 200).path("result"));
        var copied =
                call(
                        "POST",
                        "/ontologies/" + id + "/draft",
                        "member",
                        workspace,
                        Map.of("baseRevisionId", v1.path("id").asText()),
                        200);
        var body = new HashMap<>(saveBody(copied.path("draftVersion").asLong(), definition()));
        body.put("name", "Changed");
        var saved = call("PUT", "/ontologies/" + id + "/draft", "member", workspace, body, 200);
        var diff =
                call(
                        "GET",
                        "/ontologies/"
                                + id
                                + "/diff?from="
                                + v1.path("id").asText()
                                + "&to="
                                + copied.path("id").asText(),
                        "viewer",
                        workspace,
                        null,
                        200);
        assertEquals(1, diff.path("changes").size());
        assertEquals("metadata", diff.path("changes").get(0).path("category").asText());
        var disabled =
                call(
                        "PATCH",
                        "/ontologies/"
                                + id
                                + "/revisions/"
                                + v1.path("id").asText()
                                + "/availability",
                        "admin",
                        workspace,
                        Map.of("availableForNewBindings", false),
                        200);
        assertFalse(disabled.path("availableForNewBindings").asBoolean());
        assertEquals(v1.path("document"), disabled.path("document"));
        call(
                "DELETE",
                "/ontologies/" + id + "/draft?expectedDraftVersion=1",
                "member",
                workspace,
                null,
                409);
        call(
                "DELETE",
                "/ontologies/"
                        + id
                        + "/draft?expectedDraftVersion="
                        + saved.path("draftVersion").asLong(),
                "member",
                workspace,
                null,
                200);
        call("GET", "/ontologies/" + id + "/draft", "member", workspace, null, 404);
        assertEquals(
                1,
                call("GET", "/ontologies/" + id + "/revisions", "viewer", workspace, null, 200)
                        .size());
        var page =
                call(
                        "GET",
                        "/ontologies?q=Equipment&page=1&pageSize=1",
                        "viewer",
                        workspace,
                        null,
                        200);
        assertEquals(1, page.path("items").size());
        assertEquals(1, page.path("total").asInt());
    }

    @Test
    void concurrentDraftCreationAndPublishHaveOneDurableWinner() throws Exception {
        String id = create();
        var barrier = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<Integer> create =
                    () -> {
                        barrier.await();
                        return request(
                                        "POST",
                                        "/ontologies/" + id + "/draft",
                                        "member",
                                        workspace,
                                        Map.of())
                                .getStatus();
                    };
            var a = pool.submit(create);
            var b = pool.submit(create);
            var codes = new ArrayList<>(List.of(a.get(), b.get()));
            Collections.sort(codes);
            assertEquals(List.of(200, 409), codes);
        }
        save(id, 1);
        String op = "concurrent-" + UUID.randomUUID();
        barrier.reset();
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<com.fasterxml.jackson.databind.JsonNode> publish =
                    () -> {
                        barrier.await();
                        return publish(id, 2, op);
                    };
            var a = pool.submit(publish);
            var b = pool.submit(publish);
            assertEquals(a.get(), b.get());
        }
        assertEquals(
                1,
                jdbc.queryForObject(
                        "select count(*) from mate_semantic_command_record where operation_id=?",
                        Integer.class,
                        op));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "select count(*) from mate_semantic_governance_record where ontology_id=?",
                        Integer.class,
                        id));
    }

    @Test
    void concurrentSavesUseDatabaseCasAndOldValidationCannotPublish() throws Exception {
        String id = create();
        draft(id);
        var barrier = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<Integer> save =
                    () -> {
                        barrier.await();
                        return request(
                                        "PUT",
                                        "/ontologies/" + id + "/draft",
                                        "member",
                                        workspace,
                                        saveBody(1, definition()))
                                .getStatus();
                    };
            var a = pool.submit(save);
            var b = pool.submit(save);
            var codes = new ArrayList<>(List.of(a.get(), b.get()));
            Collections.sort(codes);
            assertEquals(List.of(200, 409), codes);
        }
        var report =
                call(
                        "POST",
                        "/ontologies/" + id + "/draft/validate",
                        "member",
                        workspace,
                        Map.of("expectedDraftVersion", 2),
                        200);
        assertTrue(report.path("valid").asBoolean());
        save(id, 2);
        call(
                "POST",
                "/ontologies/" + id + "/draft/publish",
                "owner",
                workspace,
                publishBody(2, "stale"),
                409);
    }

    @Test
    void publicationReplayAndImmutableHistoricalMetadata() throws Exception {
        String id = create();
        draft(id);
        save(id, 1);
        String op = "op-" + UUID.randomUUID();
        var published = publish(id, 2, op);
        assertTrue(published.path("publishedAt").asText().endsWith("Z"));
        assertEquals(published, publish(id, 2, op));
        call(
                "POST",
                "/ontologies/" + id + "/draft/publish",
                "owner",
                workspace,
                publishBody(3, op),
                409);
        var nextDraft =
                call(
                        "POST",
                        "/ontologies/" + id + "/draft",
                        "member",
                        workspace,
                        Map.of("baseRevisionId", published.path("id").asText()),
                        200);
        var body = new HashMap<>(saveBody(nextDraft.path("draftVersion").asLong(), definition()));
        body.put("name", "New metadata");
        call("PUT", "/ontologies/" + id + "/draft", "member", workspace, body, 200);
        assertEquals(
                published,
                call(
                        "GET",
                        "/ontologies/" + id + "/revisions/" + published.path("id").asText(),
                        "viewer",
                        workspace,
                        null,
                        200));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "select count(*) from mate_semantic_ontology_revision where ontology_id=?"
                                + " and revision_state='PUBLISHED'",
                        Integer.class,
                        id));
    }
}

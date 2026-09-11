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
    void restrictionReplacementIsAtomicAndPreservesUnrelatedAxioms() throws Exception {
        String id = create(); var initial = draft(id);
        String original = "SubClassOf(<urn:test:Equipment> ObjectMinCardinality(0 <urn:test:uses> <urn:test:Probe>))";
        var document = owlDocument("Ontology(<urn:test:rules> "
                + "Declaration(Class(<urn:test:Equipment>)) Declaration(Class(<urn:test:Probe>)) "
                + "Declaration(ObjectProperty(<urn:test:uses>)) Declaration(AnnotationProperty(<urn:test:note>)) " + original
                + " SubClassOf(Annotation(<urn:test:note> \"keep\") <urn:test:Equipment> ObjectAllValuesFrom(<urn:test:uses> <urn:test:Probe>))"
                + " SubClassOf(<urn:test:Equipment> ObjectSomeValuesFrom(<urn:test:uses> ObjectComplementOf(<urn:test:Probe>))))");
        var saved = call("PUT", "/ontologies/" + id + "/draft", "member", workspace,
                saveBody(initial.path("draftVersion").asLong(), document), 200);
        String axiomId = null;
        for (var axiom : saved.path("document").path("axioms")) {
            if (axiom.path("rendering").asText().equals(original)) axiomId = axiom.path("axiomId").asText();
        }
        assertNotNull(axiomId);
        var changes = List.of(Map.of("kind", "REMOVE", "axiomId", axiomId),
                Map.of("kind", "ADD", "functionalSyntax", "SubClassOf(<urn:test:Equipment> ObjectMinCardinality(2 <urn:test:uses> <urn:test:Probe>))"));
        var edit = Map.of("expectedDraftVersion", saved.path("draftVersion").asLong(),
                "operationId", UUID.randomUUID().toString(), "changes", changes);
        call("PATCH", "/ontologies/" + id + "/draft/axioms", "viewer", workspace, edit, 403);
        var invalid = Map.of("expectedDraftVersion", saved.path("draftVersion").asLong(),
                "operationId", UUID.randomUUID().toString(), "changes", List.of(changes.get(0),
                        Map.of("kind", "ADD", "functionalSyntax", "SubClassOf(")));
        call("PATCH", "/ontologies/" + id + "/draft/axioms", "member", workspace, invalid, 422);
        assertEquals(saved, call("GET", "/ontologies/" + id + "/draft", "member", workspace, null, 200));
        var changed = call("PATCH", "/ontologies/" + id + "/draft/axioms", "member", workspace, edit, 200);
        assertEquals(changed, call("PATCH", "/ontologies/" + id + "/draft/axioms", "member", workspace, edit, 200));
        var stale = new HashMap<>(edit); stale.put("operationId", UUID.randomUUID().toString());
        call("PATCH", "/ontologies/" + id + "/draft/axioms", "member", workspace, stale, 409);
        var readback = call("GET", "/ontologies/" + id + "/draft", "member", workspace, null, 200);
        assertEquals(changed, readback);
        Set<String> actualIds = new HashSet<>();
        for (var axiom : readback.path("document").path("axioms")) actualIds.add(axiom.path("axiomId").asText());
        assertFalse(actualIds.contains(axiomId));
        for (var axiom : saved.path("document").path("axioms")) {
            if (!axiom.path("axiomId").asText().equals(axiomId)) assertTrue(actualIds.contains(axiom.path("axiomId").asText()));
        }
        assertEquals(saved.path("document").path("axioms").size(), actualIds.size(), readback.path("document").path("axioms").toString());
        assertEquals(saved.path("document").path("source").path("policy"), readback.path("document").path("source").path("policy"));
        var stored = jdbc.query("SELECT axiom_id FROM mate_semantic_ontology_axiom WHERE revision_id=?", (rs, n) -> rs.getString(1), initial.path("id").asText());
        assertEquals(actualIds, new HashSet<>(stored));
    }

    @Test
    void businessModelEditsMapToOwlWithStableGeneratedTermsAndReplay() throws Exception {
        String id = create();
        var initial = draft(id);
        String originalRestriction = "SubClassOf(<urn:test:Equipment> ObjectMinCardinality(1 <urn:test:uses> <urn:test:Probe>))";
        String originalSomeRestriction = "SubClassOf(<urn:test:Equipment> ObjectSomeValuesFrom(<urn:test:uses> <urn:test:Probe>))";
        String document = "Ontology(<urn:test:model-editor> "
                + "Declaration(Class(<urn:test:Equipment>)) Declaration(Class(<urn:test:Probe>)) Declaration(Class(<urn:test:Valve>)) "
                + "Declaration(ObjectProperty(<urn:test:uses>)) Declaration(ObjectProperty(<urn:test:monitors>)) Declaration(DataProperty(<urn:test:voltage>)) "
                + "Declaration(AnnotationProperty(<urn:test:note>)) "
                + "AnnotationAssertion(<http://www.w3.org/2000/01/rdf-schema#label> <urn:test:Equipment> \"Equipment\") "
                + "AnnotationAssertion(<http://www.w3.org/2000/01/rdf-schema#comment> <urn:test:Equipment> \"keep description\") "
                + "AnnotationAssertion(<urn:test:note> <urn:test:Equipment> \"keep unrelated\") "
                + "ObjectPropertyDomain(<urn:test:uses> ObjectIntersectionOf(<urn:test:Equipment> <urn:test:Probe>)) "
                + originalRestriction + " " + originalSomeRestriction
                + " SubClassOf(<urn:test:Equipment> ObjectSomeValuesFrom(<urn:test:uses> ObjectComplementOf(<urn:test:Probe>))))";
        var saved = call("PUT", "/ontologies/" + id + "/draft", "member", workspace,
                saveBody(initial.path("draftVersion").asLong(), owlDocument(document)), 200);
        String labelAxiomId = null;
        String restrictionAxiomId = null;
        String someRestrictionAxiomId = null;
        String complexDomainAxiomId = null;
        for (var axiom : saved.path("document").path("axioms")) {
            if (axiom.path("rendering").asText().equals(
                    "AnnotationAssertion(rdfs:label <urn:test:Equipment> \"Equipment\")"))
                labelAxiomId = axiom.path("axiomId").asText();
            if (axiom.path("rendering").asText().equals(originalRestriction))
                restrictionAxiomId = axiom.path("axiomId").asText();
            if (axiom.path("rendering").asText().equals(originalSomeRestriction))
                someRestrictionAxiomId = axiom.path("axiomId").asText();
            if (axiom.path("rendering").asText().contains("ObjectPropertyDomain(<urn:test:uses> ObjectIntersectionOf"))
                complexDomainAxiomId = axiom.path("axiomId").asText();
        }
        assertNotNull(labelAxiomId);
        assertNotNull(restrictionAxiomId);
        assertNotNull(someRestrictionAxiomId);
        assertNotNull(complexDomainAxiomId);
        String operationId = "model-" + UUID.randomUUID();
        Map<String, Object> createObject = new HashMap<>();
        createObject.put("kind", "CREATE_TERM"); createObject.put("termKind", "OBJECT"); createObject.put("name", "Pump");
        Map<String, Object> createRelation = new HashMap<>();
        createRelation.put("kind", "CREATE_TERM"); createRelation.put("termKind", "RELATION"); createRelation.put("name", "uses part");
        createRelation.put("domainId", "urn:test:Equipment"); createRelation.put("rangeId", "urn:test:Probe");
        Map<String, Object> rename = new HashMap<>();
        rename.put("kind", "REPLACE_DEFINITION"); rename.put("targetId", "urn:test:Equipment"); rename.put("field", "NAME");
        rename.put("value", "设备"); rename.put("language", "zh"); rename.put("originalAxiomId", labelAxiomId);
        Map<String, Object> restriction = new HashMap<>();
        restriction.put("kind", "REPLACE_RESTRICTION"); restriction.put("targetId", "urn:test:Equipment");
        restriction.put("operator", "MIN"); restriction.put("propertyId", "urn:test:uses"); restriction.put("fillerId", "urn:test:Probe");
        restriction.put("cardinality", 2); restriction.put("originalAxiomId", restrictionAxiomId);
        Map<String, Object> edit = new HashMap<>();
        edit.put("expectedDraftVersion", saved.path("draftVersion").asLong()); edit.put("operationId", operationId);
        edit.put("changes", List.of(createObject, createRelation, rename, restriction));
        var changed = call("POST", "/ontologies/" + id + "/draft/model-edits", "member", workspace, edit, 200);
        assertEquals(changed, call("POST", "/ontologies/" + id + "/draft/model-edits", "member", workspace, edit, 200));
        String changedText = changed.path("document").path("source").path("documentText").asText();
        assertTrue(changedText.contains("ObjectMinCardinality(2 <urn:test:uses> <urn:test:Probe>)"));
        assertTrue(changedText.contains("\"设备\"@zh"));
        assertFalse(changedText.contains("\"Equipment\""));
        assertTrue(changedText.contains("\"keep description\""));
        assertTrue(changedText.contains("\"keep unrelated\""));
        assertTrue(changedText.contains("ObjectIntersectionOf(<urn:test:Equipment> <urn:test:Probe>)"));
        assertTrue(changed.path("document").path("axioms").toString().contains("/model-term/"));
        assertEquals(3, changed.path("draftVersion").asLong());
        String unrelatedAxiomId = null;
        String nestedRestrictionAxiomId = null;
        for (var axiom : changed.path("document").path("axioms")) {
            if (axiom.path("rendering").asText().contains("<urn:test:note> <urn:test:Equipment>"))
                unrelatedAxiomId = axiom.path("axiomId").asText();
            if (axiom.path("rendering").asText().contains("ObjectSomeValuesFrom(<urn:test:uses> ObjectComplementOf"))
                nestedRestrictionAxiomId = axiom.path("axiomId").asText();
        }
        assertNotNull(unrelatedAxiomId);
        assertNotNull(nestedRestrictionAxiomId);
        call("POST", "/ontologies/" + id + "/draft/model-edits", "member", workspace,
                Map.of("expectedDraftVersion", changed.path("draftVersion").asLong(), "operationId", UUID.randomUUID().toString(),
                        "changes", List.of(Map.of("kind", "REPLACE_DEFINITION", "targetId", "urn:test:Equipment",
                                "field", "NAME", "value", "wrong original", "originalAxiomId", unrelatedAxiomId))), 422);
        call("POST", "/ontologies/" + id + "/draft/model-edits", "member", workspace,
                Map.of("expectedDraftVersion", changed.path("draftVersion").asLong(), "operationId", UUID.randomUUID().toString(),
                        "changes", List.of(Map.of("kind", "REPLACE_RESTRICTION", "targetId", "urn:test:Equipment",
                                "operator", "SOME", "propertyId", "urn:test:uses", "fillerId", "urn:test:Probe",
                                "originalAxiomId", nestedRestrictionAxiomId))), 422);
        call("POST", "/ontologies/" + id + "/draft/model-edits", "member", workspace,
                Map.of("expectedDraftVersion", changed.path("draftVersion").asLong(), "operationId", UUID.randomUUID().toString(),
                        "changes", List.of(Map.of("kind", "REPLACE_DEFINITION", "targetId", "urn:test:voltage",
                                "termKind", "RELATION", "field", "DOMAIN", "value", "urn:test:Equipment"))), 422);
        call("POST", "/ontologies/" + id + "/draft/model-edits", "member", workspace,
                Map.of("expectedDraftVersion", changed.path("draftVersion").asLong(), "operationId", UUID.randomUUID().toString(),
                        "changes", List.of(Map.of("kind", "REPLACE_DEFINITION", "targetId", "urn:test:uses",
                                "termKind", "RELATION", "field", "DOMAIN", "value", "urn:test:Equipment",
                                "originalAxiomId", complexDomainAxiomId))), 422);
        call("POST", "/ontologies/" + id + "/draft/model-edits", "member", workspace,
                Map.of("expectedDraftVersion", changed.path("draftVersion").asLong(), "operationId", UUID.randomUUID().toString(),
                        "changes", List.of(Map.of("kind", "REPLACE_DEFINITION", "targetId", "urn:test:Equipment",
                                "field", "PARENT", "value", "urn:test:voltage"))), 422);
        call("POST", "/ontologies/" + id + "/draft/model-edits", "member", workspace,
                Map.of("expectedDraftVersion", changed.path("draftVersion").asLong(), "operationId", UUID.randomUUID().toString(),
                        "changes", List.of(Map.of("kind", "CREATE_TERM", "termKind", "RELATION", "name", "bad relation",
                                "domainId", "urn:test:voltage", "rangeId", "urn:test:Probe"))), 422);
        Map<String, Object> changedRestriction = new HashMap<>();
        changedRestriction.put("kind", "REPLACE_RESTRICTION");
        changedRestriction.put("targetId", "urn:test:Equipment");
        changedRestriction.put("operator", "MAX");
        changedRestriction.put("propertyId", "urn:test:monitors");
        changedRestriction.put("fillerId", "urn:test:Valve");
        changedRestriction.put("cardinality", 3);
        changedRestriction.put("originalAxiomId", someRestrictionAxiomId);
        String secondOperationId = UUID.randomUUID().toString();
        var finalChanged = call("POST", "/ontologies/" + id + "/draft/model-edits", "member", workspace,
                Map.of("expectedDraftVersion", changed.path("draftVersion").asLong(), "operationId", secondOperationId,
                        "changes", List.of(changedRestriction)), 200);
        assertTrue(finalChanged.path("document").path("source").path("documentText").asText()
                .contains("ObjectMaxCardinality(3 <urn:test:monitors> <urn:test:Valve>)"));
        assertEquals(finalChanged, call("POST", "/ontologies/" + id + "/draft/model-edits", "member", workspace,
                Map.of("expectedDraftVersion", changed.path("draftVersion").asLong(), "operationId", secondOperationId,
                        "changes", List.of(changedRestriction)), 200));
        call("POST", "/ontologies/" + id + "/draft/model-edits", "member", workspace,
                Map.of("expectedDraftVersion", finalChanged.path("draftVersion").asLong(), "operationId", UUID.randomUUID().toString(),
                        "changes", List.of(Map.of("kind", "REPLACE_DEFINITION", "targetId", "urn:test:Equipment\"><", "field", "NAME", "value", "bad"))), 422);
        assertEquals(finalChanged, call("GET", "/ontologies/" + id + "/draft", "member", workspace, null, 200));
        call("POST", "/ontologies/" + id + "/draft/model-edits", "member", workspace,
                Map.of("expectedDraftVersion", finalChanged.path("draftVersion").asLong() - 1, "operationId", UUID.randomUUID().toString(),
                        "changes", List.of(Map.of("kind", "REPLACE_DEFINITION", "targetId", "urn:test:Equipment", "field", "NAME", "value", "stale"))), 409);
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
        checkPassed(first, 2);
        checkPassed(second, 2);
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
        checkPassed(id, secondVersion);
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
                                409)
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
        checkPassed(id, 2);
        String op = "concurrent-" + UUID.randomUUID();
        barrier.reset();
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<com.fasterxml.jackson.databind.JsonNode> publish =
                    () -> {
                        barrier.await();
                        return call("POST", "/ontologies/" + id + "/draft/publish", "owner", workspace, publishBody(2, op), 200);
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
    private void checkPassed(String ontology, long version) throws Exception {
        assertTrue(call("POST", "/ontologies/" + ontology + "/draft/validate", "member", workspace,
                Map.of("expectedDraftVersion", version), 200).path("valid").asBoolean());
    }

}

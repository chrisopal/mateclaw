package vip.mate.semantic;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import vip.mate.semantic.support.SemanticHttpFixture;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Acceptance slice for staged non-empty OWL graph migration and append-only rollback. */
class SemanticGraphMigrationIntegrationTest extends SemanticHttpFixture {
    @Test
    void nestedPunnedAssertionsSurviveMigrationAndRollback() throws Exception {
        String kb = createKnowledgeBase(), ontology = create();
        String sourceText = "Ontology(<urn:test:roles> Declaration(Class(<urn:test:X>)) Declaration(NamedIndividual(<urn:test:X>)) Declaration(ObjectProperty(<urn:test:p>)) Declaration(DataProperty(<urn:test:label>)))";
        String targetText = "Ontology(<urn:test:roles> Declaration(Class(<urn:test:Y>)) Declaration(NamedIndividual(<urn:test:X>)) Declaration(ObjectProperty(<urn:test:q>)) Declaration(DataProperty(<urn:test:label>)))";
        JsonNode d = draft(ontology);
        JsonNode saved = call("PUT", "/ontologies/" + ontology + "/draft", "member", workspace, saveBody(d.path("draftVersion").asLong(), owlDocument(sourceText)), 200);
        JsonNode source = publish(ontology, saved.path("draftVersion").asLong(), "roles-source-" + UUID.randomUUID());
        String graph = call("PUT", "/knowledge-bases/" + kb + "/binding", "owner", workspace,
                Map.of("action", "ENABLE", "revisionId", source.path("id").asText()), 200).path("graphId").asText();
        JsonNode entity = call("POST", "/graphs/" + graph + "/entities", "member", workspace,
                Map.of("iri", "urn:test:X", "assertedTypes", List.of("urn:test:X"), "displayName", "punned"), 200);
        String raw = raw(kb, "verified role fixture");
        JsonNode imported = call("POST", "/graphs/" + graph + "/imports", "member", workspace,
                Map.of("sourceKind", "WIKI_RAW", "sourceRef", raw, "operationId", "roles-import-" + UUID.randomUUID()), 200);
        String evidence = call("POST", "/graphs/" + graph + "/snapshots/" + imported.path("snapshotId").asText() + "/evidence", "member", workspace,
                Map.of("operationId", "roles-evidence-" + UUID.randomUUID(), "startCodePoint", 0, "endCodePoint", 21, "exactQuote", "verified role fixture"), 200).path("id").asText();
        List<String> originals = List.of("ClassAssertion(ObjectIntersectionOf(<urn:test:X> ObjectSomeValuesFrom(<urn:test:p> <urn:test:X>)) <urn:test:X>)",
                "DataPropertyAssertion(<urn:test:label> <urn:test:X> \"urn:test:X\")");
        var ids = new java.util.ArrayList<String>();
        for (String assertion : originals) {
            String id = call("POST", "/graphs/" + graph + "/statements", "member", workspace,
                    Map.of("operationId", "roles-fact-" + UUID.randomUUID(), "subjectId", entity.path("id").asText(), "assertionText", assertion,
                            "validityKind", "INTERVAL", "evidenceIds", List.of(evidence)), 200).path("id").asText();
            call("POST", "/graphs/" + graph + "/statements/" + id + "/review", "owner", workspace,
                    Map.of("expectedRevision", 1, "action", "ACCEPT", "reason", "verified", "operationId", "roles-review-" + UUID.randomUUID()), 200);
            ids.add(id);
        }
        d = draft(ontology);
        saved = call("PUT", "/ontologies/" + ontology + "/draft", "member", workspace, saveBody(d.path("draftVersion").asLong(), owlDocument(targetText)), 200);
        JsonNode target = publish(ontology, saved.path("draftVersion").asLong(), "roles-target-" + UUID.randomUUID());
        long version = call("GET", "/knowledge-bases/" + kb + "/binding", "viewer", workspace, null, 200).path("graphVersion").asLong();
        JsonNode plan = call("POST", "/graphs/" + graph + "/migrations/owl/prepare", "owner", workspace,
                Map.of("operationId", "roles-plan-" + UUID.randomUUID(), "sourceRevisionId", source.path("id").asText(), "targetRevisionId", target.path("id").asText(), "expectedGraphVersion", version,
                        "classes", List.of(Map.of("from", "urn:test:X", "to", "urn:test:Y")), "objectProperties", List.of(Map.of("from", "urn:test:p", "to", "urn:test:q")), "dataProperties", List.of(), "individuals", List.of()), 200);
        assertEquals("PREPARED", plan.path("status").asText(), plan.toString());
        String path = "/graphs/" + graph + "/migrations/owl/" + plan.path("id").asText();
        call("POST", path + "/approve", "owner", workspace, Map.of("operationId", "roles-approve-" + UUID.randomUUID(), "expectedPlanDigest", plan.path("planDigest").asText()), 200);
        JsonNode executed = call("POST", path + "/execute", "owner", workspace, Map.of("operationId", "roles-execute-" + UUID.randomUUID(), "expectedPlanDigest", plan.path("planDigest").asText(), "expectedGraphVersion", version), 200);
        var parser = new vip.mate.semantic.owl.OwlAssertionAdapter();
        List<String> expected = List.of("ClassAssertion(ObjectIntersectionOf(<urn:test:Y> ObjectSomeValuesFrom(<urn:test:q> <urn:test:Y>)) <urn:test:X>)", originals.get(1));
        for (int i = 0; i < ids.size(); i++) {
            String actual = jdbc.queryForObject("SELECT assertion_text FROM mate_semantic_statement_revision WHERE statement_id=? AND revision=3", String.class, ids.get(i));
            assertEquals(parser.parse(expected.get(i)).functionalSyntax(), parser.parse(actual).functionalSyntax());
            assertEquals(target.path("id").asText(), jdbc.queryForObject("SELECT ontology_revision_id FROM mate_semantic_statement_revision WHERE statement_id=? AND revision=3", String.class, ids.get(i)));
            assertEquals(evidence, jdbc.queryForObject("SELECT evidence_id FROM mate_semantic_revision_evidence WHERE statement_id=? AND revision=3", String.class, ids.get(i)));
        }
        assertEquals("urn:test:X", jdbc.queryForObject("SELECT iri FROM mate_semantic_entity WHERE id=?", String.class, entity.path("id").asText()));
        assertEquals("[\"urn:test:Y\"]", jdbc.queryForObject("SELECT asserted_types_json FROM mate_semantic_entity WHERE id=?", String.class, entity.path("id").asText()));
        call("POST", path + "/rollback", "owner", workspace, Map.of("operationId", "roles-rollback-" + UUID.randomUUID(), "expectedPlanDigest", plan.path("planDigest").asText(), "expectedGraphVersion", executed.path("executedGraphVersion").asLong()), 200);
        for (int i = 0; i < ids.size(); i++) {
            assertEquals(parser.parse(originals.get(i)).functionalSyntax(), parser.parse(jdbc.queryForObject("SELECT assertion_text FROM mate_semantic_statement_revision WHERE statement_id=? AND revision=4", String.class, ids.get(i))).functionalSyntax());
            assertEquals(source.path("id").asText(), jdbc.queryForObject("SELECT ontology_revision_id FROM mate_semantic_statement_revision WHERE statement_id=? AND revision=4", String.class, ids.get(i)));
            assertEquals(4, call("GET", "/graphs/" + graph + "/statements/" + ids.get(i) + "/revisions", "owner", workspace, null, 200).path("revisions").size());
        }
    }

    @Test
    void acceptedFactIsMigratedAndRollbackAppendsHistory() throws Exception {
        String kb = createKnowledgeBase();
        String ontology = create();
        JsonNode firstDraft = draft(ontology);
        JsonNode firstSaved = save(ontology, firstDraft.path("draftVersion").asLong());
        JsonNode source = publish(ontology, firstSaved.path("draftVersion").asLong(), "migration-source-" + UUID.randomUUID());
        JsonNode binding = call("PUT", "/knowledge-bases/" + kb + "/binding", "owner", workspace,
                Map.of("action", "ENABLE", "revisionId", source.path("id").asText()), 200);
        String graph = binding.path("graphId").asText();
        JsonNode entity = call("POST", "/graphs/" + graph + "/entities", "member", workspace,
                Map.of("iri", "urn:test:P-101", "assertedTypes", List.of("urn:test:Equipment"), "displayName", "P-101"), 200);
        call("POST", "/graphs/" + graph + "/entities", "member", workspace,
                Map.of("iri", "urn:test:P-102", "assertedTypes", List.of("urn:test:Equipment"), "displayName", "P-102"), 200);
        String raw = raw(kb, "verified voltage 380");
        JsonNode imported = call("POST", "/graphs/" + graph + "/imports", "member", workspace,
                Map.of("sourceKind", "WIKI_RAW", "sourceRef", raw, "operationId", "migration-import-" + UUID.randomUUID()), 200);
        JsonNode evidence = call("POST", "/graphs/" + graph + "/snapshots/" + imported.path("snapshotId").asText() + "/evidence", "member", workspace,
                Map.of("operationId", "migration-evidence-" + UUID.randomUUID(), "startCodePoint", 0, "endCodePoint", 20, "exactQuote", "verified voltage 380"), 200);
        JsonNode candidate = call("POST", "/graphs/" + graph + "/statements", "member", workspace,
                Map.of("operationId", "migration-fact-" + UUID.randomUUID(), "subjectId", entity.path("id").asText(),
                        "assertionText", "DataPropertyAssertion(<urn:test:voltage> <urn:test:P-101> \"380\"^^<http://www.w3.org/2001/XMLSchema#decimal>)",
                        "validityKind", "INTERVAL", "evidenceIds", List.of(evidence.path("id").asText())), 200);
        call("POST", "/graphs/" + graph + "/statements/" + candidate.path("id").asText() + "/review", "owner", workspace,
                Map.of("expectedRevision", 1, "action", "ACCEPT", "reason", "verified", "operationId", "migration-accept-" + UUID.randomUUID()), 200);

        JsonNode secondDraft = draft(ontology);
        JsonNode secondSaved = call("PUT", "/ontologies/" + ontology + "/draft", "member", workspace,
                saveBody(secondDraft.path("draftVersion").asLong(), targetDocument()), 200);
        JsonNode target = publish(ontology, secondSaved.path("draftVersion").asLong(), "migration-target-" + UUID.randomUUID());
        long graphVersion = call("GET", "/knowledge-bases/" + kb + "/binding", "viewer", workspace, null, 200).path("graphVersion").asLong();
        JsonNode targets = call("GET", "/graphs/" + graph + "/migrations/owl/targets", "viewer", workspace, null, 200);
        assertTrue(java.util.stream.StreamSupport.stream(targets.spliterator(), false).anyMatch(v -> target.path("id").asText().equals(v.path("id").asText())));

        Map<String, Object> prepareRequest = Map.of("operationId", "migration-prepare-" + UUID.randomUUID(),
                "sourceRevisionId", source.path("id").asText(), "targetRevisionId", target.path("id").asText(),
                "expectedGraphVersion", graphVersion, "classes", List.of(), "objectProperties", List.of(), "dataProperties", List.of(),
                "individuals", List.of(Map.of("from", "urn:test:P-101", "to", "urn:test:P-102"),
                        Map.of("from", "urn:test:P-102", "to", "urn:test:P-101")));
        var wrongKind = new java.util.LinkedHashMap<String, Object>(prepareRequest);
        wrongKind.put("operationId", "wrong-kind-" + UUID.randomUUID());
        wrongKind.put("objectProperties", List.of(Map.of("from", "urn:test:unused", "to", "urn:test:voltage")));
        JsonNode invalidMapping = call("POST", "/graphs/" + graph + "/migrations/owl/prepare", "owner", workspace, wrongKind, 200);
        assertEquals("BLOCKED", invalidMapping.path("status").asText());
        assertTrue(invalidMapping.path("impact").path("blockers").toString().contains("UNKNOWN_MAPPING_TARGET"));
        call("POST", "/graphs/" + graph + "/migrations/owl/prepare", "owner", otherWorkspace, prepareRequest, 404);
        call("POST", "/graphs/" + graph + "/migrations/owl/prepare", "member", workspace, prepareRequest, 403);
        JsonNode plan = call("POST", "/graphs/" + graph + "/migrations/owl/prepare", "owner", workspace, prepareRequest, 200);
        assertEquals("PREPARED", plan.path("status").asText(), plan.toString());
        JsonNode replay = call("POST", "/graphs/" + graph + "/migrations/owl/prepare", "owner", workspace, prepareRequest, 200);
        assertEquals(plan, replay);
        var changedReplay = new java.util.LinkedHashMap<String, Object>(prepareRequest);
        changedReplay.put("individuals", List.of());
        call("POST", "/graphs/" + graph + "/migrations/owl/prepare", "owner", workspace, changedReplay, 409);

        String planId = plan.path("id").asText();
        String digest = plan.path("planDigest").asText();
        jdbc.update("UPDATE mate_semantic_ontology_revision SET available_for_new_bindings=FALSE WHERE id=?", target.path("id").asText());
        call("POST", "/graphs/" + graph + "/migrations/owl/" + planId + "/approve", "owner", workspace,
                Map.of("operationId", "disabled-target-" + UUID.randomUUID(), "expectedPlanDigest", digest), 409);
        jdbc.update("UPDATE mate_semantic_ontology_revision SET available_for_new_bindings=TRUE WHERE id=?", target.path("id").asText());

        JsonNode approved = call("POST", "/graphs/" + graph + "/migrations/owl/" + planId + "/approve", "owner", workspace,
                Map.of("operationId", "migration-approve-" + UUID.randomUUID(), "expectedPlanDigest", digest), 200);
        call("POST", "/graphs/" + graph + "/migrations/owl/" + planId + "/execute", "owner", workspace,
                Map.of("operationId", "migration-execute-missing-version-" + UUID.randomUUID(), "expectedPlanDigest", digest), 400);
        // Simulate out-of-band corruption of either immutable revision's pinned inputs.
        for (String revision : List.of(source.path("id").asText(), target.path("id").asText())) {
            for (String column : List.of("document_digest", "import_lock_digest")) {
                String original = jdbc.queryForObject("SELECT " + column + " FROM mate_semantic_ontology_revision WHERE id=?", String.class, revision);
                try {
                    jdbc.update("UPDATE mate_semantic_ontology_revision SET " + column + "=? WHERE id=?", "0".repeat(64), revision);
                    call("POST", "/graphs/" + graph + "/migrations/owl/" + planId + "/execute", "owner", workspace,
                            Map.of("operationId", "changed-digest-" + UUID.randomUUID(), "expectedPlanDigest", digest,
                                    "expectedGraphVersion", graphVersion), 409);
                    JsonNode unchanged = call("GET", "/knowledge-bases/" + kb + "/binding", "viewer", workspace, null, 200);
                    assertEquals(source.path("id").asText(), unchanged.path("ontologyRevisionId").asText());
                    assertEquals(graphVersion, unchanged.path("graphVersion").asLong());
                    assertEquals("APPROVED", jdbc.queryForObject("SELECT status FROM mate_semantic_graph_migration_plan WHERE id=?", String.class, planId));
                    assertEquals(2, call("GET", "/graphs/" + graph + "/statements/" + candidate.path("id").asText() + "/revisions", "owner", workspace, null, 200).path("revisions").size());
                } finally {
                    jdbc.update("UPDATE mate_semantic_ontology_revision SET " + column + "=? WHERE id=?", original, revision);
                }
            }
        }
        // Force failure after entity, fact and graph writes, at the final plan-state update.
        String constraint = "migration_failure_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("ALTER TABLE mate_semantic_graph_migration_plan ADD CONSTRAINT " + constraint
                + " CHECK (id <> '" + planId + "' OR status <> 'EXECUTED')");
        try {
            call("POST", "/graphs/" + graph + "/migrations/owl/" + planId + "/execute", "owner", workspace,
                    Map.of("operationId", "migration-injected-failure-" + UUID.randomUUID(),
                            "expectedPlanDigest", digest, "expectedGraphVersion", graphVersion), 500);
            JsonNode afterFailure = call("GET", "/knowledge-bases/" + kb + "/binding", "viewer", workspace, null, 200);
            assertEquals(source.path("id").asText(), afterFailure.path("ontologyRevisionId").asText());
            assertEquals(graphVersion, afterFailure.path("graphVersion").asLong());
            assertEquals("urn:test:P-101", jdbc.queryForObject("SELECT iri FROM mate_semantic_entity WHERE id=?", String.class, entity.path("id").asText()));
            assertEquals(2, call("GET", "/graphs/" + graph + "/statements/" + candidate.path("id").asText() + "/revisions", "owner", workspace, null, 200).path("revisions").size());
            assertEquals("APPROVED", jdbc.queryForObject("SELECT status FROM mate_semantic_graph_migration_plan WHERE id=?", String.class, planId));
        } finally {
            String product = jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<String>) connection -> connection.getMetaData().getDatabaseProductName());
            jdbc.execute("ALTER TABLE mate_semantic_graph_migration_plan DROP " + ("MySQL".equals(product) ? "CHECK " : "CONSTRAINT ") + constraint);
        }
        JsonNode executed = call("POST", "/graphs/" + graph + "/migrations/owl/" + planId + "/execute", "owner", workspace,
                Map.of("operationId", "migration-execute-" + UUID.randomUUID(), "expectedPlanDigest", digest, "expectedGraphVersion", graphVersion), 200);
        assertEquals("EXECUTED", executed.path("status").asText());
        assertEquals(target.path("id").asText(), call("GET", "/knowledge-bases/" + kb + "/binding", "viewer", workspace, null, 200).path("ontologyRevisionId").asText());
        JsonNode history = call("GET", "/graphs/" + graph + "/statements/" + candidate.path("id").asText() + "/revisions", "owner", workspace, null, 200);
        assertEquals(3, history.path("revisions").size());
        assertEquals(target.path("id").asText(), history.path("revisions").get(2).path("ontologyRevisionId").asText());

        long executedVersion = executed.path("executedGraphVersion").asLong();
        JsonNode rolledBack = call("POST", "/graphs/" + graph + "/migrations/owl/" + planId + "/rollback", "owner", workspace,
                Map.of("operationId", "migration-rollback-" + UUID.randomUUID(), "expectedPlanDigest", digest, "expectedGraphVersion", executedVersion), 200);
        assertEquals("ROLLED_BACK", rolledBack.path("status").asText());
        assertEquals(source.path("id").asText(), call("GET", "/knowledge-bases/" + kb + "/binding", "viewer", workspace, null, 200).path("ontologyRevisionId").asText());
        assertEquals(4, call("GET", "/graphs/" + graph + "/statements/" + candidate.path("id").asText() + "/revisions", "owner", workspace, null, 200).path("revisions").size());
        JsonNode change = call("POST", "/graphs/" + graph + "/statements/" + candidate.path("id").asText() + "/changes", "member", workspace,
                Map.of("expectedRevision", 4, "operationId", "post-rollback-change-" + UUID.randomUUID(),
                        "content", Map.of("operationId", "post-rollback-content-" + UUID.randomUUID(),
                                "subjectId", entity.path("id").asText(),
                                "assertionText", "DataPropertyAssertion(<urn:test:voltage> <urn:test:P-101> \"381\"^^<http://www.w3.org/2001/XMLSchema#decimal>)",
                                "validityKind", "INTERVAL", "evidenceIds", List.of(evidence.path("id").asText()))), 200);
        call("POST", "/graphs/" + graph + "/changes/" + change.path("id").asText() + "/review", "owner", workspace,
                Map.of("expectedRevision", 4, "action", "ACCEPT", "reason", "post-rollback correction",
                        "operationId", "post-rollback-review-" + UUID.randomUUID()), 200);
        JsonNode continued = call("GET", "/graphs/" + graph + "/statements/" + candidate.path("id").asText() + "/revisions", "owner", workspace, null, 200);
        assertEquals(5, continued.path("revisions").size());
        assertEquals(source.path("id").asText(), continued.path("revisions").get(4).path("ontologyRevisionId").asText());

    }

    @Test
    void targetSingleValuePolicyBlocksConflictingMigratedFacts() throws Exception {
        String kb = createKnowledgeBase();
        String ontology = create();
        JsonNode firstDraft = draft(ontology);
        JsonNode firstSaved = save(ontology, firstDraft.path("draftVersion").asLong());
        JsonNode source = publish(ontology, firstSaved.path("draftVersion").asLong(), "migration-policy-source-" + UUID.randomUUID());
        JsonNode binding = call("PUT", "/knowledge-bases/" + kb + "/binding", "owner", workspace,
                Map.of("action", "ENABLE", "revisionId", source.path("id").asText()), 200);
        String graph = binding.path("graphId").asText();
        JsonNode entity = call("POST", "/graphs/" + graph + "/entities", "member", workspace,
                Map.of("iri", "urn:test:P-201", "assertedTypes", List.of("urn:test:Equipment"), "displayName", "P-201"), 200);
        String raw = raw(kb, "verified voltage values");
        JsonNode imported = call("POST", "/graphs/" + graph + "/imports", "member", workspace,
                Map.of("sourceKind", "WIKI_RAW", "sourceRef", raw, "operationId", "migration-policy-import-" + UUID.randomUUID()), 200);
        JsonNode evidence = call("POST", "/graphs/" + graph + "/snapshots/" + imported.path("snapshotId").asText() + "/evidence", "member", workspace,
                Map.of("operationId", "migration-policy-evidence-" + UUID.randomUUID(), "startCodePoint", 0, "endCodePoint", 23, "exactQuote", "verified voltage values"), 200);
        for (String value : List.of("380", "381")) {
            JsonNode candidate = call("POST", "/graphs/" + graph + "/statements", "member", workspace,
                    Map.of("operationId", "migration-policy-fact-" + value + "-" + UUID.randomUUID(), "subjectId", entity.path("id").asText(),
                            "assertionText", "DataPropertyAssertion(<urn:test:voltage> <urn:test:P-201> \"" + value + "\"^^<http://www.w3.org/2001/XMLSchema#decimal>)",
                            "validityKind", "INTERVAL", "evidenceIds", List.of(evidence.path("id").asText())), 200);
            call("POST", "/graphs/" + graph + "/statements/" + candidate.path("id").asText() + "/review", "owner", workspace,
                    Map.of("expectedRevision", 1, "action", "ACCEPT", "reason", "verified", "operationId", "migration-policy-accept-" + value + "-" + UUID.randomUUID()), 200);
        }
        JsonNode secondDraft = draft(ontology);
        JsonNode secondSaved = call("PUT", "/ontologies/" + ontology + "/draft", "member", workspace,
                saveBody(secondDraft.path("draftVersion").asLong(), targetDocumentWithSingleValue()), 200);
        JsonNode target = publish(ontology, secondSaved.path("draftVersion").asLong(), "migration-policy-target-" + UUID.randomUUID());
        long graphVersion = call("GET", "/knowledge-bases/" + kb + "/binding", "viewer", workspace, null, 200).path("graphVersion").asLong();
        JsonNode plan = call("POST", "/graphs/" + graph + "/migrations/owl/prepare", "owner", workspace,
                Map.of("operationId", "migration-policy-prepare-" + UUID.randomUUID(), "sourceRevisionId", source.path("id").asText(),
                        "targetRevisionId", target.path("id").asText(), "expectedGraphVersion", graphVersion,
                        "classes", List.of(), "objectProperties", List.of(), "dataProperties", List.of(), "individuals", List.of()), 200);
        assertEquals("BLOCKED", plan.path("status").asText(), plan.toString());
        assertTrue(plan.path("impact").path("blockers").toString().contains("BUSINESS_SINGLE_VALUE"), plan.toString());
    }

    @Test
    void targetPendingSourceReviewBlocksPrepare() throws Exception {
        String kb = createKnowledgeBase();
        String ontology = create();
        JsonNode d = draft(ontology);
        JsonNode saved = save(ontology, d.path("draftVersion").asLong());
        JsonNode source = publish(ontology, saved.path("draftVersion").asLong(), "source-" + UUID.randomUUID());
        String graph = call("PUT", "/knowledge-bases/" + kb + "/binding", "owner", workspace,
                Map.of("action", "ENABLE", "revisionId", source.path("id").asText()), 200).path("graphId").asText();
        call("POST", "/graphs/" + graph + "/entities", "member", workspace,
                Map.of("iri", "urn:test:pending", "assertedTypes", List.of("urn:test:Equipment"), "displayName", "pending"), 200);
        JsonNode next = draft(ontology);
        next = save(ontology, next.path("draftVersion").asLong());
        String text = "verified specification";
        String rawId = raw(kb, text);
        JsonNode bound = call("POST", "/ontologies/" + ontology + "/draft/axiom-sources", "member", workspace,
                Map.of("expectedDraftVersion", next.path("draftVersion").asLong(), "operationId", "bind-" + UUID.randomUUID(),
                        "axiomId", next.path("document").path("axioms").get(0).path("axiomId").asText(),
                        "knowledgeBaseId", kb, "sourceRef", rawId,
                        "expectedSourceDigest", vip.mate.semantic.core.ontology.OntologyDocument.sha256(text),
                        "startCodePoint", 0, "endCodePoint", text.length(), "exactQuote", text, "origin", "EXPERT"), 200);
        JsonNode target = publish(ontology, bound.path("draftVersion").asLong(), "target-" + UUID.randomUUID());
        long version = call("GET", "/knowledge-bases/" + kb + "/binding", "viewer", workspace, null, 200).path("graphVersion").asLong();
        JsonNode plan = call("POST", "/graphs/" + graph + "/migrations/owl/prepare", "owner", workspace,
                Map.of("operationId", "prepare-" + UUID.randomUUID(), "sourceRevisionId", source.path("id").asText(),
                        "targetRevisionId", target.path("id").asText(), "expectedGraphVersion", version,
                        "classes", List.of(), "objectProperties", List.of(), "dataProperties", List.of(), "individuals", List.of()), 200);
        assertEquals("BLOCKED", plan.path("status").asText());
        assertTrue(plan.path("impact").path("blockers").toString().contains("TARGET_ONTOLOGY_SOURCE_REVIEW_PENDING"));
        JsonNode reviews = call("GET", "/ontologies/" + ontology + "/source-reviews", "owner", workspace, null, 200);
        JsonNode review = reviews.get(0);
        call("POST", "/ontologies/" + ontology + "/source-reviews/" + review.path("id").asText() + "/decision", "owner", workspace,
                Map.of("operationId", "ack-" + UUID.randomUUID(), "expectedObservedDigest", review.path("observedDigest").asText(),
                        "decision", "ACKNOWLEDGE", "reason", "verified original source"), 200);
        JsonNode ready = call("POST", "/graphs/" + graph + "/migrations/owl/prepare", "owner", workspace,
                Map.of("operationId", "ready-" + UUID.randomUUID(), "sourceRevisionId", source.path("id").asText(),
                        "targetRevisionId", target.path("id").asText(), "expectedGraphVersion", version,
                        "classes", List.of(), "objectProperties", List.of(), "dataProperties", List.of(), "individuals", List.of()), 200);
        assertEquals("PREPARED", ready.path("status").asText(), ready.toString());
        // No scan: a reviewed old digest must not authorize a newly edited source.
        jdbc.update("UPDATE mate_wiki_raw_material SET original_content=? WHERE id=?", "changed specification", Long.valueOf(rawId));
        call("POST", "/graphs/" + graph + "/migrations/owl/" + ready.path("id").asText() + "/approve", "owner", workspace,
                Map.of("operationId", "stale-approve-" + UUID.randomUUID(), "expectedPlanDigest", ready.path("planDigest").asText()), 409);
        JsonNode changed = call("POST", "/graphs/" + graph + "/migrations/owl/prepare", "owner", workspace,
                Map.of("operationId", "changed-" + UUID.randomUUID(), "sourceRevisionId", source.path("id").asText(),
                        "targetRevisionId", target.path("id").asText(), "expectedGraphVersion", version,
                        "classes", List.of(), "objectProperties", List.of(), "dataProperties", List.of(), "individuals", List.of()), 200);
        assertEquals("BLOCKED", changed.path("status").asText(), changed.toString());
        assertTrue(changed.path("impact").path("blockers").toString().contains("TARGET_ONTOLOGY_SOURCE_REVIEW_PENDING"));
        jdbc.update("UPDATE mate_wiki_raw_material SET original_content=? WHERE id=?", text, Long.valueOf(rawId));
        call("POST", "/graphs/" + graph + "/migrations/owl/" + ready.path("id").asText() + "/approve", "owner", workspace,
                Map.of("operationId", "approve-restored-" + UUID.randomUUID(), "expectedPlanDigest", ready.path("planDigest").asText()), 200);
        jdbc.update("UPDATE mate_wiki_raw_material SET original_content=? WHERE id=?", "changed after approval", Long.valueOf(rawId));
        call("POST", "/graphs/" + graph + "/migrations/owl/" + ready.path("id").asText() + "/execute", "owner", workspace,
                Map.of("operationId", "execute-stale-source-" + UUID.randomUUID(),
                        "expectedPlanDigest", ready.path("planDigest").asText(), "expectedGraphVersion", version), 409);
        assertEquals(version, call("GET", "/knowledge-bases/" + kb + "/binding", "viewer", workspace, null, 200).path("graphVersion").asLong());

        assertEquals(source.path("id").asText(), call("GET", "/knowledge-bases/" + kb + "/binding", "viewer", workspace, null, 200).path("ontologyRevisionId").asText());

    }

    @Test
    void acceptedFactLimitRejectsBeforeStaging() throws Exception {
        String kb = createKnowledgeBase();
        String ontology = create();
        JsonNode firstDraft = draft(ontology);
        JsonNode firstSaved = save(ontology, firstDraft.path("draftVersion").asLong());
        JsonNode source = publish(ontology, firstSaved.path("draftVersion").asLong(), "migration-source-" + UUID.randomUUID());
        JsonNode binding = call("PUT", "/knowledge-bases/" + kb + "/binding", "owner", workspace,
                Map.of("action", "ENABLE", "revisionId", source.path("id").asText()), 200);
        String graph = binding.path("graphId").asText();
        JsonNode entity = call("POST", "/graphs/" + graph + "/entities", "member", workspace,
                Map.of("iri", "urn:test:P-101", "assertedTypes", List.of("urn:test:Equipment"), "displayName", "P-101"), 200);
        call("POST", "/graphs/" + graph + "/entities", "member", workspace,
                Map.of("iri", "urn:test:P-102", "assertedTypes", List.of("urn:test:Equipment"), "displayName", "P-102"), 200);
        String raw = raw(kb, "verified voltage 380");
        JsonNode imported = call("POST", "/graphs/" + graph + "/imports", "member", workspace,
                Map.of("sourceKind", "WIKI_RAW", "sourceRef", raw, "operationId", "migration-import-" + UUID.randomUUID()), 200);
        JsonNode evidence = call("POST", "/graphs/" + graph + "/snapshots/" + imported.path("snapshotId").asText() + "/evidence", "member", workspace,
                Map.of("operationId", "migration-evidence-" + UUID.randomUUID(), "startCodePoint", 0, "endCodePoint", 20, "exactQuote", "verified voltage 380"), 200);
        JsonNode candidate = call("POST", "/graphs/" + graph + "/statements", "member", workspace,
                Map.of("operationId", "migration-fact-" + UUID.randomUUID(), "subjectId", entity.path("id").asText(),
                        "assertionText", "DataPropertyAssertion(<urn:test:voltage> <urn:test:P-101> \"380\"^^<http://www.w3.org/2001/XMLSchema#decimal>)",
                        "validityKind", "INTERVAL", "evidenceIds", List.of(evidence.path("id").asText())), 200);
        call("POST", "/graphs/" + graph + "/statements/" + candidate.path("id").asText() + "/review", "owner", workspace,
                Map.of("expectedRevision", 1, "action", "ACCEPT", "reason", "verified", "operationId", "migration-accept-" + UUID.randomUUID()), 200);

        JsonNode secondDraft = draft(ontology);
        JsonNode secondSaved = call("PUT", "/ontologies/" + ontology + "/draft", "member", workspace,
                saveBody(secondDraft.path("draftVersion").asLong(), targetDocument()), 200);
        JsonNode target = publish(ontology, secondSaved.path("draftVersion").asLong(), "migration-target-" + UUID.randomUUID());
        String original = candidate.path("id").asText();
        var columns = jdbc.queryForMap("SELECT * FROM mate_semantic_statement_revision WHERE statement_id=? AND revision=2", original).keySet();
        String names = String.join(",", columns);
        String selections = columns.stream().map(column -> column.equalsIgnoreCase("statement_id") ? "?" : column)
                .collect(java.util.stream.Collectors.joining(","));
        var statementRows = new java.util.ArrayList<Object[]>();
        var revisionRows = new java.util.ArrayList<Object[]>();
        for (int i = 0; i < 10000; i++) {
            String id = UUID.randomUUID().toString();
            statementRows.add(new Object[]{id, graph, 2, "test", LocalDateTime.now()});
            revisionRows.add(new Object[]{id, original});
        }
        // Bulk-load a valid accepted revision shape to exercise the migration guard,
        // independently of the public statement-creation limit.
        jdbc.batchUpdate("INSERT INTO mate_semantic_statement(id,graph_id,current_revision,created_by,created_at) VALUES(?,?,?,?,?)", statementRows);
        jdbc.batchUpdate("INSERT INTO mate_semantic_statement_revision(" + names + ") SELECT " + selections
                + " FROM mate_semantic_statement_revision WHERE statement_id=? AND revision=2", revisionRows);
        assertEquals(10001L, jdbc.queryForObject("SELECT COUNT(*) FROM mate_semantic_statement_revision WHERE graph_id=? AND review_status='ACCEPTED'", Long.class, graph));
        long version = call("GET", "/knowledge-bases/" + kb + "/binding", "viewer", workspace, null, 200).path("graphVersion").asLong();
        JsonNode rejected = call("POST", "/graphs/" + graph + "/migrations/owl/prepare", "owner", workspace,
                Map.of("operationId", "fact-limit-" + UUID.randomUUID(), "sourceRevisionId", source.path("id").asText(),
                        "targetRevisionId", target.path("id").asText(), "expectedGraphVersion", version,
                        "classes", List.of(), "objectProperties", List.of(), "dataProperties", List.of(), "individuals", List.of()), 409);
        assertTrue(rejected.toString().contains("GRAPH_STATEMENT_LIMIT"), rejected.toString());
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM mate_semantic_graph_migration_plan WHERE graph_id=?", Long.class, graph));
        JsonNode unchanged = call("GET", "/knowledge-bases/" + kb + "/binding", "viewer", workspace, null, 200);
        assertEquals(version, unchanged.path("graphVersion").asLong());
        assertEquals(source.path("id").asText(), unchanged.path("ontologyRevisionId").asText());
        assertEquals(10001L, jdbc.queryForObject("SELECT COUNT(*) FROM mate_semantic_statement WHERE graph_id=? AND current_revision=2", Long.class, graph));
    }

    @Test
    void revokedAdminCannotApproveOrExecutePreparedPlan() throws Exception {
        String kb = createKnowledgeBase();
        String ontology = create();
        JsonNode d = draft(ontology);
        JsonNode saved = save(ontology, d.path("draftVersion").asLong());
        JsonNode source = publish(ontology, saved.path("draftVersion").asLong(), "limit-source-" + UUID.randomUUID());
        String graph = call("PUT", "/knowledge-bases/" + kb + "/binding", "owner", workspace,
                Map.of("action", "ENABLE", "revisionId", source.path("id").asText()), 200).path("graphId").asText();
        JsonNode next = draft(ontology);
        JsonNode updated = save(ontology, next.path("draftVersion").asLong());
        JsonNode target = publish(ontology, updated.path("draftVersion").asLong(), "limit-target-" + UUID.randomUUID());
        call("POST", "/graphs/" + graph + "/entities", "member", workspace,
                Map.of("iri", "urn:test:permission:1", "assertedTypes", List.of("urn:test:Equipment"), "displayName", "permission"), 200);
        long version = call("GET", "/knowledge-bases/" + kb + "/binding", "viewer", workspace, null, 200).path("graphVersion").asLong();
        JsonNode plan = call("POST", "/graphs/" + graph + "/migrations/owl/prepare", "admin", workspace,
                Map.of("operationId", "permission-" + UUID.randomUUID(), "sourceRevisionId", source.path("id").asText(),
                        "targetRevisionId", target.path("id").asText(), "expectedGraphVersion", version,
                        "classes", List.of(), "objectProperties", List.of(), "dataProperties", List.of(), "individuals", List.of()), 200);
        assertEquals("PREPARED", plan.path("status").asText());
        String path = "/graphs/" + graph + "/migrations/owl/" + plan.path("id").asText();
        Long adminId = workspaces.listMembers(Long.valueOf(workspace)).stream()
                .filter(member -> "admin".equals(member.getRole())).findFirst().orElseThrow().getUserId();
        var approval = Map.of("operationId", "permission-approve-" + UUID.randomUUID(), "expectedPlanDigest", plan.path("planDigest").asText());
        workspaces.updateMemberRole(Long.valueOf(workspace), adminId, "viewer");
        call("POST", path + "/approve", "admin", workspace, approval, 403);
        assertEquals("PREPARED", call("GET", path, "owner", workspace, null, 200).path("status").asText());
        workspaces.updateMemberRole(Long.valueOf(workspace), adminId, "admin");
        call("POST", path + "/approve", "admin", workspace, approval, 200);
        workspaces.updateMemberRole(Long.valueOf(workspace), adminId, "viewer");
        call("POST", path + "/execute", "admin", workspace,
                Map.of("operationId", "permission-execute-" + UUID.randomUUID(), "expectedPlanDigest", plan.path("planDigest").asText(), "expectedGraphVersion", version), 403);
        assertEquals("APPROVED", call("GET", path, "owner", workspace, null, 200).path("status").asText());
        JsonNode unchanged = call("GET", "/knowledge-bases/" + kb + "/binding", "viewer", workspace, null, 200);
        assertEquals(version, unchanged.path("graphVersion").asLong());
        assertEquals(source.path("id").asText(), unchanged.path("ontologyRevisionId").asText());
    }

    @Test
    void entityLimitRejectsBeforeStaging() throws Exception {
        String kb = createKnowledgeBase();
        String ontology = create();
        JsonNode d = draft(ontology);
        JsonNode saved = save(ontology, d.path("draftVersion").asLong());
        JsonNode source = publish(ontology, saved.path("draftVersion").asLong(), "limit-source-" + UUID.randomUUID());
        String graph = call("PUT", "/knowledge-bases/" + kb + "/binding", "owner", workspace,
                Map.of("action", "ENABLE", "revisionId", source.path("id").asText()), 200).path("graphId").asText();
        JsonNode next = draft(ontology);
        JsonNode updated = save(ontology, next.path("draftVersion").asLong());
        JsonNode target = publish(ontology, updated.path("draftVersion").asLong(), "limit-target-" + UUID.randomUUID());
        var rows = new java.util.ArrayList<Object[]>();
        for (int i = 0; i < 1001; i++) {
            String iri = "urn:test:limit:" + i;
            rows.add(new Object[]{UUID.randomUUID().toString(), graph, iri,
                    vip.mate.semantic.core.ontology.OntologyDocument.sha256(iri), "[\"urn:test:Equipment\"]", "limit", "ACTIVE", "test", LocalDateTime.now()});
        }
        jdbc.batchUpdate("INSERT INTO mate_semantic_entity(id,graph_id,iri,iri_digest,asserted_types_json,display_name,status,created_by,created_at) VALUES(?,?,?,?,?,?,?,?,?)", rows);
        long version = call("GET", "/knowledge-bases/" + kb + "/binding", "viewer", workspace, null, 200).path("graphVersion").asLong();
        call("POST", "/graphs/" + graph + "/migrations/owl/prepare", "owner", workspace,
                Map.of("operationId", "limit-" + UUID.randomUUID(), "sourceRevisionId", source.path("id").asText(),
                        "targetRevisionId", target.path("id").asText(), "expectedGraphVersion", version,
                        "classes", List.of(), "objectProperties", List.of(), "dataProperties", List.of(), "individuals", List.of()), 409);
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM mate_semantic_graph_migration_plan WHERE graph_id=?", Long.class, graph));
        assertEquals(version, call("GET", "/knowledge-bases/" + kb + "/binding", "viewer", workspace, null, 200).path("graphVersion").asLong());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void realGraphWritesInvalidateApprovalAndRollback(boolean factWrite) throws Exception {
        String kb = createKnowledgeBase();
        String ontology = create();
        JsonNode d = draft(ontology);
        JsonNode saved = save(ontology, d.path("draftVersion").asLong());
        JsonNode source = publish(ontology, saved.path("draftVersion").asLong(), "cas-source-" + UUID.randomUUID());
        String graph = call("PUT", "/knowledge-bases/" + kb + "/binding", "owner", workspace,
                Map.of("action", "ENABLE", "revisionId", source.path("id").asText()), 200).path("graphId").asText();
        JsonNode subject = call("POST", "/graphs/" + graph + "/entities", "member", workspace,
                Map.of("iri", "urn:test:cas:1", "assertedTypes", List.of("urn:test:Equipment"), "displayName", "first"), 200);
        String raw = raw(kb, "verified voltage 380");
        JsonNode imported = call("POST", "/graphs/" + graph + "/imports", "member", workspace,
                Map.of("sourceKind", "WIKI_RAW", "sourceRef", raw, "operationId", "cas-import-" + UUID.randomUUID()), 200);
        JsonNode evidence = call("POST", "/graphs/" + graph + "/snapshots/" + imported.path("snapshotId").asText() + "/evidence", "member", workspace,
                Map.of("operationId", "cas-evidence-" + UUID.randomUUID(), "startCodePoint", 0, "endCodePoint", 20, "exactQuote", "verified voltage 380"), 200);
        JsonNode next = draft(ontology);
        JsonNode targetSaved = save(ontology, next.path("draftVersion").asLong());
        JsonNode target = publish(ontology, targetSaved.path("draftVersion").asLong(), "cas-target-" + UUID.randomUUID());
        long version = call("GET", "/knowledge-bases/" + kb + "/binding", "viewer", workspace, null, 200).path("graphVersion").asLong();
        var request = new java.util.LinkedHashMap<String,Object>(Map.of("operationId", "cas-prepare-" + UUID.randomUUID(),
                "sourceRevisionId", source.path("id").asText(), "targetRevisionId", target.path("id").asText(),
                "expectedGraphVersion", version, "classes", List.of(), "objectProperties", List.of(), "dataProperties", List.of(), "individuals", List.of()));
        JsonNode stale = call("POST", "/graphs/" + graph + "/migrations/owl/prepare", "owner", workspace, request, 200);
        if (factWrite) appendAcceptedCasFact(graph, subject, evidence, "380");
        else {
        call("POST", "/graphs/" + graph + "/entities", "member", workspace,
                Map.of("iri", "urn:test:cas:2", "assertedTypes", List.of("urn:test:Equipment"), "displayName", "second"), 200);
        }
        call("POST", "/graphs/" + graph + "/migrations/owl/" + stale.path("id").asText() + "/approve", "owner", workspace,
                Map.of("operationId", "cas-stale-" + UUID.randomUUID(), "expectedPlanDigest", stale.path("planDigest").asText()), 409);
        version = call("GET", "/knowledge-bases/" + kb + "/binding", "viewer", workspace, null, 200).path("graphVersion").asLong();
        request.put("operationId", "cas-reprepare-" + UUID.randomUUID()); request.put("expectedGraphVersion", version);
        JsonNode plan = call("POST", "/graphs/" + graph + "/migrations/owl/prepare", "owner", workspace, request, 200);
        String base = "/graphs/" + graph + "/migrations/owl/" + plan.path("id").asText();
        String digest = plan.path("planDigest").asText();
        call("POST", base + "/approve", "owner", workspace, Map.of("operationId", "cas-approve-" + UUID.randomUUID(), "expectedPlanDigest", digest), 200);
        call("POST", base + "/execute", "owner", workspace, Map.of("operationId", "cas-execute-" + UUID.randomUUID(), "expectedPlanDigest", digest, "expectedGraphVersion", version), 200);
        JsonNode additional = factWrite ? appendAcceptedCasFact(graph, subject, evidence, "381")
                : call("POST", "/graphs/" + graph + "/entities", "member", workspace,
                Map.of("iri", "urn:test:cas:3", "assertedTypes", List.of("urn:test:Equipment"), "displayName", "third"), 200);
        JsonNode before = call("GET", "/knowledge-bases/" + kb + "/binding", "viewer", workspace, null, 200);
        call("POST", base + "/rollback", "owner", workspace,
                Map.of("operationId", "cas-rollback-" + UUID.randomUUID(), "expectedPlanDigest", digest, "expectedGraphVersion", before.path("graphVersion").asLong()), 409);
        assertEquals(before, call("GET", "/knowledge-bases/" + kb + "/binding", "viewer", workspace, null, 200));
        assertEquals(target.path("id").asText(), before.path("ontologyRevisionId").asText());
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM " + (factWrite ? "mate_semantic_statement" : "mate_semantic_entity") + " WHERE id=?", Long.class, additional.path("id").asText()));
    }

    @Test
    void foreignOntologyAndUnpublishedTargetsLeaveGraphUnchanged() throws Exception {
        String kb = createKnowledgeBase();
        String ontology = create();
        JsonNode saved = save(ontology, draft(ontology).path("draftVersion").asLong());
        JsonNode source = publish(ontology, saved.path("draftVersion").asLong(), "boundary-source-" + UUID.randomUUID());
        String graph = call("PUT", "/knowledge-bases/" + kb + "/binding", "owner", workspace,
                Map.of("action", "ENABLE", "revisionId", source.path("id").asText()), 200).path("graphId").asText();
        JsonNode entity = call("POST", "/graphs/" + graph + "/entities", "member", workspace,
                Map.of("iri", "urn:test:boundary:1", "assertedTypes", List.of("urn:test:Equipment"), "displayName", "boundary"), 200);
        JsonNode before = call("GET", "/knowledge-bases/" + kb + "/binding", "viewer", workspace, null, 200);
        String other = create();
        JsonNode otherSaved = save(other, draft(other).path("draftVersion").asLong());
        JsonNode foreign = publish(other, otherSaved.path("draftVersion").asLong(), "boundary-foreign-" + UUID.randomUUID());
        JsonNode unpublished = draft(ontology);
        assertFalse(unpublished.path("id").asText().isBlank());
        for (String target : List.of(foreign.path("id").asText(), unpublished.path("id").asText())) {
            call("POST", "/graphs/" + graph + "/migrations/owl/prepare", "owner", workspace,
                    Map.of("operationId", "boundary-prepare-" + UUID.randomUUID(),
                            "sourceRevisionId", source.path("id").asText(), "targetRevisionId", target,
                            "expectedGraphVersion", before.path("graphVersion").asLong(),
                            "classes", List.of(), "objectProperties", List.of(), "dataProperties", List.of(), "individuals", List.of()), 409);
        }
        assertEquals(before, call("GET", "/knowledge-bases/" + kb + "/binding", "viewer", workspace, null, 200));
        assertEquals("urn:test:boundary:1", jdbc.queryForObject("SELECT iri FROM mate_semantic_entity WHERE id=?", String.class, entity.path("id").asText()));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM mate_semantic_graph_migration_plan WHERE graph_id=?", Long.class, graph));
    }

    private JsonNode appendAcceptedCasFact(String graph, JsonNode subject, JsonNode evidence, String value) throws Exception {
        JsonNode fact = call("POST", "/graphs/" + graph + "/statements", "member", workspace,
                Map.of("operationId", "cas-fact-" + UUID.randomUUID(), "subjectId", subject.path("id").asText(),
                        "assertionText", "DataPropertyAssertion(<urn:test:voltage> <urn:test:cas:1> \"" + value + "\"^^<http://www.w3.org/2001/XMLSchema#decimal>)",
                        "validityKind", "INTERVAL", "evidenceIds", List.of(evidence.path("id").asText())), 200);
        call("POST", "/graphs/" + graph + "/statements/" + fact.path("id").asText() + "/review", "owner", workspace,
                Map.of("expectedRevision", 1, "action", "ACCEPT", "reason", "verified concurrent write", "operationId", "cas-review-" + UUID.randomUUID()), 200);
        return fact;
    }

    private Map<String, Object> targetDocument() {
        return owlDocument("""
            Ontology(<urn:test:equipment>
              Declaration(Class(<urn:test:Equipment>))
              Declaration(DataProperty(<urn:test:voltage>))
              Declaration(Class(<urn:test:Calibration>))
              AnnotationAssertion(<http://www.w3.org/2000/01/rdf-schema#label> <urn:test:Equipment> "Equipment")
              AnnotationAssertion(<http://www.w3.org/2000/01/rdf-schema#label> <urn:test:voltage> "Voltage")
              DataPropertyDomain(<urn:test:voltage> <urn:test:Equipment>)
              DataPropertyRange(<urn:test:voltage> <http://www.w3.org/2001/XMLSchema#decimal>))
            """);
    }

    private Map<String, Object> targetDocumentWithSingleValue() {
        var document = new java.util.LinkedHashMap<>(targetDocument());
        document.put("policy", Map.of("version", "single-v1", "rules", List.of(Map.of(
                "classIri", "urn:test:Equipment", "predicateIri", "urn:test:voltage", "required", false,
                "allowedLexicalValues", List.of(), "singleValue", true))));
        return document;
    }

    private String createKnowledgeBase() {
        String id = com.baomidou.mybatisplus.core.toolkit.IdWorker.getIdStr();
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("INSERT INTO mate_wiki_knowledge_base(id,name,description,status,page_count,raw_count,workspace_id,create_time,update_time,deleted) VALUES(?,?,?,?,?,?,?,?,?,0)",
                Long.valueOf(id), "Migration KB", "", "active", 0, 0, Long.valueOf(workspace), now, now);
        return id;
    }

    private String raw(String kb, String text) {
        String id = com.baomidou.mybatisplus.core.toolkit.IdWorker.getIdStr();
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("INSERT INTO mate_wiki_raw_material(id,kb_id,title,source_type,original_content,file_size,processing_status,create_time,update_time,deleted) VALUES(?,?,?,?,?,?,?,?,?,0)",
                Long.valueOf(id), Long.valueOf(kb), "migration source", "text", text, text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length, "completed", now, now);
        return id;
    }
}

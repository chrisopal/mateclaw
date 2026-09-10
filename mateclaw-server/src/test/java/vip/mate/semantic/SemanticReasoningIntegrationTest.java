package vip.mate.semantic;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.databind.JsonNode;

import vip.mate.semantic.core.reasoning.ReasoningRequest.AssertionScope;
import vip.mate.semantic.core.reasoning.ReasoningRequest.TaskKind;
import vip.mate.semantic.reasoning.SemanticReasoningDtos.Request;
import vip.mate.semantic.reasoning.SemanticReasoningDtos.Result;
import vip.mate.semantic.reasoning.SemanticReasoningService;
import vip.mate.semantic.support.SemanticHttpFixture;

/** End-to-end proof that reasoning consumes the authorized graph snapshot. */
class SemanticReasoningIntegrationTest extends SemanticHttpFixture {
    @Autowired SemanticReasoningService reasoning;

    private record Fixture(String ontology, String revision, String graph, String kb, Long agent, String actor) {}

    private Fixture fixture(String document) throws Exception {
        String ontology = create();
        draft(ontology);
        call("PUT", "/ontologies/" + ontology + "/draft", "member", workspace,
                saveBody(1, owlDocument(document)), 200);
        String revision = publish(ontology, 2, UUID.randomUUID().toString()).path("id").asText();
        String kb = IdWorker.getIdStr();
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("INSERT INTO mate_wiki_knowledge_base(id,name,description,status,page_count,raw_count,workspace_id,create_time,update_time,deleted) VALUES(?,?,?,?,?,?,?,?,?,0)",
                Long.valueOf(kb), "Reasoning KB", "", "active", 0, 0, Long.valueOf(workspace), now, now);
        String graph = call("PUT", "/knowledge-bases/" + kb + "/binding", "owner", workspace,
                Map.of("action", "ENABLE", "revisionId", revision), 200).path("graphId").asText();
        String actor = jdbc.queryForObject(
                "SELECT user_id FROM mate_workspace_member WHERE workspace_id=? AND role='member' AND deleted=0",
                String.class, Long.valueOf(workspace));
        return new Fixture(ontology, revision, graph, kb, agentWithKnowledgeBase(kb), actor);
    }

    @Test
    void defaultsToTboxAndKeepsOntologyAboxOptIn() throws Exception {
        Fixture f = fixture("""
                Ontology(<urn:test:reasoning>
                  Declaration(Class(<urn:test:Machine>))
                  Declaration(Class(<urn:test:Equipment>))
                  SubClassOf(<urn:test:Machine> <urn:test:Equipment>)
                  Declaration(NamedIndividual(<urn:test:m1>))
                  ClassAssertion(<urn:test:Machine> <urn:test:m1>))
                """);

        Result tbox = reasoning.reason(workspace, f.actor(), f.agent(), f.graph(),
                new Request(null, TaskKind.CLASSIFICATION, null, null, Instant.parse("2026-01-01T00:00:00Z")));
        assertEquals(AssertionScope.TBOX_ONLY, tbox.scope());
        assertEquals(f.revision(), tbox.ontologyRevisionId());
        assertTrue(tbox.facts().isEmpty());
        assertEquals("HermiT", tbox.engineName());
        assertEquals("CONSISTENT", tbox.status().name(), tbox.diagnostics().toString());

        Result abox = reasoning.reason(workspace, f.actor(), f.agent(), f.graph(),
                new Request(AssertionScope.ONTOLOGY_ABOX, TaskKind.INSTANCE_TYPES, "urn:test:m1", null,
                        Instant.parse("2026-01-01T00:00:00Z")));
        assertEquals(AssertionScope.ONTOLOGY_ABOX, abox.scope());
        assertTrue(abox.individualTypes().stream().anyMatch(item -> item.individualIri().equals("urn:test:m1")
                && item.classIris().contains("urn:test:Machine")), abox.individualTypes().toString());
    }

    @Test
    void acceptedFactsRespectAsOfAndDraftsCannotChangePinnedRevision() throws Exception {
        Fixture f = fixture("""
                Ontology(<urn:test:reasoning-time>
                  Declaration(Class(<urn:test:Equipment>))
                  Declaration(DataProperty(<urn:test:reading>)))
                """);
        JsonNode entity = call("POST", "/graphs/" + f.graph() + "/entities", "member", workspace,
                Map.of("iri", "urn:test:machine", "assertedTypes", List.of("urn:test:Equipment"), "displayName", "Machine"), 200);
        String text = "reading source";
        String raw = IdWorker.getIdStr();
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("INSERT INTO mate_wiki_raw_material(id,kb_id,title,source_type,original_content,file_size,processing_status,create_time,update_time,deleted) VALUES(?,?,?,?,?,?,?,?,?,0)",
                Long.valueOf(raw), Long.valueOf(f.kb()), "Readings", "text", text,
                text.getBytes(StandardCharsets.UTF_8).length, "completed", now, now);
        String snapshot = call("POST", "/graphs/" + f.graph() + "/imports", "member", workspace,
                Map.of("sourceKind", "WIKI_RAW", "sourceRef", raw, "operationId", UUID.randomUUID().toString()), 200)
                .path("snapshotId").asText();
        String evidence = call("POST", "/graphs/" + f.graph() + "/snapshots/" + snapshot + "/evidence", "member", workspace,
                Map.of("operationId", UUID.randomUUID().toString(), "startCodePoint", 0,
                        "endCodePoint", text.codePointCount(0, text.length()), "exactQuote", text), 200)
                .path("id").asText();
        String assertion = "DataPropertyAssertion(<urn:test:reading> <urn:test:machine> \"7\"^^<http://www.w3.org/2001/XMLSchema#integer>)";
        String fact = call("POST", "/graphs/" + f.graph() + "/statements", "member", workspace,
                Map.of("operationId", UUID.randomUUID().toString(), "subjectId", entity.path("id").asText(),
                        "assertionText", assertion, "validityKind", "INTERVAL", "validFrom", "2026-01-01T00:00:00Z",
                        "validTo", "2026-02-01T00:00:00Z", "evidenceIds", List.of(evidence)), 200)
                .path("id").asText();
        call("POST", "/graphs/" + f.graph() + "/statements/" + fact + "/review", "owner", workspace,
                Map.of("expectedRevision", 1, "action", "ACCEPT", "reason", "Verified source",
                        "operationId", UUID.randomUUID().toString()), 200);

        Result active = reasoning.reason(workspace, f.actor(), f.agent(), f.graph(),
                new Request(AssertionScope.ACCEPTED_FACTS, TaskKind.CONSISTENCY, null, null,
                        Instant.parse("2026-01-15T00:00:00Z")));
        assertEquals(1, active.facts().size());
        assertEquals(fact, active.facts().getFirst().factId());
        assertEquals(snapshot, active.facts().getFirst().snapshotId());
        assertEquals(evidence, active.facts().getFirst().evidenceIds().stream().findFirst().orElseThrow());

        Result expired = reasoning.reason(workspace, f.actor(), f.agent(), f.graph(),
                new Request(AssertionScope.ACCEPTED_FACTS, TaskKind.CONSISTENCY, null, null,
                        Instant.parse("2026-03-01T00:00:00Z")));
        assertTrue(expired.facts().isEmpty());

        JsonNode draft = call("POST", "/ontologies/" + f.ontology() + "/draft", "member", workspace,
                Map.of("baseRevisionId", f.revision()), 200);
        assertEquals(200, request("PUT", "/ontologies/" + f.ontology() + "/draft", "member", workspace,
                saveBody(draft.path("draftVersion").asLong(), owlDocument("Ontology(<urn:test:draft> Declaration(Class(<urn:test:DraftOnly>)))"))).getStatus());
        Result afterDraft = reasoning.reason(workspace, f.actor(), f.agent(), f.graph(),
                new Request(null, TaskKind.CLASSIFICATION, null, null, Instant.parse("2026-01-15T00:00:00Z")));
        assertEquals(f.revision(), afterDraft.ontologyRevisionId());
        assertEquals(active.ontologyDocumentDigest(), afterDraft.ontologyDocumentDigest());
    }
}

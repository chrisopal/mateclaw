package vip.mate.semantic;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import vip.mate.semantic.core.reasoning.ReasoningPort;
import vip.mate.semantic.core.reasoning.ReasoningRequest;
import vip.mate.semantic.core.reasoning.ReasoningResult;
import vip.mate.semantic.core.reasoning.ReasoningResult.ReasoningStatus;
import vip.mate.semantic.support.SemanticHttpFixture;

/** HTTP and snapshot-boundary coverage for the draft logical checker. */
class DraftReasoningIntegrationTest extends SemanticHttpFixture {
    @Autowired org.springframework.jdbc.core.JdbcTemplate template;
    @MockitoBean ReasoningPort worker;

    @Test
    void checksTheWholeDraftAboxOutsideTransactionsWithoutWritingOntologyState() throws Exception {
        String ontology = create();
        long version = draft(ontology).path("draftVersion").asLong();
        JsonNode saved = call("PUT", "/ontologies/" + ontology + "/draft", "member", workspace,
                saveBody(version, owlDocument("Ontology(<urn:test:draft-reason> "
                        + "Declaration(Class(<urn:test:Equipment>)) "
                        + "ClassAssertion(<urn:test:Equipment> <urn:test:machine>))")), 200);
        long before = template.queryForObject(
                "SELECT draft_version FROM mate_semantic_ontology_revision WHERE id=?", Long.class,
                saved.path("id").asText());
        AtomicReference<ReasoningRequest> captured = new AtomicReference<>();
        when(worker.reason(any())).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            ReasoningRequest input = invocation.getArgument(0);
            captured.set(input);
            return result(input, ReasoningStatus.CONSISTENT, List.of());
        });

        var checked = call("POST", "/ontologies/" + ontology + "/draft/reason", "member", workspace,
                java.util.Map.of("expectedDraftVersion", saved.path("draftVersion").asLong()), 200);
        assertEquals("CONSISTENT", checked.path("status").asText());
        assertTrue(checked.path("consistent").asBoolean());
        assertTrue(checked.path("unsatisfiableClasses").isArray());
        assertFalse(checked.path("inputDigest").asText().isBlank());
        assertEquals(saved.path("draftVersion").asLong(), checked.path("draftVersion").asLong());
        assertEquals(ReasoningRequest.AssertionScope.ONTOLOGY_ABOX, captured.get().scope());
        assertTrue(captured.get().acceptedFacts().isEmpty());
        assertEquals(before, template.queryForObject(
                "SELECT draft_version FROM mate_semantic_ontology_revision WHERE id=?", Long.class,
                saved.path("id").asText()));
        verify(worker).reason(any());
    }

    @Test
    void rejectsResultWhenDraftChangesDuringWorkerExecution() throws Exception {
        String ontology = create();
        long version = draft(ontology).path("draftVersion").asLong();
        JsonNode saved = call("PUT", "/ontologies/" + ontology + "/draft", "member", workspace,
                saveBody(version, definition()), 200);
        when(worker.reason(any())).thenAnswer(invocation -> {
            ReasoningRequest input = invocation.getArgument(0);
            template.update("UPDATE mate_semantic_ontology_revision SET draft_version=draft_version+1 WHERE id=?",
                    saved.path("id").asText());
            return result(input, ReasoningStatus.CONSISTENT, List.of());
        });
        var response = request("POST", "/ontologies/" + ontology + "/draft/reason", "member", workspace,
                java.util.Map.of("expectedDraftVersion", saved.path("draftVersion").asLong()));
        assertEquals(409, response.getStatus());
        assertTrue(response.getContentAsString().contains("REASONING_STALE"), response.getContentAsString());
    }

    @Test
    void preservesFailureStatesWithUnknownConsistency() throws Exception {
        String ontology = create();
        long version = draft(ontology).path("draftVersion").asLong();
        JsonNode saved = call("PUT", "/ontologies/" + ontology + "/draft", "member", workspace,
                saveBody(version, definition()), 200);
        when(worker.reason(any())).thenAnswer(invocation -> result(invocation.getArgument(0),
                ReasoningStatus.TIMEOUT, List.of("bounded worker timed out")));
        var checked = call("POST", "/ontologies/" + ontology + "/draft/reason", "member", workspace,
                java.util.Map.of("expectedDraftVersion", saved.path("draftVersion").asLong()), 200);
        assertEquals("TIMEOUT", checked.path("status").asText());
        assertTrue(checked.path("consistent").isNull());
        assertEquals("bounded worker timed out", checked.path("message").asText());
    }

    @Test
    void convertsWorkerExceptionToFailedUnknownResult() throws Exception {
        String ontology = create();
        long version = draft(ontology).path("draftVersion").asLong();
        JsonNode saved = call("PUT", "/ontologies/" + ontology + "/draft", "member", workspace,
                saveBody(version, definition()), 200);
        when(worker.reason(any())).thenThrow(new IllegalStateException("worker process failed"));
        var checked = call("POST", "/ontologies/" + ontology + "/draft/reason", "member", workspace,
                java.util.Map.of("expectedDraftVersion", saved.path("draftVersion").asLong()), 200);
        assertEquals("FAILED", checked.path("status").asText());
        assertTrue(checked.path("consistent").isNull());
        assertEquals("worker process failed", checked.path("message").asText());
    }

    @ParameterizedTest
    @EnumSource(value = ReasoningStatus.class, names = {"UNSUPPORTED", "CANCELLED"})
    void preservesUnsupportedAndCancelledStatesAsUnknown(ReasoningStatus status) throws Exception {
        String ontology = create();
        long version = draft(ontology).path("draftVersion").asLong();
        JsonNode saved = call("PUT", "/ontologies/" + ontology + "/draft", "member", workspace,
                saveBody(version, definition()), 200);
        when(worker.reason(any())).thenAnswer(invocation -> result(invocation.getArgument(0), status, List.of()));
        var checked = call("POST", "/ontologies/" + ontology + "/draft/reason", "member", workspace,
                java.util.Map.of("expectedDraftVersion", saved.path("draftVersion").asLong()), 200);
        assertEquals(status.name(), checked.path("status").asText());
        assertTrue(checked.path("consistent").isNull());
    }

    @Test
    void rejectsResultWhenPinnedPolicyChangesDuringWorkerExecution() throws Exception {
        String ontology = create();
        long version = draft(ontology).path("draftVersion").asLong();
        JsonNode saved = call("PUT", "/ontologies/" + ontology + "/draft", "member", workspace,
                saveBody(version, definition()), 200);
        when(worker.reason(any())).thenAnswer(invocation -> {
            var input = (ReasoningRequest) invocation.getArgument(0);
            template.update("UPDATE mate_semantic_ontology_revision SET policy_json=? WHERE id=?",
                    "{\"version\":\"changed\",\"rules\":[]}", saved.path("id").asText());
            return result(input, ReasoningStatus.CONSISTENT, List.of());
        });
        var response = request("POST", "/ontologies/" + ontology + "/draft/reason", "member", workspace,
                java.util.Map.of("expectedDraftVersion", saved.path("draftVersion").asLong()));
        assertEquals(409, response.getStatus());
        assertTrue(response.getContentAsString().contains("REASONING_STALE"), response.getContentAsString());
    }

    @Test
    void requiresMemberRoleAndDraftVersion() throws Exception {
        String ontology = create();
        long version = draft(ontology).path("draftVersion").asLong();
        call("POST", "/ontologies/" + ontology + "/draft/reason", "viewer", workspace,
                java.util.Map.of("expectedDraftVersion", version), 403);
        call("POST", "/ontologies/" + ontology + "/draft/reason", "member", workspace,
                java.util.Map.of("expectedDraftVersion", version + 1), 409);
        verifyNoInteractions(worker);
    }

    private static ReasoningResult result(ReasoningRequest input, ReasoningStatus status,
            List<String> diagnostics) {
        return new ReasoningResult(ReasoningRequest.SCHEMA_VERSION, input.requestId(), status,
                input.task().kind(), "worker-digest", "controlled", "1", 1,
                List.of(), List.of(), diagnostics,
                new ReasoningResult.Provenance(input.scope().name(), input.task().kind().name(),
                        Instant.now(), "controlled-v1"));
    }
}

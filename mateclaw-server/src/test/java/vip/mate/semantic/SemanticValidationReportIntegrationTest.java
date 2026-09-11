package vip.mate.semantic;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import vip.mate.semantic.core.reasoning.ReasoningPort;
import vip.mate.semantic.core.reasoning.ReasoningRequest;
import vip.mate.semantic.core.reasoning.ReasoningResult;
import vip.mate.semantic.core.reasoning.ReasoningResult.ReasoningStatus;
import vip.mate.semantic.support.SemanticHttpFixture;

/** Publication can only consume a complete, current, persisted validation report. */
class SemanticValidationReportIntegrationTest extends SemanticHttpFixture {
    @MockitoBean ReasoningPort worker;

    @Test
    void requiresCompleteReportAndPreservesSuccessfulPublishReplay() throws Exception {
        when(worker.reason(any())).thenAnswer(call -> consistent(call.getArgument(0)));
        String ontology = create();
        var saved = save(ontology, draft(ontology).path("draftVersion").asLong());
        String operation = "publish-" + UUID.randomUUID();

        call("POST", "/ontologies/" + ontology + "/draft/publish", "owner", workspace,
                publishBody(saved.path("draftVersion").asLong(), operation), 409);
        var report = call("POST", "/ontologies/" + ontology + "/draft/validate", "member", workspace,
                Map.of("expectedDraftVersion", saved.path("draftVersion").asLong()), 200);
        assertTrue(report.path("valid").asBoolean());
        assertEquals(4, report.path("checks").size());
        assertFalse(report.path("reportId").asText().isBlank());
        assertFalse(report.path("inputDigest").asText().isBlank());
        assertFalse(report.path("stale").asBoolean());
        assertEquals(report, call("GET", "/ontologies/" + ontology + "/draft/validation", "viewer", workspace, null, 200));

        var published = call("POST", "/ontologies/" + ontology + "/draft/publish", "owner", workspace,
                publishBody(saved.path("draftVersion").asLong(), operation), 200);
        assertEquals(published, call("POST", "/ontologies/" + ontology + "/draft/publish", "owner", workspace,
                publishBody(saved.path("draftVersion").asLong(), operation), 200));
    }

    @Test
    void failedWorkerOutcomeCannotUnlockPublication() throws Exception {
        when(worker.reason(any())).thenAnswer(call -> new ReasoningResult(
                ReasoningRequest.SCHEMA_VERSION, ((ReasoningRequest) call.getArgument(0)).requestId(),
                ReasoningStatus.TIMEOUT, ReasoningRequest.TaskKind.CLASSIFICATION, "worker-digest", "controlled", "1", 1,
                List.of(), List.of(), List.of("timeout"), List.of(),
                new ReasoningResult.Provenance("ONTOLOGY_ABOX", "CLASSIFICATION", Instant.now(), "test")));
        String ontology = create();
        var saved = save(ontology, draft(ontology).path("draftVersion").asLong());
        var report = call("POST", "/ontologies/" + ontology + "/draft/validate", "member", workspace,
                Map.of("expectedDraftVersion", saved.path("draftVersion").asLong()), 200);
        assertFalse(report.path("valid").asBoolean());
        assertEquals("ERROR", report.path("checks").get(1).path("status").asText());
        call("POST", "/ontologies/" + ontology + "/draft/publish", "owner", workspace,
                publishBody(saved.path("draftVersion").asLong(), UUID.randomUUID().toString()), 409);
    }

    private static ReasoningResult consistent(ReasoningRequest request) {
        return new ReasoningResult(ReasoningRequest.SCHEMA_VERSION, request.requestId(), ReasoningStatus.CONSISTENT,
                request.task().kind(), "worker-digest", "controlled", "1", 1, List.of(), List.of(), List.of(), List.of(),
                new ReasoningResult.Provenance("ONTOLOGY_ABOX", "CLASSIFICATION", Instant.now(), "test"));
    }
}

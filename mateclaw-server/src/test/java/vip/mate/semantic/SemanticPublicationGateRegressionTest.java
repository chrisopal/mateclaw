package vip.mate.semantic;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.semantic.authoring.OntologyAuthoringTool;
import vip.mate.semantic.core.ontology.OntologyDocument;
import vip.mate.semantic.core.reasoning.ReasoningPort;
import vip.mate.semantic.core.reasoning.ReasoningRequest;
import vip.mate.semantic.core.reasoning.ReasoningResult;
import vip.mate.semantic.core.reasoning.ReasoningResult.ReasoningStatus;
import vip.mate.semantic.support.SemanticHttpFixture;

/** Regression coverage for the persisted validation report publication gate. */
class SemanticPublicationGateRegressionTest extends SemanticHttpFixture {
    @MockitoBean ReasoningPort worker;
    @Autowired OntologyAuthoringTool authoring;

    private record SourceFixture(String ontology, long draftVersion, String kb, String raw, String text) {}

    @Test
    void savedDraftChangeMakesLatestReportStaleAndRequiresRevalidationBeforePublish() throws Exception {
        org.mockito.Mockito.reset(worker);
        when(worker.reason(any())).thenAnswer(invocation -> consistent(invocation.getArgument(0)));
        String ontology = create();
        JsonNode saved = save(ontology, draft(ontology).path("draftVersion").asLong());

        JsonNode first = validate(ontology, saved.path("draftVersion").asLong());
        assertReportContract(first);
        assertTrue(first.path("valid").asBoolean(), first.toString());
        assertAllChecksPass(first);
        assertFalse(first.path("stale").asBoolean());

        JsonNode changed = call("PUT", "/ontologies/" + ontology + "/draft", "member", workspace,
                saveBody(saved.path("draftVersion").asLong(), owlDocument(
                        "Ontology(<urn:test:equipment> "
                                + "Declaration(Class(<urn:test:Equipment>)) "
                                + "Declaration(Class(<urn:test:Machine>)))")), 200);
        JsonNode latest = call("GET", "/ontologies/" + ontology + "/draft/validation", "viewer", workspace, null, 200);
        assertFalse(latest.path("valid").asBoolean());
        assertTrue(latest.path("stale").asBoolean());
        assertEquals(first.path("reportId"), latest.path("reportId"));
        assertEquals(first.path("inputDigest"), latest.path("inputDigest"));
        assertReportContract(latest);

        call("POST", "/ontologies/" + ontology + "/draft/publish", "owner", workspace,
                publishBody(changed.path("draftVersion").asLong(), UUID.randomUUID().toString()), 409);

        JsonNode second = validate(ontology, changed.path("draftVersion").asLong());
        assertReportContract(second);
        assertTrue(second.path("valid").asBoolean(), second.toString());
        assertAllChecksPass(second);
        assertFalse(second.path("stale").asBoolean());
        assertNotEquals(first.path("reportId"), second.path("reportId"));
        assertNotEquals(first.path("inputDigest"), second.path("inputDigest"));
        publish(ontology, changed.path("draftVersion").asLong(), UUID.randomUUID().toString());
    }

    @Test
    void policyDatabaseDriftDuringWorkerPersistsOnlyAnInvalidStaleReport() throws Exception {
        String ontology = create();
        JsonNode saved = save(ontology, draft(ontology).path("draftVersion").asLong());
        org.mockito.Mockito.reset(worker);
        when(worker.reason(any())).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),
                    "Worker must not hold a database transaction");
            jdbc.update("UPDATE mate_semantic_ontology_revision SET policy_json=? WHERE id=?",
                    "{\"version\":\"drifted\",\"rules\":[]}", saved.path("id").asText());
            return consistent(invocation.getArgument(0));
        });

        JsonNode report = validate(ontology, saved.path("draftVersion").asLong());
        assertReportContract(report);
        assertFalse(report.path("valid").asBoolean(), report.toString());
        assertTrue(report.path("stale").asBoolean(), report.toString());
        JsonNode latest = call("GET", "/ontologies/" + ontology + "/draft/validation", "viewer", workspace, null, 200);
        assertFalse(latest.path("valid").asBoolean());
        assertTrue(latest.path("stale").asBoolean());
        call("POST", "/ontologies/" + ontology + "/draft/publish", "owner", workspace,
                publishBody(saved.path("draftVersion").asLong(), UUID.randomUUID().toString()), 409);
    }

    @Test
    void draftVersionComparisonUsesValueEqualityBeyondIntegerCache() throws Exception {
        org.mockito.Mockito.reset(worker);
        when(worker.reason(any())).thenAnswer(invocation -> consistent(invocation.getArgument(0)));
        String ontology = create();
        JsonNode saved = save(ontology, draft(ontology).path("draftVersion").asLong());
        jdbc.update("UPDATE mate_semantic_ontology_revision SET draft_version=? WHERE id=?",
                256L, saved.path("id").asText());

        JsonNode report = validate(ontology, 256L);
        assertReportContract(report);
        assertEquals(256L, report.path("draftVersion").asLong());
        assertTrue(report.path("valid").asBoolean(), report.toString());
        publish(ontology, 256L, UUID.randomUUID().toString());
    }

    @Test
    void sourceReviewAndMidWorkerDigestChangeKeepPublicationLocked() throws Exception {
        org.mockito.Mockito.reset(worker);
        when(worker.reason(any())).thenAnswer(invocation -> consistent(invocation.getArgument(0)));
        SourceFixture source = sourceFixture();
        JsonNode pendingReview = currentReview(scan(source));
        assertEquals("CURRENT", pendingReview.path("sourceState").asText());
        assertEquals("PENDING", pendingReview.path("reviewState").asText());

        JsonNode pending = validate(source.ontology(), source.draftVersion());
        assertReportContract(pending);
        assertFalse(pending.path("valid").asBoolean(), pending.toString());
        assertFalse(pending.path("stale").asBoolean());
        assertHasCode(check(pending, "SOURCES"), "SOURCE_REVIEW_REQUIRED");

        acknowledge(pendingReview, source);
        JsonNode acknowledged = validate(source.ontology(), source.draftVersion());
        assertTrue(acknowledged.path("valid").asBoolean(), acknowledged.toString());
        assertAllChecksPass(acknowledged);
        assertEquals("PASS", check(acknowledged, "SOURCES").path("status").asText());

        for (String incompleteState : java.util.List.of("PENDING", "STALE")) {
            jdbc.update("UPDATE mate_semantic_ontology_source_review SET review_state=? WHERE id=?",
                    incompleteState, pendingReview.path("id").asText());
            JsonNode incomplete = validate(source.ontology(), source.draftVersion());
            assertFalse(incomplete.path("valid").asBoolean(), incomplete.toString());
            assertHasCode(check(incomplete, "SOURCES"), "SOURCE_REVIEW_REQUIRED");
            call("POST", "/ontologies/" + source.ontology() + "/draft/publish", "owner", workspace,
                    publishBody(source.draftVersion(), UUID.randomUUID().toString()), 409);
        }
        jdbc.update("UPDATE mate_semantic_ontology_source_review SET review_state='REVIEWED' WHERE id=?",
                pendingReview.path("id").asText());

        String originalDigest = acknowledged.path("inputDigest").asText();
        AtomicBoolean mutated = new AtomicBoolean();
        org.mockito.Mockito.reset(worker);
        when(worker.reason(any())).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),
                    "Worker must not hold a database transaction");
            if (mutated.compareAndSet(false, true)) {
                jdbc.update("UPDATE mate_wiki_raw_material SET original_content=? WHERE id=?",
                        "修订后的设备定义", source.raw());
            }
            return consistent(invocation.getArgument(0));
        });
        JsonNode stale = validate(source.ontology(), source.draftVersion());
        assertReportContract(stale);
        assertFalse(stale.path("valid").asBoolean(), stale.toString());
        assertTrue(stale.path("stale").asBoolean(), stale.toString());
        assertEquals(originalDigest, stale.path("inputDigest").asText());
        JsonNode latest = call("GET", "/ontologies/" + source.ontology() + "/draft/validation", "viewer", workspace, null, 200);
        assertFalse(latest.path("valid").asBoolean());
        assertTrue(latest.path("stale").asBoolean());

        org.mockito.Mockito.reset(worker);
        when(worker.reason(any())).thenAnswer(invocation -> consistent(invocation.getArgument(0)));
        JsonNode changed = validate(source.ontology(), source.draftVersion());
        assertReportContract(changed);
        assertFalse(changed.path("valid").asBoolean(), changed.toString());
        assertFalse(changed.path("stale").asBoolean(), changed.toString());
        assertNotEquals(originalDigest, changed.path("inputDigest").asText());
        assertHasCode(check(changed, "SOURCES"), "SOURCE_CHANGED");
        assertHasCode(check(changed, "SOURCES"), "SOURCE_REVIEW_REQUIRED");

        JsonNode changedReview = reviewWithState(scan(source), "CHANGED");
        assertEquals("CHANGED", changedReview.path("sourceState").asText());
        acknowledge(changedReview, source);
        JsonNode stillChanged = validate(source.ontology(), source.draftVersion());
        assertFalse(stillChanged.path("valid").asBoolean(), stillChanged.toString());
        assertEquals("FAIL", check(stillChanged, "SOURCES").path("status").asText());
        assertHasCode(check(stillChanged, "SOURCES"), "SOURCE_CHANGED");
        assertFalse(hasCode(check(stillChanged, "SOURCES"), "SOURCE_REVIEW_REQUIRED"));
        call("POST", "/ontologies/" + source.ontology() + "/draft/publish", "owner", workspace,
                publishBody(source.draftVersion(), UUID.randomUUID().toString()), 409);
    }

    @Test
    void keepHistoricalDecisionCannotBypassChangedSourcePublicationGate() throws Exception {
        org.mockito.Mockito.reset(worker);
        when(worker.reason(any())).thenAnswer(invocation -> consistent(invocation.getArgument(0)));
        SourceFixture source = sourceFixture();
        jdbc.update("UPDATE mate_wiki_raw_material SET original_content=? WHERE id=?",
                "历史版本之外的新来源", source.raw());
        JsonNode changed = reviewWithState(scan(source), "CHANGED");
        assertEquals("CHANGED", changed.path("sourceState").asText());
        JsonNode kept = call("POST", "/ontologies/" + source.ontology() + "/source-reviews/"
                        + changed.path("id").asText() + "/decision", "admin", workspace,
                Map.of("operationId", UUID.randomUUID().toString(),
                        "expectedObservedDigest", changed.path("observedDigest").asText(),
                        "decision", "KEEP_HISTORICAL", "reason", "Retain the immutable historical source"), 200);
        assertEquals("KEEP_HISTORICAL", kept.path("decision").asText());

        JsonNode report = validate(source.ontology(), source.draftVersion());
        assertReportContract(report);
        assertFalse(report.path("valid").asBoolean(), report.toString());
        assertHasCode(check(report, "SOURCES"), "SOURCE_CHANGED");
        assertHasCode(check(report, "SOURCES"), "SOURCE_REVIEW_REQUIRED");
        call("POST", "/ontologies/" + source.ontology() + "/draft/publish", "owner", workspace,
                publishBody(source.draftVersion(), UUID.randomUUID().toString()), 409);
    }

    @Test
    void viewerCannotValidateAndMemberCannotPublish() throws Exception {
        org.mockito.Mockito.reset(worker);
        when(worker.reason(any())).thenAnswer(invocation -> consistent(invocation.getArgument(0)));
        String ontology = create();
        JsonNode saved = save(ontology, draft(ontology).path("draftVersion").asLong());
        call("POST", "/ontologies/" + ontology + "/draft/validate", "viewer", workspace,
                Map.of("expectedDraftVersion", saved.path("draftVersion").asLong()), 403);
        call("POST", "/ontologies/" + ontology + "/draft/publish", "member", workspace,
                publishBody(saved.path("draftVersion").asLong(), UUID.randomUUID().toString()), 403);
    }

    @Test
    void discardingDraftAfterPersistedReportDoesNotViolateReportForeignKey() throws Exception {
        org.mockito.Mockito.reset(worker);
        when(worker.reason(any())).thenAnswer(invocation -> consistent(invocation.getArgument(0)));
        String ontology = create();
        JsonNode saved = save(ontology, draft(ontology).path("draftVersion").asLong());
        JsonNode report = validate(ontology, saved.path("draftVersion").asLong());
        assertTrue(report.path("valid").asBoolean());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM mate_semantic_ontology_validation_report WHERE ontology_id=?",
                Integer.class, ontology));

        call("DELETE", "/ontologies/" + ontology + "/draft?expectedDraftVersion="
                        + saved.path("draftVersion").asLong(), "member", workspace, null, 200);
        call("GET", "/ontologies/" + ontology + "/draft", "viewer", workspace, null, 404);
        JsonNode latest = call("GET", "/ontologies/" + ontology + "/draft/validation", "viewer", workspace, null, 200);
        assertTrue(latest.isNull());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM mate_semantic_ontology_validation_report WHERE ontology_id=?",
                Integer.class, ontology));
    }

    @Test
    void existingAuthoringToolCanPrepareButCannotBypassHumanPublishRole() throws Exception {
        org.mockito.Mockito.reset(worker);
        when(worker.reason(any())).thenAnswer(invocation -> consistent(invocation.getArgument(0)));
        String ontology = create();
        JsonNode saved = save(ontology, draft(ontology).path("draftVersion").asLong());
        ToolContext context = memberToolContext();
        var validation = authoring.semantic_ontology_validate(ontology, saved.path("draftVersion").asLong(), context);
        assertTrue(validation.valid(), validation.toString());
        assertEquals("HUMAN_REVIEW_REQUIRED",
                authoring.semantic_ontology_prepare_publish(ontology, saved.path("draftVersion").asLong(), context)
                        .get("status"));
        call("POST", "/ontologies/" + ontology + "/draft/publish", "member", workspace,
                publishBody(saved.path("draftVersion").asLong(), UUID.randomUUID().toString()), 403);
    }

    private JsonNode validate(String ontology, long draftVersion) throws Exception {
        return call("POST", "/ontologies/" + ontology + "/draft/validate", "member", workspace,
                Map.of("expectedDraftVersion", draftVersion), 200);
    }

    private SourceFixture sourceFixture() throws Exception {
        String ontology = create();
        JsonNode saved = save(ontology, draft(ontology).path("draftVersion").asLong());
        String kb = com.baomidou.mybatisplus.core.toolkit.IdWorker.getIdStr();
        String raw = com.baomidou.mybatisplus.core.toolkit.IdWorker.getIdStr();
        LocalDateTime now = LocalDateTime.now();
        String text = "设备😀用于测量。测针是设备组件。";
        jdbc.update("INSERT INTO mate_wiki_knowledge_base(id,name,description,status,page_count,raw_count,workspace_id,create_time,update_time,deleted) VALUES(?,?,?,?,?,?,?,?,?,0)",
                Long.valueOf(kb), "Publication gate source", "", "active", 0, 0,
                Long.valueOf(workspace), now, now);
        jdbc.update("INSERT INTO mate_wiki_raw_material(id,kb_id,title,source_type,original_content,file_size,processing_status,create_time,update_time,deleted) VALUES(?,?,?,?,?,?,?,?,?,0)",
                Long.valueOf(raw), Long.valueOf(kb), "Expert definitions", "text", text,
                text.getBytes(StandardCharsets.UTF_8).length, "completed", now, now);
        String axiom = saved.path("document").path("axioms").get(0).path("axiomId").asText();
        JsonNode bound = call("POST", "/ontologies/" + ontology + "/draft/axiom-sources", "member", workspace,
                Map.of("expectedDraftVersion", saved.path("draftVersion").asLong(),
                        "operationId", UUID.randomUUID().toString(), "axiomId", axiom,
                        "knowledgeBaseId", kb, "sourceRef", raw,
                        "expectedSourceDigest", OntologyDocument.sha256(text),
                        "startCodePoint", 2, "endCodePoint", 3, "exactQuote", "😀", "origin", "EXTRACTED"), 200);
        return new SourceFixture(ontology, bound.path("draftVersion").asLong(), kb, raw, text);
    }

    private JsonNode scan(SourceFixture source) throws Exception {
        return call("POST", "/ontologies/" + source.ontology() + "/source-reviews/scan", "member", workspace,
                Map.of("operationId", UUID.randomUUID().toString()), 200);
    }

    private JsonNode currentReview(JsonNode reviews) {
        return reviewWithState(reviews, "CURRENT");
    }

    private JsonNode reviewWithState(JsonNode reviews, String state) {
        for (JsonNode review : reviews) {
            if (state.equals(review.path("sourceState").asText())) {
                return review;
            }
        }
        fail("No source review found in " + reviews);
        return null;
    }

    private void acknowledge(JsonNode review, SourceFixture source) throws Exception {
        call("POST", "/ontologies/" + source.ontology() + "/source-reviews/"
                        + review.path("id").asText() + "/decision", "admin", workspace,
                Map.of("operationId", UUID.randomUUID().toString(),
                        "expectedObservedDigest", review.path("observedDigest").asText(),
                        "decision", "ACKNOWLEDGE", "reason", "Administrator reviewed the exact source"), 200);
    }

    private ToolContext memberToolContext() {
        Long user = jdbc.queryForObject(
                "SELECT user_id FROM mate_workspace_member WHERE workspace_id=? AND role='member' AND deleted=0",
                Long.class, Long.valueOf(workspace));
        return ChatOrigin.web("publication-gate", "display", Long.valueOf(workspace), null, null, user)
                .withAgent(agentWithKnowledgeBase("123456")).toToolContext();
    }

    private static ReasoningResult consistent(ReasoningRequest request) {
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),
                "Worker must not hold a database transaction");
        return new ReasoningResult(ReasoningRequest.SCHEMA_VERSION, request.requestId(), ReasoningStatus.CONSISTENT,
                request.task().kind(), "worker-digest", "controlled", "1", 1, List.of(), List.of(), List.of(), List.of(),
                new ReasoningResult.Provenance("ONTOLOGY_ABOX", "CLASSIFICATION", Instant.now(), "test"));
    }

    private static void assertReportContract(JsonNode report) {
        assertTrue(report.path("reportId").isTextual() && !report.path("reportId").asText().isBlank(), report.toString());
        assertTrue(report.path("inputDigest").isTextual() && !report.path("inputDigest").asText().isBlank(), report.toString());
        assertTrue(report.has("stale"), report.toString());
        assertEquals(4, report.path("checks").size(), report.toString());
        for (JsonNode check : report.path("checks")) {
            assertTrue(check.path("kind").isTextual(), check.toString());
            assertTrue(check.path("status").isTextual(), check.toString());
            assertTrue(check.path("violations").isArray(), check.toString());
            assertTrue(check.path("unsatisfiableClasses").isArray(), check.toString());
        }
    }

    private static void assertAllChecksPass(JsonNode report) {
        for (JsonNode check : report.path("checks")) {
            assertEquals("PASS", check.path("status").asText(), report.toString());
        }
    }

    private static JsonNode check(JsonNode report, String kind) {
        for (JsonNode check : report.path("checks")) {
            if (kind.equals(check.path("kind").asText())) return check;
        }
        fail("No " + kind + " check in " + report);
        return null;
    }

    private static void assertHasCode(JsonNode check, String code) {
        assertTrue(hasCode(check, code), "Expected " + code + " in " + check);
    }

    private static boolean hasCode(JsonNode check, String code) {
        for (JsonNode violation : check.path("violations")) {
            if (code.equals(violation.path("code").asText())) return true;
        }
        return false;
    }
}

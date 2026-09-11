package vip.mate.semantic;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vip.mate.semantic.support.SemanticHttpFixture;

/** HTTP coverage for versioned business-policy authoring and draft-only samples. */
class BusinessPolicyIntegrationTest extends SemanticHttpFixture {
    private static final String CLASS = "urn:test:Equipment";
    private static final String PROPERTY = "urn:test:voltage";
    private static final String DECIMAL = "http://www.w3.org/2001/XMLSchema#decimal";

    @Test
    void policyLifecycleUsesDraftCasAndLeavesFormalGraphStateUntouched() throws Exception {
        String ontology = create();
        JsonNode base = save(ontology, draft(ontology).path("draftVersion").asLong());
        long beforeEntities = jdbc.queryForObject("SELECT COUNT(*) FROM mate_semantic_entity", Long.class);
        long beforeGraphs = jdbc.queryForObject("SELECT COUNT(*) FROM mate_semantic_graph", Long.class);
        long beforeStatements = jdbc.queryForObject("SELECT COUNT(*) FROM mate_semantic_statement", Long.class);

        Map<String, Object> rule = rule(true, true, "mm", List.of("380", "380.0"));
        Map<String, Object> body = Map.of("expectedDraftVersion", base.path("draftVersion").asLong(),
                "operationId", "policy-" + UUID.randomUUID(), "rules", List.of(rule));
        JsonNode saved = call("PUT", "/ontologies/" + ontology + "/draft/business-policy", "member", workspace, body, 200);
        assertEquals(List.of(CLASS), List.of(saved.path("document").path("source").path("policy").path("rules").get(0).path("classIri").asText()));
        String policyVersion = saved.path("document").path("source").path("policy").path("version").asText();
        assertFalse(policyVersion.isBlank());

        JsonNode valid = check(ontology, saved.path("draftVersion").asLong(), true,
                Map.of(PROPERTY, List.of(literal("380", "mm"))));
        assertTrue(valid.path("valid").asBoolean(), valid.toString());
        assertEquals(policyVersion, valid.path("policyVersion").asText());

        JsonNode missing = check(ontology, saved.path("draftVersion").asLong(), true, Map.of());
        assertFalse(missing.path("valid").asBoolean(), missing.toString());
        assertHasCode(missing, "BUSINESS_REQUIRED");
        JsonNode partialMissing = check(ontology, saved.path("draftVersion").asLong(), false, Map.of());
        assertTrue(partialMissing.path("valid").asBoolean(), partialMissing.toString());

        JsonNode invalid = check(ontology, saved.path("draftVersion").asLong(), true,
                Map.of(PROPERTY, List.of(literal("381", "cm"))));
        assertFalse(invalid.path("valid").asBoolean(), invalid.toString());
        assertHasCode(invalid, "BUSINESS_UNIT_MISMATCH");
        assertHasCode(invalid, "BUSINESS_VALUE_NOT_ALLOWED");

        JsonNode distinct = check(ontology, saved.path("draftVersion").asLong(), true,
                Map.of(PROPERTY, List.of(literal("380", "mm"), literal("381", "mm"))));
        assertHasCode(distinct, "BUSINESS_SINGLE_VALUE");

        Map<String, Object> staleCheck = new java.util.LinkedHashMap<>(Map.of(
                "expectedDraftVersion", saved.path("draftVersion").asLong() - 1,
                "classIri", CLASS, "completeSubmission", true, "properties", Map.of()));
        call("POST", "/ontologies/" + ontology + "/draft/check-sample", "member", workspace, staleCheck, 409);
        call("PUT", "/ontologies/" + ontology + "/draft/business-policy", "member", workspace,
                new java.util.LinkedHashMap<>(Map.of("expectedDraftVersion", base.path("draftVersion").asLong(),
                        "operationId", UUID.randomUUID().toString(), "rules", List.of(rule))), 409);

        JsonNode replay = call("PUT", "/ontologies/" + ontology + "/draft/business-policy", "member", workspace, body, 200);
        assertEquals(saved, replay);
        call("PUT", "/ontologies/" + ontology + "/draft/business-policy", "viewer", workspace, body, 403);
        call("POST", "/ontologies/" + ontology + "/draft/check-sample", "viewer", workspace,
                Map.of("expectedDraftVersion", saved.path("draftVersion").asLong(), "classIri", CLASS,
                        "completeSubmission", true, "properties", Map.of()), 403);
        call("POST", "/ontologies/" + ontology + "/draft/check-sample", "member", otherWorkspace,
                Map.of("expectedDraftVersion", saved.path("draftVersion").asLong(), "classIri", CLASS,
                        "completeSubmission", true, "properties", Map.of()), 403);

        assertEquals(beforeEntities, jdbc.queryForObject("SELECT COUNT(*) FROM mate_semantic_entity", Long.class));
        assertEquals(beforeGraphs, jdbc.queryForObject("SELECT COUNT(*) FROM mate_semantic_graph", Long.class));
        assertEquals(beforeStatements, jdbc.queryForObject("SELECT COUNT(*) FROM mate_semantic_statement", Long.class));
    }

    private JsonNode check(String ontology, long version, boolean complete, Map<String, Object> properties) throws Exception {
        return call("POST", "/ontologies/" + ontology + "/draft/check-sample", "member", workspace,
                Map.of("expectedDraftVersion", version, "classIri", CLASS,
                        "completeSubmission", complete, "properties", properties), 200);
    }

    private static Map<String, Object> rule(boolean required, boolean single, String unit, List<String> allowed) {
        return Map.of("classIri", CLASS, "predicateIri", PROPERTY, "required", required,
                "unit", unit, "allowedLexicalValues", allowed, "singleValue", single);
    }

    private static Map<String, Object> literal(String value, String unit) {
        return Map.of("lexicalValue", value, "datatypeIri", DECIMAL, "unit", unit);
    }

    private static void assertHasCode(JsonNode result, String code) {
        for (JsonNode violation : result.path("violations")) {
            if (code.equals(violation.path("code").asText())) return;
        }
        fail("Expected " + code + " in " + result);
    }
}

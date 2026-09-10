package vip.mate.semantic;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vip.mate.semantic.support.SemanticHttpFixture;

import static org.junit.jupiter.api.Assertions.*;

/** Complete-object policy validation is explicit and does not change proposal semantics. */
class CompleteObjectValidationIntegrationTest extends SemanticHttpFixture {
    private static final String ENTITY = "urn:test:machine";
    private static final String EQUIPMENT = "urn:test:Equipment";
    private static final String VOLTAGE = "urn:test:voltage";
    private static final String DECIMAL = "http://www.w3.org/2001/XMLSchema#decimal";
    private static final String UNIT = "urn:mateclaw:semantic:unit";

    @Test
    void validatesFullObjectPolicyAndKeepsPartialFactOpenWorld() throws Exception {
        Fixture fixture = fixture();
        String valid = assertion("380", "mm");

        JsonNode accepted = validate(fixture, fixture.graphVersion(), fixture.revision(), List.of(valid));
        assertTrue(accepted.path("valid").asBoolean(), accepted.toString());
        assertEquals("policy-v1", accepted.path("policyVersion").asText());

        JsonNode missing = validate(fixture, fixture.graphVersion(), fixture.revision(),
                List.of());
        assertFalse(missing.path("valid").asBoolean(), missing.toString());
        assertHasCode(missing, "BUSINESS_REQUIRED");

        JsonNode invalid = validate(fixture, fixture.graphVersion(), fixture.revision(),
                List.of(assertion("381", "cm")));
        assertFalse(invalid.path("valid").asBoolean(), invalid.toString());
        assertHasCode(invalid, "BUSINESS_UNIT_MISMATCH");
        assertHasCode(invalid, "BUSINESS_VALUE_NOT_ALLOWED");

        JsonNode valueSpaceEqual = validate(fixture, fixture.graphVersion(), fixture.revision(),
                List.of(assertion("380", "mm"), assertion("380.0", "mm")));
        assertTrue(valueSpaceEqual.path("valid").asBoolean(), valueSpaceEqual.toString());

        JsonNode partial = call("POST", "/graphs/" + fixture.graph() + "/statements", "member", workspace,
                        Map.of("operationId", "partial-" + UUID.randomUUID(), "subjectId", fixture.entityId(),
                        "assertionText", valid, "validityKind", "UNKNOWN"), 200);
        assertEquals("PROPOSED", partial.path("reviewStatus").asText());

        call("POST", "/graphs/" + fixture.graph() + "/objects/complete-validation", "member", workspace,
                Map.of("expectedGraphVersion", fixture.graphVersion() - 1,
                        "expectedOntologyRevisionId", fixture.revision(), "entityId", fixture.entityId(),
                        "assertions", List.of(valid)), 409);
        call("POST", "/graphs/" + fixture.graph() + "/objects/complete-validation", "member", workspace,
                Map.of("expectedGraphVersion", fixture.graphVersion(),
                        "expectedOntologyRevisionId", "stale-revision", "entityId", fixture.entityId(),
                        "assertions", List.of(valid)), 409);
        call("POST", "/graphs/" + fixture.graph() + "/objects/complete-validation", "member", otherWorkspace,
                Map.of("expectedGraphVersion", fixture.graphVersion(),
                        "expectedOntologyRevisionId", fixture.revision(), "entityId", fixture.entityId(),
                        "assertions", List.of(valid)), 403);
    }

    private JsonNode validate(Fixture fixture, long version, String revision, List<String> assertions) throws Exception {
        return call("POST", "/graphs/" + fixture.graph() + "/objects/complete-validation", "member", workspace,
                Map.of("expectedGraphVersion", version, "expectedOntologyRevisionId", revision,
                        "entityId", fixture.entityId(), "assertions", assertions), 200);
    }

    private Fixture fixture() throws Exception {
        String ontology = create();
        JsonNode draft = draft(ontology);
        Map<String, Object> document = new java.util.LinkedHashMap<>(owlDocument("""
                Ontology(<urn:test:equipment>
                  Declaration(Class(<urn:test:Equipment>))
                  Declaration(DataProperty(<urn:test:voltage>))
                  DataPropertyDomain(<urn:test:voltage> <urn:test:Equipment>)
                  DataPropertyRange(<urn:test:voltage> <http://www.w3.org/2001/XMLSchema#decimal>))
                """));
        document.put("policy", Map.of("version", "policy-v1", "rules", List.of(Map.of(
                "classIri", EQUIPMENT, "predicateIri", VOLTAGE, "required", true, "unit", "mm",
                "allowedLexicalValues", List.of("380", "380.0"), "singleValue", true))));
        JsonNode saved = call("PUT", "/ontologies/" + ontology + "/draft", "member", workspace,
                saveBody(draft.path("draftVersion").asLong(), document), 200);
        String revision = publish(ontology, saved.path("draftVersion").asLong(), "publish-" + UUID.randomUUID())
                .path("id").asText();
        String kb = com.baomidou.mybatisplus.core.toolkit.IdWorker.getIdStr();
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("INSERT INTO mate_wiki_knowledge_base(id,name,description,status,page_count,raw_count,workspace_id,create_time,update_time,deleted) VALUES(?,?,?,?,?,?,?,?,?,0)",
                Long.valueOf(kb), "Complete object KB", "", "active", 0, 0, Long.valueOf(workspace), now, now);
        String graph = call("PUT", "/knowledge-bases/" + kb + "/binding", "owner", workspace,
                Map.of("action", "ENABLE", "revisionId", revision), 200).path("graphId").asText();
        JsonNode entity = call("POST", "/graphs/" + graph + "/entities", "member", workspace,
                Map.of("iri", ENTITY, "assertedTypes", List.of(EQUIPMENT), "displayName", "Machine"), 200);
        long graphVersion = call("GET", "/knowledge-bases/" + kb + "/binding", "viewer", workspace, null, 200)
                .path("graphVersion").asLong();
        return new Fixture(revision, graph, entity.path("id").asText(), graphVersion);
    }

    private String assertion(String value, String unit) {
        return "DataPropertyAssertion(Annotation(<" + UNIT + "> \"" + unit + "\") <" + VOLTAGE + "> <"
                + ENTITY + "> \"" + value + "\"^^<" + DECIMAL + ">)";
    }

    private void assertHasCode(JsonNode result, String code) {
        boolean found = false;
        for (JsonNode violation : result.path("violations")) {
            if (code.equals(violation.path("code").asText())) found = true;
        }
        assertTrue(found, () -> "Expected " + code + " in " + result);
    }

    private record Fixture(String revision, String graph, String entityId, long graphVersion) {}
}

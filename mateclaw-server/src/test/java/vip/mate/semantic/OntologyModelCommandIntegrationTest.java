package vip.mate.semantic;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import vip.mate.semantic.support.SemanticHttpFixture;
import vip.mate.semantic.web.OntologyDtos.ModelEdit;

class OntologyModelCommandIntegrationTest extends SemanticHttpFixture {
    private Map<String, Object> object(String clientId, String name) {
        return Map.of("kind", "CREATE_TERM", "termKind", "OBJECT", "clientId", clientId, "name", name);
    }

    @Test void relatedBatchHasFinalAxiomMappingsStableIdsAndExactRetry() throws Exception {
        String id = create();
        var before = draft(id);
        long version = before.path("draftVersion").asLong();
        var request = Map.of("expectedDraftVersion", version, "operationId", UUID.randomUUID().toString(),
                "changes", List.of(
                    Map.of("kind", "CREATE_TERM", "termKind", "RELATION", "clientId", "uses",
                        "name", "使用", "domainId", "$equipment", "rangeId", "$sensor"),
                    object("equipment", "设备"), object("sensor", "传感器"),
                    Map.of("kind", "REPLACE_RESTRICTION", "clientId", "rule", "targetId", "$equipment",
                        "propertyId", "$uses", "fillerId", "$sensor", "operator", "SOME")));
        String endpoint = "/ontologies/" + id + "/draft/model-commands";
        var applied = call("POST", endpoint, "member", workspace, request, 200);
        assertEquals(applied, call("POST", endpoint, "member", workspace, request, 200));
        var saved = applied.path("draft");
        assertEquals(version + 1, saved.path("draftVersion").asLong());
        assertEquals(saved, call("GET", "/ontologies/" + id + "/draft", "viewer", workspace, null, 200));
        Set<String> finalAxioms = new HashSet<>();
        saved.path("document").path("axioms").forEach(a -> finalAxioms.add(a.path("axiomId").asText()));
        assertEquals(4, applied.path("items").size());
        for (var item : applied.path("items")) {
            assertFalse(item.path("axiomIds").isEmpty());
            item.path("axiomIds").forEach(a -> assertTrue(finalAxioms.contains(a.asText()), a.asText()));
        }
        String equipment = applied.path("items").get(1).path("targetId").asText();
        String labelId = null;
        for (var axiom : saved.path("document").path("axioms")) {
            if (axiom.path("rendering").asText().contains("rdfs:label <" + equipment + ">"))
                labelId = axiom.path("axiomId").asText();
        }
        assertNotNull(labelId);
        var renamed = call("POST", endpoint, "member", workspace, Map.of(
                "expectedDraftVersion", version + 1, "operationId", UUID.randomUUID().toString(),
                "changes", List.of(Map.of("kind", "REPLACE_DEFINITION", "targetId", equipment,
                    "field", "NAME", "value", "生产设备", "originalAxiomId", labelId))), 200);
        assertEquals(equipment, renamed.path("items").get(0).path("targetId").asText());
        assertTrue(renamed.path("draft").path("document").toString().contains(equipment));
        assertEquals(applied, call("POST", endpoint, "member", workspace, request, 200));
        call("POST", endpoint, "member", workspace, Map.of("expectedDraftVersion", version,
                "operationId", UUID.randomUUID().toString(), "changes", List.of(object("new", "新对象"))), 409);
        call("POST", endpoint, "viewer", workspace, request, 403);
    }

    @Test void invalidReferencesAndDuplicateIdsFailEntireBatch() throws Exception {
        String id = create(); var before = draft(id);
        String endpoint = "/ontologies/" + id + "/draft/model-commands";
        for (var changes : List.of(
                List.of(object("equipment", "设备"), Map.of("kind", "CREATE_TERM", "termKind", "RELATION",
                    "name", "使用", "domainId", "$equipment", "rangeId", "$missing")),
                List.of(object("duplicate", "设备"), object("duplicate", "传感器")),
                List.of(object("equipment", "设备"), Map.of("kind", "CREATE_TERM", "termKind", "ATTRIBUTE",
                    "name", "错误类型", "domainId", "$equipment", "rangeId", "$equipment")))) {
            call("POST", endpoint, "member", workspace, Map.of(
                    "expectedDraftVersion", before.path("draftVersion").asLong(),
                    "operationId", UUID.randomUUID().toString(), "changes", changes), 422);
            assertEquals(before, call("GET", "/ontologies/" + id + "/draft", "viewer", workspace, null, 200));
        }
    }

    @Test void legacyModelEditSerializationOmitsNewNullClientId() {
        var legacy = new ModelEdit("CREATE_TERM", "OBJECT", null, "设备", null, null,
                null, null, null, null, null, null, null, null);
        assertFalse(json.valueToTree(legacy).has("clientId"));
    }
}

package vip.mate.semantic;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import vip.mate.semantic.support.SemanticHttpFixture;

import java.util.*;

class SemanticOntologyM4IntegrationTest extends SemanticHttpFixture {
    @Test
    void preservesLegacyBytesAndReplayWhileVersionTwoFieldsRoundTrip() throws Exception {
        String id = create();
        var d = draft(id);
        var saved = save(id, d.path("draftVersion").asLong());
        long version = saved.path("draftVersion").asLong();
        String operation = "legacy-" + UUID.randomUUID();
        var published = publish(id, version, operation);
        // Freeze a genuine legacy JSON shape and command result without new fields.
        String legacy = json.writeValueAsString(definition());
        jdbc.update(
                "UPDATE mate_semantic_ontology_revision SET definition_json=? WHERE id=?",
                legacy,
                published.path("id").asText());
        var oldResult = published.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) oldResult)
                .set("definition", json.valueToTree(definition()));
        jdbc.update(
                "UPDATE mate_semantic_command_record SET result_json=? WHERE workspace_id=? AND"
                        + " operation_id=?",
                oldResult.toString(),
                Long.valueOf(workspace),
                operation);
        var replay = publish(id, version, operation);
        assertEquals(1, replay.path("definition").path("definitionFormatVersion").asInt());
        assertEquals(
                legacy,
                jdbc.queryForObject(
                        "SELECT definition_json FROM mate_semantic_ontology_revision WHERE id=?",
                        String.class,
                        published.path("id").asText()));
        var draft2 =
                call(
                        "POST",
                        "/ontologies/" + id + "/draft",
                        "member",
                        workspace,
                        Map.of("baseRevisionId", published.path("id").asText()),
                        200);
        var format2 = enhanced();
        var changed =
                call(
                        "PUT",
                        "/ontologies/" + id + "/draft",
                        "member",
                        workspace,
                        saveBody(draft2.path("draftVersion").asLong(), format2),
                        200);
        long dv = changed.path("draftVersion").asLong();
        assertEquals(
                "0",
                changed.path("definition")
                        .path("properties")
                        .get(0)
                        .path("constraints")
                        .path("minimum")
                        .asText());
        call(
                "PUT",
                "/ontologies/" + id + "/draft",
                "member",
                workspace,
                saveBody(dv, definition()),
                409);
        var verified =
                call(
                        "POST",
                        "/ontologies/" + id + "/draft/validate",
                        "member",
                        workspace,
                        Map.of("expectedDraftVersion", dv),
                        200);
        assertTrue(verified.path("valid").asBoolean());
        var v2 = publish(id, dv, "v2-" + UUID.randomUUID());
        assertEquals(2, v2.path("definition").path("definitionFormatVersion").asInt());
        assertEquals(
                legacy,
                jdbc.queryForObject(
                        "SELECT definition_json FROM mate_semantic_ontology_revision WHERE id=?",
                        String.class,
                        published.path("id").asText()));
    }

    @Test
    void rejectsUnsupportedFormatsAndUnknownOrWrongTypeFieldsWithoutSaving() throws Exception {
        String id = create();
        var draft = draft(id);
        long version = draft.path("draftVersion").asLong();
        for (Object invalid : List.of(3, "2")) {
            var d = new LinkedHashMap<>(definition());
            d.put("definitionFormatVersion", invalid);
            call(
                    "PUT",
                    "/ontologies/" + id + "/draft",
                    "member",
                    workspace,
                    saveBody(version, d),
                    400);
        }
        var unknown = new LinkedHashMap<>(definition());
        unknown.put("script", "ignored?");
        call(
                "PUT",
                "/ontologies/" + id + "/draft",
                "member",
                workspace,
                saveBody(version, unknown),
                400);
        var v2 = enhanced();
        v2.remove("definitionFormatVersion");
        call(
                "PUT",
                "/ontologies/" + id + "/draft",
                "member",
                workspace,
                saveBody(version, v2),
                400);
        assertEquals(
                version,
                call("GET", "/ontologies/" + id + "/draft", "member", workspace, null, 200)
                        .path("draftVersion")
                        .asLong());
    }

    @Test
    void ambiguousAliasesWarnButDoNotBlockPublication() throws Exception {
        String id = create();
        var draft = draft(id);
        var model = enhanced();
        model.put(
                "types",
                List.of(
                        Map.of(
                                "key",
                                "Equipment",
                                "label",
                                "设备",
                                "description",
                                "",
                                "aliases",
                                List.of("设施")),
                        Map.of(
                                "key",
                                "Facility",
                                "label",
                                "设施",
                                "description",
                                "",
                                "aliases",
                                List.of("设施"))));
        var saved =
                call(
                        "PUT",
                        "/ontologies/" + id + "/draft",
                        "member",
                        workspace,
                        saveBody(draft.path("draftVersion").asLong(), model),
                        200);
        var report =
                call(
                        "POST",
                        "/ontologies/" + id + "/draft/validate",
                        "member",
                        workspace,
                        Map.of("expectedDraftVersion", saved.path("draftVersion").asLong()),
                        200);
        assertTrue(report.path("valid").asBoolean());
        assertTrue(report.toString().contains("WARNING"));
        publish(id, saved.path("draftVersion").asLong(), "warning-" + UUID.randomUUID());
    }

    static Map<String, Object> enhanced() {
        return new LinkedHashMap<>(
                Map.of(
                        "definitionFormatVersion",
                        2,
                        "types",
                        List.of(
                                Map.of(
                                        "key",
                                        "Equipment",
                                        "label",
                                        "设备",
                                        "description",
                                        "",
                                        "aliases",
                                        List.of("装备"),
                                        "deprecated",
                                        false)),
                        "properties",
                        List.of(
                                Map.of(
                                        "key",
                                        "voltage",
                                        "label",
                                        "电压",
                                        "description",
                                        "",
                                        "ownerTypeKey",
                                        "Equipment",
                                        "valueType",
                                        "DECIMAL",
                                        "multiplicity",
                                        "SINGLE",
                                        "fixedUnit",
                                        "V",
                                        "constraints",
                                        Map.of("minimum", "0", "maximum", "400"),
                                        "aliases",
                                        List.of("额定电压"))),
                        "relations",
                        List.of()));
    }
}

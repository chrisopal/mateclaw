package vip.mate.presales;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import vip.mate.presales.PresalesDtos.Command;

class PresalesRepairPolicyTest {
    private final ObjectMapper json = new ObjectMapper();
    private final PresalesRepairPolicy policy = new PresalesRepairPolicy(json);

    @ParameterizedTest
    @ValueSource(strings = {"UPDATE_PROJECT", "BIND_MATERIAL", "UNBIND_MATERIAL"})
    void onlyNarrowRepairPayloadsCanBypassRevokedSourceRead(String action) throws Exception {
        String key =
                switch (action) {
                    case "UPDATE_PROJECT" -> "agentId";
                    case "BIND_MATERIAL" -> "kbId";
                    default -> "id";
                };
        var payload = json.createObjectNode().put(key, "9007199254740993001");
        var before = payload.deepCopy();
        assertTrue(policy.allows(command(action, payload)));
        assertEquals(before, payload);
        payload.put("sourceLabel", "private");
        assertFalse(policy.allows(command(action, payload)));
        payload.remove("sourceLabel");
        for (String invalid : List.of("null", "42", "true", "[]", "{}", "\" \"")) {
            payload.set(key, json.readTree(invalid));
            assertFalse(policy.allows(command(action, payload)), invalid);
        }
        assertFalse(policy.allows(command(action, json.createObjectNode())));
        assertFalse(policy.allows(command(action, null)));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "SAVE_REQUIREMENT",
                "APPROVE_BASELINE",
                "PUBLISH_RELEASE",
                "ARCHIVE",
                "unknown",
                ""
            })
    void privilegedAndUnrelatedActionsNeverBecomeRepairCommands(String action) {
        assertFalse(policy.allows(command(action, json.createObjectNode().put("id", "binding"))));
    }

    @Test
    void absentCommandCannotAuthorizeRepair() {
        assertFalse(policy.allows(null));
    }

    @Test
    void bindingRepairStillLeavesValueValidationToTheCommandBoundary() {
        var payload =
                json.createObjectNode()
                        .put("id", "binding")
                        .put("kbId", "1001")
                        .put("graphId", "graph");
        payload.putObject("role").put("text", "unsupported shape");
        assertTrue(policy.allows(command("BIND_MATERIAL", payload)));
        assertThrows(
                PresalesRejected.class,
                () ->
                        PresalesCommandPayload.validate(
                                PresalesDtos.CommandKind.BIND_MATERIAL, payload));
    }

    @Test
    void restrictedViewDoesNotMutateSparseProjectWhileDerivingStage() throws Exception {
        var project =
                (ObjectNode)
                        json.readTree(
                                "{\"id\":\"p\",\"version\":2,\"requirements\":[{\"text\":\"private\"}]}");
        var before = project.deepCopy();
        var view = policy.restrictedView(project);
        assertEquals("REQUIREMENTS", view.path("stage").asText());
        assertEquals(
                before,
                project,
                "Reading a repair view must not materialize collections in its input");
        assertFalse(view.toString().contains("private"));
    }

    @Test
    void viewContainsOnlyMetadataEmptySourceCollectionsAndOpaqueBindings() throws Exception {
        var project =
                (ObjectNode)
                        json.readTree(
                                """
                {"id":"9007199254740993001","workspaceId":"9007199254740993002",
                 "version":2,"name":"Project","stage":null,"customer":null,
                 "privateExtension":{"secret":"private"},
                 "materials":[{"id":"a","kbId":"private","role":"PROJECT","label":"private"},
                 {"id":"b","role":"private"},{"id":" "}],
                 "tasks":[{"result":"private"}],"repairBindings":[{"secret":"private"}]}
                """);
        var before = project.deepCopy();
        var view = policy.restrictedView(project);
        assertEquals(before, project);
        assertEquals("9007199254740993001", view.path("id").asText());
        assertEquals("9007199254740993002", view.path("workspaceId").asText());
        assertTrue(view.has("stage") && view.path("stage").isNull());
        assertTrue(view.has("customer") && view.path("customer").isNull());
        assertEquals(
                json.readTree(
                        "[{\"id\":\"a\",\"role\":\"PROJECT\"},{\"id\":\"b\",\"role\":\"UNKNOWN\"}]"),
                view.path("repairBindings"));
        assertFalse(view.toString().contains("private"));
        for (String collection :
                List.of(
                        "materials",
                        "requirements",
                        "clarifications",
                        "baselines",
                        "fitGaps",
                        "cases",
                        "solutions",
                        "reviews",
                        "reviewDrafts",
                        "releases",
                        "tasks",
                        "contextCards")) {
            assertTrue(
                    view.path(collection).isArray() && view.path(collection).isEmpty(), collection);
        }
        assertTrue(view.path("sourceAccessRestricted").asBoolean());
        view.put("name", "changed");
        ((ObjectNode) view.path("repairBindings").get(0)).put("id", "changed");
        assertEquals(before, project);
    }

    private Command command(String action, ObjectNode payload) {
        return new Command(2, "operation", action, payload);
    }
}

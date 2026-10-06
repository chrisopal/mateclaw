package vip.mate.presales;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;

class PresalesCommandKindsTest {
    @Test
    void allSixteenKnownActionsResolveExactly() {
        var names =
                List.of(
                        "UPDATE_PROJECT",
                        "ARCHIVE",
                        "BIND_MATERIAL",
                        "SAVE_REQUIREMENT",
                        "SAVE_CLARIFICATION",
                        "UNBIND_MATERIAL",
                        "CANCEL_AI_TASK",
                        "SAVE_AI_TASK",
                        "SAVE_CONTEXT",
                        "SAVE_REVIEW",
                        "CREATE_RELEASE",
                        "APPROVE_RELEASE",
                        "PUBLISH_RELEASE",
                        "APPROVE_BASELINE",
                        "SAVE_FIT_GAP",
                        "SAVE_SOLUTION");
        assertEquals(
                new HashSet<>(names),
                Arrays.stream(PresalesDtos.CommandKind.values())
                        .filter(k -> k != PresalesDtos.CommandKind.UNKNOWN)
                        .map(Enum::name)
                        .collect(java.util.stream.Collectors.toSet()));
        for (String raw : names) {
            var action = new PresalesDtos.Command(1L, "op", raw, null).parsedAction();
            assertEquals(PresalesDtos.CommandKind.valueOf(raw), action.kind());
            assertEquals(raw, action.raw());
        }
    }

    @Test
    void unknownIsStructuredWithoutTrimmingOrCaseNormalization() {
        for (String input :
                Arrays.asList(null, "", "unknown", "UNKNOWN", "archive", " ARCHIVE ", "归档")) {
            var action = new PresalesDtos.Command(1L, "op", input, null).parsedAction();
            assertEquals(PresalesDtos.CommandKind.UNKNOWN, action.kind());
            assertEquals(Objects.toString(input, ""), action.raw());
        }
    }

    @Test
    void parsingCannotChangeOriginalFourFieldWireOrItsHash() throws Exception {
        var json = new ObjectMapper();
        var payload =
                json.createObjectNode()
                        .put("legacyExtension", "keep")
                        .put("id", "9007199254740993001");
        for (String raw : Arrays.asList("UPDATE_PROJECT", "unknown", null)) {
            var expected =
                    json.createObjectNode().put("expectedVersion", 3).put("operationId", "op");
            if (raw == null) expected.putNull("action");
            else expected.put("action", raw);
            expected.set("payload", payload);
            var command = json.treeToValue(expected, PresalesDtos.Command.class);
            String before = json.writeValueAsString(command);
            command.parsedAction();
            assertEquals(expected.toString(), before);
            assertEquals(before, json.writeValueAsString(command));
            assertEquals(4, json.valueToTree(command).size());
            assertEquals(payload, command.payload());
        }
    }
}

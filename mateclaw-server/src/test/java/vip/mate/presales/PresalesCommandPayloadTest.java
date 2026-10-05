package vip.mate.presales;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import vip.mate.presales.PresalesDtos.CommandKind;

class PresalesCommandPayloadTest {
    private final ObjectMapper json = new ObjectMapper();

    static Stream<Arguments> valid() {
        return Stream.of(
                Arguments.of(
                        "UPDATE_PROJECT",
                        "{\"name\":\"原文 Ω\",\"customer\":\"\",\"ownerId\":\"9007199254740993001\",\"agentId\":\"\",\"industry\":\"\",\"goal\":\"\"}"),
                Arguments.of("ARCHIVE", "{\"id\":123}"),
                Arguments.of(
                        "BIND_MATERIAL",
                        "{\"id\":\"\",\"kbId\":\"1001\",\"graphId\":\"\",\"role\":null}"),
                Arguments.of(
                        "SAVE_REQUIREMENT",
                        "{\"title\":\"t\",\"description\":\"d\",\"originKind\":\"HISTORICAL\",\"priority\":null,\"scope\":null,\"statementId\":\"id\",\"statementRevision\":\"9007199254740993001\",\"graphId\":\"\",\"proposedByTaskId\":\"\"}"),
                Arguments.of(
                        "SAVE_CLARIFICATION",
                        "{\"question\":\"q\",\"requirementId\":\"\",\"impact\":\"\",\"ownerId\":\"\",\"answer\":\"\",\"answerSourceId\":\"\",\"status\":null}"),
                Arguments.of("UNBIND_MATERIAL", "{\"id\":\"binding\"}"),
                Arguments.of("CANCEL_AI_TASK", "{\"taskId\":\"task\"}"),
                Arguments.of(
                        "SAVE_AI_TASK",
                        "{\"status\":null,\"skill\":\"future\",\"result\":{\"schemaVersion\":\"1\",\"needsHumanReview\":\"true\",\"items\":null},\"contextSnapshot\":{\"operational_record\":[null,123]}}"),
                Arguments.of(
                        "SAVE_CONTEXT",
                        "{\"title\":\"\",\"text\":\"\",\"originKind\":\"future\",\"sourceRefs\":[\" a \",\" a \",\"\"]}"),
                Arguments.of(
                        "SAVE_REVIEW",
                        "{\"solutionId\":\"s\",\"summary\":\"summary\",\"issues\":[{\"description\":\"old\",\"text\":\"new\",\"severity\":null,\"status\":null}]}"),
                Arguments.of("CREATE_RELEASE", "{\"solutionId\":\"s\",\"purpose\":\"\"}"),
                Arguments.of("APPROVE_RELEASE", "{\"releaseId\":\"r\",\"reason\":\"原文\"}"),
                Arguments.of("PUBLISH_RELEASE", "{\"releaseId\":\"r\"}"),
                Arguments.of("APPROVE_BASELINE", "{\"reason\":\"原文\"}"),
                Arguments.of(
                        "SAVE_FIT_GAP",
                        "{\"requirementId\":\"r\",\"status\":null,\"reason\":\"\",\"productVersion\":\"\",\"graphId\":\"\",\"evidenceIds\":[\" a \",\" a \"]}"),
                Arguments.of(
                        "SAVE_SOLUTION",
                        "{\"title\":\"t\",\"baselineId\":\"\",\"baselineVersion\":0,\"sections\":[{\"title\":\"t\",\"text\":\"txt\",\"requirementRefs\":[\"r\",\"r\"],\"sourceRefs\":\"policy-owned\"}],\"requirementResponses\":[{\"requirementId\":\"r\",\"reason\":\"\",\"status\":null,\"evidenceIds\":[]}],\"presentation\":null,\"sourceRefs\":false}"));
    }

    @ParameterizedTest
    @MethodSource("valid")
    void onlyShapesAreCheckedAndOriginalNodesRemainUnmodified(String action, String raw)
            throws Exception {
        var value = (ObjectNode) json.readTree(raw);
        value.putObject("opaqueExtension").put("id", 123).putNull("status");
        String before = value.toString();
        PresalesCommandPayload.validate(CommandKind.valueOf(action), value);
        assertEquals(before, value.toString());
        // Missing mandatory fields still belong to the existing application/domain policy.
        PresalesCommandPayload.validate(CommandKind.valueOf(action), json.createObjectNode());
    }

    static Stream<Arguments> invalid() {
        return Stream.of(
                Arguments.of("SAVE_REQUIREMENT", "{\"id\":null}", "id"),
                Arguments.of("SAVE_REQUIREMENT", "{\"scope\":true}", "scope"),
                Arguments.of("SAVE_REQUIREMENT", "{\"statementRevision\":-1}", "statementRevision"),
                Arguments.of(
                        "SAVE_REQUIREMENT",
                        "{\"statementRevision\":9007199254740992}",
                        "statementRevision"),
                Arguments.of(
                        "SAVE_REQUIREMENT", "{\"statementRevision\":null}", "statementRevision"),
                Arguments.of("SAVE_CONTEXT", "{\"sourceRefs\":null}", "sourceRefs"),
                Arguments.of("SAVE_CONTEXT", "{\"sourceRefs\":[\"valid\",null]}", "sourceRefs[1]"),
                Arguments.of("SAVE_REVIEW", "{\"issues\":[null]}", "issues[0]"),
                Arguments.of(
                        "SAVE_REVIEW", "{\"issues\":[{\"severity\":1}]}", "issues[0].severity"),
                Arguments.of("SAVE_SOLUTION", "{\"sections\":[[]]}", "sections[0]"),
                Arguments.of(
                        "SAVE_SOLUTION", "{\"requirementResponses\":{}}", "requirementResponses"),
                Arguments.of(
                        "SAVE_SOLUTION",
                        "{\"requirementResponses\":[{\"evidenceIds\":[null]}]}",
                        "requirementResponses[0].evidenceIds[0]"),
                Arguments.of("SAVE_AI_TASK", "{\"result\":null}", "result"),
                Arguments.of("SAVE_AI_TASK", "{\"contextSnapshot\":false}", "contextSnapshot"));
    }

    @ParameterizedTest
    @MethodSource("invalid")
    void errorsOnlyExposeDeclaredFieldPaths(String action, String raw, String path)
            throws Exception {
        var error =
                assertThrows(
                        PresalesRejected.class,
                        () ->
                                PresalesCommandPayload.validate(
                                        CommandKind.valueOf(action),
                                        (ObjectNode) json.readTree(raw)));
        assertEquals(400, error.status());
        assertEquals("INVALID_REQUEST", error.code());
        assertEquals("Invalid payload field: " + path, error.getMessage());
    }

    @Test
    void unknownActionsAndRawStringRevisionsRemainForDomainHandling() {
        var unknown = json.createObjectNode().put("id", 123);
        PresalesCommandPayload.validate(CommandKind.UNKNOWN, unknown);
        for (String revision : new String[] {"", "01", "-1", "future"}) {
            var value = json.createObjectNode().put("statementRevision", revision);
            PresalesCommandPayload.validate(CommandKind.SAVE_REQUIREMENT, value);
            assertEquals(revision, value.get("statementRevision").textValue());
        }
    }
}

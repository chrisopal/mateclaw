package vip.mate.bidding;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BiddingSkillValidatorTest {
    private final ObjectMapper json = new ObjectMapper();
    private final BiddingSkillValidator validator = new BiddingSkillValidator();

    @Test void acceptsCompleteProfileWithEvidenceFromReadInput() throws Exception {
        ObjectNode input = object("""
            {"schemaVersion":"1","blocks":[{"id":"b1","text":"2026年10月1日截止","sourceId":"s1","version":1}],"readBlockIds":["b1"]}
            """);
        ObjectNode payload = object("""
            {"schemaVersion":"1","basicInfo":{"project":null,"tenderer":null,"lot":"一标段"},
             "deadlines":[{"name":"投标截止","value":"2026年10月1日","reason":null,"evidenceRefs":[{"sourceId":"s1","version":1,"blockId":"b1","quote":"2026年10月1日"}]}],
             "deliveryConditions":[],"mandatoryOutline":[],"formatRequirements":[],"unknowns":[{"field":"project","reason":"未提供项目名称"},{"field":"tenderer","reason":"未提供招标人"}],
             "coverage":{"processedBlockIds":["b1"],"unprocessedBlockIds":[]},"warnings":[]}
            """);
        assertDoesNotThrow(() -> validator.validate("bidding-tender-profile", payload, input));
    }

    @Test void rejectsMissingCoverageForScoring() throws Exception {
        ObjectNode input = object("""
            {"schemaVersion":"1","blocks":[{"id":"b1","text":"技术项10分","sourceId":"s1","version":1}],"readBlockIds":["b1"]}
            """);
        ObjectNode payload = object("""
            {"schemaVersion":"1","criteria":[],"totalChecks":[],"coverage":{"processedBlockIds":[],"unprocessedBlockIds":[]},"warnings":[]}
            """);
        var error = assertThrows(BiddingApiException.class,
            () -> validator.validate("bidding-scoring-analysis", payload, input));
        assertEquals("COVERAGE_INCOMPLETE", error.code());
    }

    @Test void rejectsFabricatedEvidenceAndUnknownObjectFields() throws Exception {
        ObjectNode input = object("""
            {"schemaVersion":"1","blocks":[{"id":"b1","text":"不得转包","sourceId":"s1","version":1}],"readBlockIds":["b1"]}
            """);
        ObjectNode payload = object("""
            {"schemaVersion":"1","items":[{"id":"i1","text":"不得转包","scope":"全部","trigger":"转包","evidenceRefs":[{"sourceId":"s1","version":1,"blockId":"b1","quote":"不得分包"}],"unknowns":[],"invented":true}],
             "coverage":{"processedBlockIds":["b1"],"unprocessedBlockIds":[]},"warnings":[]}
            """);
        var error = assertThrows(BiddingApiException.class,
            () -> validator.validate("bidding-elimination-analysis", payload, input));
        assertEquals("OUTPUT_SCHEMA_INVALID", error.code());
        assertTrue(error.getMessage().contains("/items/0/invented"));
    }

    @Test void rejectsCoverageThatClaimsAnUnreadBlockWasProcessed() throws Exception {
        ObjectNode input = object("""
            {"schemaVersion":"1","blocks":[{"id":"b1","text":"条款","sourceId":"s1","version":1}],"readBlockIds":[]}
            """);
        ObjectNode payload = object("""
            {"schemaVersion":"1","items":[],"coverage":{"processedBlockIds":["b1"],"unprocessedBlockIds":[]},"warnings":[]}
            """);
        var error = assertThrows(BiddingApiException.class,
            () -> validator.validate("bidding-elimination-analysis", payload, input));
        assertEquals("BLOCK_NOT_READ", error.code());
    }

    @Test void rejectsNaNAndNonDecimalScoreValues() throws Exception {
        ObjectNode input = object("""
            {"schemaVersion":"1","blocks":[{"id":"b1","text":"得分10分","sourceId":"s1","version":1}],"readBlockIds":["b1"]}
            """);
        ObjectNode payload = object("""
            {"schemaVersion":"1","criteria":[{"id":"c1","parentId":null,"title":"技术","score":"NaN","unit":"分","rule":"按项计分","requiredProof":null,"evidenceRefs":[{"sourceId":"s1","version":1,"blockId":"b1","quote":"得分10分"}]}],
             "totalChecks":[],"coverage":{"processedBlockIds":["b1"],"unprocessedBlockIds":[]},"warnings":[]}
            """);
        var error = assertThrows(BiddingApiException.class,
            () -> validator.validate("bidding-scoring-analysis", payload, input));
        assertEquals("OUTPUT_SCHEMA_INVALID", error.code());
    }

    @Test void bundledGoldenExamplesAgreeWithJavaAndPinnedJsonSchemaChecks() throws Exception {
        JsonNode fixture;
        try (var stream = getClass().getResourceAsStream("/bidding/analysis/golden.json")) {
            assertNotNull(stream); fixture = json.readTree(stream);
        }
        ObjectNode input = (ObjectNode) fixture.path("input");
        for (String skill : java.util.List.of("bidding-tender-profile", "bidding-elimination-analysis", "bidding-requirement-analysis", "bidding-scoring-analysis")) {
            ObjectNode valid = (ObjectNode) fixture.path("valid").path(skill);
            assertDoesNotThrow(() -> validator.validate(skill, valid, input));
            assertPinnedSchemaAccepts(skill, valid);

            ObjectNode invalid = (ObjectNode) fixture.path("invalid").path(skill);
            assertThrows(BiddingApiException.class, () -> validator.validate(skill, invalid, input));
            assertPinnedSchemaRejects(skill, invalid);
        }
    }

    private void assertPinnedSchemaAccepts(String skill, ObjectNode output) throws Exception {
        String schema = schema(skill);
        var result = BiddingEmployeeRuntime.readResult(reactor.core.publisher.Flux.just(
                vip.mate.agent.AgentService.StreamDelta.event("project_skill_loaded", Map.of("digest", "skill")),
                vip.mate.agent.AgentService.StreamDelta.finalAnswer(json.writeValueAsString(output), false),
                vip.mate.agent.AgentService.StreamDelta.event("project_execution_completed", Map.of("configDigest", "config", "skillDigest", "skill"))),
                "skill", "config", schema);
        assertNotNull(result.payload(), skill + " should satisfy its packaged schema: " + result.failure());
    }

    private void assertPinnedSchemaRejects(String skill, ObjectNode output) throws Exception {
        var result = BiddingEmployeeRuntime.readResult(reactor.core.publisher.Flux.just(
                vip.mate.agent.AgentService.StreamDelta.event("project_skill_loaded", Map.of("digest", "skill")),
                vip.mate.agent.AgentService.StreamDelta.finalAnswer(json.writeValueAsString(output), false),
                vip.mate.agent.AgentService.StreamDelta.event("project_execution_completed", Map.of("configDigest", "config", "skillDigest", "skill"))),
                "skill", "config", schema(skill));
        assertNull(result.payload(), skill + " should fail its packaged schema");
    }

    private String schema(String skill) throws Exception {
        try (var stream = getClass().getResourceAsStream("/skills/" + skill + "/output.schema.json")) {
            assertNotNull(stream, "packaged output schema for " + skill);
            return new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private ObjectNode object(String source) throws Exception { return (ObjectNode) json.readTree(source); }
}

package vip.mate.bidding;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
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

    @Test void acceptsLeafOrQualifiedUnknownForMissingBasicInfoAndRejectsNearMiss() throws Exception {
        ObjectNode input = object("""
            {"schemaVersion":"1","blocks":[{"id":"b1","text":"招标文件","sourceId":"s1","version":1}],"readBlockIds":[]}
            """);
        String template = """
            {"schemaVersion":"1","basicInfo":{"project":"项目","tenderer":"招标人","lot":null},
             "deadlines":[],"deliveryConditions":[],"mandatoryOutline":[],"formatRequirements":[],
             "unknowns":[{"field":"%s","reason":"未提供标段"}],
             "coverage":{"processedBlockIds":[],"unprocessedBlockIds":["b1"]},"warnings":[]}
            """;
        for (String field : java.util.List.of("lot", "basicInfo.lot")) {
            ObjectNode payload = object(template.formatted(field));
            assertDoesNotThrow(() -> validator.validate("bidding-tender-profile", payload, input), field);
        }

        ObjectNode nearMiss = object(template.formatted("basicInfo.lotName"));
        var error = assertThrows(BiddingApiException.class,
                () -> validator.validate("bidding-tender-profile", nearMiss, input));
        assertEquals("OUTPUT_SCHEMA_INVALID", error.code());
        assertTrue(error.getMessage().contains("lot"));
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

    @Test void rejectsTotalDifferenceThatDoesNotEqualStatedMinusCalculated() throws Exception {
        ObjectNode input = object("""
            {"schemaVersion":"1","blocks":[{"id":"b1","text":"技术方案12.50分，总分12.50分","sourceId":"s1","version":1}],"readBlockIds":["b1"]}
            """);
        ObjectNode payload = scoringPayload("10", "0");
        var error = assertThrows(BiddingApiException.class,
                () -> validator.validate("bidding-scoring-analysis", payload, input));
        assertEquals("TOTAL_CHECK_MISMATCH", error.code());
        assertTrue(error.getMessage().contains("/totalChecks/0/difference"));
    }

    @Test void rejectsCalculatedTotalThatDoesNotMatchApplicableLeafCriteria() throws Exception {
        ObjectNode input = object("""
            {"schemaVersion":"1","blocks":[{"id":"b1","text":"技术方案12.50分，总分12.50分","sourceId":"s1","version":1}],"readBlockIds":["b1"]}
            """);
        ObjectNode payload = scoringPayload("10", "2.50");
        var error = assertThrows(BiddingApiException.class,
                () -> validator.validate("bidding-scoring-analysis", payload, input));
        assertEquals("TOTAL_CHECK_CRITERIA_MISMATCH", error.code());
        assertTrue(error.getMessage().contains("/totalChecks/0/calculatedTotal"));
    }

    @Test void calculatesTotalFromLeafCriteriaWithoutDoubleCountingParentAggregate() throws Exception {
        ObjectNode input = object("""
            {"schemaVersion":"1","blocks":[{"id":"b1","text":"综合项20分，技术12.50分，商务7.50分","sourceId":"s1","version":1}],"readBlockIds":["b1"]}
            """);
        ObjectNode payload = object("""
            {"schemaVersion":"1","criteria":[
              {"id":"p1","parentId":null,"title":"综合项","score":"20","unit":"分","rule":null,"requiredProof":null,"evidenceRefs":[{"sourceId":"s1","version":1,"blockId":"b1","quote":"综合项20分"}]},
              {"id":"c1","parentId":"p1","title":"技术","score":"12.50","unit":"分","rule":null,"requiredProof":null,"evidenceRefs":[{"sourceId":"s1","version":1,"blockId":"b1","quote":"技术12.50分"}]},
              {"id":"c2","parentId":"p1","title":"商务","score":"7.50","unit":"分","rule":null,"requiredProof":null,"evidenceRefs":[{"sourceId":"s1","version":1,"blockId":"b1","quote":"商务7.50分"}]}],
             "totalChecks":[{"name":"综合总分","criterionIds":["c1","c2"],"statedTotal":"20","calculatedTotal":"20.00","difference":"0.00","evidenceRefs":[{"sourceId":"s1","version":1,"blockId":"b1","quote":"综合项20分"}]}],
             "coverage":{"processedBlockIds":["b1"],"unprocessedBlockIds":[]},"warnings":[]}
            """);
        assertDoesNotThrow(() -> validator.validate("bidding-scoring-analysis", payload, input));
    }

    @Test void leavesCalculatedTotalUnknownWhenAnApplicableLeafScoreIsUnknown() throws Exception {
        ObjectNode input = object("""
            {"schemaVersion":"1","blocks":[{"id":"b1","text":"综合项20分，技术12.50分，商务分值待定","sourceId":"s1","version":1}],"readBlockIds":["b1"]}
            """);
        ObjectNode payload = object("""
            {"schemaVersion":"1","criteria":[
              {"id":"p1","parentId":null,"title":"综合项","score":"20","unit":"分","rule":null,"requiredProof":null,"evidenceRefs":[{"sourceId":"s1","version":1,"blockId":"b1","quote":"综合项20分"}]},
              {"id":"c1","parentId":"p1","title":"技术","score":"12.50","unit":"分","rule":null,"requiredProof":null,"evidenceRefs":[{"sourceId":"s1","version":1,"blockId":"b1","quote":"技术12.50分"}]},
              {"id":"c2","parentId":"p1","title":"商务","score":null,"unit":"分","rule":null,"requiredProof":null,"evidenceRefs":[{"sourceId":"s1","version":1,"blockId":"b1","quote":"商务分值待定"}]}],
             "totalChecks":[{"name":"综合总分","criterionIds":["c1","c2"],"statedTotal":"20","calculatedTotal":"12.50","difference":"7.50","evidenceRefs":[{"sourceId":"s1","version":1,"blockId":"b1","quote":"综合项20分"}]}],
             "coverage":{"processedBlockIds":["b1"],"unprocessedBlockIds":[]},"warnings":[]}
            """);
        var error = assertThrows(BiddingApiException.class,
                () -> validator.validate("bidding-scoring-analysis", payload, input));
        assertEquals("TOTAL_CHECK_CRITERIA_UNKNOWN", error.code());
    }

    @Test void validatesMultipleSectionSubtotalsAndAnIndependentlyScoredParent() throws Exception {
        ObjectNode input = object("""
            {"schemaVersion":"1","blocks":[{"id":"b1","text":"技术总分50分，研发20分，实施30分；商务总分50分，价格30分，服务20分","sourceId":"s1","version":1}],"readBlockIds":["b1"]}
            """);
        ObjectNode payload = object("""
            {"schemaVersion":"1","criteria":[
              {"id":"tech","parentId":null,"title":"技术评分","score":"50","unit":"分","rule":null,"requiredProof":null,"evidenceRefs":[{"sourceId":"s1","version":1,"blockId":"b1","quote":"技术总分50分"}]},
              {"id":"dev","parentId":"tech","title":"研发","score":"20","unit":"分","rule":null,"requiredProof":null,"evidenceRefs":[{"sourceId":"s1","version":1,"blockId":"b1","quote":"研发20分"}]},
              {"id":"impl","parentId":"tech","title":"实施","score":"30","unit":"分","rule":null,"requiredProof":null,"evidenceRefs":[{"sourceId":"s1","version":1,"blockId":"b1","quote":"实施30分"}]},
              {"id":"commercial","parentId":null,"title":"商务评分","score":"50","unit":"分","rule":null,"requiredProof":null,"evidenceRefs":[{"sourceId":"s1","version":1,"blockId":"b1","quote":"商务总分50分"}]},
              {"id":"price","parentId":"commercial","title":"价格","score":"30","unit":"分","rule":null,"requiredProof":null,"evidenceRefs":[{"sourceId":"s1","version":1,"blockId":"b1","quote":"价格30分"}]},
              {"id":"service","parentId":"commercial","title":"服务","score":"20","unit":"分","rule":null,"requiredProof":null,"evidenceRefs":[{"sourceId":"s1","version":1,"blockId":"b1","quote":"服务20分"}]}],
             "totalChecks":[
              {"name":"技术小计","criterionIds":["tech"],"statedTotal":"50","calculatedTotal":"50.00","difference":"0.00","evidenceRefs":[{"sourceId":"s1","version":1,"blockId":"b1","quote":"技术总分50分"}]},
              {"name":"研发实施合计","criterionIds":["dev","impl"],"statedTotal":"50","calculatedTotal":"50","difference":"0","evidenceRefs":[{"sourceId":"s1","version":1,"blockId":"b1","quote":"研发20分"}]},
              {"name":"商务小计","criterionIds":["commercial"],"statedTotal":"50","calculatedTotal":"50","difference":"0","evidenceRefs":[{"sourceId":"s1","version":1,"blockId":"b1","quote":"商务总分50分"}]},
              {"name":"价格服务合计","criterionIds":["price","service"],"statedTotal":"50","calculatedTotal":"50","difference":"0","evidenceRefs":[{"sourceId":"s1","version":1,"blockId":"b1","quote":"价格30分"}]}],
             "coverage":{"processedBlockIds":["b1"],"unprocessedBlockIds":[]},"warnings":[]}
            """);
        assertDoesNotThrow(() -> validator.validate("bidding-scoring-analysis", payload, input));
    }

    @Test void rejectsUnknownDuplicateAndHierarchicallyOverlappingApplicableCriteria() throws Exception {
        ObjectNode input = object("""
            {"schemaVersion":"1","blocks":[{"id":"b1","text":"技术总分20分，研发20分","sourceId":"s1","version":1}],"readBlockIds":["b1"]}
            """);
        ObjectNode payload = object("""
            {"schemaVersion":"1","criteria":[
              {"id":"parent","parentId":null,"title":"技术","score":"20","unit":"分","rule":null,"requiredProof":null,"evidenceRefs":[{"sourceId":"s1","version":1,"blockId":"b1","quote":"技术总分20分"}]},
              {"id":"child","parentId":"parent","title":"研发","score":"20","unit":"分","rule":null,"requiredProof":null,"evidenceRefs":[{"sourceId":"s1","version":1,"blockId":"b1","quote":"研发20分"}]}],
             "totalChecks":[{"name":"合计","criterionIds":["parent","child"],"statedTotal":"40","calculatedTotal":"40","difference":"0","evidenceRefs":[{"sourceId":"s1","version":1,"blockId":"b1","quote":"技术总分20分"}]}],
             "coverage":{"processedBlockIds":["b1"],"unprocessedBlockIds":[]},"warnings":[]}
            """);
        var overlap = assertThrows(BiddingApiException.class, () -> validator.validate("bidding-scoring-analysis", payload, input));
        assertEquals("TOTAL_CHECK_CRITERIA_OVERLAP", overlap.code());
        ArrayNode selected = (ArrayNode) payload.path("totalChecks").get(0).path("criterionIds");
        selected.removeAll().add("missing");
        var unknown = assertThrows(BiddingApiException.class, () -> validator.validate("bidding-scoring-analysis", payload, input));
        assertEquals("TOTAL_CHECK_CRITERION_UNKNOWN", unknown.code());
        selected.removeAll().add("child").add("child");
        var duplicate = assertThrows(BiddingApiException.class, () -> validator.validate("bidding-scoring-analysis", payload, input));
        assertEquals("TOTAL_CHECK_CRITERION_DUPLICATE", duplicate.code());
    }

    private ObjectNode scoringPayload(String calculatedTotal, String difference) throws Exception {
        return object("""
            {"schemaVersion":"1","criteria":[{"id":"c1","parentId":null,"title":"技术方案","score":"12.50","unit":"分","rule":null,"requiredProof":null,"evidenceRefs":[{"sourceId":"s1","version":1,"blockId":"b1","quote":"技术方案12.50分"}]}],
             "totalChecks":[{"name":"总分","criterionIds":["c1"],"statedTotal":"12.50","calculatedTotal":"%s","difference":"%s","evidenceRefs":[{"sourceId":"s1","version":1,"blockId":"b1","quote":"总分12.50分"}]}],
             "coverage":{"processedBlockIds":["b1"],"unprocessedBlockIds":[]},"warnings":[]}
            """.formatted(calculatedTotal, difference));
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

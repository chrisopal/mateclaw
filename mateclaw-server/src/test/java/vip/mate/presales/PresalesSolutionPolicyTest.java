package vip.mate.presales;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class PresalesSolutionPolicyTest {
    private final ObjectMapper json = new ObjectMapper();
    private final PresalesSolutionPolicy policy = new PresalesSolutionPolicy(json);

    private ObjectNode draft() {
        var v = json.createObjectNode().put("title", "draft");
        v.putArray("sections").addObject().put("title", "section").put("text", "text");
        return v;
    }

    @Test
    void employeePresentationPassesOnlyAfterCallerValidationWhileHumanPresentationRejects() {
        var p = json.createObjectNode();
        var v = draft();
        v.putObject("presentation").put("digest", "opaque");
        var original = v.deepCopy();
        policy.prepare(p, v, true);
        assertEquals(original.path("presentation"), v.path("presentation"));
        assertFalse(v.has("id"));
        assertFalse(p.has("solutions"));
        var e = assertThrows(PresalesRejected.class, () -> policy.prepare(p, original, false));
        assertEquals(422, e.status());
        assertEquals("PRESENTATION_METADATA_UNTRUSTED", e.code());
    }

    @Test
    void exactBaselineScopesDetermineCoverageOrderAndRounding() {
        var p = json.createObjectNode();
        var refs =
                p.putArray("baselines")
                        .addObject()
                        .put("id", "base")
                        .put("version", 2)
                        .putArray("references");
        for (String id : new String[] {"c", "a", "b"})
            refs.addObject().put("requirementId", id).put("scope", "IN");
        p.putArray("requirements").addObject().put("id", "different-live").put("scope", "OUT");
        var v = draft().put("baselineId", "base");
        ((ObjectNode) v.path("sections").get(0)).putArray("requirementRefs").add("a").add("c");
        v.putArray("requirementResponses")
                .addObject()
                .put("requirementId", "a")
                .put("status", "FULL");
        v.withArray("requirementResponses")
                .addObject()
                .put("requirementId", "c")
                .put("status", "CONDITIONAL")
                .put("reason", "condition");
        policy.prepare(p, v, false);
        var coverage = v.path("coverage");
        assertEquals(3, coverage.path("totalIn").asInt());
        assertEquals(2, coverage.path("handledIn").asInt());
        assertEquals(66.67, coverage.path("percentage").asDouble());
        assertEquals("c", coverage.path("responses").get(0).path("requirementId").asText());
        assertEquals("b", coverage.path("responses").get(2).path("requirementId").asText());
        assertEquals("UNHANDLED", coverage.path("responses").get(2).path("status").asText());
        assertEquals(2, v.path("baselineVersion").asInt());
        var direct = policy.coverage(p, v);
        assertEquals(coverage, direct);
        ((ObjectNode) direct.path("responses").get(0)).put("reason", "changed");
        assertEquals("condition", v.path("requirementResponses").get(1).path("reason").asText());
    }

    @Test
    void sourceArrayLimitAndNontextualReferencesKeep422Errors() {
        var p = json.createObjectNode();
        var sources =
                p.putArray("tasks").addObject().putObject("contextSnapshot").putArray("sources");
        sources.addObject().put("sourceRef", "allowed");
        var v = draft();
        for (int i = 0; i < 100; i++) v.withArray("sourceRefs").add("allowed");
        assertDoesNotThrow(() -> policy.prepare(p, v, false));
        v.withArray("sourceRefs").add("allowed");
        var size = assertThrows(PresalesRejected.class, () -> policy.prepare(p, v, false));
        assertEquals("MODEL_FORMAT", size.code());
        assertEquals(422, size.status());
        v.putArray("sourceRefs").add(1);
        var value = assertThrows(PresalesRejected.class, () -> policy.prepare(p, v, false));
        assertEquals("INVALID_SOURCE_REFERENCE", value.code());
    }

    @Test
    void presentationAndBaselineFaultsPrecedeSourceAndCoverageFaults() {
        var p = json.createObjectNode();
        p.putArray("baselines").addObject().put("id", "base").put("version", 2);
        var v = draft().put("baselineId", "base").put("baselineVersion", 1);
        v.putArray("sourceRefs").add("unbound");
        v.putArray("requirementResponses").addObject().put("requirementId", "not-in-baseline");
        var e = assertThrows(PresalesRejected.class, () -> policy.prepare(p, v, false));
        assertEquals("BASELINE_STALE", e.code());
        v.putNull("presentation");
        e = assertThrows(PresalesRejected.class, () -> policy.prepare(p, v, false));
        assertEquals("PRESENTATION_METADATA_UNTRUSTED", e.code());
    }
}

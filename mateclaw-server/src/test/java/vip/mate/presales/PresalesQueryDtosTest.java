package vip.mate.presales;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class PresalesQueryDtosTest {
    @Test
    void evidenceSnapshotKeepsNullEntriesAndIsDetachedFromUpstream() {
        var evidence = new ArrayList<>(Arrays.asList("e2", null, "e1"));
        var json = new ObjectMapper();
        var oldSnapshot = json.valueToTree(evidence);
        var dto =
                new PresalesDtos.TrustedStatement(
                        "9007199254740993001", 3, "g", null, "label", evidence);
        evidence.clear();
        assertEquals(oldSnapshot, json.valueToTree(dto).path("evidenceIds"));
        assertThrows(UnsupportedOperationException.class, () -> dto.evidenceIds().add("later"));
        assertTrue(json.valueToTree(dto).path("revision").isInt());
        assertTrue(json.valueToTree(dto).path("id").isTextual());
    }

    @Test
    void nullEvidenceRemainsExplicitNull() {
        var dto = new PresalesDtos.TrustedStatement("s", 1, "g", null, "label", null);
        var wire = new ObjectMapper().valueToTree(dto);
        assertTrue(wire.has("evidenceIds"));
        assertTrue(wire.path("evidenceIds").isNull());
    }
}

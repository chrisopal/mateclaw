package vip.mate.bidding;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.MockBean;
import vip.mate.presales.PresalesService;

class BiddingHandoffReceiptTest extends BiddingHttpFixture {
    @MockBean PresalesService presales;

    @Test void receivedReleaseReplaysOnceAndNewOperationConflictsWithoutChangingProject() throws Exception {
        var project = project();
        String projectId = project.path("id").asText();
        ObjectNode snapshot = json.createObjectNode().put("releaseId", "release-1")
                .put("customerConfirmationStatus", "UNCONFIRMED");
        snapshot.putObject("baseline").put("id", "baseline-1");
        snapshot.putObject("solution").put("id", "solution-1").put("title", "Example solution").put("version", 2);
        snapshot.putObject("release").put("id", "release-1").put("status", "PUBLISHED");
        snapshot.putArray("materialRefs");
        when(presales.handoff(workspace, "pre-1", "release-1")).thenReturn(snapshot);
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(snapshot)));
        Map<String, Object> payload = Map.of("presalesProjectId", "pre-1", "releaseId", "release-1", "expectedDigest", digest,
                "receivedNotes", List.of("Scope confirmation pending"));
        String commandPath = "/projects/" + projectId + "/commands";
        Map<String, Object> firstCommand = Map.of("operationId", "receive-once", "expected", ref(project),
                "action", "RECEIVE_HANDOFF", "payload", payload);

        var first = api("POST", commandPath, "member", workspace, firstCommand, 200);
        assertEquals("UNCONFIRMED", first.path("customerConfirmationStatus").asText());
        assertEquals("presales:pre-1:release-1", first.path("materialRefs").get(0).path("id").asText());
        assertEquals(first, api("POST", commandPath, "member", workspace, firstCommand, 200));

        var beforeConflict = api("GET", "/projects/" + projectId, "member", workspace, null, 200);
        Map<String, Object> secondCommand = Map.of("operationId", "receive-again", "expected", ref(beforeConflict),
                "action", "RECEIVE_HANDOFF", "payload", payload);
        var conflict = api("POST", commandPath, "member", workspace, secondCommand, 409);
        assertTrue(conflict.toString().contains("HANDOFF_ALREADY_RECEIVED"), conflict.toString());

        var afterConflict = api("GET", "/projects/" + projectId, "member", workspace, null, 200);
        assertEquals(beforeConflict.path("ref"), afterConflict.path("ref"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM mate_bidding_handoff WHERE workspace_id=? AND project_id=?", Integer.class, workspace, projectId));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM mate_bidding_material WHERE workspace_id=? AND project_id=? AND source_kind='PRESALES_RELEASE'", Integer.class, workspace, projectId));
    }
}

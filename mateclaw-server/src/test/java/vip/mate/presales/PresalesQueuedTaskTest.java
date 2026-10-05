package vip.mate.presales;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class PresalesQueuedTaskTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void capturedSnapshotCannotBeChangedByCallerOrAccessor() throws Exception {
        var snapshot =
                (ObjectNode)
                        json.readTree(
                                """
                                {"projectVersion":2,"sources":[{"sourceRef":"9007199254740993001"},null],
                                 "extension":{"value":null}}
                                """);
        var expected = snapshot.deepCopy();
        var task = task(snapshot);
        snapshot.withArray("sources").removeAll();
        snapshot.withObject("extension").put("value", "changed before serialization");
        task.contextSnapshot().put("projectVersion", 999);
        task.contextSnapshot().withArray("sources").removeAll();
        assertEquals(expected, task.contextSnapshot());
        assertEquals(expected, json.valueToTree(task).path("contextSnapshot"));
    }

    @Test
    void nullableSnapshotIsSerializedAsExplicitNullWithoutInventingContent() {
        var task = task(null);
        assertNull(task.contextSnapshot());
        var wire = json.valueToTree(task);
        assertTrue(wire.has("contextSnapshot"));
        assertTrue(wire.path("contextSnapshot").isNull());
    }

    private PresalesQueuedTask task(ObjectNode snapshot) {
        return new PresalesQueuedTask(
                "op",
                "hash",
                "S1",
                "7",
                null,
                "goal",
                PresalesQueuedTask.Status.RUNNING,
                PresalesQueuedTask.QueueState.QUEUED,
                "time",
                "run",
                true,
                "17",
                null,
                "skill",
                null,
                null,
                "conversation",
                snapshot);
    }
}

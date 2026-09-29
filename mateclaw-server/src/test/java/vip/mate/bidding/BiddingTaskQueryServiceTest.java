package vip.mate.bidding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BiddingTaskQueryServiceTest {
    private final ObjectMapper json = new ObjectMapper();
    private final BiddingTaskService tasks = mock(BiddingTaskService.class);
    private final BiddingTaskReadRepository repository = mock(BiddingTaskReadRepository.class);
    private final BiddingMaterials materials = mock(BiddingMaterials.class);
    private final BiddingTaskQueryService queries =
            new BiddingTaskQueryService(tasks, repository, materials, json);
    private final BiddingTypes.Scope requestScope = new BiddingTypes.Scope("1", "actor", null);
    private final BiddingTypes.Scope projectScope = new BiddingTypes.Scope("1", "actor", "project");
    private final BiddingTypes.Ref reference =
            new BiddingTypes.Ref("material", "source", 1, "digest");

    @BeforeEach
    void boundTask() {
        when(repository.findAccess("1", "project", "task"))
                .thenReturn(
                        new BiddingTaskReadRepository.TaskAccess("writer", project("reviewer")));
    }

    @Test
    void writerTaskRevalidatesBothInputAndEvidenceBeforeReturningDetails() {
        ObjectNode details = details("chapter:1");
        when(tasks.taskDetails(requestScope, "task")).thenReturn(details);

        assertSame(details, queries.details(requestScope, "task"));

        verify(materials)
                .requireReadable(
                        projectScope, "writer", details.path("snapshot").path("inputRefs"));
        verify(materials).requireReadable(projectScope, "writer", reference);
        verify(materials, never()).requireReviewerReadable(projectScope, "reviewer", reference);
    }

    @Test
    void historicalReviewTaskUsesReviewerAccessForBothSources() {
        ObjectNode details = details("review:old-baseline");
        when(tasks.taskDetails(requestScope, "task")).thenReturn(details);
        when(repository.findAccess("1", "project", "task"))
                .thenReturn(
                        new BiddingTaskReadRepository.TaskAccess("reviewer", project("reviewer")));

        assertSame(details, queries.details(requestScope, "task"));

        verify(materials, org.mockito.Mockito.times(2))
                .requireReviewerReadable(projectScope, "reviewer", reference);
        verify(materials, never()).requireReadable(projectScope, "reviewer", reference);
    }

    @Test
    void reviewerRebindingNeverDowngradesHistoricalTaskToWriterAccess() {
        when(tasks.taskDetails(requestScope, "task")).thenReturn(details("review:old-baseline"));

        BiddingApiException denied =
                assertThrows(
                        BiddingApiException.class, () -> queries.details(requestScope, "task"));

        assertEquals(403, denied.status());
        assertEquals("REVIEWER_MATERIAL_UNAVAILABLE", denied.code());
        org.mockito.Mockito.verifyNoInteractions(materials);
    }

    @Test
    void malformedBindingsFailBeforeStoredSourcesCanBeReturned() {
        when(tasks.taskDetails(requestScope, "task")).thenReturn(details("chapter:1"));
        when(repository.findAccess("1", "project", "task"))
                .thenReturn(
                        new BiddingTaskReadRepository.TaskAccess(
                                "writer", json.createObjectNode()));

        BiddingApiException denied =
                assertThrows(
                        BiddingApiException.class, () -> queries.details(requestScope, "task"));

        assertEquals(500, denied.status());
        assertEquals("PROJECT_STATE_INVALID", denied.code());
        org.mockito.Mockito.verifyNoInteractions(materials);
    }

    @Test
    void corruptProjectJsonFailsWithoutExposingTaskSources() {
        when(tasks.taskDetails(requestScope, "task")).thenReturn(details("chapter:1"));
        when(repository.findAccess("1", "project", "task"))
                .thenThrow(new IllegalStateException("corrupt project body"));

        BiddingApiException denied =
                assertThrows(
                        BiddingApiException.class, () -> queries.details(requestScope, "task"));

        assertEquals("PROJECT_STATE_INVALID", denied.code());
        org.mockito.Mockito.verifyNoInteractions(materials);
    }

    @Test
    void missingProjectAfterTaskLookupCannotReturnStoredSnapshot() {
        when(tasks.taskDetails(requestScope, "task")).thenReturn(details("chapter:1"));
        when(repository.findAccess("1", "project", "task"))
                .thenReturn(new BiddingTaskReadRepository.TaskAccess("writer", null));

        BiddingApiException denied =
                assertThrows(
                        BiddingApiException.class, () -> queries.details(requestScope, "task"));

        assertEquals(404, denied.status());
        org.mockito.Mockito.verifyNoInteractions(materials);
    }

    @Test
    void blankTaskEmployeeCannotReturnStoredSnapshot() {
        when(tasks.taskDetails(requestScope, "task")).thenReturn(details("review:old-baseline"));
        when(repository.findAccess("1", "project", "task"))
                .thenReturn(new BiddingTaskReadRepository.TaskAccess("", project("")));

        BiddingApiException denied =
                assertThrows(
                        BiddingApiException.class, () -> queries.details(requestScope, "task"));

        assertEquals(403, denied.status());
        assertEquals("EMPLOYEE_UNAVAILABLE", denied.code());
        org.mockito.Mockito.verifyNoInteractions(materials);
    }

    private ObjectNode details(String targetId) {
        ObjectNode details = json.createObjectNode().put("projectId", "project");
        ObjectNode snapshot = details.putObject("snapshot");
        snapshot.putObject("_bidding").put("targetId", targetId);
        snapshot.putArray("inputRefs").add(json.valueToTree(reference));
        snapshot.putObject("input")
                .putObject("evidenceSnapshot")
                .putObject("materials")
                .putArray("items")
                .addObject()
                .set("ref", json.valueToTree(reference));
        return details;
    }

    private ObjectNode project(String reviewerId) {
        ObjectNode project = json.createObjectNode();
        project.putObject("bindings").putObject("reviewer").put("agentId", reviewerId);
        return project;
    }
}

package vip.mate.presales;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import vip.mate.presales.repository.PresalesProjectRepository;

class PresalesGenerationCoordinatorTest {
    @SuppressWarnings("unchecked")
    private static ObjectProvider<PresalesPresentationHook> hooks() {
        return mock(ObjectProvider.class);
    }

    @Test
    void processPersistsSuccessOnlyAfterModelAndRevalidation() throws Exception {
        var json = new ObjectMapper();
        var service = mock(PresalesService.class);
        var contexts = mock(PresalesContextProvider.class);
        var model = mock(PresalesEmployeeRuntime.class);
        var state =
                (ObjectNode)
                        json.readTree(
                                "{\"id\":\"p\",\"version\":2,\"tasks\":[{\"id\":\"t\",\"status\":\"RUNNING\"}]}");
        when(service.get("w", "p")).thenReturn(state.deepCopy());
        when(service.find(any(), eq("tasks"), eq("t")))
                .thenAnswer(i -> (ObjectNode) ((ObjectNode) i.getArgument(0)).path("tasks").get(0));
        when(model.execute(
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        any(),
                        any()))
                .thenReturn(json.createObjectNode().put("schemaVersion", 1));
        when(service.saveEmployeeTask(anyString(), anyString(), any()))
                .thenAnswer(
                        i -> {
                            var command = i.<PresalesDtos.Command>getArgument(2);
                            assertEquals("SUCCEEDED", command.payload().path("status").asText());
                            return state;
                        });
        var coordinator =
                new PresalesGenerationCoordinator(service, contexts, model, json, hooks());
        var task = (ObjectNode) state.path("tasks").get(0).deepCopy();
        coordinator.process(
                new PresalesGenerationCoordinator.Submission(
                        "w",
                        "actor",
                        "p",
                        "op",
                        "S1",
                        "goal",
                        task,
                        json.createObjectNode().put("projectVersion", 2),
                        2));
        verify(model)
                .execute(
                        eq("w"),
                        eq("actor"),
                        anyString(),
                        anyString(),
                        contains("Return"),
                        any(),
                        any());
        verify(contexts).revalidate(eq("w"), any(), any());
        verify(model).revalidate(eq("w"), eq("actor"), any(), any());
        verify(service).saveEmployeeTask(eq("w"), eq("p"), any());
    }

    @Test
    void revokedEmployeeAfterModelReturnCannotBeAccepted() throws Exception {
        var json = new ObjectMapper();
        var service = mock(PresalesService.class);
        var model = mock(PresalesEmployeeRuntime.class);
        var state =
                (ObjectNode)
                        json.readTree(
                                "{\"id\":\"p\",\"version\":2,\"tasks\":[{\"id\":\"t\",\"status\":\"RUNNING\"}]}");
        when(service.get("w", "p")).thenReturn(state.deepCopy());
        when(service.find(any(), eq("tasks"), eq("t")))
                .thenAnswer(i -> (ObjectNode) ((ObjectNode) i.getArgument(0)).path("tasks").get(0));
        when(model.execute(
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        any(),
                        any()))
                .thenReturn(json.createObjectNode().put("schemaVersion", 1));
        doThrow(PresalesModelAdapter.error(409, "EMPLOYEE_UNAVAILABLE"))
                .when(model)
                .revalidate(eq("w"), eq("actor"), any(), any());
        var coordinator =
                new PresalesGenerationCoordinator(
                        service, mock(PresalesContextProvider.class), model, json, hooks());
        var task = (ObjectNode) state.path("tasks").get(0).deepCopy();

        coordinator.process(
                new PresalesGenerationCoordinator.Submission(
                        "w",
                        "actor",
                        "p",
                        "op",
                        "S1",
                        "goal",
                        task,
                        json.createObjectNode().put("projectVersion", 2),
                        2));

        verify(service)
                .saveEmployeeTask(
                        eq("w"),
                        eq("p"),
                        argThat(
                                command ->
                                        "FAILED".equals(command.payload().path("status").asText())
                                                && command.payload()
                                                        .path("result")
                                                        .isMissingNode()));
    }

    @Test
    void cancellationReservationPreventsLateModelResult() throws Exception {
        var json = new ObjectMapper();
        var service = mock(PresalesService.class);
        var state =
                (ObjectNode)
                        json.readTree(
                                "{\"id\":\"p\",\"version\":2,\"tasks\":[{\"id\":\"t\",\"status\":\"RUNNING\"}]}");
        when(service.get("w", "p")).thenReturn(state.deepCopy());
        when(service.find(any(), eq("tasks"), eq("t")))
                .thenAnswer(i -> (ObjectNode) ((ObjectNode) i.getArgument(0)).path("tasks").get(0));
        var model = mock(PresalesEmployeeRuntime.class);
        var coordinator =
                new PresalesGenerationCoordinator(
                        service, mock(PresalesContextProvider.class), model, json, hooks());
        coordinator.requestCancellation("w", "p", "t");
        coordinator.process(
                new PresalesGenerationCoordinator.Submission(
                        "w",
                        "actor",
                        "p",
                        "op",
                        "S1",
                        "goal",
                        (ObjectNode) state.path("tasks").get(0).deepCopy(),
                        json.createObjectNode().put("projectVersion", 2),
                        2));
        verify(model, never())
                .execute(
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        any(),
                        any());
        verify(service, never()).saveEmployeeTask(anyString(), anyString(), any());
    }

    @Test
    void recoveryMarksEvenRecentlyQueuedTasksInterrupted() throws Exception {
        var json = new ObjectMapper();
        var projects = mock(PresalesProjectRepository.class);
        when(projects.listRuntimeRows())
                .thenReturn(
                        List.of(
                                new PresalesProjectRepository.ProjectRow(
                                        "p",
                                        "w",
                                        4,
                                        "original",
                                        "ACTIVE",
                                        "{\"version\":4,\"tasks\":[{\"id\":\"t\",\"status\":\"RUNNING\",\"queuedAt\":\"%s\"}]}"
                                                .formatted(
                                                        java.time.Instant.now().minusSeconds(1)))));
        var coordinator =
                new PresalesGenerationCoordinator(
                        mock(PresalesService.class),
                        mock(PresalesContextProvider.class),
                        mock(PresalesEmployeeRuntime.class),
                        json,
                        projects,
                        hooks());
        coordinator.recoverStaleTasks();
        verify(projects)
                .updateRuntimeBody(
                        eq("w"),
                        eq("p"),
                        eq(4L),
                        eq(5L),
                        contains("\"INTERRUPTED_BY_RESTART\""),
                        any(PresalesListingProjectionV1.Projection.class));
    }
}

package vip.mate.presales;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PresalesGenerationControllerTest {
    @ParameterizedTest
    @ValueSource(ints = {1, Integer.MAX_VALUE - 2})
    void acceptsAndQueuesImmediatelyAndRetryDoesNotQueueAgain(int version) throws Exception {
        var json = new ObjectMapper();
        var service = mock(PresalesService.class);
        var access = mock(PresalesAccess.class);
        var contexts = mock(PresalesContextProvider.class);
        var model = mock(PresalesEmployeeRuntime.class);
        var coordinator = mock(PresalesGenerationCoordinator.class);
        var state =
                new AtomicReference<>(
                        (ObjectNode) json.readTree("{\"id\":\"p\",\"version\":1,\"tasks\":[]}"));
        state.get().put("version", version);
        when(service.get("w", "p")).thenAnswer(i -> state.get().deepCopy());
        var employee = new vip.mate.agent.model.AgentEntity();
        employee.setId(7L);
        employee.setName("售前员工");
        when(model.require(anyString(), anyString())).thenReturn(employee);
        when(model.capture("w", "7", "S1"))
                .thenReturn(PresalesTaskPackageFixtures.capture("S1", "17", "config"));
        when(access.require("w", "member")).thenReturn("1");
        when(contexts.snapshot(anyString(), any(), anyString(), anyString()))
                .thenReturn(json.createObjectNode().put("projectVersion", 1));
        org.mockito.stubbing.Answer<ObjectNode> save =
                i -> {
                    long expectedVersion = i.getArgument(2);
                    PresalesQueuedTask queuedTask = i.getArgument(4);
                    assertEquals(state.get().path("version").asInt(), expectedVersion);
                    var p = state.get().deepCopy();
                    ObjectNode task = json.valueToTree(queuedTask);
                    task.put("id", "task");
                    p.putArray("tasks").add(task);
                    p.put("version", expectedVersion + 1);
                    state.set(p);
                    return p.deepCopy();
                };
        when(service.queueEmployeeTask(
                        anyString(), anyString(), anyLong(), anyString(), any(), any()))
                .thenAnswer(save);
        var controller =
                new PresalesGenerationController(
                        new PresalesGenerationService(
                                service, access, contexts, model, json, coordinator));
        var input = new PresalesDtos.Generate((long) version, "operation", "S1", "理解需求");
        var accepted = controller.generate("w", "p", input);
        assertEquals(200, accepted.getCode());
        assertEquals(
                version + 1,
                state.get()
                        .path("tasks")
                        .get(0)
                        .path("contextSnapshot")
                        .path("projectVersion")
                        .intValue());
        verify(coordinator).enqueue(any(PresalesGenerationCoordinator.Submission.class));
        controller.generate("w", "p", input);
        verify(coordinator, times(1)).enqueue(any(PresalesGenerationCoordinator.Submission.class));
        verify(model, never())
                .execute(
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        any(),
                        any());
    }

    @Test
    void noModelDoesNotCreatePretendTask() {
        var service = mock(PresalesService.class);
        var json = new ObjectMapper();
        when(service.get("w", "p")).thenReturn(json.createObjectNode().put("version", 1));
        var model = mock(PresalesEmployeeRuntime.class);
        when(model.require(anyString(), anyString()))
                .thenThrow(PresalesModelAdapter.error(409, "EMPLOYEE_UNAVAILABLE"));
        var controller =
                new PresalesGenerationController(
                        new PresalesGenerationService(
                                service,
                                mock(PresalesAccess.class),
                                mock(PresalesContextProvider.class),
                                model,
                                json,
                                mock(PresalesGenerationCoordinator.class)));
        assertThrows(
                vip.mate.semantic.web.SemanticApiException.class,
                () ->
                        controller.generate(
                                "w", "p", new PresalesDtos.Generate(1L, "op", "S1", "分析")));
        verify(service, never()).command(any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"absent", "null", "blank", "explicit"})
    void cancellationReservesBeforeCommitAndPreservesOperationId(String mode) {
        var f = new CancellationFixture("RUNNING");
        PresalesDtos.Cancel input =
                switch (mode) {
                    case "absent" -> null;
                    case "null" -> new PresalesDtos.Cancel(null);
                    case "blank" -> new PresalesDtos.Cancel("  ");
                    default -> new PresalesDtos.Cancel("explicit-operation");
                };
        String expected = mode.equals("explicit") ? "explicit-operation" : "cancel:t";
        when(f.service.command(eq("w"), eq("p"), any()))
                .thenAnswer(
                        call -> {
                            PresalesDtos.Command command = call.getArgument(2);
                            assertEquals(4, command.expectedVersion());
                            assertEquals(expected, command.operationId());
                            assertEquals("CANCEL_AI_TASK", command.action());
                            assertEquals(
                                    f.json.createObjectNode().put("taskId", "t"),
                                    command.payload());
                            return f.project.deepCopy().put("version", 5);
                        });
        assertEquals(200, f.controller.cancel("w", "p", "t", input).getCode());
        var order = inOrder(f.access, f.service, f.coordinator);
        order.verify(f.access).require("w", "member");
        order.verify(f.service).get("w", "p");
        order.verify(f.service).find(f.project, "tasks", "t");
        order.verify(f.coordinator).requestCancellation("w", "p", "t");
        order.verify(f.service).command(eq("w"), eq("p"), any());
        verify(f.coordinator, never()).clearCancellation(any(), any(), any());
    }

    @Test
    void failedCancellationClearsReservationAndRethrowsOriginalFailure() {
        var f = new CancellationFixture("RUNNING");
        var failure = PresalesModelAdapter.error(409, "VERSION_CONFLICT");
        when(f.service.command(any(), any(), any())).thenThrow(failure);
        assertSame(
                failure,
                assertThrows(
                        RuntimeException.class, () -> f.controller.cancel("w", "p", "t", null)));
        var order = inOrder(f.service, f.coordinator);
        order.verify(f.coordinator).requestCancellation("w", "p", "t");
        order.verify(f.service).command(any(), any(), any());
        order.verify(f.coordinator).clearCancellation("w", "p", "t");
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUCCEEDED", "CANCELLED", "FAILED"})
    void terminalTasksCannotBeCancelled(String status) {
        var f = new CancellationFixture(status);
        var rejected =
                assertThrows(
                        vip.mate.semantic.web.SemanticApiException.class,
                        () -> f.controller.cancel("w", "p", "t", null));
        assertEquals(409, rejected.status());
        assertEquals("TASK_STATE", rejected.code());
        verifyNoInteractions(f.coordinator);
        verify(f.service, never()).command(any(), any(), any());
    }

    @Test
    void oversizedCancellationOperationDoesNotReserve() {
        var f = new CancellationFixture("RUNNING");
        var rejected =
                assertThrows(
                        vip.mate.semantic.web.SemanticApiException.class,
                        () ->
                                f.controller.cancel(
                                        "w", "p", "t", new PresalesDtos.Cancel("x".repeat(101))));
        assertEquals(400, rejected.status());
        assertEquals("INVALID_REQUEST", rejected.code());
        verifyNoInteractions(f.coordinator);
        verify(f.service, never()).command(any(), any(), any());
    }

    @Test
    void unauthorizedCancellationDoesNotReadOrReserve() {
        var f = new CancellationFixture("RUNNING");
        var failure = PresalesModelAdapter.error(403, "FORBIDDEN");
        when(f.access.require("w", "member")).thenThrow(failure);
        assertSame(
                failure,
                assertThrows(
                        RuntimeException.class, () -> f.controller.cancel("w", "p", "t", null)));
        verifyNoInteractions(f.service, f.coordinator);
    }

    private static final class CancellationFixture {
        final ObjectMapper json = new ObjectMapper();
        final PresalesService service = mock(PresalesService.class);
        final PresalesAccess access = mock(PresalesAccess.class);
        final PresalesGenerationCoordinator coordinator = mock(PresalesGenerationCoordinator.class);
        final ObjectNode project = json.createObjectNode().put("id", "p").put("version", 4);
        final PresalesGenerationController controller =
                new PresalesGenerationController(
                        new PresalesGenerationService(
                                service,
                                access,
                                mock(PresalesContextProvider.class),
                                mock(PresalesEmployeeRuntime.class),
                                json,
                                coordinator));

        CancellationFixture(String status) {
            ObjectNode task =
                    project.putArray("tasks").addObject().put("id", "t").put("status", status);
            when(service.get("w", "p")).thenReturn(project);
            when(service.find(project, "tasks", "t")).thenReturn(task);
        }
    }
}

package vip.mate.presales;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import vip.mate.agent.execution.ProjectExecutionOptions;
import vip.mate.semantic.web.SemanticApiException;

class PresalesExecutionRevalidationProviderTest {
    private final ObjectMapper json = new ObjectMapper();
    private final PresalesAccess access = mock(PresalesAccess.class);
    private final PresalesService service = mock(PresalesService.class);
    private final PresalesEmployeeRuntime runtime = mock(PresalesEmployeeRuntime.class);
    private final PresalesContextProvider contexts = mock(PresalesContextProvider.class);
    private final PresalesExecutionRevalidationProvider provider =
            new PresalesExecutionRevalidationProvider(access, service, runtime, contexts);
    private final PresalesToolScope scope =
            new PresalesToolScope("1", "9", "p", "t", "run", "op", List.of("src"), "7", 2);
    private final ProjectExecutionOptions options =
            new ProjectExecutionOptions(
                    "run",
                    "17",
                    "config",
                    "presales-skill",
                    "skill",
                    Map.of("SKILL.md", "content"),
                    PresalesToolPolicy.PROJECT_VISIBLE_TOOLS,
                    scope,
                    0,
                    false,
                    false,
                    12);

    @Test
    void activeTaskWithMatchingActorPinsAndSourcesPasses() throws Exception {
        ObjectNode project = activeProject();

        when(service.getForExecution("1", "p", "9")).thenReturn(project);
        when(service.find(project, "tasks", "t"))
                .thenReturn((ObjectNode) project.path("tasks").get(0));
        when(runtime.pin("1", "7", "S1"))
                .thenReturn(
                        new PresalesEmployeeRuntime.Pin("17", "config", "presales-skill", "skill"));

        provider.requireActive(options);

        verify(contexts).revalidate(eq("1"), eq(project), any(ObjectNode.class));
    }

    @Test
    void changedActorFailsBeforeProjectRead() {
        org.mockito.Mockito.doThrow(
                        new vip.mate.semantic.web.SemanticApiException(
                                403, "ACCESS_REVOKED", "revoked"))
                .when(access)
                .requireActor("1", "9", "member");

        assertThrows(SemanticApiException.class, () -> provider.requireActive(options));

        verify(service, never()).getForExecution("1", "p", "9");
    }

    @Test
    void cancelledTaskFailsBeforeModelOrSourceRead() throws Exception {
        ObjectNode project = activeProject();
        ((ObjectNode) project.path("tasks").get(0)).put("status", "CANCELLED");

        when(service.getForExecution("1", "p", "9")).thenReturn(project);
        when(service.find(project, "tasks", "t"))
                .thenReturn((ObjectNode) project.path("tasks").get(0));

        assertThrows(SemanticApiException.class, () -> provider.requireActive(options));

        verify(runtime, never()).pin("1", "7", "S1");
        verify(contexts, never()).revalidate(eq("1"), eq(project), any(ObjectNode.class));
    }

    @Test
    void changedModelPinFailsBeforeSourceRead() throws Exception {
        ObjectNode project = activeProject();

        when(service.getForExecution("1", "p", "9")).thenReturn(project);
        when(service.find(project, "tasks", "t"))
                .thenReturn((ObjectNode) project.path("tasks").get(0));
        when(runtime.pin("1", "7", "S1"))
                .thenReturn(
                        new PresalesEmployeeRuntime.Pin(
                                "17", "changed", "presales-skill", "skill"));

        assertThrows(SemanticApiException.class, () -> provider.requireActive(options));

        verify(contexts, never()).revalidate(eq("1"), eq(project), any(ObjectNode.class));
    }

    @Test
    void changedOperationOrInputReferenceFailsBeforeModelRead() throws Exception {
        ObjectNode project = activeProject();
        ObjectNode task = (ObjectNode) project.path("tasks").get(0);
        task.put("operationId", "other");

        when(service.getForExecution("1", "p", "9")).thenReturn(project);
        when(service.find(project, "tasks", "t")).thenReturn(task);

        assertThrows(SemanticApiException.class, () -> provider.requireActive(options));
        verify(runtime, never()).pin("1", "7", "S1");

        task.put("operationId", "op");
        ((ObjectNode) task.path("contextSnapshot").path("sources").get(0))
                .put("sourceRef", "different");
        assertThrows(SemanticApiException.class, () -> provider.requireActive(options));
        verify(runtime, never()).pin("1", "7", "S1");
    }

    private ObjectNode activeProject() throws Exception {
        return (ObjectNode)
                json.readTree(
                        """
                {"id":"p","version":2,"agentId":"7","tasks":[{"id":"t","runId":"run",
                "status":"RUNNING","agentId":"7","skill":"S1","modelConfigId":"17",
                "configDigest":"config","skillName":"presales-skill","skillDigest":"skill",
                "operationId":"op","conversationId":"presales:1:p:run",
                "contextSnapshot":{"caseRef":"p","projectVersion":2,
                "sources":[{"sourceRef":"src"}]}}]}
                """);
    }
}

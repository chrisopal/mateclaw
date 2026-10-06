package vip.mate.presales;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import vip.mate.agent.execution.ProjectExecutionOptions;
import vip.mate.agent.execution.ProjectToolPolicy;
import vip.mate.agent.model.AgentEntity;
import vip.mate.semantic.web.SemanticApiException;
import vip.mate.workspace.core.service.ProjectSourceAccess;

class PresalesExecutionVersionContractTest {
    private final ObjectMapper json = new ObjectMapper();
    private final PresalesAccess access = mock(PresalesAccess.class);
    private final PresalesService service = mock(PresalesService.class);
    private final PresalesEmployeeRuntime runtime = mock(PresalesEmployeeRuntime.class);
    private final PresalesContextProvider contexts = mock(PresalesContextProvider.class);
    private final PresalesEmployeeRuntime.Pin pin =
            new PresalesEmployeeRuntime.Pin("17", "config", "presales-skill", "skill");

    @ParameterizedTest
    @ValueSource(
            strings = {
                "2.5",
                "2.0",
                "9007199254740992",
                "-4294967294",
                "\"2.5\"",
                "0",
                "-1",
                "null",
                "{}",
                "[]",
                "true",
                "\"bad\""
            })
    void contextRejectsMalformedVersionsBeforeReadingSources(String value) throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var sourceAccess = mock(ProjectSourceAccess.class);
        var provider = new PresalesContextProvider(jdbc, json, access, sourceAccess);
        when(access.require("1", "member")).thenReturn("9");
        ObjectNode project = project();
        JsonNode invalid = json.readTree(value);
        project.set("version", invalid);
        assertCode("VERSION_CONFLICT", () -> provider.snapshot("1", project, "S1", "goal"));
        var snapshot = snapshot();
        assertCode("VERSION_CONFLICT", () -> provider.revalidate("1", project, snapshot));
        project.put("version", 2);
        snapshot.set("projectVersion", invalid);
        assertCode("VERSION_CONFLICT", () -> provider.revalidate("1", project, snapshot));
        project.set("version", invalid);
        assertCode("VERSION_CONFLICT", () -> provider.revalidate("1", project, snapshot));
        verifyNoInteractions(jdbc, sourceAccess);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "2.5",
                "2.0",
                "4294967298",
                "-4294967294",
                "\"2.5\"",
                "0",
                "-1",
                "null",
                "{}",
                "[]",
                "true",
                "\"bad\""
            })
    void toolRevalidationRejectsEitherMalformedVersionBeforePinOrSourceRead(String value)
            throws Exception {
        ObjectNode project = wireProject();
        ObjectNode task = (ObjectNode) project.path("tasks").get(0);
        var provider =
                new PresalesExecutionRevalidationProvider(access, service, runtime, contexts);
        when(runtime.pin("1", "7", "S1")).thenReturn(pin);
        project.set("version", json.readTree(value));
        assertCode("PROJECT_CHANGED_DURING_GENERATION", () -> provider.requireActive(options()));
        project.put("version", 2);
        ((ObjectNode) task.path("contextSnapshot")).set("projectVersion", json.readTree(value));
        assertCode("TASK_SCOPE_CHANGED", () -> provider.requireActive(options()));
        verifyNoInteractions(runtime, contexts);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "2.5",
                "2.0",
                "9007199254740992",
                "-4294967294",
                "\"2.5\"",
                "0",
                "-1",
                "null",
                "{}",
                "[]",
                "true",
                "\"bad\""
            })
    @SuppressWarnings("unchecked")
    void runtimeDoesNotTurnMalformedSnapshotIntoTrustedOptions(String value) throws Exception {
        ProjectToolPolicy.Revalidator revalidator = mock(ProjectToolPolicy.Revalidator.class);
        var actual =
                spy(
                        new PresalesEmployeeRuntime(
                                mock(ObjectProvider.class),
                                mock(ObjectProvider.class),
                                json,
                                null,
                                revalidator,
                                mock(ObjectProvider.class)));
        doReturn(pin).when(actual).pin("1", "7", "S1");
        ObjectNode snapshot = snapshot();
        snapshot.set("projectVersion", json.readTree(value));
        assertCode("TASK_SCOPE_CHANGED", () -> actual.revalidate("1", "9", task(), snapshot));
        verifyNoInteractions(revalidator);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "2.5",
                "2.0",
                "4294967298",
                "-4294967294",
                "\"2.5\"",
                "0",
                "-1",
                "null",
                "{}",
                "[]",
                "true",
                "\"bad\""
            })
    void generationDoesNotQueueMalformedAcceptedVersion(String value) throws Exception {
        var coordinator = mock(PresalesGenerationCoordinator.class);
        ObjectNode initial = project();
        initial.put("version", 1);
        when(service.get("1", "p")).thenReturn(initial);
        when(access.require("1", "member")).thenReturn("9");
        var employee = new AgentEntity();
        employee.setId(7L);
        when(runtime.require("1", "7")).thenReturn(employee);
        when(runtime.capture("1", "7", "S1"))
                .thenReturn(PresalesTaskPackageFixtures.capture("S1", "17", "config"));
        when(contexts.snapshot(anyString(), any(), anyString(), anyString()))
                .thenReturn(snapshot());
        ObjectNode saved = project();
        saved.set("version", json.readTree(value));
        saved.putArray("tasks").add(task());
        when(service.queueEmployeeTask(
                        anyString(), anyString(), anyLong(), anyString(), any(), any()))
                .thenReturn(saved);
        var app =
                new PresalesGenerationService(
                        service, access, contexts, runtime, json, coordinator);
        assertCode(
                "VERSION_CONFLICT",
                () -> app.generate("1", "p", new PresalesDtos.Generate(1L, "op", "S1", "goal")));
        verifyNoInteractions(coordinator);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "2.5",
                "2.0",
                "9007199254740992",
                "-4294967294",
                "\"2.5\"",
                "0",
                "-1",
                "null",
                "{}",
                "[]",
                "true",
                "\"bad\""
            })
    void cancellationRejectsMalformedVersionBeforeReservingOrSaving(String value) throws Exception {
        ObjectNode project = wireProject();
        project.set("version", json.readTree(value));
        var coordinator = mock(PresalesGenerationCoordinator.class);
        var app =
                new PresalesGenerationService(
                        service, access, contexts, runtime, json, coordinator);
        assertCode(
                "VERSION_CONFLICT",
                () -> app.cancel("1", "p", "t", new PresalesDtos.Cancel("cancel")));
        verifyNoInteractions(coordinator);
        verify(service, never()).command(any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "2.5",
                "2.0",
                "9007199254740992",
                "-4294967294",
                "\"2.5\"",
                "0",
                "-1",
                "null",
                "{}",
                "[]",
                "true",
                "\"bad\""
            })
    @SuppressWarnings("unchecked")
    void terminalPersistenceNeverUsesTruncatedCompareAndSwapVersion(String value) throws Exception {
        ObjectNode project = wireProject();
        project.set("version", json.readTree(value));
        when(runtime.execute(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(json.createObjectNode().put("schemaVersion", 1));
        var coordinator =
                new PresalesGenerationCoordinator(
                        service, contexts, runtime, json, mock(ObjectProvider.class));
        coordinator.process(
                new PresalesGenerationCoordinator.Submission(
                        "1", "9", "p", "op", "S1", "goal", task(), snapshot(), 2));
        verify(service, never()).saveEmployeeTask(any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "2",
                "\"2\"",
                "\" 2 \"",
                "2147483647",
                "2147483648",
                "4294967298",
                "9007199254740991",
                "\"2147483648\""
            })
    void exactVersionsAndHistoricalIntegerTextStillPass(String value) throws Exception {
        long version = json.readTree(value).asLong();
        ObjectNode project = wireProject();
        project.set("version", json.readTree(value));
        ObjectNode task = (ObjectNode) project.path("tasks").get(0);
        ((ObjectNode) task.path("contextSnapshot")).set("projectVersion", json.readTree(value));
        when(runtime.pin("1", "7", "S1")).thenReturn(pin);
        new PresalesExecutionRevalidationProvider(access, service, runtime, contexts)
                .requireActive(options(version));
        verify(contexts).revalidate(eq("1"), eq(project), any());
        var actualContext =
                new PresalesContextProvider(
                        mock(JdbcTemplate.class), json, access, mock(ProjectSourceAccess.class));
        assertEquals(
                version,
                actualContext
                        .snapshot("1", project, "S1", "goal")
                        .path("projectVersion")
                        .longValue());
        actualContext.revalidate("1", project, (ObjectNode) task.path("contextSnapshot"));
        project.put(
                "version",
                version == PresalesProjectRevision.MAX_VALUE ? version - 1 : version + 1);
        assertCode(
                "VERSION_CONFLICT",
                () ->
                        actualContext.revalidate(
                                "1", project, (ObjectNode) task.path("contextSnapshot")));
    }

    private ObjectNode wireProject() throws Exception {
        ObjectNode project = project();
        ObjectNode task = task();
        project.putArray("tasks").add(task);
        when(service.get("1", "p")).thenReturn(project);
        when(service.getForExecution("1", "p", "9")).thenReturn(project);
        when(service.find(project, "tasks", "t")).thenReturn(task);
        return project;
    }

    private ObjectNode project() throws Exception {
        return (ObjectNode)
                json.readTree("{\"id\":\"p\",\"version\":2,\"agentId\":\"7\",\"tasks\":[]}");
    }

    private ObjectNode snapshot() throws Exception {
        return (ObjectNode)
                json.readTree(
                        "{\"caseRef\":\"p\",\"actorId\":\"9\",\"skill\":\"S1\",\"projectVersion\":2,\"sources\":[]}");
    }

    private ObjectNode task() throws Exception {
        var task =
                (ObjectNode)
                        json.readTree(
                                """
                {"id":"t","runId":"run","operationId":"op","conversationId":"presales:1:p:run",
                 "status":"RUNNING","agentId":"7","skill":"S1","modelConfigId":"17",
                 "configDigest":"config","skillName":"presales-skill","skillDigest":"skill"}
                """);
        task.set("contextSnapshot", snapshot());
        return task;
    }

    private ProjectExecutionOptions options() {
        return options(2);
    }

    private ProjectExecutionOptions options(long version) {
        return new ProjectExecutionOptions(
                "run",
                "17",
                "config",
                "presales-skill",
                "skill",
                Map.of("SKILL.md", "content"),
                PresalesToolPolicy.PROJECT_VISIBLE_TOOLS,
                new PresalesToolScope("1", "9", "p", "t", "run", "op", List.of(), "7", version),
                0,
                false,
                false,
                12);
    }

    private void assertCode(String code, org.junit.jupiter.api.function.Executable action) {
        assertEquals(code, assertThrows(SemanticApiException.class, action).code());
    }
}

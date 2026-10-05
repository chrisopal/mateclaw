package vip.mate.presales;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import vip.mate.agent.model.AgentEntity;
import vip.mate.config.JacksonConfig;

/** Characterizes the legacy command at the actual new-task write/queue boundary. */
class PresalesQueuedTaskContractTest {
    @ParameterizedTest
    @ValueSource(strings = {"default", "host", "omitNull", "sortProperties"})
    void generatedTaskKeepsExactWireAndQueuesStoredTask(String mapperMode) throws Exception {
        ObjectMapper json = mapper(mapperMode);
        var service = mock(PresalesService.class);
        var access = mock(PresalesAccess.class);
        var contexts = mock(PresalesContextProvider.class);
        var model = mock(PresalesEmployeeRuntime.class);
        var coordinator = mock(PresalesGenerationCoordinator.class);
        var project = json.createObjectNode().put("id", "project").put("version", 1);
        project.putArray("tasks");
        var snapshot =
                (ObjectNode)
                        json.readTree(
                                """
                                {"projectVersion":1,"largeId":"9007199254740993001",
                                 "extension":{"nullable":null,"text":"中文\\n<quoted>"}}
                                """);
        var employee = new AgentEntity();
        employee.setId(Long.MAX_VALUE);
        employee.setName(null);
        when(access.require("workspace", "member")).thenReturn("actor");
        when(service.get("workspace", "project")).thenReturn(project);
        when(model.require(anyString(), anyString())).thenReturn(employee);
        when(model.pin("workspace", Long.toString(Long.MAX_VALUE), "S1"))
                .thenReturn(new PresalesEmployeeRuntime.Pin("17", null, "skill-name", null, null));
        when(contexts.snapshot("workspace", project, "S1", "理解需求")).thenReturn(snapshot);
        when(service.command(eq("workspace"), eq("project"), any()))
                .thenAnswer(
                        call -> {
                            PresalesDtos.Command command = call.getArgument(2);
                            var saved = command.payload().deepCopy();
                            saved.put("id", "stored-task").put("version", 1);
                            saved.put("authority", "UNTRUSTED_DRAFT");
                            var response = project.deepCopy().put("version", 2);
                            response.putArray("tasks").add(saved);
                            return response;
                        });
        var generation =
                new PresalesGenerationService(service, access, contexts, model, json, coordinator);
        var input = new PresalesDtos.Generate(1, "operation", "S1", "理解需求");
        var response = generation.generate("workspace", "project", input);
        var write = ArgumentCaptor.forClass(PresalesDtos.Command.class);
        verify(service).command(eq("workspace"), eq("project"), write.capture());
        var command = write.getValue();
        assertEquals(1, command.expectedVersion());
        assertEquals("operation:start", command.operationId());
        assertEquals("SAVE_AI_TASK", command.action());
        var actual = command.payload();
        String runId = actual.path("runId").asText();
        UUID.fromString(runId);
        Instant.parse(actual.path("queuedAt").asText());
        var expected =
                (ObjectNode)
                        json.readTree(
                                """
                                {"operationId":"operation","requestHash":"REPLACE",
                                 "skill":"S1","agentId":"9223372036854775807","agentName":null,
                                 "taskGoal":"理解需求","status":"RUNNING","queueState":"QUEUED",
                                 "queuedAt":"REPLACE","runId":"REPLACE","needsHumanReview":true,
                                 "modelConfigId":"17","configDigest":null,"skillName":"skill-name",
                                 "skillDigest":null,"presentationDigest":null,"conversationId":"REPLACE",
                                 "contextSnapshot":{"projectVersion":2,"largeId":"9007199254740993001",
                                  "extension":{"nullable":null,"text":"中文\\n<quoted>"}}}
                                """);
        expected.put("requestHash", PresalesArtifactRenderer.digest(json.writeValueAsBytes(input)));
        expected.put("queuedAt", actual.path("queuedAt").asText());
        expected.put("runId", runId);
        expected.put("conversationId", "presales:workspace:project:" + runId);
        assertEquals(json.writeValueAsString(expected), json.writeValueAsString(actual));
        var queued = ArgumentCaptor.forClass(PresalesGenerationCoordinator.Submission.class);
        var ordered = inOrder(service, coordinator);
        ordered.verify(service).get("workspace", "project");
        ordered.verify(service).command(eq("workspace"), eq("project"), any());
        ordered.verify(coordinator).enqueue(queued.capture());
        assertEquals(response.path("tasks").get(0), queued.getValue().task());
        assertEquals(expected.path("contextSnapshot"), queued.getValue().snapshot());
        assertEquals(2, queued.getValue().acceptedVersion());
        assertEquals("actor", queued.getValue().actor());
        when(service.get("workspace", "project")).thenReturn(response);
        assertSame(response, generation.generate("workspace", "project", input));
        verify(coordinator, times(1)).enqueue(any());
        verify(model, times(1)).pin(anyString(), anyString(), anyString());
    }

    private static ObjectMapper mapper(String mode) {
        if (mode.equals("host")) {
            var builder = new Jackson2ObjectMapperBuilder();
            var config = new JacksonConfig();
            config.longToStringCustomizer().customize(builder);
            config.enumTolerantCustomizer().customize(builder);
            return builder.build();
        }
        if (mode.equals("sortProperties"))
            return JsonMapper.builder()
                    .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                    .build();
        var json = new ObjectMapper();
        if (mode.equals("omitNull")) json.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        return json;
    }
}

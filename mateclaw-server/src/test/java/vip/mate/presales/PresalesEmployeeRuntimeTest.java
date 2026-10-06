package vip.mate.presales;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;
import vip.mate.agent.AgentService;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.agent.execution.ProjectExecutionOptions;
import vip.mate.agent.execution.ProjectToolPolicy;
import vip.mate.agent.model.AgentEntity;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.service.ModelConfigService;
import vip.mate.workspace.conversation.ConversationService;

class PresalesEmployeeRuntimeTest {
    @Test
    void projectRunUsesPinnedExecutionOptionsAndAuthenticatedWorkspace() throws Exception {
        AgentService agents = mock(AgentService.class);
        ConversationService conversations = mock(ConversationService.class);
        ModelConfigService models = mock(ModelConfigService.class);
        ProjectToolPolicy.Revalidator revalidator = mock(ProjectToolPolicy.Revalidator.class);
        ObjectProvider<AgentService> agentProvider = provider(agents);
        ObjectProvider<ConversationService> conversationProvider = provider(conversations);
        var employee = new AgentEntity();
        employee.setId(7L);
        employee.setWorkspaceId(1L);
        employee.setEnabled(true);
        employee.setRuntimeType("native");
        when(agents.getAgent(7L)).thenReturn(employee);
        var model = new ModelConfigEntity();
        model.setId(17L);
        model.setEnabled(true);
        model.setProvider("test");
        model.setModelName("model");
        when(models.resolveModel(employee.getModelName())).thenReturn(model);

        var packages = mock(vip.mate.presales.repository.PresalesProjectRepository.class);
        var runtime =
                new PresalesEmployeeRuntime(
                        agentProvider,
                        conversationProvider,
                        new ObjectMapper(),
                        models,
                        revalidator,
                        provider(packages));
        var captured = runtime.capture("1", "7", "S1");
        var pin = captured.pin();
        ObjectNode task =
                new ObjectMapper()
                        .createObjectNode()
                        .put("id", "t")
                        .put("runId", "run")
                        .put("operationId", "op")
                        .put("conversationId", "presales:1:p:run")
                        .put("agentId", "7")
                        .put("skill", "S1")
                        .put("modelConfigId", pin.modelConfigId())
                        .put("configDigest", pin.configDigest())
                        .put("skillName", pin.skillName())
                        .put("skillDigest", pin.skillDigest());
        ObjectNode snapshot =
                new ObjectMapper()
                        .createObjectNode()
                        .put("skill", "S1")
                        .put("caseRef", "p")
                        .put("projectVersion", 2);
        task.put("packageDigest", captured.skillPackage().digest());
        snapshot.put("workspaceId", "1").put("actorId", "9");
        when(packages.findTaskPackage("1", "p", "t", "run"))
                .thenReturn(
                        java.util.Optional.of(
                                new vip.mate.presales.repository.PresalesProjectRepository
                                        .TaskPackageRow(
                                        "1",
                                        "p",
                                        "t",
                                        "run",
                                        "9",
                                        "7",
                                        captured.skillPackage().digest(),
                                        new ObjectMapper()
                                                .writeValueAsString(captured.skillPackage()))));
        var origin = ArgumentCaptor.forClass(ChatOrigin.class);
        var options = ArgumentCaptor.forClass(ProjectExecutionOptions.class);
        when(agents.chatStructuredStream(
                        eq(7L),
                        anyString(),
                        eq("presales:1:p:run"),
                        eq("9"),
                        isNull(),
                        origin.capture(),
                        options.capture()))
                .thenReturn(
                        Flux.just(
                                AgentService.StreamDelta.segmentOnly("先读取项目资料。", null),
                                AgentService.StreamDelta.finalAnswer(
                                        "{\"schemaVersion\":1,\"needsHumanReview\":true,\"items\":[],\"unknowns\":[],\"assumptions\":[]}",
                                        true)));

        var result =
                runtime.execute(
                        "1",
                        "9",
                        "7",
                        "presales:1:p:run",
                        PresalesModelAdapter.instructions("S1"),
                        task,
                        snapshot);

        assertTrue(result.path("needsHumanReview").asBoolean());
        assertEquals(1L, origin.getValue().workspaceId());
        assertEquals(9L, origin.getValue().requesterUserId());
        assertEquals("t", ((PresalesToolScope) options.getValue().toolPolicy()).taskId());
        assertEquals(pin.configDigest(), options.getValue().configDigest());
        verify(revalidator).requireActive(options.getValue());
        verify(conversations).getOrCreateConversation("presales:1:p:run", 7L, "9", 1L);
        verify(agents)
                .chatStructuredStream(
                        eq(7L),
                        contains("BOUND EMPLOYEE ID: 7."),
                        eq("presales:1:p:run"),
                        eq("9"),
                        isNull(),
                        any(ChatOrigin.class),
                        any(ProjectExecutionOptions.class));
        assertThrows(
                vip.mate.semantic.web.SemanticApiException.class, () -> runtime.require("2", "7"));

        task.put("configDigest", "changed");
        assertThrows(
                vip.mate.semantic.web.SemanticApiException.class,
                () ->
                        runtime.execute(
                                "1",
                                "9",
                                "7",
                                "presales:1:p:run",
                                PresalesModelAdapter.instructions("S1"),
                                task,
                                snapshot));
        verify(agents, times(1))
                .chatStructuredStream(
                        eq(7L),
                        anyString(),
                        eq("presales:1:p:run"),
                        eq("9"),
                        isNull(),
                        any(ChatOrigin.class),
                        any(ProjectExecutionOptions.class));
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(value);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}

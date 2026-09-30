package vip.mate.agent.graph.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.jdbc.core.JdbcTemplate;
import vip.mate.agent.AgentToolSet;
import vip.mate.agent.GraphEventPublisher.GraphEvent;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.agent.execution.ProjectConversationToolBoundary;
import vip.mate.agent.execution.ProjectToolPolicy;
import vip.mate.presales.PresalesToolPolicy;
import vip.mate.tool.guard.ToolGuardResult;

class ProjectConversationToolBoundaryTest {
    @Test
    void presalesPolicyUsesConversationBoundaryInsteadOfSerializableTaskPolicy() {
        assertTrue(
                ProjectConversationToolBoundary.class.isAssignableFrom(PresalesToolPolicy.class));
        assertFalse(ProjectToolPolicy.class.isAssignableFrom(PresalesToolPolicy.class));
    }

    @Test
    void ordinaryChatKeepsExistingToolExecutionWhenOptionalPolicyIsAbsent() throws Exception {
        ToolCallback callback = callback("ordinary result");
        ToolExecutionExecutor executor = executor(callback);

        var result =
                executor.execute(
                        List.of(new AssistantMessage.ToolCall("c", "function", "web_search", "{}")),
                        "chat-1",
                        "7",
                        true,
                        "1",
                        null,
                        ChatOrigin.web("chat-1", "1", 1L, null));

        assertEquals("ordinary result", result.responses().getFirst().responseData());
        verify(callback).call(eq("{}"), any());
    }

    @Test
    void ordinaryChatWithPresalesPolicyDoesNotReadPresalesState() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ToolCallback callback = callback("ordinary result");
        ToolExecutionExecutor executor = executor(callback);
        executor.setProjectToolPolicy(
                new PresalesToolPolicy(
                        jdbc,
                        new ObjectMapper(),
                        new vip.mate.workspace.core.service.ProjectSourceAccess(jdbc)));

        var result =
                executor.execute(
                        List.of(new AssistantMessage.ToolCall("c", "function", "web_search", "{}")),
                        "chat-1",
                        "7",
                        true,
                        "1",
                        null,
                        ChatOrigin.web("chat-1", "1", 1L, null));

        assertEquals("ordinary result", result.responses().getFirst().responseData());
        verify(callback).call(eq("{}"), any());
        verifyNoInteractions(jdbc);
    }

    @Test
    void preapprovedPresalesReplayFailsClosedWhenOptionalPolicyIsAbsent() throws Exception {
        ToolCallback callback = callback(null);
        ToolExecutionExecutor executor = executor(callback);
        List<GraphEvent> events = new ArrayList<>();

        var response =
                executor.executePreApproved(
                        new AssistantMessage.ToolCall("c", "function", "web_search", "{}"),
                        "{}",
                        events,
                        "presales:1:project:run",
                        null,
                        null,
                        "7",
                        ChatOrigin.web("presales:1:project:run", "1", 1L, null));

        assertTrue(response.responseData().contains("PRESALES_PROJECT_SCOPE"));
        verify(callback, never()).call(anyString(), any());
    }

    private static ToolExecutionExecutor executor(ToolCallback callback) {
        return new ToolExecutionExecutor(
                AgentToolSet.fromCallbacks(List.of(), List.of(callback)),
                (name, arguments) -> ToolGuardResult.allow(),
                null,
                null);
    }

    private static ToolCallback callback(String response) {
        ToolCallback callback = mock(ToolCallback.class);
        when(callback.getToolDefinition())
                .thenReturn(
                        ToolDefinition.builder()
                                .name("web_search")
                                .description("test")
                                .inputSchema("{}")
                                .build());
        when(callback.getToolMetadata())
                .thenReturn(ToolMetadata.builder().returnDirect(false).build());
        if (response != null) {
            when(callback.call(anyString(), any())).thenReturn(response);
        }
        return callback;
    }
}

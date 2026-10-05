package vip.mate.agent.graph;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static vip.mate.agent.graph.state.MateClawStateKeys.*;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;
import vip.mate.workspace.conversation.ConversationService;

class StateGraphReActAgentAttributionTest {
    @ParameterizedTest
    @CsvSource({
        "false,0,0", "false,1,0", "false,1,7", "false,0,7",
        "true,0,0", "true,1,0", "true,1,7", "true,0,7"
    })
    void completedStreamsRetainActualModelWithoutInventingUsage(
            boolean replay, int calls, int tokens) {
        var graph = mock(CompiledGraph.class);
        var state =
                new OverAllState(
                        Map.of(
                                FINISH_REASON,
                                "normal",
                                FINAL_ANSWER,
                                "answer",
                                LLM_CALL_COUNT,
                                calls,
                                PROMPT_TOKENS,
                                tokens,
                                COMPLETION_TOKENS,
                                0,
                                RUNTIME_MODEL_NAME,
                                "actual-model",
                                RUNTIME_PROVIDER_ID,
                                "actual-provider"));
        when(graph.stream(anyMap(), any(RunnableConfig.class)))
                .thenReturn(
                        Flux.just(
                                NodeOutput.of("reasoning", "test", state, null),
                                NodeOutput.of("finalAnswer", "test", state, null)));
        var agent =
                new StateGraphReActAgent(null, mock(ConversationService.class), graph, null, null);
        var stream =
                replay
                        ? agent.chatWithReplayStream("question", "ordinary-chat", "", "user")
                        : agent.chatStructuredStream("question", "ordinary-chat");
        var deltas = stream.collectList().block(Duration.ofSeconds(5));
        assertNotNull(deltas);
        var usage =
                deltas.stream()
                        .filter(
                                delta ->
                                        delta.isEvent() && "_usage_final".equals(delta.eventType()))
                        .toList();
        if (calls == 0 && tokens == 0) {
            assertTrue(usage.isEmpty(), "Configured model alone is not an executed model");
        } else {
            assertEquals(
                    1,
                    usage.size(),
                    "One final event per stream, including usage-less model calls");
            Map<String, Object> event = usage.getFirst().eventData();
            assertEquals(tokens, ((Number) event.get("promptTokens")).intValue());
            assertEquals(0, ((Number) event.get("completionTokens")).intValue());
            assertEquals("actual-model", event.get("runtimeModelName"));
            assertEquals("actual-provider", event.get("runtimeProviderId"));
        }
        verify(graph).stream(anyMap(), any(RunnableConfig.class));
    }

    @ParameterizedTest
    @CsvSource({"false,error_fallback", "true,error_fallback", "false,stopped", "true,stopped"})
    void unsuccessfulAttemptsDoNotInventModelAttribution(boolean replay, String reason) {
        var graph = mock(CompiledGraph.class);
        var state =
                new OverAllState(
                        Map.of(
                                FINAL_ANSWER,
                                "fallback",
                                FINISH_REASON,
                                reason,
                                LLM_CALL_COUNT,
                                1,
                                RUNTIME_MODEL_NAME,
                                "attempted-model"));
        when(graph.stream(anyMap(), any(RunnableConfig.class)))
                .thenReturn(Flux.just(NodeOutput.of("reasoning", "test", state, null)));
        var agent =
                new StateGraphReActAgent(null, mock(ConversationService.class), graph, null, null);
        var deltas =
                (replay
                                ? agent.chatWithReplayStream("question", "same", "", "user")
                                : agent.chatStructuredStream("question", "same"))
                        .collectList()
                        .block(Duration.ofSeconds(5));
        assertNotNull(deltas);
        assertTrue(
                deltas.stream()
                        .noneMatch(d -> d.isEvent() && "_usage_final".equals(d.eventType())));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void modelObservationDoesNotCarryIntoNextTurn(boolean replay) {
        var graph = mock(CompiledGraph.class);
        var calls = new ArrayList<Map<String, Object>>();
        when(graph.stream(anyMap(), any(RunnableConfig.class)))
                .thenAnswer(
                        invocation -> {
                            Map<String, Object> inputs = invocation.getArgument(0);
                            calls.add(new HashMap<>(inputs));
                            var output = new HashMap<>(inputs);
                            output.put(FINAL_ANSWER, "answer");
                            output.put(FINISH_REASON, "normal");
                            output.put(
                                    LLM_CALL_COUNT,
                                    calls.size() == 1 ? 1 : inputs.getOrDefault(LLM_CALL_COUNT, 0));
                            return Flux.just(
                                    NodeOutput.of(
                                            "reasoning", "test", new OverAllState(output), null));
                        });
        var agent =
                new StateGraphReActAgent(null, mock(ConversationService.class), graph, null, null);
        for (int turn = 0; turn < 2; turn++) {
            var deltas =
                    (replay
                                    ? agent.chatWithReplayStream("question", "same", "", "user")
                                    : agent.chatStructuredStream("question", "same"))
                            .collectList()
                            .block(Duration.ofSeconds(5));
            assertNotNull(deltas);
            assertEquals(
                    turn == 0 ? 1 : 0,
                    deltas.stream()
                            .filter(d -> d.isEvent() && "_usage_final".equals(d.eventType()))
                            .count());
        }
        assertEquals(2, calls.size());
        calls.forEach(input -> assertEquals(0, input.get(LLM_CALL_COUNT)));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void toolResponseRetainsIdentityWhenLaterAttemptStops(boolean replay) {
        var graph = mock(CompiledGraph.class);
        var responded =
                new OverAllState(
                        Map.of(
                                NEEDS_TOOL_CALL,
                                true,
                                LLM_CALL_COUNT,
                                1,
                                RUNTIME_MODEL_NAME,
                                "responding-model",
                                RUNTIME_PROVIDER_ID,
                                "responding-provider"));
        var stopped =
                new OverAllState(
                        Map.of(
                                NEEDS_TOOL_CALL,
                                false,
                                LLM_CALL_COUNT,
                                2,
                                FINISH_REASON,
                                "stopped",
                                FINAL_ANSWER,
                                "",
                                RUNTIME_MODEL_NAME,
                                "responding-model",
                                RUNTIME_PROVIDER_ID,
                                "responding-provider"));
        when(graph.stream(anyMap(), any(RunnableConfig.class)))
                .thenReturn(
                        Flux.just(
                                NodeOutput.of("reasoning", "test", responded, null),
                                NodeOutput.of("reasoning", "test", stopped, null)));
        var agent =
                new StateGraphReActAgent(null, mock(ConversationService.class), graph, null, null);
        var conversation = "attribution-tool-" + java.util.UUID.randomUUID();
        var deltas =
                (replay
                                ? agent.chatWithReplayStream("question", conversation, "", "user")
                                : agent.chatStructuredStream("question", conversation))
                        .collectList()
                        .block(Duration.ofSeconds(5));
        assertNotNull(deltas);
        var usage =
                deltas.stream()
                        .filter(d -> d.isEvent() && "_usage_final".equals(d.eventType()))
                        .toList();
        assertEquals(1, usage.size());
        assertEquals("responding-model", usage.getFirst().eventData().get("runtimeModelName"));
        assertEquals("responding-provider", usage.getFirst().eventData().get("runtimeProviderId"));
        assertEquals(0L, ((Number) usage.getFirst().eventData().get("promptTokens")).longValue());
    }
}

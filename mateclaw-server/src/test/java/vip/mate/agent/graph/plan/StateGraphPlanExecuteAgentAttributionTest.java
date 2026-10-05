package vip.mate.agent.graph.plan;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static vip.mate.agent.graph.state.MateClawStateKeys.*;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import reactor.core.publisher.Flux;
import vip.mate.agent.graph.plan.state.PlanStateKeys;
import vip.mate.planning.service.PlanningService;
import vip.mate.workspace.conversation.ConversationService;

class StateGraphPlanExecuteAgentAttributionTest {
    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void onlyObservedResponsesAttributeZeroUsageAndResetPerTurn(boolean replay, boolean observed) {
        var graph = mock(CompiledGraph.class);
        var state =
                new OverAllState(
                        Map.of(
                                PlanStateKeys.FINAL_SUMMARY,
                                "answer",
                                MODEL_RESPONSE_OBSERVED,
                                observed,
                                RUNTIME_MODEL_NAME,
                                "actual-model",
                                RUNTIME_PROVIDER_ID,
                                "actual-provider"));
        when(graph.stream(anyMap(), any(RunnableConfig.class)))
                .thenAnswer(
                        invocation -> {
                            Map<String, Object> input = invocation.getArgument(0);
                            assertEquals(false, input.get(MODEL_RESPONSE_OBSERVED));
                            return Flux.just(NodeOutput.of("summary", "test", state, null));
                        });
        var agent =
                new StateGraphPlanExecuteAgent(
                        null,
                        mock(ConversationService.class),
                        graph,
                        mock(PlanningService.class),
                        null,
                        null);
        for (int turn = 0; turn < 2; turn++) {
            var stream =
                    replay
                            ? agent.chatWithReplayStream("question", "plan-attribution", "")
                            : agent.chatStructuredStream("question", "plan-attribution");
            var events = stream.collectList().block(Duration.ofSeconds(5));
            assertNotNull(events);
            var usage =
                    events.stream()
                            .filter(d -> d.isEvent() && "_usage_final".equals(d.eventType()))
                            .toList();
            assertEquals(observed ? 1 : 0, usage.size());
            if (observed) {
                assertEquals("actual-model", usage.getFirst().eventData().get("runtimeModelName"));
                assertEquals(
                        "actual-provider", usage.getFirst().eventData().get("runtimeProviderId"));
                assertEquals(
                        0L,
                        ((Number) usage.getFirst().eventData().get("promptTokens")).longValue());
            }
        }
    }
}

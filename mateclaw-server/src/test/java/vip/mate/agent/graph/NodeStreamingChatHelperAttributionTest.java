package vip.mate.agent.graph;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static vip.mate.agent.graph.state.MateClawStateKeys.*;

import com.alibaba.cloud.ai.graph.OverAllState;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import vip.mate.agent.graph.plan.state.PlanStateAccessor;
import vip.mate.agent.graph.state.MateClawStateAccessor;
import vip.mate.channel.web.ChatStreamTracker;
import vip.mate.llm.failover.FallbackEntry;

class NodeStreamingChatHelperAttributionTest {
    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void actualResponseIdentityReachesBothGraphStates(boolean failover, boolean usageReported) {
        var tracker = mock(ChatStreamTracker.class);
        var primary = mock(ChatModel.class);
        var failedBackup = mock(ChatModel.class);
        var winner = failover ? mock(ChatModel.class) : primary;
        if (failover) {
            when(primary.stream(any(Prompt.class)))
                    .thenReturn(Flux.error(new RuntimeException("401 Unauthorized")));
            when(failedBackup.stream(any(Prompt.class)))
                    .thenReturn(Flux.error(new RuntimeException("401 Unauthorized")));
        }
        var metadata = mock(ChatResponseMetadata.class);
        when(metadata.getModel()).thenReturn("actual-answer-model");
        if (usageReported) {
            var usage = mock(Usage.class);
            when(usage.getPromptTokens()).thenReturn(3);
            when(usage.getCompletionTokens()).thenReturn(5);
            when(metadata.getUsage()).thenReturn(usage);
        }
        var response =
                new ChatResponse(List.of(new Generation(new AssistantMessage("answer"))), metadata);
        when(winner.stream(any(Prompt.class))).thenReturn(Flux.just(response));
        var chain =
                failover
                        ? List.of(
                                new FallbackEntry("failed-backup", failedBackup),
                                new FallbackEntry("winning-provider", winner))
                        : List.<FallbackEntry>of();
        var helper = new NodeStreamingChatHelper(tracker, chain, null, null, "primary-provider");
        var result =
                helper.streamCall(
                        primary,
                        new Prompt(new UserMessage("hello")),
                        "attribution-" + java.util.UUID.randomUUID(),
                        "test");
        assertEquals("answer", result.text());
        var original =
                new OverAllState(
                        Map.of(
                                RUNTIME_MODEL_NAME,
                                "configured-primary",
                                RUNTIME_PROVIDER_ID,
                                "primary-provider",
                                PROMPT_TOKENS,
                                10,
                                COMPLETION_TOKENS,
                                20));
        var react = MateClawStateAccessor.output().mergeUsage(original, result).build();
        var plan = PlanStateAccessor.output().mergeUsage(original, result).build();
        for (var output : List.of(react, plan)) {
            assertEquals("actual-answer-model", output.get(RUNTIME_MODEL_NAME));
            assertEquals(
                    failover ? "winning-provider" : "primary-provider",
                    output.get(RUNTIME_PROVIDER_ID));
            assertEquals(usageReported ? 13 : 10, output.get(PROMPT_TOKENS));
            assertEquals(usageReported ? 25 : 20, output.get(COMPLETION_TOKENS));
        }
        verify(winner).stream(any(Prompt.class));
        if (failover) {
            verify(primary).stream(any(Prompt.class));
            verify(failedBackup).stream(any(Prompt.class));
        }
    }
}

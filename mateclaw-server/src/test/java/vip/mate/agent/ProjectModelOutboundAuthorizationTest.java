package vip.mate.agent;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static vip.mate.agent.graph.state.MateClawStateKeys.*;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.agent.execution.ProjectExecutionOptions;
import vip.mate.agent.execution.ProjectToolPolicy;
import vip.mate.agent.graph.NodeStreamingChatHelper;
import vip.mate.agent.graph.executor.ToolExecutionExecutor;
import vip.mate.agent.graph.node.LimitExceededNode;
import vip.mate.agent.graph.node.ReasoningNode;
import vip.mate.agent.graph.node.SummarizingNode;
import vip.mate.agent.graph.observation.ObservationProcessor;
import vip.mate.channel.web.ChatStreamTracker;
import vip.mate.config.GraphObservationProperties;
import vip.mate.goal.config.GoalProperties;
import vip.mate.llm.failover.FallbackEntry;
import vip.mate.tool.guard.ToolGuard;
import vip.mate.tool.guard.ToolGuardResult;

class ProjectModelOutboundAuthorizationTest {
    private static final String CONVERSATION = "project-outbound-attempt";
    private static final String SOURCE_TOOL = "read_authorized_source";
    private static final String SOURCE = "RETAINED_AUTHORIZED_SOURCE_v4";

    public record FixturePolicy() implements ProjectToolPolicy {
        @Override
        public void require(String name, String arguments) {}
    }

    private final ChatStreamTracker tracker = mock(ChatStreamTracker.class);
    private final ProjectExecutionOptions options =
            new ProjectExecutionOptions(
                    "attempt",
                    "1",
                    "model-digest",
                    "skill",
                    "skill-digest",
                    Map.of(),
                    Set.of(SOURCE_TOOL),
                    new FixturePolicy(),
                    0,
                    false,
                    false,
                    12);
    private final AtomicBoolean allowed = new AtomicBoolean(true);
    private final AtomicInteger checks = new AtomicInteger();
    private final SecurityException denied = new SecurityException("ACCESS_REVOKED");
    private final ProjectToolPolicy.Revalidator revalidator =
            actual -> {
                assertSame(
                        options,
                        actual,
                        "the exact trusted attempt must reach the existing policy");
                checks.incrementAndGet();
                if (!allowed.get()) throw denied;
            };

    @ParameterizedTest
    @ValueSource(strings = {"reasoning", "summarizing", "limit"})
    void revocationAfterSuccessfulToolPostcheckPreventsRetainedSourceEgress(String consumer)
            throws Exception {
        OverAllState state = state(readAuthorizedSource());
        assertEquals(2, checks.get(), "the actual tool executor passed both authorization checks");
        allowed.set(false);
        ChatModel model = successModel();
        assertSame(
                denied,
                assertThrows(
                        SecurityException.class,
                        () -> node(consumer, model, helper(revalidator, List.of())).apply(state)));
        verify(model, never()).stream(any(Prompt.class));
        assertEquals(3, checks.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"reasoning", "summarizing", "limit"})
    void currentlyAuthorizedRetainedSourceStillReachesEachConsumer(String consumer)
            throws Exception {
        OverAllState state = state(readAuthorizedSource());
        ChatModel model = successModel();
        node(consumer, model, helper(revalidator, List.of())).apply(state);
        var sent = ArgumentCaptor.forClass(Prompt.class);
        verify(model).stream(sent.capture());
        assertTrue(promptText(sent.getValue()).contains(SOURCE));
        assertEquals(3, checks.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"reasoning", "summarizing", "limit"})
    void missingProjectRevalidatorFailsClosedAtEveryConsumer(String consumer) {
        ChatModel model = successModel();
        assertThrows(
                IllegalStateException.class,
                () -> node(consumer, model, helper(null, List.of())).apply(state(List.of())));
        verify(model, never()).stream(any(Prompt.class));
    }

    @Test
    void authorizationIsRecheckedAfterProviderErrorBeforeRetry() {
        ChatModel model = mock(ChatModel.class);
        when(model.stream(any(Prompt.class)))
                .thenAnswer(
                        call -> {
                            allowed.set(false);
                            return Flux.error(new RuntimeException("503 Service Unavailable"));
                        });
        NodeStreamingChatHelper helper = helper(revalidator, List.of());
        ReflectionTestUtils.invokeMethod(helper, "setRetryTimingForTest", 0L, 0L, 30_000L);
        assertSame(
                denied,
                assertThrows(
                        SecurityException.class,
                        () ->
                                helper.streamCall(
                                        model, new Prompt(SOURCE), CONVERSATION, "reasoning")));
        verify(model, times(1)).stream(any(Prompt.class));
        assertEquals(2, checks.get());
    }

    @Test
    void authorizationFailureBeforeFallbackIsNotClassifiedAsProviderFailure() {
        ChatModel primary = mock(ChatModel.class);
        ChatModel fallback = successModel();
        when(primary.stream(any(Prompt.class)))
                .thenAnswer(
                        call -> {
                            allowed.set(false);
                            return Flux.error(
                                    new RuntimeException("401 Unauthorized: Invalid API Key"));
                        });
        NodeStreamingChatHelper helper =
                helper(revalidator, List.of(new FallbackEntry("fallback", fallback)));
        assertSame(
                denied,
                assertThrows(
                        SecurityException.class,
                        () ->
                                helper.streamCall(
                                        primary, new Prompt(SOURCE), CONVERSATION, "reasoning")));
        verify(primary).stream(any(Prompt.class));
        verify(fallback, never()).stream(any(Prompt.class));
        assertEquals(2, checks.get());
    }

    @Test
    void ordinaryChatDoesNotRequireProjectPolicy() {
        ChatModel model = successModel();
        assertEquals(
                "answer",
                new NodeStreamingChatHelper(tracker)
                        .streamCall(model, new Prompt("ordinary"), CONVERSATION, "reasoning")
                        .text());
        verify(model).stream(any(Prompt.class));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void graphBuilderBindsTheTrustedAttemptToItsActualModelConsumer(boolean authorized)
            throws Exception {
        AgentGraphBuilder builder = mock(AgentGraphBuilder.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(builder, "streamTracker", tracker);
        ReflectionTestUtils.setField(builder, "projectExecutionRevalidator", revalidator);
        ReflectionTestUtils.setField(
                builder, "graphObservationProperties", new GraphObservationProperties());
        GoalProperties goals = new GoalProperties();
        goals.setEnabled(false);
        ReflectionTestUtils.setField(builder, "goalProperties", goals);
        allowed.set(authorized);
        ChatModel model = successModel();
        var graph =
                builder.buildReActGraph(
                        AgentToolSet.fromCallbacks(List.of(), List.of()),
                        model,
                        12,
                        null,
                        null,
                        1L,
                        null,
                        null,
                        Set.of(),
                        true,
                        options);
        if (authorized) {
            assertTrue(
                    graph.invoke(
                                    state(List.of()).data(),
                                    RunnableConfig.builder().threadId("allowed").build())
                            .isPresent());
            verify(model).stream(any(Prompt.class));
        } else {
            Exception failure =
                    assertThrows(
                            Exception.class,
                            () ->
                                    graph.invoke(
                                            state(List.of()).data(),
                                            RunnableConfig.builder().threadId("denied").build()));
            Throwable cause = failure;
            while (cause.getCause() != null) cause = cause.getCause();
            assertSame(denied, cause);
            verify(model, never()).stream(any(Prompt.class));
        }
        assertEquals(1, checks.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"authorized", "revoked", "missing", "ordinary"})
    void initialWindowSummaryCannotSendOldSourceWithoutCurrentAuthority(String mode) {
        var properties = new vip.mate.config.ConversationWindowProperties();
        properties.setDefaultMaxInputTokens(1000);
        var conversations = mock(vip.mate.workspace.conversation.ConversationService.class);
        var manager =
                new vip.mate.agent.context.ConversationWindowManager(
                        properties, null, conversations);
        var graph = mock(com.alibaba.cloud.ai.graph.CompiledGraph.class);
        when(graph.stream(anyMap(), any(RunnableConfig.class))).thenReturn(Flux.empty());
        ChatModel model = successModel();
        when(model.call(any(Prompt.class)))
                .thenReturn(
                        new ChatResponse(List.of(new Generation(new AssistantMessage("summary")))));
        List<Message> history = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++)
            history.add(new UserMessage(SOURCE + " source text".repeat(300)));
        AgentGraphBuilder builder = mock(AgentGraphBuilder.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(builder, "conversationService", conversations);
        ReflectionTestUtils.setField(builder, "conversationWindowManager", manager);
        ReflectionTestUtils.setField(
                builder,
                "projectExecutionRevalidator",
                mode.equals("missing") ? null : revalidator);
        var providers = mock(vip.mate.llm.service.ModelProviderService.class);
        when(providers.readProviderGenerateKwargs(any())).thenReturn(Map.of());
        ReflectionTestUtils.setField(builder, "modelProviderService", providers);
        var config = new vip.mate.llm.model.ModelConfigEntity();
        config.setProvider("fixture");
        config.setModelName("fixture-model");
        doReturn(model).when(builder).buildRuntimeChatModel(same(config), any());
        doReturn(model).when(builder).buildRuntimeChatModel(same(config));
        doReturn(graph)
                .when(builder)
                .buildReActGraph(
                        any(),
                        same(model),
                        anyInt(),
                        any(),
                        same(config),
                        any(),
                        any(),
                        any(),
                        anySet(),
                        anyBoolean(),
                        any());
        var agent =
                spy(
                        builder.buildReActAgent(
                                AgentToolSet.fromCallbacks(List.of(), List.of()),
                                config,
                                12,
                                1L,
                                null,
                                null,
                                Set.of(),
                                !mode.equals("ordinary"),
                                mode.equals("ordinary") ? null : options));
        doReturn(history).when(agent).buildConversationHistory(CONVERSATION, "source question");
        allowed.set(!mode.equals("revoked"));
        agent.chatStructuredStream(
                        "source question",
                        CONVERSATION,
                        "actor",
                        mode.equals("ordinary") ? null : options)
                .blockLast();
        if (mode.equals("authorized") || mode.equals("ordinary")) {
            var sent = ArgumentCaptor.forClass(Prompt.class);
            verify(model).call(sent.capture());
            assertTrue(promptText(sent.getValue()).contains(SOURCE));
            assertEquals(mode.equals("ordinary") ? 0 : 1, checks.get());
        } else {
            verify(model, never()).call(any(Prompt.class));
            assertEquals(mode.equals("missing") ? 0 : 1, checks.get());
        }
    }

    private List<ToolResponseMessage.ToolResponse> readAuthorizedSource() {
        ToolCallback callback = mock(ToolCallback.class);
        when(callback.getToolDefinition())
                .thenReturn(
                        ToolDefinition.builder()
                                .name(SOURCE_TOOL)
                                .description("source fixture")
                                .inputSchema("{}")
                                .build());
        when(callback.getToolMetadata())
                .thenReturn(ToolMetadata.builder().returnDirect(false).build());
        when(callback.call(anyString(), any())).thenReturn(SOURCE);
        ToolExecutionExecutor executor =
                new ToolExecutionExecutor(
                        AgentToolSet.fromCallbacks(List.of(), List.of(callback)),
                        (ToolGuard) (name, args) -> ToolGuardResult.allow(),
                        null,
                        null);
        executor.setProjectExecutionRevalidator(revalidator);
        var result =
                executor.execute(
                        List.of(
                                new AssistantMessage.ToolCall(
                                        "source-call", "function", SOURCE_TOOL, "{}")),
                        CONVERSATION,
                        "agent",
                        false,
                        "actor",
                        null,
                        ChatOrigin.EMPTY,
                        Set.of(),
                        options);
        assertEquals(SOURCE, result.responses().getFirst().responseData());
        return result.responses();
    }

    private OverAllState state(List<ToolResponseMessage.ToolResponse> responses) {
        Map<String, Object> values = new HashMap<>();
        values.put(CONVERSATION_ID, CONVERSATION);
        values.put(SYSTEM_PROMPT, "Answer from authorized sources");
        values.put(USER_MESSAGE, "source question");
        values.put(
                MESSAGES,
                responses.isEmpty()
                        ? List.of(new UserMessage("question"))
                        : List.of(
                                new UserMessage("source question"),
                                ToolResponseMessage.builder().responses(responses).build()));
        values.put(
                OBSERVATION_HISTORY,
                responses.stream().map(ToolResponseMessage.ToolResponse::responseData).toList());
        values.put(MAX_ITERATIONS, 12);
        values.put(CURRENT_ITERATION, 1);
        values.put(PROJECT_EXECUTION_OPTIONS, options);
        return new OverAllState(values);
    }

    private NodeAction node(String consumer, ChatModel model, NodeStreamingChatHelper helper) {
        return switch (consumer) {
            case "reasoning" ->
                    new ReasoningNode(
                            model,
                            AgentToolSet.fromCallbacks(List.of(), List.of()),
                            null,
                            helper,
                            null,
                            tracker);
            case "summarizing" -> new SummarizingNode(model, helper, tracker);
            case "limit" ->
                    new LimitExceededNode(
                            model,
                            new ObservationProcessor(new GraphObservationProperties()),
                            helper);
            default -> throw new IllegalArgumentException(consumer);
        };
    }

    private NodeStreamingChatHelper helper(
            ProjectToolPolicy.Revalidator policy, List<FallbackEntry> fallbacks) {
        return new NodeStreamingChatHelper(
                tracker, fallbacks, null, null, null, null, null, options, policy);
    }

    private ChatModel successModel() {
        ChatModel model = mock(ChatModel.class);
        when(model.stream(any(Prompt.class)))
                .thenReturn(
                        Flux.just(
                                new ChatResponse(
                                        List.of(new Generation(new AssistantMessage("answer"))))));
        return model;
    }

    private String promptText(Prompt prompt) {
        return prompt.getInstructions().stream()
                .map(
                        message ->
                                message instanceof ToolResponseMessage tool
                                        ? tool.getResponses().toString()
                                        : message.getText())
                .reduce("", String::concat);
    }
}

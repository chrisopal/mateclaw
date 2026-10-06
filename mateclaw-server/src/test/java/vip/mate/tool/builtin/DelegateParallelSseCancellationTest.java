package vip.mate.tool.builtin;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.*;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import vip.mate.agent.AgentService;
import vip.mate.agent.AgentService.ChatResult;
import vip.mate.agent.delegation.DelegatedUsageAccumulator;
import vip.mate.agent.delegation.SubagentRegistry;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.repository.AgentMapper;
import vip.mate.audit.service.AuditEventService;
import vip.mate.channel.web.ChatStreamTracker;
import vip.mate.workspace.conversation.ConversationService;

@ExtendWith(MockitoExtension.class)
@Timeout(20)
class DelegateParallelSseCancellationTest {
    private static final String ROOT = "slow-sse-parent";
    @Mock AgentService agentService;
    @Mock AgentMapper agentMapper;
    @Mock ConversationService conversationService;
    @Mock AuditEventService auditEventService;
    @Spy ChatStreamTracker streamTracker = new ChatStreamTracker(new ObjectMapper());
    @Spy SubagentRegistry subagentRegistry = new SubagentRegistry();
    @Spy DelegatedUsageAccumulator delegatedUsageAccumulator = new DelegatedUsageAccumulator();
    @InjectMocks DelegateAgentTool tool;

    @BeforeAll
    static void initTables() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new org.apache.ibatis.session.Configuration(), ""),
                AgentEntity.class);
    }

    @BeforeEach
    void setup() {
        ReflectionTestUtils.setField(tool, "objectMapper", new ObjectMapper());
        streamTracker.register(ROOT);
        streamTracker.incrementFlux(ROOT);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void realChildRelayCannotDelayBothSiblingCancellations(boolean failFast) throws Exception {
        ReflectionTestUtils.setField(tool, "parallelTimeoutSeconds", failFast ? 10 : 1);
        when(agentMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(agent(101L, "A"), agent(102L, "B"), agent(103L, "C"));
        CountDownLatch siblingsStarted = new CountDownLatch(2);
        CountDownLatch releaseModels = new CountDownLatch(1);
        CountDownLatch notificationStarted = new CountDownLatch(1);
        CountDownLatch releaseNotification = new CountDownLatch(1);
        CountDownLatch childrenFinished = new CountDownLatch(3);
        CountDownLatch disposed = new CountDownLatch(2);
        List<String> siblingIds = new CopyOnWriteArrayList<>();
        Runnable hookB = mock(Runnable.class), hookC = mock(Runnable.class);
        Disposable disposableB = mock(Disposable.class), disposableC = mock(Disposable.class);
        doAnswer(
                        call -> {
                            disposed.countDown();
                            return null;
                        })
                .when(disposableB)
                .dispose();
        doAnswer(
                        call -> {
                            disposed.countDown();
                            return null;
                        })
                .when(disposableC)
                .dispose();
        doAnswer(
                        call -> {
                            Object result = call.callRealMethod();
                            childrenFinished.countDown();
                            return result;
                        })
                .when(streamTracker)
                .complete(argThat((String id) -> !ROOT.equals(id)));
        SseEmitter emitter =
                new SseEmitter() {
                    @Override
                    public void send(SseEventBuilder event) throws IOException {
                        boolean completion =
                                event.build().stream()
                                        .anyMatch(
                                                part ->
                                                        part.getData()
                                                                .toString()
                                                                .contains(
                                                                        "event:delegation_child_complete"));
                        if (completion) {
                            notificationStarted.countDown();
                            await(releaseNotification);
                        }
                    }
                };
        streamTracker.attach(ROOT, emitter);
        when(agentService.chatWithUsage(anyLong(), anyString(), anyString(), any()))
                .thenAnswer(
                        call -> {
                            long id = call.getArgument(0);
                            if (id == 101L) {
                                await(siblingsStarted);
                                if (failFast)
                                    throw new IllegalStateException("required child failed");
                                return ChatResult.contentOnly("Result A");
                            }
                            String conversation = call.getArgument(2);
                            siblingIds.add(conversation);
                            streamTracker.registerCancellationHook(
                                    conversation, id == 102L ? hookB : hookC);
                            streamTracker.setDisposable(
                                    conversation, id == 102L ? disposableB : disposableC);
                            siblingsStarted.countDown();
                            await(releaseModels);
                            return ChatResult.contentOnly("late result");
                        });
        CompletableFuture<String> batch =
                CompletableFuture.supplyAsync(
                        () -> {
                            ToolExecutionContext.set(ROOT, "admin");
                            try {
                                return tool.delegateParallel(
                                        "[{\"agentName\":\"A\",\"task\":\"a\"},"
                                                + "{\"agentName\":\"B\",\"task\":\"b\"},"
                                                + "{\"agentName\":\"C\",\"task\":\"c\"}]",
                                        null);
                            } finally {
                                ToolExecutionContext.clear();
                            }
                        });
        try {
            await(notificationStarted);
            assertTrue(
                    disposed.await(2, TimeUnit.SECONDS),
                    "both model disposables must stop before the blocked root SSE is released");
            for (String id : siblingIds) assertTrue(streamTracker.isStopRequested(id));
            verify(hookB).run();
            verify(hookC).run();
            verify(disposableB).dispose();
            verify(disposableC).dispose();
            assertFalse(batch.isDone(), "end must still follow the in-flight completion event");
            releaseNotification.countDown();
            String result = batch.get(5, TimeUnit.SECONDS);
            assertTrue(result.contains(failFast ? "cancelled=2" : "timeout=2"), result);
            assertTrue(subagentRegistry.snapshot(ROOT).isEmpty());
        } finally {
            releaseNotification.countDown();
            releaseModels.countDown();
            batch.get(5, TimeUnit.SECONDS);
            await(childrenFinished);
            streamTracker.detach(ROOT, emitter);
            streamTracker.complete(ROOT);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void durableStopFailureStillCancelsSiblingsAndCleansTheBatch(boolean failFast)
            throws Exception {
        ReflectionTestUtils.setField(tool, "parallelTimeoutSeconds", failFast ? 10 : 1);
        when(agentMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(agent(101L, "A"), agent(102L, "B"), agent(103L, "C"));
        var context = mock(org.springframework.context.ApplicationContext.class);
        ReflectionTestUtils.setField(streamTracker, "applicationContext", context);
        var siblingsStarted = new CountDownLatch(2);
        var releaseModels = new CountDownLatch(1);
        var childrenFinished = new CountDownLatch(3);
        var disposed = new CountDownLatch(2);
        var siblingIds = new ConcurrentHashMap<Long, String>();
        List<String> stopAttempts = new CopyOnWriteArrayList<>();
        List<Runnable> relayStops = new CopyOnWriteArrayList<>();
        List<com.fasterxml.jackson.databind.JsonNode> endings = new CopyOnWriteArrayList<>();
        Runnable hookB = mock(Runnable.class), hookC = mock(Runnable.class);
        Disposable disposableB = mock(Disposable.class), disposableC = mock(Disposable.class);
        for (var disposable : List.of(disposableB, disposableC)) {
            doAnswer(
                            call -> {
                                disposed.countDown();
                                return null;
                            })
                    .when(disposable)
                    .dispose();
        }
        doAnswer(
                        call -> {
                            Object event = call.getArgument(0);
                            if (event
                                    instanceof
                                    vip.mate.goal.service.GoalExecutionSignal.Stop stop) {
                                stopAttempts.add(stop.conversationId());
                                if (stop.conversationId().equals(siblingIds.get(102L)))
                                    throw new IllegalStateException(
                                            "synthetic durable stop failure");
                            }
                            return null;
                        })
                .when(context)
                .publishEvent(any(Object.class));
        doAnswer(
                        call -> {
                            Runnable actual = (Runnable) call.callRealMethod();
                            Runnable tracked = mock(Runnable.class);
                            doAnswer(
                                            ignored -> {
                                                actual.run();
                                                return null;
                                            })
                                    .when(tracked)
                                    .run();
                            relayStops.add(tracked);
                            return tracked;
                        })
                .when(streamTracker)
                .addBatchedEventRelay(anyString(), anyString(), anyInt(), anyLong(), any());
        doAnswer(
                        call -> {
                            if ("delegation_end".equals(call.getArgument(1)))
                                endings.add(new ObjectMapper().valueToTree(call.getArgument(2)));
                            return call.callRealMethod();
                        })
                .when(streamTracker)
                .broadcastObject(eq(ROOT), anyString(), any());
        doAnswer(
                        call -> {
                            Object result = call.callRealMethod();
                            childrenFinished.countDown();
                            return result;
                        })
                .when(streamTracker)
                .complete(argThat((String id) -> !ROOT.equals(id)));
        when(agentService.chatWithUsage(anyLong(), anyString(), anyString(), any()))
                .thenAnswer(
                        call -> {
                            long id = call.getArgument(0);
                            if (id == 101L) {
                                await(siblingsStarted);
                                if (failFast)
                                    throw new IllegalStateException("required child failed");
                                return ChatResult.contentOnly("Result A");
                            }
                            String conversation = call.getArgument(2);
                            siblingIds.put(id, conversation);
                            streamTracker.registerCancellationHook(
                                    conversation, id == 102L ? hookB : hookC);
                            streamTracker.setDisposable(
                                    conversation, id == 102L ? disposableB : disposableC);
                            siblingsStarted.countDown();
                            await(releaseModels);
                            return ChatResult.contentOnly("late result");
                        });
        CompletableFuture<String> batch =
                CompletableFuture.supplyAsync(
                        () -> {
                            ToolExecutionContext.set(ROOT, "admin");
                            try {
                                return tool.delegateParallel(
                                        "[{\"agentName\":\"A\",\"task\":\"a\"},"
                                                + "{\"agentName\":\"B\",\"task\":\"b\"},"
                                                + "{\"agentName\":\"C\",\"task\":\"c\"}]",
                                        null);
                            } finally {
                                ToolExecutionContext.clear();
                            }
                        });
        try {
            await(siblingsStarted);
            assertTrue(
                    disposed.await(3, TimeUnit.SECONDS),
                    "one failed durable stop must not prevent the next sibling's local cancellation");
            String result = batch.get(5, TimeUnit.SECONDS);
            assertTrue(
                    result.contains(
                            failFast ? "cancelled=1 error=2" : "timeout=1 cancelled=0 error=1"),
                    result);
            for (String id : siblingIds.values()) {
                assertTrue(stopAttempts.contains(id));
                assertTrue(streamTracker.isStopRequested(id));
            }
            verify(hookB).run();
            verify(hookC).run();
            verify(disposableB).dispose();
            verify(disposableC).dispose();
            assertEquals(3, relayStops.size());
            for (Runnable stop : relayStops) verify(stop).run();
            assertTrue(subagentRegistry.snapshot(ROOT).isEmpty());
            assertEquals(1, endings.size());
            var children = endings.getFirst().path("childResults");
            assertEquals(3, children.size());
            var failedStop =
                    java.util.stream.StreamSupport.stream(children.spliterator(), false)
                            .filter(child -> child.path("taskIndex").asInt() == 1)
                            .findFirst()
                            .orElseThrow();
            assertEquals("error", failedStop.path("outcome").asText());
            assertFalse(failedStop.path("success").asBoolean());
            assertTrue(failedStop.path("error").asText().contains("stop"));
            releaseModels.countDown();
            await(childrenFinished);
            assertEquals(1, endings.size(), "late completions must not reopen the closed batch");
            assertTrue(subagentRegistry.snapshot(ROOT).isEmpty());
        } finally {
            releaseModels.countDown();
            batch.handle((value, failure) -> null).get(5, TimeUnit.SECONDS);
            await(childrenFinished);
            streamTracker.complete(ROOT);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void completedChildCannotDeliverInlineOnTheRegistrationOwner(boolean optional)
            throws Exception {
        var child =
                CompletableFuture.completedFuture(
                        DelegateAgentTool.ChildResult.ofError(0, "A", "completed failure"));
        var requiredFailure = new CompletableFuture<Void>();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var returned = new CountDownLatch(1);
        var notificationFinished = new CountDownLatch(1);
        var registration =
                CompletableFuture.runAsync(
                        () -> {
                            DelegateAgentTool.registerParallelCompletion(
                                    child,
                                    optional,
                                    requiredFailure,
                                    (result, error) -> {
                                        entered.countDown();
                                        try {
                                            await(release);
                                        } finally {
                                            notificationFinished.countDown();
                                        }
                                    });
                            returned.countDown();
                        });
        try {
            await(entered);
            assertAll(
                    () ->
                            assertTrue(
                                    returned.await(2, TimeUnit.SECONDS),
                                    "already-completed notification must not block further dispatch"),
                    () ->
                            assertEquals(
                                    !optional,
                                    requiredFailure.isDone(),
                                    "required failure must signal without waiting for delivery"));
        } finally {
            release.countDown();
            registration.get(5, TimeUnit.SECONDS);
            await(notificationFinished);
            streamTracker.complete(ROOT);
        }
    }

    private static AgentEntity agent(long id, String name) {
        AgentEntity value = new AgentEntity();
        value.setId(id);
        value.setName(name);
        value.setEnabled(true);
        value.setWorkspaceId(1L);
        value.setAgentType("react");
        return value;
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "controlled checkpoint not reached");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}

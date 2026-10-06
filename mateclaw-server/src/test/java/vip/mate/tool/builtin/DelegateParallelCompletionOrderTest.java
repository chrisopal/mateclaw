package vip.mate.tool.builtin;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
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
@Timeout(15)
class DelegateParallelCompletionOrderTest {
    private static final String ROOT = "ordered-parent";
    @Mock AgentService agentService;
    @Mock AgentMapper agentMapper;
    @Mock ChatStreamTracker streamTracker;
    @Mock ConversationService conversationService;
    @Mock AuditEventService auditEventService;
    @Mock Runnable stopRelay;
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
        ReflectionTestUtils.setField(tool, "parallelTimeoutSeconds", 3);
        when(streamTracker.isRunning(ROOT)).thenReturn(true);
        when(streamTracker.addBatchedEventRelay(
                        anyString(), anyString(), anyInt(), anyLong(), any()))
                .thenReturn(stopRelay);
    }

    @Test
    void batchEndWaitsForAlreadyStartedChildNotification() throws Exception {
        var a = agent(101L, "A");
        var b = agent(102L, "B");
        when(agentMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(a, b);
        CountDownLatch bStarted = new CountDownLatch(1);
        CountDownLatch releaseB = new CountDownLatch(1);
        CountDownLatch notificationStarted = new CountDownLatch(1);
        CountDownLatch releaseNotification = new CountDownLatch(1);
        List<String> events = new CopyOnWriteArrayList<>();
        when(agentService.chatWithUsage(anyLong(), anyString(), anyString(), any()))
                .thenAnswer(
                        call -> {
                            if (call.<Long>getArgument(0) == 101L) {
                                await(bStarted);
                                return ChatResult.contentOnly("Result A");
                            }
                            bStarted.countDown();
                            await(releaseB);
                            return ChatResult.contentOnly("Result B");
                        });
        recordEvents(events, notificationStarted, releaseNotification);
        CompletableFuture<String> batch = runBatch(2);
        try {
            await(notificationStarted);
            releaseB.countDown();
            assertThrows(
                    TimeoutException.class,
                    () -> batch.get(250, TimeUnit.MILLISECONDS),
                    "end must not overtake the in-flight notification");
            assertFalse(events.contains("delegation_end"));
            releaseNotification.countDown();
            assertTrue(batch.get(5, TimeUnit.SECONDS).contains("success=2"));
            assertEquals(1, events.stream().filter("child-0"::equals).count());
            assertEquals(1, events.stream().filter("child-1"::equals).count());
            assertEquals("delegation_end", events.getLast());
            assertCleanup(2);
        } finally {
            releaseB.countDown();
            releaseNotification.countDown();
            batch.get(5, TimeUnit.SECONDS);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void stopsBothSiblingsBeforeWaitingForSlowNotification(boolean failFast) throws Exception {
        ReflectionTestUtils.setField(tool, "parallelTimeoutSeconds", failFast ? 10 : 1);
        when(agentMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(agent(101L, "A"), agent(102L, "B"), agent(103L, "C"));
        CountDownLatch siblingsStarted = new CountDownLatch(2);
        CountDownLatch releaseSiblings = new CountDownLatch(1);
        CountDownLatch childrenCompleted = new CountDownLatch(3);
        doAnswer(
                        call -> {
                            childrenCompleted.countDown();
                            return null;
                        })
                .when(streamTracker)
                .complete(anyString());
        CountDownLatch stopped = new CountDownLatch(2);
        CountDownLatch notificationStarted = new CountDownLatch(1);
        CountDownLatch releaseNotification = new CountDownLatch(1);
        List<String> events = new CopyOnWriteArrayList<>();
        List<String> siblingIds = new CopyOnWriteArrayList<>();
        List<String> stoppedIds = new CopyOnWriteArrayList<>();
        when(agentService.chatWithUsage(anyLong(), anyString(), anyString(), any()))
                .thenAnswer(
                        call -> {
                            if (call.<Long>getArgument(0) == 101L) {
                                await(siblingsStarted);
                                if (failFast)
                                    throw new IllegalStateException("required child failed");
                                return ChatResult.contentOnly("Result A");
                            }
                            siblingIds.add(call.getArgument(2));
                            siblingsStarted.countDown();
                            await(releaseSiblings);
                            return ChatResult.contentOnly("late result");
                        });
        doAnswer(
                        call -> {
                            stoppedIds.add(call.getArgument(0));
                            stopped.countDown();
                            return null;
                        })
                .when(streamTracker)
                .requestStop(anyString());
        recordEvents(events, notificationStarted, releaseNotification);
        CompletableFuture<String> batch = runBatch(3);
        try {
            await(notificationStarted);
            await(stopped);
            assertTrue(stoppedIds.containsAll(siblingIds));
            assertThrows(
                    TimeoutException.class,
                    () -> batch.get(250, TimeUnit.MILLISECONDS),
                    "cancellation proceeds but end still waits for the in-flight event");
            releaseNotification.countDown();
            String result = batch.get(5, TimeUnit.SECONDS);
            assertTrue(
                    result.contains(
                            failFast
                                    ? "success=0 blank_success=0 timeout=0 cancelled=2 error=1"
                                    : "success=1 blank_success=0 timeout=2"),
                    result);
            assertEquals(List.of("delegation_start", "child-0", "delegation_end"), events);
            assertCleanup(3);
            releaseSiblings.countDown();
            await(childrenCompleted);
            assertEquals(List.of("delegation_start", "child-0", "delegation_end"), events);
        } finally {
            releaseNotification.countDown();
            releaseSiblings.countDown();
            batch.get(5, TimeUnit.SECONDS);
            await(childrenCompleted);
        }
    }

    @Test
    void optionalFailureNotifiesWhileOtherChildStillRuns() throws Exception {
        when(agentMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(agent(101L, "A"), agent(102L, "B"));
        CountDownLatch bStarted = new CountDownLatch(1);
        CountDownLatch releaseB = new CountDownLatch(1);
        CountDownLatch notified = new CountDownLatch(1);
        List<String> events = new CopyOnWriteArrayList<>();
        when(agentService.chatWithUsage(anyLong(), anyString(), anyString(), any()))
                .thenAnswer(
                        call -> {
                            if (call.<Long>getArgument(0) == 101L) {
                                await(bStarted);
                                throw new IllegalStateException("optional child failed");
                            }
                            bStarted.countDown();
                            await(releaseB);
                            return ChatResult.contentOnly("Result B");
                        });
        doAnswer(
                        call -> {
                            String event = call.getArgument(1);
                            if ("delegation_child_complete".equals(event)) {
                                Map<?, ?> payload = call.getArgument(2);
                                events.add("child-" + payload.get("taskIndex"));
                                if (Integer.valueOf(0).equals(payload.get("taskIndex"))) {
                                    assertEquals(false, payload.get("success"));
                                    assertEquals("error", payload.get("outcome"));
                                    notified.countDown();
                                }
                            } else {
                                events.add(event);
                            }
                            return null;
                        })
                .when(streamTracker)
                .broadcastObject(eq(ROOT), anyString(), any());
        CompletableFuture<String> batch =
                runBatch(
                        "[{\"agentName\":\"A\",\"task\":\"a\",\"optional\":true},{\"agentName\":\"B\",\"task\":\"b\"}]");
        try {
            await(notified);
            assertFalse(
                    batch.isDone(),
                    "optional completion must notify before the running sibling finishes");
            verify(streamTracker, never()).requestStop(anyString());
            releaseB.countDown();
            String result = batch.get(5, TimeUnit.SECONDS);
            assertTrue(
                    result.contains("success=1 blank_success=0 timeout=0 cancelled=0 error=1"),
                    result);
            assertEquals(
                    List.of("delegation_start", "child-0", "child-1", "delegation_end"), events);
            assertCleanup(2);
        } finally {
            releaseB.countDown();
            batch.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void notificationFailureDoesNotRetryOrChangeSuccessfulModelResults() throws Exception {
        when(agentMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(agent(101L, "A"), agent(102L, "B"));
        CountDownLatch bStarted = new CountDownLatch(1);
        CountDownLatch attempted = new CountDownLatch(1);
        List<String> events = new CopyOnWriteArrayList<>();
        when(agentService.chatWithUsage(anyLong(), anyString(), anyString(), any()))
                .thenAnswer(
                        call -> {
                            if (call.<Long>getArgument(0) == 101L) {
                                await(bStarted);
                            } else {
                                bStarted.countDown();
                                await(attempted);
                            }
                            return ChatResult.contentOnly("successful model result");
                        });
        doAnswer(
                        call -> {
                            String event = call.getArgument(1);
                            if ("delegation_child_complete".equals(event)) {
                                Map<?, ?> payload = call.getArgument(2);
                                events.add("child-" + payload.get("taskIndex"));
                                if (Integer.valueOf(0).equals(payload.get("taskIndex"))) {
                                    attempted.countDown();
                                    throw new IllegalStateException("synthetic transport failure");
                                }
                            } else {
                                events.add(event);
                            }
                            return null;
                        })
                .when(streamTracker)
                .broadcastObject(eq(ROOT), anyString(), any());
        String result = runBatch(2).get(5, TimeUnit.SECONDS);
        assertTrue(
                result.contains("success=2 blank_success=0 timeout=0 cancelled=0 error=0"), result);
        assertEquals(List.of("delegation_start", "child-0", "child-1", "delegation_end"), events);
        verify(streamTracker, never()).requestStop(anyString());
        assertCleanup(2);
    }

    private void assertCleanup(int children) {
        verify(stopRelay, times(children)).run();
        assertTrue(
                subagentRegistry.snapshot(ROOT).isEmpty(), "batch registrations must be drained");
    }

    private void recordEvents(List<String> events, CountDownLatch entered, CountDownLatch release) {
        doAnswer(
                        call -> {
                            String event = call.getArgument(1);
                            if ("delegation_child_complete".equals(event)) {
                                Map<?, ?> payload = call.getArgument(2);
                                if (Integer.valueOf(0).equals(payload.get("taskIndex"))) {
                                    entered.countDown();
                                    await(release);
                                }
                                events.add("child-" + payload.get("taskIndex"));
                            } else {
                                events.add(event);
                            }
                            return null;
                        })
                .when(streamTracker)
                .broadcastObject(eq(ROOT), anyString(), any());
    }

    private CompletableFuture<String> runBatch(int count) {
        String tasks =
                count == 2
                        ? "[{\"agentName\":\"A\",\"task\":\"a\"},{\"agentName\":\"B\",\"task\":\"b\"}]"
                        : "[{\"agentName\":\"A\",\"task\":\"a\"},{\"agentName\":\"B\",\"task\":\"b\"},{\"agentName\":\"C\",\"task\":\"c\"}]";
        return runBatch(tasks);
    }

    private CompletableFuture<String> runBatch(String tasks) {
        return CompletableFuture.supplyAsync(
                () -> {
                    ToolExecutionContext.set(ROOT, "admin");
                    try {
                        return tool.delegateParallel(tasks, null);
                    } finally {
                        ToolExecutionContext.clear();
                    }
                });
    }

    private static AgentEntity agent(long id, String name) {
        AgentEntity a = new AgentEntity();
        a.setId(id);
        a.setName(name);
        a.setEnabled(true);
        a.setWorkspaceId(1L);
        a.setAgentType("react");
        return a;
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue(
                latch.await(5, TimeUnit.SECONDS),
                "controlled concurrency checkpoint was not reached");
    }
}

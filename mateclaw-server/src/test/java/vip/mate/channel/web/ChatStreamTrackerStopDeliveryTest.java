package vip.mate.channel.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.Disposable;
import vip.mate.goal.service.GoalExecutionSignal;

class ChatStreamTrackerStopDeliveryTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void notificationChoicePreservesStopIntentHooksAndDisposal(boolean silent) {
        var tracker = new ChatStreamTracker(new ObjectMapper());
        var context = mock(ApplicationContext.class);
        ReflectionTestUtils.setField(tracker, "applicationContext", context);
        tracker.register("child");
        tracker.incrementFlux("child");
        List<String> order = new ArrayList<>();
        var disposable = mock(Disposable.class);
        doAnswer(
                        call -> {
                            order.add("dispose");
                            return null;
                        })
                .when(disposable)
                .dispose();
        tracker.setDisposable("child", disposable);
        tracker.registerCancellationHook("child", () -> order.add("hook"));
        Runnable removeRelay = tracker.addEventRelay("child", (event, json) -> order.add(event));
        try {
            assertTrue(
                    silent
                            ? tracker.requestStopWithoutNotification("child")
                            : tracker.requestStop("child"));
            verify(context).publishEvent(new GoalExecutionSignal.Stop("child"));
            assertTrue(tracker.isStopRequested("child"));
            assertEquals(
                    silent ? List.of("hook", "dispose") : List.of("phase", "hook", "dispose"),
                    order);
        } finally {
            removeRelay.run();
            tracker.complete("child");
        }
    }

    @Test
    void noLiveRunStillReceivesDurableStopIntent() {
        var tracker = new ChatStreamTracker(new ObjectMapper());
        var context = mock(ApplicationContext.class);
        ReflectionTestUtils.setField(tracker, "applicationContext", context);
        assertFalse(tracker.requestStopWithoutNotification("between-segments"));
        verify(context).publishEvent(new GoalExecutionSignal.Stop("between-segments"));
    }

    @Test
    void persistenceFailureStillCancelsWithoutEnteringThePhaseRelay() {
        var tracker = new ChatStreamTracker(new ObjectMapper());
        var context = mock(ApplicationContext.class);
        ReflectionTestUtils.setField(tracker, "applicationContext", context);
        var failure = new IllegalStateException("durable Stop failed");
        doThrow(failure).when(context).publishEvent(new GoalExecutionSignal.Stop("child"));
        tracker.register("child");
        tracker.incrementFlux("child");
        var hook = mock(Runnable.class);
        var disposable = mock(Disposable.class);
        tracker.registerCancellationHook("child", hook);
        tracker.setDisposable("child", disposable);
        List<String> events = new ArrayList<>();
        Runnable removeRelay = tracker.addEventRelay("child", (event, json) -> events.add(event));
        try {
            assertSame(
                    failure,
                    assertThrows(
                            IllegalStateException.class,
                            () -> tracker.requestStopWithoutNotification("child")));
            assertTrue(tracker.isStopRequested("child"));
            verify(hook).run();
            verify(disposable).dispose();
            assertTrue(
                    events.isEmpty(), "failure fallback must not restore synchronous notification");
        } finally {
            removeRelay.run();
            tracker.complete("child");
        }
    }
}

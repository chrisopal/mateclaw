package vip.mate.config;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class SchedulingConfigTest {
    @Test
    void shutdownCancelsFutureTasksAndLetsRunningTaskFinish() throws Exception {
        var scheduler = (ThreadPoolTaskScheduler) new SchedulingConfig().taskScheduler();
        // Observe termination ourselves instead of blocking the test in shutdown.
        scheduler.setAwaitTerminationSeconds(0);
        scheduler.initialize();
        var started = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        var interrupted = new AtomicBoolean();
        var futureRan = new AtomicBoolean();
        try {
            scheduler.execute(() -> {
                started.countDown();
                try { finish.await(); }
                catch (InterruptedException ex) { interrupted.set(true); Thread.currentThread().interrupt(); }
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            var future = scheduler.schedule(() -> futureRan.set(true), Instant.now().plusSeconds(3600));
            scheduler.shutdown();
            assertFalse(scheduler.getScheduledThreadPoolExecutor().isTerminated());
            finish.countDown();
            assertTrue(scheduler.getScheduledThreadPoolExecutor().awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(future.isCancelled());
            assertFalse(futureRan.get());
            assertFalse(interrupted.get());
        } finally {
            finish.countDown();
            scheduler.getScheduledThreadPoolExecutor().shutdownNow();
        }
    }
}

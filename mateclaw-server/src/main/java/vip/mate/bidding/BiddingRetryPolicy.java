package vip.mate.bidding;

import java.util.OptionalLong;
import org.springframework.stereotype.Component;

/** Bounds automatic retries for one manually requested execution cycle. */
@Component
public final class BiddingRetryPolicy {
    public OptionalLong nextDelayMs(BiddingTypes.Failure failure, int cycleAttempt, long jitterMs) {
        if (failure == null || !"TRANSIENT".equals(failure.category()) || cycleAttempt < 1 || cycleAttempt >= 3 || jitterMs < 0)
            return OptionalLong.empty();
        long base = cycleAttempt == 1 ? 10_000L : 30_000L;
        long backoff;
        try { backoff = Math.addExact(base, jitterMs); }
        catch (ArithmeticException overflow) { backoff = Long.MAX_VALUE; }
        return OptionalLong.of(Math.max(backoff, failure.retryAfterMs() == null ? 0 : Math.max(0, failure.retryAfterMs())));
    }
}

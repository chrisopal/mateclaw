package vip.mate.bidding;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class BiddingRetryPolicyTest {
    @Test void retryBudgetIncludesRetryAfterAndStopsAfterThirdAttempt() {
        var failure = new BiddingTypes.Failure("RATE_LIMIT", "TRANSIENT", 45_000L, false, false, false);
        var policy = new BiddingRetryPolicy();
        assertEquals(45_000L, policy.nextDelayMs(failure, 1, 0).orElseThrow());
        assertEquals(45_000L, policy.nextDelayMs(failure, 2, 0).orElseThrow());
        assertTrue(policy.nextDelayMs(failure, 3, 0).isEmpty());
        var invalid = new BiddingTypes.Failure("INVALID_OUTPUT", "VALIDATION", null, false, false, false);
        assertTrue(policy.nextDelayMs(invalid, 1, 0).isEmpty());
    }

    @Test void transientBackoffHasBoundedJitterAndManualCycleBoundary() {
        var failure = new BiddingTypes.Failure("SERVER_ERROR", "TRANSIENT", null, false, false, false);
        var policy = new BiddingRetryPolicy();
        assertEquals(10_250L, policy.nextDelayMs(failure, 1, 250).orElseThrow());
        assertEquals(30_000L, policy.nextDelayMs(failure, 2, 0).orElseThrow());
        assertTrue(policy.nextDelayMs(failure, 0, 0).isEmpty());
        assertTrue(policy.nextDelayMs(failure, 1, -1).isEmpty());
    }
}

package za.co.neroland.nerolink.ratelimit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Token bucket: a burst up to the per-minute budget, then 429 with a retry hint; buckets are per key. */
class RateLimiterTest {

    @Test
    void budgetIsPerKey() {
        RateLimiter limiter = new RateLimiter();
        int allowed = 0;
        for (int i = 0; i < 1000; i++) {
            if (limiter.check("device-a").allowed()) {
                allowed++;
            }
        }
        assertTrue(allowed >= 1 && allowed < 1000, "bucket must run dry: " + allowed);
        var denied = limiter.check("device-a");
        assertFalse(denied.allowed());
        assertTrue(denied.retryAfterMs() > 0);
        assertTrue(limiter.check("device-b").allowed());
        limiter.forget("device-a");
        assertTrue(limiter.check("device-a").allowed());
    }
}

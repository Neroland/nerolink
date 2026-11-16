package za.co.neroland.nerolink.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** The bulk bucket: configured snapshot routes are charged to it instead of the shared bucket. */
class RequestBudgetTest {

    private static final List<String> ROUTES = List.of("neromaps/tiles");
    private static final List<String> TILES = List.of("neromaps", "tiles");
    private static final List<String> ENERGY = List.of("core", "energy");

    private static RateLimiter.Decision charge(RateLimiter l, String method, List<String> seg) {
        return RequestBudget.charge(l, method, seg, "device-a", ROUTES, 60, 30);
    }

    @Test
    void onlyConfiguredGetSnapshotsAreBulk() {
        assertTrue(RequestBudget.isBulk("GET", TILES, ROUTES));
        assertFalse(RequestBudget.isBulk("GET", ENERGY, ROUTES));
        assertFalse(RequestBudget.isBulk("POST", TILES, ROUTES));
        assertFalse(RequestBudget.isBulk("GET", List.of("neromaps", "waypoints"), ROUTES));
        assertFalse(RequestBudget.isBulk("GET", List.of("actions", "neromaps", "tiles"), ROUTES));
        assertFalse(RequestBudget.isBulk("GET", List.of("wiki", "neromaps"), List.of("wiki/neromaps")));
        assertFalse(RequestBudget.isBulk("GET", TILES, List.of()));
    }

    @Test
    void tileTrafficNeverReducesTheSharedBucket() {
        RateLimiter limiter = new RateLimiter();
        int tiles = 0;
        for (int i = 0; i < 500; i++) {
            if (charge(limiter, "GET", TILES).allowed()) {
                tiles++;
            }
        }
        // The bulk bucket ran dry at its own budget (30, plus at most a token of refill)...
        assertTrue(tiles >= 30 && tiles <= 31, "bulk allowed " + tiles);
        // ...and the shared bucket still holds its full 60.
        int shared = 0;
        for (int i = 0; i < 60; i++) {
            if (charge(limiter, "GET", ENERGY).allowed()) {
                shared++;
            }
        }
        assertEquals(60, shared);
    }

    @Test
    void sharedExhaustionLeavesTheBulkBucketAlone() {
        RateLimiter limiter = new RateLimiter();
        while (charge(limiter, "GET", ENERGY).allowed()) {
            // drain the shared bucket
        }
        assertTrue(charge(limiter, "GET", TILES).allowed());
    }

    @Test
    void bulkExhaustionIsA429WithARetryHint() {
        RateLimiter limiter = new RateLimiter();
        RateLimiter.Decision last = null;
        for (int i = 0; i < 100; i++) {
            last = charge(limiter, "GET", TILES);
        }
        assertFalse(last.allowed());
        assertTrue(last.retryAfterMs() > 0);
        // 30 per minute refills one token every 2 s.
        assertTrue(last.retryAfterMs() <= 2000, "retry " + last.retryAfterMs());
    }

    @Test
    void bulkBucketsArePerDeviceAndForgottenWithTheDevice() {
        RateLimiter limiter = new RateLimiter();
        for (int i = 0; i < 100; i++) {
            charge(limiter, "GET", TILES);
        }
        assertFalse(charge(limiter, "GET", TILES).allowed());
        assertTrue(RequestBudget.charge(limiter, "GET", TILES, "device-b", ROUTES, 60, 30).allowed());
        limiter.forget("device-a");
        assertTrue(charge(limiter, "GET", TILES).allowed());
    }
}

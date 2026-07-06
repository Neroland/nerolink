package za.co.neroland.nerolink.ratelimit;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import za.co.neroland.nerolink.config.NeroLinkConfig;

/**
 * Per-token token-bucket rate limiter. Each token gets a bucket that refills continuously to
 * {@code rateLimitPerMinute} (config) capacity; a request costs one token. On breach the API
 * answers {@code 429 RATE_LIMITED} with a {@code retryAfterMs} hint. A global concurrent-client
 * cap ({@code maxClients}) is enforced separately at the pairing/connect seam.
 *
 * <p>Buckets are keyed by device id (stable per token) and pruned lazily. Thread-safe: called
 * from Netty I/O threads before any game work is scheduled.
 */
public final class RateLimiter {

    private static final class Bucket {
        double tokens;
        long lastRefillNanos;

        Bucket(double initial) {
            this.tokens = initial;
            this.lastRefillNanos = System.nanoTime();
        }
    }

    /** Outcome of a rate-limit check. */
    public record Decision(boolean allowed, long retryAfterMs) {
        static final Decision OK = new Decision(true, 0L);
    }

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    /**
     * Charge one request against a token's bucket.
     *
     * @param key stable per-token key (device id)
     * @return an {@link Decision}; if not allowed, {@code retryAfterMs} is how long until a token frees.
     */
    public Decision check(String key) {
        int perMinute = NeroLinkConfig.RATE_LIMIT_PER_MINUTE.get();
        double capacity = perMinute;
        double refillPerNano = perMinute / 60_000_000_000.0; // tokens per nanosecond

        Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket(capacity));
        synchronized (bucket) {
            long now = System.nanoTime();
            double refill = (now - bucket.lastRefillNanos) * refillPerNano;
            bucket.tokens = Math.min(capacity, bucket.tokens + refill);
            bucket.lastRefillNanos = now;

            if (bucket.tokens >= 1.0) {
                bucket.tokens -= 1.0;
                return Decision.OK;
            }
            double needed = 1.0 - bucket.tokens;
            long retryMs = (long) Math.ceil(needed / (perMinute / 60_000.0));
            return new Decision(false, Math.max(1L, retryMs));
        }
    }

    /** Drop a token's bucket (on revoke). */
    public void forget(String key) {
        buckets.remove(key);
    }

    /** Clear all buckets (on bridge stop). */
    public void clear() {
        buckets.clear();
    }
}

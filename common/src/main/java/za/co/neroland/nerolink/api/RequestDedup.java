package za.co.neroland.nerolink.api;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonObject;

/**
 * Idempotency cache for actions. Clients send a {@code requestId} (UUID) with every action;
 * the bridge caches the result for 10 minutes so a retried request returns the original
 * outcome instead of re-executing. Keyed by {@code (player, requestId)} so one client's id
 * can never replay another player's action.
 */
public final class RequestDedup {

    private static final long TTL_MILLIS = 10 * 60 * 1000L;

    private record Cached(JsonObject envelope, long expiresAt) {
    }

    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    /** The previously cached response envelope for this player+requestId, if still fresh. */
    public Optional<JsonObject> lookup(java.util.UUID player, String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return Optional.empty();
        }
        purgeExpired();
        Cached cached = cache.get(key(player, requestId));
        if (cached == null || System.currentTimeMillis() > cached.expiresAt()) {
            return Optional.empty();
        }
        return Optional.of(cached.envelope());
    }

    /** Remember a response envelope for this player+requestId. No-op if requestId is absent. */
    public void remember(java.util.UUID player, String requestId, JsonObject envelope) {
        if (requestId == null || requestId.isBlank()) {
            return;
        }
        cache.put(key(player, requestId), new Cached(envelope, System.currentTimeMillis() + TTL_MILLIS));
    }

    private static String key(java.util.UUID player, String requestId) {
        return player + ":" + requestId;
    }

    private void purgeExpired() {
        long now = System.currentTimeMillis();
        cache.values().removeIf(c -> now > c.expiresAt());
    }
}

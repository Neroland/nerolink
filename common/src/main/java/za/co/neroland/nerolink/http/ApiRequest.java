package za.co.neroland.nerolink.http;

import java.util.List;
import java.util.Map;

import com.google.gson.JsonObject;

/**
 * A parsed HTTP request handed to the {@link za.co.neroland.nerolink.api.ApiDispatcher}. The
 * Netty layer has already decoded method, path segments, query params, the (optional) JSON
 * body and the Bearer token — nothing here touches game state, so it is safe to build on an
 * I/O thread.
 *
 * @param transport how the request arrived — decides which pairing proof is acceptable
 * @param source    rate-limit key for unauthenticated routes: the client IP for direct
 *                  connections, {@code "relay"} for tunnelled ones (the relay does not forward IPs)
 */
public record ApiRequest(String method,
                         List<String> segments,
                         Map<String, String> query,
                         JsonObject body,
                         String bearerToken,
                         Transport transport,
                         String source) {

    /** How a request reached the bridge. */
    public enum Transport {
        /** The local listener over the bridge's pinned TLS certificate. */
        DIRECT_TLS,
        /** The local listener over plain HTTP (loopback only, behind the admin's own TLS proxy). */
        DIRECT_PLAIN,
        /** Through the relay tunnel (TLS to the relay's public certificate). */
        RELAY
    }

    /** Convenience for tests and tools: a relay-transport request. */
    public ApiRequest(String method, List<String> segments, Map<String, String> query,
                      JsonObject body, String bearerToken) {
        this(method, segments, query, body, bearerToken, Transport.RELAY, "relay");
    }

    /** Path segment at index, or null if out of range. */
    public String segment(int index) {
        return index >= 0 && index < segments.size() ? segments.get(index) : null;
    }

    public int segmentCount() {
        return segments.size();
    }

    public String queryOrNull(String key) {
        return query.get(key);
    }
}

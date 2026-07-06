package za.co.neroland.nerolink.http;

import java.util.List;
import java.util.Map;

import com.google.gson.JsonObject;

/**
 * A parsed HTTP request handed to the {@link za.co.neroland.nerolink.api.ApiDispatcher}. The
 * Netty layer has already decoded method, path segments, query params, the (optional) JSON
 * body and the Bearer token — nothing here touches game state, so it is safe to build on an
 * I/O thread.
 */
public record ApiRequest(String method,
                         List<String> segments,
                         Map<String, String> query,
                         JsonObject body,
                         String bearerToken) {

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

package za.co.neroland.nerolink.http;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * The JSON envelope every REST response uses, per the API spec:
 * {@code {"ok":true,"data":...}} or {@code {"ok":false,"error":{"code","message","retryAfterMs?"}}}.
 * Also the shared {@link Gson} instance. Envelope building is pure (no game state), so it is
 * safe to call from Netty I/O threads.
 */
public final class Json {

    public static final Gson GSON = new Gson();

    private Json() {
    }

    /** {@code {"ok":true,"data":<data>}}. */
    public static JsonObject ok(JsonElement data) {
        JsonObject env = new JsonObject();
        env.addProperty("ok", true);
        env.add("data", data == null ? new JsonObject() : data);
        return env;
    }

    /** {@code {"ok":false,"error":{"code","message"}}}. */
    public static JsonObject error(String code, String message) {
        return error(code, message, -1L);
    }

    /** {@code {"ok":false,"error":{"code","message","retryAfterMs"?}}}. */
    public static JsonObject error(String code, String message, long retryAfterMs) {
        JsonObject err = new JsonObject();
        err.addProperty("code", code);
        err.addProperty("message", message == null ? "" : message);
        if (retryAfterMs >= 0) {
            err.addProperty("retryAfterMs", retryAfterMs);
        }
        JsonObject env = new JsonObject();
        env.addProperty("ok", false);
        env.add("error", err);
        return env;
    }

    public static String toString(JsonElement element) {
        return GSON.toJson(element);
    }
}

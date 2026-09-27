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

    /** Deepest nesting accepted from a client; real payloads are 2-3 levels deep. */
    static final int MAX_DEPTH = 32;

    /**
     * Parse untrusted client JSON into an object, or null if it is not a JSON object, is nested
     * deeper than {@link #MAX_DEPTH} (checked before parsing, so a hostile body cannot overflow
     * the parser's stack), or is otherwise malformed. Never throws.
     */
    public static JsonObject parseObject(String raw) {
        if (raw == null || raw.isEmpty() || exceedsDepth(raw, MAX_DEPTH)) {
            return null;
        }
        try {
            JsonElement el = com.google.gson.JsonParser.parseString(raw);
            return el != null && el.isJsonObject() ? el.getAsJsonObject() : null;
        } catch (RuntimeException | StackOverflowError e) {
            return null;
        }
    }

    /** Whether brackets/braces (outside strings) nest deeper than {@code max}. */
    static boolean exceedsDepth(String raw, int max) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            switch (c) {
                case '"' -> inString = true;
                case '{', '[' -> {
                    if (++depth > max) {
                        return true;
                    }
                }
                case '}', ']' -> depth--;
                default -> { }
            }
        }
        return false;
    }

    public static String toString(JsonElement element) {
        return GSON.toJson(element);
    }
}

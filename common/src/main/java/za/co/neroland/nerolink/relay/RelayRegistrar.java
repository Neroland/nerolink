package za.co.neroland.nerolink.relay;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import za.co.neroland.nerolink.http.Json;

/**
 * One-shot HTTP client for {@code POST <origin>/register} against a NeroLink relay, used by
 * {@code /nerolink setup}. Runs entirely off the server thread on the JDK's bundled
 * {@link HttpClient} async executor and returns a {@link Result} the command marshals back onto
 * the server thread for feedback.
 *
 * <p>A real {@code User-Agent} is sent because Cloudflare's bot protection 403s the default Java
 * UA. The request/response bodies are the relay's {@code {ok,data|error}} envelope, parsed with
 * Gson.
 *
 * <p><b>Privacy.</b> Nothing here logs. The returned {@link Result} carries the {@code serverKey}
 * only so the command can persist it and dial the tunnel — the command never prints it.
 */
public final class RelayRegistrar {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

    /** Outcome kinds, mapped to friendly op-facing copy by the command. */
    public enum Failure {
        UNREACHABLE, REGISTRATION_CLOSED, BAD_RESPONSE
    }

    /**
     * Registration outcome. Exactly one of {@code success} data or a {@code failure} kind is set.
     * On success the four credential fields are populated; {@code serverKey} is a secret.
     */
    public record Result(boolean ok, Failure failure, String detail,
                         String serverId, String serverKey, String tunnelUrl, String baseUrl) {

        static Result success(String serverId, String serverKey, String tunnelUrl, String baseUrl) {
            return new Result(true, null, null, serverId, serverKey, tunnelUrl, baseUrl);
        }

        static Result fail(Failure failure, String detail) {
            return new Result(false, failure, detail, null, null, null, null);
        }
    }

    private RelayRegistrar() {
    }

    /**
     * Fire the registration asynchronously. Never throws; failures are folded into a
     * {@link Result}. The returned future always completes on an HTTP-client thread.
     *
     * @param origin    relay base origin, e.g. {@code https://nerorelay.neroserver.xyz}
     * @param serverName display name sent as {@code {"serverName":...}} (the world/level name)
     * @param userAgent  a real UA, e.g. {@code nerolink-bridge/<version>}
     */
    public static java.util.concurrent.CompletableFuture<Result> register(
            String origin, String serverName, String userAgent) {
        final URI target;
        try {
            target = URI.create(registerUrl(origin));
        } catch (RuntimeException e) {
            return java.util.concurrent.CompletableFuture.completedFuture(
                    Result.fail(Failure.UNREACHABLE, "invalid relay origin"));
        }

        JsonObject body = new JsonObject();
        body.addProperty("serverName", serverName == null ? "Minecraft Server" : serverName);

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest request = HttpRequest.newBuilder(target)
                .timeout(REQUEST_TIMEOUT)
                .header("User-Agent", userAgent)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.toString(body)))
                .build();

        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .handle((response, error) -> {
                    if (error != null) {
                        return Result.fail(Failure.UNREACHABLE, error.getClass().getSimpleName());
                    }
                    return parse(response.body());
                });
    }

    /** Append {@code /register}, tolerating a trailing slash on the origin. */
    static String registerUrl(String origin) {
        String base = origin == null ? "" : origin.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/register";
    }

    /**
     * Parse the relay envelope. Works for both the 200 success body and the 403 ACTION_DISABLED
     * body (both are valid {@code {ok,...}} envelopes), so the HTTP status is not consulted.
     */
    private static Result parse(String raw) {
        JsonObject env;
        try {
            env = JsonParser.parseString(raw).getAsJsonObject();
        } catch (Exception e) {
            return Result.fail(Failure.BAD_RESPONSE, "non-JSON response");
        }
        if (env == null || !env.has("ok")) {
            return Result.fail(Failure.BAD_RESPONSE, "missing envelope");
        }
        if (env.get("ok").getAsBoolean()) {
            JsonObject data = env.has("data") && env.get("data").isJsonObject()
                    ? env.getAsJsonObject("data") : null;
            if (data == null) {
                return Result.fail(Failure.BAD_RESPONSE, "missing data");
            }
            String serverId = str(data, "serverId");
            String serverKey = str(data, "serverKey");
            String tunnelUrl = str(data, "tunnelUrl");
            String baseUrl = str(data, "baseUrl");
            if (serverId.isBlank() || serverKey.isBlank() || tunnelUrl.isBlank()) {
                return Result.fail(Failure.BAD_RESPONSE, "incomplete registration");
            }
            return Result.success(serverId, serverKey, tunnelUrl, baseUrl);
        }
        // ok:false — read the error code.
        String code = "";
        if (env.has("error") && env.get("error").isJsonObject()) {
            code = str(env.getAsJsonObject("error"), "code");
        }
        if ("ACTION_DISABLED".equalsIgnoreCase(code)) {
            return Result.fail(Failure.REGISTRATION_CLOSED, code);
        }
        return Result.fail(Failure.BAD_RESPONSE, code.isBlank() ? "error envelope" : code);
    }

    private static String str(JsonObject obj, String key) {
        return obj != null && obj.has(key) && !obj.get(key).isJsonNull()
                ? obj.get(key).getAsString() : "";
    }
}

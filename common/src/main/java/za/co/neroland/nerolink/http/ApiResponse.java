package za.co.neroland.nerolink.http;

import com.google.gson.JsonObject;

/**
 * A ready-to-serialize REST response: HTTP status + the JSON envelope body. Built off the
 * server thread once the game work (if any) has completed, then written back on the Netty
 * I/O thread.
 */
public record ApiResponse(int status, JsonObject body) {

    public static ApiResponse ok(com.google.gson.JsonElement data) {
        return new ApiResponse(200, Json.ok(data));
    }

    public static ApiResponse error(int status, String code, String message) {
        return new ApiResponse(status, Json.error(code, message));
    }

    public static ApiResponse error(int status, String code, String message, long retryAfterMs) {
        return new ApiResponse(status, Json.error(code, message, retryAfterMs));
    }

    public static ApiResponse unauthorized(String message) {
        return error(401, ApiErrors.UNAUTHORIZED, message);
    }

    public static ApiResponse notFound(String message) {
        return error(404, ApiErrors.NOT_FOUND, message);
    }

    public static ApiResponse validation(String message) {
        return error(400, ApiErrors.VALIDATION, message);
    }
}

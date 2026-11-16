package za.co.neroland.nerolink.ratelimit;

import java.util.List;

/**
 * Picks the rate-limit bucket an authenticated request is charged to.
 *
 * <p>Every request is charged to the device's shared bucket ({@code rateLimitPerMinute}), except a
 * {@code GET /{module}/{section}} snapshot whose {@code module/section} is listed in
 * {@code bulkSnapshotRoutes}: that one is charged to a second, per-device <b>bulk</b> bucket
 * ({@code bulkRateLimitPerMinute}) <i>instead</i>. Bulk traffic (map tiles) therefore cannot drain
 * the budget the other modules share, and the other modules cannot starve it. Pure: no game or
 * config access, so it is unit-tested directly.
 */
public final class RequestBudget {

    private static final String BULK_SUFFIX = "|bulk";

    private RequestBudget() {
    }

    /** The bulk bucket key for a device id. A device id never contains {@code '|'}. */
    public static String bulkKey(String deviceId) {
        return deviceId + BULK_SUFFIX;
    }

    /**
     * Whether a request is a bulk snapshot.
     *
     * @param method     HTTP method
     * @param segments   path segments after {@code api/v1}
     * @param bulkRoutes configured {@code module/section} ids (lower case)
     */
    public static boolean isBulk(String method, List<String> segments, List<String> bulkRoutes) {
        if (!"GET".equals(method) || segments.size() != 2 || bulkRoutes.isEmpty()) {
            return false;
        }
        if ("wiki".equals(segments.get(0))) {
            return false;
        }
        return bulkRoutes.contains(segments.get(0) + "/" + segments.get(1));
    }

    /**
     * Charge one request to the right bucket.
     *
     * @param sharedPerMinute the shared per-device budget
     * @param bulkPerMinute   the bulk per-device budget
     */
    public static RateLimiter.Decision charge(RateLimiter limiter, String method, List<String> segments,
            String deviceId, List<String> bulkRoutes, int sharedPerMinute, int bulkPerMinute) {
        if (isBulk(method, segments, bulkRoutes)) {
            return limiter.check(bulkKey(deviceId), bulkPerMinute);
        }
        return limiter.check(deviceId, sharedPerMinute);
    }
}

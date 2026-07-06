package za.co.neroland.nerolink.http;

/** The shared error codes from the API spec's "Errors (shared codes)" section. */
public final class ApiErrors {

    public static final String UNAUTHORIZED = "UNAUTHORIZED";
    public static final String TOKEN_REVOKED = "TOKEN_REVOKED";
    public static final String RATE_LIMITED = "RATE_LIMITED";
    public static final String MODULE_ABSENT = "MODULE_ABSENT";
    public static final String ACTION_DISABLED = "ACTION_DISABLED";
    public static final String NOT_OWNER = "NOT_OWNER";
    public static final String GATE_LOCKED = "GATE_LOCKED";
    public static final String PLAYER_OFFLINE_REQUIRED = "PLAYER_OFFLINE_REQUIRED";
    public static final String VALIDATION = "VALIDATION";
    public static final String INTERNAL = "INTERNAL";
    public static final String NOT_FOUND = "NOT_FOUND";

    private ApiErrors() {
    }
}

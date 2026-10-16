package za.co.neroland.nerolink.relay;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import za.co.neroland.nerolandcore.link.LinkEvent;

/**
 * Builds the text of a relay {@code notify} push from a Core {@link LinkEvent}. Pure: no Netty,
 * no Minecraft, no I/O, so every rule is unit-testable.
 *
 * <p>A player-scoped event may carry a reserved, optional {@value #FIELD} object in its payload:
 * {@code {"title": ≤120, "body": ≤240, "deepLink": ≤256}}. Each field is validated on its own;
 * a missing or malformed field falls back to the generic 1.0.0 value for that field only. Nothing
 * else in the payload is read, and there is no category override (the category is the player's
 * opt-in key and is always the module id). The deep link must match {@link #DEEP_LINK}; the literal
 * {@value #SERVER_PLACEHOLDER} is replaced by this server's relay id.
 *
 * <p><b>{@code _notify} text is shown on lock screens and passes through third-party push
 * services; producers must put nothing personal in it — the bridge cannot know what a string
 * means and does not try.</b> The bridge leaves {@code _notify} in the payload; WS clients ignore
 * unknown members.
 */
public final class NotifyCopy {

    /** The reserved payload member a producer may set. */
    public static final String FIELD = "_notify";
    public static final int TITLE_MAX = 120;
    public static final int BODY_MAX = 240;
    public static final int LINK_MAX = 256;
    /** Literal a producer writes where the bridge's relay server id belongs. */
    public static final String SERVER_PLACEHOLDER = "{server}";
    static final Pattern DEEP_LINK = Pattern.compile(
            "^nerolink://server/[A-Za-z0-9_-]{1,64}/[a-z0-9_-]{1,32}(/[A-Za-z0-9_-]{1,64}){0,2}$");
    /** How long a rich push for a player+module suppresses a generic one. */
    static final long RICH_SHADOW_MS = 2_000L;

    private static final String ALERTS_TOPIC = "alerts";
    private static final String RAISED = "raised";

    private NotifyCopy() {
    }

    /**
     * The resolved push text.
     *
     * @param title    push title, never null
     * @param body     push body, never null
     * @param deepLink validated deep link, or {@code null} for none
     * @param rich     whether the title or body came from the producer's {@value #FIELD}
     */
    public record Copy(String title, String body, String deepLink, boolean rich) {
    }

    /** Fixed copy exactly as 1.0.0 produced it. */
    public static Copy fallback(LinkEvent e) {
        return new Copy("NeroLink — " + e.moduleId(), e.topic() + " update", null, false);
    }

    /**
     * Mod-supplied copy from {@code payload._notify}, each field validated independently, else
     * that field's fallback.
     *
     * @param serverId this bridge's relay server id; blank drops any link that needs it
     */
    public static Copy resolve(LinkEvent e, String serverId) {
        Copy base = fallback(e);
        JsonObject payload = e.payload();
        if (payload == null) {
            return base;
        }
        JsonElement raw = payload.get(FIELD);
        if (raw == null || !raw.isJsonObject()) {
            return base;
        }
        JsonObject n = raw.getAsJsonObject();
        String title = text(n.get("title"), TITLE_MAX);
        String body = text(n.get("body"), BODY_MAX);
        String link = deepLink(n.get("deepLink"), serverId);
        boolean rich = title != null || body != null;
        return new Copy(title != null ? title : base.title(), body != null ? body : base.body(), link, rich);
    }

    /** Topic-level verb filter: for topic "alerts", push only when payload.event is absent or "raised". */
    public static boolean shouldPush(LinkEvent e) {
        if (!ALERTS_TOPIC.equals(e.topic())) {
            return true;
        }
        JsonObject payload = e.payload();
        JsonElement verb = payload == null ? null : payload.get("event");
        if (verb == null || verb.isJsonNull()) {
            return true;
        }
        return isString(verb) && RAISED.equals(verb.getAsString());
    }

    /** trim → strip chars below 0x20 → truncate; null when missing, non-string or blank. */
    static String text(JsonElement el, int max) {
        if (!isString(el)) {
            return null;
        }
        String s = el.getAsString().trim();
        StringBuilder sb = new StringBuilder(Math.min(s.length(), max + 16));
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x20) {
                sb.append(c);
            }
        }
        String out = truncate(sb.toString(), max);
        return out.isBlank() ? null : out;
    }

    /** Substitutes {@code {server}}, then enforces the length cap and the regex; null otherwise. */
    static String deepLink(JsonElement el, String serverId) {
        if (!isString(el)) {
            return null;
        }
        String link = el.getAsString();
        if (link.contains(SERVER_PLACEHOLDER)) {
            if (serverId == null || serverId.isBlank()) {
                return null;
            }
            link = link.replace(SERVER_PLACEHOLDER, serverId);
        }
        if (link.length() > LINK_MAX || !DEEP_LINK.matcher(link).matches()) {
            return null;
        }
        return link;
    }

    private static boolean isString(JsonElement el) {
        return el instanceof JsonPrimitive p && p.isString();
    }

    /** Cuts to {@code max} UTF-16 units (the relay's cap unit) without splitting a surrogate pair. */
    private static String truncate(String s, int max) {
        if (s.length() <= max) {
            return s;
        }
        int end = max;
        if (Character.isHighSurrogate(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(0, end);
    }

    /**
     * Remembers when a rich push went to a player for a module, so the generic push for the same
     * event (e.g. Core's {@code alerts raised} event a millisecond later) is skipped. Only
     * producers of {@value #FIELD} ever create entries, so other modules see no change.
     */
    static final class RichPushShadow {
        static final long PRUNE_AFTER_MS = 60_000L;
        static final int MAX_ENTRIES = 4_096;

        private final Map<String, Long> lastRich = new ConcurrentHashMap<>();

        void remember(UUID player, String category, long now) {
            lastRich.entrySet().removeIf(en -> now - en.getValue() > PRUNE_AFTER_MS);
            if (lastRich.size() >= MAX_ENTRIES) {
                lastRich.clear();
            }
            lastRich.put(key(player, category), now);
        }

        boolean shadowed(UUID player, String category, long now) {
            Long at = lastRich.get(key(player, category));
            return at != null && now >= at && now - at < RICH_SHADOW_MS;
        }

        int size() {
            return lastRich.size();
        }

        private static String key(UUID player, String category) {
            return player + "|" + category;
        }
    }
}

package za.co.neroland.nerolink.config;

import java.util.List;
import java.util.Locale;

import za.co.neroland.nerolandcore.config.ConfigManager;
import za.co.neroland.nerolandcore.config.ConfigSchema;
import za.co.neroland.nerolandcore.config.ConfigValue;

/**
 * NeroLink bridge configuration, registered with Core's config system
 * ({@code nerolink.properties}). Every admin lever from the design doc lives here:
 * the HTTP/WS port, rate limits, token lifetime, read-only + disabled-action gates,
 * snapshot cadences and the privacy-notice text.
 *
 * <p>Core's config system is scalar-only (bool / int / long / double / String), so the
 * list-valued {@code actionsDisabled} lever is stored as one comma-delimited string and
 * split on read (see {@link #actionsDisabled()}). Same for the (multi-line) privacy notice.
 *
 * <p>All values read lazily through the {@link ConfigValue} handles, so a config reload
 * ({@code /neroland config reload}) is picked up without a bridge restart for anything
 * consulted per-request (rate limits, read-only, disabled actions). Port changes need a
 * server restart because the socket is bound at server-start.
 */
public final class NeroLinkConfig {

    /** {@code nerolink.properties} — matches the mod id so Core writes the right file. */
    public static final ConfigSchema SCHEMA =
            ConfigSchema.create("nerolink", "NeroLink companion-bridge configuration.");

    public static final ConfigValue<Boolean> ENABLED = SCHEMA.bool(
            "enabled", true, true,
            "Master switch. When false the bridge binds no socket and serves nothing.");

    public static final ConfigValue<Integer> PORT = SCHEMA.intRange(
            "port", 25580, 1024, 65535, true,
            "TCP port the HTTP + WebSocket bridge binds (server-start). Change needs a restart.");

    public static final ConfigValue<String> BIND_ADDRESS = SCHEMA.string(
            "bindAddress", "0.0.0.0", true,
            "Interface the bridge binds. 0.0.0.0 = all; 127.0.0.1 = local/relay-forward only.");

    public static final ConfigValue<Integer> RATE_LIMIT_PER_MINUTE = SCHEMA.intRange(
            "rateLimitPerMinute", 60, 1, 6000, true,
            "Per-token REST request budget per rolling minute. 429 + retryAfterMs on breach.");

    public static final ConfigValue<Integer> MAX_CLIENTS = SCHEMA.intRange(
            "maxClients", 64, 1, 4096, true,
            "Global cap on concurrent paired clients (REST + WS). Protects a busy server.");

    public static final ConfigValue<Integer> TOKEN_EXPIRY_DAYS = SCHEMA.intRange(
            "tokenExpiryDays", 90, 1, 3650, true,
            "Device tokens expire after this many days of inactivity (lazily on next use).");

    public static final ConfigValue<Boolean> READ_ONLY = SCHEMA.bool(
            "readOnly", false, true,
            "Read-only bridge: all POST /actions are refused with ACTION_DISABLED. Snapshots still served.");

    public static final ConfigValue<Boolean> ALLOW_OFFLINE_OVERRIDE = SCHEMA.bool(
            "allowOfflineActions", true, true,
            "When false, every action requires the player be online, ignoring each action's allowOffline flag.");

    public static final ConfigValue<String> ACTIONS_DISABLED = SCHEMA.string(
            "actionsDisabled", "", true,
            "Comma-separated module/action ids to disable globally, e.g. \"nerologistics/craft_order,core/ack_alert\".");

    public static final ConfigValue<Integer> SNAPSHOT_CADENCE_HOT_MS = SCHEMA.intRange(
            "snapshotCadenceHotMs", 5000, 500, 600000, true,
            "Cache cadence for hot sections (energy, drones). WS deltas batch to at most one per second.");

    public static final ConfigValue<Integer> SNAPSHOT_CADENCE_COLD_MS = SCHEMA.intRange(
            "snapshotCadenceColdMs", 30000, 500, 600000, true,
            "Cache cadence for cold sections (stock, storage).");

    public static final ConfigValue<String> RELAY_URL = SCHEMA.string(
            "relayUrl", "", true,
            "Optional NeroLink relay endpoint for out-of-LAN access + push. Unused in v1 (documented only).");

    public static final ConfigValue<String> PRIVACY_NOTICE_TEXT = SCHEMA.string(
            "privacyNoticeText",
            "This server's NeroLink bridge stores only: a hashed device token, your "
                    + "notification preferences, and pending pairing codes - all keyed to your "
                    + "Minecraft account and erasable on request. No email, no location, no chat. "
                    + "All data shown is scoped to you.",
            false,
            "Data-processing notice returned by GET /privacy/notice and shown at first pairing.");

    private NeroLinkConfig() {
    }

    /** Register the schema with Core. Call once from {@code NeroLinkCommon.init()}. */
    public static void register() {
        ConfigManager.register(SCHEMA);
    }

    /** Parsed, lower-cased set of globally disabled {@code module/action} ids. */
    public static List<String> actionsDisabled() {
        String raw = ACTIONS_DISABLED.get();
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(raw.split(","))
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /** Whether the given {@code module/action} id is globally disabled by config. */
    public static boolean isActionDisabled(String moduleId, String actionId) {
        String id = (moduleId + "/" + actionId).toLowerCase(Locale.ROOT);
        return actionsDisabled().contains(id);
    }
}

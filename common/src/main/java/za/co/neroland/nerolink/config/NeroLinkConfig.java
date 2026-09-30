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
            "Interface the direct-mode listener binds on a DEDICATED server. 0.0.0.0 = all interfaces "
                    + "(LAN / port-forwarded phones can connect directly); 127.0.0.1 = this machine only "
                    + "(use the relay for phones). Single-player worlds always bind 127.0.0.1 unless "
                    + "singleplayerLanAccess=true. Traffic is always TLS-encrypted (see tlsEnabled).");

    public static final ConfigValue<Boolean> DIRECT_ENABLED = SCHEMA.bool(
            "directEnabled", true, true,
            "Run the direct-mode listener (LAN / port-forward). Set false to use the relay only; "
                    + "the relay tunnel is outbound and needs no listener.");

    public static final ConfigValue<Boolean> TLS_ENABLED = SCHEMA.bool(
            "tlsEnabled", true, true,
            "Encrypt the direct-mode listener with the bridge's self-signed certificate, which the "
                    + "companion app pins at pairing (config/nerolink/bridge-tls.p12; delete it to rotate "
                    + "- every device must then re-pair). The NeroLink app's direct mode requires this. "
                    + "false is only for local tooling: plain HTTP is refused unless bindAddress is 127.0.0.1.");

    public static final ConfigValue<Boolean> SINGLEPLAYER_LAN_ACCESS = SCHEMA.bool(
            "singleplayerLanAccess", false, true,
            "Let phones on your network reach the bridge while you play single-player. Off by default, "
                    + "so a single-player world never opens a network port on its own.");

    public static final ConfigValue<Integer> RATE_LIMIT_PER_MINUTE = SCHEMA.intRange(
            "rateLimitPerMinute", 60, 1, 6000, true,
            "Per-token REST request budget per rolling minute. 429 + retryAfterMs on breach.");

    public static final ConfigValue<Integer> MAX_CLIENTS = SCHEMA.intRange(
            "maxClients", 64, 1, 4096, true,
            "Global cap on concurrent live-update (WebSocket) connections, direct + relay. "
                    + "Protects a busy server; extra connections are refused until one closes.");

    public static final ConfigValue<Integer> MAX_DEVICES_PER_PLAYER = SCHEMA.intRange(
            "maxDevicesPerPlayer", 5, 1, 64, true,
            "How many devices one player may pair at once. Pairing beyond this is refused; "
                    + "revoke one with /nerolink revoke <device-id>.");

    public static final ConfigValue<Integer> TOKEN_EXPIRY_DAYS = SCHEMA.intRange(
            "tokenExpiryDays", 90, 1, 3650, true,
            "Device tokens expire after this many days of inactivity; expired devices are deleted "
                    + "by a periodic sweep (and every token expires after 365 days regardless).");

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
            "Reserved for snapshot caching (not used yet). WS deltas already batch to at most one per second.");

    public static final ConfigValue<Integer> SNAPSHOT_CADENCE_COLD_MS = SCHEMA.intRange(
            "snapshotCadenceColdMs", 30000, 500, 600000, true,
            "Reserved for snapshot caching of cold sections (not used yet).");

    public static final ConfigValue<String> RELAY_ORIGIN = SCHEMA.string(
            "relayOrigin", "https://relay.nerolandmc.net", true,
            "Relay base origin used by /nerolink setup to register this server, e.g. "
                    + "https://relay.nerolandmc.net. /nerolink setup [origin] posts to <origin>/register "
                    + "and stores the returned credentials per-world; you do not edit relayUrl/relayKey by hand.");

    public static final ConfigValue<String> RELAY_URL = SCHEMA.string(
            "relayUrl", "", true,
            "MANUAL OVERRIDE (advanced): relay tunnel URL, e.g. "
                    + "wss://relay.nerolandmc.net/tunnel/<serverId>. Blank = use the /nerolink setup "
                    + "registration instead. When BOTH relayUrl and relayKey are set they take precedence "
                    + "over the stored setup registration; otherwise leave both blank and use /nerolink setup.");

    public static final ConfigValue<String> RELAY_KEY = SCHEMA.string(
            "relayKey", "", true,
            "MANUAL OVERRIDE (advanced): server key paired with relayUrl - keep secret, never logged. "
                    + "Blank = use the /nerolink setup registration instead. Set BOTH relayUrl and relayKey "
                    + "to override the stored setup credentials; otherwise leave blank and use /nerolink setup.");

    public static final ConfigValue<String> PRIVACY_NOTICE_TEXT = SCHEMA.string(
            "privacyNoticeText",
            "This server's NeroLink bridge stores, keyed to your Minecraft account: a hashed "
                    + "device token, the device name you enter, when you paired and last connected, and "
                    + "your notification preferences. Devices you stop using are deleted automatically. No email, "
                    + "no location, no chat. Everything shown is your own data. You can export or delete "
                    + "it from the app's Privacy settings at any time. If this server uses a NeroLink relay, "
                    + "your requests pass through it (encrypted to the relay, operated on Cloudflare) "
                    + "without their content being stored there.",
            false,
            "Data-processing notice returned by GET /privacy/notice and shown at first pairing.");

    /**
     * Anonymous crash reporting (Sentry, EU ingest). CLIENT-LOCAL opt-out — deliberately NOT
     * server-authoritative, so it is never synced and each install decides for itself. Default on;
     * set false to opt out. Payload is stack trace + mod/MC/loader/OS/Java versions only — never
     * tokens, pairing codes, relay keys, player identifiers, IPs, or world data (POPIA/GDPR).
     */
    public static final ConfigValue<Boolean> TELEMETRY_ENABLED = SCHEMA.bool(
            "telemetryEnabled", true, false,
            "Anonymous error reporting to the developers (stack trace + mod/MC/loader/OS/Java "
                    + "versions only — never tokens, pairing codes, relay keys, names, UUIDs, IPs, or "
                    + "world data; POPIA/GDPR-compliant, EU servers). Set false to opt out.");

    private NeroLinkConfig() {
    }

    /** Register the schema with Core. Call once from {@code NeroLinkCommon.init()}. */
    public static void register() {
        ConfigManager.register(SCHEMA);
    }

    /** Whether anonymous crash reporting is enabled (client-local opt-out; default on). */
    public static boolean telemetryEnabled() {
        return TELEMETRY_ENABLED.get();
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

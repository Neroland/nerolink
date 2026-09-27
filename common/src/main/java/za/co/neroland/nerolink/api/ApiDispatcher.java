package za.co.neroland.nerolink.api;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import za.co.neroland.nerolandcore.link.LinkActionHandler;
import za.co.neroland.nerolandcore.link.LinkActionResult;
import za.co.neroland.nerolandcore.link.LinkModuleInfo;
import za.co.neroland.nerolandcore.link.LinkSnapshotProvider;
import za.co.neroland.nerolandcore.link.NeroLinkRegistry;
import za.co.neroland.nerolink.NeroLinkBridge;
import za.co.neroland.nerolink.NeroLinkCommon;
import za.co.neroland.nerolink.auth.TokenStore;
import za.co.neroland.nerolink.config.NeroLinkConfig;
import za.co.neroland.nerolink.coremodule.CoreModule;
import za.co.neroland.nerolink.http.ApiErrors;
import za.co.neroland.nerolink.http.ApiRequest;
import za.co.neroland.nerolink.http.ApiResponse;
import za.co.neroland.nerolink.prefs.PrefsStore;

/**
 * Routes a decoded {@link ApiRequest} to the right handler and returns a
 * {@link CompletableFuture} of the {@link ApiResponse}. Called from Netty I/O threads.
 *
 * <p><b>Threading contract.</b> Authentication, rate limiting and pure JSON shaping run on the
 * calling I/O thread. Anything that reads or writes game state (snapshots, actions, gate/alert
 * lookups) is marshalled onto the server thread via {@link #onServerThread(java.util.function.Supplier)}
 * ({@code server.execute(...)}), and the response future completes when that work finishes.
 * No game state is ever touched off the server thread.
 *
 * <p>Every authenticated response is scoped to the token's player (POPIA/GDPR own-data-only).
 */
public final class ApiDispatcher {

    /** Nero module ids the app knows about, surfaced as {@code absent:true} when not installed. */
    private static final List<String> KNOWN_MODULES = List.of(
            "core", "nerospace", "nerotech", "nerologistics", "neroeconomy", "nerofactions",
            "neroruins", "nerocolonies", "neroevents", "nerodecor", "neroquests",
            "nerosecurity", "nerocreatures", "neroagriculture", "neropower", "nerocompanion");

    /** Max seconds a request waits for the server thread before answering 503. */
    static final long SERVER_THREAD_TIMEOUT_SECONDS = 10;
    /** Max notification-preference categories stored per player. */
    static final int MAX_PREF_KEYS = 64;

    private final NeroLinkBridge bridge;

    public ApiDispatcher(NeroLinkBridge bridge) {
        this.bridge = bridge;
    }

    /**
     * Handle a REST request. Path segments exclude the leading {@code api/v1}. Public (unauthed)
     * routes are {@code POST /pair} and {@code GET /privacy/notice}; everything else needs a
     * valid Bearer token and is charged against that token's rate-limit bucket.
     */
    public CompletableFuture<ApiResponse> handle(ApiRequest req) {
        try {
            List<String> seg = req.segments();
            String method = req.method();

            // --- public routes -------------------------------------------------------
            // Public: POST /pair (budgeted, see PairingService) and GET /privacy/notice.
            if (method.equals("POST") && matches(seg, "pair")) {
                return handlePair(req);
            }
            if (method.equals("GET") && matches(seg, "privacy", "notice")) {
                JsonObject data = new JsonObject();
                data.addProperty("notice", NeroLinkConfig.PRIVACY_NOTICE_TEXT.get());
                return completed(ApiResponse.ok(data));
            }

            // --- authenticate --------------------------------------------------------
            Optional<TokenStore.Device> auth = authenticate(req);
            if (auth.isEmpty()) {
                return completed(ApiResponse.unauthorized("missing or invalid bearer token"));
            }
            TokenStore.Device device = auth.get();
            UUID player = device.player();

            // --- rate limit ----------------------------------------------------------
            var decision = bridge.rateLimiter().check(device.deviceId());
            if (!decision.allowed()) {
                return completed(ApiResponse.error(429, ApiErrors.RATE_LIMITED,
                        "rate limit exceeded", decision.retryAfterMs()));
            }

            // --- authed routes -------------------------------------------------------
            if (method.equals("DELETE") && matches(seg, "session")) {
                // On the server thread, like every other token-store write that must not be lost
                // to a concurrent save.
                return onServerThread(() -> {
                    bridge.tokens().revoke(device.deviceId());
                    bridge.onDeviceRemoved(device);
                    JsonObject data = new JsonObject();
                    data.addProperty("revoked", true);
                    return ApiResponse.ok(data);
                });
            }
            if (method.equals("GET") && matches(seg, "session")) {
                // Who this token is. Used by the relay to bind push registrations to the real
                // player/device instead of trusting client-supplied ids.
                JsonObject data = new JsonObject();
                data.addProperty("playerUuid", player.toString());
                data.addProperty("deviceId", device.deviceId());
                data.addProperty("deviceName", device.deviceName());
                return completed(ApiResponse.ok(data));
            }
            if (method.equals("GET") && matches(seg, "discovery")) {
                return onServerThread(() -> discovery(player));
            }
            if (method.equals("GET") && seg.size() == 2 && seg.get(0).equals("privacy")) {
                return switch (seg.get(1)) {
                    case "export" -> onServerThread(() -> privacyExport(player, device));
                    default -> completed(ApiResponse.notFound("unknown privacy route"));
                };
            }
            if (method.equals("POST") && matches(seg, "privacy", "erase")) {
                return onServerThread(() -> privacyErase(player));
            }
            if (matches2(seg, "prefs", "notifications")) {
                if (method.equals("GET")) {
                    return onServerThread(() -> prefsGet(player));
                }
                if (method.equals("PUT")) {
                    return onServerThread(() -> prefsPut(player, req.body()));
                }
                return completed(ApiResponse.error(405, ApiErrors.VALIDATION, "method not allowed"));
            }
            if (method.equals("POST") && seg.size() == 3 && seg.get(0).equals("actions")) {
                return handleAction(player, seg.get(1), seg.get(2), req.body());
            }
            // Wiki (in-app per-mod wiki browsing). MUST precede the generic 2-segment snapshot
            // route below so GET /wiki/{module} isn't swallowed as a {module}/{section} snapshot.
            if (method.equals("GET") && !seg.isEmpty() && seg.get(0).equals("wiki")) {
                return switch (seg.size()) {
                    case 1 -> onServerThread(() -> WikiRoutes.aggregateIndex(player));
                    case 2 -> onServerThread(() -> WikiRoutes.moduleIndex(player, seg.get(1)));
                    case 3 -> onServerThread(() -> WikiRoutes.modulePage(player, seg.get(1), seg.get(2)));
                    default -> completed(ApiResponse.notFound("no such wiki route"));
                };
            }
            // Module snapshot: GET /{moduleId}/{section}
            if (method.equals("GET") && seg.size() == 2) {
                return handleSnapshot(player, seg.get(0), seg.get(1), req.query());
            }

            return completed(ApiResponse.notFound("no such route"));
        } catch (RuntimeException e) {
            // Log the type only: messages can echo client input.
            NeroLinkCommon.LOGGER.warn("[NeroLink] request handling error: {}", e.getClass().getName());
            return completed(ApiResponse.error(500, ApiErrors.INTERNAL, "internal error"));
        }
    }

    // --- discovery -------------------------------------------------------------------

    private ApiResponse discovery(UUID player) {
        MinecraftServer server = bridge.server();
        JsonObject data = new JsonObject();
        data.addProperty("apiRevision", 1);
        data.addProperty("bridgeVersion", NeroLinkCommon.bridgeVersion());
        data.addProperty("coreVersion", CoreModule.coreVersion());

        JsonObject serverObj = new JsonObject();
        serverObj.addProperty("id", serverId(server));
        serverObj.addProperty("name", serverName(server));
        serverObj.addProperty("online", true);
        serverObj.addProperty("players", server.getPlayerCount());
        data.add("server", serverObj);

        // Present modules (from the registry) keyed by id for absent-detection.
        Map<String, LinkModuleInfo> present = new java.util.LinkedHashMap<>();
        for (LinkModuleInfo info : NeroLinkRegistry.modules()) {
            present.put(info.moduleId(), info);
        }

        JsonArray modules = new JsonArray();
        // Emit known modules (present -> full entry, else absent:true), then any extra present ones.
        java.util.Set<String> emitted = new java.util.LinkedHashSet<>();
        for (String id : KNOWN_MODULES) {
            LinkModuleInfo info = present.get(id);
            modules.add(info != null ? moduleEntry(info) : absentEntry(id));
            emitted.add(id);
        }
        for (LinkModuleInfo info : present.values()) {
            if (!emitted.contains(info.moduleId())) {
                modules.add(moduleEntry(info));
            }
        }
        data.add("modules", modules);
        return ApiResponse.ok(data);
    }

    private static JsonObject moduleEntry(LinkModuleInfo info) {
        JsonObject m = new JsonObject();
        m.addProperty("id", info.moduleId());
        m.addProperty("version", info.modVersion());
        m.addProperty("schema", info.schemaVersion());
        JsonArray sections = new JsonArray();
        info.dataSections().forEach(sections::add);
        m.add("data", sections);
        JsonArray actions = new JsonArray();
        info.actionIds().forEach(actions::add);
        m.add("actions", actions);
        return m;
    }

    private static JsonObject absentEntry(String id) {
        JsonObject m = new JsonObject();
        m.addProperty("id", id);
        m.add("version", com.google.gson.JsonNull.INSTANCE);
        m.addProperty("schema", 0);
        m.add("data", new JsonArray());
        m.add("actions", new JsonArray());
        m.addProperty("absent", true);
        return m;
    }

    // --- snapshots -------------------------------------------------------------------

    private CompletableFuture<ApiResponse> handleSnapshot(UUID player, String moduleId, String section,
                                                          Map<String, String> query) {
        Optional<LinkSnapshotProvider> provider = NeroLinkRegistry.snapshotProvider(moduleId);
        if (provider.isEmpty()) {
            return completed(ApiResponse.error(404, ApiErrors.MODULE_ABSENT,
                    "module not present: " + moduleId));
        }
        return onServerThread(() -> {
            try {
                JsonObject snap = provider.get().snapshot(player, section, query);
                return ApiResponse.ok(snap == null ? new JsonObject() : snap);
            } catch (Exception e) {
                NeroLinkCommon.LOGGER.warn("[NeroLink] snapshot error for {}/{}", safe(moduleId), safe(section), e);
                return ApiResponse.error(500, ApiErrors.INTERNAL, "snapshot failed");
            }
        });
    }

    // --- actions ---------------------------------------------------------------------

    private CompletableFuture<ApiResponse> handleAction(UUID player, String moduleId, String actionId,
                                                        JsonObject body) {
        // Config gates first (cheap, off-thread).
        if (NeroLinkConfig.READ_ONLY.get()) {
            return completed(ApiResponse.error(403, ApiErrors.ACTION_DISABLED, "bridge is read-only"));
        }
        if (NeroLinkConfig.isActionDisabled(moduleId, actionId)) {
            return completed(ApiResponse.error(403, ApiErrors.ACTION_DISABLED,
                    "action disabled by server: " + moduleId + "/" + actionId));
        }
        Optional<LinkActionHandler> handlerOpt = NeroLinkRegistry.actionHandler(moduleId);
        if (handlerOpt.isEmpty()) {
            return completed(ApiResponse.error(404, ApiErrors.MODULE_ABSENT,
                    "module not present: " + moduleId));
        }
        LinkActionHandler handler = handlerOpt.get();
        if (!handler.actionIds().contains(actionId)) {
            return completed(ApiResponse.error(404, ApiErrors.MODULE_ABSENT,
                    "unknown action: " + moduleId + "/" + actionId));
        }

        JsonObject params = body == null ? new JsonObject() : body;
        String requestId = params.has("requestId") && params.get("requestId").isJsonPrimitive()
                ? params.get("requestId").getAsString() : null;

        // Idempotency: replay a cached response for a repeated requestId.
        Optional<JsonObject> cached = bridge.dedup().lookup(player, requestId);
        if (cached.isPresent()) {
            return completed(new ApiResponse(200, cached.get()));
        }

        // Offline gating: honour the action's allowOffline flag unless config forces online-only.
        boolean allowOffline = NeroLinkConfig.ALLOW_OFFLINE_OVERRIDE.get() && handler.allowOffline(actionId);

        return onServerThread(() -> {
            ServerPlayer online = bridge.server().getPlayerList().getPlayer(player);
            if (online == null && !allowOffline) {
                return ApiResponse.error(409, ApiErrors.PLAYER_OFFLINE_REQUIRED,
                        "this action requires you to be online");
            }
            LinkActionResult result;
            try {
                result = handler.execute(player, actionId, params);
            } catch (Exception e) {
                NeroLinkCommon.LOGGER.warn("[NeroLink] action error for {}/{}", safe(moduleId), safe(actionId), e);
                return ApiResponse.error(500, ApiErrors.INTERNAL, "action failed");
            }
            ApiResponse response = toResponse(result);
            bridge.dedup().remember(player, requestId, response.body());
            return response;
        });
    }

    private static ApiResponse toResponse(LinkActionResult result) {
        if (result == null) {
            return ApiResponse.error(500, ApiErrors.INTERNAL, "null action result");
        }
        if (result.ok()) {
            return ApiResponse.ok(result.state() == null ? new JsonObject() : result.state());
        }
        String code = switch (result.error()) {
            case NOT_OWNER -> ApiErrors.NOT_OWNER;
            case GATE_LOCKED -> ApiErrors.GATE_LOCKED;
            case VALIDATION -> ApiErrors.VALIDATION;
            case ACTION_DISABLED -> ApiErrors.ACTION_DISABLED;
            case PLAYER_OFFLINE_REQUIRED -> ApiErrors.PLAYER_OFFLINE_REQUIRED;
            case INTERNAL -> ApiErrors.INTERNAL;
            case null -> ApiErrors.INTERNAL;
        };
        int status = switch (result.error()) {
            case VALIDATION -> 400;
            case NOT_OWNER, GATE_LOCKED, ACTION_DISABLED -> 403;
            case PLAYER_OFFLINE_REQUIRED -> 409;
            case INTERNAL -> 500;
            case null -> 500;
        };
        return ApiResponse.error(status, code, result.message());
    }

    // --- privacy ---------------------------------------------------------------------

    private ApiResponse privacyExport(UUID player, TokenStore.Device thisDevice) {
        MinecraftServer server = bridge.server();
        JsonObject data = new JsonObject();
        data.addProperty("playerUuid", player.toString());

        JsonArray devices = new JsonArray();
        for (TokenStore.Device d : bridge.tokens().devicesOf(player)) {
            JsonObject dev = new JsonObject();
            dev.addProperty("deviceId", d.deviceId());
            dev.addProperty("deviceName", d.deviceName());
            dev.addProperty("createdAt", d.createdAt());
            dev.addProperty("lastSeenAt", d.lastSeenAt());
            dev.addProperty("thisDevice", d.deviceId().equals(thisDevice.deviceId()));
            // token hash intentionally omitted; the plaintext token is never stored anyway.
            devices.add(dev);
        }
        data.add("devices", devices);

        JsonObject prefs = new JsonObject();
        PrefsStore.get(server).notifications(player).forEach(prefs::addProperty);
        data.add("notificationPrefs", prefs);
        data.addProperty("note", "The bridge keeps no shadow copies of mod data; module data shown in the "
                + "app is read live from the owning mods and is not stored here.");
        return ApiResponse.ok(data);
    }

    /**
     * In-app "Delete my data": purges everything <i>NeroLink</i> holds for this player (devices,
     * prefs, pairing codes, sockets, relay push registrations). It deliberately does not fan out
     * to every Neroland mod: a bearer token on a phone should not be able to wipe a player's game
     * progress. Full erasure across all mods stays an in-game action ({@code /neroland data eraseme}).
     */
    private ApiResponse privacyErase(UUID player) {
        NeroLinkCommon.eraseBridgeData(bridge.server(), player);
        JsonObject data = new JsonObject();
        data.addProperty("erased", true);
        data.addProperty("scope", "bridge");
        data.addProperty("note", "NeroLink data erased. To erase your data from every Neroland mod on this "
                + "server, run /neroland data eraseme in-game.");
        return ApiResponse.ok(data);
    }

    // --- prefs -----------------------------------------------------------------------

    private ApiResponse prefsGet(UUID player) {
        JsonObject prefs = new JsonObject();
        PrefsStore.get(bridge.server()).notifications(player).forEach(prefs::addProperty);
        JsonObject data = new JsonObject();
        data.add("notifications", prefs);
        return ApiResponse.ok(data);
    }

    private ApiResponse prefsPut(UUID player, JsonObject body) {
        if (body == null) {
            return ApiResponse.validation("body required");
        }
        JsonObject categories = body.has("notifications") && body.get("notifications").isJsonObject()
                ? body.getAsJsonObject("notifications") : body;
        Map<String, Boolean> map = new java.util.LinkedHashMap<>();
        for (String key : categories.keySet()) {
            var el = categories.get(key);
            if (map.size() >= MAX_PREF_KEYS || key.length() > 64) {
                continue;
            }
            if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isBoolean()) {
                map.put(key, el.getAsBoolean());
            }
        }
        PrefsStore.get(bridge.server()).setNotifications(player, map);
        return prefsGet(player);
    }

    // --- pairing (public) ------------------------------------------------------------

    /**
     * {@code POST /pair}. Over the relay the body carries the plain {@code code}. Over the direct
     * TLS listener the client must instead send {@code codeProof} — an HMAC of the code over the
     * certificate fingerprint it saw (see {@link za.co.neroland.nerolink.tls.BridgeTls}) — so a
     * machine-in-the-middle with a different certificate cannot pair. Failed attempts are budgeted
     * per source and globally (see {@link za.co.neroland.nerolink.auth.PairingService}).
     */
    private CompletableFuture<ApiResponse> handlePair(ApiRequest req) {
        JsonObject body = req.body();
        if (body == null) {
            return completed(ApiResponse.validation("JSON body required"));
        }
        String deviceName = optString(body, "deviceName");
        if (body.has("deviceName") && !body.get("deviceName").isJsonNull() && deviceName == null) {
            return completed(ApiResponse.validation("deviceName must be a string"));
        }

        za.co.neroland.nerolink.auth.PairingService.Redemption redemption;
        if (req.transport() == ApiRequest.Transport.DIRECT_TLS) {
            String proof = optString(body, "codeProof");
            if (proof == null) {
                return completed(ApiResponse.validation(
                        "codeProof required on direct connections (update the NeroLink app)"));
            }
            redemption = bridge.pairing().redeemProof(proof, req.source());
        } else {
            String code = optString(body, "code");
            if (code == null) {
                return completed(ApiResponse.validation("code required"));
            }
            redemption = bridge.pairing().redeem(code, req.source());
        }
        switch (redemption.outcome()) {
            case LOCKED -> {
                return completed(ApiResponse.error(429, ApiErrors.RATE_LIMITED,
                        "too many failed pairing attempts; run /nerolink pair again in a minute", 60_000L));
            }
            case INVALID -> {
                return completed(ApiResponse.error(401, ApiErrors.UNAUTHORIZED, "invalid or expired pairing code"));
            }
            case OK -> { }
        }
        UUID player = redemption.player();
        int maxDevices = NeroLinkConfig.MAX_DEVICES_PER_PLAYER.get();
        MinecraftServer server = bridge.server();
        // Pairing is issued on the server thread (player lookup + consistent device cap).
        return onServerThread(() -> {
            if (bridge.tokens().devicesOf(player).size() >= maxDevices) {
                return ApiResponse.error(409, ApiErrors.VALIDATION,
                        "device limit reached; revoke one with /nerolink devices and /nerolink revoke");
            }
            TokenStore.Issued issued = bridge.tokens().issue(server, player, deviceName);
            ServerPlayer sp = server.getPlayerList().getPlayer(player);
            JsonObject data = new JsonObject();
            data.addProperty("token", issued.token());
            data.addProperty("deviceId", issued.device().deviceId());
            data.addProperty("playerUuid", player.toString());
            data.addProperty("playerName", sp != null ? sp.getGameProfile().name() : "");
            data.addProperty("serverId", serverId(server));
            data.addProperty("serverName", serverName(server));
            return ApiResponse.ok(data);
        });
    }

    private static String optString(JsonObject body, String key) {
        if (!body.has(key)) {
            return null;
        }
        var el = body.get(key);
        return el.isJsonPrimitive() && el.getAsJsonPrimitive().isString() ? el.getAsString() : null;
    }

    /** Strip anything but a conservative id alphabet before logging client-supplied names (no log forging). */
    private static String safe(String value) {
        if (value == null) {
            return "null";
        }
        String cleaned = value.replaceAll("[^A-Za-z0-9_./-]", "?");
        return cleaned.length() > 64 ? cleaned.substring(0, 64) + "…" : cleaned;
    }

    // --- helpers ---------------------------------------------------------------------

    private Optional<TokenStore.Device> authenticate(ApiRequest req) {
        String token = req.bearerToken();
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        long expiryMillis = NeroLinkConfig.TOKEN_EXPIRY_DAYS.get() * 24L * 60 * 60 * 1000;
        return bridge.tokens().authenticate(bridge.server(), token, expiryMillis);
    }

    /**
     * Run game-touching work on the server thread; complete the future with its result. If the
     * server does not get to it within {@link #SERVER_THREAD_TIMEOUT_SECONDS} (stalled or
     * stopping) the client gets a 503 instead of a connection that hangs forever.
     */
    private CompletableFuture<ApiResponse> onServerThread(java.util.function.Supplier<ApiResponse> work) {
        MinecraftServer server = bridge.server();
        CompletableFuture<ApiResponse> future = new CompletableFuture<>();
        if (server.isSameThread()) {
            future.complete(work.get());
            return future;
        }
        future.completeOnTimeout(ApiResponse.error(503, ApiErrors.INTERNAL, "server busy; try again"),
                SERVER_THREAD_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
        server.execute(() -> {
            try {
                future.complete(work.get());
            } catch (Throwable t) {
                NeroLinkCommon.LOGGER.warn("[NeroLink] server-thread task error", t);
                future.complete(ApiResponse.error(500, ApiErrors.INTERNAL, "internal error"));
            }
        });
        return future;
    }

    private static CompletableFuture<ApiResponse> completed(ApiResponse response) {
        return CompletableFuture.completedFuture(response);
    }

    private static boolean matches(List<String> seg, String... path) {
        if (seg.size() != path.length) {
            return false;
        }
        for (int i = 0; i < path.length; i++) {
            if (!seg.get(i).equals(path[i])) {
                return false;
            }
        }
        return true;
    }

    private static boolean matches2(List<String> seg, String a, String b) {
        return seg.size() == 2 && seg.get(0).equals(a) && seg.get(1).equals(b);
    }

    private static String serverId(MinecraftServer server) {
        // Stable-ish per-world id without leaking anything personal: hash the world name.
        return Integer.toHexString(levelName(server).hashCode());
    }

    /** A display name for the server (the world/level name; no MOTD dependency across MC versions). */
    private static String serverName(MinecraftServer server) {
        String name = levelName(server);
        return name.isBlank() ? "Minecraft Server" : name;
    }

    private static String levelName(MinecraftServer server) {
        return server.getWorldData() != null ? server.getWorldData().getLevelName() : "world";
    }
}

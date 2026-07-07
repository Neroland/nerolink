package za.co.neroland.nerolink.command;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import za.co.neroland.nerolandcore.link.LinkModuleInfo;
import za.co.neroland.nerolandcore.link.NeroLinkRegistry;
import za.co.neroland.nerolink.NeroLinkBridge;
import za.co.neroland.nerolink.NeroLinkCommon;
import za.co.neroland.nerolink.auth.TokenStore;
import za.co.neroland.nerolink.config.NeroLinkConfig;
import za.co.neroland.nerolink.relay.RelayRegistrar;
import za.co.neroland.nerolink.relay.RelaySettings;

/**
 * The {@code /nerolink} command tree, built with vanilla Brigadier so it is byte-identical on
 * every loader (each loader just calls {@link #register(CommandDispatcher)} from its command
 * event). Sub-commands:
 * <ul>
 *   <li>{@code pair} — mint a pairing code and whisper it to the running player only.</li>
 *   <li>{@code devices} — list your paired devices (names + last-seen, never tokens).</li>
 *   <li>{@code revoke <device>} — revoke one of your devices by id.</li>
 *   <li>{@code status} — op-only bridge status (client counts + module list + Server ID).</li>
 *   <li>{@code setup [origin]} / {@code setup force [origin]} — op-only relay onboarding: register
 *       with the relay, persist the credentials, and bring the tunnel up with no server restart.</li>
 * </ul>
 *
 * <p>POPIA/GDPR: the pairing code and tokens are never broadcast or logged; the code is sent
 * only to the requesting player via {@code sendSystemMessage}.
 */
public final class NeroLinkCommand {

    private NeroLinkCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("nerolink")
                .then(Commands.literal("pair").executes(NeroLinkCommand::pair))
                .then(Commands.literal("devices").executes(NeroLinkCommand::devices))
                .then(Commands.literal("revoke")
                        .then(Commands.argument("device", StringArgumentType.string())
                                .executes(NeroLinkCommand::revoke)))
                .then(Commands.literal("status")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(NeroLinkCommand::status))
                .then(Commands.literal("setup")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(ctx -> setup(ctx, null, false))
                        .then(Commands.literal("force")
                                .executes(ctx -> setup(ctx, null, true))
                                .then(Commands.argument("origin", StringArgumentType.greedyString())
                                        .executes(ctx -> setup(ctx, StringArgumentType.getString(ctx, "origin"), true))))
                        .then(Commands.argument("origin", StringArgumentType.greedyString())
                                .executes(ctx -> setup(ctx, StringArgumentType.getString(ctx, "origin"), false)))));
    }

    private static int pair(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(Component.literal("Only a player can pair a device."));
            return 0;
        }
        if (NeroLinkBridge.instance() == null) {
            ctx.getSource().sendFailure(Component.literal("The NeroLink bridge is not running on this server."));
            return 0;
        }
        String code = NeroLinkBridge.instance().pairing().issue(player.getUUID());
        MinecraftServer server = ctx.getSource().getServer();
        // Whisper the code to this player only.
        player.sendSystemMessage(Component.empty()
                .append(Component.literal("[NeroLink] ").withStyle(ChatFormatting.AQUA))
                .append(Component.literal("Pairing code: ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(code).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)));
        // If a relay registration is active, the Server ID is what players enter in the app —
        // show it prominently, before the LAN address (which only direct-mode clients need).
        Optional<String> serverId = activeServerId(server);
        serverId.ifPresent(id -> player.sendSystemMessage(Component.empty()
                .append(Component.literal("Server ID: ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(id).withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD))
                .append(Component.literal("  (enter this + the code in the app)")
                        .withStyle(ChatFormatting.DARK_GRAY))));
        player.sendSystemMessage(Component.empty()
                .append(Component.literal("Bridge address: ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(bridgeAddress()).withStyle(ChatFormatting.AQUA))
                .append(Component.literal("  (LAN/direct mode — NOT the 'open to LAN' game port)")
                        .withStyle(ChatFormatting.DARK_GRAY)));
        player.sendSystemMessage(Component.literal(
                "Enter within 5 minutes. Single-use.")
                .withStyle(ChatFormatting.DARK_GRAY));
        return Command.SINGLE_SUCCESS;
    }

    /**
     * The Server ID players type in the app, when a relay registration is active. Prefers the
     * {@link RelaySettings} written by {@code /nerolink setup}; falls back to parsing the id out of
     * a manual {@code relayUrl} override ({@code .../tunnel/<serverId>}). Empty in direct-only mode.
     */
    private static Optional<String> activeServerId(MinecraftServer server) {
        RelaySettings settings = RelaySettings.get(server);
        if (settings.isRegistered()) {
            return Optional.of(settings.serverId());
        }
        String cfgUrl = NeroLinkConfig.RELAY_URL.get();
        String cfgKey = NeroLinkConfig.RELAY_KEY.get();
        if (cfgUrl != null && !cfgUrl.isBlank() && cfgKey != null && !cfgKey.isBlank()) {
            return serverIdFromTunnelUrl(cfgUrl);
        }
        return Optional.empty();
    }

    /** Extract the last non-empty path segment of a {@code .../tunnel/<serverId>} URL. */
    private static Optional<String> serverIdFromTunnelUrl(String tunnelUrl) {
        try {
            String path = java.net.URI.create(tunnelUrl.trim()).getPath();
            if (path == null || path.isBlank()) {
                return Optional.empty();
            }
            String[] parts = path.split("/");
            for (int i = parts.length - 1; i >= 0; i--) {
                if (!parts[i].isBlank()) {
                    return Optional.of(parts[i]);
                }
            }
        } catch (RuntimeException ignored) {
            // malformed override URL; no id to show
        }
        return Optional.empty();
    }

    /**
     * Best-effort {@code host:port} for the bridge as seen from the LAN, for the pairing
     * whisper. Uses the first site-local IPv4 (falls back to the bind address); the port is
     * the bridge's configured port — deliberately NOT the ephemeral "open to LAN" game port,
     * which players otherwise tend to copy by mistake. No personal data involved.
     */
    private static String bridgeAddress() {
        int port = za.co.neroland.nerolink.config.NeroLinkConfig.PORT.get();
        String bind = za.co.neroland.nerolink.config.NeroLinkConfig.BIND_ADDRESS.get();
        String host = "0.0.0.0".equals(bind) ? firstSiteLocalIpv4() : bind;
        return host + ":" + port;
    }

    private static String firstSiteLocalIpv4() {
        try {
            var interfaces = java.net.NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                var nif = interfaces.nextElement();
                if (!nif.isUp() || nif.isLoopback() || nif.isVirtual()) {
                    continue;
                }
                var addrs = nif.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    var addr = addrs.nextElement();
                    if (addr instanceof java.net.Inet4Address && addr.isSiteLocalAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (java.net.SocketException ignored) {
            // fall through
        }
        return "<this machine's LAN IP>";
    }

    private static int devices(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(Component.literal("Only a player can list devices."));
            return 0;
        }
        MinecraftServer server = ctx.getSource().getServer();
        List<TokenStore.Device> devices = TokenStore.get(server).devicesOf(player.getUUID());
        if (devices.isEmpty()) {
            player.sendSystemMessage(Component.literal("[NeroLink] No paired devices.")
                    .withStyle(ChatFormatting.GRAY));
            return Command.SINGLE_SUCCESS;
        }
        player.sendSystemMessage(Component.literal("[NeroLink] Your paired devices:")
                .withStyle(ChatFormatting.AQUA));
        for (TokenStore.Device d : devices) {
            player.sendSystemMessage(Component.literal(
                    " - " + d.deviceName() + "  (" + d.deviceId() + ")")
                    .withStyle(ChatFormatting.GRAY));
        }
        player.sendSystemMessage(Component.literal("Revoke with /nerolink revoke <device-id>.")
                .withStyle(ChatFormatting.DARK_GRAY));
        return Command.SINGLE_SUCCESS;
    }

    private static int revoke(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(Component.literal("Only a player can revoke a device."));
            return 0;
        }
        MinecraftServer server = ctx.getSource().getServer();
        String deviceId = StringArgumentType.getString(ctx, "device");
        TokenStore tokens = TokenStore.get(server);
        UUID owner = player.getUUID();
        // Scope to the requesting player's own devices only.
        boolean owned = tokens.devicesOf(owner).stream().anyMatch(d -> d.deviceId().equals(deviceId));
        if (!owned) {
            ctx.getSource().sendFailure(Component.literal("[NeroLink] No such device of yours: " + deviceId));
            return 0;
        }
        tokens.revoke(deviceId);
        NeroLinkBridge bridge = NeroLinkBridge.instance();
        if (bridge != null) {
            bridge.rateLimiter().forget(deviceId);
        }
        player.sendSystemMessage(Component.literal("[NeroLink] Device revoked: " + deviceId)
                .withStyle(ChatFormatting.GREEN));
        return Command.SINGLE_SUCCESS;
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        NeroLinkBridge bridge = NeroLinkBridge.instance();
        if (bridge == null) {
            src.sendSuccess(() -> Component.literal("[NeroLink] Bridge is not running.")
                    .withStyle(ChatFormatting.YELLOW), false);
            return Command.SINGLE_SUCCESS;
        }
        List<LinkModuleInfo> modules = NeroLinkRegistry.modules();
        String moduleList = modules.isEmpty() ? "(none)"
                : String.join(", ", modules.stream().map(LinkModuleInfo::moduleId).toList());
        int deviceCount = countDevices(bridge);
        String relayState = relayStateText(bridge);
        Optional<String> serverId = activeServerId(bridge.server());
        String serverIdText = serverId.map(id -> "Server ID: " + id + ". ").orElse("");
        src.sendSuccess(() -> Component.literal("[NeroLink] Bridge running. ")
                .withStyle(ChatFormatting.AQUA)
                .append(Component.literal("Paired devices: " + deviceCount + ". ")
                        .withStyle(ChatFormatting.GRAY))
                .append(Component.literal("Relay: " + relayState + ". ")
                        .withStyle(ChatFormatting.GRAY))
                .append(Component.literal(serverIdText)
                        .withStyle(ChatFormatting.GRAY))
                .append(Component.literal("Modules: " + moduleList)
                        .withStyle(ChatFormatting.GRAY)), false);
        return Command.SINGLE_SUCCESS;
    }

    /**
     * {@code /nerolink setup [origin]} and {@code /nerolink setup force [origin]} — one-shot relay
     * onboarding for ops. Registers this server with the relay ({@code POST <origin>/register}) off
     * the server thread, persists the returned credentials to {@link RelaySettings}, and dials the
     * tunnel immediately (no server restart). When a registration for the same origin already
     * exists and {@code force} is false, it simply re-dials the tunnel and reports the stored id.
     *
     * <p>POPIA/GDPR: no player data is touched; the {@code serverKey} is never printed or logged.
     */
    private static int setup(CommandContext<CommandSourceStack> ctx, String originArg, boolean force) {
        CommandSourceStack src = ctx.getSource();
        MinecraftServer server = src.getServer();
        NeroLinkBridge bridge = NeroLinkBridge.instance();
        if (bridge == null) {
            src.sendFailure(Component.literal("[NeroLink] Bridge is not running; cannot set up the relay."));
            return 0;
        }
        String origin = (originArg != null && !originArg.isBlank())
                ? originArg.trim() : NeroLinkConfig.RELAY_ORIGIN.get();
        if (origin == null || origin.isBlank()) {
            src.sendFailure(Component.literal(
                    "[NeroLink] No relay origin. Pass one: /nerolink setup <https-origin>, or set relayOrigin in config."));
            return 0;
        }

        RelaySettings settings = RelaySettings.get(server);
        if (!force && settings.isRegisteredFor(origin)) {
            // Already registered here — just (re)connect and report the stored id.
            bridge.restartRelay(settings.tunnelUrl(), settings.serverKey());
            src.sendSuccess(() -> Component.literal("[NeroLink] Already registered with this relay.")
                    .withStyle(ChatFormatting.GREEN), false);
            reportRegistration(src, settings.serverId(), settings.baseUrl(), false);
            return Command.SINGLE_SUCCESS;
        }

        src.sendSuccess(() -> Component.literal("[NeroLink] Registering with " + origin + " …")
                .withStyle(ChatFormatting.GRAY), false);

        String serverName = serverName(server);
        String userAgent = "nerolink-bridge/" + NeroLinkCommon.BRIDGE_VERSION;
        RelayRegistrar.register(origin, serverName, userAgent).whenComplete((result, error) ->
                // Marshal back onto the server thread before touching SavedData or the source.
                server.execute(() -> onSetupComplete(src, server, origin, result, error)));
        return Command.SINGLE_SUCCESS;
    }

    /** Completion handler for {@code /nerolink setup}, always run on the server thread. */
    private static void onSetupComplete(CommandSourceStack src, MinecraftServer server, String origin,
                                        RelayRegistrar.Result result, Throwable error) {
        NeroLinkBridge bridge = NeroLinkBridge.instance();
        if (error != null || result == null) {
            safeFailure(src, "[NeroLink] Relay setup failed unexpectedly. Nothing was changed.");
            return;
        }
        if (!result.ok()) {
            safeFailure(src, failureMessage(result.failure(), origin));
            return;
        }
        if (bridge == null) {
            safeFailure(src, "[NeroLink] Bridge stopped before setup finished; nothing was saved.");
            return;
        }
        // Persist the registration (never log the serverKey) and dial the tunnel now.
        RelaySettings.get(server).set(origin, result.serverId(), result.serverKey(),
                result.tunnelUrl(), result.baseUrl(), System.currentTimeMillis());
        bridge.restartRelay(result.tunnelUrl(), result.serverKey());
        safeSuccess(src, Component.literal("[NeroLink] Relay registered!").withStyle(ChatFormatting.GREEN));
        reportRegistration(src, result.serverId(), result.baseUrl(), true);
    }

    /** Shared success detail: bold/gold Server ID, app URL, and the tunnel-connecting hint. */
    private static void reportRegistration(CommandSourceStack src, String serverId, String baseUrl, boolean connecting) {
        safeSuccess(src, Component.empty()
                .append(Component.literal("Server ID: ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(serverId).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD))
                .append(Component.literal("  (players enter this in the app)").withStyle(ChatFormatting.DARK_GRAY)));
        if (baseUrl != null && !baseUrl.isBlank()) {
            safeSuccess(src, Component.empty()
                    .append(Component.literal("App URL: ").withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(baseUrl).withStyle(ChatFormatting.AQUA)));
        }
        safeSuccess(src, Component.literal(
                (connecting ? "Tunnel connecting" : "Reconnecting the tunnel") + " — check /nerolink status.")
                .withStyle(ChatFormatting.DARK_GRAY));
    }

    /** Friendly op-facing copy for each registration failure kind. */
    private static String failureMessage(RelayRegistrar.Failure failure, String origin) {
        return switch (failure) {
            case UNREACHABLE -> "[NeroLink] Could not reach the relay at " + origin
                    + ". Check the origin (config relayOrigin) and this server's connection.";
            case REGISTRATION_CLOSED -> "[NeroLink] Registration is closed on this relay "
                    + "(REGISTRATION_OPEN=false). Ask the relay operator to reopen it, then retry.";
            case BAD_RESPONSE -> "[NeroLink] The relay returned an unexpected response. "
                    + "Check the origin points at a NeroLink relay, then retry.";
        };
    }

    /** Send success feedback, guarding against a source that has since gone away. */
    private static void safeSuccess(CommandSourceStack src, Component message) {
        try {
            src.sendSuccess(() -> message, false);
        } catch (RuntimeException ignored) {
            // The command source (e.g. a player who logged off) is gone; drop the feedback.
        }
    }

    /** Send failure feedback, guarding against a source that has since gone away. */
    private static void safeFailure(CommandSourceStack src, String message) {
        try {
            src.sendFailure(Component.literal(message));
        } catch (RuntimeException ignored) {
            // Source gone; nothing to report to.
        }
    }

    /** Display name for the server (world/level name), matching the discovery endpoint's source. */
    private static String serverName(MinecraftServer server) {
        String name = server.getWorldData() != null ? server.getWorldData().getLevelName() : "";
        return name == null || name.isBlank() ? "Minecraft Server" : name;
    }

    /** Human relay state for {@code /nerolink status}: connected / connecting / disabled. */
    private static String relayStateText(NeroLinkBridge bridge) {
        return switch (bridge.relay().state()) {
            case CONNECTED -> "connected";
            case CONNECTING -> "connecting";
            case DISABLED -> "disabled";
        };
    }

    private static int countDevices(NeroLinkBridge bridge) {
        // Total paired devices across all players (op-visible aggregate, no personal data).
        int total = 0;
        for (ServerPlayer p : bridge.server().getPlayerList().getPlayers()) {
            total += bridge.tokens().devicesOf(p.getUUID()).size();
        }
        return total;
    }
}

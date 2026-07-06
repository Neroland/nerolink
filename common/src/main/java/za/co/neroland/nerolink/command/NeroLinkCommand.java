package za.co.neroland.nerolink.command;

import java.util.List;
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
import za.co.neroland.nerolink.auth.TokenStore;

/**
 * The {@code /nerolink} command tree, built with vanilla Brigadier so it is byte-identical on
 * every loader (each loader just calls {@link #register(CommandDispatcher)} from its command
 * event). Sub-commands:
 * <ul>
 *   <li>{@code pair} — mint a pairing code and whisper it to the running player only.</li>
 *   <li>{@code devices} — list your paired devices (names + last-seen, never tokens).</li>
 *   <li>{@code revoke <device>} — revoke one of your devices by id.</li>
 *   <li>{@code status} — op-only bridge status (client counts + module list).</li>
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
                        .executes(NeroLinkCommand::status)));
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
        // Whisper the code to this player only.
        player.sendSystemMessage(Component.empty()
                .append(Component.literal("[NeroLink] ").withStyle(ChatFormatting.AQUA))
                .append(Component.literal("Pairing code: ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(code).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)));
        player.sendSystemMessage(Component.empty()
                .append(Component.literal("Bridge address: ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(bridgeAddress()).withStyle(ChatFormatting.AQUA))
                .append(Component.literal("  (NOT the 'open to LAN' game port)")
                        .withStyle(ChatFormatting.DARK_GRAY)));
        player.sendSystemMessage(Component.literal(
                "Enter both in your NeroLink companion client within 5 minutes. Single-use.")
                .withStyle(ChatFormatting.DARK_GRAY));
        return Command.SINGLE_SUCCESS;
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
        src.sendSuccess(() -> Component.literal("[NeroLink] Bridge running. ")
                .withStyle(ChatFormatting.AQUA)
                .append(Component.literal("Paired devices: " + deviceCount + ". ")
                        .withStyle(ChatFormatting.GRAY))
                .append(Component.literal("Modules: " + moduleList)
                        .withStyle(ChatFormatting.GRAY)), false);
        return Command.SINGLE_SUCCESS;
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

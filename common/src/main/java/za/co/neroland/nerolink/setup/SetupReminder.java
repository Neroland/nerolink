package za.co.neroland.nerolink.setup;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import za.co.neroland.nerolink.NeroLinkBridge;
import za.co.neroland.nerolink.command.NeroLinkCommand;
import za.co.neroland.nerolink.config.NeroLinkConfig;

/**
 * The join-time welcome message for a server or single-player world that has NeroLink installed
 * but has never run {@code /nerolink setup}. Operators are told the one command that finishes the
 * job. Players who cannot run it are only told when they could not pair a device at all: on a
 * dedicated server to ask an admin, in a single-player world how the host can switch it on.
 *
 * <p>Each player sees it at most once per server run, and it stops for everyone as soon as a relay
 * registration exists. Admins who run direct mode on purpose turn it off with
 * {@code setupReminder=false}.
 *
 * <p>POPIA/GDPR: the "already reminded" set lives in memory only. It is never written to disk,
 * logged or sent anywhere, it is cleared when the server stops, and a player's entry is dropped
 * by the shared erasure hook.
 */
public final class SetupReminder {

    /** Who the reminder is worded for, or {@link #NONE} when nothing should be sent. */
    public enum Audience {
        NONE, OPERATOR, PLAYER, HOST
    }

    /** Players already reminded during this server run (memory only). */
    private static final Set<UUID> REMINDED = ConcurrentHashMap.newKeySet();

    private SetupReminder() {
    }

    /**
     * Decide who, if anyone, gets the reminder. Pure, so the rules are unit-testable.
     *
     * @param enabled         the {@code setupReminder} config lever
     * @param bridgeRunning   the bridge is up (a bridge disabled in config is not "not set up")
     * @param dedicated       a dedicated server, as opposed to a single-player / open-to-LAN world
     * @param relayConfigured a relay registration or manual relay override exists
     * @param directReachable the direct-mode listener is up and not loopback-only
     * @param canSetup        the joining player may run {@code /nerolink setup}
     */
    public static Audience audience(boolean enabled, boolean bridgeRunning, boolean dedicated,
                                    boolean relayConfigured, boolean directReachable, boolean canSetup) {
        if (!enabled || !bridgeRunning || relayConfigured) {
            return Audience.NONE;
        }
        if (canSetup) {
            return Audience.OPERATOR;
        }
        // Without a relay, players can still pair over a reachable direct listener; leave them be.
        if (directReachable) {
            return Audience.NONE;
        }
        // A single-player world without cheats has nobody who may run the command, so the host
        // gets the config route as well.
        return dedicated ? Audience.PLAYER : Audience.HOST;
    }

    /** Called by each loader's player-join event, on the server thread. */
    public static void onPlayerJoin(ServerPlayer player) {
        NeroLinkBridge bridge = NeroLinkBridge.instance();
        if (bridge == null || !NeroLinkConfig.SETUP_REMINDER.get()) {
            return;
        }
        MinecraftServer server = bridge.server();
        NeroLinkBridge.DirectEndpoint direct = bridge.direct();
        boolean directReachable = direct.running() && !direct.loopbackOnly();
        boolean dedicated = server.isDedicatedServer();
        Audience audience = audience(true, true, dedicated,
                NeroLinkBridge.resolveRelayCredentials(server) != null, directReachable,
                NeroLinkCommand.canRunSetup(player));
        if (audience == Audience.NONE || !REMINDED.add(player.getUUID())) {
            return;
        }
        if (audience == Audience.OPERATOR) {
            player.sendSystemMessage(Component.empty()
                    .append(Component.literal("[NeroLink] ").withStyle(ChatFormatting.AQUA))
                    .append(Component.literal("is installed but not set up yet. Run ")
                            .withStyle(ChatFormatting.GRAY))
                    .append(Component.literal("/nerolink setup").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD))
                    .append(Component.literal(" to connect this " + (dedicated ? "server" : "world")
                            + " to the NeroLink app. "
                            + "It is one command and needs no restart.").withStyle(ChatFormatting.GRAY)));
            player.sendSystemMessage(Component.literal(directReachable
                    ? "Until then, devices can only pair over the direct address (same network or a forwarded port)."
                    : "Until then, players cannot pair a device.")
                    .withStyle(ChatFormatting.YELLOW));
            player.sendSystemMessage(Component.literal(
                    "To hide this reminder, set setupReminder=false in config/nerolink.properties.")
                    .withStyle(ChatFormatting.DARK_GRAY));
        } else if (audience == Audience.HOST) {
            player.sendSystemMessage(Component.empty()
                    .append(Component.literal("[NeroLink] ").withStyle(ChatFormatting.AQUA))
                    .append(Component.literal("is installed but not set up yet, so the NeroLink app "
                            + "cannot reach this world.").withStyle(ChatFormatting.GRAY)));
            player.sendSystemMessage(Component.empty()
                    .append(Component.literal("With cheats on, run ").withStyle(ChatFormatting.GRAY))
                    .append(Component.literal("/nerolink setup").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD))
                    .append(Component.literal(" to use it from anywhere. For your own network only, set "
                            + "singleplayerLanAccess=true in config/nerolink.properties and reload the world.")
                            .withStyle(ChatFormatting.GRAY)));
            player.sendSystemMessage(Component.literal(
                    "To hide this reminder, set setupReminder=false in config/nerolink.properties.")
                    .withStyle(ChatFormatting.DARK_GRAY));
        } else {
            player.sendSystemMessage(Component.empty()
                    .append(Component.literal("[NeroLink] ").withStyle(ChatFormatting.AQUA))
                    .append(Component.literal("This server has the NeroLink companion app bridge installed, "
                            + "but it is not set up yet. Ask an admin to run ").withStyle(ChatFormatting.GRAY))
                    .append(Component.literal("/nerolink setup").withStyle(ChatFormatting.GOLD))
                    .append(Component.literal(".").withStyle(ChatFormatting.GRAY)));
        }
    }

    /** Drop one player's entry (shared erasure hook). */
    public static void forget(UUID uuid) {
        REMINDED.remove(uuid);
    }

    /** Clear the per-run memory (server stopping). */
    public static void reset() {
        REMINDED.clear();
    }
}

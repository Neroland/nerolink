package za.co.neroland.nerolink.progression;

import java.util.UUID;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;

import za.co.neroland.nerolandcore.progression.ProgressionGates;
import za.co.neroland.nerolink.NeroLinkCommon;

/**
 * The companion-app milestones NeroLink records as player-scope Neroland Core gates. Each one is a gate file
 * under {@code data/nerolink/neroland_gates/} with no {@code requires}; other mods (for example a trophy or
 * quest pack) can read them, and NeroLink never reads them back to decide anything.
 *
 * <p>Privacy (POPIA/GDPR): only the player's UUID goes into {@link ProgressionGates#open(MinecraftServer, UUID,
 * Identifier)}, which stores it in Core's progression store (purged by Core's erasure hook). No device id,
 * device name, token hash, address or module data reaches a gate, and nothing here logs anything.
 *
 * <p>Must be called on the server thread. Recording is idempotent: Core ignores an open on an open gate.
 */
public final class LinkMilestones {

    /** One recordable milestone and the gate it opens. */
    public enum Milestone {
        /** The player paired their first device. */
        FIRST_PAIRED("first_paired"),
        /** The player has two paired devices at the same time. */
        TWO_DEVICES("two_devices"),
        /** A module action the player sent from the app succeeded. */
        FIRST_ACTION("first_action"),
        /** The bridge served the player an in-app wiki page. */
        WIKI_READER("wiki_reader"),
        /** The player turned on at least one notification category. */
        ALERTS_ENABLED("alerts_enabled");

        private final Identifier gate;

        Milestone(String path) {
            this.gate = Identifier.fromNamespaceAndPath(NeroLinkCommon.MOD_ID, path);
        }

        /** The player-scope record gate this milestone opens. */
        public Identifier gate() {
            return gate;
        }
    }

    private LinkMilestones() {
    }

    /**
     * Record {@code milestone} for a player, online or offline. Never throws: a failure here must not break
     * the API request that triggered it.
     */
    public static void record(MinecraftServer server, UUID player, Milestone milestone) {
        if (server == null || player == null) {
            return;
        }
        try {
            ProgressionGates.open(server, player, milestone.gate());
        } catch (RuntimeException e) {
            // Type only: no player data in logs.
            NeroLinkCommon.LOGGER.debug("[NeroLink] milestone {} not recorded: {}",
                    milestone.name(), e.getClass().getName());
        }
    }
}

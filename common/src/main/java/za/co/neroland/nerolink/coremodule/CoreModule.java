package za.co.neroland.nerolink.coremodule;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import za.co.neroland.nerolandcore.link.LinkActionHandler;
import za.co.neroland.nerolandcore.link.LinkActionResult;
import za.co.neroland.nerolandcore.link.LinkAlert;
import za.co.neroland.nerolandcore.link.LinkAlerts;
import za.co.neroland.nerolandcore.link.LinkModuleInfo;
import za.co.neroland.nerolandcore.link.LinkSnapshotProvider;
import za.co.neroland.nerolandcore.link.NeroLinkRegistry;
import za.co.neroland.nerolandcore.progression.CoreGates;
import za.co.neroland.nerolandcore.progression.ProgressionGates;
import za.co.neroland.nerolink.NeroLinkBridge;
import za.co.neroland.nerolink.platform.InstalledMods;

/**
 * The built-in {@code core} module the bridge itself provides, so a Core-only server is fully
 * functional per the design ("a Core-only server still gets a useful app"). Registered into
 * {@link NeroLinkRegistry} as both a {@link LinkSnapshotProvider} and a {@link LinkActionHandler}.
 *
 * <p>Sections:
 * <ul>
 *   <li>{@code gates} — the four Core progression gates with per-player unlocked state
 *       (drawn from {@link ProgressionGates}), scope-correct via the online {@link ServerPlayer}.</li>
 *   <li>{@code alerts} — the player's active alerts from {@link LinkAlerts}.</li>
 *   <li>{@code energy} / {@code storage} — well-formed but empty in v1 with a {@code note}:
 *       Core exposes no cheap global index of a player's energy/storage blocks, and the design
 *       forbids scanning all loaded chunks. Documented as a TODO; a future Core index lights
 *       these up without an app change (schema is additive).</li>
 *   <li>{@code mods} — server-wide snapshot of the installed Neroland mods (id/name/version),
 *       plus the running {@code loader} and {@code mcVersion}, collected once per loader at init
 *       (see {@link InstalledMods}). Public metadata, identical for every player.</li>
 * </ul>
 *
 * <p>Action: {@code ack_alert} — acknowledge (and optionally snooze) one of the player's own
 * alerts. Every read/write is scoped to the authenticated player's UUID.
 */
public final class CoreModule implements LinkSnapshotProvider, LinkActionHandler {

    public static final String MODULE_ID = "core";
    public static final int SCHEMA_VERSION = 1;

    private static final List<String> SECTIONS = List.of("gates", "alerts", "energy", "storage", "mods");
    private static final List<String> ACTIONS = List.of("ack_alert");

    /** The four Core gates in unlock order, with app-friendly labels. */
    private static final List<Identifier> GATES = List.of(
            CoreGates.INDUSTRIAL_POWER,
            CoreGates.REACHED_ORBIT,
            CoreGates.FIRST_COLONY,
            CoreGates.DEEP_SPACE);

    private static final CoreModule INSTANCE = new CoreModule();

    private CoreModule() {
    }

    /**
     * Core version reported in this module's discovery entry. The bridge requires Core 2.0+
     * (see the loader manifests); this is a display string, not a resolution constraint.
     */
    public static final String CORE_VERSION = "2.0.0";

    /** Register the core module (snapshot + action) with the link registry. */
    public static void register() {
        LinkModuleInfo info = new LinkModuleInfo(MODULE_ID, CORE_VERSION, SCHEMA_VERSION, SECTIONS, ACTIONS);
        NeroLinkRegistry.registerSnapshotProvider(INSTANCE, info);
        NeroLinkRegistry.registerActionHandler(INSTANCE, info);
    }

    // --- LinkSnapshotProvider --------------------------------------------------------

    @Override
    public String moduleId() {
        return MODULE_ID;
    }

    @Override
    public int schemaVersion() {
        return SCHEMA_VERSION;
    }

    @Override
    public List<String> sections() {
        return SECTIONS;
    }

    @Override
    public JsonObject snapshot(UUID playerId, String section, Map<String, String> params) {
        MinecraftServer server = server();
        return switch (section) {
            case "gates" -> gates(server, playerId);
            case "alerts" -> alerts(server, playerId);
            case "energy" -> emptyWithNote("energy",
                    "Per-player energy index not available in Core v2; chunk scanning is disallowed. "
                            + "Populated by a future Core storage index (additive schema).");
            case "storage" -> emptyWithNote("storage",
                    "Per-player storage index not available in Core v2; chunk scanning is disallowed. "
                            + "Populated by a future Core storage index (additive schema).");
            case "mods" -> mods(server);
            default -> {
                JsonObject obj = new JsonObject();
                obj.addProperty("section", section);
                obj.addProperty("note", "unknown core section");
                yield obj;
            }
        };
    }

    private JsonObject gates(MinecraftServer server, UUID playerId) {
        JsonObject out = new JsonObject();
        out.addProperty("asOf", System.currentTimeMillis());
        JsonArray arr = new JsonArray();
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        for (Identifier gate : GATES) {
            JsonObject g = new JsonObject();
            g.addProperty("id", gate.getPath());
            boolean unlocked;
            if (player != null) {
                // Scope-correct check (resolves player/team/server scope of the gate).
                unlocked = ProgressionGates.isOpen(player, gate);
            } else {
                // Offline: fall back to server-scope openness (player-scope needs a ServerPlayer).
                unlocked = ProgressionGates.isServerOpen(server, gate);
            }
            g.addProperty("unlocked", unlocked);
            arr.add(g);
        }
        out.add("gates", arr);
        return out;
    }

    private JsonObject alerts(MinecraftServer server, UUID playerId) {
        JsonObject out = new JsonObject();
        out.addProperty("asOf", System.currentTimeMillis());
        JsonArray arr = new JsonArray();
        long now = System.currentTimeMillis();
        for (LinkAlert alert : LinkAlerts.get(server).list(playerId)) {
            JsonObject a = new JsonObject();
            a.addProperty("id", alert.id());
            a.addProperty("module", alert.moduleId());
            a.addProperty("severity", alert.severity().name());
            a.addProperty("text", alert.text());
            a.addProperty("at", alert.createdAt());
            a.addProperty("acked", alert.acked());
            a.addProperty("snoozed", alert.isSnoozedAt(now));
            arr.add(a);
        }
        out.add("alerts", arr);
        return out;
    }

    /**
     * Server-wide snapshot of the installed Neroland mods plus the running loader and MC version,
     * so the app can render a mods overview and drive update checks. Not player-scoped — a mods
     * list is public metadata, identical for every player. The list is collected once per loader
     * at init into {@link InstalledMods}; here it is sorted by id for a stable app ordering.
     */
    private JsonObject mods(MinecraftServer server) {
        JsonObject out = new JsonObject();
        out.addProperty("asOf", System.currentTimeMillis());
        out.addProperty("loader", InstalledMods.loader());
        out.addProperty("mcVersion", server.getServerVersion());
        List<InstalledMods.Entry> entries = new ArrayList<>(InstalledMods.entries());
        entries.sort(Comparator.comparing(InstalledMods.Entry::id));
        JsonArray arr = new JsonArray();
        for (InstalledMods.Entry e : entries) {
            JsonObject m = new JsonObject();
            m.addProperty("id", e.id());
            m.addProperty("name", e.name());
            m.addProperty("version", e.version());
            arr.add(m);
        }
        out.add("mods", arr);
        return out;
    }

    private static JsonObject emptyWithNote(String key, String note) {
        JsonObject out = new JsonObject();
        out.addProperty("asOf", System.currentTimeMillis());
        out.add(key, new JsonArray());
        out.addProperty("note", note);
        return out;
    }

    // --- LinkActionHandler -----------------------------------------------------------

    @Override
    public List<String> actionIds() {
        return ACTIONS;
    }

    @Override
    public boolean allowOffline(String actionId) {
        // Acking your own alert makes sense while offline.
        return "ack_alert".equals(actionId);
    }

    @Override
    public LinkActionResult execute(UUID playerId, String actionId, JsonObject params) {
        if (!"ack_alert".equals(actionId)) {
            return LinkActionResult.error(LinkActionResult.Error.VALIDATION, "unknown core action: " + actionId);
        }
        if (params == null || !params.has("alertId")) {
            return LinkActionResult.error(LinkActionResult.Error.VALIDATION, "alertId is required");
        }
        String alertId = params.get("alertId").getAsString();
        MinecraftServer server = server();
        LinkAlerts alerts = LinkAlerts.get(server);

        boolean ok;
        if (params.has("snoozeMs") && !params.get("snoozeMs").isJsonNull()) {
            long snoozeMs = params.get("snoozeMs").getAsLong();
            ok = alerts.snooze(server, playerId, alertId, System.currentTimeMillis() + Math.max(0, snoozeMs));
        } else {
            ok = alerts.ack(server, playerId, alertId);
        }
        if (!ok) {
            return LinkActionResult.error(LinkActionResult.Error.NOT_OWNER,
                    "no such alert for this player");
        }
        JsonObject state = new JsonObject();
        state.addProperty("alertId", alertId);
        state.addProperty("acked", true);
        return LinkActionResult.ok(state);
    }

    private static MinecraftServer server() {
        NeroLinkBridge bridge = NeroLinkBridge.instance();
        if (bridge == null) {
            throw new IllegalStateException("bridge not running");
        }
        return bridge.server();
    }
}

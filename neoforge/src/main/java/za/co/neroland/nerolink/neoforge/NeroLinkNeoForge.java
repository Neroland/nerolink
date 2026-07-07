package za.co.neroland.nerolink.neoforge;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforgespi.language.IModInfo;

import za.co.neroland.nerolink.NeroLinkBridge;
import za.co.neroland.nerolink.NeroLinkCommon;
import za.co.neroland.nerolink.command.NeroLinkCommand;
import za.co.neroland.nerolink.platform.InstalledMods;
import za.co.neroland.nerolink.telemetry.NeroLinkTelemetry;

/**
 * NeoForge entry point for NeroLink. After the common init (config + erasure), it wires the
 * {@code /nerolink} command and starts/stops the bridge's embedded HTTP+WS server on the game
 * event bus's server-lifecycle events.
 */
@Mod(NeroLinkCommon.MOD_ID)
public final class NeroLinkNeoForge {

    public NeroLinkNeoForge(IEventBus modEventBus, ModContainer modContainer) {
        NeroLinkCommon.LOGGER.info("[NeroLink] NeoForge bootstrap");
        NeroLinkCommon.init();
        collectInstalledMods();
        NeroLinkTelemetry.init(!FMLEnvironment.isProduction(),
                FMLEnvironment.getDist() == Dist.CLIENT, minecraftVersion());

        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) ->
                NeroLinkCommand.register(event.getDispatcher()));
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent event) ->
                NeroLinkBridge.start(event.getServer()));
        NeoForge.EVENT_BUS.addListener((ServerStoppingEvent event) ->
                NeroLinkBridge.stop());
    }

    /**
     * Snapshot the loaded Neroland mods (ids starting with {@code nero}, case-insensitive, the
     * bridge itself included). {@link ModList} is built during mod discovery, before mod
     * construction, so {@link ModList#getMods()} is fully populated here.
     */
    private static void collectInstalledMods() {
        List<InstalledMods.Entry> mods = new ArrayList<>();
        for (IModInfo info : ModList.get().getMods()) {
            String id = info.getModId();
            if (id != null && id.toLowerCase(Locale.ROOT).startsWith("nero")) {
                mods.add(new InstalledMods.Entry(id, info.getDisplayName(),
                        info.getVersion().toString()));
            }
        }
        InstalledMods.set("neoforge", mods);
    }

    /** The running Minecraft version (loaded as a mod by NeoForge), or {@code "unknown"}. */
    private static String minecraftVersion() {
        return ModList.get().getModContainerById("minecraft")
                .map(mc -> mc.getModInfo().getVersion().toString())
                .orElse("unknown");
    }
}

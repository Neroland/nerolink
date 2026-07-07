package za.co.neroland.nerolink.forge;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.forgespi.language.IModInfo;

import za.co.neroland.nerolink.NeroLinkBridge;
import za.co.neroland.nerolink.NeroLinkCommon;
import za.co.neroland.nerolink.command.NeroLinkCommand;
import za.co.neroland.nerolink.platform.InstalledMods;
import za.co.neroland.nerolink.telemetry.NeroLinkTelemetry;

/**
 * MinecraftForge entry point for NeroLink. After the common init (config + erasure), it wires the
 * {@code /nerolink} command and starts/stops the bridge's embedded HTTP+WS server on the game
 * bus's server-lifecycle events (26.x per-event {@code .BUS} idiom).
 */
@Mod(NeroLinkCommon.MOD_ID)
public final class NeroLinkForge {

    public NeroLinkForge(FMLJavaModLoadingContext context) {
        NeroLinkCommon.LOGGER.info("[NeroLink] Forge bootstrap");
        NeroLinkCommon.init();
        collectInstalledMods();
        NeroLinkTelemetry.init(!FMLEnvironment.production,
                FMLEnvironment.dist == Dist.CLIENT, minecraftVersion());

        RegisterCommandsEvent.BUS.addListener(event -> NeroLinkCommand.register(event.getDispatcher()));
        ServerStartedEvent.BUS.addListener(event -> NeroLinkBridge.start(event.getServer()));
        ServerStoppingEvent.BUS.addListener(event -> NeroLinkBridge.stop());
    }

    /**
     * Snapshot the loaded Neroland mods (ids starting with {@code nero}, case-insensitive, the
     * bridge itself included). {@link ModList} is built during mod discovery, before mod
     * construction, so {@link ModList#getMods()} is fully populated here.
     */
    private static void collectInstalledMods() {
        List<InstalledMods.Entry> mods = new ArrayList<>();
        for (IModInfo info : ModList.getMods()) {
            String id = info.getModId();
            if (id != null && id.toLowerCase(Locale.ROOT).startsWith("nero")) {
                mods.add(new InstalledMods.Entry(id, info.getDisplayName(),
                        info.getVersion().toString()));
            }
        }
        InstalledMods.set("forge", mods);
    }

    /** The running Minecraft version (loaded as a mod by Forge), or {@code "unknown"}. */
    private static String minecraftVersion() {
        for (IModInfo info : ModList.getMods()) {
            if ("minecraft".equals(info.getModId())) {
                return info.getVersion().toString();
            }
        }
        return "unknown";
    }
}

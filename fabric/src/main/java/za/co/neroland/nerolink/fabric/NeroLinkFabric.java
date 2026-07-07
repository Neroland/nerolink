package za.co.neroland.nerolink.fabric;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModMetadata;

import za.co.neroland.nerolink.NeroLinkBridge;
import za.co.neroland.nerolink.NeroLinkCommon;
import za.co.neroland.nerolink.command.NeroLinkCommand;
import za.co.neroland.nerolink.platform.InstalledMods;
import za.co.neroland.nerolink.telemetry.NeroLinkTelemetry;

/**
 * Fabric entry point for NeroLink. After the common init (config + erasure), it wires the
 * {@code /nerolink} command and starts/stops the bridge's embedded HTTP+WS server on the
 * server-lifecycle events, so the socket only exists while a world is running.
 */
public final class NeroLinkFabric implements ModInitializer {

    @Override
    public void onInitialize() {
        NeroLinkCommon.LOGGER.info("[NeroLink] Fabric bootstrap");
        NeroLinkCommon.init();
        collectInstalledMods();
        FabricLoader loader = FabricLoader.getInstance();
        NeroLinkTelemetry.init(loader.isDevelopmentEnvironment(),
                loader.getEnvironmentType() == EnvType.CLIENT, minecraftVersion());

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                NeroLinkCommand.register(dispatcher));

        ServerLifecycleEvents.SERVER_STARTED.register(NeroLinkBridge::start);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> NeroLinkBridge.stop());
    }

    /**
     * Snapshot the loaded Neroland mods (ids starting with {@code nero}, case-insensitive, the
     * bridge itself included). At {@link ModInitializer} time Fabric mod discovery is complete, so
     * {@link FabricLoader#getAllMods()} is fully populated.
     */
    private static void collectInstalledMods() {
        List<InstalledMods.Entry> mods = new ArrayList<>();
        for (ModContainer container : FabricLoader.getInstance().getAllMods()) {
            ModMetadata meta = container.getMetadata();
            String id = meta.getId();
            if (id != null && id.toLowerCase(Locale.ROOT).startsWith("nero")) {
                mods.add(new InstalledMods.Entry(id, meta.getName(),
                        meta.getVersion().getFriendlyString()));
            }
        }
        InstalledMods.set("fabric", mods);
    }

    /** The running Minecraft version (loaded as a mod by Fabric), or {@code "unknown"}. */
    private static String minecraftVersion() {
        return FabricLoader.getInstance().getModContainer("minecraft")
                .map(mc -> mc.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }
}

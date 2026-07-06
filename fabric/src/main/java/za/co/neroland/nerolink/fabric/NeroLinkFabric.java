package za.co.neroland.nerolink.fabric;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

import za.co.neroland.nerolink.NeroLinkBridge;
import za.co.neroland.nerolink.NeroLinkCommon;
import za.co.neroland.nerolink.command.NeroLinkCommand;

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

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                NeroLinkCommand.register(dispatcher));

        ServerLifecycleEvents.SERVER_STARTED.register(NeroLinkBridge::start);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> NeroLinkBridge.stop());
    }
}

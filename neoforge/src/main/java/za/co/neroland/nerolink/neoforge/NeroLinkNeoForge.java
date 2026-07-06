package za.co.neroland.nerolink.neoforge;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

import za.co.neroland.nerolink.NeroLinkBridge;
import za.co.neroland.nerolink.NeroLinkCommon;
import za.co.neroland.nerolink.command.NeroLinkCommand;

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

        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) ->
                NeroLinkCommand.register(event.getDispatcher()));
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent event) ->
                NeroLinkBridge.start(event.getServer()));
        NeoForge.EVENT_BUS.addListener((ServerStoppingEvent event) ->
                NeroLinkBridge.stop());
    }
}

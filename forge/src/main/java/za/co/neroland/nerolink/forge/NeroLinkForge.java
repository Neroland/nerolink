package za.co.neroland.nerolink.forge;

import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

import za.co.neroland.nerolink.NeroLinkBridge;
import za.co.neroland.nerolink.NeroLinkCommon;
import za.co.neroland.nerolink.command.NeroLinkCommand;

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

        RegisterCommandsEvent.BUS.addListener(event -> NeroLinkCommand.register(event.getDispatcher()));
        ServerStartedEvent.BUS.addListener(event -> NeroLinkBridge.start(event.getServer()));
        ServerStoppingEvent.BUS.addListener(event -> NeroLinkBridge.stop());
    }
}

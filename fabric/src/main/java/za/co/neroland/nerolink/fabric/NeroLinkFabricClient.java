package za.co.neroland.nerolink.fabric;

import net.fabricmc.api.ClientModInitializer;

import za.co.neroland.nerolink.NeroLinkCommon;

/** Fabric client entry point for NeroLink. */
public final class NeroLinkFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        NeroLinkCommon.LOGGER.info("[NeroLink] Fabric client bootstrap");
    }
}

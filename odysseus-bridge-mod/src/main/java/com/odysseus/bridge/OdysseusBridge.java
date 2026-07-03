package com.odysseus.bridge;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Odysseus Bridge — client-side Fabric addon.
 *
 * Boots a background WebSocket client that connects to the local Odysseus
 * server. Receives Baritone commands (e.g. "goto 100 64 -50") and executes
 * them through Baritone's chat control. Streams Baritone process events
 * back to Odysseus as JSON.
 *
 * Zero server chat traffic — everything happens on your machine.
 */
public class OdysseusBridge implements ClientModInitializer {
    public static final String MOD_ID   = "odysseus-bridge";
    public static final String VERSION  = "0.1.0";
    // Where Odysseus is listening. Override with env OYDSSEUS_BRIDGE_URL.
    public static final String DEFAULT_URL = "ws://127.0.0.1:7860/api/minecraft/copilot_bridge";

    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    private BridgeClient client;

    @Override
    public void onInitializeClient() {
        LOG.info("Odysseus Bridge {} loading — MC {}", VERSION,
                 FabricLoader.getInstance().getModContainer("minecraft")
                     .map(m -> m.getMetadata().getVersion().getFriendlyString())
                     .orElse("?"));

        String url = System.getenv().getOrDefault("ODYSSEUS_BRIDGE_URL", DEFAULT_URL);

        client = new BridgeClient(url);
        client.start();

        // Also hook Baritone's event bus for path progress notifications.
        // Wrapped in try/catch so this addon still loads even if Baritone
        // isn't present at runtime (we degrade to command-only, no events).
        try {
            BaritoneHook.install(client);
            LOG.info("Baritone event hook installed.");
        } catch (Throwable t) {
            LOG.warn("Couldn't install Baritone hook — events will be limited: {}", t.getMessage());
        }
    }
}

package com.odysseus.bridge;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.loader.api.FabricLoader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Odysseus Bridge — client-side Fabric addon.
 *
 * On launch, opens a WebSocket to the local Odysseus server. Commands
 * from Odysseus get dispatched to Baritone directly (via reflection —
 * no Baritone compile dep). Baritone's own status messages (which land
 * in the local chat overlay only, never touching server chat) get
 * intercepted via Fabric API's ClientReceiveMessageEvents and forwarded
 * back to Odysseus as JSON.
 *
 * Zero server chat traffic — everything happens on your machine.
 */
public class OdysseusBridge implements ClientModInitializer {
    public static final String MOD_ID   = "odysseus-bridge";
    public static final String VERSION  = "0.1.0";
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

        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            String text = message.getString();
            if (text == null || text.isEmpty()) return;
            if (text.startsWith("[Baritone]")) {
                String stripped = text.substring("[Baritone]".length()).trim();
                String event = classifyBaritoneMessage(stripped);
                client.sendEvent(event, stripped);
            }
        });

        LOG.info("Odysseus Bridge initialized. Watching chat for [Baritone] messages.");
    }

    private static String classifyBaritoneMessage(String text) {
        String low = text.toLowerCase();
        if (low.contains("goal reached") || low.contains("arrived") || low.contains("done"))
            return "goal_reached";
        if (low.contains("failure") || low.contains("failed") || low.contains("no path")
                || low.contains("cannot") || low.contains("unable"))
            return "path_failed";
        if (low.contains("cancel") || low.contains("paused"))
            return "cancelled";
        if (low.contains("going to") || low.contains("starting") || low.contains("mining")
                || low.contains("pathing"))
            return "path_start";
        return "log";
    }
}

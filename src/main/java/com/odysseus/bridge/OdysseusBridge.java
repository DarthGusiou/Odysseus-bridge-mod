package com.odysseus.bridge;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Odysseus Bridge — client-side Fabric addon.
 *
 * On launch, opens a WebSocket to the local Odysseus server. Commands
 * from Odysseus get dispatched to Baritone directly (via reflection —
 * no Baritone compile dep). Chat messages are captured via ChatHudMixin
 * (Baritone injects into the chat HUD directly, bypassing Fabric API's
 * ClientReceiveMessageEvents), classified, and forwarded to Odysseus.
 *
 * Zero server chat traffic — everything happens on your machine.
 */
public class OdysseusBridge implements ClientModInitializer {
    public static final String MOD_ID   = "odysseus-bridge";
    public static final String VERSION  = "0.1.0";
    // Where Odysseus is listening. Override with env ODYSSEUS_BRIDGE_URL.
    public static final String DEFAULT_URL = "ws://127.0.0.1:7860/api/minecraft/copilot_bridge";

    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    private static BridgeClient client;

    @Override
    public void onInitializeClient() {
        LOG.info("Odysseus Bridge {} loading — MC {}", VERSION,
                 FabricLoader.getInstance().getModContainer("minecraft")
                     .map(m -> m.getMetadata().getVersion().getFriendlyString())
                     .orElse("?"));

        String url = System.getenv().getOrDefault("ODYSSEUS_BRIDGE_URL", DEFAULT_URL);
        client = new BridgeClient(url);
        client.start();

        LOG.info("Odysseus Bridge initialized. Chat interception via ChatHud mixin.");
    }

    /** Called from ChatHudMixin for every message the chat overlay renders. */
    public static void onChatMessage(String text) {
        if (text == null || text.isEmpty() || client == null) return;
        if (!text.contains("Baritone")) return;
        String stripped = text;
        if (text.startsWith("[")) {
            int close = text.indexOf(']');
            if (close > 0 && close < text.length() - 1) {
                stripped = text.substring(close + 1).trim();
            }
        }
        String event = classifyBaritoneMessage(stripped);
        client.sendEvent(event, stripped);
    }

    /** Rough classifier so Odysseus can color the copilot_status event. */
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

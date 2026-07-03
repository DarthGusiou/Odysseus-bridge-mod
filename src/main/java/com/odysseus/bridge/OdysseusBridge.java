package com.odysseus.bridge;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OdysseusBridge implements ClientModInitializer {
    public static final String MOD_ID   = "odysseus-bridge";
    public static final String VERSION  = "0.1.2";
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

        LOG.info("Odysseus Bridge {} initialized. Diagnostic build — logs every intercepted chat line.", VERSION);
    }

    public static void onChatMessage(String text) {
        if (text == null) { LOG.info("[chat] null"); return; }
        LOG.info("[chat] raw=«{}»", text);
        if (text.isEmpty() || client == null) return;
        boolean isBaritone = text.contains("Baritone") || text.contains("baritone");
        if (!isBaritone) return;
        LOG.info("[chat] forwarding Baritone message");
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

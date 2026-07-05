package com.odysseus.bridge;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Odysseus Bridge — client-side Fabric addon.
 *
 * Runs a local WebSocket client that connects to Odysseus, dispatches
 * commands to Baritone (via reflection) or to our own OdysseusDispatcher
 * (crafting, chest ops, item use), and forwards Baritone/Odysseus status
 * messages back through the socket.
 *
 * Chat interception uses a ChatHudMixin — Baritone injects into the chat
 * overlay directly, bypassing Fabric API's receive events.
 *
 * Zero server chat traffic — everything happens on your machine.
 */
public class OdysseusBridge implements ClientModInitializer {
    public static final String MOD_ID   = "odysseus-bridge";
    public static final String VERSION  = "0.1.15";
    public static final String DEFAULT_URL = "ws://127.0.0.1:7860/api/minecraft/copilot_bridge";

    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    private static BridgeClient client;

    /** Structured event envelope prefix — see docs at
     *  github.com/DarthGusiou/Minecraft-AI/blob/1.21.8-minecraft-AI/docs/odysseus-events.md */
    private static final String STRUCTURED_PREFIX = "[ODY]";
    private static final Gson GSON = new Gson();

    /** Called by OdysseusDispatcher to send status back to Odysseus. */
    public static BridgeClient getClient() { return client; }

    @Override
    public void onInitializeClient() {
        LOG.info("Odysseus Bridge {} loading — MC {}", VERSION,
                 FabricLoader.getInstance().getModContainer("minecraft")
                     .map(m -> m.getMetadata().getVersion().getFriendlyString())
                     .orElse("?"));

        String url = System.getenv().getOrDefault("ODYSSEUS_BRIDGE_URL", DEFAULT_URL);
        client = new BridgeClient(url);
        client.start();

        // Register the tick pump that runs OdysseusDispatcher's task state machines.
        OdysseusDispatcher.register();

        LOG.info("Odysseus Bridge {} initialized. Chat interception + structured [ODY] event handling enabled.", VERSION);
    }

    /**
     * Detect + consume a structured Odysseus event embedded in a chat message.
     * Returns true if the message was a valid Odysseus envelope AND was forwarded;
     * caller should then cancel chat rendering. Returns false otherwise so the
     * caller can fall through to the legacy path-string matcher.
     *
     * Matches any text whose first non-whitespace token begins with "[ODY]" —
     * this way the check tolerates any leading formatting characters Baritone
     * may prepend (color codes, styling, etc.). The payload after "[ODY]" must
     * parse as a JSON object with both `schema_version` and `type` fields, or
     * we treat it as coincidence and fall through.
     */
    public static boolean tryHandleStructuredEvent(String text) {
        if (text == null || client == null) return false;
        int prefixIdx = text.indexOf(STRUCTURED_PREFIX);
        // Prefix must be near the start — bail early on regular chat that
        // happens to mention "[ODY]" mid-message.
        if (prefixIdx < 0 || prefixIdx > 4) return false;
        String rest = text.substring(prefixIdx + STRUCTURED_PREFIX.length()).trim();
        int jsonStart = rest.indexOf('{');
        if (jsonStart < 0) return false;
        String jsonSlice = rest.substring(jsonStart);
        try {
            JsonObject env = GSON.fromJson(jsonSlice, JsonObject.class);
            if (env == null || !env.has("schema_version") || !env.has("type")) {
                return false;
            }
            String type = env.get("type").getAsString();
            LOG.info("[bridge-struct] {} → {}", type,
                     env.has("message") ? env.get("message").getAsString() : "");
            client.sendStructuredEvent(env);
            return true;
        } catch (Throwable t) {
            LOG.warn("[bridge-struct] parse failed for '{}': {}", jsonSlice, t.toString());
            return false;
        }
    }

    /** Called from ChatHudMixin for every message the chat overlay renders. */
    public static void onChatMessage(String text) {
        if (text == null || text.isEmpty() || client == null) return;
        if (!isBaritoneOutput(text)) return;
        String stripped = text;
        if (text.startsWith("[Baritone]")) {
            stripped = text.substring("[Baritone]".length()).trim();
        }
        String event = classifyBaritoneMessage(stripped);
        LOG.info("[bridge] {} → {}", event, stripped);
        client.sendEvent(event, stripped);
    }

    /** Recognize Baritone output regardless of whether it has [Baritone] prefix. */
    private static boolean isBaritoneOutput(String text) {
        if (text.startsWith("<")) return false;
        if (text.contains("Baritone")) return true;
        if (text.startsWith("> ")) return true;
        if (text.contains("BetterBlockPos")
            || text.contains("GoalBlock")
            || text.contains("GoalComposite")
            || text.contains("GoalGetToBlock")
            || text.contains("GoalNear")
            || text.contains("GoalXZ")
            || text.contains("GoalYLevel")) return true;
        if (text.contains("movements considered")
            || text.contains("Favoring size")
            || text.contains("Starting to search")
            || text.contains("Finished finding")
            || text.contains("Path ends")
            || text.contains("No path found")
            || text.contains("cost coefficient")) return true;
        if (text.startsWith("Going to:")
            || text.startsWith("Mining")
            || text.startsWith("Farming")
            || text.startsWith("Building")
            || text.contains("Goal reached")) return true;
        return false;
    }

    private static String classifyBaritoneMessage(String text) {
        String low = text.toLowerCase();
        if (low.contains("goal reached") || low.contains("arrived"))
            return "goal_reached";
        if (low.contains("no path found") || low.contains("failed")
            || low.contains("cannot") || low.contains("unable"))
            return "path_failed";
        if (low.contains("cancel") || low.contains("paused"))
            return "cancelled";
        if (low.contains("starting to search") || low.contains("going to")
            || low.contains("finished finding") || low.contains("mining")
            || low.contains("path ends") || text.startsWith("> "))
            return "path_start";
        return "log";
    }
}

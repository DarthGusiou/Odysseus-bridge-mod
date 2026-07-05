package com.odysseus.bridge;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * WebSocket client. Connects to Odysseus, sends "hello" on connect,
 * routes incoming commands to Baritone or OdysseusDispatcher based on
 * prefix, and forwards events sent via {@link #sendEvent}.
 *
 * Auto-reconnects with exponential-ish backoff (5s → 30s cap) so a
 * temporarily-down Odysseus doesn't require a game restart.
 */
public class BridgeClient {
    private static final Gson GSON = new Gson();

    private final URI uri;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "odysseus-bridge");
                t.setDaemon(true);
                return t;
            });

    private volatile WebSocketClient socket;
    private volatile int backoffSeconds = 5;

    public BridgeClient(String url) {
        this.uri = URI.create(url);
    }

    public void start() {
        scheduleConnect(0);
    }

    private void scheduleConnect(long delaySeconds) {
        scheduler.schedule(this::connect, delaySeconds, TimeUnit.SECONDS);
    }

    private void connect() {
        try {
            socket = new WebSocketClient(uri) {
                @Override public void onOpen(ServerHandshake handshake) {
                    OdysseusBridge.LOG.info("Bridge WS connected → {}", uri);
                    backoffSeconds = 5;
                    sendHello();
                }
                @Override public void onMessage(String message) {
                    try {
                        JsonObject msg = GSON.fromJson(message, JsonObject.class);
                        String type = msg.has("type") ? msg.get("type").getAsString() : "";
                        if ("cmd".equals(type)) {
                            String cmd = msg.has("value") ? msg.get("value").getAsString() : "";
                            // Route by prefix — # = Baritone, ! = custom Odysseus commands.
                            String trimmed = cmd.trim();
                            if (trimmed.startsWith("!")) {
                                OdysseusDispatcher.execute(trimmed, BridgeClient.this);
                            } else {
                                BaritoneDispatcher.execute(cmd);
                            }
                        } else if ("ping".equals(type)) {
                            JsonObject pong = new JsonObject();
                            pong.addProperty("type", "pong");
                            send(pong.toString());
                        }
                    } catch (Exception e) {
                        OdysseusBridge.LOG.warn("Bad WS message: {}", e.getMessage());
                    }
                }
                @Override public void onClose(int code, String reason, boolean remote) {
                    OdysseusBridge.LOG.info("Bridge WS closed ({}): {}", code, reason);
                    backoffSeconds = Math.min(backoffSeconds * 2, 30);
                    scheduleConnect(backoffSeconds);
                }
                @Override public void onError(Exception ex) {
                    OdysseusBridge.LOG.warn("Bridge WS error: {}", ex.getMessage());
                }
            };
            socket.setConnectionLostTimeout(60);
            socket.connect();
        } catch (Exception e) {
            OdysseusBridge.LOG.warn("Bridge connect failed: {} — retrying in {}s", e.getMessage(), backoffSeconds);
            scheduleConnect(backoffSeconds);
            backoffSeconds = Math.min(backoffSeconds * 2, 30);
        }
    }

    private void sendHello() {
        JsonObject hello = new JsonObject();
        hello.addProperty("type", "hello");
        hello.addProperty("addon_version", OdysseusBridge.VERSION);
        hello.addProperty("mc_version",
            FabricLoader.getInstance().getModContainer("minecraft")
                .map(m -> m.getMetadata().getVersion().getFriendlyString())
                .orElse("?"));
        hello.addProperty("baritone_version",
            FabricLoader.getInstance().getModContainer("baritone")
                .map(m -> m.getMetadata().getVersion().getFriendlyString())
                .orElse(""));
        String username = "";
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null && mc.getSession() != null) {
            username = mc.getSession().getUsername();
        }
        hello.addProperty("user", username);
        socket.send(hello.toString());
    }

    /** Called by dispatchers when they have status to report back to Odysseus. */
    public void sendEvent(String event, String text) {
        WebSocketClient s = this.socket;
        if (s == null || !s.isOpen()) return;
        JsonObject msg = new JsonObject();
        msg.addProperty("type", "baritone_event");
        msg.addProperty("event", event);
        msg.addProperty("text", text);
        s.send(msg.toString());
    }

    /**
     * Forward a structured Odysseus event to the Odysseus backend. The
     * envelope contents are wrapped in a message of type "odysseus_structured"
     * so the backend WS handler can route it distinctly from legacy
     * "baritone_event" messages (which stay in place for the migration window).
     *
     * See docs/odysseus-events.md for the envelope schema.
     */
    public void sendStructuredEvent(JsonObject envelope) {
        WebSocketClient s = this.socket;
        if (s == null || !s.isOpen() || envelope == null) return;
        JsonObject msg = new JsonObject();
        msg.addProperty("type", "odysseus_structured");
        msg.add("event", envelope);
        s.send(msg.toString());
    }
}

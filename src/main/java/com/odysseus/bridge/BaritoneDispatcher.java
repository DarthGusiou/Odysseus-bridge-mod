package com.odysseus.bridge;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;

/**
 * Sends Baritone commands to the local client by injecting them as chat
 * messages. Baritone's own {@code ExampleBaritoneControl} intercepts
 * outgoing chat messages that begin with its command prefix ({@code #} by
 * default), cancels the send so nothing reaches the server, and dispatches
 * to its command manager — the exact path a user typing in chat takes.
 *
 * Why not reflection into {@code baritone.api.BaritoneAPI}: cross-mod
 * {@code Class.forName} kept throwing {@code ClassNotFoundException} on
 * this setup even with the standalone Baritone jar installed and the
 * Knot classloader passed explicitly. Chat injection sidesteps the
 * classloader question entirely and has fewer moving parts.
 */
public class BaritoneDispatcher {

    /** Execute a Baritone command string, e.g. "mine 6 oak_log" or "goto 100 64 -50". */
    public static void execute(String command) {
        if (command == null || command.isBlank()) return;
        String cmd = command.trim();
        // Baritone's chat interceptor requires the # prefix. Add if missing so
        // callers can pass either form.
        if (!cmd.startsWith("#")) cmd = "#" + cmd;

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null) {
            OdysseusBridge.LOG.warn("Dropped Baritone cmd — MC not initialized: {}", command);
            return;
        }

        final String finalCmd = cmd;
        mc.execute(() -> {
            ClientPlayerEntity player = mc.player;
            if (player == null) {
                OdysseusBridge.LOG.warn("Dropped Baritone cmd — no player: {}", finalCmd);
                return;
            }
            ClientPlayNetworkHandler net = player.networkHandler;
            if (net == null) {
                OdysseusBridge.LOG.warn("Dropped Baritone cmd — no network handler: {}", finalCmd);
                return;
            }
            try {
                // Baritone mixes into the outgoing-chat pipeline and cancels
                // messages beginning with its prefix, so this never leaves
                // the client and never appears in chat/server logs.
                net.sendChatMessage(finalCmd);
                OdysseusBridge.LOG.info("Baritone chat-inject: {}", finalCmd);
            } catch (Throwable t) {
                OdysseusBridge.LOG.warn("Baritone chat-inject failed [{}]: {}", finalCmd, t.toString());
            }
        });
    }
}

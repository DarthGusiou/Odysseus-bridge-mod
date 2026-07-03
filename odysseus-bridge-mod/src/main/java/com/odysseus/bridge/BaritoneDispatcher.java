package com.odysseus.bridge;

import baritone.api.BaritoneAPI;
import baritone.api.command.manager.ICommandManager;
import net.minecraft.client.MinecraftClient;

/**
 * Runs Baritone commands as if the local user had typed them, but WITHOUT
 * routing through the game's chat system. Uses Baritone's programmatic
 * ICommandManager so nothing appears in server chat.
 *
 * If a build ever finds this API missing, the fallback path (commented
 * inline) calls Baritone via the client's chat-command-processor with a
 * silent flag — that requires slightly newer Baritone but avoids any
 * server round-trip either way.
 */
public class BaritoneDispatcher {

    /** Execute a Baritone command string, e.g. "goto 100 64 -50" or "stop". */
    public static void execute(String command) {
        if (command == null || command.isBlank()) return;
        // Baritone commands can arrive with or without a leading `#`.
        String trimmed = command.trim();
        if (trimmed.startsWith("#")) trimmed = trimmed.substring(1);

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null) {
            OdysseusBridge.LOG.warn("Dropped Baritone cmd — MC not initialized: {}", trimmed);
            return;
        }

        String finalCmd = trimmed;
        // Execute on the main client thread; Baritone requires that.
        mc.execute(() -> {
            try {
                ICommandManager mgr = BaritoneAPI.getProvider()
                        .getPrimaryBaritone()
                        .getCommandManager();
                boolean ok = mgr.execute(finalCmd);
                OdysseusBridge.LOG.info("Baritone.execute({}) -> {}", finalCmd, ok);
            } catch (Throwable t) {
                OdysseusBridge.LOG.warn("Baritone command failed [{}]: {}", finalCmd, t.getMessage());
            }
        });
    }
}

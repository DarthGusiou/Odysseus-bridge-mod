package com.odysseus.bridge;

import net.minecraft.client.MinecraftClient;
import java.lang.reflect.Method;

/**
 * Runs Baritone commands without a compile-time dependency on Baritone.
 * Uses reflection so a single jar works with any Fabric Baritone fork
 * (Baritone-Meteor, Cabaletta upstream, etc.) as long as
 * `baritone.api.BaritoneAPI` is on the classpath at runtime.
 *
 * Nothing goes through server chat — the command is dispatched directly
 * to Baritone's ICommandManager on the main thread.
 */
public class BaritoneDispatcher {

    /** Execute a Baritone command string, e.g. "goto 100 64 -50" or "stop". */
    public static void execute(String command) {
        if (command == null || command.isBlank()) return;
        String cmd = command.trim();
        if (cmd.startsWith("#")) cmd = cmd.substring(1);

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null) {
            OdysseusBridge.LOG.warn("Dropped Baritone cmd — MC not initialized: {}", cmd);
            return;
        }

        final String finalCmd = cmd;
        // Use MinecraftClient's classloader (Fabric's Knot) so cross-mod
        // reflection can see Baritone. Class.forName(name) uses the caller's
        // module classloader which, from inside a mc.execute() lambda, is not
        // guaranteed to be Knot — reflection then fails with
        // ClassNotFoundException even when Baritone is fully installed.
        final ClassLoader cl = MinecraftClient.class.getClassLoader();
        mc.execute(() -> {
            try {
                Class<?> apiClass    = Class.forName("baritone.api.BaritoneAPI", true, cl);
                Object   provider    = apiClass.getMethod("getProvider").invoke(null);
                Object   primary     = provider.getClass().getMethod("getPrimaryBaritone").invoke(provider);
                Object   cmdManager  = primary.getClass().getMethod("getCommandManager").invoke(primary);
                Method   execute     = findExecuteMethod(cmdManager.getClass());
                Object   result      = execute.invoke(cmdManager, finalCmd);
                OdysseusBridge.LOG.info("Baritone.execute({}) -> {}", finalCmd, result);
            } catch (ClassNotFoundException notFound) {
                OdysseusBridge.LOG.warn("Baritone not on classpath — is a Baritone Fabric mod installed?");
            } catch (Throwable t) {
                OdysseusBridge.LOG.warn("Baritone command failed [{}]: {}", finalCmd, t.toString());
            }
        });
    }

    private static Method findExecuteMethod(Class<?> mgrClass) throws NoSuchMethodException {
        for (Method m : mgrClass.getMethods()) {
            if (!"execute".equals(m.getName())) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 1 && p[0] == String.class) return m;
        }
        throw new NoSuchMethodException("No execute(String) on " + mgrClass.getName());
    }
}

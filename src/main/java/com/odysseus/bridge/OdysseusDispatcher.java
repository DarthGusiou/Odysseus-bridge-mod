package com.odysseus.bridge;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Custom Odysseus commands — crafting, chest interaction, item use.
 * Runs a single ticking task at a time via ClientTickEvents so multi-step
 * ops (open menu → click recipe → take output → close) can span ticks
 * without blocking the render thread.
 *
 * Recipe iteration + clickRecipe use reflection — the Minecraft recipe
 * API changed shape in 1.21.5+ (RecipeManager.values gone, clickRecipe
 * now takes NetworkRecipeId, Recipe.getResult refactored) and reflection
 * keeps this working across future changes without version pinning.
 */
public class OdysseusDispatcher {
    private static volatile Task currentTask;

    /** Register the tick pump. Called once from OdysseusBridge.onInitializeClient. */
    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(OdysseusDispatcher::onTick);
    }

    private static void onTick(MinecraftClient client) {
        Task task = currentTask;
        if (task == null) return;
        try {
            if (task.tick(client)) {
                currentTask = null;
            }
        } catch (Throwable t) {
            OdysseusBridge.LOG.warn("[odysseus] task crashed: {}", t.toString());
            emit("error", task.name() + " crashed: " + t.getMessage());
            currentTask = null;
        }
    }

    /** Parse and dispatch a raw "!command args" string. */
    public static void execute(String rawCmd, BridgeClient client) {
        if (rawCmd == null || rawCmd.isBlank()) return;
        String cmd = rawCmd.trim();
        if (cmd.startsWith("!")) cmd = cmd.substring(1);
        String[] parts = cmd.split("\\s+");
        String verb = parts[0].toLowerCase();

        if (currentTask != null) {
            emit("busy", "Another Odysseus task is running: " + currentTask.name());
            return;
        }
        switch (verb) {
            case "craft":
                if (parts.length < 2) {
                    emit("craft_failed", "Usage: !craft <item> [count]");
                    return;
                }
                String item = parts[1];
                int count = parts.length >= 3 ? parseIntOr(parts[2], 1) : 1;
                currentTask = new CraftTask(item, count);
                return;
            case "use":
                currentTask = new UseHeldTask();
                return;
            default:
                emit("unknown_command", "Unknown Odysseus command: !" + verb);
        }
    }

    // ── Task API ──────────────────────────────────────────────────────────

    interface Task {
        /** Returns true when the task is finished. */
        boolean tick(MinecraftClient client);
        String name();
    }

    // ── Craft task ────────────────────────────────────────────────────────

    private static class CraftTask implements Task {
        private final String targetItemId;
        private final int targetCount;

        private State state = State.FIND_TABLE;
        private int ticksInState = 0;
        private int initialInventoryCount = -1;
        private int lastKnownCount = -1;
        private Object recipeEntry;   // opaque — accessed via reflection

        enum State { FIND_TABLE, WAIT_SCREEN, CLICK_RECIPE, WAIT_FILL, TAKE_OUTPUT, WAIT_TAKE, CHECK_COUNT, CLOSE, DONE }

        CraftTask(String itemId, int count) {
            this.targetItemId = normalizeId(itemId);
            this.targetCount = Math.max(1, count);
        }

        @Override public String name() { return "craft " + targetItemId + " x" + targetCount; }

        @Override
        public boolean tick(MinecraftClient client) {
            ClientPlayerEntity player = client.player;
            World world = client.world;
            if (player == null || world == null) return false;
            ticksInState++;
            switch (state) {
                case FIND_TABLE:      return doFindTable(client, player, world);
                case WAIT_SCREEN:     return doWaitScreen(client, player);
                case CLICK_RECIPE:    return doClickRecipe(client, player, world);
                case WAIT_FILL:       return doWaitFill(client, player);
                case TAKE_OUTPUT:     return doTakeOutput(client, player);
                case WAIT_TAKE:       return doWaitTake(client, player);
                case CHECK_COUNT:     return doCheckCount(client, player);
                case CLOSE:           return doClose(client, player);
                case DONE:            return true;
            }
            return true;
        }

        private void enter(State next) {
            this.state = next;
            this.ticksInState = 0;
        }

        private boolean doFindTable(MinecraftClient client, ClientPlayerEntity player, World world) {
            if (player.currentScreenHandler instanceof CraftingScreenHandler) {
                enter(State.CLICK_RECIPE);
                return false;
            }
            BlockPos here = player.getBlockPos();
            BlockPos found = null;
            outer:
            for (int dy = -1; dy <= 2; dy++) {
                for (int dx = -3; dx <= 3; dx++) {
                    for (int dz = -3; dz <= 3; dz++) {
                        BlockPos p = here.add(dx, dy, dz);
                        if (world.getBlockState(p).getBlock() == Blocks.CRAFTING_TABLE) {
                            found = p;
                            break outer;
                        }
                    }
                }
            }
            if (found == null) {
                emit("craft_failed", "No crafting_table within 3 blocks — walk to one first (#goto crafting_table).");
                enter(State.DONE);
                return true;
            }
            Vec3d hitVec = Vec3d.ofCenter(found);
            BlockHitResult hit = new BlockHitResult(hitVec, Direction.UP, found, false);
            ClientPlayerInteractionManager im = client.interactionManager;
            if (im != null) {
                im.interactBlock(player, Hand.MAIN_HAND, hit);
            }
            enter(State.WAIT_SCREEN);
            return false;
        }

        private boolean doWaitScreen(MinecraftClient client, ClientPlayerEntity player) {
            if (player.currentScreenHandler instanceof CraftingScreenHandler) {
                enter(State.CLICK_RECIPE);
                return false;
            }
            if (ticksInState > 40) {
                emit("craft_failed", "Timed out waiting for crafting screen to open.");
                enter(State.CLOSE);
                return false;
            }
            return false;
        }

        private boolean doClickRecipe(MinecraftClient client, ClientPlayerEntity player, World world) {
            if (recipeEntry == null) {
                Item target = itemFromId(targetItemId);
                if (target == null) {
                    emit("craft_failed", "Unknown item id: " + targetItemId);
                    enter(State.CLOSE);
                    return false;
                }
                Optional<Object> pick = findRecipeEntryForOutput(world, target);
                if (pick.isEmpty()) {
                    emit("craft_failed", "No recipe found that produces " + targetItemId);
                    enter(State.CLOSE);
                    return false;
                }
                recipeEntry = pick.get();
                initialInventoryCount = countInInventory(player, target);
                lastKnownCount = initialInventoryCount;
            }
            ScreenHandler h = player.currentScreenHandler;
            ClientPlayerInteractionManager im = client.interactionManager;
            if (im == null) {
                emit("craft_failed", "No interaction manager (client offline?).");
                enter(State.CLOSE);
                return false;
            }
            boolean ok = tryClickRecipe(im, h.syncId, recipeEntry, /*craftAll=*/true);
            if (!ok) {
                emit("craft_failed", "Failed to invoke clickRecipe (API mismatch — please report).");
                enter(State.CLOSE);
                return false;
            }
            enter(State.WAIT_FILL);
            return false;
        }

        private boolean doWaitFill(MinecraftClient client, ClientPlayerEntity player) {
            if (ticksInState >= 2) {
                enter(State.TAKE_OUTPUT);
            }
            return false;
        }

        private boolean doTakeOutput(MinecraftClient client, ClientPlayerEntity player) {
            ScreenHandler h = player.currentScreenHandler;
            if (!(h instanceof CraftingScreenHandler)) {
                emit("craft_failed", "Crafting screen closed unexpectedly.");
                enter(State.DONE);
                return true;
            }
            ItemStack outputStack = h.slots.get(0).getStack();
            if (outputStack.isEmpty()) {
                int haveNow = countInInventory(player, itemFromId(targetItemId));
                int delta = haveNow - initialInventoryCount;
                if (delta > 0) {
                    emit("craft_ok",
                        "Crafted " + delta + " " + targetItemId
                            + " (inventory now: " + haveNow + ")");
                } else {
                    emit("craft_failed",
                        "Grid empty — likely missing ingredients for " + targetItemId);
                }
                enter(State.CLOSE);
                return false;
            }
            ClientPlayerInteractionManager im = client.interactionManager;
            if (im != null) {
                im.clickSlot(h.syncId, 0, 0, SlotActionType.QUICK_MOVE, player);
            }
            enter(State.WAIT_TAKE);
            return false;
        }

        private boolean doWaitTake(MinecraftClient client, ClientPlayerEntity player) {
            if (ticksInState >= 2) {
                enter(State.CHECK_COUNT);
            }
            return false;
        }

        private boolean doCheckCount(MinecraftClient client, ClientPlayerEntity player) {
            Item target = itemFromId(targetItemId);
            int haveNow = countInInventory(player, target);
            int crafted = haveNow - initialInventoryCount;
            if (crafted >= targetCount) {
                emit("craft_ok",
                    "Crafted " + crafted + " " + targetItemId
                        + " (target " + targetCount + ", inventory " + haveNow + ")");
                enter(State.CLOSE);
                return false;
            }
            if (haveNow == lastKnownCount) {
                emit("craft_failed",
                    "Stopped at " + crafted + "/" + targetCount + " — no more ingredients for " + targetItemId);
                enter(State.CLOSE);
                return false;
            }
            lastKnownCount = haveNow;
            enter(State.TAKE_OUTPUT);
            return false;
        }

        private boolean doClose(MinecraftClient client, ClientPlayerEntity player) {
            if (player.currentScreenHandler != player.playerScreenHandler) {
                player.closeHandledScreen();
            }
            enter(State.DONE);
            return true;
        }
    }

    // ── Use held item task (right-click) ──────────────────────────────────

    private static class UseHeldTask implements Task {
        @Override public String name() { return "use held item"; }
        @Override
        public boolean tick(MinecraftClient client) {
            ClientPlayerEntity player = client.player;
            if (player == null || client.interactionManager == null) {
                emit("use_failed", "No player/interaction manager.");
                return true;
            }
            client.interactionManager.interactItem(player, Hand.MAIN_HAND);
            emit("use_ok", "Used held item.");
            return true;
        }
    }

    // ── Reflection helpers for recipe API (changes every MC bump) ─────────

    /** Iterate the client's known recipes. Tries multiple method names since
     *  the RecipeManager API shifts between MC versions. */
    @SuppressWarnings("unchecked")
    private static Iterable<Object> allRecipeEntries(Object recipeManager) {
        if (recipeManager == null) return List.of();
        Class<?> cls = recipeManager.getClass();
        for (String name : new String[]{"values", "recipes", "getRecipes", "getAllRecipes"}) {
            Method m = findNoArgMethod(cls, name);
            if (m == null) continue;
            try {
                Object r = m.invoke(recipeManager);
                if (r instanceof Iterable) return (Iterable<Object>) r;
                if (r instanceof Map) return ((Map<Object, Object>) r).values();
                if (r instanceof Collection) return (Collection<Object>) r;
            } catch (Throwable ignore) {}
        }
        return List.of();
    }

    /** Extract the result ItemStack from a Recipe object without knowing the exact
     *  API. Tries a handful of getter names with and without a registry-lookup arg. */
    private static ItemStack recipeResult(Object recipeEntry, World world) {
        Object recipe = unwrapRecipe(recipeEntry);
        if (recipe == null) return null;
        Object regMgr = world.getRegistryManager();
        Class<?> cls = recipe.getClass();
        for (String name : new String[]{"getResult", "result", "getResultStack",
                                        "getOutput", "output", "getResultItem"}) {
            // Try no-arg
            Method m = findNoArgMethod(cls, name);
            if (m != null) {
                try {
                    Object r = m.invoke(recipe);
                    if (r instanceof ItemStack) return (ItemStack) r;
                } catch (Throwable ignore) {}
            }
            // Try single-arg variants
            for (Method candidate : cls.getMethods()) {
                if (!candidate.getName().equals(name)) continue;
                if (candidate.getParameterCount() != 1) continue;
                Class<?> paramType = candidate.getParameterTypes()[0];
                if (!paramType.isInstance(regMgr)) continue;
                try {
                    Object r = candidate.invoke(recipe, regMgr);
                    if (r instanceof ItemStack) return (ItemStack) r;
                } catch (Throwable ignore) {}
            }
        }
        return null;
    }

    /** Given a RecipeEntry, return the underlying Recipe (via .value() or similar). */
    private static Object unwrapRecipe(Object entry) {
        if (entry == null) return null;
        Class<?> cls = entry.getClass();
        for (String name : new String[]{"value", "recipe", "getRecipe"}) {
            Method m = findNoArgMethod(cls, name);
            if (m == null) continue;
            try { return m.invoke(entry); } catch (Throwable ignore) {}
        }
        return entry;  // maybe it IS the recipe
    }

    /** Get NetworkRecipeId (or Identifier — API-dependent) for a recipe entry.
     *  This is what clickRecipe expects in 1.21.5+. */
    private static Object recipeNetworkId(Object entry) {
        if (entry == null) return null;
        for (String name : new String[]{"id", "networkId", "getId", "getNetworkId"}) {
            Method m = findNoArgMethod(entry.getClass(), name);
            if (m == null) continue;
            try { return m.invoke(entry); } catch (Throwable ignore) {}
        }
        return null;
    }

    /** Try every clickRecipe signature we've seen. Returns true if we invoked one. */
    private static boolean tryClickRecipe(ClientPlayerInteractionManager im,
                                          int syncId, Object recipeEntry, boolean craftAll) {
        Object networkId = recipeNetworkId(recipeEntry);
        for (Method m : im.getClass().getMethods()) {
            if (!"clickRecipe".equals(m.getName())) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p.length != 3) continue;
            if (!(p[0] == int.class || p[0] == Integer.class)) continue;
            if (!(p[2] == boolean.class || p[2] == Boolean.class)) continue;
            // Try passing NetworkRecipeId (new API) or the RecipeEntry directly (old API).
            Object second = null;
            if (networkId != null && p[1].isInstance(networkId)) second = networkId;
            else if (p[1].isInstance(recipeEntry)) second = recipeEntry;
            if (second == null) continue;
            try {
                m.invoke(im, syncId, second, craftAll);
                return true;
            } catch (Throwable t) {
                OdysseusBridge.LOG.warn("[odysseus] clickRecipe invoke failed: {}", t.toString());
            }
        }
        return false;
    }

    private static Method findNoArgMethod(Class<?> cls, String name) {
        for (Method m : cls.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == 0) return m;
        }
        return null;
    }

    /** Find a recipe entry whose output matches the target item. */
    private static Optional<Object> findRecipeEntryForOutput(World world, Item output) {
        Object rm = world.getRecipeManager();
        List<Object> entries = new ArrayList<>();
        for (Object e : allRecipeEntries(rm)) entries.add(e);
        for (Object entry : entries) {
            try {
                ItemStack result = recipeResult(entry, world);
                if (result != null && !result.isEmpty() && result.getItem() == output) {
                    return Optional.of(entry);
                }
            } catch (Throwable ignore) {}
        }
        return Optional.empty();
    }

    // ── Misc helpers ──────────────────────────────────────────────────────

    private static String normalizeId(String s) {
        s = s.trim().toLowerCase();
        if (!s.contains(":")) s = "minecraft:" + s;
        return s;
    }

    private static Item itemFromId(String id) {
        try {
            Identifier ident = Identifier.tryParse(id);
            if (ident == null) return null;
            Item item = Registries.ITEM.get(ident);
            return (item == null || item == net.minecraft.item.Items.AIR) ? null : item;
        } catch (Throwable t) {
            return null;
        }
    }

    private static int countInInventory(ClientPlayerEntity player, Item item) {
        if (item == null) return 0;
        PlayerInventory inv = player.getInventory();
        int total = 0;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (!s.isEmpty() && s.getItem() == item) total += s.getCount();
        }
        return total;
    }

    private static int parseIntOr(String s, int fallback) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return fallback; }
    }

    private static void emit(String event, String text) {
        OdysseusBridge.LOG.info("[odysseus] {} : {}", event, text);
        BridgeClient c = OdysseusBridge.getClient();
        if (c != null) c.sendEvent(event, text);
    }
}

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
import java.util.List;
import java.util.Optional;

public class OdysseusDispatcher {
    private static volatile Task currentTask;

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(OdysseusDispatcher::onTick);
    }

    private static void onTick(MinecraftClient client) {
        Task task = currentTask;
        if (task == null) return;
        try {
            if (task.tick(client)) currentTask = null;
        } catch (Throwable t) {
            OdysseusBridge.LOG.warn("[odysseus] task crashed: {}", t.toString());
            emit("error", task.name() + " crashed: " + t.getMessage());
            currentTask = null;
        }
    }

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
                if (parts.length < 2) { emit("craft_failed", "Usage: !craft <item> [count]"); return; }
                String item = parts[1];
                int count = parts.length >= 3 ? parseIntOr(parts[2], 1) : 1;
                currentTask = new CraftTask(item, count);
                return;
            case "use":
                currentTask = new UseHeldTask();
                return;
            case "debug_recipes":
                currentTask = new DebugRecipesTask();
                return;
            default:
                emit("unknown_command", "Unknown Odysseus command: !" + verb);
        }
    }

    interface Task {
        boolean tick(MinecraftClient client);
        String name();
    }

    // ── Debug: dump ClientRecipeBook.method_1393()[0] deeply ──────────────

    private static class DebugRecipesTask implements Task {
        @Override public String name() { return "debug recipes"; }
        @Override
        public boolean tick(MinecraftClient client) {
            ClientPlayerEntity player = client.player;
            if (player == null) { emit("error", "no player"); return true; }
            Object book;
            try { book = player.getRecipeBook(); } catch (Throwable t) { book = null; }
            if (book == null) { emit("error", "no recipe book"); return true; }
            OdysseusBridge.LOG.info("[odysseus-debug] book class: {}", book.getClass().getName());
            List<?> list = findFirstListReturn(book);
            if (list == null) {
                OdysseusBridge.LOG.info("[odysseus-debug] no List-returning no-arg method found on book");
                emit("use_ok", "No List method on book");
                return true;
            }
            OdysseusBridge.LOG.info("[odysseus-debug] book list size: {}", list.size());
            if (list.isEmpty()) { emit("use_ok", "Book list empty"); return true; }
            Object first = list.get(0);
            OdysseusBridge.LOG.info("[odysseus-debug] entry[0] class: {}", first.getClass().getName());
            dumpNoArgMethods(first, "entry[0]");
            // Recurse into every no-arg method result to see what's inside
            for (Method m : first.getClass().getMethods()) {
                if (m.getParameterCount() != 0) continue;
                if (m.getDeclaringClass() == Object.class) continue;
                try {
                    Object sub = m.invoke(first);
                    if (sub == null) continue;
                    OdysseusBridge.LOG.info("[odysseus-debug]   entry[0].{}() = {} class={}",
                        m.getName(),
                        sub.toString().length() > 80 ? sub.toString().substring(0, 80) + "…" : sub.toString(),
                        sub.getClass().getName());
                    if (m.getReturnType().isPrimitive()
                        || sub instanceof String || sub instanceof Number || sub instanceof Boolean
                        || sub.getClass().isArray()) continue;
                    dumpNoArgMethods(sub, "  " + m.getName() + "()→");
                } catch (Throwable t) {
                    OdysseusBridge.LOG.info("[odysseus-debug]   entry[0].{}() threw: {}", m.getName(), t.toString());
                }
            }
            emit("use_ok", "Deep dump complete — grep MC log for [odysseus-debug]");
            return true;
        }
    }

    private static void dumpNoArgMethods(Object obj, String label) {
        if (obj == null) return;
        for (Method m : obj.getClass().getMethods()) {
            if (m.getParameterCount() != 0) continue;
            if (m.getDeclaringClass() == Object.class) continue;
            OdysseusBridge.LOG.info("[odysseus-debug] {}.{}() → {}",
                label, m.getName(), m.getReturnType().getSimpleName());
        }
    }

    // ── Craft task ────────────────────────────────────────────────────────

    private static class CraftTask implements Task {
        private final String targetItemId;
        private final int targetCount;

        private State state = State.FIND_TABLE;
        private int ticksInState = 0;
        private int initialInventoryCount = -1;
        private int lastKnownCount = -1;
        private Object recipeEntry;

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
                case FIND_TABLE:   return doFindTable(client, player, world);
                case WAIT_SCREEN:  return doWaitScreen(client, player);
                case CLICK_RECIPE: return doClickRecipe(client, player, world);
                case WAIT_FILL:    return doWaitFill(client, player);
                case TAKE_OUTPUT:  return doTakeOutput(client, player);
                case WAIT_TAKE:    return doWaitTake(client, player);
                case CHECK_COUNT:  return doCheckCount(client, player);
                case CLOSE:        return doClose(client, player);
                case DONE:         return true;
            }
            return true;
        }

        private void enter(State next) { this.state = next; this.ticksInState = 0; }

        private boolean doFindTable(MinecraftClient client, ClientPlayerEntity player, World world) {
            if (player.currentScreenHandler instanceof CraftingScreenHandler) {
                enter(State.CLICK_RECIPE); return false;
            }
            BlockPos here = player.getBlockPos();
            BlockPos found = null;
            outer:
            for (int dy = -1; dy <= 2; dy++)
                for (int dx = -3; dx <= 3; dx++)
                    for (int dz = -3; dz <= 3; dz++) {
                        BlockPos p = here.add(dx, dy, dz);
                        if (world.getBlockState(p).getBlock() == Blocks.CRAFTING_TABLE) { found = p; break outer; }
                    }
            if (found == null) {
                emit("craft_failed", "No crafting_table within 3 blocks — walk to one first (#goto crafting_table).");
                enter(State.DONE); return true;
            }
            Vec3d hitVec = Vec3d.ofCenter(found);
            BlockHitResult hit = new BlockHitResult(hitVec, Direction.UP, found, false);
            ClientPlayerInteractionManager im = client.interactionManager;
            if (im != null) im.interactBlock(player, Hand.MAIN_HAND, hit);
            enter(State.WAIT_SCREEN);
            return false;
        }

        private boolean doWaitScreen(MinecraftClient client, ClientPlayerEntity player) {
            if (player.currentScreenHandler instanceof CraftingScreenHandler) {
                enter(State.CLICK_RECIPE); return false;
            }
            if (ticksInState > 40) {
                emit("craft_failed", "Timed out waiting for crafting screen to open.");
                enter(State.CLOSE); return false;
            }
            return false;
        }

        private boolean doClickRecipe(MinecraftClient client, ClientPlayerEntity player, World world) {
            if (recipeEntry == null) {
                Item target = itemFromId(targetItemId);
                if (target == null) {
                    emit("craft_failed", "Unknown item id: " + targetItemId);
                    enter(State.CLOSE); return false;
                }
                Optional<Object> pick = findRecipeEntryForOutput(player, target);
                if (pick.isEmpty()) {
                    emit("craft_failed", "No recipe entry matched " + targetItemId
                        + " — try !debug_recipes and paste the log.");
                    enter(State.CLOSE); return false;
                }
                recipeEntry = pick.get();
                OdysseusBridge.LOG.info("[odysseus] using recipe entry: {}", recipeEntry);
                initialInventoryCount = countInInventory(player, target);
                lastKnownCount = initialInventoryCount;
            }
            ScreenHandler h = player.currentScreenHandler;
            ClientPlayerInteractionManager im = client.interactionManager;
            if (im == null) { emit("craft_failed", "No interaction manager."); enter(State.CLOSE); return false; }
            boolean ok = tryClickRecipe(im, h.syncId, recipeEntry, true);
            if (!ok) {
                emit("craft_failed", "clickRecipe reflection failed — see [odysseus] log.");
                enter(State.CLOSE); return false;
            }
            enter(State.WAIT_FILL);
            return false;
        }

        private boolean doWaitFill(MinecraftClient client, ClientPlayerEntity player) {
            if (ticksInState >= 2) enter(State.TAKE_OUTPUT);
            return false;
        }

        private boolean doTakeOutput(MinecraftClient client, ClientPlayerEntity player) {
            ScreenHandler h = player.currentScreenHandler;
            if (!(h instanceof CraftingScreenHandler)) {
                emit("craft_failed", "Crafting screen closed unexpectedly.");
                enter(State.DONE); return true;
            }
            ItemStack outputStack = h.slots.get(0).getStack();
            if (outputStack.isEmpty()) {
                int haveNow = countInInventory(player, itemFromId(targetItemId));
                int delta = haveNow - initialInventoryCount;
                if (delta > 0) emit("craft_ok", "Crafted " + delta + " " + targetItemId + " (inventory now: " + haveNow + ")");
                else emit("craft_failed", "Grid empty — likely missing ingredients for " + targetItemId);
                enter(State.CLOSE); return false;
            }
            ClientPlayerInteractionManager im = client.interactionManager;
            if (im != null) im.clickSlot(h.syncId, 0, 0, SlotActionType.QUICK_MOVE, player);
            enter(State.WAIT_TAKE);
            return false;
        }

        private boolean doWaitTake(MinecraftClient client, ClientPlayerEntity player) {
            if (ticksInState >= 2) enter(State.CHECK_COUNT);
            return false;
        }

        private boolean doCheckCount(MinecraftClient client, ClientPlayerEntity player) {
            Item target = itemFromId(targetItemId);
            int haveNow = countInInventory(player, target);
            int crafted = haveNow - initialInventoryCount;
            if (crafted >= targetCount) {
                emit("craft_ok", "Crafted " + crafted + " " + targetItemId + " (target " + targetCount + ", inventory " + haveNow + ")");
                enter(State.CLOSE); return false;
            }
            if (haveNow == lastKnownCount) {
                emit("craft_failed", "Stopped at " + crafted + "/" + targetCount + " — no more ingredients for " + targetItemId);
                enter(State.CLOSE); return false;
            }
            lastKnownCount = haveNow;
            enter(State.TAKE_OUTPUT);
            return false;
        }

        private boolean doClose(MinecraftClient client, ClientPlayerEntity player) {
            client.execute(() -> {
                if (client.player != null && client.player.currentScreenHandler != client.player.playerScreenHandler) {
                    try { client.player.closeHandledScreen(); } catch (Throwable ignore) {}
                }
                client.setScreen(null);
            });
            enter(State.DONE);
            return true;
        }
    }

    // ── Use held item ─────────────────────────────────────────────────────

    private static class UseHeldTask implements Task {
        @Override public String name() { return "use held item"; }
        @Override
        public boolean tick(MinecraftClient client) {
            ClientPlayerEntity player = client.player;
            if (player == null || client.interactionManager == null) {
                emit("use_failed", "No player/interaction manager."); return true;
            }
            client.interactionManager.interactItem(player, Hand.MAIN_HAND);
            emit("use_ok", "Used held item.");
            return true;
        }
    }

    // ── Recipe lookup — RecipeBook only (client RecipeManager is empty in 1.21.6+) ──

    private static Optional<Object> findRecipeEntryForOutput(ClientPlayerEntity player, Item output) {
        Object book;
        try { book = player.getRecipeBook(); } catch (Throwable t) { return Optional.empty(); }
        if (book == null) return Optional.empty();
        List<?> entries = findFirstListReturn(book);
        if (entries == null || entries.isEmpty()) {
            OdysseusBridge.LOG.info("[odysseus] recipe book returned empty/no-list");
            return Optional.empty();
        }
        OdysseusBridge.LOG.info("[odysseus] scanning {} recipe entries for {}", entries.size(), output);
        for (Object entry : entries) {
            ItemStack result = extractResultStack(entry);
            if (result != null && !result.isEmpty() && result.getItem() == output) {
                return Optional.of(entry);
            }
        }
        return Optional.empty();
    }

    /** Find the first no-arg method on `obj` returning a List. Cache is unnecessary — one call per craft. */
    private static List<?> findFirstListReturn(Object obj) {
        if (obj == null) return null;
        for (Method m : obj.getClass().getMethods()) {
            if (m.getParameterCount() != 0) continue;
            if (m.getReturnType() != List.class) continue;
            if (m.getDeclaringClass() == Object.class) continue;
            try {
                Object r = m.invoke(obj);
                if (r instanceof List<?>) return (List<?>) r;
            } catch (Throwable ignore) {}
        }
        return null;
    }

    /** Walk any RecipeDisplayEntry-shaped object looking for an ItemStack result.
     *  Uses reflection since the RecipeDisplay hierarchy is deeply nested and
     *  changes every MC version. Two levels deep is enough for all known layouts. */
    private static ItemStack extractResultStack(Object entry) {
        if (entry == null) return null;
        // Level 0: entry itself
        ItemStack s = firstItemStack(entry);
        if (s != null) return s;
        // Level 1: any child object returned by no-arg methods (display, result, output, etc.)
        for (Method m : entry.getClass().getMethods()) {
            if (m.getParameterCount() != 0) continue;
            if (m.getDeclaringClass() == Object.class) continue;
            Class<?> ret = m.getReturnType();
            if (ret.isPrimitive() || ret == String.class || ret == Class.class) continue;
            try {
                Object child = m.invoke(entry);
                if (child == null) continue;
                ItemStack cs = firstItemStack(child);
                if (cs != null) return cs;
                // Level 2: dig one more level (display.result.stacks, etc.)
                for (Method m2 : child.getClass().getMethods()) {
                    if (m2.getParameterCount() != 0) continue;
                    if (m2.getDeclaringClass() == Object.class) continue;
                    Class<?> ret2 = m2.getReturnType();
                    if (ret2.isPrimitive() || ret2 == String.class || ret2 == Class.class) continue;
                    try {
                        Object grand = m2.invoke(child);
                        if (grand == null) continue;
                        ItemStack gs = firstItemStack(grand);
                        if (gs != null) return gs;
                    } catch (Throwable ignore) {}
                }
            } catch (Throwable ignore) {}
        }
        return null;
    }

    /** Return the first non-empty ItemStack accessible via any no-arg method on obj,
     *  including List<ItemStack> unwrapping. */
    private static ItemStack firstItemStack(Object obj) {
        if (obj == null) return null;
        if (obj instanceof ItemStack) {
            ItemStack s = (ItemStack) obj;
            return s.isEmpty() ? null : s;
        }
        for (Method m : obj.getClass().getMethods()) {
            if (m.getParameterCount() != 0) continue;
            if (m.getDeclaringClass() == Object.class) continue;
            Class<?> ret = m.getReturnType();
            if (ret == ItemStack.class) {
                try {
                    Object r = m.invoke(obj);
                    if (r instanceof ItemStack && !((ItemStack) r).isEmpty()) return (ItemStack) r;
                } catch (Throwable ignore) {}
            } else if (ret == List.class) {
                try {
                    Object r = m.invoke(obj);
                    if (r instanceof List<?>) {
                        for (Object o : (List<?>) r) {
                            if (o instanceof ItemStack && !((ItemStack) o).isEmpty()) return (ItemStack) o;
                        }
                    }
                } catch (Throwable ignore) {}
            }
        }
        return null;
    }

    /** Try each clickRecipe overload with each of the entry's no-arg return values
     *  as the second argument. Whichever combo has matching types wins. */
    private static boolean tryClickRecipe(ClientPlayerInteractionManager im, int syncId, Object recipeEntry, boolean craftAll) {
        // Gather all candidate "second arg" values from the entry — its id, itself, or nested id-like objects.
        java.util.List<Object> candidates = new java.util.ArrayList<>();
        candidates.add(recipeEntry);
        for (Method m : recipeEntry.getClass().getMethods()) {
            if (m.getParameterCount() != 0) continue;
            if (m.getDeclaringClass() == Object.class) continue;
            Class<?> ret = m.getReturnType();
            if (ret.isPrimitive() || ret == String.class || ret == Class.class || ret == List.class) continue;
            try {
                Object v = m.invoke(recipeEntry);
                if (v != null) candidates.add(v);
            } catch (Throwable ignore) {}
        }
        for (Method m : im.getClass().getMethods()) {
            if (!"clickRecipe".equals(m.getName())) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p.length != 3) continue;
            if (!(p[0] == int.class || p[0] == Integer.class)) continue;
            if (!(p[2] == boolean.class || p[2] == Boolean.class)) continue;
            for (Object cand : candidates) {
                if (!p[1].isInstance(cand)) continue;
                try {
                    m.invoke(im, syncId, cand, craftAll);
                    OdysseusBridge.LOG.info("[odysseus] clickRecipe hit with second-arg class {}", cand.getClass().getName());
                    return true;
                } catch (Throwable t) {
                    OdysseusBridge.LOG.warn("[odysseus] clickRecipe throw: {}", t.toString());
                }
            }
        }
        return false;
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
        } catch (Throwable t) { return null; }
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

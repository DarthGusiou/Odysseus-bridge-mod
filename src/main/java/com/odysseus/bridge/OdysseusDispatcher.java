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

public class OdysseusDispatcher {
    private static volatile Task currentTask;
    private static boolean debugDumped = false;

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

    // ── Debug task: dump methods on the recipe classes so we know what to call ──

    private static class DebugRecipesTask implements Task {
        @Override public String name() { return "debug recipes"; }
        @Override
        public boolean tick(MinecraftClient client) {
            ClientPlayerEntity player = client.player;
            World world = client.world;
            if (player == null || world == null) {
                emit("error", "no player/world");
                return true;
            }
            Object rm = world.getRecipeManager();
            dumpClassMethods(rm, "RecipeManager");
            try {
                Object book = player.getRecipeBook();
                if (book != null) dumpClassMethods(book, "ClientRecipeBook");
            } catch (Throwable t) {
                OdysseusBridge.LOG.info("[odysseus-debug] no recipe book: {}", t.toString());
            }
            emit("use_ok", "Recipe classes dumped to MC log — grep for [odysseus-debug]");
            return true;
        }
    }

    private static void dumpClassMethods(Object obj, String label) {
        if (obj == null) return;
        Class<?> cls = obj.getClass();
        OdysseusBridge.LOG.info("[odysseus-debug] {} class={} methods:", label, cls.getName());
        for (Method m : cls.getMethods()) {
            StringBuilder sb = new StringBuilder("  ");
            sb.append(m.getName()).append("(");
            Class<?>[] p = m.getParameterTypes();
            for (int i = 0; i < p.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(p[i].getSimpleName());
            }
            sb.append(") → ").append(m.getReturnType().getSimpleName());
            OdysseusBridge.LOG.info("[odysseus-debug] {}", sb.toString());
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
                // First-time debug: dump recipe class methods so we can adapt.
                if (!debugDumped) {
                    debugDumped = true;
                    dumpClassMethods(world.getRecipeManager(), "RecipeManager");
                    try {
                        Object book = player.getRecipeBook();
                        if (book != null) dumpClassMethods(book, "ClientRecipeBook");
                    } catch (Throwable ignore) {}
                }
                Item target = itemFromId(targetItemId);
                if (target == null) {
                    emit("craft_failed", "Unknown item id: " + targetItemId);
                    enter(State.CLOSE); return false;
                }
                Optional<Object> pick = findRecipeEntryForOutput(player, world, target);
                if (pick.isEmpty()) {
                    emit("craft_failed", "No recipe found that produces " + targetItemId
                        + " — run !debug_recipes and paste the log so I can fix the reflection.");
                    enter(State.CLOSE); return false;
                }
                recipeEntry = pick.get();
                initialInventoryCount = countInInventory(player, target);
                lastKnownCount = initialInventoryCount;
            }
            ScreenHandler h = player.currentScreenHandler;
            ClientPlayerInteractionManager im = client.interactionManager;
            if (im == null) {
                emit("craft_failed", "No interaction manager."); enter(State.CLOSE); return false;
            }
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

        /** Close in a way that doesn't fire the pause-menu code path. */
        private boolean doClose(MinecraftClient client, ClientPlayerEntity player) {
            // Directly clear the client screen — avoids the ESC-key-simulation path
            // that closeHandledScreen() seems to hit in 1.21.8 which triggers pause menu.
            client.execute(() -> {
                if (client.player != null && client.player.currentScreenHandler != client.player.playerScreenHandler) {
                    // Send the close packet to the server ourselves...
                    try {
                        client.player.closeHandledScreen();
                    } catch (Throwable ignore) {}
                }
                // ...then force the client screen away so no game-menu code runs.
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
                emit("use_failed", "No player/interaction manager.");
                return true;
            }
            client.interactionManager.interactItem(player, Hand.MAIN_HAND);
            emit("use_ok", "Used held item.");
            return true;
        }
    }

    // ── Recipe reflection — tries RecipeManager AND ClientRecipeBook ──────

    /** Try RecipeManager methods, then ClientRecipeBook methods, then dig into
     *  ClientRecipeBook's RecipeResultCollection groups. Returns first match. */
    private static Optional<Object> findRecipeEntryForOutput(ClientPlayerEntity player, World world, Item output) {
        // Path A: RecipeManager direct iteration (works on older API).
        for (Object entry : allEntriesFromRecipeManager(world.getRecipeManager())) {
            try {
                ItemStack result = recipeResult(entry, world);
                if (result != null && !result.isEmpty() && result.getItem() == output) {
                    OdysseusBridge.LOG.info("[odysseus] found via RecipeManager: {}", entry);
                    return Optional.of(entry);
                }
            } catch (Throwable ignore) {}
        }
        // Path B: ClientRecipeBook — iterate result collections and their entries.
        try {
            Object book = player.getRecipeBook();
            if (book != null) {
                for (Object entry : allEntriesFromRecipeBook(book)) {
                    try {
                        ItemStack result = recipeResult(entry, world);
                        if (result != null && !result.isEmpty() && result.getItem() == output) {
                            OdysseusBridge.LOG.info("[odysseus] found via RecipeBook: {}", entry);
                            return Optional.of(entry);
                        }
                    } catch (Throwable ignore) {}
                }
            }
        } catch (Throwable t) {
            OdysseusBridge.LOG.warn("[odysseus] recipe book scan failed: {}", t.toString());
        }
        OdysseusBridge.LOG.info("[odysseus] no recipe found for {} — book scan returned nothing", output);
        return Optional.empty();
    }

    @SuppressWarnings("unchecked")
    private static Iterable<Object> allEntriesFromRecipeManager(Object rm) {
        if (rm == null) return List.of();
        Class<?> cls = rm.getClass();
        for (String name : new String[]{"values", "recipes", "getRecipes", "getAllRecipes"}) {
            Method m = findNoArgMethod(cls, name);
            if (m == null) continue;
            try {
                Object r = m.invoke(rm);
                if (r instanceof Iterable) return (Iterable<Object>) r;
                if (r instanceof Map) return ((Map<Object, Object>) r).values();
                if (r instanceof Collection) return (Collection<Object>) r;
            } catch (Throwable ignore) {}
        }
        return List.of();
    }

    /** Iterate ClientRecipeBook.getOrderedResults() (or equivalent) and flatten
     *  every RecipeDisplayEntry within. */
    @SuppressWarnings("unchecked")
    private static Iterable<Object> allEntriesFromRecipeBook(Object book) {
        List<Object> out = new ArrayList<>();
        if (book == null) return out;
        Class<?> cls = book.getClass();
        // Find any method returning a List of collections.
        Object collections = null;
        for (String name : new String[]{"getOrderedResults", "getResults", "results",
                                        "getKeyedResults", "getAllCollections"}) {
            Method m = findNoArgMethod(cls, name);
            if (m == null) continue;
            try {
                Object r = m.invoke(book);
                if (r instanceof Iterable) { collections = r; break; }
                if (r instanceof Map) { collections = ((Map<Object, Object>) r).values(); break; }
            } catch (Throwable ignore) {}
        }
        if (collections == null) return out;
        for (Object collection : (Iterable<Object>) collections) {
            Class<?> ccls = collection.getClass();
            for (String innerName : new String[]{"getAllResults", "getResults", "results"}) {
                Method innerM = null;
                // getResults may take a boolean; findNoArgMethod won't find it. Also try 1-arg.
                for (Method cand : ccls.getMethods()) {
                    if (!cand.getName().equals(innerName)) continue;
                    if (cand.getParameterCount() == 0) { innerM = cand; break; }
                }
                if (innerM != null) {
                    try {
                        Object entries = innerM.invoke(collection);
                        if (entries instanceof Iterable) {
                            for (Object e : (Iterable<Object>) entries) out.add(e);
                        }
                    } catch (Throwable ignore) {}
                }
            }
        }
        return out;
    }

    private static ItemStack recipeResult(Object entry, World world) {
        if (entry == null) return null;
        Object recipe = unwrapRecipe(entry);
        if (recipe == null) return null;
        Object regMgr = world.getRegistryManager();
        Class<?> cls = recipe.getClass();
        for (String name : new String[]{"getResult", "result", "getResultStack",
                                        "getOutput", "output", "getResultItem"}) {
            Method noArg = findNoArgMethod(cls, name);
            if (noArg != null) {
                try {
                    Object r = noArg.invoke(recipe);
                    if (r instanceof ItemStack) return (ItemStack) r;
                } catch (Throwable ignore) {}
            }
            for (Method cand : cls.getMethods()) {
                if (!cand.getName().equals(name)) continue;
                if (cand.getParameterCount() != 1) continue;
                if (!cand.getParameterTypes()[0].isInstance(regMgr)) continue;
                try {
                    Object r = cand.invoke(recipe, regMgr);
                    if (r instanceof ItemStack) return (ItemStack) r;
                } catch (Throwable ignore) {}
            }
        }
        return null;
    }

    private static Object unwrapRecipe(Object entry) {
        if (entry == null) return null;
        for (String name : new String[]{"value", "recipe", "getRecipe", "display"}) {
            Method m = findNoArgMethod(entry.getClass(), name);
            if (m == null) continue;
            try { Object r = m.invoke(entry); if (r != null) return r; } catch (Throwable ignore) {}
        }
        return entry;
    }

    private static Object recipeNetworkId(Object entry) {
        if (entry == null) return null;
        for (String name : new String[]{"id", "networkId", "getId", "getNetworkId"}) {
            Method m = findNoArgMethod(entry.getClass(), name);
            if (m == null) continue;
            try { return m.invoke(entry); } catch (Throwable ignore) {}
        }
        return null;
    }

    private static boolean tryClickRecipe(ClientPlayerInteractionManager im, int syncId, Object recipeEntry, boolean craftAll) {
        Object networkId = recipeNetworkId(recipeEntry);
        for (Method m : im.getClass().getMethods()) {
            if (!"clickRecipe".equals(m.getName())) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p.length != 3) continue;
            if (!(p[0] == int.class || p[0] == Integer.class)) continue;
            if (!(p[2] == boolean.class || p[2] == Boolean.class)) continue;
            Object second = null;
            if (networkId != null && p[1].isInstance(networkId)) second = networkId;
            else if (p[1].isInstance(recipeEntry)) second = recipeEntry;
            if (second == null) continue;
            try { m.invoke(im, syncId, second, craftAll); return true; }
            catch (Throwable t) { OdysseusBridge.LOG.warn("[odysseus] clickRecipe fail: {}", t.toString()); }
        }
        return false;
    }

    private static Method findNoArgMethod(Class<?> cls, String name) {
        for (Method m : cls.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == 0) return m;
        }
        return null;
    }

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

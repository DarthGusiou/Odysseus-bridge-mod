package com.odysseus.bridge;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.GameMenuScreen;
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

import java.util.HashMap;
import java.util.Map;

public class OdysseusDispatcher {
    private static volatile Task currentTask;
    private static String lastScreenClass = "<init>";
    private static int suppressPauseMenuTicks = 0;

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(OdysseusDispatcher::onTick);
        ClientTickEvents.END_CLIENT_TICK.register(OdysseusDispatcher::onScreenMonitorTick);
        ClientTickEvents.END_CLIENT_TICK.register(OdysseusDispatcher::onPauseMenuGuardTick);
        // NOTE: Baritone state polling was REMOVED in v0.1.14. Doing reflection
        // calls into Baritone's PathingControlManager on the render thread every
        // tick caused deadlocks against Baritone's internal locks held by its
        // pathfinder worker threads — MC would hang with the rainbow cursor on
        // macOS. Arrival detection falls back to Baritone's chat messages
        // (enable via `#set notificationOnPathComplete true` in-game), which
        // Odysseus already matches with its expanded terminal-text patterns.
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

    private static void onScreenMonitorTick(MinecraftClient client) {
        String cur = client.currentScreen == null ? "null" : client.currentScreen.getClass().getName();
        if (!cur.equals(lastScreenClass)) {
            OdysseusBridge.LOG.info("[odysseus-screen] currentScreen: {} → {}", lastScreenClass, cur);
            lastScreenClass = cur;
        }
    }

    private static void onPauseMenuGuardTick(MinecraftClient client) {
        if (suppressPauseMenuTicks <= 0) return;
        suppressPauseMenuTicks--;
        if (client.currentScreen instanceof GameMenuScreen) {
            OdysseusBridge.LOG.info("[odysseus] auto-dismissing GameMenuScreen after craft close");
            client.setScreen(null);
            suppressPauseMenuTicks = 0;
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
                if (count < 1 || count > 999) {
                    emit("craft_failed", "Invalid count " + count + " (must be 1-999).");
                    return;
                }
                currentTask = new CraftTask(item, count);
                return;
            case "use":
                currentTask = new UseHeldTask();
                return;
            case "recipes":
                StringBuilder sb = new StringBuilder("Known recipes: ");
                for (String id : RECIPES.keySet()) sb.append(id).append(", ");
                emit("use_ok", sb.toString());
                return;
            default:
                emit("unknown_command", "Unknown Odysseus command: !" + verb);
        }
    }

    interface Task {
        boolean tick(MinecraftClient client);
        String name();
    }

    private static class RecipeSpec {
        final int outputCount;
        final int[] slots;
        final String[] items;
        RecipeSpec(int outputCount, int[] slots, String[] items) {
            this.outputCount = outputCount; this.slots = slots; this.items = items;
        }
    }

    private static final Map<String, RecipeSpec> RECIPES = new HashMap<>();
    static {
        String[][] plankMap = {
            {"oak_planks", "oak_log"}, {"birch_planks", "birch_log"},
            {"spruce_planks", "spruce_log"}, {"jungle_planks", "jungle_log"},
            {"acacia_planks", "acacia_log"}, {"dark_oak_planks", "dark_oak_log"},
            {"mangrove_planks", "mangrove_log"}, {"cherry_planks", "cherry_log"},
            {"pale_oak_planks", "pale_oak_log"},
        };
        for (String[] pm : plankMap) {
            RECIPES.put(pm[0], new RecipeSpec(4, new int[]{1}, new String[]{pm[1]}));
        }
        RECIPES.put("stick", new RecipeSpec(4, new int[]{1, 4}, new String[]{"oak_planks", "oak_planks"}));
        RECIPES.put("crafting_table", new RecipeSpec(1, new int[]{1, 2, 4, 5},
            new String[]{"oak_planks", "oak_planks", "oak_planks", "oak_planks"}));
        RECIPES.put("torch", new RecipeSpec(4, new int[]{1, 4}, new String[]{"coal", "stick"}));
        RECIPES.put("furnace", new RecipeSpec(1, new int[]{1, 2, 3, 4, 6, 7, 8, 9},
            new String[]{"cobblestone","cobblestone","cobblestone","cobblestone",
                         "cobblestone","cobblestone","cobblestone","cobblestone"}));
        RECIPES.put("chest", new RecipeSpec(1, new int[]{1, 2, 3, 4, 6, 7, 8, 9},
            new String[]{"oak_planks","oak_planks","oak_planks","oak_planks",
                         "oak_planks","oak_planks","oak_planks","oak_planks"}));
        RECIPES.put("wooden_pickaxe", new RecipeSpec(1, new int[]{1, 2, 3, 5, 8},
            new String[]{"oak_planks","oak_planks","oak_planks","stick","stick"}));
        RECIPES.put("wooden_axe", new RecipeSpec(1, new int[]{1, 2, 5, 4, 7},
            new String[]{"oak_planks","oak_planks","oak_planks","stick","stick"}));
        RECIPES.put("wooden_shovel", new RecipeSpec(1, new int[]{2, 5, 8},
            new String[]{"oak_planks","stick","stick"}));
        RECIPES.put("wooden_sword", new RecipeSpec(1, new int[]{2, 5, 8},
            new String[]{"oak_planks","oak_planks","stick"}));
        RECIPES.put("stone_pickaxe", new RecipeSpec(1, new int[]{1, 2, 3, 5, 8},
            new String[]{"cobblestone","cobblestone","cobblestone","stick","stick"}));
        RECIPES.put("stone_axe", new RecipeSpec(1, new int[]{1, 2, 5, 4, 7},
            new String[]{"cobblestone","cobblestone","cobblestone","stick","stick"}));
        RECIPES.put("stone_shovel", new RecipeSpec(1, new int[]{2, 5, 8},
            new String[]{"cobblestone","stick","stick"}));
        RECIPES.put("stone_sword", new RecipeSpec(1, new int[]{2, 5, 8},
            new String[]{"cobblestone","cobblestone","stick"}));
    }

    private static class CraftTask implements Task {
        private final String targetItemId;
        private final String targetItemKey;
        private final int targetCount;
        private final RecipeSpec recipe;

        private State state = State.FIND_TABLE;
        private int ticksInState = 0;
        private int initialInventoryCount = -1;
        private int lastKnownCount = -1;
        private int ingredientIdx = 0;
        private int subStep = 0;
        private int sourceSlotForCurrent = -1;

        enum State {
            FIND_TABLE, WAIT_SCREEN, PLACE_INGREDIENTS, WAIT_STEP, TAKE_OUTPUT, WAIT_TAKE,
            CHECK_COUNT, CLEAR_GRID, WAIT_CLEAR, CLOSE, DONE
        }

        CraftTask(String itemId, int count) {
            String norm = itemId.trim().toLowerCase();
            if (norm.startsWith("minecraft:")) norm = norm.substring("minecraft:".length());
            this.targetItemKey = norm;
            this.targetItemId = "minecraft:" + norm;
            this.targetCount = Math.max(1, count);
            this.recipe = RECIPES.get(norm);
        }

        @Override public String name() { return "craft " + targetItemKey + " x" + targetCount; }

        @Override
        public boolean tick(MinecraftClient client) {
            ClientPlayerEntity player = client.player;
            World world = client.world;
            if (player == null || world == null) return false;
            ticksInState++;
            switch (state) {
                case FIND_TABLE:        return doFindTable(client, player, world);
                case WAIT_SCREEN:       return doWaitScreen(client, player);
                case PLACE_INGREDIENTS: return doPlaceIngredients(client, player);
                case WAIT_STEP:         return doWaitStep();
                case TAKE_OUTPUT:       return doTakeOutput(client, player);
                case WAIT_TAKE:         return doWaitTake();
                case CHECK_COUNT:       return doCheckCount(client, player);
                case CLEAR_GRID:        return doClearGrid(client, player);
                case WAIT_CLEAR:        return doWaitClear();
                case CLOSE:             return doClose(client, player);
                case DONE:              return true;
            }
            return true;
        }

        private void enter(State next) { this.state = next; this.ticksInState = 0; }

        private boolean doFindTable(MinecraftClient client, ClientPlayerEntity player, World world) {
            if (recipe == null) {
                emit("craft_failed", "No hardcoded recipe for " + targetItemKey + " — run !recipes to see supported ones.");
                enter(State.DONE); return true;
            }
            if (player.currentScreenHandler instanceof CraftingScreenHandler) {
                initialInventoryCount = countInInventory(player, itemFromId(targetItemId));
                lastKnownCount = initialInventoryCount;
                ingredientIdx = 0; subStep = 0; sourceSlotForCurrent = -1;
                enter(State.PLACE_INGREDIENTS); return false;
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
                initialInventoryCount = countInInventory(player, itemFromId(targetItemId));
                lastKnownCount = initialInventoryCount;
                ingredientIdx = 0; subStep = 0; sourceSlotForCurrent = -1;
                enter(State.PLACE_INGREDIENTS); return false;
            }
            if (ticksInState > 40) {
                emit("craft_failed", "Timed out waiting for crafting screen to open.");
                enter(State.CLOSE); return false;
            }
            return false;
        }

        private boolean doPlaceIngredients(MinecraftClient client, ClientPlayerEntity player) {
            ScreenHandler h = player.currentScreenHandler;
            ClientPlayerInteractionManager im = client.interactionManager;
            if (!(h instanceof CraftingScreenHandler) || im == null) {
                emit("craft_failed", "Crafting screen closed unexpectedly.");
                enter(State.CLOSE); return false;
            }
            if (ingredientIdx >= recipe.slots.length) { enter(State.TAKE_OUTPUT); return false; }
            int gridSlot = recipe.slots[ingredientIdx];
            String ingId = recipe.items[ingredientIdx];
            Item ingItem = itemFromId("minecraft:" + ingId);
            if (ingItem == null) {
                emit("craft_failed", "Unknown ingredient in recipe: " + ingId);
                enter(State.CLOSE); return false;
            }
            switch (subStep) {
                case 0: {
                    int sourceSlot = findItemSlotInHandler(h, ingItem, 10);
                    if (sourceSlot < 0) {
                        emit("craft_failed", "Missing " + ingId + " for " + targetItemKey);
                        enter(State.CLEAR_GRID); return false;
                    }
                    sourceSlotForCurrent = sourceSlot;
                    im.clickSlot(h.syncId, sourceSlot, 0, SlotActionType.PICKUP, player);
                    subStep = 1; enter(State.WAIT_STEP); return false;
                }
                case 1: {
                    im.clickSlot(h.syncId, gridSlot, 1, SlotActionType.PICKUP, player);
                    subStep = 2; enter(State.WAIT_STEP); return false;
                }
                case 2: {
                    if (sourceSlotForCurrent >= 0) {
                        im.clickSlot(h.syncId, sourceSlotForCurrent, 0, SlotActionType.PICKUP, player);
                    }
                    sourceSlotForCurrent = -1;
                    subStep = 0; ingredientIdx++;
                    enter(State.WAIT_STEP); return false;
                }
            }
            return false;
        }

        private boolean doWaitStep() {
            if (ticksInState >= 2) enter(State.PLACE_INGREDIENTS);
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
                if (delta > 0) emit("craft_ok", "Crafted " + delta + " " + targetItemKey + " (inventory now: " + haveNow + ")");
                else emit("craft_failed", "Grid empty — ingredient placement failed for " + targetItemKey);
                enter(State.CLEAR_GRID); return false;
            }
            ClientPlayerInteractionManager im = client.interactionManager;
            if (im != null) im.clickSlot(h.syncId, 0, 0, SlotActionType.QUICK_MOVE, player);
            enter(State.WAIT_TAKE); return false;
        }

        private boolean doWaitTake() {
            if (ticksInState >= 2) enter(State.CHECK_COUNT);
            return false;
        }

        private boolean doCheckCount(MinecraftClient client, ClientPlayerEntity player) {
            Item target = itemFromId(targetItemId);
            int haveNow = countInInventory(player, target);

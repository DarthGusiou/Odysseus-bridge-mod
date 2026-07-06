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
import net.minecraft.block.BlockState;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.screen.PlayerScreenHandler;
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

        // Read-only queries: dispatch BEFORE the busy check so the model can
        // introspect state even while a mine/craft is running.
        switch (verb) {
            case "status":
                emit("status_ok", buildStatus());
                return;
            case "inventory":
                emit("inventory_ok", buildInventory());
                return;
        }

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
                // Always prefer the personal 2x2 grid when the recipe fits —
                // saves the round-trip of opening/closing a nearby table for
                // simple recipes (planks, sticks, torches, crafting_table).
                // Only recipes that require the full 3x3 grid (chest, furnace,
                // iron tools, armor) fall back to the table path.
                String norm = item.trim().toLowerCase();
                if (norm.startsWith("minecraft:")) norm = norm.substring("minecraft:".length());
                RecipeSpec rSpec = RECIPES.get(norm);
                boolean use2x2 = fitsPersonalGrid(rSpec);
                currentTask = new CraftTask(item, count, use2x2);
                return;
            case "use":
                currentTask = new UseHeldTask();
                return;
            case "place":
                if (parts.length != 5) {
                    emit("place_failed", "Usage: !place <block> <x> <y> <z>  (coords may be absolute like 100 or relative like ~3 ~ ~-2)");
                    return;
                }
                String blockId = parts[1];
                ClientPlayerEntity pePlace = MinecraftClient.getInstance() == null ? null
                                            : MinecraftClient.getInstance().player;
                if (pePlace == null) {
                    emit("place_failed", "No player — can't resolve place coords.");
                    return;
                }
                BlockPos here = pePlace.getBlockPos();
                Integer px = parseCoord(parts[2], here.getX());
                Integer py = parseCoord(parts[3], here.getY());
                Integer pz = parseCoord(parts[4], here.getZ());
                if (px == null || py == null || pz == null) {
                    emit("place_failed", "Invalid coord in !place " + blockId
                        + " " + parts[2] + " " + parts[3] + " " + parts[4]);
                    return;
                }
                currentTask = new PlaceTask(blockId, new BlockPos(px, py, pz));
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

    /** Render current player+world state as a single line. Fields match what
     *  the session.snapshot envelope emits from Baritone, but as a synchronous
     *  query the model can call any time via !status. */
    private static String buildStatus() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.player == null || mc.world == null) return "unavailable (no world)";
        ClientPlayerEntity p = mc.player;
        BlockPos pos = p.getBlockPos();
        StringBuilder sb = new StringBuilder();
        sb.append("pos=(").append(pos.getX()).append(',').append(pos.getY()).append(',').append(pos.getZ()).append(')');
        Identifier dim = mc.world.getRegistryKey().getValue();
        sb.append(" dim=").append(dim == null ? "?" : dim.getPath());
        sb.append(" hp=").append(String.format("%.0f", p.getHealth())).append('/').append(String.format("%.0f", p.getMaxHealth()));
        sb.append(" hunger=").append(p.getHungerManager().getFoodLevel());
        try {
            String biome = mc.world.getBiome(pos).getKey()
                .map(k -> k.getValue().getPath()).orElse("?");
            sb.append(" biome=").append(biome);
        } catch (Throwable ignored) { }
        long ttime = mc.world.getTimeOfDay() % 24000;
        sb.append(" time=").append(ttime < 12000 ? "day" : (ttime < 13000 ? "dusk" : (ttime < 23000 ? "night" : "dawn")));
        sb.append(" held=").append(itemName(p.getMainHandStack()));
        return sb.toString();
    }

    /** Render full inventory grouped by section. Empty slots omitted. */
    private static String buildInventory() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.player == null) return "unavailable (no player)";
        PlayerInventory inv = mc.player.getInventory();

        Map<String, Integer> hotbar = new java.util.LinkedHashMap<>();
        Map<String, Integer> main = new java.util.LinkedHashMap<>();
        Map<String, Integer> armor = new java.util.LinkedHashMap<>();
        Map<String, Integer> offhand = new java.util.LinkedHashMap<>();

        // Slots 0-8 = hotbar, 9-35 = main storage
        for (int i = 0; i < 9; i++) accumulate(hotbar, inv.getStack(i));
        for (int i = 9; i < 36; i++) accumulate(main, inv.getStack(i));
        // Armor slots 36-39 (feet, legs, chest, head)
        for (int i = 36; i < 40; i++) accumulate(armor, inv.getStack(i));
        // Offhand slot 40
        accumulate(offhand, inv.getStack(40));

        StringBuilder sb = new StringBuilder();
        appendSection(sb, "hotbar", hotbar);
        appendSection(sb, "main", main);
        appendSection(sb, "armor", armor);
        appendSection(sb, "offhand", offhand);
        if (sb.length() == 0) sb.append("empty");
        return sb.toString();
    }

    private static void accumulate(Map<String, Integer> into, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        String name = itemName(stack);
        into.merge(name, stack.getCount(), Integer::sum);
    }

    private static void appendSection(StringBuilder sb, String label, Map<String, Integer> items) {
        if (items.isEmpty()) return;
        if (sb.length() > 0) sb.append(" | ");
        sb.append(label).append(": ");
        boolean first = true;
        for (Map.Entry<String, Integer> e : items.entrySet()) {
            if (!first) sb.append(", ");
            first = false;
            sb.append(e.getKey()).append("×").append(e.getValue());
        }
    }

    /** Clean item id, e.g. "diamond_pickaxe" (strip "minecraft:" namespace). */
    private static String itemName(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "empty";
        Identifier id = Registries.ITEM.getId(stack.getItem());
        if (id == null) return "unknown";
        return "minecraft".equals(id.getNamespace()) ? id.getPath() : id.toString();
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

    /** Recipes whose slots all fall inside the 2x2 sub-grid (top-left 4
     *  slots of the 3x3 table layout) can be crafted in the player's
     *  personal inventory grid without a crafting_table. */
    private static boolean fitsPersonalGrid(RecipeSpec r) {
        if (r == null) return false;
        for (int s : r.slots) {
            if (s != 1 && s != 2 && s != 4 && s != 5) return false;
        }
        return true;
    }

    private static class CraftTask implements Task {
        private final String targetItemId;
        private final String targetItemKey;
        private final int targetCount;
        private final RecipeSpec recipe;
        /** When true, use the player's own 2x2 inventory grid (PlayerScreenHandler);
         *  no crafting_table needed. Chosen at construction based on recipe shape
         *  and table availability. */
        private final boolean use2x2;

        private State state;
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

        CraftTask(String itemId, int count, boolean use2x2) {
            String norm = itemId.trim().toLowerCase();
            if (norm.startsWith("minecraft:")) norm = norm.substring("minecraft:".length());
            this.targetItemKey = norm;
            this.targetItemId = "minecraft:" + norm;
            this.targetCount = Math.max(1, count);
            this.recipe = RECIPES.get(norm);
            this.use2x2 = use2x2;
            // 2x2 path skips table-find/screen-open — grid is always available.
            this.state = use2x2 ? State.PLACE_INGREDIENTS : State.FIND_TABLE;
        }

        /** Translate a 3x3 grid slot (1-9) to the corresponding player-crafting
         *  slot (1-4) in the PlayerScreenHandler layout. Only valid on the 2x2
         *  path where all slots are in {1, 2, 4, 5}. */
        private int handlerSlot(int gridSlot) {
            if (!use2x2) return gridSlot;
            switch (gridSlot) {
                case 1: return 1;
                case 2: return 2;
                case 4: return 3;
                case 5: return 4;
                default: return gridSlot;   // unreachable if fitsPersonalGrid returned true
            }
        }

        /** First player-inventory slot in the currently-open container.
         *  CraftingScreenHandler places main inv at slot 10. PlayerScreenHandler
         *  places main inv at slot 9 (crafting=1-4, armor=5-8, main=9-35, hotbar=36-44). */
        private int playerInvStart() { return use2x2 ? 9 : 10; }

        /** Last grid slot to clear on cleanup. */
        private int lastGridSlot() { return use2x2 ? 4 : 9; }

        /** True when the container we need is currently active on the player. */
        private boolean containerActive(ClientPlayerEntity player) {
            if (use2x2) return player.currentScreenHandler instanceof PlayerScreenHandler;
            return player.currentScreenHandler instanceof CraftingScreenHandler;
        }

        @Override public String name() { return "craft " + targetItemKey + " x" + targetCount + (use2x2 ? " [2x2]" : ""); }

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
            // Target-total semantics: if the player already has enough, no-op.
            // Matches !craft prompt contract ("count = final inventory target").
            // Also saves ingredients when the AI double-dispatches by mistake.
            int haveInit = countInInventory(player, itemFromId(targetItemId));
            if (haveInit >= targetCount) {
                emit("craft_ok", "Already have " + haveInit + " " + targetItemKey + " (target " + targetCount + ") — no crafting needed.");
                enter(State.DONE); return true;
            }
            if (player.currentScreenHandler instanceof CraftingScreenHandler) {
                initialInventoryCount = countInInventory(player, itemFromId(targetItemId));
                lastKnownCount = initialInventoryCount;
                ingredientIdx = 0; subStep = 0; sourceSlotForCurrent = -1;
                enter(State.PLACE_INGREDIENTS); return false;
            }
            BlockPos found = findCraftingTableNear(player, world);
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
            if (!containerActive(player) || im == null) {
                emit("craft_failed", (use2x2 ? "Personal " : "") + "Crafting screen unavailable.");
                enter(use2x2 ? State.DONE : State.CLOSE); return false;
            }
            // On the 2x2 path we skipped FIND_TABLE, so capture the initial
            // inventory count on the first placement tick.
            if (use2x2 && initialInventoryCount < 0) {
                initialInventoryCount = countInInventory(player, itemFromId(targetItemId));
                lastKnownCount = initialInventoryCount;
                if (initialInventoryCount >= targetCount) {
                    emit("craft_ok", "Already have " + initialInventoryCount + " " + targetItemKey + " (target " + targetCount + ") — no crafting needed.");
                    enter(State.DONE); return true;
                }
            }
            if (ingredientIdx >= recipe.slots.length) { enter(State.TAKE_OUTPUT); return false; }
            int gridSlot = handlerSlot(recipe.slots[ingredientIdx]);
            String ingId = recipe.items[ingredientIdx];
            Item ingItem = itemFromId("minecraft:" + ingId);
            if (ingItem == null) {
                emit("craft_failed", "Unknown ingredient in recipe: " + ingId);
                enter(State.CLOSE); return false;
            }
            switch (subStep) {
                case 0: {
                    int sourceSlot = findItemSlotInHandler(h, ingItem, playerInvStart());
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
            if (!containerActive(player)) {
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
            int crafted = haveNow - initialInventoryCount;
            // Total semantics: stop when inventory hits the target, not when
            // this run's delta hits it. Prevents !craft stone_axe 1 (when
            // you already have 1) from ending at inventory 2.
            if (haveNow >= targetCount) {
                emit("craft_ok", "Crafted " + crafted + " " + targetItemKey + " (target " + targetCount + ", inventory " + haveNow + ")");
                enter(State.CLEAR_GRID); return false;
            }
            if (haveNow == lastKnownCount) {
                emit("craft_failed", "Stopped at inventory " + haveNow + "/" + targetCount + " — no more ingredients for " + targetItemKey);
                enter(State.CLEAR_GRID); return false;
            }
            lastKnownCount = haveNow;
            ingredientIdx = 0; subStep = 0; sourceSlotForCurrent = -1;
            enter(State.PLACE_INGREDIENTS); return false;
        }

        private boolean doClearGrid(MinecraftClient client, ClientPlayerEntity player) {
            ScreenHandler h = player.currentScreenHandler;
            ClientPlayerInteractionManager im = client.interactionManager;
            if (!containerActive(player) || im == null) { enter(use2x2 ? State.DONE : State.CLOSE); return false; }
            for (int i = 1; i <= lastGridSlot(); i++) {
                if (!h.slots.get(i).getStack().isEmpty()) {
                    im.clickSlot(h.syncId, i, 0, SlotActionType.QUICK_MOVE, player);
                }
            }
            enter(use2x2 ? State.DONE : State.WAIT_CLEAR); return false;
        }

        private boolean doWaitClear() {
            if (ticksInState >= 2) enter(State.CLOSE);
            return false;
        }

        private boolean doClose(MinecraftClient client, ClientPlayerEntity player) {
            OdysseusBridge.LOG.info("[odysseus] doClose — closing screen and arming pause-menu guard");
            suppressPauseMenuTicks = 40;
            client.execute(() -> {
                if (client.currentScreen != null) {
                    client.currentScreen.close();
                } else if (player.currentScreenHandler != player.playerScreenHandler) {
                    player.closeHandledScreen();
                }
            });
            enter(State.DONE);
            return true;
        }
    }

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

    /** Return the first placeable neighbor of {@code preferred}, or null if
     *  no usable spot exists within reach. Prefers the requested spot itself,
     *  then its 4 horizontal neighbors, then +1 above the requested spot and
     *  its neighbors. "Placeable" means: currently air, has a solid block
     *  directly below for support, and within 4 blocks of the player.
     *  Used by PlaceTask to auto-relocate when the model's chosen offset is
     *  blocked — avoids the AI looping on the same failing coordinate. */
    private static BlockPos findPlaceableNear(ClientPlayerEntity player, World world, BlockPos preferred) {
        BlockPos playerPos = player.getBlockPos();
        int[][] offsets = new int[][] {
            {0, 0, 0},
            {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1},   // horizontal neighbors
            {0, 1, 0},                                        // one up (in case player is on a step)
            {1, 1, 0}, {-1, 1, 0}, {0, 1, 1}, {0, 1, -1},    // horizontal neighbors, one up
            {2, 0, 0}, {-2, 0, 0}, {0, 0, 2}, {0, 0, -2},    // one further out
        };
        for (int[] off : offsets) {
            BlockPos cand = preferred.add(off[0], off[1], off[2]);
            if (!world.getBlockState(cand).isAir()) continue;
            if (world.getBlockState(cand.down()).isAir()) continue;
            double dsq = playerPos.getSquaredDistance(cand);
            if (dsq > 4.0 * 4.0) continue;
            return cand;
        }
        return null;
    }

    /** Search a 7×4×7 box centered on the player for a crafting_table.
     *  Returns null if none within range. Shared by !craft (to decide 2×2
     *  fallback) and CraftTask.doFindTable. */
    private static BlockPos findCraftingTableNear(ClientPlayerEntity player, World world) {
        BlockPos here = player.getBlockPos();
        for (int dy = -1; dy <= 2; dy++)
            for (int dx = -3; dx <= 3; dx++)
                for (int dz = -3; dz <= 3; dz++) {
                    BlockPos p = here.add(dx, dy, dz);
                    if (world.getBlockState(p).getBlock() == Blocks.CRAFTING_TABLE) return p;
                }
        return null;
    }

    private static class PlaceTask implements Task {
        private final String blockKey;
        private final String blockItemId;
        /** Position the user (model) originally asked for. */
        private final BlockPos requestedTarget;
        /** Where we're actually going to place — starts equal to requestedTarget,
         *  bumped to a nearby offset if the requested spot is occupied but a
         *  usable neighbor is within reach. */
        private BlockPos resolvedTarget;
        private State state = State.CHECK_AND_EQUIP;
        private int ticksInState = 0;
        private int hotbarSlotToUse = -1;

        enum State { CHECK_AND_EQUIP, WAIT_EQUIP, DO_PLACE, VERIFY, DONE }

        PlaceTask(String blockId, BlockPos target) {
            String norm = blockId.trim().toLowerCase();
            if (norm.startsWith("minecraft:")) norm = norm.substring("minecraft:".length());
            this.blockKey = norm;
            this.blockItemId = "minecraft:" + norm;
            this.requestedTarget = target;
            this.resolvedTarget = target;
        }

        @Override public String name() {
            return "place " + blockKey + " at (" + requestedTarget.getX() + "," + requestedTarget.getY() + "," + requestedTarget.getZ() + ")";
        }

        @Override
        public boolean tick(MinecraftClient client) {
            ClientPlayerEntity player = client.player;
            World world = client.world;
            if (player == null || world == null) return true;
            ticksInState++;
            switch (state) {
                case CHECK_AND_EQUIP: return doCheckAndEquip(client, player);
                case WAIT_EQUIP:      return doWaitEquip();
                case DO_PLACE:        return doDoPlace(client, player, world);
                case VERIFY:          return doVerify(client, player, world);
                case DONE:            return true;
            }
            return true;
        }

        private void enter(State next) { this.state = next; this.ticksInState = 0; }

        private boolean doCheckAndEquip(MinecraftClient client, ClientPlayerEntity player) {
            Item wantItem = itemFromId(blockItemId);
            if (wantItem == null) {
                emit("place_failed", "Unknown block: " + blockKey);
                return true;
            }
            int have = countInInventory(player, wantItem);
            if (have <= 0) {
                emit("place_failed", "Don't have " + blockKey + " in inventory.");
                return true;
            }
            // Reach check. Vanilla reach is 4.5 blocks; we're a hair inside to
            // leave margin for interact. Fail with an actionable #goto hint.
            double dsq = player.getBlockPos().getSquaredDistance(resolvedTarget);
            if (dsq > 4.0 * 4.0) {
                emit("place_failed", "Too far (" + String.format("%.1f", Math.sqrt(dsq))
                    + " blocks) — walk closer first with #goto "
                    + resolvedTarget.getX() + " " + resolvedTarget.getY() + " " + resolvedTarget.getZ());
                return true;
            }

            int selected = player.getInventory().getSelectedSlot();
            ItemStack held = player.getInventory().getStack(selected);
            if (held != null && !held.isEmpty() && held.getItem() == wantItem) {
                // Already holding the right block — skip equip.
                hotbarSlotToUse = selected;
                enter(State.DO_PLACE);
                return false;
            }
            // Find the item somewhere in inventory (skipping crafting/armor
            // slots at 0-8 of the player screen handler).
            PlayerScreenHandler h = player.playerScreenHandler;
            int srcSlot = findItemSlotInHandler(h, wantItem, 9);
            if (srcSlot < 0) {
                emit("place_failed", "Don't have " + blockKey + " in inventory.");
                return true;
            }
            ClientPlayerInteractionManager im = client.interactionManager;
            if (im == null) {
                emit("place_failed", "No interaction manager.");
                return true;
            }
            // SWAP action: button param = target hotbar slot (0-8).
            hotbarSlotToUse = selected;
            im.clickSlot(h.syncId, srcSlot, selected, SlotActionType.SWAP, player);
            enter(State.WAIT_EQUIP);
            return false;
        }

        private boolean doWaitEquip() {
            if (ticksInState >= 3) enter(State.DO_PLACE);
            return false;
        }

        private boolean doDoPlace(MinecraftClient client, ClientPlayerEntity player, World world) {
            // If the requested position is unusable (occupied, or nothing to
            // place on), search a small ring of neighbors for a valid spot
            // instead of hard-failing. The AI can then keep working with the
            // reported actual position rather than looping on the same offset.
            BlockPos spot = findPlaceableNear(player, world, resolvedTarget);
            if (spot == null) {
                emit("place_failed", "No valid placement spot near ("
                    + requestedTarget.getX() + "," + requestedTarget.getY() + ","
                    + requestedTarget.getZ() + ") — requested position and nearby offsets are all occupied, unsupported, or out of reach. Try a different area with #goto or !place at explicit coords further away.");
                enter(State.DONE); return true;
            }
            if (!spot.equals(requestedTarget)) {
                OdysseusBridge.LOG.info("[odysseus] !place fallback — requested ({},{},{}) unusable, using ({},{},{})",
                    requestedTarget.getX(), requestedTarget.getY(), requestedTarget.getZ(),
                    spot.getX(), spot.getY(), spot.getZ());
            }
            resolvedTarget = spot;
            BlockPos support = resolvedTarget.down();
            // Click the TOP face of the support block to place on top of it.
            Vec3d hitVec = new Vec3d(resolvedTarget.getX() + 0.5,
                                     resolvedTarget.getY(),
                                     resolvedTarget.getZ() + 0.5);
            BlockHitResult hit = new BlockHitResult(hitVec, Direction.UP, support, false);
            ClientPlayerInteractionManager im = client.interactionManager;
            if (im == null) {
                emit("place_failed", "No interaction manager.");
                enter(State.DONE); return true;
            }
            im.interactBlock(player, Hand.MAIN_HAND, hit);
            enter(State.VERIFY);
            return false;
        }

        private boolean doVerify(MinecraftClient client, ClientPlayerEntity player, World world) {
            // One tick delay to let the server round-trip.
            if (ticksInState < 2) return false;
            BlockState nowAt = world.getBlockState(resolvedTarget);
            if (nowAt.isAir()) {
                emit("place_failed", "Placement of " + blockKey + " at ("
                    + resolvedTarget.getX() + "," + resolvedTarget.getY() + "," + resolvedTarget.getZ()
                    + ") didn't stick — check facing, reach, or block support.");
                enter(State.DONE); return true;
            }
            // If we fell back to a nearby offset, spell that out so the model
            // updates its mental map and doesn't retry the original coord.
            String reqPart = "";
            if (!resolvedTarget.equals(requestedTarget)) {
                reqPart = " (requested (" + requestedTarget.getX() + "," + requestedTarget.getY()
                    + "," + requestedTarget.getZ() + ") was occupied — used nearby offset)";
            }
            emit("place_ok", "Placed " + blockKey + " at ("
                + resolvedTarget.getX() + "," + resolvedTarget.getY() + "," + resolvedTarget.getZ() + ")"
                + reqPart);
            enter(State.DONE);
            return true;
        }
    }

    private static int findItemSlotInHandler(ScreenHandler h, Item item, int startFromSlot) {
        for (int i = startFromSlot; i < h.slots.size(); i++) {
            ItemStack s = h.slots.get(i).getStack();
            if (!s.isEmpty() && s.getItem() == item) return i;
        }
        return -1;
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

    /** Parse a coordinate token in the Minecraft-command style: an integer,
     *  {@code ~} (= reference), {@code ~N} (= reference + N), or {@code ~-N}.
     *  Returns null on malformed input. Used by !place. */
    private static Integer parseCoord(String token, int reference) {
        if (token == null || token.isEmpty()) return null;
        try {
            if (token.charAt(0) == '~') {
                if (token.length() == 1) return reference;
                return reference + Integer.parseInt(token.substring(1));
            }
            return Integer.parseInt(token);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void emit(String event, String text) {
        OdysseusBridge.LOG.info("[odysseus] {} : {}", event, text);
        BridgeClient c = OdysseusBridge.getClient();
        if (c != null) c.sendEvent(event, text);
    }
}

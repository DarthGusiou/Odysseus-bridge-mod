package com.odysseus.bridge;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.Recipe;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.recipe.RecipeManager;
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

import java.util.Optional;

/**
 * Custom Odysseus commands — crafting, chest interaction, item use.
 * Runs a single ticking task at a time via a ClientTickEvents handler
 * so multi-step ops (open menu → click recipe → take output → close)
 * can span ticks without blocking the render thread.
 *
 * Status is reported back through the WebSocket via BridgeClient.sendEvent
 * so Odysseus can surface it in the panel and let the AI react.
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

    /**
     * State machine:
     *   FIND_TABLE    → locate nearby crafting_table, right-click
     *   WAIT_SCREEN   → poll for CraftingScreenHandler
     *   CLICK_RECIPE  → send clickRecipe(craftAll=true)
     *   TAKE_OUTPUT   → shift-click slot 0 to move result to inventory
     *   CHECK_COUNT   → decide loop or close
     *   CLOSE         → closeHandledScreen + final status
     */
    private static class CraftTask implements Task {
        private final String targetItemId;
        private final int targetCount;

        private State state = State.FIND_TABLE;
        private int ticksInState = 0;
        private int initialInventoryCount = -1;
        private int lastKnownCount = -1;
        private RecipeEntry<?> recipe;

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
            if (recipe == null) {
                Item target = itemFromId(targetItemId);
                if (target == null) {
                    emit("craft_failed", "Unknown item id: " + targetItemId);
                    enter(State.CLOSE);
                    return false;
                }
                Optional<RecipeEntry<?>> pick = findRecipeForOutput(world, target);
                if (pick.isEmpty()) {
                    emit("craft_failed", "No recipe found that produces " + targetItemId);
                    enter(State.CLOSE);
                    return false;
                }
                recipe = pick.get();
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
            im.clickRecipe(h.syncId, recipe, /*craftAll=*/true);
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

    // ── Helpers ───────────────────────────────────────────────────────────

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

    private static Optional<RecipeEntry<?>> findRecipeForOutput(World world, Item output) {
        RecipeManager rm = world.getRecipeManager();
        try {
            for (RecipeEntry<?> entry : rm.values()) {
                try {
                    Recipe<?> rec = entry.value();
                    ItemStack result = rec.getResult(world.getRegistryManager());
                    if (!result.isEmpty() && result.getItem() == output) {
                        return Optional.of(entry);
                    }
                } catch (Throwable ignore) {}
            }
        } catch (Throwable t) {
            OdysseusBridge.LOG.warn("[odysseus] recipe scan failed: {}", t.toString());
        }
        return Optional.empty();
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

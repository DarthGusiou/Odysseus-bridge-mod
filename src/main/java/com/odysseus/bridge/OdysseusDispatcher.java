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

    // ── Wood-family recipe substitution ──────────────────────────────────
    // Vanilla recipes that use the #minecraft:planks or #minecraft:wooden_slabs
    // tags accept ANY variant of that family. The recipes.json we import
    // from Prismarine's minecraft-data expands each tag into one variant per
    // wood, and we only registered the first variant — so a bare
    // `!craft bowl` would demand whatever plank type the JSON happened to
    // list first (often cherry_planks). Instead, CraftTask.doPlaceIngredients
    // checks these sets and, for matching recipes, substitutes any-plank or
    // any-wooden-slab from inventory.
    //
    // Verified against 1.21.8 recipes.json — see scratchpad/wood_variant_recipes.json.
    // 25 total (Group A: 20 planks-only; Group B: 2 mixed; Group C: 3 slab-only).

    /** All 12 plank items in 1.21.8. */
    private static final java.util.Set<String> PLANK_ITEMS = java.util.Set.of(
        "oak_planks", "birch_planks", "spruce_planks", "jungle_planks",
        "acacia_planks", "dark_oak_planks", "mangrove_planks", "cherry_planks",
        "pale_oak_planks", "crimson_planks", "warped_planks", "bamboo_planks"
    );

    /** All 12 wooden slab items in 1.21.8 (matches #wooden_slabs per the data). */
    private static final java.util.Set<String> WOODEN_SLAB_ITEMS = java.util.Set.of(
        "oak_slab", "birch_slab", "spruce_slab", "jungle_slab",
        "acacia_slab", "dark_oak_slab", "mangrove_slab", "cherry_slab",
        "pale_oak_slab", "crimson_slab", "warped_slab", "bamboo_slab"
    );

    /** Recipes whose _planks ingredient positions accept ANY plank variant.
     *  Groups A + B — the 22 vanilla recipes tagged with #minecraft:planks. */
    private static final java.util.Set<String> PLANK_ACCEPTING_RECIPES = java.util.Set.of(
        "beehive", "bookshelf", "bowl", "cartography_table", "chest",
        "crafting_table", "fletching_table", "grindstone", "jukebox", "loom",
        "note_block", "piston", "shield", "smithing_table", "tripwire_hook",
        "wooden_axe", "wooden_hoe", "wooden_pickaxe", "wooden_shovel", "wooden_sword",
        "barrel", "chiseled_bookshelf"
    );

    /** Recipes whose _slab ingredient positions accept ANY wooden slab variant.
     *  Groups B + C — the 5 vanilla recipes tagged with #minecraft:wooden_slabs. */
    private static final java.util.Set<String> WOODEN_SLAB_ACCEPTING_RECIPES = java.util.Set.of(
        "composter", "daylight_detector", "lectern",
        "barrel", "chiseled_bookshelf"
    );

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

        // ── Auto-generated from Prismarine minecraft-data 1.21.8 ────────
        //   Source: recipes.json (829 result items, 1 variant each — first)
        //   Extractor: scratchpad/extract_recipes.py
        //   Skipped: existing hand-crafted (planks/stick/wood+stone tools/table/torch/
        //            furnace/chest) whose oak_planks preference is preserved.

        RECIPES.put("granite", new RecipeSpec(1, new int[]{1, 2}, new String[]{"diorite", "quartz"}));
        RECIPES.put("polished_granite", new RecipeSpec(4, new int[]{1, 2, 4, 5}, new String[]{"granite", "granite", "granite", "granite"}));
        RECIPES.put("diorite", new RecipeSpec(2, new int[]{1, 2, 4, 5}, new String[]{"cobblestone", "quartz", "quartz", "cobblestone"}));
        RECIPES.put("polished_diorite", new RecipeSpec(4, new int[]{1, 2, 4, 5}, new String[]{"diorite", "diorite", "diorite", "diorite"}));
        RECIPES.put("andesite", new RecipeSpec(2, new int[]{1, 2}, new String[]{"diorite", "cobblestone"}));
        RECIPES.put("polished_andesite", new RecipeSpec(4, new int[]{1, 2, 4, 5}, new String[]{"andesite", "andesite", "andesite", "andesite"}));
        RECIPES.put("polished_deepslate", new RecipeSpec(4, new int[]{1, 2, 4, 5}, new String[]{"cobbled_deepslate", "cobbled_deepslate", "cobbled_deepslate", "cobbled_deepslate"}));
        RECIPES.put("tuff_slab", new RecipeSpec(6, new int[]{1, 2, 3}, new String[]{"tuff", "tuff", "tuff"}));
        RECIPES.put("tuff_stairs", new RecipeSpec(4, new int[]{1, 4, 5, 7, 8, 9}, new String[]{"tuff", "tuff", "tuff", "tuff", "tuff", "tuff"}));
        RECIPES.put("tuff_wall", new RecipeSpec(6, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"tuff", "tuff", "tuff", "tuff", "tuff", "tuff"}));
        RECIPES.put("chiseled_tuff", new RecipeSpec(1, new int[]{1, 4}, new String[]{"tuff_slab", "tuff_slab"}));
        RECIPES.put("polished_tuff", new RecipeSpec(4, new int[]{1, 2, 4, 5}, new String[]{"tuff", "tuff", "tuff", "tuff"}));
        RECIPES.put("polished_tuff_slab", new RecipeSpec(6, new int[]{1, 2, 3}, new String[]{"polished_tuff", "polished_tuff", "polished_tuff"}));
        RECIPES.put("polished_tuff_stairs", new RecipeSpec(4, new int[]{1, 4, 5, 7, 8, 9}, new String[]{"polished_tuff", "polished_tuff", "polished_tuff", "polished_tuff", "polished_tuff", "polished_tuff"}));
        RECIPES.put("polished_tuff_wall", new RecipeSpec(6, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"polished_tuff", "polished_tuff", "polished_tuff", "polished_tuff", "polished_tuff", "polished_tuff"}));
        RECIPES.put("tuff_bricks", new RecipeSpec(4, new int[]{1, 2, 4, 5}, new String[]{"polished_tuff", "polished_tuff", "polished_tuff", "polished_tuff"}));
        RECIPES.put("tuff_brick_slab", new RecipeSpec(6, new int[]{1, 2, 3}, new String[]{"tuff_bricks", "tuff_bricks", "tuff_bricks"}));
        RECIPES.put("tuff_brick_stairs", new RecipeSpec(4, new int[]{1, 4, 5, 7, 8, 9}, new String[]{"tuff_bricks", "tuff_bricks", "tuff_bricks", "tuff_bricks", "tuff_bricks", "tuff_bricks"}));
        RECIPES.put("tuff_brick_wall", new RecipeSpec(6, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"tuff_bricks", "tuff_bricks", "tuff_bricks", "tuff_bricks", "tuff_bricks", "tuff_bricks"}));
        RECIPES.put("chiseled_tuff_bricks", new RecipeSpec(1, new int[]{1, 4}, new String[]{"tuff_brick_slab", "tuff_brick_slab"}));
        RECIPES.put("dripstone_block", new RecipeSpec(1, new int[]{1, 2, 4, 5}, new String[]{"pointed_dripstone", "pointed_dripstone", "pointed_dripstone", "pointed_dripstone"}));
        RECIPES.put("coarse_dirt", new RecipeSpec(4, new int[]{1, 2, 4, 5}, new String[]{"dirt", "gravel", "gravel", "dirt"}));
        RECIPES.put("bamboo_planks", new RecipeSpec(2, new int[]{1}, new String[]{"bamboo_block"}));
        RECIPES.put("crimson_planks", new RecipeSpec(4, new int[]{1}, new String[]{"crimson_stem"}));
        RECIPES.put("warped_planks", new RecipeSpec(4, new int[]{1}, new String[]{"warped_stem"}));
        RECIPES.put("bamboo_mosaic", new RecipeSpec(1, new int[]{1, 4}, new String[]{"bamboo_slab", "bamboo_slab"}));
        RECIPES.put("coal_block", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"coal", "coal", "coal", "coal", "coal", "coal", "coal", "coal", "coal"}));
        RECIPES.put("raw_iron_block", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"raw_iron", "raw_iron", "raw_iron", "raw_iron", "raw_iron", "raw_iron", "raw_iron", "raw_iron", "raw_iron"}));
        RECIPES.put("raw_copper_block", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"raw_copper", "raw_copper", "raw_copper", "raw_copper", "raw_copper", "raw_copper", "raw_copper", "raw_copper", "raw_copper"}));
        RECIPES.put("raw_gold_block", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"raw_gold", "raw_gold", "raw_gold", "raw_gold", "raw_gold", "raw_gold", "raw_gold", "raw_gold", "raw_gold"}));
        RECIPES.put("amethyst_block", new RecipeSpec(1, new int[]{1, 2, 4, 5}, new String[]{"amethyst_shard", "amethyst_shard", "amethyst_shard", "amethyst_shard"}));
        RECIPES.put("iron_block", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"iron_ingot", "iron_ingot", "iron_ingot", "iron_ingot", "iron_ingot", "iron_ingot", "iron_ingot", "iron_ingot", "iron_ingot"}));
        RECIPES.put("copper_block", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"copper_ingot", "copper_ingot", "copper_ingot", "copper_ingot", "copper_ingot", "copper_ingot", "copper_ingot", "copper_ingot", "copper_ingot"}));
        RECIPES.put("gold_block", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"gold_ingot", "gold_ingot", "gold_ingot", "gold_ingot", "gold_ingot", "gold_ingot", "gold_ingot", "gold_ingot", "gold_ingot"}));
        RECIPES.put("diamond_block", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"diamond", "diamond", "diamond", "diamond", "diamond", "diamond", "diamond", "diamond", "diamond"}));
        RECIPES.put("netherite_block", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"netherite_ingot", "netherite_ingot", "netherite_ingot", "netherite_ingot", "netherite_ingot", "netherite_ingot", "netherite_ingot", "netherite_ingot", "netherite_ingot"}));
        RECIPES.put("chiseled_copper", new RecipeSpec(1, new int[]{1, 4}, new String[]{"cut_copper_slab", "cut_copper_slab"}));
        RECIPES.put("exposed_chiseled_copper", new RecipeSpec(1, new int[]{1, 4}, new String[]{"exposed_cut_copper_slab", "exposed_cut_copper_slab"}));
        RECIPES.put("weathered_chiseled_copper", new RecipeSpec(1, new int[]{1, 4}, new String[]{"weathered_cut_copper_slab", "weathered_cut_copper_slab"}));
        RECIPES.put("oxidized_chiseled_copper", new RecipeSpec(1, new int[]{1, 4}, new String[]{"oxidized_cut_copper_slab", "oxidized_cut_copper_slab"}));
        RECIPES.put("cut_copper", new RecipeSpec(4, new int[]{1, 2, 4, 5}, new String[]{"copper_block", "copper_block", "copper_block", "copper_block"}));
        RECIPES.put("exposed_cut_copper", new RecipeSpec(4, new int[]{1, 2, 4, 5}, new String[]{"exposed_copper", "exposed_copper", "exposed_copper", "exposed_copper"}));
        RECIPES.put("weathered_cut_copper", new RecipeSpec(4, new int[]{1, 2, 4, 5}, new String[]{"weathered_copper", "weathered_copper", "weathered_copper", "weathered_copper"}));
        RECIPES.put("oxidized_cut_copper", new RecipeSpec(4, new int[]{1, 2, 4, 5}, new String[]{"oxidized_copper", "oxidized_copper", "oxidized_copper", "oxidized_copper"}));
        RECIPES.put("cut_copper_stairs", new RecipeSpec(4, new int[]{1, 4, 5, 7, 8, 9}, new String[]{"cut_copper", "cut_copper", "cut_copper", "cut_copper", "cut_copper", "cut_copper"}));
        RECIPES.put("exposed_cut_copper_stairs", new RecipeSpec(4, new int[]{1, 4, 5, 7, 8, 9}, new String[]{"exposed_cut_copper", "exposed_cut_copper", "exposed_cut_copper", "exposed_cut_copper", "exposed_cut_copper", "exposed_cut_copper"}));
        RECIPES.put("weathered_cut_copper_stairs", new RecipeSpec(4, new int[]{1, 4, 5, 7, 8, 9}, new String[]{"weathered_cut_copper", "weathered_cut_copper", "weathered_cut_copper", "weathered_cut_copper", "weathered_cut_copper", "weathered_cut_copper"}));
        RECIPES.put("oxidized_cut_copper_stairs", new RecipeSpec(4, new int[]{1, 4, 5, 7, 8, 9}, new String[]{"oxidized_cut_copper", "oxidized_cut_copper", "oxidized_cut_copper", "oxidized_cut_copper", "oxidized_cut_copper", "oxidized_cut_copper"}));
        RECIPES.put("cut_copper_slab", new RecipeSpec(6, new int[]{1, 2, 3}, new String[]{"cut_copper", "cut_copper", "cut_copper"}));
        RECIPES.put("exposed_cut_copper_slab", new RecipeSpec(6, new int[]{1, 2, 3}, new String[]{"exposed_cut_copper", "exposed_cut_copper", "exposed_cut_copper"}));
        RECIPES.put("weathered_cut_copper_slab", new RecipeSpec(6, new int[]{1, 2, 3}, new String[]{"weathered_cut_copper", "weathered_cut_copper", "weathered_cut_copper"}));
        RECIPES.put("oxidized_cut_copper_slab", new RecipeSpec(6, new int[]{1, 2, 3}, new String[]{"oxidized_cut_copper", "oxidized_cut_copper", "oxidized_cut_copper"}));
        RECIPES.put("waxed_copper_block", new RecipeSpec(1, new int[]{1, 2}, new String[]{"copper_block", "honeycomb"}));
        RECIPES.put("waxed_exposed_copper", new RecipeSpec(1, new int[]{1, 2}, new String[]{"exposed_copper", "honeycomb"}));
        RECIPES.put("waxed_weathered_copper", new RecipeSpec(1, new int[]{1, 2}, new String[]{"weathered_copper", "honeycomb"}));
        RECIPES.put("waxed_oxidized_copper", new RecipeSpec(1, new int[]{1, 2}, new String[]{"oxidized_copper", "honeycomb"}));
        RECIPES.put("waxed_chiseled_copper", new RecipeSpec(1, new int[]{1, 4}, new String[]{"waxed_cut_copper_slab", "waxed_cut_copper_slab"}));
        RECIPES.put("waxed_exposed_chiseled_copper", new RecipeSpec(1, new int[]{1, 4}, new String[]{"waxed_exposed_cut_copper_slab", "waxed_exposed_cut_copper_slab"}));
        RECIPES.put("waxed_weathered_chiseled_copper", new RecipeSpec(1, new int[]{1, 4}, new String[]{"waxed_weathered_cut_copper_slab", "waxed_weathered_cut_copper_slab"}));
        RECIPES.put("waxed_oxidized_chiseled_copper", new RecipeSpec(1, new int[]{1, 4}, new String[]{"waxed_oxidized_cut_copper_slab", "waxed_oxidized_cut_copper_slab"}));
        RECIPES.put("waxed_cut_copper", new RecipeSpec(4, new int[]{1, 2, 4, 5}, new String[]{"waxed_copper_block", "waxed_copper_block", "waxed_copper_block", "waxed_copper_block"}));
        RECIPES.put("waxed_exposed_cut_copper", new RecipeSpec(4, new int[]{1, 2, 4, 5}, new String[]{"waxed_exposed_copper", "waxed_exposed_copper", "waxed_exposed_copper", "waxed_exposed_copper"}));
        RECIPES.put("waxed_weathered_cut_copper", new RecipeSpec(4, new int[]{1, 2, 4, 5}, new String[]{"waxed_weathered_copper", "waxed_weathered_copper", "waxed_weathered_copper", "waxed_weathered_copper"}));
        RECIPES.put("waxed_oxidized_cut_copper", new RecipeSpec(4, new int[]{1, 2, 4, 5}, new String[]{"waxed_oxidized_copper", "waxed_oxidized_copper", "waxed_oxidized_copper", "waxed_oxidized_copper"}));
        RECIPES.put("waxed_cut_copper_stairs", new RecipeSpec(4, new int[]{1, 4, 5, 7, 8, 9}, new String[]{"waxed_cut_copper", "waxed_cut_copper", "waxed_cut_copper", "waxed_cut_copper", "waxed_cut_copper", "waxed_cut_copper"}));
        RECIPES.put("waxed_exposed_cut_copper_stairs", new RecipeSpec(4, new int[]{1, 4, 5, 7, 8, 9}, new String[]{"waxed_exposed_cut_copper", "waxed_exposed_cut_copper", "waxed_exposed_cut_copper", "waxed_exposed_cut_copper", "waxed_exposed_cut_copper", "waxed_exposed_cut_copper"}));
        RECIPES.put("waxed_weathered_cut_copper_stairs", new RecipeSpec(4, new int[]{1, 4, 5, 7, 8, 9}, new String[]{"waxed_weathered_cut_copper", "waxed_weathered_cut_copper", "waxed_weathered_cut_copper", "waxed_weathered_cut_copper", "waxed_weathered_cut_copper", "waxed_weathered_cut_copper"}));
        RECIPES.put("waxed_oxidized_cut_copper_stairs", new RecipeSpec(4, new int[]{1, 4, 5, 7, 8, 9}, new String[]{"waxed_oxidized_cut_copper", "waxed_oxidized_cut_copper", "waxed_oxidized_cut_copper", "waxed_oxidized_cut_copper", "waxed_oxidized_cut_copper", "waxed_oxidized_cut_copper"}));
        RECIPES.put("waxed_cut_copper_slab", new RecipeSpec(6, new int[]{1, 2, 3}, new String[]{"waxed_cut_copper", "waxed_cut_copper", "waxed_cut_copper"}));
        RECIPES.put("waxed_exposed_cut_copper_slab", new RecipeSpec(6, new int[]{1, 2, 3}, new String[]{"waxed_exposed_cut_copper", "waxed_exposed_cut_copper", "waxed_exposed_cut_copper"}));
        RECIPES.put("waxed_weathered_cut_copper_slab", new RecipeSpec(6, new int[]{1, 2, 3}, new String[]{"waxed_weathered_cut_copper", "waxed_weathered_cut_copper", "waxed_weathered_cut_copper"}));
        RECIPES.put("waxed_oxidized_cut_copper_slab", new RecipeSpec(6, new int[]{1, 2, 3}, new String[]{"waxed_oxidized_cut_copper", "waxed_oxidized_cut_copper", "waxed_oxidized_cut_copper"}));
        RECIPES.put("muddy_mangrove_roots", new RecipeSpec(1, new int[]{1, 2}, new String[]{"mud", "mangrove_roots"}));
        RECIPES.put("bamboo_block", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"bamboo", "bamboo", "bamboo", "bamboo", "bamboo", "bamboo", "bamboo", "bamboo", "bamboo"}));
        RECIPES.put("stripped_oak_wood", new RecipeSpec(3, new int[]{1, 2, 4, 5}, new String[]{"stripped_oak_log", "stripped_oak_log", "stripped_oak_log", "stripped_oak_log"}));
        RECIPES.put("stripped_spruce_wood", new RecipeSpec(3, new int[]{1, 2, 4, 5}, new String[]{"stripped_spruce_log", "stripped_spruce_log", "stripped_spruce_log", "stripped_spruce_log"}));
        RECIPES.put("stripped_birch_wood", new RecipeSpec(3, new int[]{1, 2, 4, 5}, new String[]{"stripped_birch_log", "stripped_birch_log", "stripped_birch_log", "stripped_birch_log"}));
        RECIPES.put("stripped_jungle_wood", new RecipeSpec(3, new int[]{1, 2, 4, 5}, new String[]{"stripped_jungle_log", "stripped_jungle_log", "stripped_jungle_log", "stripped_jungle_log"}));
        RECIPES.put("stripped_acacia_wood", new RecipeSpec(3, new int[]{1, 2, 4, 5}, new String[]{"stripped_acacia_log", "stripped_acacia_log", "stripped_acacia_log", "stripped_acacia_log"}));
        RECIPES.put("stripped_cherry_wood", new RecipeSpec(3, new int[]{1, 2, 4, 5}, new String[]{"stripped_cherry_log", "stripped_cherry_log", "stripped_cherry_log", "stripped_cherry_log"}));
        RECIPES.put("stripped_dark_oak_wood", new RecipeSpec(3, new int[]{1, 2, 4, 5}, new String[]{"stripped_dark_oak_log", "stripped_dark_oak_log", "stripped_dark_oak_log", "stripped_dark_oak_log"}));
        RECIPES.put("stripped_pale_oak_wood", new RecipeSpec(3, new int[]{1, 2, 4, 5}, new String[]{"stripped_pale_oak_log", "stripped_pale_oak_log", "stripped_pale_oak_log", "stripped_pale_oak_log"}));
        RECIPES.put("stripped_mangrove_wood", new RecipeSpec(3, new int[]{1, 2, 4, 5}, new String[]{"stripped_mangrove_log", "stripped_mangrove_log", "stripped_mangrove_log", "stripped_mangrove_log"}));
        RECIPES.put("stripped_crimson_hyphae", new RecipeSpec(3, new int[]{1, 2, 4, 5}, new String[]{"stripped_crimson_stem", "stripped_crimson_stem", "stripped_crimson_stem", "stripped_crimson_stem"}));
        RECIPES.put("stripped_warped_hyphae", new RecipeSpec(3, new int[]{1, 2, 4, 5}, new String[]{"stripped_warped_stem", "stripped_warped_stem", "stripped_warped_stem", "stripped_warped_stem"}));
        RECIPES.put("oak_wood", new RecipeSpec(3, new int[]{1, 2, 4, 5}, new String[]{"oak_log", "oak_log", "oak_log", "oak_log"}));
        RECIPES.put("spruce_wood", new RecipeSpec(3, new int[]{1, 2, 4, 5}, new String[]{"spruce_log", "spruce_log", "spruce_log", "spruce_log"}));
        RECIPES.put("birch_wood", new RecipeSpec(3, new int[]{1, 2, 4, 5}, new String[]{"birch_log", "birch_log", "birch_log", "birch_log"}));
        RECIPES.put("jungle_wood", new RecipeSpec(3, new int[]{1, 2, 4, 5}, new String[]{"jungle_log", "jungle_log", "jungle_log", "jungle_log"}));
        RECIPES.put("acacia_wood", new RecipeSpec(3, new int[]{1, 2, 4, 5}, new String[]{"acacia_log", "acacia_log", "acacia_log", "acacia_log"}));
        RECIPES.put("cherry_wood", new RecipeSpec(3, new int[]{1, 2, 4, 5}, new String[]{"cherry_log", "cherry_log", "cherry_log", "cherry_log"}));
        RECIPES.put("pale_oak_wood", new RecipeSpec(3, new int[]{1, 2, 4, 5}, new String[]{"pale_oak_log", "pale_oak_log", "pale_oak_log", "pale_oak_log"}));
        RECIPES.put("dark_oak_wood", new RecipeSpec(3, new int[]{1, 2, 4, 5}, new String[]{"dark_oak_log", "dark_oak_log", "dark_oak_log", "dark_oak_log"}));
        RECIPES.put("mangrove_wood", new RecipeSpec(3, new int[]{1, 2, 4, 5}, new String[]{"mangrove_log", "mangrove_log", "mangrove_log", "mangrove_log"}));
        RECIPES.put("crimson_hyphae", new RecipeSpec(3, new int[]{1, 2, 4, 5}, new String[]{"crimson_stem", "crimson_stem", "crimson_stem", "crimson_stem"}));
        RECIPES.put("warped_hyphae", new RecipeSpec(3, new int[]{1, 2, 4, 5}, new String[]{"warped_stem", "warped_stem", "warped_stem", "warped_stem"}));
        RECIPES.put("tinted_glass", new RecipeSpec(2, new int[]{2, 4, 5, 6, 8}, new String[]{"amethyst_shard", "amethyst_shard", "glass", "amethyst_shard", "amethyst_shard"}));
        RECIPES.put("lapis_block", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"lapis_lazuli", "lapis_lazuli", "lapis_lazuli", "lapis_lazuli", "lapis_lazuli", "lapis_lazuli", "lapis_lazuli", "lapis_lazuli", "lapis_lazuli"}));
        RECIPES.put("sandstone", new RecipeSpec(1, new int[]{1, 2, 4, 5}, new String[]{"sand", "sand", "sand", "sand"}));
        RECIPES.put("chiseled_sandstone", new RecipeSpec(1, new int[]{1, 4}, new String[]{"sandstone_slab", "sandstone_slab"}));
        RECIPES.put("cut_sandstone", new RecipeSpec(4, new int[]{1, 2, 4, 5}, new String[]{"sandstone", "sandstone", "sandstone", "sandstone"}));
        RECIPES.put("white_wool", new RecipeSpec(1, new int[]{1, 2, 4, 5}, new String[]{"string", "string", "string", "string"}));
        RECIPES.put("orange_wool", new RecipeSpec(1, new int[]{1, 2}, new String[]{"orange_dye", "black_wool"}));
        RECIPES.put("magenta_wool", new RecipeSpec(1, new int[]{1, 2}, new String[]{"magenta_dye", "black_wool"}));
        RECIPES.put("light_blue_wool", new RecipeSpec(1, new int[]{1, 2}, new String[]{"light_blue_dye", "black_wool"}));
        RECIPES.put("yellow_wool", new RecipeSpec(1, new int[]{1, 2}, new String[]{"yellow_dye", "black_wool"}));
        RECIPES.put("lime_wool", new RecipeSpec(1, new int[]{1, 2}, new String[]{"lime_dye", "black_wool"}));
        RECIPES.put("pink_wool", new RecipeSpec(1, new int[]{1, 2}, new String[]{"pink_dye", "black_wool"}));
        RECIPES.put("gray_wool", new RecipeSpec(1, new int[]{1, 2}, new String[]{"gray_dye", "black_wool"}));
        RECIPES.put("light_gray_wool", new RecipeSpec(1, new int[]{1, 2}, new String[]{"light_gray_dye", "black_wool"}));
        RECIPES.put("cyan_wool", new RecipeSpec(1, new int[]{1, 2}, new String[]{"cyan_dye", "black_wool"}));
        RECIPES.put("purple_wool", new RecipeSpec(1, new int[]{1, 2}, new String[]{"purple_dye", "black_wool"}));
        RECIPES.put("blue_wool", new RecipeSpec(1, new int[]{1, 2}, new String[]{"blue_dye", "black_wool"}));
        RECIPES.put("brown_wool", new RecipeSpec(1, new int[]{1, 2}, new String[]{"brown_dye", "black_wool"}));
        RECIPES.put("green_wool", new RecipeSpec(1, new int[]{1, 2}, new String[]{"green_dye", "black_wool"}));
        RECIPES.put("red_wool", new RecipeSpec(1, new int[]{1, 2}, new String[]{"red_dye", "black_wool"}));
        RECIPES.put("black_wool", new RecipeSpec(1, new int[]{1, 2}, new String[]{"black_dye", "blue_wool"}));
        RECIPES.put("moss_carpet", new RecipeSpec(3, new int[]{1, 2}, new String[]{"moss_block", "moss_block"}));
        RECIPES.put("pale_moss_carpet", new RecipeSpec(3, new int[]{1, 2}, new String[]{"pale_moss_block", "pale_moss_block"}));
        RECIPES.put("oak_slab", new RecipeSpec(6, new int[]{1, 2, 3}, new String[]{"oak_planks", "oak_planks", "oak_planks"}));
        RECIPES.put("spruce_slab", new RecipeSpec(6, new int[]{1, 2, 3}, new String[]{"spruce_planks", "spruce_planks", "spruce_planks"}));
        RECIPES.put("birch_slab", new RecipeSpec(6, new int[]{1, 2, 3}, new String[]{"birch_planks", "birch_planks", "birch_planks"}));
        RECIPES.put("jungle_slab", new RecipeSpec(6, new int[]{1, 2, 3}, new String[]{"jungle_planks", "jungle_planks", "jungle_planks"}));
        RECIPES.put("acacia_slab", new RecipeSpec(6, new int[]{1, 2, 3}, new String[]{"acacia_planks", "acacia_planks", "acacia_planks"}));
        RECIPES.put("cherry_slab", new RecipeSpec(6, new int[]{1, 2, 3}, new String[]{"cherry_planks", "cherry_planks", "cherry_planks"}));
        RECIPES.put("dark_oak_slab", new RecipeSpec(6, new int[]{1, 2, 3}, new String[]{"dark_oak_planks", "dark_oak_planks", "dark_oak_planks"}));
        RECIPES.put("pale_oak_slab", new RecipeSpec(6, new int[]{1, 2, 3}, new String[]{"pale_oak_planks", "pale_oak_planks", "pale_oak_planks"}));
        RECIPES.put("mangrove_slab", new RecipeSpec(6, new int[]{1, 2, 3}, new String[]{"mangrove_planks", "mangrove_planks", "mangrove_planks"}));
        RECIPES.put("bamboo_slab", new RecipeSpec(6, new int[]{1, 2, 3}, new String[]{"bamboo_planks", "bamboo_planks", "bamboo_planks"}));
        RECIPES.put("bamboo_mosaic_slab", new RecipeSpec(6, new int[]{1, 2, 3}, new String[]{"bamboo_mosaic", "bamboo_mosaic", "bamboo_mosaic"}));
        RECIPES.put("crimson_slab", new RecipeSpec(6, new int[]{1, 2, 3}, new String[]{"crimson_planks", "crimson_planks", "crimson_planks"}));
        RECIPES.put("warped_slab", new RecipeSpec(6, new int[]{1, 2, 3}, new String[]{"warped_planks", "warped_planks", "warped_planks"}));
        RECIPES.put("stone_slab", new RecipeSpec(6, new int[]{1, 2, 3}, new String[]{"stone", "stone", "stone"}));
        RECIPES.put("smooth_stone_slab", new RecipeSpec(6, new int[]{1, 2, 3}, new String[]{"smooth_stone", "smooth_stone", "smooth_stone"}));
        RECIPES.put("sandstone_slab", new RecipeSpec(6, new int[]{1, 2, 3}, new String[]{"chiseled_sandstone", "chiseled_sandstone", "chiseled_sandstone"}));
        RECIPES.put("cut_sandstone_slab", new RecipeSpec(6, new int[]{1, 2, 3}, new String[]{"cut_sandstone", "cut_sandstone", "cut_sandstone"}));
        RECIPES.put("cobblestone_slab", new RecipeSpec(6, new int[]{1, 2, 3}, new String[]{"cobblestone", "cobblestone", "cobblestone"}));
        RECIPES.put("brick_slab", new RecipeSpec(6, new int[]{1, 2, 3}, new String[]{"bricks", "bricks", "bricks"}));
        RECIPES.put("stone_brick_slab", new RecipeSpec(6, new int[]{1, 2, 3}, new String[]{"stone_bricks", "stone_bricks", "stone_bricks"}));
        RECIPES.put("mud_brick_slab", new RecipeSpec(6, new int[]{1, 2, 3}, new String[]{"mud_bricks", "mud_bricks", "mud_bricks"}));
        RECIPES.put("nether_brick_slab", new RecipeSpec(6, new int[]{1, 2, 3}, new String[]{"nether_bricks", "nether_bricks", "nether_bricks"}));
        RECIPES.put("quartz_slab", new RecipeSpec(6, new int[]{1, 2, 3}, new String[]{"quartz_pillar", "quartz_pillar", "quartz_pillar"}));
        RECIPES.put("red_sandstone_slab", new RecipeSpec(6, new int[]{1, 2, 3}, new String[]{"chiseled_red_sandstone", "chiseled_red_sandstone", "chiseled_red_sandstone"}));
        RECIPES.put("cut_red_sandstone_slab", new RecipeSpec(6, new int[]{1, 2, 3}, new String[]{"cut_red_sandstone", "cut_red_sandstone", "cut_red_sandstone"}));
        RECIPES.put("purpur_slab", new RecipeSpec(6, new int[]{1, 2, 3}, new String[]{"purpur_pillar", "purpur_pillar", "purpur_pillar"}));
        RECIPES.put("prismarine_slab", new RecipeSpec(6, new int[]{1, 2, 3}, new String[]{"prismarine", "prismarine", "prismarine"}));
        RECIPES.put("prismarine_brick_slab", new RecipeSpec(6, new int[]{1, 2, 3}, new String[]{"prismarine_bricks", "prismarine_bricks", "prismarine_bricks"}));
        RECIPES.put("dark_prismarine_slab", new RecipeSpec(6, new int[]{1, 2, 3}, new String[]{"dark_prismarine", "dark_prismarine", "dark_prismarine"}));
        RECIPES.put("bricks", new RecipeSpec(1, new int[]{1, 2, 4, 5}, new String[]{"brick", "brick", "brick", "brick"}));
        RECIPES.put("bookshelf", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"cherry_planks", "cherry_planks", "cherry_planks", "book", "book", "book", "cherry_planks", "cherry_planks", "cherry_planks"}));
        RECIPES.put("chiseled_bookshelf", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"cherry_planks", "cherry_planks", "cherry_planks", "cherry_slab", "cherry_slab", "cherry_slab", "cherry_planks", "cherry_planks", "cherry_planks"}));
        RECIPES.put("decorated_pot", new RecipeSpec(1, new int[]{2, 4, 6, 8}, new String[]{"brick", "brick", "brick", "brick"}));
        RECIPES.put("mossy_cobblestone", new RecipeSpec(1, new int[]{1, 2}, new String[]{"cobblestone", "moss_block"}));
        RECIPES.put("end_rod", new RecipeSpec(4, new int[]{1, 4}, new String[]{"blaze_rod", "popped_chorus_fruit"}));
        RECIPES.put("purpur_block", new RecipeSpec(4, new int[]{1, 2, 4, 5}, new String[]{"popped_chorus_fruit", "popped_chorus_fruit", "popped_chorus_fruit", "popped_chorus_fruit"}));
        RECIPES.put("purpur_pillar", new RecipeSpec(1, new int[]{1, 4}, new String[]{"purpur_slab", "purpur_slab"}));
        RECIPES.put("purpur_stairs", new RecipeSpec(4, new int[]{1, 4, 5, 7, 8, 9}, new String[]{"purpur_pillar", "purpur_pillar", "purpur_pillar", "purpur_pillar", "purpur_pillar", "purpur_pillar"}));
        RECIPES.put("creaking_heart", new RecipeSpec(1, new int[]{1, 4, 7}, new String[]{"pale_oak_log", "resin_block", "pale_oak_log"}));
        RECIPES.put("ladder", new RecipeSpec(3, new int[]{1, 3, 4, 5, 6, 7, 9}, new String[]{"stick", "stick", "stick", "stick", "stick", "stick", "stick"}));
        RECIPES.put("cobblestone_stairs", new RecipeSpec(4, new int[]{1, 4, 5, 7, 8, 9}, new String[]{"cobblestone", "cobblestone", "cobblestone", "cobblestone", "cobblestone", "cobblestone"}));
        RECIPES.put("snow", new RecipeSpec(6, new int[]{1, 2, 3}, new String[]{"snow_block", "snow_block", "snow_block"}));
        RECIPES.put("snow_block", new RecipeSpec(1, new int[]{1, 2, 4, 5}, new String[]{"snowball", "snowball", "snowball", "snowball"}));
        RECIPES.put("clay", new RecipeSpec(1, new int[]{1, 2, 4, 5}, new String[]{"clay_ball", "clay_ball", "clay_ball", "clay_ball"}));
        RECIPES.put("jukebox", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"cherry_planks", "cherry_planks", "cherry_planks", "cherry_planks", "diamond", "cherry_planks", "cherry_planks", "cherry_planks", "cherry_planks"}));
        RECIPES.put("oak_fence", new RecipeSpec(3, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"oak_planks", "stick", "oak_planks", "oak_planks", "stick", "oak_planks"}));
        RECIPES.put("spruce_fence", new RecipeSpec(3, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"spruce_planks", "stick", "spruce_planks", "spruce_planks", "stick", "spruce_planks"}));
        RECIPES.put("birch_fence", new RecipeSpec(3, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"birch_planks", "stick", "birch_planks", "birch_planks", "stick", "birch_planks"}));
        RECIPES.put("jungle_fence", new RecipeSpec(3, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"jungle_planks", "stick", "jungle_planks", "jungle_planks", "stick", "jungle_planks"}));
        RECIPES.put("acacia_fence", new RecipeSpec(3, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"acacia_planks", "stick", "acacia_planks", "acacia_planks", "stick", "acacia_planks"}));
        RECIPES.put("cherry_fence", new RecipeSpec(3, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"cherry_planks", "stick", "cherry_planks", "cherry_planks", "stick", "cherry_planks"}));
        RECIPES.put("dark_oak_fence", new RecipeSpec(3, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"dark_oak_planks", "stick", "dark_oak_planks", "dark_oak_planks", "stick", "dark_oak_planks"}));
        RECIPES.put("pale_oak_fence", new RecipeSpec(3, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"pale_oak_planks", "stick", "pale_oak_planks", "pale_oak_planks", "stick", "pale_oak_planks"}));
        RECIPES.put("mangrove_fence", new RecipeSpec(3, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"mangrove_planks", "stick", "mangrove_planks", "mangrove_planks", "stick", "mangrove_planks"}));
        RECIPES.put("bamboo_fence", new RecipeSpec(3, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"bamboo_planks", "stick", "bamboo_planks", "bamboo_planks", "stick", "bamboo_planks"}));
        RECIPES.put("crimson_fence", new RecipeSpec(3, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"crimson_planks", "stick", "crimson_planks", "crimson_planks", "stick", "crimson_planks"}));
        RECIPES.put("warped_fence", new RecipeSpec(3, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"warped_planks", "stick", "warped_planks", "warped_planks", "stick", "warped_planks"}));
        RECIPES.put("jack_o_lantern", new RecipeSpec(1, new int[]{1, 4}, new String[]{"carved_pumpkin", "torch"}));
        RECIPES.put("polished_basalt", new RecipeSpec(4, new int[]{1, 2, 4, 5}, new String[]{"basalt", "basalt", "basalt", "basalt"}));
        RECIPES.put("soul_torch", new RecipeSpec(4, new int[]{1, 4, 7}, new String[]{"charcoal", "stick", "soul_soil"}));
        RECIPES.put("glowstone", new RecipeSpec(1, new int[]{1, 2, 4, 5}, new String[]{"glowstone_dust", "glowstone_dust", "glowstone_dust", "glowstone_dust"}));
        RECIPES.put("stone_bricks", new RecipeSpec(4, new int[]{1, 2, 4, 5}, new String[]{"stone", "stone", "stone", "stone"}));
        RECIPES.put("mossy_stone_bricks", new RecipeSpec(1, new int[]{1, 2}, new String[]{"stone_bricks", "moss_block"}));
        RECIPES.put("chiseled_stone_bricks", new RecipeSpec(1, new int[]{1, 4}, new String[]{"stone_brick_slab", "stone_brick_slab"}));
        RECIPES.put("packed_mud", new RecipeSpec(1, new int[]{1, 2}, new String[]{"mud", "wheat"}));
        RECIPES.put("mud_bricks", new RecipeSpec(4, new int[]{1, 2, 4, 5}, new String[]{"packed_mud", "packed_mud", "packed_mud", "packed_mud"}));
        RECIPES.put("deepslate_bricks", new RecipeSpec(4, new int[]{1, 2, 4, 5}, new String[]{"polished_deepslate", "polished_deepslate", "polished_deepslate", "polished_deepslate"}));
        RECIPES.put("deepslate_tiles", new RecipeSpec(4, new int[]{1, 2, 4, 5}, new String[]{"deepslate_bricks", "deepslate_bricks", "deepslate_bricks", "deepslate_bricks"}));
        RECIPES.put("chiseled_deepslate", new RecipeSpec(1, new int[]{1, 4}, new String[]{"cobbled_deepslate_slab", "cobbled_deepslate_slab"}));
        RECIPES.put("iron_bars", new RecipeSpec(16, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"iron_ingot", "iron_ingot", "iron_ingot", "iron_ingot", "iron_ingot", "iron_ingot"}));
        RECIPES.put("chain", new RecipeSpec(1, new int[]{1, 4, 7}, new String[]{"iron_nugget", "iron_ingot", "iron_nugget"}));
        RECIPES.put("glass_pane", new RecipeSpec(16, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"glass", "glass", "glass", "glass", "glass", "glass"}));
        RECIPES.put("melon", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"melon_slice", "melon_slice", "melon_slice", "melon_slice", "melon_slice", "melon_slice", "melon_slice", "melon_slice", "melon_slice"}));
        RECIPES.put("resin_clump", new RecipeSpec(9, new int[]{1}, new String[]{"resin_block"}));
        RECIPES.put("resin_block", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"resin_clump", "resin_clump", "resin_clump", "resin_clump", "resin_clump", "resin_clump", "resin_clump", "resin_clump", "resin_clump"}));
        RECIPES.put("resin_bricks", new RecipeSpec(1, new int[]{1, 2, 4, 5}, new String[]{"resin_brick", "resin_brick", "resin_brick", "resin_brick"}));
        RECIPES.put("resin_brick_stairs", new RecipeSpec(4, new int[]{1, 4, 5, 7, 8, 9}, new String[]{"resin_bricks", "resin_bricks", "resin_bricks", "resin_bricks", "resin_bricks", "resin_bricks"}));
        RECIPES.put("resin_brick_slab", new RecipeSpec(6, new int[]{1, 2, 3}, new String[]{"resin_bricks", "resin_bricks", "resin_bricks"}));
        RECIPES.put("resin_brick_wall", new RecipeSpec(6, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"resin_bricks", "resin_bricks", "resin_bricks", "resin_bricks", "resin_bricks", "resin_bricks"}));
        RECIPES.put("chiseled_resin_bricks", new RecipeSpec(1, new int[]{1, 4}, new String[]{"resin_brick_slab", "resin_brick_slab"}));
        RECIPES.put("brick_stairs", new RecipeSpec(4, new int[]{1, 4, 5, 7, 8, 9}, new String[]{"bricks", "bricks", "bricks", "bricks", "bricks", "bricks"}));
        RECIPES.put("stone_brick_stairs", new RecipeSpec(4, new int[]{1, 4, 5, 7, 8, 9}, new String[]{"stone_bricks", "stone_bricks", "stone_bricks", "stone_bricks", "stone_bricks", "stone_bricks"}));
        RECIPES.put("mud_brick_stairs", new RecipeSpec(4, new int[]{1, 4, 5, 7, 8, 9}, new String[]{"mud_bricks", "mud_bricks", "mud_bricks", "mud_bricks", "mud_bricks", "mud_bricks"}));
        RECIPES.put("nether_bricks", new RecipeSpec(1, new int[]{1, 2, 4, 5}, new String[]{"nether_brick", "nether_brick", "nether_brick", "nether_brick"}));
        RECIPES.put("chiseled_nether_bricks", new RecipeSpec(1, new int[]{1, 4}, new String[]{"nether_brick_slab", "nether_brick_slab"}));
        RECIPES.put("nether_brick_fence", new RecipeSpec(6, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"nether_bricks", "nether_brick", "nether_bricks", "nether_bricks", "nether_brick", "nether_bricks"}));
        RECIPES.put("nether_brick_stairs", new RecipeSpec(4, new int[]{1, 4, 5, 7, 8, 9}, new String[]{"nether_bricks", "nether_bricks", "nether_bricks", "nether_bricks", "nether_bricks", "nether_bricks"}));
        RECIPES.put("enchanting_table", new RecipeSpec(1, new int[]{2, 4, 5, 6, 7, 8, 9}, new String[]{"book", "diamond", "obsidian", "diamond", "obsidian", "obsidian", "obsidian"}));
        RECIPES.put("end_stone_bricks", new RecipeSpec(4, new int[]{1, 2, 4, 5}, new String[]{"end_stone", "end_stone", "end_stone", "end_stone"}));
        RECIPES.put("sandstone_stairs", new RecipeSpec(4, new int[]{1, 4, 5, 7, 8, 9}, new String[]{"cut_sandstone", "cut_sandstone", "cut_sandstone", "cut_sandstone", "cut_sandstone", "cut_sandstone"}));
        RECIPES.put("ender_chest", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"obsidian", "obsidian", "obsidian", "obsidian", "ender_eye", "obsidian", "obsidian", "obsidian", "obsidian"}));
        RECIPES.put("emerald_block", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"emerald", "emerald", "emerald", "emerald", "emerald", "emerald", "emerald", "emerald", "emerald"}));
        RECIPES.put("oak_stairs", new RecipeSpec(4, new int[]{1, 4, 5, 7, 8, 9}, new String[]{"oak_planks", "oak_planks", "oak_planks", "oak_planks", "oak_planks", "oak_planks"}));
        RECIPES.put("spruce_stairs", new RecipeSpec(4, new int[]{1, 4, 5, 7, 8, 9}, new String[]{"spruce_planks", "spruce_planks", "spruce_planks", "spruce_planks", "spruce_planks", "spruce_planks"}));
        RECIPES.put("birch_stairs", new RecipeSpec(4, new int[]{1, 4, 5, 7, 8, 9}, new String[]{"birch_planks", "birch_planks", "birch_planks", "birch_planks", "birch_planks", "birch_planks"}));
        RECIPES.put("jungle_stairs", new RecipeSpec(4, new int[]{1, 4, 5, 7, 8, 9}, new String[]{"jungle_planks", "jungle_planks", "jungle_planks", "jungle_planks", "jungle_planks", "jungle_planks"}));
        RECIPES.put("acacia_stairs", new RecipeSpec(4, new int[]{1, 4, 5, 7, 8, 9}, new String[]{"acacia_planks", "acacia_planks", "acacia_planks", "acacia_planks", "acacia_planks", "acacia_planks"}));
        RECIPES.put("cherry_stairs", new RecipeSpec(4, new int[]{1, 4, 5, 7, 8, 9}, new String[]{"cherry_planks", "cherry_planks", "cherry_planks", "cherry_planks", "cherry_planks", "cherry_planks"}));
        RECIPES.put("dark_oak_stairs", new RecipeSpec(4, new int[]{1, 4, 5, 7, 8, 9}, new String[]{"dark_oak_planks", "dark_oak_planks", "dark_oak_planks", "dark_oak_planks", "dark_oak_planks", "dark_oak_planks"}));
        RECIPES.put("pale_oak_stairs", new RecipeSpec(4, new int[]{1, 4, 5, 7, 8, 9}, new String[]{"pale_oak_planks", "pale_oak_planks", "pale_oak_planks", "pale_oak_planks", "pale_oak_planks", "pale_oak_planks"}));
        RECIPES.put("mangrove_stairs", new RecipeSpec(4, new int[]{1, 4, 5, 7, 8, 9}, new String[]{"mangrove_planks", "mangrove_planks", "mangrove_planks", "mangrove_planks", "mangrove_planks", "mangrove_planks"}));
        RECIPES.put("bamboo_stairs", new RecipeSpec(4, new int[]{1, 4, 5, 7, 8, 9}, new String[]{"bamboo_planks", "bamboo_planks", "bamboo_planks", "bamboo_planks", "bamboo_planks", "bamboo_planks"}));
        RECIPES.put("bamboo_mosaic_stairs", new RecipeSpec(4, new int[]{1, 4, 5, 7, 8, 9}, new String[]{"bamboo_mosaic", "bamboo_mosaic", "bamboo_mosaic", "bamboo_mosaic", "bamboo_mosaic", "bamboo_mosaic"}));
        RECIPES.put("crimson_stairs", new RecipeSpec(4, new int[]{1, 4, 5, 7, 8, 9}, new String[]{"crimson_planks", "crimson_planks", "crimson_planks", "crimson_planks", "crimson_planks", "crimson_planks"}));
        RECIPES.put("warped_stairs", new RecipeSpec(4, new int[]{1, 4, 5, 7, 8, 9}, new String[]{"warped_planks", "warped_planks", "warped_planks", "warped_planks", "warped_planks", "warped_planks"}));
        RECIPES.put("beacon", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"glass", "glass", "glass", "glass", "nether_star", "glass", "obsidian", "obsidian", "obsidian"}));
        RECIPES.put("cobblestone_wall", new RecipeSpec(6, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"cobblestone", "cobblestone", "cobblestone", "cobblestone", "cobblestone", "cobblestone"}));
        RECIPES.put("mossy_cobblestone_wall", new RecipeSpec(6, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"mossy_cobblestone", "mossy_cobblestone", "mossy_cobblestone", "mossy_cobblestone", "mossy_cobblestone", "mossy_cobblestone"}));
        RECIPES.put("brick_wall", new RecipeSpec(6, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"bricks", "bricks", "bricks", "bricks", "bricks", "bricks"}));
        RECIPES.put("prismarine_wall", new RecipeSpec(6, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"prismarine", "prismarine", "prismarine", "prismarine", "prismarine", "prismarine"}));
        RECIPES.put("red_sandstone_wall", new RecipeSpec(6, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"red_sandstone", "red_sandstone", "red_sandstone", "red_sandstone", "red_sandstone", "red_sandstone"}));
        RECIPES.put("mossy_stone_brick_wall", new RecipeSpec(6, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"mossy_stone_bricks", "mossy_stone_bricks", "mossy_stone_bricks", "mossy_stone_bricks", "mossy_stone_bricks", "mossy_stone_bricks"}));
        RECIPES.put("granite_wall", new RecipeSpec(6, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"granite", "granite", "granite", "granite", "granite", "granite"}));
        RECIPES.put("stone_brick_wall", new RecipeSpec(6, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"stone_bricks", "stone_bricks", "stone_bricks", "stone_bricks", "stone_bricks", "stone_bricks"}));
        RECIPES.put("mud_brick_wall", new RecipeSpec(6, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"mud_bricks", "mud_bricks", "mud_bricks", "mud_bricks", "mud_bricks", "mud_bricks"}));
        RECIPES.put("nether_brick_wall", new RecipeSpec(6, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"nether_bricks", "nether_bricks", "nether_bricks", "nether_bricks", "nether_bricks", "nether_bricks"}));
        RECIPES.put("andesite_wall", new RecipeSpec(6, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"andesite", "andesite", "andesite", "andesite", "andesite", "andesite"}));
        RECIPES.put("red_nether_brick_wall", new RecipeSpec(6, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"red_nether_bricks", "red_nether_bricks", "red_nether_bricks", "red_nether_bricks", "red_nether_bricks", "red_nether_bricks"}));
        RECIPES.put("sandstone_wall", new RecipeSpec(6, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"sandstone", "sandstone", "sandstone", "sandstone", "sandstone", "sandstone"}));
        RECIPES.put("end_stone_brick_wall", new RecipeSpec(6, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"end_stone_bricks", "end_stone_bricks", "end_stone_bricks", "end_stone_bricks", "end_stone_bricks", "end_stone_bricks"}));
        RECIPES.put("diorite_wall", new RecipeSpec(6, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"diorite", "diorite", "diorite", "diorite", "diorite", "diorite"}));
        RECIPES.put("blackstone_wall", new RecipeSpec(6, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"blackstone", "blackstone", "blackstone", "blackstone", "blackstone", "blackstone"}));
        RECIPES.put("polished_blackstone_wall", new RecipeSpec(6, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"polished_blackstone", "polished_blackstone", "polished_blackstone", "polished_blackstone", "polished_blackstone", "polished_blackstone"}));
        RECIPES.put("polished_blackstone_brick_wall", new RecipeSpec(6, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"polished_blackstone_bricks", "polished_blackstone_bricks", "polished_blackstone_bricks", "polished_blackstone_bricks", "polished_blackstone_bricks", "polished_blackstone_bricks"}));
        RECIPES.put("cobbled_deepslate_wall", new RecipeSpec(6, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"cobbled_deepslate", "cobbled_deepslate", "cobbled_deepslate", "cobbled_deepslate", "cobbled_deepslate", "cobbled_deepslate"}));
        RECIPES.put("polished_deepslate_wall", new RecipeSpec(6, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"polished_deepslate", "polished_deepslate", "polished_deepslate", "polished_deepslate", "polished_deepslate", "polished_deepslate"}));
        RECIPES.put("deepslate_brick_wall", new RecipeSpec(6, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"deepslate_bricks", "deepslate_bricks", "deepslate_bricks", "deepslate_bricks", "deepslate_bricks", "deepslate_bricks"}));
        RECIPES.put("deepslate_tile_wall", new RecipeSpec(6, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"deepslate_tiles", "deepslate_tiles", "deepslate_tiles", "deepslate_tiles", "deepslate_tiles", "deepslate_tiles"}));
        RECIPES.put("anvil", new RecipeSpec(1, new int[]{1, 2, 3, 5, 7, 8, 9}, new String[]{"iron_block", "iron_block", "iron_block", "iron_ingot", "iron_ingot", "iron_ingot", "iron_ingot"}));
        RECIPES.put("chiseled_quartz_block", new RecipeSpec(1, new int[]{1, 4}, new String[]{"quartz_slab", "quartz_slab"}));
        RECIPES.put("quartz_block", new RecipeSpec(1, new int[]{1, 2, 4, 5}, new String[]{"quartz", "quartz", "quartz", "quartz"}));
        RECIPES.put("quartz_bricks", new RecipeSpec(4, new int[]{1, 2, 4, 5}, new String[]{"quartz_block", "quartz_block", "quartz_block", "quartz_block"}));
        RECIPES.put("quartz_pillar", new RecipeSpec(2, new int[]{1, 4}, new String[]{"quartz_block", "quartz_block"}));
        RECIPES.put("quartz_stairs", new RecipeSpec(4, new int[]{1, 4, 5, 7, 8, 9}, new String[]{"quartz_pillar", "quartz_pillar", "quartz_pillar", "quartz_pillar", "quartz_pillar", "quartz_pillar"}));
        RECIPES.put("white_terracotta", new RecipeSpec(8, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"terracotta", "terracotta", "terracotta", "terracotta", "white_dye", "terracotta", "terracotta", "terracotta", "terracotta"}));
        RECIPES.put("orange_terracotta", new RecipeSpec(8, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"terracotta", "terracotta", "terracotta", "terracotta", "orange_dye", "terracotta", "terracotta", "terracotta", "terracotta"}));
        RECIPES.put("magenta_terracotta", new RecipeSpec(8, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"terracotta", "terracotta", "terracotta", "terracotta", "magenta_dye", "terracotta", "terracotta", "terracotta", "terracotta"}));
        RECIPES.put("light_blue_terracotta", new RecipeSpec(8, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"terracotta", "terracotta", "terracotta", "terracotta", "light_blue_dye", "terracotta", "terracotta", "terracotta", "terracotta"}));
        RECIPES.put("yellow_terracotta", new RecipeSpec(8, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"terracotta", "terracotta", "terracotta", "terracotta", "yellow_dye", "terracotta", "terracotta", "terracotta", "terracotta"}));
        RECIPES.put("lime_terracotta", new RecipeSpec(8, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"terracotta", "terracotta", "terracotta", "terracotta", "lime_dye", "terracotta", "terracotta", "terracotta", "terracotta"}));
        RECIPES.put("pink_terracotta", new RecipeSpec(8, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"terracotta", "terracotta", "terracotta", "terracotta", "pink_dye", "terracotta", "terracotta", "terracotta", "terracotta"}));
        RECIPES.put("gray_terracotta", new RecipeSpec(8, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"terracotta", "terracotta", "terracotta", "terracotta", "gray_dye", "terracotta", "terracotta", "terracotta", "terracotta"}));
        RECIPES.put("light_gray_terracotta", new RecipeSpec(8, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"terracotta", "terracotta", "terracotta", "terracotta", "light_gray_dye", "terracotta", "terracotta", "terracotta", "terracotta"}));
        RECIPES.put("cyan_terracotta", new RecipeSpec(8, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"terracotta", "terracotta", "terracotta", "terracotta", "cyan_dye", "terracotta", "terracotta", "terracotta", "terracotta"}));
        RECIPES.put("purple_terracotta", new RecipeSpec(8, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"terracotta", "terracotta", "terracotta", "terracotta", "purple_dye", "terracotta", "terracotta", "terracotta", "terracotta"}));
        RECIPES.put("blue_terracotta", new RecipeSpec(8, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"terracotta", "terracotta", "terracotta", "terracotta", "blue_dye", "terracotta", "terracotta", "terracotta", "terracotta"}));
        RECIPES.put("brown_terracotta", new RecipeSpec(8, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"terracotta", "terracotta", "terracotta", "terracotta", "brown_dye", "terracotta", "terracotta", "terracotta", "terracotta"}));
        RECIPES.put("green_terracotta", new RecipeSpec(8, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"terracotta", "terracotta", "terracotta", "terracotta", "green_dye", "terracotta", "terracotta", "terracotta", "terracotta"}));
        RECIPES.put("red_terracotta", new RecipeSpec(8, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"terracotta", "terracotta", "terracotta", "terracotta", "red_dye", "terracotta", "terracotta", "terracotta", "terracotta"}));
        RECIPES.put("black_terracotta", new RecipeSpec(8, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"terracotta", "terracotta", "terracotta", "terracotta", "black_dye", "terracotta", "terracotta", "terracotta", "terracotta"}));
        RECIPES.put("hay_block", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"wheat", "wheat", "wheat", "wheat", "wheat", "wheat", "wheat", "wheat", "wheat"}));
        RECIPES.put("white_carpet", new RecipeSpec(3, new int[]{1, 2}, new String[]{"white_wool", "white_wool"}));
        RECIPES.put("orange_carpet", new RecipeSpec(3, new int[]{1, 2}, new String[]{"orange_wool", "orange_wool"}));
        RECIPES.put("magenta_carpet", new RecipeSpec(3, new int[]{1, 2}, new String[]{"magenta_wool", "magenta_wool"}));
        RECIPES.put("light_blue_carpet", new RecipeSpec(3, new int[]{1, 2}, new String[]{"light_blue_wool", "light_blue_wool"}));
        RECIPES.put("yellow_carpet", new RecipeSpec(3, new int[]{1, 2}, new String[]{"yellow_wool", "yellow_wool"}));
        RECIPES.put("lime_carpet", new RecipeSpec(3, new int[]{1, 2}, new String[]{"lime_wool", "lime_wool"}));
        RECIPES.put("pink_carpet", new RecipeSpec(3, new int[]{1, 2}, new String[]{"pink_wool", "pink_wool"}));
        RECIPES.put("gray_carpet", new RecipeSpec(3, new int[]{1, 2}, new String[]{"gray_wool", "gray_wool"}));
        RECIPES.put("light_gray_carpet", new RecipeSpec(3, new int[]{1, 2}, new String[]{"light_gray_wool", "light_gray_wool"}));
        RECIPES.put("cyan_carpet", new RecipeSpec(3, new int[]{1, 2}, new String[]{"cyan_wool", "cyan_wool"}));
        RECIPES.put("purple_carpet", new RecipeSpec(3, new int[]{1, 2}, new String[]{"purple_wool", "purple_wool"}));
        RECIPES.put("blue_carpet", new RecipeSpec(3, new int[]{1, 2}, new String[]{"blue_wool", "blue_wool"}));
        RECIPES.put("brown_carpet", new RecipeSpec(3, new int[]{1, 2}, new String[]{"brown_wool", "brown_wool"}));
        RECIPES.put("green_carpet", new RecipeSpec(3, new int[]{1, 2}, new String[]{"green_wool", "green_wool"}));
        RECIPES.put("red_carpet", new RecipeSpec(3, new int[]{1, 2}, new String[]{"red_wool", "red_wool"}));
        RECIPES.put("black_carpet", new RecipeSpec(3, new int[]{1, 2}, new String[]{"black_wool", "black_wool"}));
        RECIPES.put("packed_ice", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"ice", "ice", "ice", "ice", "ice", "ice", "ice", "ice", "ice"}));
        RECIPES.put("white_stained_glass", new RecipeSpec(8, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"glass", "glass", "glass", "glass", "white_dye", "glass", "glass", "glass", "glass"}));
        RECIPES.put("orange_stained_glass", new RecipeSpec(8, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"glass", "glass", "glass", "glass", "orange_dye", "glass", "glass", "glass", "glass"}));
        RECIPES.put("magenta_stained_glass", new RecipeSpec(8, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"glass", "glass", "glass", "glass", "magenta_dye", "glass", "glass", "glass", "glass"}));
        RECIPES.put("light_blue_stained_glass", new RecipeSpec(8, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"glass", "glass", "glass", "glass", "light_blue_dye", "glass", "glass", "glass", "glass"}));
        RECIPES.put("yellow_stained_glass", new RecipeSpec(8, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"glass", "glass", "glass", "glass", "yellow_dye", "glass", "glass", "glass", "glass"}));
        RECIPES.put("lime_stained_glass", new RecipeSpec(8, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"glass", "glass", "glass", "glass", "lime_dye", "glass", "glass", "glass", "glass"}));
        RECIPES.put("pink_stained_glass", new RecipeSpec(8, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"glass", "glass", "glass", "glass", "pink_dye", "glass", "glass", "glass", "glass"}));
        RECIPES.put("gray_stained_glass", new RecipeSpec(8, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"glass", "glass", "glass", "glass", "gray_dye", "glass", "glass", "glass", "glass"}));
        RECIPES.put("light_gray_stained_glass", new RecipeSpec(8, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"glass", "glass", "glass", "glass", "light_gray_dye", "glass", "glass", "glass", "glass"}));
        RECIPES.put("cyan_stained_glass", new RecipeSpec(8, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"glass", "glass", "glass", "glass", "cyan_dye", "glass", "glass", "glass", "glass"}));
        RECIPES.put("purple_stained_glass", new RecipeSpec(8, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"glass", "glass", "glass", "glass", "purple_dye", "glass", "glass", "glass", "glass"}));
        RECIPES.put("blue_stained_glass", new RecipeSpec(8, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"glass", "glass", "glass", "glass", "blue_dye", "glass", "glass", "glass", "glass"}));
        RECIPES.put("brown_stained_glass", new RecipeSpec(8, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"glass", "glass", "glass", "glass", "brown_dye", "glass", "glass", "glass", "glass"}));
        RECIPES.put("green_stained_glass", new RecipeSpec(8, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"glass", "glass", "glass", "glass", "green_dye", "glass", "glass", "glass", "glass"}));
        RECIPES.put("red_stained_glass", new RecipeSpec(8, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"glass", "glass", "glass", "glass", "red_dye", "glass", "glass", "glass", "glass"}));
        RECIPES.put("black_stained_glass", new RecipeSpec(8, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"glass", "glass", "glass", "glass", "black_dye", "glass", "glass", "glass", "glass"}));
        RECIPES.put("white_stained_glass_pane", new RecipeSpec(16, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"white_stained_glass", "white_stained_glass", "white_stained_glass", "white_stained_glass", "white_stained_glass", "white_stained_glass"}));
        RECIPES.put("orange_stained_glass_pane", new RecipeSpec(16, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"orange_stained_glass", "orange_stained_glass", "orange_stained_glass", "orange_stained_glass", "orange_stained_glass", "orange_stained_glass"}));
        RECIPES.put("magenta_stained_glass_pane", new RecipeSpec(16, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"magenta_stained_glass", "magenta_stained_glass", "magenta_stained_glass", "magenta_stained_glass", "magenta_stained_glass", "magenta_stained_glass"}));
        RECIPES.put("light_blue_stained_glass_pane", new RecipeSpec(16, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"light_blue_stained_glass", "light_blue_stained_glass", "light_blue_stained_glass", "light_blue_stained_glass", "light_blue_stained_glass", "light_blue_stained_glass"}));
        RECIPES.put("yellow_stained_glass_pane", new RecipeSpec(16, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"yellow_stained_glass", "yellow_stained_glass", "yellow_stained_glass", "yellow_stained_glass", "yellow_stained_glass", "yellow_stained_glass"}));
        RECIPES.put("lime_stained_glass_pane", new RecipeSpec(16, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"lime_stained_glass", "lime_stained_glass", "lime_stained_glass", "lime_stained_glass", "lime_stained_glass", "lime_stained_glass"}));
        RECIPES.put("pink_stained_glass_pane", new RecipeSpec(16, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"pink_stained_glass", "pink_stained_glass", "pink_stained_glass", "pink_stained_glass", "pink_stained_glass", "pink_stained_glass"}));
        RECIPES.put("gray_stained_glass_pane", new RecipeSpec(16, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"gray_stained_glass", "gray_stained_glass", "gray_stained_glass", "gray_stained_glass", "gray_stained_glass", "gray_stained_glass"}));
        RECIPES.put("light_gray_stained_glass_pane", new RecipeSpec(16, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"light_gray_stained_glass", "light_gray_stained_glass", "light_gray_stained_glass", "light_gray_stained_glass", "light_gray_stained_glass", "light_gray_stained_glass"}));
        RECIPES.put("cyan_stained_glass_pane", new RecipeSpec(16, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"cyan_stained_glass", "cyan_stained_glass", "cyan_stained_glass", "cyan_stained_glass", "cyan_stained_glass", "cyan_stained_glass"}));
        RECIPES.put("purple_stained_glass_pane", new RecipeSpec(16, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"purple_stained_glass", "purple_stained_glass", "purple_stained_glass", "purple_stained_glass", "purple_stained_glass", "purple_stained_glass"}));
        RECIPES.put("blue_stained_glass_pane", new RecipeSpec(16, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"blue_stained_glass", "blue_stained_glass", "blue_stained_glass", "blue_stained_glass", "blue_stained_glass", "blue_stained_glass"}));
        RECIPES.put("brown_stained_glass_pane", new RecipeSpec(16, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"brown_stained_glass", "brown_stained_glass", "brown_stained_glass", "brown_stained_glass", "brown_stained_glass", "brown_stained_glass"}));
        RECIPES.put("green_stained_glass_pane", new RecipeSpec(16, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"green_stained_glass", "green_stained_glass", "green_stained_glass", "green_stained_glass", "green_stained_glass", "green_stained_glass"}));
        RECIPES.put("red_stained_glass_pane", new RecipeSpec(16, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"red_stained_glass", "red_stained_glass", "red_stained_glass", "red_stained_glass", "red_stained_glass", "red_stained_glass"}));
        RECIPES.put("black_stained_glass_pane", new RecipeSpec(16, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"black_stained_glass", "black_stained_glass", "black_stained_glass", "black_stained_glass", "black_stained_glass", "black_stained_glass"}));
        RECIPES.put("prismarine", new RecipeSpec(1, new int[]{1, 2, 4, 5}, new String[]{"prismarine_shard", "prismarine_shard", "prismarine_shard", "prismarine_shard"}));
        RECIPES.put("prismarine_bricks", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"prismarine_shard", "prismarine_shard", "prismarine_shard", "prismarine_shard", "prismarine_shard", "prismarine_shard", "prismarine_shard", "prismarine_shard", "prismarine_shard"}));
        RECIPES.put("dark_prismarine", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"prismarine_shard", "prismarine_shard", "prismarine_shard", "prismarine_shard", "black_dye", "prismarine_shard", "prismarine_shard", "prismarine_shard", "prismarine_shard"}));
        RECIPES.put("prismarine_stairs", new RecipeSpec(4, new int[]{1, 4, 5, 7, 8, 9}, new String[]{"prismarine", "prismarine", "prismarine", "prismarine", "prismarine", "prismarine"}));
        RECIPES.put("prismarine_brick_stairs", new RecipeSpec(4, new int[]{1, 4, 5, 7, 8, 9}, new String[]{"prismarine_bricks", "prismarine_bricks", "prismarine_bricks", "prismarine_bricks", "prismarine_bricks", "prismarine_bricks"}));
        RECIPES.put("dark_prismarine_stairs", new RecipeSpec(4, new int[]{1, 4, 5, 7, 8, 9}, new String[]{"dark_prismarine", "dark_prismarine", "dark_prismarine", "dark_prismarine", "dark_prismarine", "dark_prismarine"}));
        RECIPES.put("sea_lantern", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"prismarine_shard", "prismarine_crystals", "prismarine_shard", "prismarine_crystals", "prismarine_crystals", "prismarine_crystals", "prismarine_shard", "prismarine_crystals", "prismarine_shard"}));
        RECIPES.put("red_sandstone", new RecipeSpec(1, new int[]{1, 2, 4, 5}, new String[]{"red_sand", "red_sand", "red_sand", "red_sand"}));
        RECIPES.put("chiseled_red_sandstone", new RecipeSpec(1, new int[]{1, 4}, new String[]{"red_sandstone_slab", "red_sandstone_slab"}));
        RECIPES.put("cut_red_sandstone", new RecipeSpec(4, new int[]{1, 2, 4, 5}, new String[]{"red_sandstone", "red_sandstone", "red_sandstone", "red_sandstone"}));
        RECIPES.put("red_sandstone_stairs", new RecipeSpec(4, new int[]{1, 4, 5, 7, 8, 9}, new String[]{"cut_red_sandstone", "cut_red_sandstone", "cut_red_sandstone", "cut_red_sandstone", "cut_red_sandstone", "cut_red_sandstone"}));
        RECIPES.put("magma_block", new RecipeSpec(1, new int[]{1, 2, 4, 5}, new String[]{"magma_cream", "magma_cream", "magma_cream", "magma_cream"}));
        RECIPES.put("nether_wart_block", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"nether_wart", "nether_wart", "nether_wart", "nether_wart", "nether_wart", "nether_wart", "nether_wart", "nether_wart", "nether_wart"}));
        RECIPES.put("red_nether_bricks", new RecipeSpec(1, new int[]{1, 2, 4, 5}, new String[]{"nether_brick", "nether_wart", "nether_wart", "nether_brick"}));
        RECIPES.put("bone_block", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"bone_meal", "bone_meal", "bone_meal", "bone_meal", "bone_meal", "bone_meal", "bone_meal", "bone_meal", "bone_meal"}));
        RECIPES.put("shulker_box", new RecipeSpec(1, new int[]{1, 4, 7}, new String[]{"shulker_shell", "chest", "shulker_shell"}));
        RECIPES.put("white_concrete_powder", new RecipeSpec(8, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"white_dye", "sand", "sand", "sand", "sand", "gravel", "gravel", "gravel", "gravel"}));
        RECIPES.put("orange_concrete_powder", new RecipeSpec(8, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"orange_dye", "sand", "sand", "sand", "sand", "gravel", "gravel", "gravel", "gravel"}));
        RECIPES.put("magenta_concrete_powder", new RecipeSpec(8, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"magenta_dye", "sand", "sand", "sand", "sand", "gravel", "gravel", "gravel", "gravel"}));
        RECIPES.put("light_blue_concrete_powder", new RecipeSpec(8, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"light_blue_dye", "sand", "sand", "sand", "sand", "gravel", "gravel", "gravel", "gravel"}));
        RECIPES.put("yellow_concrete_powder", new RecipeSpec(8, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"yellow_dye", "sand", "sand", "sand", "sand", "gravel", "gravel", "gravel", "gravel"}));
        RECIPES.put("lime_concrete_powder", new RecipeSpec(8, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"lime_dye", "sand", "sand", "sand", "sand", "gravel", "gravel", "gravel", "gravel"}));
        RECIPES.put("pink_concrete_powder", new RecipeSpec(8, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"pink_dye", "sand", "sand", "sand", "sand", "gravel", "gravel", "gravel", "gravel"}));
        RECIPES.put("gray_concrete_powder", new RecipeSpec(8, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"gray_dye", "sand", "sand", "sand", "sand", "gravel", "gravel", "gravel", "gravel"}));
        RECIPES.put("light_gray_concrete_powder", new RecipeSpec(8, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"light_gray_dye", "sand", "sand", "sand", "sand", "gravel", "gravel", "gravel", "gravel"}));
        RECIPES.put("cyan_concrete_powder", new RecipeSpec(8, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"cyan_dye", "sand", "sand", "sand", "sand", "gravel", "gravel", "gravel", "gravel"}));
        RECIPES.put("purple_concrete_powder", new RecipeSpec(8, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"purple_dye", "sand", "sand", "sand", "sand", "gravel", "gravel", "gravel", "gravel"}));
        RECIPES.put("blue_concrete_powder", new RecipeSpec(8, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"blue_dye", "sand", "sand", "sand", "sand", "gravel", "gravel", "gravel", "gravel"}));
        RECIPES.put("brown_concrete_powder", new RecipeSpec(8, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"brown_dye", "sand", "sand", "sand", "sand", "gravel", "gravel", "gravel", "gravel"}));
        RECIPES.put("green_concrete_powder", new RecipeSpec(8, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"green_dye", "sand", "sand", "sand", "sand", "gravel", "gravel", "gravel", "gravel"}));
        RECIPES.put("red_concrete_powder", new RecipeSpec(8, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"red_dye", "sand", "sand", "sand", "sand", "gravel", "gravel", "gravel", "gravel"}));
        RECIPES.put("black_concrete_powder", new RecipeSpec(8, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"black_dye", "sand", "sand", "sand", "sand", "gravel", "gravel", "gravel", "gravel"}));
        RECIPES.put("dried_ghast", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"ghast_tear", "ghast_tear", "ghast_tear", "ghast_tear", "soul_sand", "ghast_tear", "ghast_tear", "ghast_tear", "ghast_tear"}));
        RECIPES.put("blue_ice", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"packed_ice", "packed_ice", "packed_ice", "packed_ice", "packed_ice", "packed_ice", "packed_ice", "packed_ice", "packed_ice"}));
        RECIPES.put("conduit", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"nautilus_shell", "nautilus_shell", "nautilus_shell", "nautilus_shell", "heart_of_the_sea", "nautilus_shell", "nautilus_shell", "nautilus_shell", "nautilus_shell"}));
        RECIPES.put("polished_granite_stairs", new RecipeSpec(4, new int[]{1, 4, 5, 7, 8, 9}, new String[]{"polished_granite", "polished_granite", "polished_granite", "polished_granite", "polished_granite", "polished_granite"}));
        RECIPES.put("smooth_red_sandstone_stairs", new RecipeSpec(4, new int[]{1, 4, 5, 7, 8, 9}, new String[]{"smooth_red_sandstone", "smooth_red_sandstone", "smooth_red_sandstone", "smooth_red_sandstone", "smooth_red_sandstone", "smooth_red_sandstone"}));
        RECIPES.put("mossy_stone_brick_stairs", new RecipeSpec(4, new int[]{1, 4, 5, 7, 8, 9}, new String[]{"mossy_stone_bricks", "mossy_stone_bricks", "mossy_stone_bricks", "mossy_stone_bricks", "mossy_stone_bricks", "mossy_stone_bricks"}));
        RECIPES.put("polished_diorite_stairs", new RecipeSpec(4, new int[]{1, 4, 5, 7, 8, 9}, new String[]{"polished_diorite", "polished_diorite", "polished_diorite", "polished_diorite", "polished_diorite", "polished_diorite"}));
        RECIPES.put("mossy_cobblestone_stairs", new RecipeSpec(4, new int[]{1, 4, 5, 7, 8, 9}, new String[]{"mossy_cobblestone", "mossy_cobblestone", "mossy_cobblestone", "mossy_cobblestone", "mossy_cobblestone", "mossy_cobblestone"}));
        RECIPES.put("end_stone_brick_stairs", new RecipeSpec(4, new int[]{1, 4, 5, 7, 8, 9}, new String[]{"end_stone_bricks", "end_stone_bricks", "end_stone_bricks", "end_stone_bricks", "end_stone_bricks", "end_stone_bricks"}));
        RECIPES.put("stone_stairs", new RecipeSpec(4, new int[]{1, 4, 5, 7, 8, 9}, new String[]{"stone", "stone", "stone", "stone", "stone", "stone"}));
        RECIPES.put("smooth_sandstone_stairs", new RecipeSpec(4, new int[]{1, 4, 5, 7, 8, 9}, new String[]{"smooth_sandstone", "smooth_sandstone", "smooth_sandstone", "smooth_sandstone", "smooth_sandstone", "smooth_sandstone"}));
        RECIPES.put("smooth_quartz_stairs", new RecipeSpec(4, new int[]{1, 4, 5, 7, 8, 9}, new String[]{"smooth_quartz", "smooth_quartz", "smooth_quartz", "smooth_quartz", "smooth_quartz", "smooth_quartz"}));
        RECIPES.put("granite_stairs", new RecipeSpec(4, new int[]{1, 4, 5, 7, 8, 9}, new String[]{"granite", "granite", "granite", "granite", "granite", "granite"}));
        RECIPES.put("andesite_stairs", new RecipeSpec(4, new int[]{1, 4, 5, 7, 8, 9}, new String[]{"andesite", "andesite", "andesite", "andesite", "andesite", "andesite"}));
        RECIPES.put("red_nether_brick_stairs", new RecipeSpec(4, new int[]{1, 4, 5, 7, 8, 9}, new String[]{"red_nether_bricks", "red_nether_bricks", "red_nether_bricks", "red_nether_bricks", "red_nether_bricks", "red_nether_bricks"}));
        RECIPES.put("polished_andesite_stairs", new RecipeSpec(4, new int[]{1, 4, 5, 7, 8, 9}, new String[]{"polished_andesite", "polished_andesite", "polished_andesite", "polished_andesite", "polished_andesite", "polished_andesite"}));
        RECIPES.put("diorite_stairs", new RecipeSpec(4, new int[]{1, 4, 5, 7, 8, 9}, new String[]{"diorite", "diorite", "diorite", "diorite", "diorite", "diorite"}));
        RECIPES.put("cobbled_deepslate_stairs", new RecipeSpec(4, new int[]{1, 4, 5, 7, 8, 9}, new String[]{"cobbled_deepslate", "cobbled_deepslate", "cobbled_deepslate", "cobbled_deepslate", "cobbled_deepslate", "cobbled_deepslate"}));
        RECIPES.put("polished_deepslate_stairs", new RecipeSpec(4, new int[]{1, 4, 5, 7, 8, 9}, new String[]{"polished_deepslate", "polished_deepslate", "polished_deepslate", "polished_deepslate", "polished_deepslate", "polished_deepslate"}));
        RECIPES.put("deepslate_brick_stairs", new RecipeSpec(4, new int[]{1, 4, 5, 7, 8, 9}, new String[]{"deepslate_bricks", "deepslate_bricks", "deepslate_bricks", "deepslate_bricks", "deepslate_bricks", "deepslate_bricks"}));
        RECIPES.put("deepslate_tile_stairs", new RecipeSpec(4, new int[]{1, 4, 5, 7, 8, 9}, new String[]{"deepslate_tiles", "deepslate_tiles", "deepslate_tiles", "deepslate_tiles", "deepslate_tiles", "deepslate_tiles"}));
        RECIPES.put("polished_granite_slab", new RecipeSpec(6, new int[]{1, 2, 3}, new String[]{"polished_granite", "polished_granite", "polished_granite"}));
        RECIPES.put("smooth_red_sandstone_slab", new RecipeSpec(6, new int[]{1, 2, 3}, new String[]{"smooth_red_sandstone", "smooth_red_sandstone", "smooth_red_sandstone"}));
        RECIPES.put("mossy_stone_brick_slab", new RecipeSpec(6, new int[]{1, 2, 3}, new String[]{"mossy_stone_bricks", "mossy_stone_bricks", "mossy_stone_bricks"}));
        RECIPES.put("polished_diorite_slab", new RecipeSpec(6, new int[]{1, 2, 3}, new String[]{"polished_diorite", "polished_diorite", "polished_diorite"}));
        RECIPES.put("mossy_cobblestone_slab", new RecipeSpec(6, new int[]{1, 2, 3}, new String[]{"mossy_cobblestone", "mossy_cobblestone", "mossy_cobblestone"}));
        RECIPES.put("end_stone_brick_slab", new RecipeSpec(6, new int[]{1, 2, 3}, new String[]{"end_stone_bricks", "end_stone_bricks", "end_stone_bricks"}));
        RECIPES.put("smooth_sandstone_slab", new RecipeSpec(6, new int[]{1, 2, 3}, new String[]{"smooth_sandstone", "smooth_sandstone", "smooth_sandstone"}));
        RECIPES.put("smooth_quartz_slab", new RecipeSpec(6, new int[]{1, 2, 3}, new String[]{"smooth_quartz", "smooth_quartz", "smooth_quartz"}));
        RECIPES.put("granite_slab", new RecipeSpec(6, new int[]{1, 2, 3}, new String[]{"granite", "granite", "granite"}));
        RECIPES.put("andesite_slab", new RecipeSpec(6, new int[]{1, 2, 3}, new String[]{"andesite", "andesite", "andesite"}));
        RECIPES.put("red_nether_brick_slab", new RecipeSpec(6, new int[]{1, 2, 3}, new String[]{"red_nether_bricks", "red_nether_bricks", "red_nether_bricks"}));
        RECIPES.put("polished_andesite_slab", new RecipeSpec(6, new int[]{1, 2, 3}, new String[]{"polished_andesite", "polished_andesite", "polished_andesite"}));
        RECIPES.put("diorite_slab", new RecipeSpec(6, new int[]{1, 2, 3}, new String[]{"diorite", "diorite", "diorite"}));
        RECIPES.put("cobbled_deepslate_slab", new RecipeSpec(6, new int[]{1, 2, 3}, new String[]{"cobbled_deepslate", "cobbled_deepslate", "cobbled_deepslate"}));
        RECIPES.put("polished_deepslate_slab", new RecipeSpec(6, new int[]{1, 2, 3}, new String[]{"polished_deepslate", "polished_deepslate", "polished_deepslate"}));
        RECIPES.put("deepslate_brick_slab", new RecipeSpec(6, new int[]{1, 2, 3}, new String[]{"deepslate_bricks", "deepslate_bricks", "deepslate_bricks"}));
        RECIPES.put("deepslate_tile_slab", new RecipeSpec(6, new int[]{1, 2, 3}, new String[]{"deepslate_tiles", "deepslate_tiles", "deepslate_tiles"}));
        RECIPES.put("scaffolding", new RecipeSpec(6, new int[]{1, 2, 3, 4, 6, 7, 9}, new String[]{"bamboo", "string", "bamboo", "bamboo", "bamboo", "bamboo", "bamboo"}));
        RECIPES.put("redstone", new RecipeSpec(9, new int[]{1}, new String[]{"redstone_block"}));
        RECIPES.put("redstone_torch", new RecipeSpec(1, new int[]{1, 4}, new String[]{"redstone", "stick"}));
        RECIPES.put("redstone_block", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"redstone", "redstone", "redstone", "redstone", "redstone", "redstone", "redstone", "redstone", "redstone"}));
        RECIPES.put("repeater", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"redstone_torch", "redstone", "redstone_torch", "stone", "stone", "stone"}));
        RECIPES.put("comparator", new RecipeSpec(1, new int[]{2, 4, 5, 6, 7, 8, 9}, new String[]{"redstone_torch", "redstone_torch", "quartz", "redstone_torch", "stone", "stone", "stone"}));
        RECIPES.put("piston", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"cherry_planks", "cherry_planks", "cherry_planks", "cobblestone", "iron_ingot", "cobblestone", "cobblestone", "redstone", "cobblestone"}));
        RECIPES.put("sticky_piston", new RecipeSpec(1, new int[]{1, 4}, new String[]{"slime_ball", "piston"}));
        RECIPES.put("slime_block", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"slime_ball", "slime_ball", "slime_ball", "slime_ball", "slime_ball", "slime_ball", "slime_ball", "slime_ball", "slime_ball"}));
        RECIPES.put("honey_block", new RecipeSpec(1, new int[]{1, 2, 4, 5}, new String[]{"honey_bottle", "honey_bottle", "honey_bottle", "honey_bottle"}));
        RECIPES.put("observer", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"cobblestone", "cobblestone", "cobblestone", "redstone", "redstone", "quartz", "cobblestone", "cobblestone", "cobblestone"}));
        RECIPES.put("hopper", new RecipeSpec(1, new int[]{1, 3, 4, 5, 6, 8}, new String[]{"iron_ingot", "iron_ingot", "iron_ingot", "chest", "iron_ingot", "iron_ingot"}));
        RECIPES.put("dispenser", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"cobblestone", "cobblestone", "cobblestone", "cobblestone", "bow", "cobblestone", "cobblestone", "redstone", "cobblestone"}));
        RECIPES.put("dropper", new RecipeSpec(1, new int[]{1, 2, 3, 4, 6, 7, 8, 9}, new String[]{"cobblestone", "cobblestone", "cobblestone", "cobblestone", "cobblestone", "cobblestone", "redstone", "cobblestone"}));
        RECIPES.put("lectern", new RecipeSpec(1, new int[]{1, 2, 3, 5, 8}, new String[]{"cherry_slab", "cherry_slab", "cherry_slab", "bookshelf", "cherry_slab"}));
        RECIPES.put("target", new RecipeSpec(1, new int[]{2, 4, 5, 6, 8}, new String[]{"redstone", "redstone", "hay_block", "redstone", "redstone"}));
        RECIPES.put("lever", new RecipeSpec(1, new int[]{1, 4}, new String[]{"stick", "cobblestone"}));
        RECIPES.put("lightning_rod", new RecipeSpec(1, new int[]{1, 4, 7}, new String[]{"copper_ingot", "copper_ingot", "copper_ingot"}));
        RECIPES.put("daylight_detector", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"glass", "glass", "glass", "quartz", "quartz", "quartz", "cherry_slab", "cherry_slab", "cherry_slab"}));
        RECIPES.put("calibrated_sculk_sensor", new RecipeSpec(1, new int[]{2, 4, 5, 6}, new String[]{"amethyst_shard", "amethyst_shard", "sculk_sensor", "amethyst_shard"}));
        RECIPES.put("tripwire_hook", new RecipeSpec(2, new int[]{1, 4, 7}, new String[]{"iron_ingot", "stick", "cherry_planks"}));
        RECIPES.put("trapped_chest", new RecipeSpec(1, new int[]{1, 2}, new String[]{"chest", "tripwire_hook"}));
        RECIPES.put("tnt", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"gunpowder", "red_sand", "gunpowder", "red_sand", "gunpowder", "red_sand", "gunpowder", "red_sand", "gunpowder"}));
        RECIPES.put("redstone_lamp", new RecipeSpec(1, new int[]{2, 4, 5, 6, 8}, new String[]{"redstone", "redstone", "glowstone", "redstone", "redstone"}));
        RECIPES.put("note_block", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"cherry_planks", "cherry_planks", "cherry_planks", "cherry_planks", "redstone", "cherry_planks", "cherry_planks", "cherry_planks", "cherry_planks"}));
        RECIPES.put("stone_button", new RecipeSpec(1, new int[]{1}, new String[]{"stone"}));
        RECIPES.put("polished_blackstone_button", new RecipeSpec(1, new int[]{1}, new String[]{"polished_blackstone"}));
        RECIPES.put("oak_button", new RecipeSpec(1, new int[]{1}, new String[]{"oak_planks"}));
        RECIPES.put("spruce_button", new RecipeSpec(1, new int[]{1}, new String[]{"spruce_planks"}));
        RECIPES.put("birch_button", new RecipeSpec(1, new int[]{1}, new String[]{"birch_planks"}));
        RECIPES.put("jungle_button", new RecipeSpec(1, new int[]{1}, new String[]{"jungle_planks"}));
        RECIPES.put("acacia_button", new RecipeSpec(1, new int[]{1}, new String[]{"acacia_planks"}));
        RECIPES.put("cherry_button", new RecipeSpec(1, new int[]{1}, new String[]{"cherry_planks"}));
        RECIPES.put("dark_oak_button", new RecipeSpec(1, new int[]{1}, new String[]{"dark_oak_planks"}));
        RECIPES.put("pale_oak_button", new RecipeSpec(1, new int[]{1}, new String[]{"pale_oak_planks"}));
        RECIPES.put("mangrove_button", new RecipeSpec(1, new int[]{1}, new String[]{"mangrove_planks"}));
        RECIPES.put("bamboo_button", new RecipeSpec(1, new int[]{1}, new String[]{"bamboo_planks"}));
        RECIPES.put("crimson_button", new RecipeSpec(1, new int[]{1}, new String[]{"crimson_planks"}));
        RECIPES.put("warped_button", new RecipeSpec(1, new int[]{1}, new String[]{"warped_planks"}));
        RECIPES.put("stone_pressure_plate", new RecipeSpec(1, new int[]{1, 2}, new String[]{"stone", "stone"}));
        RECIPES.put("polished_blackstone_pressure_plate", new RecipeSpec(1, new int[]{1, 2}, new String[]{"polished_blackstone", "polished_blackstone"}));
        RECIPES.put("light_weighted_pressure_plate", new RecipeSpec(1, new int[]{1, 2}, new String[]{"gold_ingot", "gold_ingot"}));
        RECIPES.put("heavy_weighted_pressure_plate", new RecipeSpec(1, new int[]{1, 2}, new String[]{"iron_ingot", "iron_ingot"}));
        RECIPES.put("oak_pressure_plate", new RecipeSpec(1, new int[]{1, 2}, new String[]{"oak_planks", "oak_planks"}));
        RECIPES.put("spruce_pressure_plate", new RecipeSpec(1, new int[]{1, 2}, new String[]{"spruce_planks", "spruce_planks"}));
        RECIPES.put("birch_pressure_plate", new RecipeSpec(1, new int[]{1, 2}, new String[]{"birch_planks", "birch_planks"}));
        RECIPES.put("jungle_pressure_plate", new RecipeSpec(1, new int[]{1, 2}, new String[]{"jungle_planks", "jungle_planks"}));
        RECIPES.put("acacia_pressure_plate", new RecipeSpec(1, new int[]{1, 2}, new String[]{"acacia_planks", "acacia_planks"}));
        RECIPES.put("cherry_pressure_plate", new RecipeSpec(1, new int[]{1, 2}, new String[]{"cherry_planks", "cherry_planks"}));
        RECIPES.put("dark_oak_pressure_plate", new RecipeSpec(1, new int[]{1, 2}, new String[]{"dark_oak_planks", "dark_oak_planks"}));
        RECIPES.put("pale_oak_pressure_plate", new RecipeSpec(1, new int[]{1, 2}, new String[]{"pale_oak_planks", "pale_oak_planks"}));
        RECIPES.put("mangrove_pressure_plate", new RecipeSpec(1, new int[]{1, 2}, new String[]{"mangrove_planks", "mangrove_planks"}));
        RECIPES.put("bamboo_pressure_plate", new RecipeSpec(1, new int[]{1, 2}, new String[]{"bamboo_planks", "bamboo_planks"}));
        RECIPES.put("crimson_pressure_plate", new RecipeSpec(1, new int[]{1, 2}, new String[]{"crimson_planks", "crimson_planks"}));
        RECIPES.put("warped_pressure_plate", new RecipeSpec(1, new int[]{1, 2}, new String[]{"warped_planks", "warped_planks"}));
        RECIPES.put("iron_door", new RecipeSpec(3, new int[]{1, 2, 4, 5, 7, 8}, new String[]{"iron_ingot", "iron_ingot", "iron_ingot", "iron_ingot", "iron_ingot", "iron_ingot"}));
        RECIPES.put("oak_door", new RecipeSpec(3, new int[]{1, 2, 4, 5, 7, 8}, new String[]{"oak_planks", "oak_planks", "oak_planks", "oak_planks", "oak_planks", "oak_planks"}));
        RECIPES.put("spruce_door", new RecipeSpec(3, new int[]{1, 2, 4, 5, 7, 8}, new String[]{"spruce_planks", "spruce_planks", "spruce_planks", "spruce_planks", "spruce_planks", "spruce_planks"}));
        RECIPES.put("birch_door", new RecipeSpec(3, new int[]{1, 2, 4, 5, 7, 8}, new String[]{"birch_planks", "birch_planks", "birch_planks", "birch_planks", "birch_planks", "birch_planks"}));
        RECIPES.put("jungle_door", new RecipeSpec(3, new int[]{1, 2, 4, 5, 7, 8}, new String[]{"jungle_planks", "jungle_planks", "jungle_planks", "jungle_planks", "jungle_planks", "jungle_planks"}));
        RECIPES.put("acacia_door", new RecipeSpec(3, new int[]{1, 2, 4, 5, 7, 8}, new String[]{"acacia_planks", "acacia_planks", "acacia_planks", "acacia_planks", "acacia_planks", "acacia_planks"}));
        RECIPES.put("cherry_door", new RecipeSpec(3, new int[]{1, 2, 4, 5, 7, 8}, new String[]{"cherry_planks", "cherry_planks", "cherry_planks", "cherry_planks", "cherry_planks", "cherry_planks"}));
        RECIPES.put("dark_oak_door", new RecipeSpec(3, new int[]{1, 2, 4, 5, 7, 8}, new String[]{"dark_oak_planks", "dark_oak_planks", "dark_oak_planks", "dark_oak_planks", "dark_oak_planks", "dark_oak_planks"}));
        RECIPES.put("pale_oak_door", new RecipeSpec(3, new int[]{1, 2, 4, 5, 7, 8}, new String[]{"pale_oak_planks", "pale_oak_planks", "pale_oak_planks", "pale_oak_planks", "pale_oak_planks", "pale_oak_planks"}));
        RECIPES.put("mangrove_door", new RecipeSpec(3, new int[]{1, 2, 4, 5, 7, 8}, new String[]{"mangrove_planks", "mangrove_planks", "mangrove_planks", "mangrove_planks", "mangrove_planks", "mangrove_planks"}));
        RECIPES.put("bamboo_door", new RecipeSpec(3, new int[]{1, 2, 4, 5, 7, 8}, new String[]{"bamboo_planks", "bamboo_planks", "bamboo_planks", "bamboo_planks", "bamboo_planks", "bamboo_planks"}));
        RECIPES.put("crimson_door", new RecipeSpec(3, new int[]{1, 2, 4, 5, 7, 8}, new String[]{"crimson_planks", "crimson_planks", "crimson_planks", "crimson_planks", "crimson_planks", "crimson_planks"}));
        RECIPES.put("warped_door", new RecipeSpec(3, new int[]{1, 2, 4, 5, 7, 8}, new String[]{"warped_planks", "warped_planks", "warped_planks", "warped_planks", "warped_planks", "warped_planks"}));
        RECIPES.put("copper_door", new RecipeSpec(3, new int[]{1, 2, 4, 5, 7, 8}, new String[]{"copper_ingot", "copper_ingot", "copper_ingot", "copper_ingot", "copper_ingot", "copper_ingot"}));
        RECIPES.put("waxed_copper_door", new RecipeSpec(1, new int[]{1, 2}, new String[]{"copper_door", "honeycomb"}));
        RECIPES.put("waxed_exposed_copper_door", new RecipeSpec(1, new int[]{1, 2}, new String[]{"exposed_copper_door", "honeycomb"}));
        RECIPES.put("waxed_weathered_copper_door", new RecipeSpec(1, new int[]{1, 2}, new String[]{"weathered_copper_door", "honeycomb"}));
        RECIPES.put("waxed_oxidized_copper_door", new RecipeSpec(1, new int[]{1, 2}, new String[]{"oxidized_copper_door", "honeycomb"}));
        RECIPES.put("iron_trapdoor", new RecipeSpec(1, new int[]{1, 2, 4, 5}, new String[]{"iron_ingot", "iron_ingot", "iron_ingot", "iron_ingot"}));
        RECIPES.put("oak_trapdoor", new RecipeSpec(2, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"oak_planks", "oak_planks", "oak_planks", "oak_planks", "oak_planks", "oak_planks"}));
        RECIPES.put("spruce_trapdoor", new RecipeSpec(2, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"spruce_planks", "spruce_planks", "spruce_planks", "spruce_planks", "spruce_planks", "spruce_planks"}));
        RECIPES.put("birch_trapdoor", new RecipeSpec(2, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"birch_planks", "birch_planks", "birch_planks", "birch_planks", "birch_planks", "birch_planks"}));
        RECIPES.put("jungle_trapdoor", new RecipeSpec(2, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"jungle_planks", "jungle_planks", "jungle_planks", "jungle_planks", "jungle_planks", "jungle_planks"}));
        RECIPES.put("acacia_trapdoor", new RecipeSpec(2, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"acacia_planks", "acacia_planks", "acacia_planks", "acacia_planks", "acacia_planks", "acacia_planks"}));
        RECIPES.put("cherry_trapdoor", new RecipeSpec(2, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"cherry_planks", "cherry_planks", "cherry_planks", "cherry_planks", "cherry_planks", "cherry_planks"}));
        RECIPES.put("dark_oak_trapdoor", new RecipeSpec(2, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"dark_oak_planks", "dark_oak_planks", "dark_oak_planks", "dark_oak_planks", "dark_oak_planks", "dark_oak_planks"}));
        RECIPES.put("pale_oak_trapdoor", new RecipeSpec(2, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"pale_oak_planks", "pale_oak_planks", "pale_oak_planks", "pale_oak_planks", "pale_oak_planks", "pale_oak_planks"}));
        RECIPES.put("mangrove_trapdoor", new RecipeSpec(2, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"mangrove_planks", "mangrove_planks", "mangrove_planks", "mangrove_planks", "mangrove_planks", "mangrove_planks"}));
        RECIPES.put("bamboo_trapdoor", new RecipeSpec(2, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"bamboo_planks", "bamboo_planks", "bamboo_planks", "bamboo_planks", "bamboo_planks", "bamboo_planks"}));
        RECIPES.put("crimson_trapdoor", new RecipeSpec(2, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"crimson_planks", "crimson_planks", "crimson_planks", "crimson_planks", "crimson_planks", "crimson_planks"}));
        RECIPES.put("warped_trapdoor", new RecipeSpec(2, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"warped_planks", "warped_planks", "warped_planks", "warped_planks", "warped_planks", "warped_planks"}));
        RECIPES.put("copper_trapdoor", new RecipeSpec(2, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"copper_ingot", "copper_ingot", "copper_ingot", "copper_ingot", "copper_ingot", "copper_ingot"}));
        RECIPES.put("waxed_copper_trapdoor", new RecipeSpec(1, new int[]{1, 2}, new String[]{"copper_trapdoor", "honeycomb"}));
        RECIPES.put("waxed_exposed_copper_trapdoor", new RecipeSpec(1, new int[]{1, 2}, new String[]{"exposed_copper_trapdoor", "honeycomb"}));
        RECIPES.put("waxed_weathered_copper_trapdoor", new RecipeSpec(1, new int[]{1, 2}, new String[]{"weathered_copper_trapdoor", "honeycomb"}));
        RECIPES.put("waxed_oxidized_copper_trapdoor", new RecipeSpec(1, new int[]{1, 2}, new String[]{"oxidized_copper_trapdoor", "honeycomb"}));
        RECIPES.put("oak_fence_gate", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"stick", "oak_planks", "stick", "stick", "oak_planks", "stick"}));
        RECIPES.put("spruce_fence_gate", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"stick", "spruce_planks", "stick", "stick", "spruce_planks", "stick"}));
        RECIPES.put("birch_fence_gate", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"stick", "birch_planks", "stick", "stick", "birch_planks", "stick"}));
        RECIPES.put("jungle_fence_gate", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"stick", "jungle_planks", "stick", "stick", "jungle_planks", "stick"}));
        RECIPES.put("acacia_fence_gate", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"stick", "acacia_planks", "stick", "stick", "acacia_planks", "stick"}));
        RECIPES.put("cherry_fence_gate", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"stick", "cherry_planks", "stick", "stick", "cherry_planks", "stick"}));
        RECIPES.put("dark_oak_fence_gate", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"stick", "dark_oak_planks", "stick", "stick", "dark_oak_planks", "stick"}));
        RECIPES.put("pale_oak_fence_gate", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"stick", "pale_oak_planks", "stick", "stick", "pale_oak_planks", "stick"}));
        RECIPES.put("mangrove_fence_gate", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"stick", "mangrove_planks", "stick", "stick", "mangrove_planks", "stick"}));
        RECIPES.put("bamboo_fence_gate", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"stick", "bamboo_planks", "stick", "stick", "bamboo_planks", "stick"}));
        RECIPES.put("crimson_fence_gate", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"stick", "crimson_planks", "stick", "stick", "crimson_planks", "stick"}));
        RECIPES.put("warped_fence_gate", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"stick", "warped_planks", "stick", "stick", "warped_planks", "stick"}));
        RECIPES.put("powered_rail", new RecipeSpec(6, new int[]{1, 3, 4, 5, 6, 7, 8, 9}, new String[]{"gold_ingot", "gold_ingot", "gold_ingot", "stick", "gold_ingot", "gold_ingot", "redstone", "gold_ingot"}));
        RECIPES.put("detector_rail", new RecipeSpec(6, new int[]{1, 3, 4, 5, 6, 7, 8, 9}, new String[]{"iron_ingot", "iron_ingot", "iron_ingot", "stone_pressure_plate", "iron_ingot", "iron_ingot", "redstone", "iron_ingot"}));
        RECIPES.put("rail", new RecipeSpec(16, new int[]{1, 3, 4, 5, 6, 7, 9}, new String[]{"iron_ingot", "iron_ingot", "iron_ingot", "stick", "iron_ingot", "iron_ingot", "iron_ingot"}));
        RECIPES.put("activator_rail", new RecipeSpec(6, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"iron_ingot", "stick", "iron_ingot", "iron_ingot", "redstone_torch", "iron_ingot", "iron_ingot", "stick", "iron_ingot"}));
        RECIPES.put("saddle", new RecipeSpec(1, new int[]{2, 4, 5, 6}, new String[]{"leather", "leather", "iron_ingot", "leather"}));
        RECIPES.put("white_harness", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"leather", "leather", "leather", "glass", "white_wool", "glass"}));
        RECIPES.put("orange_harness", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"leather", "leather", "leather", "glass", "orange_wool", "glass"}));
        RECIPES.put("magenta_harness", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"leather", "leather", "leather", "glass", "magenta_wool", "glass"}));
        RECIPES.put("light_blue_harness", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"leather", "leather", "leather", "glass", "light_blue_wool", "glass"}));
        RECIPES.put("yellow_harness", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"leather", "leather", "leather", "glass", "yellow_wool", "glass"}));
        RECIPES.put("lime_harness", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"leather", "leather", "leather", "glass", "lime_wool", "glass"}));
        RECIPES.put("pink_harness", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"leather", "leather", "leather", "glass", "pink_wool", "glass"}));
        RECIPES.put("gray_harness", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"leather", "leather", "leather", "glass", "gray_wool", "glass"}));
        RECIPES.put("light_gray_harness", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"leather", "leather", "leather", "glass", "light_gray_wool", "glass"}));
        RECIPES.put("cyan_harness", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"leather", "leather", "leather", "glass", "cyan_wool", "glass"}));
        RECIPES.put("purple_harness", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"leather", "leather", "leather", "glass", "purple_wool", "glass"}));
        RECIPES.put("blue_harness", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"leather", "leather", "leather", "glass", "blue_wool", "glass"}));
        RECIPES.put("brown_harness", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"leather", "leather", "leather", "glass", "brown_wool", "glass"}));
        RECIPES.put("green_harness", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"leather", "leather", "leather", "glass", "green_wool", "glass"}));
        RECIPES.put("red_harness", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"leather", "leather", "leather", "glass", "red_wool", "glass"}));
        RECIPES.put("black_harness", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"leather", "leather", "leather", "glass", "black_wool", "glass"}));
        RECIPES.put("minecart", new RecipeSpec(1, new int[]{1, 3, 4, 5, 6}, new String[]{"iron_ingot", "iron_ingot", "iron_ingot", "iron_ingot", "iron_ingot"}));
        RECIPES.put("chest_minecart", new RecipeSpec(1, new int[]{1, 2}, new String[]{"chest", "minecart"}));
        RECIPES.put("furnace_minecart", new RecipeSpec(1, new int[]{1, 2}, new String[]{"furnace", "minecart"}));
        RECIPES.put("tnt_minecart", new RecipeSpec(1, new int[]{1, 2}, new String[]{"tnt", "minecart"}));
        RECIPES.put("hopper_minecart", new RecipeSpec(1, new int[]{1, 2}, new String[]{"hopper", "minecart"}));
        RECIPES.put("carrot_on_a_stick", new RecipeSpec(1, new int[]{1, 5}, new String[]{"fishing_rod", "carrot"}));
        RECIPES.put("warped_fungus_on_a_stick", new RecipeSpec(1, new int[]{1, 5}, new String[]{"fishing_rod", "warped_fungus"}));
        RECIPES.put("oak_boat", new RecipeSpec(1, new int[]{1, 3, 4, 5, 6}, new String[]{"oak_planks", "oak_planks", "oak_planks", "oak_planks", "oak_planks"}));
        RECIPES.put("oak_chest_boat", new RecipeSpec(1, new int[]{1, 2}, new String[]{"chest", "oak_boat"}));
        RECIPES.put("spruce_boat", new RecipeSpec(1, new int[]{1, 3, 4, 5, 6}, new String[]{"spruce_planks", "spruce_planks", "spruce_planks", "spruce_planks", "spruce_planks"}));
        RECIPES.put("spruce_chest_boat", new RecipeSpec(1, new int[]{1, 2}, new String[]{"chest", "spruce_boat"}));
        RECIPES.put("birch_boat", new RecipeSpec(1, new int[]{1, 3, 4, 5, 6}, new String[]{"birch_planks", "birch_planks", "birch_planks", "birch_planks", "birch_planks"}));
        RECIPES.put("birch_chest_boat", new RecipeSpec(1, new int[]{1, 2}, new String[]{"chest", "birch_boat"}));
        RECIPES.put("jungle_boat", new RecipeSpec(1, new int[]{1, 3, 4, 5, 6}, new String[]{"jungle_planks", "jungle_planks", "jungle_planks", "jungle_planks", "jungle_planks"}));
        RECIPES.put("jungle_chest_boat", new RecipeSpec(1, new int[]{1, 2}, new String[]{"chest", "jungle_boat"}));
        RECIPES.put("acacia_boat", new RecipeSpec(1, new int[]{1, 3, 4, 5, 6}, new String[]{"acacia_planks", "acacia_planks", "acacia_planks", "acacia_planks", "acacia_planks"}));
        RECIPES.put("acacia_chest_boat", new RecipeSpec(1, new int[]{1, 2}, new String[]{"chest", "acacia_boat"}));
        RECIPES.put("cherry_boat", new RecipeSpec(1, new int[]{1, 3, 4, 5, 6}, new String[]{"cherry_planks", "cherry_planks", "cherry_planks", "cherry_planks", "cherry_planks"}));
        RECIPES.put("cherry_chest_boat", new RecipeSpec(1, new int[]{1, 2}, new String[]{"chest", "cherry_boat"}));
        RECIPES.put("dark_oak_boat", new RecipeSpec(1, new int[]{1, 3, 4, 5, 6}, new String[]{"dark_oak_planks", "dark_oak_planks", "dark_oak_planks", "dark_oak_planks", "dark_oak_planks"}));
        RECIPES.put("dark_oak_chest_boat", new RecipeSpec(1, new int[]{1, 2}, new String[]{"chest", "dark_oak_boat"}));
        RECIPES.put("pale_oak_boat", new RecipeSpec(1, new int[]{1, 3, 4, 5, 6}, new String[]{"pale_oak_planks", "pale_oak_planks", "pale_oak_planks", "pale_oak_planks", "pale_oak_planks"}));
        RECIPES.put("pale_oak_chest_boat", new RecipeSpec(1, new int[]{1, 2}, new String[]{"chest", "pale_oak_boat"}));
        RECIPES.put("mangrove_boat", new RecipeSpec(1, new int[]{1, 3, 4, 5, 6}, new String[]{"mangrove_planks", "mangrove_planks", "mangrove_planks", "mangrove_planks", "mangrove_planks"}));
        RECIPES.put("mangrove_chest_boat", new RecipeSpec(1, new int[]{1, 2}, new String[]{"chest", "mangrove_boat"}));
        RECIPES.put("bamboo_raft", new RecipeSpec(1, new int[]{1, 3, 4, 5, 6}, new String[]{"bamboo_planks", "bamboo_planks", "bamboo_planks", "bamboo_planks", "bamboo_planks"}));
        RECIPES.put("bamboo_chest_raft", new RecipeSpec(1, new int[]{1, 2}, new String[]{"chest", "bamboo_raft"}));
        RECIPES.put("turtle_helmet", new RecipeSpec(1, new int[]{1, 2, 3, 4, 6}, new String[]{"turtle_scute", "turtle_scute", "turtle_scute", "turtle_scute", "turtle_scute"}));
        RECIPES.put("wolf_armor", new RecipeSpec(1, new int[]{1, 4, 5, 6, 7, 9}, new String[]{"armadillo_scute", "armadillo_scute", "armadillo_scute", "armadillo_scute", "armadillo_scute", "armadillo_scute"}));
        RECIPES.put("flint_and_steel", new RecipeSpec(1, new int[]{1, 2}, new String[]{"iron_ingot", "flint"}));
        RECIPES.put("bowl", new RecipeSpec(4, new int[]{1, 3, 5}, new String[]{"cherry_planks", "cherry_planks", "cherry_planks"}));
        RECIPES.put("bow", new RecipeSpec(1, new int[]{2, 3, 4, 6, 8, 9}, new String[]{"stick", "string", "stick", "string", "stick", "string"}));
        RECIPES.put("arrow", new RecipeSpec(4, new int[]{1, 4, 7}, new String[]{"flint", "stick", "feather"}));
        RECIPES.put("coal", new RecipeSpec(9, new int[]{1}, new String[]{"coal_block"}));
        RECIPES.put("diamond", new RecipeSpec(9, new int[]{1}, new String[]{"diamond_block"}));
        RECIPES.put("emerald", new RecipeSpec(9, new int[]{1}, new String[]{"emerald_block"}));
        RECIPES.put("lapis_lazuli", new RecipeSpec(9, new int[]{1}, new String[]{"lapis_block"}));
        RECIPES.put("raw_iron", new RecipeSpec(9, new int[]{1}, new String[]{"raw_iron_block"}));
        RECIPES.put("iron_ingot", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"iron_nugget", "iron_nugget", "iron_nugget", "iron_nugget", "iron_nugget", "iron_nugget", "iron_nugget", "iron_nugget", "iron_nugget"}));
        RECIPES.put("raw_copper", new RecipeSpec(9, new int[]{1}, new String[]{"raw_copper_block"}));
        RECIPES.put("copper_ingot", new RecipeSpec(9, new int[]{1}, new String[]{"copper_block"}));
        RECIPES.put("raw_gold", new RecipeSpec(9, new int[]{1}, new String[]{"raw_gold_block"}));
        RECIPES.put("gold_ingot", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"gold_nugget", "gold_nugget", "gold_nugget", "gold_nugget", "gold_nugget", "gold_nugget", "gold_nugget", "gold_nugget", "gold_nugget"}));
        RECIPES.put("netherite_ingot", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6, 7, 8}, new String[]{"netherite_scrap", "netherite_scrap", "netherite_scrap", "netherite_scrap", "gold_ingot", "gold_ingot", "gold_ingot", "gold_ingot"}));
        RECIPES.put("wooden_hoe", new RecipeSpec(1, new int[]{1, 2, 5, 8}, new String[]{"cherry_planks", "cherry_planks", "stick", "stick"}));
        RECIPES.put("stone_hoe", new RecipeSpec(1, new int[]{1, 2, 5, 8}, new String[]{"cobbled_deepslate", "cobbled_deepslate", "stick", "stick"}));
        RECIPES.put("golden_sword", new RecipeSpec(1, new int[]{1, 4, 7}, new String[]{"gold_ingot", "gold_ingot", "stick"}));
        RECIPES.put("golden_shovel", new RecipeSpec(1, new int[]{1, 4, 7}, new String[]{"gold_ingot", "stick", "stick"}));
        RECIPES.put("golden_pickaxe", new RecipeSpec(1, new int[]{1, 2, 3, 5, 8}, new String[]{"gold_ingot", "gold_ingot", "gold_ingot", "stick", "stick"}));
        RECIPES.put("golden_axe", new RecipeSpec(1, new int[]{1, 2, 4, 5, 8}, new String[]{"gold_ingot", "gold_ingot", "gold_ingot", "stick", "stick"}));
        RECIPES.put("golden_hoe", new RecipeSpec(1, new int[]{1, 2, 5, 8}, new String[]{"gold_ingot", "gold_ingot", "stick", "stick"}));
        RECIPES.put("iron_sword", new RecipeSpec(1, new int[]{1, 4, 7}, new String[]{"iron_ingot", "iron_ingot", "stick"}));
        RECIPES.put("iron_shovel", new RecipeSpec(1, new int[]{1, 4, 7}, new String[]{"iron_ingot", "stick", "stick"}));
        RECIPES.put("iron_pickaxe", new RecipeSpec(1, new int[]{1, 2, 3, 5, 8}, new String[]{"iron_ingot", "iron_ingot", "iron_ingot", "stick", "stick"}));
        RECIPES.put("iron_axe", new RecipeSpec(1, new int[]{1, 2, 4, 5, 8}, new String[]{"iron_ingot", "iron_ingot", "iron_ingot", "stick", "stick"}));
        RECIPES.put("iron_hoe", new RecipeSpec(1, new int[]{1, 2, 5, 8}, new String[]{"iron_ingot", "iron_ingot", "stick", "stick"}));
        RECIPES.put("diamond_sword", new RecipeSpec(1, new int[]{1, 4, 7}, new String[]{"diamond", "diamond", "stick"}));
        RECIPES.put("diamond_shovel", new RecipeSpec(1, new int[]{1, 4, 7}, new String[]{"diamond", "stick", "stick"}));
        RECIPES.put("diamond_pickaxe", new RecipeSpec(1, new int[]{1, 2, 3, 5, 8}, new String[]{"diamond", "diamond", "diamond", "stick", "stick"}));
        RECIPES.put("diamond_axe", new RecipeSpec(1, new int[]{1, 2, 4, 5, 8}, new String[]{"diamond", "diamond", "diamond", "stick", "stick"}));
        RECIPES.put("diamond_hoe", new RecipeSpec(1, new int[]{1, 2, 5, 8}, new String[]{"diamond", "diamond", "stick", "stick"}));
        RECIPES.put("mushroom_stew", new RecipeSpec(1, new int[]{1, 2, 4}, new String[]{"brown_mushroom", "red_mushroom", "bowl"}));
        RECIPES.put("wheat", new RecipeSpec(9, new int[]{1}, new String[]{"hay_block"}));
        RECIPES.put("bread", new RecipeSpec(1, new int[]{1, 2, 3}, new String[]{"wheat", "wheat", "wheat"}));
        RECIPES.put("leather_helmet", new RecipeSpec(1, new int[]{1, 2, 3, 4, 6}, new String[]{"leather", "leather", "leather", "leather", "leather"}));
        RECIPES.put("leather_chestplate", new RecipeSpec(1, new int[]{1, 3, 4, 5, 6, 7, 8, 9}, new String[]{"leather", "leather", "leather", "leather", "leather", "leather", "leather", "leather"}));
        RECIPES.put("leather_leggings", new RecipeSpec(1, new int[]{1, 2, 3, 4, 6, 7, 9}, new String[]{"leather", "leather", "leather", "leather", "leather", "leather", "leather"}));
        RECIPES.put("leather_boots", new RecipeSpec(1, new int[]{1, 3, 4, 6}, new String[]{"leather", "leather", "leather", "leather"}));
        RECIPES.put("iron_helmet", new RecipeSpec(1, new int[]{1, 2, 3, 4, 6}, new String[]{"iron_ingot", "iron_ingot", "iron_ingot", "iron_ingot", "iron_ingot"}));
        RECIPES.put("iron_chestplate", new RecipeSpec(1, new int[]{1, 3, 4, 5, 6, 7, 8, 9}, new String[]{"iron_ingot", "iron_ingot", "iron_ingot", "iron_ingot", "iron_ingot", "iron_ingot", "iron_ingot", "iron_ingot"}));
        RECIPES.put("iron_leggings", new RecipeSpec(1, new int[]{1, 2, 3, 4, 6, 7, 9}, new String[]{"iron_ingot", "iron_ingot", "iron_ingot", "iron_ingot", "iron_ingot", "iron_ingot", "iron_ingot"}));
        RECIPES.put("iron_boots", new RecipeSpec(1, new int[]{1, 3, 4, 6}, new String[]{"iron_ingot", "iron_ingot", "iron_ingot", "iron_ingot"}));
        RECIPES.put("diamond_helmet", new RecipeSpec(1, new int[]{1, 2, 3, 4, 6}, new String[]{"diamond", "diamond", "diamond", "diamond", "diamond"}));
        RECIPES.put("diamond_chestplate", new RecipeSpec(1, new int[]{1, 3, 4, 5, 6, 7, 8, 9}, new String[]{"diamond", "diamond", "diamond", "diamond", "diamond", "diamond", "diamond", "diamond"}));
        RECIPES.put("diamond_leggings", new RecipeSpec(1, new int[]{1, 2, 3, 4, 6, 7, 9}, new String[]{"diamond", "diamond", "diamond", "diamond", "diamond", "diamond", "diamond"}));
        RECIPES.put("diamond_boots", new RecipeSpec(1, new int[]{1, 3, 4, 6}, new String[]{"diamond", "diamond", "diamond", "diamond"}));
        RECIPES.put("golden_helmet", new RecipeSpec(1, new int[]{1, 2, 3, 4, 6}, new String[]{"gold_ingot", "gold_ingot", "gold_ingot", "gold_ingot", "gold_ingot"}));
        RECIPES.put("golden_chestplate", new RecipeSpec(1, new int[]{1, 3, 4, 5, 6, 7, 8, 9}, new String[]{"gold_ingot", "gold_ingot", "gold_ingot", "gold_ingot", "gold_ingot", "gold_ingot", "gold_ingot", "gold_ingot"}));
        RECIPES.put("golden_leggings", new RecipeSpec(1, new int[]{1, 2, 3, 4, 6, 7, 9}, new String[]{"gold_ingot", "gold_ingot", "gold_ingot", "gold_ingot", "gold_ingot", "gold_ingot", "gold_ingot"}));
        RECIPES.put("golden_boots", new RecipeSpec(1, new int[]{1, 3, 4, 6}, new String[]{"gold_ingot", "gold_ingot", "gold_ingot", "gold_ingot"}));
        RECIPES.put("painting", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"stick", "stick", "stick", "stick", "black_wool", "stick", "stick", "stick", "stick"}));
        RECIPES.put("golden_apple", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"gold_ingot", "gold_ingot", "gold_ingot", "gold_ingot", "apple", "gold_ingot", "gold_ingot", "gold_ingot", "gold_ingot"}));
        RECIPES.put("oak_sign", new RecipeSpec(3, new int[]{1, 2, 3, 4, 5, 6, 8}, new String[]{"oak_planks", "oak_planks", "oak_planks", "oak_planks", "oak_planks", "oak_planks", "stick"}));
        RECIPES.put("spruce_sign", new RecipeSpec(3, new int[]{1, 2, 3, 4, 5, 6, 8}, new String[]{"spruce_planks", "spruce_planks", "spruce_planks", "spruce_planks", "spruce_planks", "spruce_planks", "stick"}));
        RECIPES.put("birch_sign", new RecipeSpec(3, new int[]{1, 2, 3, 4, 5, 6, 8}, new String[]{"birch_planks", "birch_planks", "birch_planks", "birch_planks", "birch_planks", "birch_planks", "stick"}));
        RECIPES.put("jungle_sign", new RecipeSpec(3, new int[]{1, 2, 3, 4, 5, 6, 8}, new String[]{"jungle_planks", "jungle_planks", "jungle_planks", "jungle_planks", "jungle_planks", "jungle_planks", "stick"}));
        RECIPES.put("acacia_sign", new RecipeSpec(3, new int[]{1, 2, 3, 4, 5, 6, 8}, new String[]{"acacia_planks", "acacia_planks", "acacia_planks", "acacia_planks", "acacia_planks", "acacia_planks", "stick"}));
        RECIPES.put("cherry_sign", new RecipeSpec(3, new int[]{1, 2, 3, 4, 5, 6, 8}, new String[]{"cherry_planks", "cherry_planks", "cherry_planks", "cherry_planks", "cherry_planks", "cherry_planks", "stick"}));
        RECIPES.put("dark_oak_sign", new RecipeSpec(3, new int[]{1, 2, 3, 4, 5, 6, 8}, new String[]{"dark_oak_planks", "dark_oak_planks", "dark_oak_planks", "dark_oak_planks", "dark_oak_planks", "dark_oak_planks", "stick"}));
        RECIPES.put("pale_oak_sign", new RecipeSpec(3, new int[]{1, 2, 3, 4, 5, 6, 8}, new String[]{"pale_oak_planks", "pale_oak_planks", "pale_oak_planks", "pale_oak_planks", "pale_oak_planks", "pale_oak_planks", "stick"}));
        RECIPES.put("mangrove_sign", new RecipeSpec(3, new int[]{1, 2, 3, 4, 5, 6, 8}, new String[]{"mangrove_planks", "mangrove_planks", "mangrove_planks", "mangrove_planks", "mangrove_planks", "mangrove_planks", "stick"}));
        RECIPES.put("bamboo_sign", new RecipeSpec(3, new int[]{1, 2, 3, 4, 5, 6, 8}, new String[]{"bamboo_planks", "bamboo_planks", "bamboo_planks", "bamboo_planks", "bamboo_planks", "bamboo_planks", "stick"}));
        RECIPES.put("crimson_sign", new RecipeSpec(3, new int[]{1, 2, 3, 4, 5, 6, 8}, new String[]{"crimson_planks", "crimson_planks", "crimson_planks", "crimson_planks", "crimson_planks", "crimson_planks", "stick"}));
        RECIPES.put("warped_sign", new RecipeSpec(3, new int[]{1, 2, 3, 4, 5, 6, 8}, new String[]{"warped_planks", "warped_planks", "warped_planks", "warped_planks", "warped_planks", "warped_planks", "stick"}));
        RECIPES.put("oak_hanging_sign", new RecipeSpec(6, new int[]{1, 3, 4, 5, 6, 7, 8, 9}, new String[]{"chain", "chain", "stripped_oak_log", "stripped_oak_log", "stripped_oak_log", "stripped_oak_log", "stripped_oak_log", "stripped_oak_log"}));
        RECIPES.put("spruce_hanging_sign", new RecipeSpec(6, new int[]{1, 3, 4, 5, 6, 7, 8, 9}, new String[]{"chain", "chain", "stripped_spruce_log", "stripped_spruce_log", "stripped_spruce_log", "stripped_spruce_log", "stripped_spruce_log", "stripped_spruce_log"}));
        RECIPES.put("birch_hanging_sign", new RecipeSpec(6, new int[]{1, 3, 4, 5, 6, 7, 8, 9}, new String[]{"chain", "chain", "stripped_birch_log", "stripped_birch_log", "stripped_birch_log", "stripped_birch_log", "stripped_birch_log", "stripped_birch_log"}));
        RECIPES.put("jungle_hanging_sign", new RecipeSpec(6, new int[]{1, 3, 4, 5, 6, 7, 8, 9}, new String[]{"chain", "chain", "stripped_jungle_log", "stripped_jungle_log", "stripped_jungle_log", "stripped_jungle_log", "stripped_jungle_log", "stripped_jungle_log"}));
        RECIPES.put("acacia_hanging_sign", new RecipeSpec(6, new int[]{1, 3, 4, 5, 6, 7, 8, 9}, new String[]{"chain", "chain", "stripped_acacia_log", "stripped_acacia_log", "stripped_acacia_log", "stripped_acacia_log", "stripped_acacia_log", "stripped_acacia_log"}));
        RECIPES.put("cherry_hanging_sign", new RecipeSpec(6, new int[]{1, 3, 4, 5, 6, 7, 8, 9}, new String[]{"chain", "chain", "stripped_cherry_log", "stripped_cherry_log", "stripped_cherry_log", "stripped_cherry_log", "stripped_cherry_log", "stripped_cherry_log"}));
        RECIPES.put("dark_oak_hanging_sign", new RecipeSpec(6, new int[]{1, 3, 4, 5, 6, 7, 8, 9}, new String[]{"chain", "chain", "stripped_dark_oak_log", "stripped_dark_oak_log", "stripped_dark_oak_log", "stripped_dark_oak_log", "stripped_dark_oak_log", "stripped_dark_oak_log"}));
        RECIPES.put("pale_oak_hanging_sign", new RecipeSpec(6, new int[]{1, 3, 4, 5, 6, 7, 8, 9}, new String[]{"chain", "chain", "stripped_pale_oak_log", "stripped_pale_oak_log", "stripped_pale_oak_log", "stripped_pale_oak_log", "stripped_pale_oak_log", "stripped_pale_oak_log"}));
        RECIPES.put("mangrove_hanging_sign", new RecipeSpec(6, new int[]{1, 3, 4, 5, 6, 7, 8, 9}, new String[]{"chain", "chain", "stripped_mangrove_log", "stripped_mangrove_log", "stripped_mangrove_log", "stripped_mangrove_log", "stripped_mangrove_log", "stripped_mangrove_log"}));
        RECIPES.put("bamboo_hanging_sign", new RecipeSpec(6, new int[]{1, 3, 4, 5, 6, 7, 8, 9}, new String[]{"chain", "chain", "stripped_bamboo_block", "stripped_bamboo_block", "stripped_bamboo_block", "stripped_bamboo_block", "stripped_bamboo_block", "stripped_bamboo_block"}));
        RECIPES.put("crimson_hanging_sign", new RecipeSpec(6, new int[]{1, 3, 4, 5, 6, 7, 8, 9}, new String[]{"chain", "chain", "stripped_crimson_stem", "stripped_crimson_stem", "stripped_crimson_stem", "stripped_crimson_stem", "stripped_crimson_stem", "stripped_crimson_stem"}));
        RECIPES.put("warped_hanging_sign", new RecipeSpec(6, new int[]{1, 3, 4, 5, 6, 7, 8, 9}, new String[]{"chain", "chain", "stripped_warped_stem", "stripped_warped_stem", "stripped_warped_stem", "stripped_warped_stem", "stripped_warped_stem", "stripped_warped_stem"}));
        RECIPES.put("bucket", new RecipeSpec(1, new int[]{1, 3, 5}, new String[]{"iron_ingot", "iron_ingot", "iron_ingot"}));
        RECIPES.put("leather", new RecipeSpec(1, new int[]{1, 2, 4, 5}, new String[]{"rabbit_hide", "rabbit_hide", "rabbit_hide", "rabbit_hide"}));
        RECIPES.put("dried_kelp_block", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"dried_kelp", "dried_kelp", "dried_kelp", "dried_kelp", "dried_kelp", "dried_kelp", "dried_kelp", "dried_kelp", "dried_kelp"}));
        RECIPES.put("paper", new RecipeSpec(3, new int[]{1, 2, 3}, new String[]{"sugar_cane", "sugar_cane", "sugar_cane"}));
        RECIPES.put("book", new RecipeSpec(1, new int[]{1, 2, 4, 5}, new String[]{"paper", "paper", "paper", "leather"}));
        RECIPES.put("slime_ball", new RecipeSpec(9, new int[]{1}, new String[]{"slime_block"}));
        RECIPES.put("compass", new RecipeSpec(1, new int[]{2, 4, 5, 6, 8}, new String[]{"iron_ingot", "iron_ingot", "redstone", "iron_ingot", "iron_ingot"}));
        RECIPES.put("recovery_compass", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"echo_shard", "echo_shard", "echo_shard", "echo_shard", "compass", "echo_shard", "echo_shard", "echo_shard", "echo_shard"}));
        RECIPES.put("bundle", new RecipeSpec(1, new int[]{1, 4}, new String[]{"string", "leather"}));
        RECIPES.put("fishing_rod", new RecipeSpec(1, new int[]{3, 5, 6, 7, 9}, new String[]{"stick", "stick", "string", "stick", "string"}));
        RECIPES.put("clock", new RecipeSpec(1, new int[]{2, 4, 5, 6, 8}, new String[]{"gold_ingot", "gold_ingot", "redstone", "gold_ingot", "gold_ingot"}));
        RECIPES.put("spyglass", new RecipeSpec(1, new int[]{1, 4, 7}, new String[]{"amethyst_shard", "copper_ingot", "copper_ingot"}));
        RECIPES.put("white_dye", new RecipeSpec(1, new int[]{1}, new String[]{"bone_meal"}));
        RECIPES.put("orange_dye", new RecipeSpec(1, new int[]{1}, new String[]{"open_eyeblossom"}));
        RECIPES.put("magenta_dye", new RecipeSpec(1, new int[]{1}, new String[]{"allium"}));
        RECIPES.put("light_blue_dye", new RecipeSpec(1, new int[]{1}, new String[]{"blue_orchid"}));
        RECIPES.put("yellow_dye", new RecipeSpec(1, new int[]{1}, new String[]{"dandelion"}));
        RECIPES.put("lime_dye", new RecipeSpec(2, new int[]{1, 2}, new String[]{"green_dye", "white_dye"}));
        RECIPES.put("pink_dye", new RecipeSpec(1, new int[]{1}, new String[]{"cactus_flower"}));
        RECIPES.put("gray_dye", new RecipeSpec(2, new int[]{1, 2}, new String[]{"black_dye", "white_dye"}));
        RECIPES.put("light_gray_dye", new RecipeSpec(1, new int[]{1}, new String[]{"azure_bluet"}));
        RECIPES.put("cyan_dye", new RecipeSpec(2, new int[]{1, 2}, new String[]{"blue_dye", "green_dye"}));
        RECIPES.put("purple_dye", new RecipeSpec(2, new int[]{1, 2}, new String[]{"blue_dye", "red_dye"}));
        RECIPES.put("blue_dye", new RecipeSpec(1, new int[]{1}, new String[]{"lapis_lazuli"}));
        RECIPES.put("brown_dye", new RecipeSpec(1, new int[]{1}, new String[]{"cocoa_beans"}));
        RECIPES.put("red_dye", new RecipeSpec(1, new int[]{1}, new String[]{"beetroot"}));
        RECIPES.put("black_dye", new RecipeSpec(1, new int[]{1}, new String[]{"ink_sac"}));
        RECIPES.put("bone_meal", new RecipeSpec(3, new int[]{1}, new String[]{"bone"}));
        RECIPES.put("sugar", new RecipeSpec(3, new int[]{1}, new String[]{"honey_bottle"}));
        RECIPES.put("cake", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"milk_bucket", "milk_bucket", "milk_bucket", "sugar", "brown_egg", "sugar", "wheat", "wheat", "wheat"}));
        RECIPES.put("white_bed", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"white_wool", "white_wool", "white_wool", "cherry_planks", "cherry_planks", "cherry_planks"}));
        RECIPES.put("orange_bed", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"orange_wool", "orange_wool", "orange_wool", "cherry_planks", "cherry_planks", "cherry_planks"}));
        RECIPES.put("magenta_bed", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"magenta_wool", "magenta_wool", "magenta_wool", "cherry_planks", "cherry_planks", "cherry_planks"}));
        RECIPES.put("light_blue_bed", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"light_blue_wool", "light_blue_wool", "light_blue_wool", "cherry_planks", "cherry_planks", "cherry_planks"}));
        RECIPES.put("yellow_bed", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"yellow_wool", "yellow_wool", "yellow_wool", "cherry_planks", "cherry_planks", "cherry_planks"}));
        RECIPES.put("lime_bed", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"lime_wool", "lime_wool", "lime_wool", "cherry_planks", "cherry_planks", "cherry_planks"}));
        RECIPES.put("pink_bed", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"pink_wool", "pink_wool", "pink_wool", "cherry_planks", "cherry_planks", "cherry_planks"}));
        RECIPES.put("gray_bed", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"gray_wool", "gray_wool", "gray_wool", "cherry_planks", "cherry_planks", "cherry_planks"}));
        RECIPES.put("light_gray_bed", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"light_gray_wool", "light_gray_wool", "light_gray_wool", "cherry_planks", "cherry_planks", "cherry_planks"}));
        RECIPES.put("cyan_bed", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"cyan_wool", "cyan_wool", "cyan_wool", "cherry_planks", "cherry_planks", "cherry_planks"}));
        RECIPES.put("purple_bed", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"purple_wool", "purple_wool", "purple_wool", "cherry_planks", "cherry_planks", "cherry_planks"}));
        RECIPES.put("blue_bed", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"blue_wool", "blue_wool", "blue_wool", "cherry_planks", "cherry_planks", "cherry_planks"}));
        RECIPES.put("brown_bed", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"brown_wool", "brown_wool", "brown_wool", "cherry_planks", "cherry_planks", "cherry_planks"}));
        RECIPES.put("green_bed", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"green_wool", "green_wool", "green_wool", "cherry_planks", "cherry_planks", "cherry_planks"}));
        RECIPES.put("red_bed", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"red_wool", "red_wool", "red_wool", "cherry_planks", "cherry_planks", "cherry_planks"}));
        RECIPES.put("black_bed", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6}, new String[]{"black_wool", "black_wool", "black_wool", "cherry_planks", "cherry_planks", "cherry_planks"}));
        RECIPES.put("cookie", new RecipeSpec(8, new int[]{1, 2, 3}, new String[]{"wheat", "cocoa_beans", "wheat"}));
        RECIPES.put("crafter", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"iron_ingot", "iron_ingot", "iron_ingot", "iron_ingot", "crafting_table", "iron_ingot", "redstone", "dropper", "redstone"}));
        RECIPES.put("shears", new RecipeSpec(1, new int[]{2, 4}, new String[]{"iron_ingot", "iron_ingot"}));
        RECIPES.put("dried_kelp", new RecipeSpec(9, new int[]{1}, new String[]{"dried_kelp_block"}));
        RECIPES.put("pumpkin_seeds", new RecipeSpec(4, new int[]{1}, new String[]{"pumpkin"}));
        RECIPES.put("melon_seeds", new RecipeSpec(1, new int[]{1}, new String[]{"melon_slice"}));
        RECIPES.put("gold_nugget", new RecipeSpec(9, new int[]{1}, new String[]{"gold_ingot"}));
        RECIPES.put("glass_bottle", new RecipeSpec(3, new int[]{1, 3, 5}, new String[]{"glass", "glass", "glass"}));
        RECIPES.put("fermented_spider_eye", new RecipeSpec(1, new int[]{1, 2, 4}, new String[]{"spider_eye", "brown_mushroom", "sugar"}));
        RECIPES.put("blaze_powder", new RecipeSpec(2, new int[]{1}, new String[]{"blaze_rod"}));
        RECIPES.put("magma_cream", new RecipeSpec(1, new int[]{1, 2}, new String[]{"blaze_powder", "slime_ball"}));
        RECIPES.put("brewing_stand", new RecipeSpec(1, new int[]{2, 4, 5, 6}, new String[]{"blaze_rod", "cobbled_deepslate", "cobbled_deepslate", "cobbled_deepslate"}));
        RECIPES.put("cauldron", new RecipeSpec(1, new int[]{1, 3, 4, 6, 7, 8, 9}, new String[]{"iron_ingot", "iron_ingot", "iron_ingot", "iron_ingot", "iron_ingot", "iron_ingot", "iron_ingot"}));
        RECIPES.put("ender_eye", new RecipeSpec(1, new int[]{1, 2}, new String[]{"ender_pearl", "blaze_powder"}));
        RECIPES.put("glistering_melon_slice", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"gold_nugget", "gold_nugget", "gold_nugget", "gold_nugget", "melon_slice", "gold_nugget", "gold_nugget", "gold_nugget", "gold_nugget"}));
        RECIPES.put("fire_charge", new RecipeSpec(3, new int[]{1, 2, 4}, new String[]{"gunpowder", "blaze_powder", "coal"}));
        RECIPES.put("wind_charge", new RecipeSpec(4, new int[]{1}, new String[]{"breeze_rod"}));
        RECIPES.put("writable_book", new RecipeSpec(1, new int[]{1, 2, 4}, new String[]{"book", "ink_sac", "feather"}));
        RECIPES.put("mace", new RecipeSpec(1, new int[]{1, 4}, new String[]{"heavy_core", "breeze_rod"}));
        RECIPES.put("item_frame", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"stick", "stick", "stick", "stick", "leather", "stick", "stick", "stick", "stick"}));
        RECIPES.put("glow_item_frame", new RecipeSpec(1, new int[]{1, 2}, new String[]{"item_frame", "glow_ink_sac"}));
        RECIPES.put("flower_pot", new RecipeSpec(1, new int[]{1, 3, 5}, new String[]{"brick", "brick", "brick"}));
        RECIPES.put("map", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"paper", "paper", "paper", "paper", "compass", "paper", "paper", "paper", "paper"}));
        RECIPES.put("golden_carrot", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"gold_nugget", "gold_nugget", "gold_nugget", "gold_nugget", "carrot", "gold_nugget", "gold_nugget", "gold_nugget", "gold_nugget"}));
        RECIPES.put("pumpkin_pie", new RecipeSpec(1, new int[]{1, 2, 4}, new String[]{"pumpkin", "sugar", "egg"}));
        RECIPES.put("firework_rocket", new RecipeSpec(3, new int[]{1, 2}, new String[]{"gunpowder", "paper"}));
        RECIPES.put("rabbit_stew", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5}, new String[]{"baked_potato", "cooked_rabbit", "bowl", "carrot", "brown_mushroom"}));
        RECIPES.put("armor_stand", new RecipeSpec(1, new int[]{1, 2, 3, 5, 7, 8, 9}, new String[]{"stick", "stick", "stick", "stick", "stick", "smooth_stone_slab", "stick"}));
        RECIPES.put("leather_horse_armor", new RecipeSpec(1, new int[]{1, 3, 4, 5, 6, 7, 9}, new String[]{"leather", "leather", "leather", "leather", "leather", "leather", "leather"}));
        RECIPES.put("lead", new RecipeSpec(2, new int[]{1, 2, 4, 5, 9}, new String[]{"string", "string", "string", "string", "string"}));
        RECIPES.put("white_banner", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6, 8}, new String[]{"white_wool", "white_wool", "white_wool", "white_wool", "white_wool", "white_wool", "stick"}));
        RECIPES.put("orange_banner", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6, 8}, new String[]{"orange_wool", "orange_wool", "orange_wool", "orange_wool", "orange_wool", "orange_wool", "stick"}));
        RECIPES.put("magenta_banner", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6, 8}, new String[]{"magenta_wool", "magenta_wool", "magenta_wool", "magenta_wool", "magenta_wool", "magenta_wool", "stick"}));
        RECIPES.put("light_blue_banner", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6, 8}, new String[]{"light_blue_wool", "light_blue_wool", "light_blue_wool", "light_blue_wool", "light_blue_wool", "light_blue_wool", "stick"}));
        RECIPES.put("yellow_banner", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6, 8}, new String[]{"yellow_wool", "yellow_wool", "yellow_wool", "yellow_wool", "yellow_wool", "yellow_wool", "stick"}));
        RECIPES.put("lime_banner", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6, 8}, new String[]{"lime_wool", "lime_wool", "lime_wool", "lime_wool", "lime_wool", "lime_wool", "stick"}));
        RECIPES.put("pink_banner", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6, 8}, new String[]{"pink_wool", "pink_wool", "pink_wool", "pink_wool", "pink_wool", "pink_wool", "stick"}));
        RECIPES.put("gray_banner", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6, 8}, new String[]{"gray_wool", "gray_wool", "gray_wool", "gray_wool", "gray_wool", "gray_wool", "stick"}));
        RECIPES.put("light_gray_banner", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6, 8}, new String[]{"light_gray_wool", "light_gray_wool", "light_gray_wool", "light_gray_wool", "light_gray_wool", "light_gray_wool", "stick"}));
        RECIPES.put("cyan_banner", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6, 8}, new String[]{"cyan_wool", "cyan_wool", "cyan_wool", "cyan_wool", "cyan_wool", "cyan_wool", "stick"}));
        RECIPES.put("purple_banner", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6, 8}, new String[]{"purple_wool", "purple_wool", "purple_wool", "purple_wool", "purple_wool", "purple_wool", "stick"}));
        RECIPES.put("blue_banner", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6, 8}, new String[]{"blue_wool", "blue_wool", "blue_wool", "blue_wool", "blue_wool", "blue_wool", "stick"}));
        RECIPES.put("brown_banner", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6, 8}, new String[]{"brown_wool", "brown_wool", "brown_wool", "brown_wool", "brown_wool", "brown_wool", "stick"}));
        RECIPES.put("green_banner", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6, 8}, new String[]{"green_wool", "green_wool", "green_wool", "green_wool", "green_wool", "green_wool", "stick"}));
        RECIPES.put("red_banner", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6, 8}, new String[]{"red_wool", "red_wool", "red_wool", "red_wool", "red_wool", "red_wool", "stick"}));
        RECIPES.put("black_banner", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6, 8}, new String[]{"black_wool", "black_wool", "black_wool", "black_wool", "black_wool", "black_wool", "stick"}));
        RECIPES.put("end_crystal", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"glass", "glass", "glass", "glass", "ender_eye", "glass", "glass", "ghast_tear", "glass"}));
        RECIPES.put("beetroot_soup", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6, 7}, new String[]{"bowl", "beetroot", "beetroot", "beetroot", "beetroot", "beetroot", "beetroot"}));
        RECIPES.put("spectral_arrow", new RecipeSpec(2, new int[]{2, 4, 5, 6, 8}, new String[]{"glowstone_dust", "glowstone_dust", "arrow", "glowstone_dust", "glowstone_dust"}));
        RECIPES.put("shield", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6, 8}, new String[]{"cherry_planks", "iron_ingot", "cherry_planks", "cherry_planks", "cherry_planks", "cherry_planks", "cherry_planks"}));
        RECIPES.put("iron_nugget", new RecipeSpec(9, new int[]{1}, new String[]{"iron_ingot"}));
        RECIPES.put("music_disc_5", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"disc_fragment_5", "disc_fragment_5", "disc_fragment_5", "disc_fragment_5", "disc_fragment_5", "disc_fragment_5", "disc_fragment_5", "disc_fragment_5", "disc_fragment_5"}));
        RECIPES.put("crossbow", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6, 8}, new String[]{"stick", "iron_ingot", "stick", "string", "tripwire_hook", "string", "stick"}));
        RECIPES.put("suspicious_stew", new RecipeSpec(1, new int[]{1, 2, 4, 5}, new String[]{"bowl", "brown_mushroom", "red_mushroom", "allium"}));
        RECIPES.put("loom", new RecipeSpec(1, new int[]{1, 2, 4, 5}, new String[]{"string", "string", "cherry_planks", "cherry_planks"}));
        RECIPES.put("flower_banner_pattern", new RecipeSpec(1, new int[]{1, 2}, new String[]{"paper", "oxeye_daisy"}));
        RECIPES.put("creeper_banner_pattern", new RecipeSpec(1, new int[]{1, 2}, new String[]{"paper", "creeper_head"}));
        RECIPES.put("skull_banner_pattern", new RecipeSpec(1, new int[]{1, 2}, new String[]{"paper", "wither_skeleton_skull"}));
        RECIPES.put("mojang_banner_pattern", new RecipeSpec(1, new int[]{1, 2}, new String[]{"paper", "enchanted_golden_apple"}));
        RECIPES.put("field_masoned_banner_pattern", new RecipeSpec(1, new int[]{1, 2}, new String[]{"paper", "bricks"}));
        RECIPES.put("bordure_indented_banner_pattern", new RecipeSpec(1, new int[]{1, 2}, new String[]{"paper", "vine"}));
        RECIPES.put("composter", new RecipeSpec(1, new int[]{1, 3, 4, 6, 7, 8, 9}, new String[]{"cherry_slab", "cherry_slab", "cherry_slab", "cherry_slab", "cherry_slab", "cherry_slab", "cherry_slab"}));
        RECIPES.put("barrel", new RecipeSpec(1, new int[]{1, 2, 3, 4, 6, 7, 8, 9}, new String[]{"cherry_planks", "cherry_slab", "cherry_planks", "cherry_planks", "cherry_planks", "cherry_planks", "cherry_slab", "cherry_planks"}));
        RECIPES.put("smoker", new RecipeSpec(1, new int[]{2, 4, 5, 6, 8}, new String[]{"stripped_warped_hyphae", "stripped_warped_hyphae", "furnace", "stripped_warped_hyphae", "stripped_warped_hyphae"}));
        RECIPES.put("blast_furnace", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"iron_ingot", "iron_ingot", "iron_ingot", "iron_ingot", "furnace", "iron_ingot", "smooth_stone", "smooth_stone", "smooth_stone"}));
        RECIPES.put("cartography_table", new RecipeSpec(1, new int[]{1, 2, 4, 5, 7, 8}, new String[]{"paper", "paper", "cherry_planks", "cherry_planks", "cherry_planks", "cherry_planks"}));
        RECIPES.put("fletching_table", new RecipeSpec(1, new int[]{1, 2, 4, 5, 7, 8}, new String[]{"flint", "flint", "cherry_planks", "cherry_planks", "cherry_planks", "cherry_planks"}));
        RECIPES.put("grindstone", new RecipeSpec(1, new int[]{1, 2, 3, 4, 6}, new String[]{"stick", "stone_slab", "stick", "cherry_planks", "cherry_planks"}));
        RECIPES.put("smithing_table", new RecipeSpec(1, new int[]{1, 2, 4, 5, 7, 8}, new String[]{"iron_ingot", "iron_ingot", "cherry_planks", "cherry_planks", "cherry_planks", "cherry_planks"}));
        RECIPES.put("stonecutter", new RecipeSpec(1, new int[]{2, 4, 5, 6}, new String[]{"iron_ingot", "stone", "stone", "stone"}));
        RECIPES.put("lantern", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"iron_nugget", "iron_nugget", "iron_nugget", "iron_nugget", "torch", "iron_nugget", "iron_nugget", "iron_nugget", "iron_nugget"}));
        RECIPES.put("soul_lantern", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"iron_nugget", "iron_nugget", "iron_nugget", "iron_nugget", "soul_torch", "iron_nugget", "iron_nugget", "iron_nugget", "iron_nugget"}));
        RECIPES.put("campfire", new RecipeSpec(1, new int[]{2, 4, 5, 6, 7, 8, 9}, new String[]{"stick", "stick", "coal", "stick", "stripped_warped_hyphae", "stripped_warped_hyphae", "stripped_warped_hyphae"}));
        RECIPES.put("soul_campfire", new RecipeSpec(1, new int[]{2, 4, 5, 6, 7, 8, 9}, new String[]{"stick", "stick", "soul_sand", "stick", "stripped_warped_hyphae", "stripped_warped_hyphae", "stripped_warped_hyphae"}));
        RECIPES.put("beehive", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"cherry_planks", "cherry_planks", "cherry_planks", "honeycomb", "honeycomb", "honeycomb", "cherry_planks", "cherry_planks", "cherry_planks"}));
        RECIPES.put("honey_bottle", new RecipeSpec(4, new int[]{1, 2, 3, 4, 5}, new String[]{"honey_block", "glass_bottle", "glass_bottle", "glass_bottle", "glass_bottle"}));
        RECIPES.put("honeycomb_block", new RecipeSpec(1, new int[]{1, 2, 4, 5}, new String[]{"honeycomb", "honeycomb", "honeycomb", "honeycomb"}));
        RECIPES.put("lodestone", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"chiseled_stone_bricks", "chiseled_stone_bricks", "chiseled_stone_bricks", "chiseled_stone_bricks", "iron_ingot", "chiseled_stone_bricks", "chiseled_stone_bricks", "chiseled_stone_bricks", "chiseled_stone_bricks"}));
        RECIPES.put("blackstone_slab", new RecipeSpec(6, new int[]{1, 2, 3}, new String[]{"blackstone", "blackstone", "blackstone"}));
        RECIPES.put("blackstone_stairs", new RecipeSpec(4, new int[]{1, 4, 5, 7, 8, 9}, new String[]{"blackstone", "blackstone", "blackstone", "blackstone", "blackstone", "blackstone"}));
        RECIPES.put("polished_blackstone", new RecipeSpec(4, new int[]{1, 2, 4, 5}, new String[]{"blackstone", "blackstone", "blackstone", "blackstone"}));
        RECIPES.put("polished_blackstone_slab", new RecipeSpec(6, new int[]{1, 2, 3}, new String[]{"polished_blackstone", "polished_blackstone", "polished_blackstone"}));
        RECIPES.put("polished_blackstone_stairs", new RecipeSpec(4, new int[]{1, 4, 5, 7, 8, 9}, new String[]{"polished_blackstone", "polished_blackstone", "polished_blackstone", "polished_blackstone", "polished_blackstone", "polished_blackstone"}));
        RECIPES.put("chiseled_polished_blackstone", new RecipeSpec(1, new int[]{1, 4}, new String[]{"polished_blackstone_slab", "polished_blackstone_slab"}));
        RECIPES.put("polished_blackstone_bricks", new RecipeSpec(4, new int[]{1, 2, 4, 5}, new String[]{"polished_blackstone", "polished_blackstone", "polished_blackstone", "polished_blackstone"}));
        RECIPES.put("polished_blackstone_brick_slab", new RecipeSpec(6, new int[]{1, 2, 3}, new String[]{"polished_blackstone_bricks", "polished_blackstone_bricks", "polished_blackstone_bricks"}));
        RECIPES.put("polished_blackstone_brick_stairs", new RecipeSpec(4, new int[]{1, 4, 5, 7, 8, 9}, new String[]{"polished_blackstone_bricks", "polished_blackstone_bricks", "polished_blackstone_bricks", "polished_blackstone_bricks", "polished_blackstone_bricks", "polished_blackstone_bricks"}));
        RECIPES.put("respawn_anchor", new RecipeSpec(1, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"crying_obsidian", "crying_obsidian", "crying_obsidian", "glowstone", "glowstone", "glowstone", "crying_obsidian", "crying_obsidian", "crying_obsidian"}));
        RECIPES.put("candle", new RecipeSpec(1, new int[]{1, 4}, new String[]{"string", "honeycomb"}));
        RECIPES.put("white_candle", new RecipeSpec(1, new int[]{1, 2}, new String[]{"candle", "white_dye"}));
        RECIPES.put("orange_candle", new RecipeSpec(1, new int[]{1, 2}, new String[]{"candle", "orange_dye"}));
        RECIPES.put("magenta_candle", new RecipeSpec(1, new int[]{1, 2}, new String[]{"candle", "magenta_dye"}));
        RECIPES.put("light_blue_candle", new RecipeSpec(1, new int[]{1, 2}, new String[]{"candle", "light_blue_dye"}));
        RECIPES.put("yellow_candle", new RecipeSpec(1, new int[]{1, 2}, new String[]{"candle", "yellow_dye"}));
        RECIPES.put("lime_candle", new RecipeSpec(1, new int[]{1, 2}, new String[]{"candle", "lime_dye"}));
        RECIPES.put("pink_candle", new RecipeSpec(1, new int[]{1, 2}, new String[]{"candle", "pink_dye"}));
        RECIPES.put("gray_candle", new RecipeSpec(1, new int[]{1, 2}, new String[]{"candle", "gray_dye"}));
        RECIPES.put("light_gray_candle", new RecipeSpec(1, new int[]{1, 2}, new String[]{"candle", "light_gray_dye"}));
        RECIPES.put("cyan_candle", new RecipeSpec(1, new int[]{1, 2}, new String[]{"candle", "cyan_dye"}));
        RECIPES.put("purple_candle", new RecipeSpec(1, new int[]{1, 2}, new String[]{"candle", "purple_dye"}));
        RECIPES.put("blue_candle", new RecipeSpec(1, new int[]{1, 2}, new String[]{"candle", "blue_dye"}));
        RECIPES.put("brown_candle", new RecipeSpec(1, new int[]{1, 2}, new String[]{"candle", "brown_dye"}));
        RECIPES.put("green_candle", new RecipeSpec(1, new int[]{1, 2}, new String[]{"candle", "green_dye"}));
        RECIPES.put("red_candle", new RecipeSpec(1, new int[]{1, 2}, new String[]{"candle", "red_dye"}));
        RECIPES.put("black_candle", new RecipeSpec(1, new int[]{1, 2}, new String[]{"candle", "black_dye"}));
        RECIPES.put("brush", new RecipeSpec(1, new int[]{1, 4, 7}, new String[]{"feather", "copper_ingot", "stick"}));
        RECIPES.put("netherite_upgrade_smithing_template", new RecipeSpec(2, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"diamond", "netherite_upgrade_smithing_template", "diamond", "diamond", "netherrack", "diamond", "diamond", "diamond", "diamond"}));
        RECIPES.put("sentry_armor_trim_smithing_template", new RecipeSpec(2, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"diamond", "sentry_armor_trim_smithing_template", "diamond", "diamond", "cobblestone", "diamond", "diamond", "diamond", "diamond"}));
        RECIPES.put("dune_armor_trim_smithing_template", new RecipeSpec(2, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"diamond", "dune_armor_trim_smithing_template", "diamond", "diamond", "sandstone", "diamond", "diamond", "diamond", "diamond"}));
        RECIPES.put("coast_armor_trim_smithing_template", new RecipeSpec(2, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"diamond", "coast_armor_trim_smithing_template", "diamond", "diamond", "cobblestone", "diamond", "diamond", "diamond", "diamond"}));
        RECIPES.put("wild_armor_trim_smithing_template", new RecipeSpec(2, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"diamond", "wild_armor_trim_smithing_template", "diamond", "diamond", "mossy_cobblestone", "diamond", "diamond", "diamond", "diamond"}));
        RECIPES.put("ward_armor_trim_smithing_template", new RecipeSpec(2, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"diamond", "ward_armor_trim_smithing_template", "diamond", "diamond", "cobbled_deepslate", "diamond", "diamond", "diamond", "diamond"}));
        RECIPES.put("eye_armor_trim_smithing_template", new RecipeSpec(2, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"diamond", "eye_armor_trim_smithing_template", "diamond", "diamond", "end_stone", "diamond", "diamond", "diamond", "diamond"}));
        RECIPES.put("vex_armor_trim_smithing_template", new RecipeSpec(2, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"diamond", "vex_armor_trim_smithing_template", "diamond", "diamond", "cobblestone", "diamond", "diamond", "diamond", "diamond"}));
        RECIPES.put("tide_armor_trim_smithing_template", new RecipeSpec(2, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"diamond", "tide_armor_trim_smithing_template", "diamond", "diamond", "prismarine", "diamond", "diamond", "diamond", "diamond"}));
        RECIPES.put("snout_armor_trim_smithing_template", new RecipeSpec(2, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"diamond", "snout_armor_trim_smithing_template", "diamond", "diamond", "blackstone", "diamond", "diamond", "diamond", "diamond"}));
        RECIPES.put("rib_armor_trim_smithing_template", new RecipeSpec(2, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"diamond", "rib_armor_trim_smithing_template", "diamond", "diamond", "netherrack", "diamond", "diamond", "diamond", "diamond"}));
        RECIPES.put("spire_armor_trim_smithing_template", new RecipeSpec(2, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"diamond", "spire_armor_trim_smithing_template", "diamond", "diamond", "purpur_block", "diamond", "diamond", "diamond", "diamond"}));
        RECIPES.put("wayfinder_armor_trim_smithing_template", new RecipeSpec(2, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"diamond", "wayfinder_armor_trim_smithing_template", "diamond", "diamond", "terracotta", "diamond", "diamond", "diamond", "diamond"}));
        RECIPES.put("shaper_armor_trim_smithing_template", new RecipeSpec(2, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"diamond", "shaper_armor_trim_smithing_template", "diamond", "diamond", "terracotta", "diamond", "diamond", "diamond", "diamond"}));
        RECIPES.put("silence_armor_trim_smithing_template", new RecipeSpec(2, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"diamond", "silence_armor_trim_smithing_template", "diamond", "diamond", "cobbled_deepslate", "diamond", "diamond", "diamond", "diamond"}));
        RECIPES.put("raiser_armor_trim_smithing_template", new RecipeSpec(2, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"diamond", "raiser_armor_trim_smithing_template", "diamond", "diamond", "terracotta", "diamond", "diamond", "diamond", "diamond"}));
        RECIPES.put("host_armor_trim_smithing_template", new RecipeSpec(2, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"diamond", "host_armor_trim_smithing_template", "diamond", "diamond", "terracotta", "diamond", "diamond", "diamond", "diamond"}));
        RECIPES.put("flow_armor_trim_smithing_template", new RecipeSpec(2, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"diamond", "flow_armor_trim_smithing_template", "diamond", "diamond", "breeze_rod", "diamond", "diamond", "diamond", "diamond"}));
        RECIPES.put("bolt_armor_trim_smithing_template", new RecipeSpec(2, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new String[]{"diamond", "bolt_armor_trim_smithing_template", "diamond", "diamond", "waxed_copper_block", "diamond", "diamond", "diamond", "diamond"}));
        RECIPES.put("copper_grate", new RecipeSpec(4, new int[]{2, 4, 6, 8}, new String[]{"copper_block", "copper_block", "copper_block", "copper_block"}));
        RECIPES.put("exposed_copper_grate", new RecipeSpec(4, new int[]{2, 4, 6, 8}, new String[]{"exposed_copper", "exposed_copper", "exposed_copper", "exposed_copper"}));
        RECIPES.put("weathered_copper_grate", new RecipeSpec(4, new int[]{2, 4, 6, 8}, new String[]{"weathered_copper", "weathered_copper", "weathered_copper", "weathered_copper"}));
        RECIPES.put("oxidized_copper_grate", new RecipeSpec(4, new int[]{2, 4, 6, 8}, new String[]{"oxidized_copper", "oxidized_copper", "oxidized_copper", "oxidized_copper"}));
        RECIPES.put("waxed_copper_grate", new RecipeSpec(4, new int[]{2, 4, 6, 8}, new String[]{"waxed_copper_block", "waxed_copper_block", "waxed_copper_block", "waxed_copper_block"}));
        RECIPES.put("waxed_exposed_copper_grate", new RecipeSpec(4, new int[]{2, 4, 6, 8}, new String[]{"waxed_exposed_copper", "waxed_exposed_copper", "waxed_exposed_copper", "waxed_exposed_copper"}));
        RECIPES.put("waxed_weathered_copper_grate", new RecipeSpec(4, new int[]{2, 4, 6, 8}, new String[]{"waxed_weathered_copper", "waxed_weathered_copper", "waxed_weathered_copper", "waxed_weathered_copper"}));
        RECIPES.put("waxed_oxidized_copper_grate", new RecipeSpec(4, new int[]{2, 4, 6, 8}, new String[]{"waxed_oxidized_copper", "waxed_oxidized_copper", "waxed_oxidized_copper", "waxed_oxidized_copper"}));
        RECIPES.put("copper_bulb", new RecipeSpec(4, new int[]{2, 4, 5, 6, 8}, new String[]{"copper_block", "copper_block", "blaze_rod", "copper_block", "redstone"}));
        RECIPES.put("exposed_copper_bulb", new RecipeSpec(4, new int[]{2, 4, 5, 6, 8}, new String[]{"exposed_copper", "exposed_copper", "blaze_rod", "exposed_copper", "redstone"}));
        RECIPES.put("weathered_copper_bulb", new RecipeSpec(4, new int[]{2, 4, 5, 6, 8}, new String[]{"weathered_copper", "weathered_copper", "blaze_rod", "weathered_copper", "redstone"}));
        RECIPES.put("oxidized_copper_bulb", new RecipeSpec(4, new int[]{2, 4, 5, 6, 8}, new String[]{"oxidized_copper", "oxidized_copper", "blaze_rod", "oxidized_copper", "redstone"}));
        RECIPES.put("waxed_copper_bulb", new RecipeSpec(4, new int[]{2, 4, 5, 6, 8}, new String[]{"waxed_copper_block", "waxed_copper_block", "blaze_rod", "waxed_copper_block", "redstone"}));
        RECIPES.put("waxed_exposed_copper_bulb", new RecipeSpec(4, new int[]{2, 4, 5, 6, 8}, new String[]{"waxed_exposed_copper", "waxed_exposed_copper", "blaze_rod", "waxed_exposed_copper", "redstone"}));
        RECIPES.put("waxed_weathered_copper_bulb", new RecipeSpec(4, new int[]{2, 4, 5, 6, 8}, new String[]{"waxed_weathered_copper", "waxed_weathered_copper", "blaze_rod", "waxed_weathered_copper", "redstone"}));
        RECIPES.put("waxed_oxidized_copper_bulb", new RecipeSpec(4, new int[]{2, 4, 5, 6, 8}, new String[]{"waxed_oxidized_copper", "waxed_oxidized_copper", "blaze_rod", "waxed_oxidized_copper", "redstone"}));
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
                emit("craft_ok", "You already have " + haveInit + " " + targetItemKey + " in inventory (target was " + targetCount + ") — no crafting needed.");
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
                    emit("craft_ok", "You already have " + initialInventoryCount + " " + targetItemKey + " in inventory (target was " + targetCount + ") — no crafting needed.");
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
                    // Wood-family substitution: if this recipe accepts any
                    // plank (or any wooden slab), search inventory as a set
                    // instead of demanding the exact plank type the recipe
                    // was registered with. Falls through to the plain
                    // findItemSlotInHandler when the recipe/ingredient combo
                    // is not tag-based.
                    int sourceSlot;
                    if (PLANK_ACCEPTING_RECIPES.contains(targetItemKey)
                            && PLANK_ITEMS.contains(ingId)) {
                        sourceSlot = findAnyOfInHandler(h, PLANK_ITEMS, playerInvStart());
                    } else if (WOODEN_SLAB_ACCEPTING_RECIPES.contains(targetItemKey)
                            && WOODEN_SLAB_ITEMS.contains(ingId)) {
                        sourceSlot = findAnyOfInHandler(h, WOODEN_SLAB_ITEMS, playerInvStart());
                    } else {
                        sourceSlot = findItemSlotInHandler(h, ingItem, playerInvStart());
                    }
                    if (sourceSlot < 0) {
                        // "Missing planks" is friendlier than "Missing cherry_planks"
                        // when any plank would have worked.
                        String friendly = ingId;
                        if (PLANK_ACCEPTING_RECIPES.contains(targetItemKey)
                                && PLANK_ITEMS.contains(ingId)) friendly = "any planks";
                        else if (WOODEN_SLAB_ACCEPTING_RECIPES.contains(targetItemKey)
                                && WOODEN_SLAB_ITEMS.contains(ingId)) friendly = "any wooden slab";
                        emit("craft_failed", "Missing " + friendly + " for " + targetItemKey);
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
                if (delta > 0) emit("craft_ok", "You now have " + haveNow + " " + targetItemKey + " total in inventory (crafted " + delta + " this run).");
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
                // Phrasing: lead with the definitive "you now have N" so smaller
                // models don't latch onto the target field and forget the total.
                emit("craft_ok", "You now have " + haveNow + " " + targetItemKey + " total in inventory (crafted " + crafted + " this run, target was " + targetCount + ").");
                enter(State.CLEAR_GRID); return false;
            }
            if (haveNow == lastKnownCount) {
                emit("craft_failed", "You have " + haveNow + " " + targetItemKey + " in inventory (target was " + targetCount + ") — ran out of ingredients before hitting target.");
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

    /** Return the slot index of any item whose Minecraft-id path is in
     *  {@code allowedNames}. Used by wood-family recipe substitution — the
     *  registered ingredient is e.g. "cherry_planks" but the recipe accepts
     *  any of the 12 plank variants, so we scan for the SET. Bare-minecraft
     *  namespace only (no cross-mod matching). Returns -1 if none found. */
    private static int findAnyOfInHandler(ScreenHandler h, java.util.Set<String> allowedNames, int startFromSlot) {
        for (int i = startFromSlot; i < h.slots.size(); i++) {
            ItemStack s = h.slots.get(i).getStack();
            if (s.isEmpty()) continue;
            Identifier id = Registries.ITEM.getId(s.getItem());
            if (id == null) continue;
            String name = "minecraft".equals(id.getNamespace()) ? id.getPath() : id.toString();
            if (allowedNames.contains(name)) return i;
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

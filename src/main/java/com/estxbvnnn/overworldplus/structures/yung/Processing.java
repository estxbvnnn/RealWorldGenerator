package com.estxbvnnn.overworldplus.structures.yung;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.Rotation;
import org.bukkit.World;
import org.bukkit.block.Banner;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.BrewingStand;
import org.bukkit.block.Container;
import org.bukkit.block.banner.Pattern;
import org.bukkit.block.banner.PatternType;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.block.data.type.CaveVines;
import org.bukkit.block.data.type.EndPortalFrame;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Function;

/**
 * YUNG's structure processors, rebuilt for Paper (behaviour read from the mods' own code — see
 * the LGPL licences under {@code yung/}): each processor list becomes a chain of {@link Step}s
 * applied to every block as a piece goes down, to every entity it carries, and — for legs and
 * pillars that reach down to the ground — once the piece is in place.
 *
 * Every roll uses the game's own per-position seed, so a block always gets the same result.
 */
final class Processing {

    /** What a step sees of a block being placed; {@link #world()} is what stands there now. */
    record Ctx(int x, int y, int z, Block block, int seaLevel) {
        Random random() {
            return new Random(positionSeed(x, y, z));
        }

        BlockState world() {
            return block.getState();
        }
    }

    interface Step {
        default BlockState block(Ctx c, BlockState current) {
            return current;
        }

        /** false = don't spawn it. */
        default boolean entity(int x, int y, int z, Entity entity) {
            return true;
        }

        /** Runs for each marker block of the piece once it's placed. */
        default void after(World world, int x, int y, int z, Material marker) {
        }
    }

    private Processing() {}

    static List<Step> steps(List<JsonObject> processors) {
        List<Step> steps = new ArrayList<>();
        for (JsonObject p : processors) {
            Step step = step(p);
            if (step != null) steps.add(step);
        }
        return steps;
    }

    private static Step step(JsonObject p) {
        String type = JigsawLibrary.str(p, "processor_type");
        if (type == null) return null;
        String name = type.substring(type.indexOf(':') + 1);
        if (type.equals("minecraft:rule")) return rules(p);
        return switch (type.substring(0, type.indexOf(':'))) {
            case "betterstrongholds" -> stronghold(name);
            case "betterjungletemples" -> jungle(name, p);
            case "betterwitchhuts" -> witch(name);
            case "betteroceanmonuments" -> monument(name);
            default -> null;
        };
    }

    // ---- vanilla ----

    private record Rule(Material input, float probability, BlockData output) {}

    /** minecraft:rule — one generator per position, rules tried in order, first match wins. */
    private static Step rules(JsonObject p) {
        List<Rule> rules = new ArrayList<>();
        for (JsonElement r : p.getAsJsonArray("rules")) {
            JsonObject rule = r.getAsJsonObject();
            JsonObject input = rule.getAsJsonObject("input_predicate");
            Material material = Material.matchMaterial(String.valueOf(JigsawLibrary.str(input, "block")));
            BlockData output = data(rule.getAsJsonObject("output_state"));
            if (material == null || output == null) continue;
            float probability = input.has("probability") ? input.get("probability").getAsFloat() : 1f;
            rules.add(new Rule(material, probability, output));
        }
        return new Step() {
            @Override
            public BlockState block(Ctx c, BlockState current) {
                Random random = c.random();
                for (Rule rule : rules) {
                    if (rule.input() != current.getType()) continue;
                    if (rule.probability() < 1f && random.nextFloat() >= rule.probability()) continue;
                    return rule.output().createBlockState();
                }
                return current;
            }
        };
    }

    // ---- YUNG's Better Strongholds ----

    private static Step stronghold(String name) {
        return switch (name) {
            case "banner_processor" -> new Step() {
                @Override
                public BlockState block(Ctx c, BlockState current) {
                    if (current.getType() != Material.GRAY_WALL_BANNER || !(current instanceof Banner b) || b.numberOfPatterns() > 0) {
                        return current;
                    }
                    return strongholdBanner(b, c.random());
                }
            };
            case "ore_processor" -> replace(Material.NETHER_GOLD_ORE, r -> pick(r, Material.COAL_ORE,
                    Material.COAL_ORE, .2f, Material.IRON_ORE, .2f, Material.GOLD_ORE, .2f, Material.LAPIS_ORE, .15f,
                    Material.REDSTONE_ORE, .15f, Material.EMERALD_ORE, .05f, Material.DIAMOND_ORE, .05f).createBlockData());
            case "rare_block_processor" -> replace(Material.PURPUR_BLOCK, r -> pick(r, Material.IRON_BLOCK,
                    Material.IRON_BLOCK, .3f, Material.QUARTZ_BLOCK, .3f, Material.GOLD_BLOCK, .3f, Material.DIAMOND_BLOCK, .1f).createBlockData());
            case "end_portal_frame_processor" -> new Step() {
                @Override
                public BlockState block(Ctx c, BlockState current) {
                    if (current.getBlockData() instanceof EndPortalFrame frame && c.random().nextFloat() < 0.1f) {
                        frame.setEye(true);
                        current.setBlockData(frame);
                    }
                    return current;
                }
            };
            case "leg_processor" -> new Step() {
                @Override
                public BlockState block(Ctx c, BlockState current) {
                    if (current.getType() == Material.YELLOW_STAINED_GLASS) return strongholdBrick(c.random()).createBlockData().createBlockState();
                    if (current.getType() == Material.ORANGE_STAINED_GLASS) return Material.CYAN_TERRACOTTA.createBlockData().createBlockState();
                    return current;
                }

                @Override
                public void after(World world, int x, int y, int z, Material marker) {
                    if (marker == Material.YELLOW_STAINED_GLASS || marker == Material.ORANGE_STAINED_GLASS) {
                        strongholdLeg(world, world.getBlockAt(x, y, z), new Random(positionSeed(x, y, z)));
                    }
                }
            };
            case "redstone_processor" -> new Step() {
                @Override
                public void after(World world, int x, int y, int z, Material marker) {
                    if (marker != Material.REDSTONE_WIRE) return;
                    Block below = world.getBlockAt(x, y - 1, z);
                    if (world.getBlockAt(x, y, z).getType() == Material.REDSTONE_WIRE && !below.getType().isSolid()) {
                        below.setType(Material.STONE_BRICKS, false);
                    }
                }
            };
            case "armorstand_processor" -> new Step() {
                @Override
                public boolean entity(int x, int y, int z, Entity entity) {
                    if (entity instanceof ArmorStand stand) dressArmorStand(stand, new Random(positionSeed(x, y, z)));
                    return true;
                }
            };
            case "itemframe_processor" -> new Step() {
                @Override
                public boolean entity(int x, int y, int z, Entity entity) {
                    return !(entity instanceof ItemFrame frame) || fillStrongholdFrame(frame, new Random(positionSeed(x, y, z)));
                }
            };
            default -> null; // ruin_processor: off in YUNG's own default config
        };
    }

    private static Material strongholdBrick(Random r) {
        return pick(r, Material.STONE_BRICKS, Material.MOSSY_STONE_BRICKS, .3f, Material.CRACKED_STONE_BRICKS, .2f,
                Material.INFESTED_STONE_BRICKS, .05f);
    }

    /** A stone-brick pillar down through air or water to the ground, flared with stairs and slabs at the top. */
    private static void strongholdLeg(World world, Block top, Random random) {
        Block at = top.getRelative(BlockFace.DOWN);
        for (int below = 1; at.getY() > world.getMinHeight() && open(at); below++) {
            at.setType(strongholdBrick(random), false);
            if (below <= 2) {
                for (BlockFace side : HORIZONTAL) {
                    Block next = at.getRelative(side);
                    if (open(next)) next.setBlockData(upsideDownStairs(Material.STONE_BRICK_STAIRS, side.getOppositeFace()), false);
                    Block far = next.getRelative(side);
                    if (!open(far)) continue;
                    if (below == 1) {
                        far.setBlockData(upsideDownStairs(Material.STONE_BRICK_STAIRS, side), false);
                    } else {
                        Slab slab = (Slab) Material.STONE_BRICK_SLAB.createBlockData();
                        slab.setType(Slab.Type.TOP);
                        far.setBlockData(slab, false);
                    }
                }
            }
            at = at.getRelative(BlockFace.DOWN);
        }
    }

    private static BlockState strongholdBanner(Banner original, Random random) {
        BlockFace facing = original.getBlockData() instanceof Directional d ? d.getFacing() : BlockFace.NORTH;
        int which = random.nextInt(3);
        Material base = which == 0 ? Material.MAGENTA_WALL_BANNER : which == 1 ? Material.BLACK_WALL_BANNER : Material.PURPLE_WALL_BANNER;
        BlockData data = base.createBlockData();
        if (data instanceof Directional d) d.setFacing(facing);
        BlockState state = data.createBlockState();
        if (!(state instanceof Banner banner)) return original;
        banner.setPatterns(switch (which) {
            case 0 -> List.of(new Pattern(DyeColor.WHITE, PatternType.SMALL_STRIPES), new Pattern(DyeColor.BLACK, PatternType.STRIPE_TOP),
                    new Pattern(DyeColor.BLACK, PatternType.HALF_HORIZONTAL_BOTTOM), new Pattern(DyeColor.BLACK, PatternType.BORDER),
                    new Pattern(DyeColor.BLACK, PatternType.STRIPE_MIDDLE), new Pattern(DyeColor.BLACK, PatternType.STRIPE_CENTER));
            case 1 -> List.of(new Pattern(DyeColor.GRAY, PatternType.STRIPE_BOTTOM), new Pattern(DyeColor.BLACK, PatternType.STRIPE_CENTER),
                    new Pattern(DyeColor.GRAY, PatternType.HALF_HORIZONTAL), new Pattern(DyeColor.BLACK, PatternType.CREEPER),
                    new Pattern(DyeColor.BLACK, PatternType.SKULL));
            default -> List.of(new Pattern(DyeColor.MAGENTA, PatternType.SMALL_STRIPES), new Pattern(DyeColor.PURPLE, PatternType.BRICKS),
                    new Pattern(DyeColor.MAGENTA, PatternType.CURLY_BORDER), new Pattern(DyeColor.BLACK, PatternType.BORDER));
        });
        return banner;
    }

    private static void dressArmorStand(ArmorStand stand, Random r) {
        EntityEquipment eq = stand.getEquipment();
        boolean rare = eq.getHelmet().getType() == Material.DIAMOND_HELMET;
        Material boots = rare ? pick(r, Material.AIR, Material.DIAMOND_BOOTS, .3f)
                : pick(r, Material.AIR, Material.CHAINMAIL_BOOTS, .3f, Material.LEATHER_BOOTS, .1f, Material.IRON_BOOTS, .3f);
        Material legs = rare ? pick(r, Material.AIR, Material.DIAMOND_LEGGINGS, .3f)
                : pick(r, Material.AIR, Material.CHAINMAIL_LEGGINGS, .3f, Material.LEATHER_LEGGINGS, .1f, Material.IRON_LEGGINGS, .3f);
        Material chest = rare ? pick(r, Material.AIR, Material.DIAMOND_CHESTPLATE, .3f)
                : pick(r, Material.AIR, Material.CHAINMAIL_CHESTPLATE, .3f, Material.LEATHER_CHESTPLATE, .1f, Material.IRON_CHESTPLATE, .3f);
        Material head = rare ? pick(r, Material.AIR, Material.DIAMOND_HELMET, .3f, Material.CARVED_PUMPKIN, .2f)
                : pick(r, Material.AIR, Material.CHAINMAIL_HELMET, .3f, Material.LEATHER_HELMET, .1f, Material.IRON_HELMET, .3f,
                Material.CARVED_PUMPKIN, .01f);
        if (boots != Material.AIR) eq.setBoots(new ItemStack(boots));
        if (legs != Material.AIR) eq.setLeggings(new ItemStack(legs));
        if (chest != Material.AIR) eq.setChestplate(new ItemStack(chest));
        if (head != Material.AIR) eq.setHelmet(new ItemStack(head));
    }

    /** Armoury frames (an iron sword) and storage frames (bread) get something random; YUNG drops any other frame. */
    private static boolean fillStrongholdFrame(ItemFrame frame, Random r) {
        Material item = switch (frame.getItem().getType()) {
            case IRON_SWORD -> pick(r, Material.AIR, Material.STONE_SWORD, .05f, Material.IRON_SWORD, .1f, Material.GOLDEN_SWORD, .05f,
                    Material.STONE_AXE, .05f, Material.IRON_AXE, .1f, Material.GOLDEN_AXE, .05f, Material.SHIELD, .1f, Material.BOW, .1f,
                    Material.ARROW, .05f, Material.NAME_TAG, .05f);
            case BREAD -> pick(r, Material.AIR, Material.PAPER, .25f, Material.MAP, .25f, Material.FLINT, .05f, Material.COMPASS, .05f,
                    Material.LEAD, .05f, Material.CAKE, .05f, Material.SLIME_BALL, .05f, Material.BEETROOT_SEEDS, .025f,
                    Material.WHEAT_SEEDS, .025f, Material.MELON_SEEDS, .025f, Material.PUMPKIN_SEEDS, .025f, Material.RABBIT_FOOT, .01f);
            default -> Material.AIR;
        };
        if (item == Material.AIR) return false;
        frame.setItem(new ItemStack(item));
        frame.setRotation(Rotation.values()[r.nextInt(8)]);
        return true;
    }

    // ---- YUNG's Better Jungle Temples ----

    private static Step jungle(String name, JsonObject p) {
        return switch (name) {
            case "blast_furnace_processor" -> new Step() {
                @Override
                public BlockState block(Ctx c, BlockState current) {
                    if (current.getType() != Material.BLAST_FURNACE) return current;
                    BlockData out = pick(c.random(), Material.DISPENSER, Material.DROPPER, .4f, Material.OBSERVER, .2f).createBlockData();
                    if (current.getBlockData() instanceof Directional from && out instanceof Directional to) to.setFacing(from.getFacing());
                    return out.createBlockState();
                }
            };
            case "block_replace_processor" -> blockReplace(p);
            case "cave_vine_decoration_processor" -> new Step() {
                @Override
                public BlockState block(Ctx c, BlockState current) {
                    if (current.getType() != Material.GREEN_STAINED_GLASS) return current;
                    Random r = c.random();
                    float roll = r.nextFloat();
                    BlockData out;
                    if (roll < 0.05f) out = topSlab(Material.COBBLESTONE_SLAB);
                    else if (roll < 0.10f) out = topSlab(Material.MOSSY_COBBLESTONE_SLAB);
                    else if (roll < 0.12f) {
                        CaveVines vines = (CaveVines) Material.CAVE_VINES.createBlockData();
                        vines.setAge(Math.min(vines.getMaximumAge(), r.nextInt(26)));
                        vines.setBerries(r.nextFloat() < 0.25f);
                        out = vines;
                    } else if (roll < 0.14f) out = Material.HANGING_ROOTS.createBlockData();
                    else out = Material.AIR.createBlockData();
                    return out.createBlockState();
                }
            };
            case "empty_dispenser_processor" -> new Step() {
                @Override
                public BlockState block(Ctx c, BlockState current) {
                    if (current.getType() != Material.DISPENSER || !(current instanceof Container box)) return current;
                    Inventory inv = box.getSnapshotInventory();
                    if (!inv.isEmpty()) return current;
                    Random r = c.random();
                    for (int slot = 0; slot < 9; slot++) {
                        float f = r.nextFloat();
                        if (f < 0.2f) inv.setItem(slot, new ItemStack(Material.ARROW));
                        else if (f < 0.3f) inv.setItem(slot, tippedArrow(PotionType.POISON));
                    }
                    return current;
                }
            };
            case "fireball_dispenser_processor" -> new Step() {
                @Override
                public BlockState block(Ctx c, BlockState current) {
                    if (current.getType() != Material.ORANGE_CONCRETE) return current;
                    Directional data = (Directional) Material.DISPENSER.createBlockData();
                    data.setFacing(BlockFace.UP);
                    BlockState state = data.createBlockState();
                    if (state instanceof Container box) {
                        Random r = c.random();
                        for (int slot = 0; slot < 9; slot++) {
                            if (r.nextFloat() < 0.1f || slot == 4) box.getSnapshotInventory().setItem(slot, new ItemStack(Material.FIRE_CHARGE));
                        }
                    }
                    return state;
                }
            };
            case "pillar_processor" -> pillar(p);
            case "torch_processor" -> new Step() {
                @Override
                public BlockState block(Ctx c, BlockState current) {
                    if ((current.getType() == Material.TORCH || current.getType() == Material.WALL_TORCH) && c.random().nextFloat() < 0.9f) {
                        return Material.AIR.createBlockData().createBlockState();
                    }
                    return current;
                }
            };
            default -> null; // item_frame_processor only pins the frame's block position, which Paper already does
        };
    }

    /** YUNG's block_replace: one block becomes a random choice, optionally keeping its stair/slab/wall shape. */
    private static Step blockReplace(JsonObject p) {
        Material target = material(p.getAsJsonObject("target_block"));
        Randomizer output = Randomizer.of(p.getAsJsonObject("output"));
        boolean copy = p.has("copy_input_properties") && p.get("copy_input_properties").getAsBoolean();
        boolean facing = p.has("randomize_facing") && p.get("randomize_facing").getAsBoolean();
        boolean half = p.has("randomize_half") && p.get("randomize_half").getAsBoolean();
        if (target == null || output == null) return null;
        return new Step() {
            @Override
            public BlockState block(Ctx c, BlockState current) {
                if (current.getType() != target) return current;
                Random r = c.random();
                BlockData out = output.get(r);
                if (copy) out = copyShape(current.getBlockData(), out);
                if (facing && out instanceof Directional d) {
                    BlockFace face = HORIZONTAL[r.nextInt(4)];
                    if (d.getFaces().contains(face)) d.setFacing(face);
                }
                if (half) {
                    if (out instanceof Bisected b) b.setHalf(r.nextBoolean() ? Bisected.Half.TOP : Bisected.Half.BOTTOM);
                    if (out instanceof Slab s) s.setType(r.nextBoolean() ? Slab.Type.TOP : Slab.Type.BOTTOM);
                }
                return out.createBlockState();
            }
        };
    }

    /** YUNG's pillar: the marker becomes a random block, and a pillar of random blocks runs down to the ground. */
    private static Step pillar(JsonObject p) {
        Material target = material(p.getAsJsonObject("target_block"));
        Randomizer top = Randomizer.of(p.getAsJsonObject("target_block_output"));
        Randomizer column = Randomizer.of(p.getAsJsonObject("pillar_states"));
        if (target == null || top == null || column == null) return null;
        return new Step() {
            @Override
            public BlockState block(Ctx c, BlockState current) {
                return current.getType() == target ? top.get(c.random()).createBlockState() : current;
            }

            @Override
            public void after(World world, int x, int y, int z, Material marker) {
                if (marker == target) extendDown(world, x, y, z, r -> column.get(r), new Random(positionSeed(x, y, z)));
            }
        };
    }

    // ---- YUNG's Better Witch Huts ----

    private static Step witch(String name) {
        return switch (name) {
            case "brewing_stand_processor" -> new Step() {
                @Override
                public BlockState block(Ctx c, BlockState current) {
                    if (current instanceof BrewingStand stand) fillBrewingStand(stand, c.random());
                    return current;
                }
            };
            case "fence_leg_processor" -> new Step() {
                @Override
                public BlockState block(Ctx c, BlockState current) {
                    return current.getType() == Material.CRIMSON_FENCE ? Material.OAK_FENCE.createBlockData().createBlockState() : current;
                }

                @Override
                public void after(World world, int x, int y, int z, Material marker) {
                    if (marker == Material.CRIMSON_FENCE) extendDown(world, x, y, z, r -> Material.OAK_FENCE.createBlockData(), null);
                }
            };
            case "leg_processor" -> new Step() {
                @Override
                public BlockState block(Ctx c, BlockState current) {
                    return current.getType() == Material.BROWN_STAINED_GLASS ? upright(Material.OAK_LOG).createBlockState() : current;
                }

                @Override
                public void after(World world, int x, int y, int z, Material marker) {
                    if (marker == Material.BROWN_STAINED_GLASS) extendDown(world, x, y, z, r -> upright(Material.OAK_LOG), null);
                }
            };
            case "potted_mushroom_processor" -> replace(Material.POTTED_RED_MUSHROOM, r -> pick(r, Material.POTTED_RED_MUSHROOM,
                    Material.POTTED_BROWN_MUSHROOM, .2f, Material.POTTED_CORNFLOWER, .1f, Material.POTTED_CACTUS, .1f,
                    Material.POTTED_DEAD_BUSH, .1f, Material.POTTED_FERN, .1f, Material.POTTED_AZALEA_BUSH, .1f).createBlockData());
            case "witch_circle_processor" -> new Step() {
                @Override
                public BlockState block(Ctx c, BlockState current) {
                    Random r = c.random();
                    return switch (current.getType()) {
                        case STONE_BRICKS, GRAY_STAINED_GLASS -> circleBrick(r).createBlockData().createBlockState();
                        case MOSSY_COBBLESTONE -> pick(r, Material.COBBLESTONE, Material.MOSSY_COBBLESTONE, .6f, Material.COARSE_DIRT, .1f)
                                .createBlockData().createBlockState();
                        // Keeps the stair's facing — YUNG's own version resets it, turning every stair north.
                        case STONE_BRICK_STAIRS -> r.nextFloat() < .6f
                                ? copyShape(current.getBlockData(), Material.MOSSY_STONE_BRICK_STAIRS.createBlockData()).createBlockState()
                                : current;
                        default -> current;
                    };
                }

                @Override
                public void after(World world, int x, int y, int z, Material marker) {
                    if (marker == Material.GRAY_STAINED_GLASS) {
                        extendDown(world, x, y, z, r -> circleBrick(r).createBlockData(), new Random(positionSeed(x, y, z)));
                    }
                }
            };
            default -> null;
        };
    }

    private static Material circleBrick(Random r) {
        return pick(r, Material.STONE_BRICKS, Material.MOSSY_STONE_BRICKS, .6f, Material.CRACKED_STONE_BRICKS, .1f);
    }

    private static void fillBrewingStand(BrewingStand stand, Random r) {
        String[][] recipes = {
                {"GLISTERING_MELON_SLICE", "HEALING"}, {"SUGAR", "SWIFTNESS"}, {"PUFFERFISH", "WATER_BREATHING"},
                {"GOLDEN_CARROT", "NIGHT_VISION"}, {"PHANTOM_MEMBRANE", "SLOW_FALLING"}};
        String[] recipe = recipes[r.nextInt(recipes.length)];
        Inventory inv = stand.getSnapshotInventory();
        inv.setItem(3, new ItemStack(Material.valueOf(recipe[0]), r.nextInt(4) + 2));
        PotionType potion = PotionType.valueOf(recipe[1]);
        inv.setItem(1, potion(potion));
        if (r.nextFloat() < 0.5f) inv.setItem(r.nextBoolean() ? 0 : 2, potion(potion));
    }

    // ---- YUNG's Better Ocean Monuments ----

    private static Step monument(String name) {
        return switch (name) {
            case "air_processor" -> new Step() {
                @Override
                public BlockState block(Ctx c, BlockState current) {
                    return current.getType() == Material.AIR && c.y() < c.seaLevel() ? Material.WATER.createBlockData().createBlockState() : current;
                }
            };
            case "leg_processor" -> new Step() {
                @Override
                public BlockState block(Ctx c, BlockState current) {
                    return current.getType() == Material.BLUE_STAINED_GLASS ? Material.PRISMARINE_BRICKS.createBlockData().createBlockState() : current;
                }

                @Override
                public void after(World world, int x, int y, int z, Material marker) {
                    if (marker == Material.BLUE_STAINED_GLASS) extendDown(world, x, y, z, r -> Material.PRISMARINE_BRICKS.createBlockData(), null);
                }
            };
            case "random_dark_prismarine_slab_decoration_processor" -> slabDecoration(Material.BLUE_CONCRETE, Material.DARK_PRISMARINE_SLAB, Material.WATER);
            case "random_prismarine_slab_decoration_processor" -> slabDecoration(Material.LIME_CONCRETE, Material.PRISMARINE_SLAB, Material.AIR);
            case "random_oxidization_processor" -> new Step() {
                @Override
                public BlockState block(Ctx c, BlockState current) {
                    Material[] variants = switch (current.getType()) {
                        case OXIDIZED_COPPER -> new Material[]{Material.EXPOSED_COPPER, Material.WEATHERED_COPPER};
                        case OXIDIZED_CUT_COPPER -> new Material[]{Material.EXPOSED_CUT_COPPER, Material.WEATHERED_CUT_COPPER};
                        case OXIDIZED_CUT_COPPER_STAIRS -> new Material[]{Material.EXPOSED_CUT_COPPER_STAIRS, Material.WEATHERED_CUT_COPPER_STAIRS};
                        case OXIDIZED_CUT_COPPER_SLAB -> new Material[]{Material.EXPOSED_CUT_COPPER_SLAB, Material.WEATHERED_CUT_COPPER_SLAB};
                        default -> null;
                    };
                    if (variants == null) return current;
                    Random r = c.random();
                    Material to = r.nextFloat() < 0.1f ? variants[0] : r.nextFloat() < 0.3f ? variants[1] : null;
                    return to == null ? current : copyShape(current.getBlockData(), to.createBlockData()).createBlockState();
                }
            };
            case "random_sponge_processor" -> replace(Material.ORANGE_STAINED_GLASS,
                    r -> (r.nextFloat() < 0.75f ? Material.WET_SPONGE : Material.WATER).createBlockData());
            case "sand_gravel_processor" -> replace(Material.YELLOW_STAINED_GLASS, r -> Material.GRAVEL.createBlockData());
            case "seagrass_processor" -> new Step() {
                @Override
                public BlockState block(Ctx c, BlockState current) {
                    return switch (current.getType()) {
                        case RED_SANDSTONE_SLAB -> tallSeagrass(Bisected.Half.BOTTOM).createBlockState();
                        case END_STONE_BRICK_SLAB -> tallSeagrass(Bisected.Half.TOP).createBlockState();
                        case BRICK_SLAB -> Material.SEAGRASS.createBlockData().createBlockState();
                        default -> current;
                    };
                }
            };
            case "structure_void_processor" -> new Step() {
                @Override
                public BlockState block(Ctx c, BlockState current) {
                    return current.getType() == Material.PURPUR_SLAB || current.getType() == Material.BROWN_WOOL ? c.world() : current;
                }
            };
            case "waterlog_processor" -> new Step() {
                @Override
                public BlockState block(Ctx c, BlockState current) {
                    if (c.y() < c.seaLevel() && current.getBlockData() instanceof Waterlogged w && !w.isWaterlogged()) {
                        w.setWaterlogged(true);
                        current.setBlockData(w);
                    }
                    return current;
                }
            };
            default -> null;
        };
    }

    /** A marker becomes a waterlogged top slab (40%), a bottom slab (the next 80% of the rest), or else {@code otherwise}. */
    private static Step slabDecoration(Material marker, Material slabType, Material otherwise) {
        return new Step() {
            @Override
            public BlockState block(Ctx c, BlockState current) {
                if (current.getType() != marker) return current;
                Random r = c.random();
                BlockData out;
                if (r.nextFloat() < 0.4f) out = waterloggedSlab(slabType, Slab.Type.TOP);
                else if (r.nextFloat() < 0.8f) out = waterloggedSlab(slabType, Slab.Type.BOTTOM);
                else out = otherwise.createBlockData();
                return out.createBlockState();
            }
        };
    }

    // ---- shared helpers ----

    private static final BlockFace[] HORIZONTAL = {BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST};

    private static Step replace(Material from, Function<Random, BlockData> to) {
        return new Step() {
            @Override
            public BlockState block(Ctx c, BlockState current) {
                return current.getType() == from ? to.apply(c.random()).createBlockState() : current;
            }
        };
    }

    /** Down from under (x, y, z) through air and water to whatever's solid, one block of {@code supply} at a time. */
    private static void extendDown(World world, int x, int y, int z, Function<Random, BlockData> supply, Random random) {
        Random r = random != null ? random : new Random(positionSeed(x, y, z));
        for (int at = y - 1; at > world.getMinHeight(); at--) {
            Block block = world.getBlockAt(x, at, z);
            if (!open(block)) break;
            block.setBlockData(supply.apply(r), false);
        }
    }

    private static boolean open(Block block) {
        return block.getType().isAir() || block.isLiquid();
    }

    /** YUNG's randomizer: one roll, walk the cumulative chances, the default if none is reached. */
    static Material pick(Random random, Material fallback, Object... pairs) {
        float target = random.nextFloat(), sum = 0;
        for (int i = 0; i < pairs.length; i += 2) {
            sum += (Float) pairs[i + 1];
            if (sum >= target) return (Material) pairs[i];
        }
        return fallback;
    }

    /** A JSON randomizer ({"defaultBlockState": ..., "entries": [{"blockState": ..., "probability": ...}]}). */
    private record Randomizer(BlockData fallback, List<BlockData> states, List<Float> chances) {
        static Randomizer of(JsonObject json) {
            if (json == null) return null;
            BlockData fallback = data(json.getAsJsonObject("defaultBlockState"));
            if (fallback == null) return null;
            List<BlockData> states = new ArrayList<>();
            List<Float> chances = new ArrayList<>();
            if (json.has("entries")) {
                for (JsonElement e : json.getAsJsonArray("entries")) {
                    BlockData state = data(e.getAsJsonObject().getAsJsonObject("blockState"));
                    if (state == null) continue;
                    states.add(state);
                    chances.add(e.getAsJsonObject().get("probability").getAsFloat());
                }
            }
            return new Randomizer(fallback, states, chances);
        }

        BlockData get(Random random) {
            float target = random.nextFloat(), sum = 0;
            for (int i = 0; i < states.size(); i++) {
                sum += chances.get(i);
                if (sum >= target) return states.get(i).clone();
            }
            return fallback.clone();
        }
    }

    /** {"Name": "minecraft:x", "Properties": {...}} as block data; null if this server doesn't know it. */
    static BlockData data(JsonObject state) {
        if (state == null || !state.has("Name")) return null;
        StringBuilder s = new StringBuilder(state.get("Name").getAsString());
        if (state.has("Properties")) {
            StringBuilder props = new StringBuilder();
            state.getAsJsonObject("Properties").entrySet().forEach(p ->
                    props.append(props.isEmpty() ? "" : ",").append(p.getKey()).append('=').append(p.getValue().getAsString()));
            s.append('[').append(props).append(']');
        }
        try {
            return Bukkit.createBlockData(s.toString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Material material(JsonObject state) {
        BlockData data = data(state);
        return data == null ? null : data.getMaterial();
    }

    /** Carries facing, half and slab type from one block to another where both have them. */
    private static BlockData copyShape(BlockData from, BlockData to) {
        if (from instanceof Directional a && to instanceof Directional b && b.getFaces().contains(a.getFacing())) b.setFacing(a.getFacing());
        if (from instanceof Bisected a && to instanceof Bisected b) b.setHalf(a.getHalf());
        if (from instanceof Stairs a && to instanceof Stairs b) b.setShape(a.getShape());
        if (from instanceof Slab a && to instanceof Slab b) b.setType(a.getType());
        if (from instanceof Waterlogged a && to instanceof Waterlogged b) b.setWaterlogged(a.isWaterlogged());
        if (from instanceof org.bukkit.block.data.type.Wall a && to instanceof org.bukkit.block.data.type.Wall b) {
            b.setUp(a.isUp());
            for (BlockFace f : HORIZONTAL) b.setHeight(f, a.getHeight(f));
        }
        return to;
    }

    private static BlockData upsideDownStairs(Material type, BlockFace facing) {
        Stairs stairs = (Stairs) type.createBlockData();
        stairs.setHalf(Bisected.Half.TOP);
        stairs.setFacing(facing);
        return stairs;
    }

    private static BlockData topSlab(Material type) {
        Slab slab = (Slab) type.createBlockData();
        slab.setType(Slab.Type.TOP);
        return slab;
    }

    private static BlockData waterloggedSlab(Material type, Slab.Type half) {
        Slab slab = (Slab) type.createBlockData();
        slab.setType(half);
        slab.setWaterlogged(true);
        return slab;
    }

    private static BlockData tallSeagrass(Bisected.Half half) {
        Bisected grass = (Bisected) Material.TALL_SEAGRASS.createBlockData();
        grass.setHalf(half);
        return grass;
    }

    private static BlockData upright(Material log) {
        BlockData data = log.createBlockData();
        if (data instanceof Orientable o) o.setAxis(org.bukkit.Axis.Y);
        return data;
    }

    private static ItemStack potion(PotionType type) {
        ItemStack item = new ItemStack(Material.POTION);
        if (item.getItemMeta() instanceof PotionMeta meta) {
            meta.setBasePotionType(type);
            item.setItemMeta(meta);
        }
        return item;
    }

    private static ItemStack tippedArrow(PotionType type) {
        ItemStack item = new ItemStack(Material.TIPPED_ARROW);
        if (item.getItemMeta() instanceof PotionMeta meta) {
            meta.setBasePotionType(type);
            item.setItemMeta(meta);
        }
        return item;
    }

    /** The game's own per-position seed (Mth.getSeed): the same block always gets the same roll. */
    static long positionSeed(int x, int y, int z) {
        long l = (x * 3129871L) ^ (z * 116129781L) ^ y;
        l = l * l * 42317861L + l * 11L;
        return l >> 16;
    }
}

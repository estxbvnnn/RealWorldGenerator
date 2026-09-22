package com.estxbvnnn.overworldplus.structures;

import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.generator.structure.GeneratedStructure;
import org.bukkit.generator.structure.Structure;
import org.bukkit.util.BoundingBox;

import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Detects real vanilla structures (via Chunk#getStructures(), the actual
 * generated-structure registry — not guesswork) and embellishes them:
 * villages get exterior flourishes, strongholds look older and more
 * crumbled, ruined portals get overgrowth and scattered debris. Every touch
 * only ever replaces AIR (or, for the stronghold's stone, blocks it's known
 * to be made of) — it never risks blocking a door or corrupting the layout.
 */
public final class StructureEnhancer {

    private static final Set<Structure> VILLAGES = Set.of(
            Structure.VILLAGE_PLAINS, Structure.VILLAGE_DESERT, Structure.VILLAGE_SAVANNA,
            Structure.VILLAGE_SNOWY, Structure.VILLAGE_TAIGA
    );
    private static final Set<Structure> RUINED_PORTALS = Set.of(
            Structure.RUINED_PORTAL, Structure.RUINED_PORTAL_DESERT, Structure.RUINED_PORTAL_JUNGLE,
            Structure.RUINED_PORTAL_MOUNTAIN, Structure.RUINED_PORTAL_OCEAN, Structure.RUINED_PORTAL_SWAMP
    );
    private static final Material[] VILLAGE_FLOWERS = {
            Material.POPPY, Material.DANDELION, Material.CORNFLOWER, Material.AZURE_BLUET, Material.OXEYE_DAISY
    };
    private static final Material[] DEBRIS = { Material.NETHERRACK, Material.BASALT, Material.COBBLESTONE, Material.GRAVEL };

    private StructureEnhancer() {}

    public static void enhance(Chunk chunk, FileConfiguration config) {
        for (GeneratedStructure generated : chunk.getStructures()) {
            Structure type = generated.getStructure();
            BoundingBox box = generated.getBoundingBox();

            if (VILLAGES.contains(type)) {
                decorateVillage(chunk, box, config);
            } else if (type == Structure.STRONGHOLD) {
                decorateStronghold(chunk, box, config);
            } else if (RUINED_PORTALS.contains(type)) {
                decorateRuinedPortal(chunk, box, config);
            }
        }
    }

    private static void decorateVillage(Chunk chunk, BoundingBox box, FileConfiguration config) {
        World world = chunk.getWorld();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        double chance = config.getDouble("structures.village.spot-chance", 0.7);

        for (int[] xz : columnsIn(chunk, box)) {
            if (rnd.nextDouble() >= chance) continue;
            Block ground = solidGround(world, xz[0], xz[1]);
            decorateVillageSpot(ground, rnd);
        }
    }

    private static void decorateVillageSpot(Block ground, ThreadLocalRandom rnd) {
        Block above = ground.getRelative(BlockFace.UP);
        Material type = ground.getType();

        if (above.getType() != Material.AIR) return;

        if (type == Material.GRASS_BLOCK || type == Material.DIRT_PATH) {
            above.setType(VILLAGE_FLOWERS[rnd.nextInt(VILLAGE_FLOWERS.length)], false);
        } else if (isFence(type) && rnd.nextDouble() < 0.4) {
            above.setType(Material.LANTERN, false);
        }
    }

    private static boolean isFence(Material type) {
        return type.name().endsWith("_FENCE");
    }

    private static void decorateStronghold(Chunk chunk, BoundingBox box, FileConfiguration config) {
        World world = chunk.getWorld();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        double decayChance = config.getDouble("structures.stronghold.decay-chance", 0.7);

        int minX = clampMin(chunk.getX(), box.getMinX());
        int maxX = clampMax(chunk.getX(), box.getMaxX());
        int minZ = clampMin(chunk.getZ(), box.getMinZ());
        int maxZ = clampMax(chunk.getZ(), box.getMaxZ());
        int minY = (int) Math.floor(box.getMinY());
        int maxY = (int) Math.floor(box.getMaxY());
        if (minX > maxX || minZ > maxZ || minY > maxY) return;

        int samples = config.getInt("structures.stronghold.samples", 60);
        for (int i = 0; i < samples; i++) {
            int x = rnd.nextInt(minX, maxX + 1);
            int y = rnd.nextInt(minY, maxY + 1);
            int z = rnd.nextInt(minZ, maxZ + 1);

            Block block = world.getBlockAt(x, y, z);
            Material decayed = decayedVariant(block.getType());
            if (decayed != null && rnd.nextDouble() < decayChance) {
                block.setType(decayed, false);
            }
        }
    }

    private static Material decayedVariant(Material type) {
        return switch (type) {
            case STONE_BRICKS -> Material.CRACKED_STONE_BRICKS;
            case COBBLESTONE -> Material.MOSSY_COBBLESTONE;
            case STONE_BRICK_SLAB -> Material.MOSSY_STONE_BRICK_SLAB;
            default -> null;
        };
    }

    private static void decorateRuinedPortal(Chunk chunk, BoundingBox box, FileConfiguration config) {
        World world = chunk.getWorld();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        double vineChance = config.getDouble("structures.ruined-portal.vine-chance", 0.6);
        double debrisChance = config.getDouble("structures.ruined-portal.debris-chance", 0.7);

        int minX = clampMin(chunk.getX(), box.getMinX());
        int maxX = clampMax(chunk.getX(), box.getMaxX());
        int minZ = clampMin(chunk.getZ(), box.getMinZ());
        int maxZ = clampMax(chunk.getZ(), box.getMaxZ());
        int minY = (int) Math.floor(box.getMinY());
        int maxY = (int) Math.floor(box.getMaxY());
        if (minX > maxX || minZ > maxZ || minY > maxY) return;

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    Block block = world.getBlockAt(x, y, z);
                    if (isObsidian(block.getType()) && rnd.nextDouble() < vineChance) {
                        hangVineOnFace(block, rnd);
                    }
                }
            }
        }

        scatterDebris(world, (minX + maxX) / 2, (minZ + maxZ) / 2, debrisChance, rnd);
    }

    private static boolean isObsidian(Material type) {
        return type == Material.OBSIDIAN || type == Material.CRYING_OBSIDIAN;
    }

    private static void hangVineOnFace(Block obsidian, ThreadLocalRandom rnd) {
        BlockFace[] faces = { BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST };
        BlockFace face = faces[rnd.nextInt(faces.length)];
        Block target = obsidian.getRelative(face);
        if (target.getType() != Material.AIR) return;

        target.setType(Material.VINE, false);
        if (target.getBlockData() instanceof MultipleFacing vine) {
            vine.setFace(face.getOppositeFace(), true);
            target.setBlockData(vine, false);
        }
    }

    private static void scatterDebris(World world, int cx, int cz, double chance, ThreadLocalRandom rnd) {
        for (int i = 0; i < 8; i++) {
            int x = cx + rnd.nextInt(-4, 5);
            int z = cz + rnd.nextInt(-4, 5);
            Block ground = solidGround(world, x, z);
            Block above = ground.getRelative(BlockFace.UP);
            if (above.getType() == Material.AIR && rnd.nextDouble() < chance) {
                above.setType(DEBRIS[rnd.nextInt(DEBRIS.length)], false);
            }
        }
    }

    private static Block solidGround(World world, int x, int z) {
        Block block = world.getHighestBlockAt(x, z, org.bukkit.HeightMap.WORLD_SURFACE);
        while (block.getY() > world.getMinHeight() && !block.getType().isSolid()) {
            block = block.getRelative(BlockFace.DOWN);
        }
        return block;
    }

    private static int clampMin(int chunkCoord, double boxMin) {
        return Math.max(chunkCoord << 4, (int) Math.floor(boxMin));
    }

    private static int clampMax(int chunkCoord, double boxMax) {
        return Math.min((chunkCoord << 4) + 15, (int) Math.floor(boxMax));
    }

    private static java.util.List<int[]> columnsIn(Chunk chunk, BoundingBox box) {
        java.util.List<int[]> columns = new java.util.ArrayList<>();
        int minX = clampMin(chunk.getX(), box.getMinX());
        int maxX = clampMax(chunk.getX(), box.getMaxX());
        int minZ = clampMin(chunk.getZ(), box.getMinZ());
        int maxZ = clampMax(chunk.getZ(), box.getMaxZ());
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                columns.add(new int[]{x, z});
            }
        }
        return columns;
    }
}

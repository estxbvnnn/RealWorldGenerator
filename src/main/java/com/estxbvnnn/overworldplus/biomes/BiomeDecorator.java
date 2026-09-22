package com.estxbvnnn.overworldplus.biomes;

import com.estxbvnnn.overworldplus.Area;
import com.estxbvnnn.overworldplus.CoordHash;
import com.estxbvnnn.overworldplus.PlantPlacer;
import com.estxbvnnn.overworldplus.SolidGroundFinder;
import org.bukkit.HeightMap;
import org.bukkit.Material;
import org.bukkit.block.Biome;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.Set;

/**
 * Covers every overworld land biome the tree/terrain/mountain systems don't
 * already touch — deserts, badlands, snowy plains, mushroom fields, plains,
 * cherry groves, jungle floors, swamps, and the underground lush/dripstone
 * biomes. Every sampled spot that qualifies gets decorated — which
 * decoration is a fixed function of the spot, never whether one appears.
 */
public final class BiomeDecorator {

    private static final Set<Biome> DESERT_LIKE = Set.of(Biome.DESERT);
    private static final Set<Biome> BADLANDS_LIKE = Set.of(Biome.BADLANDS, Biome.ERODED_BADLANDS, Biome.WOODED_BADLANDS);
    private static final Set<Biome> SNOWY_OPEN = Set.of(Biome.SNOWY_PLAINS, Biome.ICE_SPIKES, Biome.SNOWY_BEACH);
    private static final Set<Biome> PLAINS_LIKE = Set.of(Biome.PLAINS, Biome.SUNFLOWER_PLAINS, Biome.FLOWER_FOREST, Biome.MEADOW);
    private static final Set<Biome> JUNGLE_FLOOR = Set.of(Biome.JUNGLE, Biome.SPARSE_JUNGLE, Biome.BAMBOO_JUNGLE);
    private static final Set<Biome> SWAMP_LIKE = Set.of(Biome.SWAMP, Biome.MANGROVE_SWAMP);
    private static final Set<Biome> DARK_FOREST_LIKE = Set.of(Biome.DARK_FOREST, Biome.PALE_GARDEN);
    private static final Set<Biome> SAVANNA_LIKE = Set.of(Biome.SAVANNA, Biome.SAVANNA_PLATEAU, Biome.WINDSWEPT_SAVANNA);
    private static final Set<Biome> CAVE_MOSSY = Set.of(Biome.LUSH_CAVES);
    private static final Set<Biome> CAVE_DRIPSTONE = Set.of(Biome.DRIPSTONE_CAVES);

    private static final Material[] VILLAGE_STYLE_FLOWERS = { Material.POPPY, Material.DANDELION, Material.CORNFLOWER };
    private static final BlockFace[] CARDINAL = {
            BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST
    };

    private BiomeDecorator() {}

    public static void decorate(Area area, int chunkX, int chunkZ, FileConfiguration config) {
        long seed = area.seed();
        int samples = config.getInt("biomes.samples-per-chunk", 6);

        for (int i = 0; i < samples; i++) {
            int x = (chunkX << 4) + CoordHash.pick(seed, chunkX * 31 + i, chunkZ, 16);
            int z = (chunkZ << 4) + CoordHash.pick(seed, chunkX, chunkZ * 31 + i, 16);
            int roll = CoordHash.of(seed, x + 13, z + 13);

            // Open water is never "solid ground", so lily pads have to be handled before the
            // ground finder walks down past the water to the swamp bed underneath.
            int columnTop = area.highestY(x, z, HeightMap.WORLD_SURFACE);
            if (area.type(x, columnTop, z) == Material.WATER && SWAMP_LIKE.contains(area.biome(x, columnTop, z))) {
                if (area.type(x, columnTop + 1, z) == Material.AIR) area.set(x, columnTop + 1, z, Material.LILY_PAD);
                continue;
            }

            int topY = SolidGroundFinder.findY(area, x, z);
            decorateSpot(area, x, topY, z, area.biome(x, topY, z), roll);
        }

        decorateCaveSpots(area, chunkX, chunkZ);
    }

    private static void decorateSpot(Area area, int x, int topY, int z, Biome biome, int roll) {
        if (area.type(x, topY + 1, z) != Material.AIR) return;
        Material ground = area.type(x, topY, z);

        if (DESERT_LIKE.contains(biome) || BADLANDS_LIKE.contains(biome)) {
            decorateArid(area, x, topY, z, ground, roll);
        } else if (SNOWY_OPEN.contains(biome)) {
            area.set(x, topY + 1, z, roll % 7 == 0 ? Material.POWDER_SNOW : Material.SNOW);
        } else if (biome == Biome.MUSHROOM_FIELDS) {
            area.set(x, topY + 1, z, roll % 2 == 0 ? Material.BROWN_MUSHROOM : Material.RED_MUSHROOM);
        } else if (PLAINS_LIKE.contains(biome)) {
            if (ground != Material.GRASS_BLOCK) return;
            PlantPlacer.place(area, x, topY, z, roll % 2 == 0
                    ? VILLAGE_STYLE_FLOWERS[(roll / 2) % VILLAGE_STYLE_FLOWERS.length]
                    : Material.TALL_GRASS);
        } else if (biome == Biome.CHERRY_GROVE) {
            if (ground == Material.GRASS_BLOCK) area.set(x, topY + 1, z, Material.PINK_PETALS);
        } else if (JUNGLE_FLOOR.contains(biome)) {
            if (ground != Material.GRASS_BLOCK && ground != Material.DIRT && ground != Material.MOSS_BLOCK) return;
            if (roll % 5 < 2) {
                area.set(x, topY + 1, z, Material.BAMBOO);
            } else {
                PlantPlacer.place(area, x, topY, z, Material.LARGE_FERN);
            }
        } else if (SWAMP_LIKE.contains(biome)) {
            if (ground == Material.MUD || ground == Material.GRASS_BLOCK) area.set(x, topY + 1, z, Material.FERN);
        } else if (DARK_FOREST_LIKE.contains(biome)) {
            if (ground != Material.GRASS_BLOCK && ground != Material.PODZOL) return;
            Material[] options = { Material.BROWN_MUSHROOM, Material.RED_MUSHROOM, Material.FERN, Material.MOSS_CARPET };
            area.set(x, topY + 1, z, options[roll % options.length]);
        } else if (SAVANNA_LIKE.contains(biome)) {
            if (ground != Material.GRASS_BLOCK) return;
            PlantPlacer.place(area, x, topY, z, roll % 10 < 3 ? Material.SHORT_GRASS : Material.TALL_GRASS);
        }
    }

    private static void decorateArid(Area area, int x, int topY, int z, Material ground, int roll) {
        if (ground != Material.SAND && ground != Material.RED_SAND && ground != Material.TERRACOTTA
                && !ground.name().endsWith("_TERRACOTTA")) return;

        if (isIsolatedColumn(area, x, topY, z) && roll % 2 == 0) {
            area.set(x, topY + 1, z, Material.CACTUS);
        } else {
            area.set(x, topY + 1, z, Material.DEAD_BUSH);
        }
    }

    private static boolean isIsolatedColumn(Area area, int x, int topY, int z) {
        for (BlockFace face : CARDINAL) {
            if (area.type(x + face.getModX(), topY + 1, z + face.getModZ()) != Material.AIR) return false;
        }
        return true;
    }

    private static void decorateCaveSpots(Area area, int chunkX, int chunkZ) {
        int minY = Math.max(area.minY(), -40);
        int maxY = Math.min(63, 60);
        if (minY >= maxY) return;
        long seed = area.seed();

        for (int i = 0; i < 6; i++) {
            int x = (chunkX << 4) + CoordHash.pick(seed, chunkX * 53 + i, chunkZ, 16);
            int z = (chunkZ << 4) + CoordHash.pick(seed, chunkX, chunkZ * 53 + i, 16);
            int y = minY + CoordHash.pick(seed, x + i, z - i, maxY - minY);
            Biome biome = area.biome(x, y, z);

            if (CAVE_MOSSY.contains(biome)) {
                if (area.type(x, y, z) != Material.AIR) continue;
                Material below = area.type(x, y - 1, z);
                if (below == Material.STONE || below == Material.MOSS_BLOCK) {
                    area.set(x, y - 1, z, Material.MOSS_BLOCK);
                    if (CoordHash.pick(seed, x, z + y, 2) == 0) {
                        area.set(x, y, z, Material.MOSS_CARPET);
                    } else {
                        org.bukkit.block.data.BlockData lichen = Material.GLOW_LICHEN.createBlockData();
                        if (lichen instanceof org.bukkit.block.data.MultipleFacing facing) facing.setFace(BlockFace.DOWN, true);
                        area.set(x, y, z, lichen);
                    }
                }
            } else if (CAVE_DRIPSTONE.contains(biome)) {
                if (area.type(x, y, z) == Material.AIR && area.type(x, y - 1, z) == Material.STONE
                        && CoordHash.pick(seed, x, z - y, 2) == 0) {
                    area.set(x, y, z, Material.POINTED_DRIPSTONE);
                }
            }
        }
    }
}

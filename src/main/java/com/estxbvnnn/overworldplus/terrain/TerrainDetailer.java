package com.estxbvnnn.overworldplus.terrain;

import com.estxbvnnn.overworldplus.Area;
import com.estxbvnnn.overworldplus.CoordHash;
import com.estxbvnnn.overworldplus.PlantPlacer;
import com.estxbvnnn.overworldplus.SolidGroundFinder;
import org.bukkit.HeightMap;
import org.bukkit.Material;
import org.bukkit.block.Biome;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.util.BoundingBox;

import java.util.Set;

/**
 * The ground itself, not what stands on it. Vanilla lays a single uniform
 * grass sheet over everything; real ground varies — bare rock where a slope
 * is too steep to hold soil, podzol and needle litter under conifers, moss in
 * the wet dark places, gravel and sand where land meets water, rock
 * shouldering through a meadow. Every rule here is a fixed function of the
 * coordinates (seeded noise + a coordinate hash), so it's the same ground
 * every time and it continues seamlessly from one chunk to the next.
 *
 * Only ever swaps the top block (or clears a thin plant off a spot that
 * turned to rock) and adds plants on top — never changes terrain height, and
 * stays out of every vanilla structure's bounding box it knows about so
 * villages, paths and farms keep their ground.
 */
public final class TerrainDetailer {

    private static final Set<Biome> TAIGA_LIKE = Set.of(
            Biome.TAIGA, Biome.OLD_GROWTH_PINE_TAIGA, Biome.OLD_GROWTH_SPRUCE_TAIGA, Biome.SNOWY_TAIGA, Biome.GROVE
    );
    private static final Set<Biome> FOREST_LIKE = Set.of(
            Biome.FOREST, Biome.FLOWER_FOREST, Biome.BIRCH_FOREST, Biome.OLD_GROWTH_BIRCH_FOREST,
            Biome.CHERRY_GROVE, Biome.WINDSWEPT_FOREST
    );
    private static final Set<Biome> JUNGLE_LIKE = Set.of(Biome.JUNGLE, Biome.SPARSE_JUNGLE, Biome.BAMBOO_JUNGLE);
    private static final Set<Biome> MOSSY_LIKE = Set.of(Biome.DARK_FOREST, Biome.SWAMP);
    private static final Set<Biome> PLAINS_LIKE = Set.of(Biome.PLAINS, Biome.SUNFLOWER_PLAINS, Biome.MEADOW);
    private static final Set<Biome> SAVANNA_LIKE = Set.of(Biome.SAVANNA, Biome.SAVANNA_PLATEAU, Biome.WINDSWEPT_SAVANNA);
    private static final Set<Biome> DESERT_LIKE = Set.of(Biome.DESERT);
    private static final Set<Biome> BEACH_LIKE = Set.of(Biome.BEACH, Biome.SNOWY_BEACH);

    private static final BlockFace[] CARDINAL = {
            BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST
    };

    private TerrainDetailer() {}

    public static void detail(Area area, int chunkX, int chunkZ, FileConfiguration config) {
        TerrainNoise noise = TerrainNoise.of(area.seed());
        long seed = area.seed();
        boolean surfaceVariation = config.getBoolean("terrain.surface-variation", true);
        boolean slopeRock = config.getBoolean("terrain.slope-rock", true);
        boolean shorelines = config.getBoolean("terrain.shorelines", true);
        boolean vegetation = config.getBoolean("terrain.vegetation", true);

        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                int x = (chunkX << 4) + dx;
                int z = (chunkZ << 4) + dz;
                int topY = SolidGroundFinder.findY(area, x, z);
                if (insideStructure(area, x, topY, z)) continue;

                Material aboveType = area.type(x, topY + 1, z);
                if (aboveType == Material.WATER || aboveType == Material.LAVA) continue; // underwater

                Material ground = area.type(x, topY, z);
                Biome biome = area.biome(x, topY, z);
                // Beaches and deserts carry AbyssDepths' hand-built pieces (grass islands, stone
                // jetties, huts): only natural sand is varied there, nothing else is touched.
                if ((BEACH_LIKE.contains(biome) || DESERT_LIKE.contains(biome)) && ground != Material.SAND) continue;
                boolean jungle = JUNGLE_LIKE.contains(biome);
                int slope = slopeAt(area, x, z, topY);
                double patch = noise.patch(x, z);
                double rock = noise.rock(x, z);

                Material newGround = null;
                boolean toRock = false;

                if (ground == Material.GRASS_BLOCK) {
                    if (slopeRock && slope >= 3) {
                        newGround = (patch > 0.35 || jungle) ? Material.MOSSY_COBBLESTONE : Material.STONE;
                        toRock = true;
                    } else if (slopeRock && slope == 2 && rock > 0) {
                        newGround = rock > 0.5 ? Material.STONE : Material.COARSE_DIRT;
                        toRock = rock > 0.5;
                    } else if (surfaceVariation && rock > 0.62 && !jungle) {
                        newGround = outcrop(seed, x, z);
                        toRock = true;
                    } else if (shorelines && slope <= 1 && nextToWater(area, x, z, topY)) {
                        newGround = patch > 0.1 ? Material.GRAVEL : Material.SAND;
                    } else if (surfaceVariation) {
                        newGround = patchMaterial(biome, patch);
                    }
                } else if (ground == Material.DIRT && surfaceVariation) {
                    newGround = rock > 0.3 ? Material.STONE : Material.COARSE_DIRT;
                    toRock = rock > 0.3;
                } else if (ground == Material.STONE && surfaceVariation) {
                    if (patch > 0.45) newGround = Material.ANDESITE;
                    else if (patch < -0.5) newGround = Material.TUFF;
                    else if (rock > 0.6 && slope <= 1) newGround = Material.GRAVEL;
                } else if (ground == Material.SAND && surfaceVariation) {
                    if (DESERT_LIKE.contains(biome) && rock > 0.55) newGround = Material.SANDSTONE;
                    else if (BEACH_LIKE.contains(biome) && patch > 0.45) newGround = Material.GRAVEL;
                }

                if (newGround != null && newGround != ground) {
                    area.set(x, topY, z, newGround);
                    if (toRock && PlantPlacer.isGroundCover(aboveType)) {
                        clearPlant(area, x, topY + 1, z);
                    }
                }

                if (vegetation && !toRock && area.type(x, topY + 1, z) == Material.AIR
                        && PlantPlacer.PLANTABLE.contains(area.type(x, topY, z))) {
                    double veg = noise.vegetation(x, z);
                    int density = jungle ? (veg > 0.3 ? 70 : 45) : veg > 0.55 ? 55 : veg > 0.15 ? 30 : 0;
                    if (density > 0 && CoordHash.pick(seed, x, z, 100) < density) {
                        PlantPlacer.place(area, x, topY, z, plantFor(biome, CoordHash.pick(seed, x + 1, z + 1, 20)));
                    }
                }
            }
        }
    }

    private static boolean insideStructure(Area area, int x, int y, int z) {
        for (BoundingBox box : area.structures()) {
            if (box.contains(x, y, z)) return true;
        }
        return false;
    }

    /** Biggest height difference to the four neighbors — steep either way is steep. */
    private static int slopeAt(Area area, int x, int z, int y) {
        int slope = 0;
        for (BlockFace face : CARDINAL) {
            int nx = x + face.getModX(), nz = z + face.getModZ();
            if (!area.canEdit(nx, area.minY(), nz)) continue;
            int ny = area.highestY(nx, nz, HeightMap.MOTION_BLOCKING_NO_LEAVES);
            slope = Math.max(slope, Math.abs(ny - y));
        }
        return slope;
    }

    private static boolean nextToWater(Area area, int x, int z, int y) {
        for (BlockFace face : CARDINAL) {
            int nx = x + face.getModX(), nz = z + face.getModZ();
            if (!area.canEdit(nx, area.minY(), nz)) continue;
            int ny = area.highestY(nx, nz, HeightMap.WORLD_SURFACE);
            if (area.type(nx, ny, nz) == Material.WATER && Math.abs(ny - y) <= 1) return true;
        }
        return false;
    }

    private static Material outcrop(long seed, int x, int z) {
        int roll = CoordHash.pick(seed, x + 5, z + 5, 10);
        return roll < 6 ? Material.STONE : roll < 9 ? Material.ANDESITE : Material.GRAVEL;
    }

    private static Material patchMaterial(Biome biome, double patch) {
        if (TAIGA_LIKE.contains(biome)) {
            return patch > 0.4 ? Material.PODZOL : patch < -0.6 ? Material.COARSE_DIRT : null;
        }
        if (FOREST_LIKE.contains(biome)) {
            return patch > 0.5 ? Material.PODZOL : patch < -0.65 ? Material.COARSE_DIRT : null;
        }
        if (JUNGLE_LIKE.contains(biome)) {
            // A jungle floor is mostly moss and leaf litter, not lawn.
            return patch > 0.15 ? Material.MOSS_BLOCK : patch < -0.45 ? Material.PODZOL : null;
        }
        if (MOSSY_LIKE.contains(biome)) {
            if (patch > 0.35) return Material.MOSS_BLOCK;
            if (patch < -0.6) return biome == Biome.SWAMP ? Material.MUD : Material.PODZOL;
            return null;
        }
        if (PLAINS_LIKE.contains(biome)) {
            return patch > 0.6 ? Material.COARSE_DIRT : null;
        }
        if (SAVANNA_LIKE.contains(biome)) {
            return patch > 0.45 ? Material.COARSE_DIRT : null;
        }
        return null;
    }

    private static void clearPlant(Area area, int x, int y, int z) {
        Material plant = area.type(x, y, z);
        if (area.type(x, y + 1, z) == plant) area.set(x, y + 1, z, Material.AIR); // the top half of a tall plant
        area.set(x, y, z, Material.AIR);
    }

    private static Material plantFor(Biome biome, int roll) {
        if (biome == Biome.FLOWER_FOREST) {
            return roll < 8 ? Material.SHORT_GRASS : roll < 10 ? Material.ALLIUM : roll < 12 ? Material.LILAC
                    : roll < 13 ? Material.PEONY : roll < 14 ? Material.ROSE_BUSH
                    : roll < 16 ? Material.LILY_OF_THE_VALLEY : roll < 18 ? Material.CORNFLOWER : Material.OXEYE_DAISY;
        }
        if (biome == Biome.MEADOW) {
            return roll < 9 ? Material.SHORT_GRASS : roll < 12 ? Material.TALL_GRASS : roll < 14 ? Material.ALLIUM
                    : roll < 16 ? Material.CORNFLOWER : roll < 18 ? Material.DANDELION : Material.AZURE_BLUET;
        }
        if (PLAINS_LIKE.contains(biome)) {
            return roll < 11 ? Material.SHORT_GRASS : roll < 14 ? Material.TALL_GRASS : roll < 15 ? Material.DANDELION
                    : roll < 16 ? Material.POPPY : roll < 17 ? Material.AZURE_BLUET : roll < 18 ? Material.OXEYE_DAISY
                    : roll < 19 ? Material.CORNFLOWER : Material.SHORT_GRASS;
        }
        if (TAIGA_LIKE.contains(biome)) {
            return roll < 8 ? Material.FERN : roll < 13 ? Material.LARGE_FERN : roll < 16 ? Material.SHORT_GRASS
                    : roll < 18 ? Material.SWEET_BERRY_BUSH : Material.BROWN_MUSHROOM;
        }
        if (biome == Biome.DARK_FOREST) {
            return roll < 7 ? Material.FERN : roll < 10 ? Material.BROWN_MUSHROOM : roll < 12 ? Material.RED_MUSHROOM
                    : roll < 15 ? Material.SHORT_GRASS : roll < 17 ? Material.MOSS_CARPET : Material.LARGE_FERN;
        }
        if (JUNGLE_LIKE.contains(biome)) {
            return roll < 7 ? Material.FERN : roll < 12 ? Material.LARGE_FERN : roll < 14 ? Material.MOSS_CARPET
                    : roll < 15 ? Material.MELON : roll < 18 ? Material.SHORT_GRASS : Material.TALL_GRASS;
        }
        if (biome == Biome.SWAMP) {
            return roll < 10 ? Material.FERN : roll < 14 ? Material.SHORT_GRASS : roll < 16 ? Material.BLUE_ORCHID : Material.TALL_GRASS;
        }
        if (SAVANNA_LIKE.contains(biome)) {
            return roll < 12 ? Material.SHORT_GRASS : Material.TALL_GRASS;
        }
        if (FOREST_LIKE.contains(biome)) {
            return roll < 10 ? Material.SHORT_GRASS : roll < 14 ? Material.FERN : roll < 16 ? Material.TALL_GRASS
                    : roll < 17 ? Material.DANDELION : roll < 18 ? Material.POPPY : Material.LILY_OF_THE_VALLEY;
        }
        return Material.SHORT_GRASS;
    }
}

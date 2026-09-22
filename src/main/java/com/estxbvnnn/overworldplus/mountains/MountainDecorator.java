package com.estxbvnnn.overworldplus.mountains;

import com.estxbvnnn.overworldplus.Area;
import com.estxbvnnn.overworldplus.CoordHash;
import com.estxbvnnn.overworldplus.SolidGroundFinder;
import org.bukkit.HeightMap;
import org.bukkit.Material;
import org.bukkit.block.Biome;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Snow;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Mountain-biome chunks: denser snow, a couple of boulder outcrops, and the
 * occasional waterfall off a real cliff. Only ever writes to AIR immediately
 * above the existing terrain — never digs into it.
 *
 * Waterfalls are the expensive part: each one is a water source with physics
 * on, which then flows down the whole cliff face and keeps ticking forever.
 * The old "every sample gets one" rule could stack several in a single chunk
 * across an entire range — that was a real lag source. Now it's at most one
 * per chunk, and only in chunks a fixed coordinate rule picks.
 */
public final class MountainDecorator {

    private static final Material[] BOULDER_PALETTE = {
            Material.STONE, Material.ANDESITE, Material.MOSSY_COBBLESTONE, Material.COBBLESTONE
    };
    private static final BlockFace[] CARDINAL = {
            BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST
    };

    private MountainDecorator() {}

    public static void decorateChunk(Area area, int chunkX, int chunkZ, FileConfiguration config) {
        long seed = area.seed();
        int samples = config.getInt("mountains.samples-per-chunk", 5);
        int maxBoulders = config.getInt("mountains.max-boulders-per-chunk", 2);
        boolean snow = config.getBoolean("mountains.snow-enhancement", true);
        int bouldersPlaced = 0;
        boolean anyMountain = false;

        for (int i = 0; i < samples; i++) {
            int x = (chunkX << 4) + CoordHash.pick(seed, chunkX * 16 + i, chunkZ, 16);
            int z = (chunkZ << 4) + CoordHash.pick(seed, chunkX, chunkZ * 16 + i, 16);
            int topY = SolidGroundFinder.findY(area, x, z);
            Biome biome = area.biome(x, topY, z);
            if (!MountainBiomes.ALL.contains(biome)) continue;
            anyMountain = true;

            if (snow && MountainBiomes.SNOWY.contains(biome)) {
                enhanceSnow(area, x, topY, z);
            }
            if (bouldersPlaced < maxBoulders && placeBoulder(area, x, topY, z)) {
                bouldersPlaced++;
            }
        }

        if (!anyMountain) return;

        int everyN = Math.max(1, config.getInt("mountains.waterfall-every-n-chunks", 6));
        if (CoordHash.pick(seed, chunkX, chunkZ, everyN) == 0) {
            createOneWaterfall(area, chunkX, chunkZ);
        }
    }

    private static void enhanceSnow(Area area, int x, int topY, int z) {
        if (area.type(x, topY + 1, z) != Material.AIR) return;

        if (CoordHash.pick(area.seed(), x, z, 10) == 0) {
            area.set(x, topY + 1, z, Material.POWDER_SNOW);
            return;
        }
        BlockData data = Material.SNOW.createBlockData();
        if (data instanceof Snow snowData) {
            snowData.setLayers(2 + CoordHash.pick(area.seed(), x + 1, z + 1, snowData.getMaximumLayers() - 1));
        }
        area.set(x, topY + 1, z, data);
    }

    private static boolean placeBoulder(Area area, int x, int topY, int z) {
        if (area.type(x, topY + 1, z) != Material.AIR) return false;

        long seed = area.seed();
        int radius = 1 + CoordHash.pick(seed, x + 2, z + 2, 2);
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = 0; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    double distance = Math.sqrt(dx * dx + dy * dy * 1.5 + dz * dz);
                    if (distance > radius) continue;

                    int bx = x + dx, by = topY + 1 + dy, bz = z + dz;
                    if (area.type(bx, by, bz) == Material.AIR) {
                        area.set(bx, by, bz, BOULDER_PALETTE[CoordHash.pick(seed, bx, bz + by, BOULDER_PALETTE.length)]);
                    }
                }
            }
        }
        return true;
    }

    /** Walks the chunk for the first real cliff edge (a 4+ block drop) and starts one waterfall there. */
    private static void createOneWaterfall(Area area, int chunkX, int chunkZ) {
        for (int dx = 1; dx < 15; dx += 2) {
            for (int dz = 1; dz < 15; dz += 2) {
                int x = (chunkX << 4) + dx, z = (chunkZ << 4) + dz;
                int topY = SolidGroundFinder.findY(area, x, z);
                if (!MountainBiomes.ALL.contains(area.biome(x, topY, z))) continue;
                if (area.type(x, topY + 1, z) != Material.AIR) continue;

                for (BlockFace face : CARDINAL) {
                    int nx = x + face.getModX(), nz = z + face.getModZ();
                    int neighborY = area.highestY(nx, nz, HeightMap.WORLD_SURFACE);
                    if (topY - neighborY >= 4) {
                        area.setWithPhysics(x, topY + 1, z, Material.WATER); // so it actually flows down the cliff
                        return;
                    }
                }
            }
        }
    }
}

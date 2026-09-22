package com.estxbvnnn.overworldplus.structures;

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.MultipleFacing;

/**
 * Removes vines that hang from nothing — the leftovers of earlier versions
 * of this plugin that hung vines off trees and houses that no longer exist.
 * Those blocks are real, saved world edits: removing the code that placed
 * them doesn't retroactively undo what's already on disk. A vine survives
 * here only if it's attached the way vanilla requires: a face pointing at a
 * solid block, or hanging from a vine above it. Natural jungle/swamp vines
 * and ruined-portal vines all pass that test untouched.
 */
public final class VineCleanup {

    private VineCleanup() {}

    /** The whole chunk, once — used for the one-off cleanup of chunks touched by older versions. */
    public static int sweepChunk(World world, Chunk chunk) {
        int removed = 0;
        int baseX = chunk.getX() << 4, baseZ = chunk.getZ() << 4;
        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                removed += sweepColumn(world, baseX + dx, baseZ + dz);
            }
        }
        return removed;
    }

    public static int sweep(World world, Location center, int radius) {
        int cx = center.getBlockX();
        int cz = center.getBlockZ();
        int removed = 0;

        for (int chunkX = (cx - radius) >> 4; chunkX <= (cx + radius) >> 4; chunkX++) {
            for (int chunkZ = (cz - radius) >> 4; chunkZ <= (cz + radius) >> 4; chunkZ++) {
                if (!world.isChunkLoaded(chunkX, chunkZ)) continue; // never force-load a chunk for this

                for (int dx = 0; dx < 16; dx++) {
                    for (int dz = 0; dz < 16; dz++) {
                        int x = (chunkX << 4) + dx;
                        int z = (chunkZ << 4) + dz;
                        if ((x - cx) * (x - cx) + (z - cz) * (z - cz) > radius * radius) continue;
                        removed += sweepColumn(world, x, z);
                    }
                }
            }
        }
        return removed;
    }

    /** Only scans a window around the surface — vines from either removed system were always near ground/tree height. */
    private static int sweepColumn(World world, int x, int z) {
        int top = world.getHighestBlockYAt(x, z);
        int minY = Math.max(world.getMinHeight(), top - 40);
        int maxY = Math.min(world.getMaxHeight() - 1, top + 30);
        int removed = 0;

        // Top-down, so a strand hanging from a floating vine collapses with it in one pass.
        for (int y = maxY; y >= minY; y--) {
            Block block = world.getBlockAt(x, y, z);
            if (block.getType() != Material.VINE) continue;
            if (isSupported(block)) continue;

            block.setType(Material.AIR, false);
            removed++;
        }
        return removed;
    }

    private static boolean isSupported(Block vine) {
        if (!(vine.getBlockData() instanceof MultipleFacing facing)) return true;
        for (BlockFace face : facing.getAllowedFaces()) {
            if (facing.hasFace(face) && vine.getRelative(face).getType().isSolid()) return true;
        }
        return vine.getRelative(BlockFace.UP).getType() == Material.VINE; // hangs from the vine above
    }
}

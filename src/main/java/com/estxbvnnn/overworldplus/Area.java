package com.estxbvnnn.overworldplus;

import org.bukkit.HeightMap;
import org.bukkit.Material;
import org.bukkit.RegionAccessor;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.generator.LimitedRegion;
import org.bukkit.generator.WorldInfo;
import org.bukkit.util.BoundingBox;

import java.util.List;

/**
 * One block-access surface for both places enhancement runs: at generation
 * time inside a {@link LimitedRegion} (a chunk plus a buffer around it, on a
 * worker thread, before any client ever sees the chunk) and after the fact
 * on a loaded {@link World} (for chunks that already existed). Every pass is
 * written against this, so the exact same code produces the exact same
 * result on either path. Writes outside a region's bounds are dropped, reads
 * outside it come back as AIR — never an exception mid-chunk.
 */
public final class Area {

    private final RegionAccessor accessor;
    private final LimitedRegion region; // null when backed by a World
    private final World world;          // null when backed by a LimitedRegion
    /** Whether vanilla-tree removal may reach into a given chunk (see TreeEnhancer). */
    @FunctionalInterface
    public interface ChunkFilter {
        boolean allows(int chunkX, int chunkZ);
    }

    private final String worldName;
    private final long seed;
    private final int minY;
    private final int maxY;
    private final List<BoundingBox> structures;
    private final ChunkFilter removable;

    private Area(RegionAccessor accessor, LimitedRegion region, World world, String worldName, long seed,
                 int minY, int maxY, List<BoundingBox> structures, ChunkFilter removable) {
        this.accessor = accessor;
        this.region = region;
        this.world = world;
        this.worldName = worldName;
        this.seed = seed;
        this.minY = minY;
        this.maxY = maxY;
        this.structures = structures;
        this.removable = removable;
    }

    public static Area of(World world, List<BoundingBox> structures, ChunkFilter removable) {
        return new Area(world, null, world, world.getName(), world.getSeed(), world.getMinHeight(), world.getMaxHeight(),
                structures, removable);
    }

    public static Area of(WorldInfo info, LimitedRegion region) {
        return new Area(region, region, null, info.getName(), info.getSeed(), info.getMinHeight(), info.getMaxHeight(),
                List.of(), (cx, cz) -> true);
    }

    /** Whether a removal pass may edit the chunk containing block (x, z). */
    public boolean canRemoveIn(int x, int z) {
        return removable.allows(x >> 4, z >> 4);
    }

    public boolean isGenerating() {
        return region != null;
    }

    public String worldName() {
        return worldName;
    }

    public long seed() {
        return seed;
    }

    public int minY() {
        return minY;
    }

    /** Exclusive upper bound. */
    public int maxY() {
        return maxY;
    }

    /** Vanilla structure bounding boxes touching the chunk — only known on the loaded-world path. */
    public List<BoundingBox> structures() {
        return structures;
    }

    public boolean canEdit(int x, int y, int z) {
        if (y < minY || y >= maxY) return false;
        return region == null || region.isInRegion(x, y, z);
    }

    public Material type(int x, int y, int z) {
        if (!canEdit(x, y, z)) return Material.AIR;
        return accessor.getType(x, y, z);
    }

    public BlockData data(int x, int y, int z) {
        if (!canEdit(x, y, z)) return Material.AIR.createBlockData();
        return accessor.getBlockData(x, y, z);
    }

    public void set(int x, int y, int z, Material material) {
        set(x, y, z, material.createBlockData());
    }

    /** Places without neighbor physics: the whole point is to lay blocks down, not to trigger updates. */
    public void set(int x, int y, int z, BlockData data) {
        if (!canEdit(x, y, z)) return;
        if (world != null) {
            world.getBlockAt(x, y, z).setBlockData(data, false); // World's RegionAccessor path applies physics
        } else {
            accessor.setBlockData(x, y, z, data);
        }
    }

    /** The one case that *wants* physics: a water source that has to start flowing. */
    public void setWithPhysics(int x, int y, int z, Material material) {
        if (!canEdit(x, y, z)) return;
        if (world != null) {
            world.getBlockAt(x, y, z).setType(material, true);
        } else {
            region.setType(x, y, z, material);
            region.scheduleFluidUpdate(x, y, z);
        }
    }

    public Biome biome(int x, int y, int z) {
        return accessor.getBiome(x, Math.max(minY, Math.min(maxY - 1, y)), z);
    }

    /**
     * Y of the highest block per the heightmap, or minY if the column is outside a region's bounds.
     * During generation a LimitedRegion reports the first free Y above that block (one higher
     * than a loaded World does — measured on a real generated ocean), so it's normalised here;
     * every pass relies on both paths agreeing.
     */
    public int highestY(int x, int z, HeightMap map) {
        if (region == null) return accessor.getHighestBlockYAt(x, z, map);
        if (!region.isInRegion(x, minY, z)) return minY;
        return region.getHighestBlockYAt(x, z, map) - 1;
    }

    public boolean isSolid(int x, int y, int z) {
        return type(x, y, z).isSolid();
    }

    public BlockState state(int x, int y, int z) {
        return accessor.getBlockState(x, y, z);
    }

    /** Commits a modified state (e.g. a filled chest) back through whichever backend this is. */
    public void apply(int x, int y, int z, BlockState state) {
        if (region != null) {
            region.setBlockState(x, y, z, state);
        } else {
            state.update(true, false);
        }
    }
}

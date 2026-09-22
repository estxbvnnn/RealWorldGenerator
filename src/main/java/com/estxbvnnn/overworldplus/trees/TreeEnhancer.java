package com.estxbvnnn.overworldplus.trees;

import com.estxbvnnn.overworldplus.Area;
import com.estxbvnnn.overworldplus.BlockKey;
import org.bukkit.HeightMap;
import org.bukkit.Material;
import org.bukkit.block.Biome;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Replaces vanilla's trees with real, hand-designed ones, in two steps that
 * are the same whether this runs at generation time or on an old chunk:
 *
 * 1. Every vanilla tree standing in the chunk is removed — except mangroves
 *    (left alone entirely) and jungle floor bushes (the one-log shrubs that
 *    make a jungle floor a jungle floor). Removal walks a tree's connected
 *    logs out from its base and never steps onto a block that belongs to a
 *    neighboring chunk's big tree ({@link TreeGrid#woodAround}), so a big
 *    trunk next to a vanilla one can't be eaten — the bug behind "trees with
 *    no trunk". Pasted leaves are all persistent, so leaf clearing (natural,
 *    non-persistent only) can't touch a big canopy either.
 * 2. The chunk's planned trees ({@link TreeGrid#planChunk}) are pasted. Any
 *    base block left hanging over a slope gets propped up with rooted dirt
 *    first — a tree on a hillside grips the ground instead of floating.
 */
public final class TreeEnhancer {

    public record Result(int placed, int removed) {}

    private record Pos(int x, int y, int z) {}

    private static final Set<Material> LOG_TYPES;
    static {
        LOG_TYPES = EnumSet.noneOf(Material.class);
        for (TreeSpecies species : TreeSpecies.values()) {
            LOG_TYPES.add(species.log());
            LOG_TYPES.add(species.wood());
        }
    }
    private static final Set<Biome> JUNGLE_BIOMES = Set.of(Biome.JUNGLE, Biome.BAMBOO_JUNGLE, Biome.SPARSE_JUNGLE);

    // Vanilla's biggest trees: fancy oaks branch ~5 out, jungle giants run ~30 tall on a 2x2
    // trunk (~150 logs). The block budget is a hard ceiling on one removal.
    private static final int VANILLA_REACH = 5;
    private static final int VANILLA_MAX_HEIGHT = 32;
    private static final int VANILLA_MAX_BLOCKS = 160;

    private TreeEnhancer() {}

    public static Result enhance(Area area, int chunkX, int chunkZ, FileConfiguration config, SchematicTreeLibrary library) {
        TreeGrid.Settings settings = TreeGrid.Settings.from(config);
        boolean undergrowth = config.getBoolean("trees.undergrowth", true);

        List<TreeGrid.PlannedTree> planned = TreeGrid.planChunk(area, chunkX, chunkZ, library, settings);
        // The neighbors' trees need protecting. This chunk's own normally aren't standing yet —
        // but if this chunk is somehow being run twice, they are, and they must be treated
        // exactly like a neighbor's: never walked into by the removal below.
        Set<Long> protectedWood = TreeGrid.woodAround(area, chunkX, chunkZ, library, settings);
        for (TreeGrid.PlannedTree tree : planned) {
            if (isAlreadyStanding(area, tree)) continue;
            Set<Long> own = new HashSet<>();
            SchematicTreeLibrary.collectWood(tree.tree(), tree.x(), tree.y(), tree.z(), own);
            protectedWood.removeAll(own);
        }

        int removed = 0;
        int minX = chunkX << 4, minZ = chunkZ << 4;
        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                Pos base = findTrunkBase(area, minX + dx, minZ + dz);
                if (base == null) continue;
                if (protectedWood.contains(BlockKey.of(base.x(), base.y(), base.z()))) continue;

                Material log = area.type(base.x(), base.y(), base.z());
                if (log == Material.MANGROVE_LOG) continue;
                if (JUNGLE_BIOMES.contains(area.biome(base.x(), base.y(), base.z())) && trunkHeight(area, base) <= 2) continue;

                removeVanillaTree(area, base, protectedWood);
                removed++;
            }
        }

        for (TreeGrid.PlannedTree tree : planned) {
            propUpBase(area, tree);
            library.paste(area, tree.species(), tree.tree(), tree.x(), tree.y(), tree.z());
            if (undergrowth) {
                ForestFloor.decorate(area, tree);
            }
        }
        return new Result(planned.size(), removed);
    }

    /**
     * Scans downward from just above whatever's tallest in this column
     * (canopy included) rather than a heightmap that stops at the ground —
     * a heightmap-based window here would sit inside the leaves, well above
     * the actual trunk, and never find it. Only a log resting *directly* on
     * soil counts: a big tree's branch hanging over grass has air under it
     * and must never be mistaken for a trunk.
     */
    private static Pos findTrunkBase(Area area, int x, int z) {
        int y = area.highestY(x, z, HeightMap.WORLD_SURFACE) + 1;
        int minY = area.minY();
        int lowestLogY = Integer.MIN_VALUE;

        for (; y >= minY; y--) {
            Material type = area.type(x, y, z);
            if (LOG_TYPES.contains(type)) {
                lowestLogY = y;
                continue;
            }
            if (type == Material.AIR || !type.isSolid() || TreeGrid.isTreeBlock(type)) {
                continue; // keep descending through canopy/air/plants
            }
            // First solid, non-tree block reached — this is the real ground.
            if (lowestLogY == y + 1 && TreeGrid.SOIL_TYPES.contains(type)) {
                return new Pos(x, lowestLogY, z);
            }
            return null;
        }
        return null;
    }

    /**
     * A vanilla trunk can coincide with the anchor column by chance (a handful of logs), so
     * "wood at the anchor" isn't enough — most of the tree's wood has to be there already.
     */
    private static boolean isAlreadyStanding(Area area, TreeGrid.PlannedTree tree) {
        int expected = 0, present = 0;
        for (SchematicTree.BlockOffset offset : tree.tree().blocks()) {
            if (offset.type() == SchematicTree.Type.LEAVES) continue;
            expected++;
            Material type = area.type(tree.x() + offset.dx(), tree.y() + offset.dy(), tree.z() + offset.dz());
            if (LOG_TYPES.contains(type) || type == Material.MANGROVE_ROOTS) present++;
        }
        return expected > 0 && present >= expected * 0.6;
    }

    private static int trunkHeight(Area area, Pos base) {
        int height = 0;
        while (height < 20 && LOG_TYPES.contains(area.type(base.x(), base.y() + height, base.z()))) {
            height++;
        }
        return height;
    }

    /**
     * Removes exactly one vanilla tree: its logs by walking the connected log
     * network out from the trunk, then the natural (non-persistent) leaves
     * around it, plus vines, cocoa and any snow layer that would be left
     * floating on a removed leaf.
     */
    private static void removeVanillaTree(Area area, Pos base, Set<Long> protectedWood) {
        int topLogY = base.y();
        Set<Long> visited = new HashSet<>();
        Deque<Pos> queue = new ArrayDeque<>();
        queue.add(base);
        visited.add(BlockKey.of(base.x(), base.y(), base.z()));

        while (!queue.isEmpty() && visited.size() < VANILLA_MAX_BLOCKS) {
            Pos log = queue.poll();
            topLogY = Math.max(topLogY, log.y());
            area.set(log.x(), log.y(), log.z(), Material.AIR);

            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        int nx = log.x() + dx, ny = log.y() + dy, nz = log.z() + dz;
                        long key = BlockKey.of(nx, ny, nz);
                        if (visited.contains(key) || protectedWood.contains(key)) continue;
                        if (Math.abs(nx - base.x()) > VANILLA_REACH || Math.abs(nz - base.z()) > VANILLA_REACH) continue;
                        if (ny < base.y() || ny > base.y() + VANILLA_MAX_HEIGHT) continue;
                        // A neighbor chunk that's already finished is final — whatever wood stands
                        // there (including trees placed by older versions) is never walked into.
                        if (!area.canRemoveIn(nx, nz)) continue;
                        if (!LOG_TYPES.contains(area.type(nx, ny, nz))) continue;
                        visited.add(key);
                        queue.add(new Pos(nx, ny, nz));
                    }
                }
            }
        }

        int maxY = Math.min(area.maxY() - 1, topLogY + 6);
        for (int y = base.y(); y <= maxY; y++) {
            for (int dx = -VANILLA_REACH; dx <= VANILLA_REACH; dx++) {
                for (int dz = -VANILLA_REACH; dz <= VANILLA_REACH; dz++) {
                    int x = base.x() + dx, z = base.z() + dz;
                    if (!area.canRemoveIn(x, z)) continue;
                    Material type = area.type(x, y, z);
                    if (type.name().endsWith("_LEAVES")) {
                        if (area.data(x, y, z) instanceof Leaves leaves && leaves.isPersistent()) continue;
                        if (area.type(x, y + 1, z) == Material.SNOW) area.set(x, y + 1, z, Material.AIR);
                        area.set(x, y, z, Material.AIR);
                    } else if (type == Material.VINE || type == Material.COCOA) {
                        area.set(x, y, z, Material.AIR);
                    }
                }
            }
        }
    }

    /** Rooted dirt under any base block that would otherwise hang over a slope. */
    private static void propUpBase(Area area, TreeGrid.PlannedTree tree) {
        int groundY = tree.y() - 1;
        for (SchematicTree.BlockOffset offset : SchematicTreeLibrary.groundLayer(tree.tree())) {
            int x = tree.x() + offset.dx(), z = tree.z() + offset.dz();
            int depth = TreeGrid.moundDepth(area, x, groundY, z);
            if (depth == 0 || depth > TreeGrid.MAX_MOUND_DEPTH) continue;
            for (int i = 0; i < depth; i++) {
                area.set(x, groundY - i, z, Material.ROOTED_DIRT);
            }
        }
    }
}

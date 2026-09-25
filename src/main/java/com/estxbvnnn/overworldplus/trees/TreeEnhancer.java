package com.estxbvnnn.overworldplus.trees;

import com.estxbvnnn.overworldplus.Area;
import com.estxbvnnn.overworldplus.BlockKey;
import org.bukkit.HeightMap;
import org.bukkit.Material;
import org.bukkit.block.Biome;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
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
 *    no trunk". Leaves then go exactly as vanilla decay would take them:
 *    only those no longer attached to any log, so the trees that stay keep
 *    their canopies — the bug behind "trees with no leaves".
 * 2. The chunk's planned trees ({@link TreeGrid#planChunk}) are pasted. Any
 *    base block left hanging over a slope gets propped up with rooted dirt
 *    first — a tree on a hillside grips the ground instead of floating.
 */
public final class TreeEnhancer {

    public record Result(int placed, int removed) {}

    record Pos(int x, int y, int z) {}

    /** What touches a tree's wood: its own natural canopy, only pasted (persistent) leaves, or nothing. */
    enum LeafContact { NATURAL, PERSISTENT_ONLY, NONE }

    private static final Set<Material> LOG_TYPES;
    static {
        LOG_TYPES = EnumSet.noneOf(Material.class);
        for (TreeSpecies species : TreeSpecies.values()) {
            LOG_TYPES.add(species.log());
            LOG_TYPES.add(species.wood());
        }
    }
    private static final Set<Biome> JUNGLE_BIOMES = Set.of(Biome.JUNGLE, Biome.BAMBOO_JUNGLE, Biome.SPARSE_JUNGLE);
    // Vanilla grows no trees here, so any wood standing in these biomes was put there on purpose
    // (AbyssDepths' beach/desert trees and huts) — never treated as a vanilla tree to clear.
    private static final Set<Biome> HANDS_OFF = Set.of(
            Biome.BEACH, Biome.SNOWY_BEACH, Biome.STONY_SHORE, Biome.DESERT, Biome.BADLANDS, Biome.ERODED_BADLANDS
    );

    // Vanilla's biggest trees: mega jungle trees run past 30 tall on a 2x2 trunk with branches
    // ~6 out from it, mega spruces ~30 tall — well over 150 logs. These are only ceilings, so
    // they sit well above the largest tree rather than right at it: a tree cut short by a
    // ceiling leaves a bare stump behind.
    private static final int VANILLA_REACH = 8;
    private static final int VANILLA_MAX_HEIGHT = 48;
    private static final int VANILLA_MAX_BLOCKS = 640;
    // Vanilla leaves decay once they're more than 6 steps (through leaves) from any log.
    private static final int LEAF_SUPPORT_DISTANCE = 6;

    private static final int[][] FACES = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

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

        // Decide on the untouched chunk first, remove afterwards: deciding tree by tree while
        // removing meant one tree's cleanup could strip a neighbour's canopy, and the neighbour —
        // now without leaves — no longer looked like a vanilla tree and was left as a bare trunk.
        List<Set<Pos>> doomed = new ArrayList<>();
        Set<Long> claimed = new HashSet<>();
        int minX = chunkX << 4, minZ = chunkZ << 4;
        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                Pos base = findTrunkBase(area, minX + dx, minZ + dz);
                if (base == null) continue;
                long baseKey = BlockKey.of(base.x(), base.y(), base.z());
                if (protectedWood.contains(baseKey) || claimed.contains(baseKey)) continue;
                if (leftAloneOnPurpose(area, base)) continue;

                Set<Pos> logs = collectTree(area, base, protectedWood);
                if (!isVanilla(area, logs)) continue;
                for (Pos log : logs) claimed.add(BlockKey.of(log.x(), log.y(), log.z()));
                doomed.add(logs);
            }
        }
        if (!doomed.isEmpty()) {
            removeTrees(area, doomed);
        }

        for (TreeGrid.PlannedTree tree : planned) {
            propUpBase(area, tree);
            library.paste(area, tree.species(), tree.tree(), tree.x(), tree.y(), tree.z());
            if (undergrowth) {
                ForestFloor.decorate(area, tree);
            }
        }
        return new Result(planned.size(), doomed.size());
    }

    /**
     * Scans downward from just above whatever's tallest in this column
     * (canopy included) rather than a heightmap that stops at the ground —
     * a heightmap-based window here would sit inside the leaves, well above
     * the actual trunk, and never find it. Only a log resting *directly* on
     * soil counts: a big tree's branch hanging over grass has air under it
     * and must never be mistaken for a trunk.
     */
    static Pos findTrunkBase(Area area, int x, int z) {
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
     * Vanilla trees kept on purpose: mangroves (their root mazes over water can't be rebuilt),
     * jungle floor bushes (one-log shrubs that make a jungle floor a jungle floor), and anything
     * in the beach/desert biomes, where vanilla grows no trees and wood means a built piece.
     */
    static boolean leftAloneOnPurpose(Area area, Pos base) {
        if (area.type(base.x(), base.y(), base.z()) == Material.MANGROVE_LOG) return true;
        Biome biome = area.biome(base.x(), base.y(), base.z());
        if (HANDS_OFF.contains(biome)) return true;
        return JUNGLE_BIOMES.contains(biome) && trunkHeight(area, base) <= 2;
    }

    static boolean inHandsOffBiome(Area area, Pos base) {
        return HANDS_OFF.contains(area.biome(base.x(), base.y(), base.z()));
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

    /**
     * One tree's wood: the connected logs out from its base. A vanilla tree is one material all
     * the way through (an oak is oak logs, never jungle wood), so the walk never steps onto a
     * different kind of wood — that's a neighbouring tree or a build — nor onto a big tree's
     * wood ({@link TreeGrid#woodAround}), which is what stops it eating a big trunk next door.
     */
    static Set<Pos> collectTree(Area area, Pos base, Set<Long> protectedWood) {
        Material trunkMaterial = area.type(base.x(), base.y(), base.z());
        Set<Long> visited = new HashSet<>();
        Set<Pos> logs = new HashSet<>();
        Deque<Pos> queue = new ArrayDeque<>();
        queue.add(base);
        visited.add(BlockKey.of(base.x(), base.y(), base.z()));

        while (!queue.isEmpty() && logs.size() < VANILLA_MAX_BLOCKS) {
            Pos log = queue.poll();
            logs.add(log);
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
                        if (HANDS_OFF.contains(area.biome(nx, ny, nz))) continue;
                        if (area.type(nx, ny, nz) != trunkMaterial) continue;
                        visited.add(key);
                        queue.add(new Pos(nx, ny, nz));
                    }
                }
            }
        }
        return logs;
    }

    /** What kind of leaves touch this wood (any of the 26 blocks around any log). */
    static LeafContact leafContact(Area area, Set<Pos> logs) {
        boolean persistent = false;
        for (Pos log : logs) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (!(area.data(log.x() + dx, log.y() + dy, log.z() + dz) instanceof Leaves leaves)) continue;
                        if (!leaves.isPersistent()) return LeafContact.NATURAL;
                        persistent = true;
                    }
                }
            }
        }
        return persistent ? LeafContact.PERSISTENT_ONLY : LeafContact.NONE;
    }

    /**
     * A vanilla tree carries its own natural (decaying) canopy, touching its wood; wood with
     * none is something built — a hut, another plugin's tree, a player's cabin — never ours to
     * clear. While a chunk is generating nothing has been built yet, so a big tree's pasted
     * (persistent) canopy brushing a vanilla tree doesn't save it — that used to leave vanilla
     * trees standing under big ones. On an old, loaded chunk any persistent leaf on the wood
     * does, since there it may well be a player's build.
     */
    private static boolean isVanilla(Area area, Set<Pos> logs) {
        LeafContact contact = leafContact(area, logs);
        if (contact != LeafContact.NATURAL) return false;
        if (area.isGenerating()) return true;
        for (Pos log : logs) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (area.data(log.x() + dx, log.y() + dy, log.z() + dz) instanceof Leaves leaves
                                && leaves.isPersistent()) return false;
                    }
                }
            }
        }
        return true;
    }

    private static int trunkHeight(Area area, Pos base) {
        int height = 0;
        while (height < VANILLA_MAX_HEIGHT && LOG_TYPES.contains(area.type(base.x(), base.y() + height, base.z()))) {
            height++;
        }
        return height;
    }

    /**
     * Removes the doomed trees' wood, then does what vanilla leaf decay would do: any natural
     * leaf no longer within {@value #LEAF_SUPPORT_DISTANCE} steps (through leaves) of a log
     * goes. Leaves of the trees that stay — mangroves, jungle bushes, anything next door —
     * are still attached to their own logs, so they stay too; the old fixed radius around
     * each removed tree stripped those neighbours bare. Cocoa and vines left hanging on
     * nothing, and snow on a removed leaf, go with them.
     */
    private static void removeTrees(Area area, List<Set<Pos>> doomed) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (Set<Pos> logs : doomed) {
            for (Pos log : logs) {
                area.set(log.x(), log.y(), log.z(), Material.AIR);
                minX = Math.min(minX, log.x());
                maxX = Math.max(maxX, log.x());
                minY = Math.min(minY, log.y());
                maxY = Math.max(maxY, log.y());
                minZ = Math.min(minZ, log.z());
                maxZ = Math.max(maxZ, log.z());
            }
        }
        // Every leaf of these trees sits within the decay distance of their wood.
        int r = LEAF_SUPPORT_DISTANCE;
        minX -= r;
        minZ -= r;
        maxX += r;
        maxZ += r;
        maxY = Math.min(area.maxY() - 2, maxY + r);
        int sizeY = maxY - minY + 1, sizeZ = maxZ - minZ + 1;

        // Each natural leaf's distance from the nearest remaining log, breadth-first through
        // leaves the way vanilla counts it. Logs just outside the box hold up leaves inside it,
        // so the search starts from a margin of r around the box.
        int[] distance = new int[(maxX - minX + 1) * sizeY * sizeZ];
        Arrays.fill(distance, Integer.MAX_VALUE);
        Deque<int[]> queue = new ArrayDeque<>();
        int scanMinY = Math.max(area.minY(), minY - r), scanMaxY = Math.min(area.maxY() - 1, maxY + r);
        for (int x = minX - r; x <= maxX + r; x++) {
            for (int z = minZ - r; z <= maxZ + r; z++) {
                for (int y = scanMinY; y <= scanMaxY; y++) {
                    if (isWood(area.type(x, y, z))) queue.add(new int[]{x, y, z, 0});
                }
            }
        }
        while (!queue.isEmpty()) {
            int[] at = queue.poll();
            if (at[3] >= r) continue;
            for (int[] face : FACES) {
                int x = at[0] + face[0], y = at[1] + face[1], z = at[2] + face[2];
                if (x < minX || x > maxX || y < minY || y > maxY || z < minZ || z > maxZ) continue;
                int index = ((x - minX) * sizeY + (y - minY)) * sizeZ + (z - minZ);
                if (distance[index] <= at[3] + 1) continue;
                if (!area.type(x, y, z).name().endsWith("_LEAVES")) continue;
                distance[index] = at[3] + 1;
                queue.add(new int[]{x, y, z, at[3] + 1});
            }
        }

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (!area.canRemoveIn(x, z)) continue;
                for (int y = maxY; y >= minY; y--) {
                    Material type = area.type(x, y, z);
                    if (type.name().endsWith("_LEAVES")) {
                        if (distance[((x - minX) * sizeY + (y - minY)) * sizeZ + (z - minZ)] != Integer.MAX_VALUE) continue;
                        if (area.data(x, y, z) instanceof Leaves leaves && leaves.isPersistent()) continue;
                        if (area.type(x, y + 1, z) == Material.SNOW) area.set(x, y + 1, z, Material.AIR);
                        area.set(x, y, z, Material.AIR);
                    } else if (type == Material.COCOA && !touchesWood(area, x, y, z)) {
                        area.set(x, y, z, Material.AIR);
                    }
                }
            }
        }
        // Only once every leaf that's going is gone — a vine judged against a leaf that's
        // removed a column later would be left floating. Top-down, so a vine hanging from a
        // vine that just lost its hold goes too.
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (!area.canRemoveIn(x, z)) continue;
                for (int y = maxY; y >= minY; y--) {
                    if (area.type(x, y, z) == Material.VINE && !vineHeld(area, x, y, z)) {
                        area.set(x, y, z, Material.AIR);
                    }
                }
            }
        }
    }

    private static boolean isWood(Material type) {
        String name = type.name();
        return name.endsWith("_LOG") || name.endsWith("_WOOD") || type == Material.MANGROVE_ROOTS;
    }

    private static boolean touchesWood(Area area, int x, int y, int z) {
        return isWood(area.type(x + 1, y, z)) || isWood(area.type(x - 1, y, z))
                || isWood(area.type(x, y, z + 1)) || isWood(area.type(x, y, z - 1));
    }

    /** A vine stays if something solid (leaves count) is beside or above it, or the vine above it stayed. */
    private static boolean vineHeld(Area area, int x, int y, int z) {
        Material above = area.type(x, y + 1, z);
        if (above == Material.VINE || isHold(above)) return true;
        return isHold(area.type(x + 1, y, z)) || isHold(area.type(x - 1, y, z))
                || isHold(area.type(x, y, z + 1)) || isHold(area.type(x, y, z - 1));
    }

    private static boolean isHold(Material type) {
        return type.isSolid() || type.name().endsWith("_LEAVES");
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

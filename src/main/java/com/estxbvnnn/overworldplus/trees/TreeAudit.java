package com.estxbvnnn.overworldplus.trees;

import com.estxbvnnn.overworldplus.Area;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;

/**
 * Checks the trees that should be standing in loaded chunks against what
 * is actually there — the exact two failure modes reported on a real
 * world: a canopy with its trunk missing, and a base hanging in the air.
 */
public final class TreeAudit {

    public record Report(int trees, int missingTrunk, int woodMissingSome, int floatingBase, int chunksChecked,
                         int vanillaLeft, int leafless, List<String> details) {
        @Override
        public String toString() {
            return trees + " tree(s) in " + chunksChecked + " loaded chunk(s): " + missingTrunk + " with no trunk at the anchor, "
                    + woodMissingSome + " missing >10% of their wood, " + floatingBase + " with a base block over air; "
                    + vanillaLeft + " vanilla tree(s) still standing, " + leafless + " bare trunk(s) with no leaves."
                    + (details.isEmpty() ? "" : " First: " + String.join(" | ", details));
        }
    }

    private TreeAudit() {}

    public static Report run(World world, int centerChunkX, int centerChunkZ, int radiusChunks,
                             SchematicTreeLibrary library, FileConfiguration config) {
        Area area = Area.of(world, List.of(), (cx, cz) -> true);
        TreeGrid.Settings settings = TreeGrid.Settings.from(config);
        int trees = 0, missingTrunk = 0, woodMissing = 0, floating = 0, chunks = 0;
        List<String> details = new java.util.ArrayList<>();

        for (int cx = centerChunkX - radiusChunks; cx <= centerChunkX + radiusChunks; cx++) {
            for (int cz = centerChunkZ - radiusChunks; cz <= centerChunkZ + radiusChunks; cz++) {
                if (!world.isChunkLoaded(cx, cz)) continue;
                chunks++;
                for (TreeGrid.PlannedTree tree : TreeGrid.planChunk(area, cx, cz, library, settings)) {
                    trees++;
                    StringBuilder problem = new StringBuilder();
                    Material atAnchor = area.type(tree.x(), tree.y(), tree.z());
                    if (!isWood(atAnchor)) {
                        missingTrunk++;
                        problem.append("anchor=").append(atAnchor).append(' ');
                    }

                    int expected = 0, present = 0;
                    for (SchematicTree.BlockOffset offset : tree.tree().blocks()) {
                        if (offset.type() == SchematicTree.Type.LEAVES) continue;
                        expected++;
                        if (isWood(area.type(tree.x() + offset.dx(), tree.y() + offset.dy(), tree.z() + offset.dz()))) present++;
                    }
                    if (expected > 0 && present < expected * 0.9) {
                        woodMissing++;
                        problem.append("wood=").append(present).append('/').append(expected).append(' ');
                    }

                    int floatingBlocks = 0;
                    for (SchematicTree.BlockOffset offset : SchematicTreeLibrary.groundLayer(tree.tree())) {
                        int bx = tree.x() + offset.dx(), bz = tree.z() + offset.dz();
                        if (!area.isSolid(bx, tree.y() - 1, bz)) floatingBlocks++;
                    }
                    if (floatingBlocks > 0) {
                        floating++;
                        problem.append("floatingBase=").append(floatingBlocks).append(' ');
                    }

                    if (problem.length() > 0 && details.size() < 5) {
                        details.add(tree.species() + "#" + tree.variant() + " @" + tree.x() + "," + tree.y() + "," + tree.z()
                                + " (" + problem.toString().trim() + ")");
                    }
                }
            }
        }
        int[] leftovers = leftovers(world, area, centerChunkX, centerChunkZ, radiusChunks, library, settings, details);
        return new Report(trees, missingTrunk, woodMissing, floating, chunks, leftovers[0], leftovers[1], details);
    }

    /**
     * Every other trunk standing on soil in the loaded chunks — anything that isn't one of the
     * planned big trees: [0] vanilla trees still standing (natural leaves on them; mangroves,
     * jungle floor bushes and the beach/desert biomes, which are left alone on purpose, don't
     * count), [1] bare trunks with no leaves touching them at all.
     */
    private static int[] leftovers(World world, Area area, int centerChunkX, int centerChunkZ, int radiusChunks,
                                   SchematicTreeLibrary library, TreeGrid.Settings settings, List<String> details) {
        java.util.Set<Long> planned = new java.util.HashSet<>();
        for (int cx = centerChunkX - radiusChunks - 1; cx <= centerChunkX + radiusChunks + 1; cx++) {
            for (int cz = centerChunkZ - radiusChunks - 1; cz <= centerChunkZ + radiusChunks + 1; cz++) {
                for (TreeGrid.PlannedTree tree : TreeGrid.planChunk(area, cx, cz, library, settings)) {
                    SchematicTreeLibrary.collectWood(tree.tree(), tree.x(), tree.y(), tree.z(), planned);
                }
            }
        }
        int vanilla = 0, bare = 0;
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (int cx = centerChunkX - radiusChunks; cx <= centerChunkX + radiusChunks; cx++) {
            for (int cz = centerChunkZ - radiusChunks; cz <= centerChunkZ + radiusChunks; cz++) {
                if (!world.isChunkLoaded(cx, cz)) continue;
                for (int x = cx << 4; x < (cx << 4) + 16; x++) {
                    for (int z = cz << 4; z < (cz << 4) + 16; z++) {
                        TreeEnhancer.Pos base = TreeEnhancer.findTrunkBase(area, x, z);
                        if (base == null) continue;
                        long key = com.estxbvnnn.overworldplus.BlockKey.of(base.x(), base.y(), base.z());
                        if (planned.contains(key) || !seen.add(key)) continue;
                        // Kept on purpose (mangroves, jungle bushes, beach/desert pieces): only a bare one is a problem.
                        boolean kept = TreeEnhancer.leftAloneOnPurpose(area, base);
                        // A log lying on its side is a fallen log (ForestFloor's, or vanilla's own) — not a tree.
                        if (area.data(base.x(), base.y(), base.z()) instanceof org.bukkit.block.data.Orientable log
                                && log.getAxis() != org.bukkit.Axis.Y) continue;
                        java.util.Set<TreeEnhancer.Pos> logs = TreeEnhancer.collectTree(area, base, planned);
                        logs.forEach(p -> seen.add(com.estxbvnnn.overworldplus.BlockKey.of(p.x(), p.y(), p.z())));
                        TreeEnhancer.LeafContact contact = TreeEnhancer.leafContact(area, logs);
                        String what = null;
                        if (contact == TreeEnhancer.LeafContact.NATURAL && !kept) {
                            vanilla++;
                            what = "vanilla ";
                        } else if (contact == TreeEnhancer.LeafContact.NONE
                                && !(kept && TreeEnhancer.inHandsOffBiome(area, base))) {
                            bare++;
                            what = "bare ";
                        }
                        if (what != null && details.size() < 8) {
                            details.add(what + area.type(base.x(), base.y(), base.z()).name().toLowerCase() + "x" + logs.size()
                                    + " @" + base.x() + "," + base.y() + "," + base.z());
                        }
                    }
                }
            }
        }
        return new int[]{vanilla, bare};
    }

    private static boolean isWood(Material type) {
        String name = type.name();
        return name.endsWith("_LOG") || name.endsWith("_WOOD") || type == Material.MANGROVE_ROOTS;
    }
}

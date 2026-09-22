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
                         List<String> details) {
        @Override
        public String toString() {
            return trees + " tree(s) in " + chunksChecked + " loaded chunk(s): " + missingTrunk + " with no trunk at the anchor, "
                    + woodMissingSome + " missing >10% of their wood, " + floatingBase + " with a base block over air."
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
        return new Report(trees, missingTrunk, woodMissing, floating, chunks, details);
    }

    private static boolean isWood(Material type) {
        String name = type.name();
        return name.endsWith("_LOG") || name.endsWith("_WOOD") || type == Material.MANGROVE_ROOTS;
    }
}

package com.estxbvnnn.overworldplus.trees;

import com.estxbvnnn.overworldplus.Area;
import com.estxbvnnn.overworldplus.BlockKey;
import org.bukkit.Axis;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.plugin.Plugin;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.logging.Level;

/**
 * Loads real tree shapes for every overworld species, bundled as plugin
 * resources (extracted from a full tree-schematic pack — see tools/ in the
 * repo) and pastes them directly. No procedural vanilla-tree generation is
 * used anymore for any species: it's always a fixed block list, so cost is
 * exactly the tree's real block count, and the shape is exactly what was
 * hand-designed.
 */
public final class SchematicTreeLibrary {

    /** A chosen variant plus its index, so the same choice can be re-derived later from the index alone. */
    public record Pick(int index, SchematicTree tree) {}

    private final Map<TreeSpecies, List<SchematicTree>> bySpecies = new EnumMap<>(TreeSpecies.class);

    public SchematicTreeLibrary(Plugin plugin) {
        int totalTrees = 0;
        for (TreeSpecies species : TreeSpecies.values()) {
            List<SchematicTree> trees = load(plugin, species);
            bySpecies.put(species, trees);
            totalTrees += trees.size();
        }
        plugin.getLogger().info("Loaded " + totalTrees + " schematic tree variants across "
                + TreeSpecies.values().length + " species.");
    }

    private List<SchematicTree> load(Plugin plugin, TreeSpecies species) {
        List<SchematicTree> result = new ArrayList<>();
        String path = "trees/" + species.resourceKey() + "_variants.txt.gz";
        try (InputStream raw = plugin.getResource(path)) {
            if (raw == null) {
                plugin.getLogger().warning(path + " not found in jar — " + species + " trees will stay untouched vanilla.");
                return result;
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new GZIPInputStream(raw), StandardCharsets.UTF_8))) {
                int count = Integer.parseInt(reader.readLine().trim());
                for (int i = 0; i < count; i++) {
                    String[] header = reader.readLine().split(",");
                    int height = Integer.parseInt(header[1]);
                    int spread = Integer.parseInt(header[2]);

                    List<SchematicTree.BlockOffset> blocks = new ArrayList<>();
                    String blockLine = reader.readLine();
                    for (String part : blockLine.split(";")) {
                        String[] f = part.split(",");
                        blocks.add(new SchematicTree.BlockOffset(
                                Integer.parseInt(f[0]), Integer.parseInt(f[1]), Integer.parseInt(f[2]),
                                SchematicTree.Type.valueOf(f[3])));
                    }
                    result.add(new SchematicTree(height, spread, blocks));
                }
            }
        } catch (IOException | RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Failed loading " + path, e);
        }
        return result;
    }

    public boolean isEmpty(TreeSpecies species) {
        return bySpecies.getOrDefault(species, List.of()).isEmpty();
    }

    public List<SchematicTree> variants(TreeSpecies species) {
        return bySpecies.getOrDefault(species, List.of());
    }

    /**
     * Picks the variant of the given species that fits under {@code maxHeight}, chosen by
     * {@code selector} (a coordinate hash, so the same spot always gets the same tree — and
     * so a neighbor chunk can re-derive which tree stands here without being told).
     */
    public Optional<Pick> pickFor(TreeSpecies species, int maxHeight, int selector) {
        List<SchematicTree> all = variants(species);
        List<Integer> eligible = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).height() <= maxHeight) eligible.add(i);
        }
        if (eligible.isEmpty()) return Optional.empty();
        int index = eligible.get(Math.floorMod(selector, eligible.size()));
        return Optional.of(new Pick(index, all.get(index)));
    }

    // Real canopies run into the hundreds of leaf blocks each, all opaque and all rendered —
    // that's the actual FPS cost of "real trees instead of vanilla's sparse ones". Skipping
    // every 5th leaf position (a fixed coordinate rule, not a per-block dice roll) cuts ~20%
    // of that render cost while the canopy silhouette stays intact; trunk/branch wood is never
    // skipped, since that's what still needs to read as a tree at a glance.
    private static final int LEAF_THIN_STRIDE = 5;

    public void paste(Area area, TreeSpecies species, SchematicTree tree, int ax, int ay, int az) {
        for (SchematicTree.BlockOffset offset : tree.blocks()) {
            if (offset.type() == SchematicTree.Type.LEAVES && isThinnedLeaf(offset)) continue;
            int x = ax + offset.dx(), y = ay + offset.dy(), z = az + offset.dz();
            if (offset.type() == SchematicTree.Type.LEAVES) {
                Material existing = area.type(x, y, z);
                // Never let foliage cut through a neighboring tree's wood (jungle canopies
                // overlap on purpose), and never let a drooping branch's leaves replace ground.
                if (isWood(existing)) continue;
                if (offset.dy() <= 0 && existing.isSolid() && !isTreeBlock(existing)) continue;
            }
            applyBlock(area, x, y, z, offset.type(), species);
        }
    }

    /** Every wood/root position this tree occupies once pasted at the given anchor. */
    public static void collectWood(SchematicTree tree, int ax, int ay, int az, Set<Long> into) {
        for (SchematicTree.BlockOffset offset : tree.blocks()) {
            if (offset.type() == SchematicTree.Type.LEAVES) continue;
            into.add(BlockKey.of(ax + offset.dx(), ay + offset.dy(), az + offset.dz()));
        }
    }

    /** The wood/root blocks that sit at trunk-base level — what has to be standing on something. */
    public static List<SchematicTree.BlockOffset> groundLayer(SchematicTree tree) {
        List<SchematicTree.BlockOffset> layer = new ArrayList<>();
        for (SchematicTree.BlockOffset offset : tree.blocks()) {
            if (offset.dy() == 0 && offset.type() != SchematicTree.Type.LEAVES) layer.add(offset);
        }
        return layer;
    }

    private static boolean isTreeBlock(Material type) {
        return isWood(type) || type.name().endsWith("_LEAVES");
    }

    private static boolean isWood(Material type) {
        String name = type.name();
        return name.endsWith("_LOG") || name.endsWith("_WOOD") || type == Material.MANGROVE_ROOTS;
    }

    private boolean isThinnedLeaf(SchematicTree.BlockOffset offset) {
        int sum = offset.dx() + offset.dy() * 3 + offset.dz() * 7; // decorrelate the three axes
        return Math.floorMod(sum, LEAF_THIN_STRIDE) == 0;
    }

    private void applyBlock(Area area, int x, int y, int z, SchematicTree.Type type, TreeSpecies species) {
        switch (type) {
            case LOG_X -> setOriented(area, x, y, z, species.log(), Axis.X);
            case LOG_Y -> setOriented(area, x, y, z, species.log(), Axis.Y);
            case LOG_Z -> setOriented(area, x, y, z, species.log(), Axis.Z);
            case WOOD_X -> setOriented(area, x, y, z, species.wood(), Axis.X);
            case WOOD_Y -> setOriented(area, x, y, z, species.wood(), Axis.Y);
            case WOOD_Z -> setOriented(area, x, y, z, species.wood(), Axis.Z);
            case LEAVES -> setLeaves(area, x, y, z, species.leaves());
            case ROOTS -> area.set(x, y, z, Material.MANGROVE_ROOTS);
        }
    }

    private void setOriented(Area area, int x, int y, int z, Material material, Axis axis) {
        BlockData data = material.createBlockData();
        if (data instanceof Orientable orientable) orientable.setAxis(axis);
        area.set(x, y, z, data);
    }

    /** Pasted, not grown from a log network — flagged persistent so it never decays. */
    public static void setLeaves(Area area, int x, int y, int z, Material leavesMaterial) {
        BlockData data = leavesMaterial.createBlockData();
        if (data instanceof Leaves leaves) leaves.setPersistent(true);
        area.set(x, y, z, data);
    }
}

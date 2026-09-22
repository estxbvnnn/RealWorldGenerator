package com.estxbvnnn.overworldplus.trees;

import com.estxbvnnn.overworldplus.Area;
import com.estxbvnnn.overworldplus.CoordHash;
import org.bukkit.HeightMap;
import org.bukkit.Material;
import org.bukkit.block.Biome;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntFunction;

/**
 * Where the big trees go, decided by the world itself — not by where vanilla
 * happened to drop its own small trees. The world is cut into a grid of
 * cells (20 blocks for normal forests, 10 for jungle); each cell owns one
 * candidate spot, jittered by a coordinate hash, and that spot gets a tree
 * if its biome wants one there (every cell in a forest, one cell in ten on
 * the plains) and the ground can carry it. Everything is a fixed function of
 * the seed and the coordinates, so:
 *
 * - spacing is guaranteed everywhere, including across chunk borders, with
 *   no per-chunk bookkeeping at all;
 * - a chunk can work out, on its own, exactly which trees its neighbors
 *   have — same inputs, same answer — which is what lets vanilla-tree removal
 *   walk right up to a neighbor's big trunk without ever touching it;
 * - it comes out identical whether it ran at generation time or later on an
 *   already-loaded chunk.
 */
public final class TreeGrid {

    public record PlannedTree(int x, int y, int z, TreeSpecies species, int variant, SchematicTree tree) {}

    public record Settings(int normalSpacing, int jungleSpacing) {
        public static Settings from(FileConfiguration config) {
            return new Settings(
                    Math.max(8, config.getInt("trees.spacing", 20)),
                    Math.max(6, config.getInt("trees.jungle-spacing", 10)));
        }
    }

    private enum Grid { NORMAL, DENSE }

    /** Which species a biome grows, and how many of its cells actually get one (1 = every cell). */
    private record Profile(Grid grid, int fill, IntFunction<TreeSpecies> chooser) {
        static Profile of(Grid grid, int fill, TreeSpecies only) {
            return new Profile(grid, fill, h -> only);
        }
    }

    private static final Map<Biome, Profile> PROFILES = Map.ofEntries(
            Map.entry(Biome.FOREST, new Profile(Grid.NORMAL, 1, h -> h % 5 == 0 ? TreeSpecies.BIRCH : TreeSpecies.OAK)),
            Map.entry(Biome.WINDSWEPT_FOREST, new Profile(Grid.NORMAL, 1, h -> h % 3 == 0 ? TreeSpecies.SPRUCE : TreeSpecies.OAK)),
            Map.entry(Biome.FLOWER_FOREST, new Profile(Grid.NORMAL, 2, h -> h % 3 == 0 ? TreeSpecies.BIRCH : TreeSpecies.OAK)),
            Map.entry(Biome.BIRCH_FOREST, Profile.of(Grid.NORMAL, 1, TreeSpecies.BIRCH)),
            Map.entry(Biome.OLD_GROWTH_BIRCH_FOREST, Profile.of(Grid.NORMAL, 1, TreeSpecies.BIRCH)),
            Map.entry(Biome.DARK_FOREST, new Profile(Grid.NORMAL, 1, h -> h % 7 == 0 ? TreeSpecies.OAK : TreeSpecies.DARK_OAK)),
            Map.entry(Biome.PALE_GARDEN, Profile.of(Grid.NORMAL, 1, TreeSpecies.PALE_OAK)),
            Map.entry(Biome.TAIGA, Profile.of(Grid.NORMAL, 1, TreeSpecies.SPRUCE)),
            Map.entry(Biome.OLD_GROWTH_PINE_TAIGA, Profile.of(Grid.NORMAL, 1, TreeSpecies.SPRUCE)),
            Map.entry(Biome.OLD_GROWTH_SPRUCE_TAIGA, Profile.of(Grid.NORMAL, 1, TreeSpecies.SPRUCE)),
            Map.entry(Biome.SNOWY_TAIGA, Profile.of(Grid.NORMAL, 1, TreeSpecies.SPRUCE)),
            Map.entry(Biome.GROVE, Profile.of(Grid.NORMAL, 2, TreeSpecies.SPRUCE)),
            Map.entry(Biome.SAVANNA, Profile.of(Grid.NORMAL, 3, TreeSpecies.ACACIA)),
            Map.entry(Biome.SAVANNA_PLATEAU, Profile.of(Grid.NORMAL, 3, TreeSpecies.ACACIA)),
            Map.entry(Biome.WINDSWEPT_SAVANNA, Profile.of(Grid.NORMAL, 4, TreeSpecies.ACACIA)),
            Map.entry(Biome.CHERRY_GROVE, Profile.of(Grid.NORMAL, 2, TreeSpecies.CHERRY)),
            // Mangrove swamps stay vanilla on purpose: their trees stand on root mazes over water,
            // which neither the ground test nor the mound fill can honestly reproduce.
            Map.entry(Biome.SWAMP, Profile.of(Grid.NORMAL, 2, TreeSpecies.OAK)),
            Map.entry(Biome.PLAINS, Profile.of(Grid.NORMAL, 10, TreeSpecies.OAK)),
            Map.entry(Biome.SUNFLOWER_PLAINS, Profile.of(Grid.NORMAL, 10, TreeSpecies.OAK)),
            Map.entry(Biome.MEADOW, Profile.of(Grid.NORMAL, 14, TreeSpecies.OAK)),
            Map.entry(Biome.WOODED_BADLANDS, Profile.of(Grid.NORMAL, 3, TreeSpecies.OAK)),
            Map.entry(Biome.WINDSWEPT_HILLS, new Profile(Grid.NORMAL, 5, h -> h % 2 == 0 ? TreeSpecies.SPRUCE : TreeSpecies.OAK)),
            Map.entry(Biome.WINDSWEPT_GRAVELLY_HILLS, Profile.of(Grid.NORMAL, 6, TreeSpecies.SPRUCE)),
            Map.entry(Biome.SNOWY_PLAINS, Profile.of(Grid.NORMAL, 20, TreeSpecies.SPRUCE)),
            Map.entry(Biome.SPARSE_JUNGLE, Profile.of(Grid.NORMAL, 2, TreeSpecies.JUNGLE)),
            Map.entry(Biome.JUNGLE, Profile.of(Grid.DENSE, 1, TreeSpecies.JUNGLE)),
            Map.entry(Biome.BAMBOO_JUNGLE, Profile.of(Grid.DENSE, 2, TreeSpecies.JUNGLE))
    );

    static final Set<Material> SOIL_TYPES = Set.of(
            Material.GRASS_BLOCK, Material.DIRT, Material.PODZOL, Material.MYCELIUM,
            Material.ROOTED_DIRT, Material.MOSS_BLOCK, Material.COARSE_DIRT, Material.PALE_MOSS_BLOCK,
            Material.SAND, Material.RED_SAND, Material.MUD
    );

    /** How far down a floating base block may be propped up with rooted dirt before the spot is rejected. */
    public static final int MAX_MOUND_DEPTH = 4;

    private static final long NORMAL_SALT = 0x7EEE_0001L;
    private static final long DENSE_SALT = 0x7EEE_0002L;

    private TreeGrid() {}

    /** Every planned tree whose trunk anchor falls inside this chunk. */
    public static List<PlannedTree> planChunk(Area area, int chunkX, int chunkZ, SchematicTreeLibrary library, Settings settings) {
        List<PlannedTree> planned = new ArrayList<>();
        int minX = chunkX << 4, minZ = chunkZ << 4;
        collect(area, minX, minZ, settings.normalSpacing(), NORMAL_SALT, Grid.NORMAL, library, planned);
        collect(area, minX, minZ, settings.jungleSpacing(), DENSE_SALT, Grid.DENSE, library, planned);
        return planned;
    }

    /** Every wood/root block position of the trees in this chunk and its eight neighbors. */
    public static Set<Long> woodAround(Area area, int chunkX, int chunkZ, SchematicTreeLibrary library, Settings settings) {
        Set<Long> wood = new HashSet<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (PlannedTree tree : planChunk(area, chunkX + dx, chunkZ + dz, library, settings)) {
                    SchematicTreeLibrary.collectWood(tree.tree(), tree.x(), tree.y(), tree.z(), wood);
                }
            }
        }
        return wood;
    }

    private static void collect(Area area, int minX, int minZ, int spacing, long salt, Grid grid,
                                SchematicTreeLibrary library, List<PlannedTree> into) {
        int firstCellX = Math.floorDiv(minX, spacing), lastCellX = Math.floorDiv(minX + 15, spacing);
        int firstCellZ = Math.floorDiv(minZ, spacing), lastCellZ = Math.floorDiv(minZ + 15, spacing);
        for (int cx = firstCellX; cx <= lastCellX; cx++) {
            for (int cz = firstCellZ; cz <= lastCellZ; cz++) {
                resolveCell(area, cx, cz, spacing, salt, grid, library).ifPresent(tree -> {
                    if (tree.x() >= minX && tree.x() < minX + 16 && tree.z() >= minZ && tree.z() < minZ + 16) {
                        into.add(tree);
                    }
                });
            }
        }
    }

    private static Optional<PlannedTree> resolveCell(Area area, int cellX, int cellZ, int spacing, long salt, Grid grid,
                                                     SchematicTreeLibrary library) {
        long seed = area.seed() ^ salt;
        int band = Math.max(1, spacing / 3); // jitter within the middle third: neighbors stay >= 2/3 spacing apart
        int x = cellX * spacing + band + CoordHash.pick(seed, cellX, cellZ, band + 1);
        int z = cellZ * spacing + band + CoordHash.pick(seed, cellZ, cellX, band + 1);

        int groundY = groundY(area, x, z);
        if (groundY == Integer.MIN_VALUE) return Optional.empty();
        int baseY = groundY + 1;
        if (isSubmerged(area, x, baseY, z)) return Optional.empty();

        Profile profile = PROFILES.get(area.biome(x, baseY, z));
        if (profile == null || profile.grid() != grid) return Optional.empty();
        if (profile.fill() > 1 && CoordHash.pick(seed, x + 3, z, profile.fill()) != 0) return Optional.empty();

        TreeSpecies species = profile.chooser().apply(CoordHash.of(seed, x, z + 3));
        if (library.isEmpty(species)) return Optional.empty();

        int clearance = area.maxY() - baseY - 2;
        Optional<SchematicTreeLibrary.Pick> pick = library.pickFor(species, clearance, CoordHash.of(seed, x + 7, z + 7));
        if (pick.isEmpty()) return Optional.empty();
        if (!canStand(area, x, groundY, z, pick.get().tree())) return Optional.empty();

        return Optional.of(new PlannedTree(x, baseY, z, species, pick.get().index(), pick.get().tree()));
    }

    /**
     * The real ground under a spot: walks down through air, plants, leaves and logs (a vanilla
     * tree that's about to be removed, or this very tree once it's standing) to the first
     * solid non-tree block. Gives the same answer before and after the tree is pasted, which
     * is what makes a neighbor's re-derivation match.
     */
    static int groundY(Area area, int x, int z) {
        int y = area.highestY(x, z, HeightMap.WORLD_SURFACE) + 1;
        int minY = area.minY();
        while (y > minY) {
            Material type = area.type(x, y, z);
            if (type == Material.AIR || !type.isSolid() || isTreeBlock(type)) {
                y--;
                continue;
            }
            return SOIL_TYPES.contains(type) ? y : Integer.MIN_VALUE;
        }
        return Integer.MIN_VALUE;
    }

    /**
     * Under water, however shallow: the column's top block is water (seagrass and kelp sit
     * under the surface, so "is the block above the ground water?" alone misses them), or
     * water sits anywhere in the trunk's first few blocks.
     */
    private static boolean isSubmerged(Area area, int x, int baseY, int z) {
        int top = area.highestY(x, z, HeightMap.WORLD_SURFACE);
        if (area.type(x, top, z) == Material.WATER) return true;
        for (int y = baseY; y <= baseY + 2; y++) {
            Material type = area.type(x, y, z);
            if (type == Material.WATER || type == Material.LAVA) return true;
        }
        return false;
    }

    /** Would every trunk-base block end up on something within MAX_MOUND_DEPTH of propping up? */
    private static boolean canStand(Area area, int x, int groundY, int z, SchematicTree tree) {
        for (SchematicTree.BlockOffset offset : SchematicTreeLibrary.groundLayer(tree)) {
            int bx = x + offset.dx(), bz = z + offset.dz();
            if (!area.canEdit(bx, groundY, bz)) continue; // beyond what we can see — assume fine (see TreeEnhancer)
            if (moundDepth(area, bx, groundY, bz) > MAX_MOUND_DEPTH) return false;
        }
        return true;
    }

    /**
     * How many blocks of air (or shallow water — a bank tree's footing may reach into the
     * shallows, and water that spreads after generation must not leave a base hanging) sit
     * under (x, groundY, z) before something solid. 0 if it's already supported.
     */
    static int moundDepth(Area area, int x, int groundY, int z) {
        int depth = 0;
        int y = groundY;
        while (depth <= MAX_MOUND_DEPTH && y > area.minY()) {
            Material type = area.type(x, y, z);
            if (type.isSolid() && !isTreeBlock(type)) return depth;
            if (type == Material.LAVA) return MAX_MOUND_DEPTH + 1;
            depth++;
            y--;
        }
        return depth;
    }

    static boolean isTreeBlock(Material type) {
        String name = type.name();
        return name.endsWith("_LOG") || name.endsWith("_WOOD") || name.endsWith("_LEAVES");
    }
}

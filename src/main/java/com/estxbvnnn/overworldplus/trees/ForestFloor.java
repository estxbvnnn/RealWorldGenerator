package com.estxbvnnn.overworldplus.trees;

import com.estxbvnnn.overworldplus.Area;
import com.estxbvnnn.overworldplus.CoordHash;
import com.estxbvnnn.overworldplus.PlantPlacer;
import com.estxbvnnn.overworldplus.SolidGroundFinder;
import org.bukkit.Axis;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.block.data.Orientable;

import java.util.ArrayList;
import java.util.List;

/**
 * What lives under a real tree: a fallen log now and then, a couple of
 * shrubs, ferns/grass/mushrooms — and in the jungle, vines trailing off the
 * canopy. All decided by a fixed rule on the tree's coordinates, so the same
 * tree always has the same floor.
 */
final class ForestFloor {

    private static final BlockFace[] CARDINAL = {
            BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST
    };
    private static final BlockFace[] AROUND = {
            BlockFace.NORTH, BlockFace.NORTH_EAST, BlockFace.EAST, BlockFace.SOUTH_EAST,
            BlockFace.SOUTH, BlockFace.SOUTH_WEST, BlockFace.WEST, BlockFace.NORTH_WEST
    };

    private ForestFloor() {}

    static void decorate(Area area, TreeGrid.PlannedTree tree) {
        long seed = area.seed();
        int x = tree.x(), z = tree.z();
        TreeSpecies species = tree.species();

        if (CoordHash.pick(seed, x, z, 3) == 0) {
            placeFallenLog(area, tree, seed);
        }
        placeBush(area, species, x, z, AROUND[CoordHash.pick(seed, x + 11, z, 8)], 3 + CoordHash.pick(seed, x, z + 11, 3));
        placeBush(area, species, x, z, AROUND[CoordHash.pick(seed, x + 23, z, 8)], 4 + CoordHash.pick(seed, x, z + 23, 3));
        for (int i = 0; i < 4; i++) {
            BlockFace face = AROUND[CoordHash.pick(seed, x + 31 + i, z, 8)];
            int dist = 2 + CoordHash.pick(seed, x, z + 31 + i, 4);
            int px = x + face.getModX() * dist, pz = z + face.getModZ() * dist;
            int groundY = SolidGroundFinder.findY(area, px, pz);
            PlantPlacer.place(area, px, groundY, pz, floorPlant(species, CoordHash.pick(seed, x + i, z + i, 10)));
        }
        if (species == TreeSpecies.JUNGLE) {
            hangVines(area, tree, seed);
        }
    }

    private static void placeFallenLog(Area area, TreeGrid.PlannedTree tree, long seed) {
        int x = tree.x(), z = tree.z();
        BlockFace dir = CARDINAL[CoordHash.pick(seed, x + 7, z, 4)];
        int start = 4 + CoordHash.pick(seed, x, z + 7, 3);
        int length = 3 + CoordHash.pick(seed, x + 7, z + 7, 3);

        List<int[]> spots = new ArrayList<>();
        int groundY = Integer.MIN_VALUE;
        for (int i = 0; i < length; i++) {
            int d = start + i;
            int px = x + dir.getModX() * d, pz = z + dir.getModZ() * d;
            int gy = SolidGroundFinder.findY(area, px, pz);
            if (!TreeGrid.SOIL_TYPES.contains(area.type(px, gy, pz))) return;
            if (area.type(px, gy + 1, pz) != Material.AIR) return;
            if (groundY == Integer.MIN_VALUE) groundY = gy;
            if (gy != groundY) return; // only on flat ground — a log doesn't lie on a slope
            spots.add(new int[]{px, gy + 1, pz});
        }

        Axis axis = (dir == BlockFace.EAST || dir == BlockFace.WEST) ? Axis.X : Axis.Z;
        BlockData log = tree.species().log().createBlockData();
        if (log instanceof Orientable orientable) orientable.setAxis(axis);
        for (int[] spot : spots) {
            area.set(spot[0], spot[1], spot[2], log);
        }
        int[] second = spots.get(1);
        if (area.type(second[0], second[1] + 1, second[2]) == Material.AIR) {
            area.set(second[0], second[1] + 1, second[2],
                    CoordHash.pick(seed, x + 3, z + 3, 2) == 0 ? Material.MOSS_CARPET : Material.BROWN_MUSHROOM);
        }
    }

    /** A low, plus-shaped shrub of the tree's own leaves, one block off the ground. */
    private static void placeBush(Area area, TreeSpecies species, int x, int z, BlockFace face, int dist) {
        int px = x + face.getModX() * dist, pz = z + face.getModZ() * dist;
        int gy = SolidGroundFinder.findY(area, px, pz);
        if (!TreeGrid.SOIL_TYPES.contains(area.type(px, gy, pz))) return;
        int cy = gy + 1;
        if (area.type(px, cy, pz) != Material.AIR) return;

        SchematicTreeLibrary.setLeaves(area, px, cy, pz, species.leaves());
        for (BlockFace side : CARDINAL) {
            int ax = px + side.getModX(), az = pz + side.getModZ();
            if (area.type(ax, cy, az) == Material.AIR && area.isSolid(ax, cy - 1, az)) {
                SchematicTreeLibrary.setLeaves(area, ax, cy, az, species.leaves());
            }
        }
        if (area.type(px, cy + 1, pz) == Material.AIR) {
            SchematicTreeLibrary.setLeaves(area, px, cy + 1, pz, species.leaves());
        }
    }

    /**
     * Vines trailing from the underside edges of a jungle canopy — exactly the way vanilla
     * hangs them: each strand's top vine is attached to the side of a real leaf block, and
     * the ones below it carry the same side face down. Only ever placed into a column of
     * air, so nothing can end up floating with nothing to hang from.
     */
    private static void hangVines(Area area, TreeGrid.PlannedTree tree, long seed) {
        int strands = 0;
        int i = 0;
        for (SchematicTree.BlockOffset offset : tree.tree().blocks()) {
            if (strands >= 6) break;
            if (offset.type() != SchematicTree.Type.LEAVES) continue;
            if (offset.dy() < tree.tree().height() / 3) continue; // canopy, not low foliage
            i++;
            if (CoordHash.pick(seed, tree.x() + i, tree.z() - i, 9) != 0) continue;

            int lx = tree.x() + offset.dx(), ly = tree.y() + offset.dy(), lz = tree.z() + offset.dz();
            if (!area.type(lx, ly, lz).name().endsWith("_LEAVES")) continue; // thinned out, or overwritten

            BlockFace side = CARDINAL[CoordHash.pick(seed, lx, lz, 4)];
            int vx = lx + side.getModX(), vz = lz + side.getModZ();
            int length = 3 + CoordHash.pick(seed, lx + 5, lz + 5, 5);
            if (!isAirColumn(area, vx, ly, vz, length)) continue;

            BlockData vine = Material.VINE.createBlockData();
            if (vine instanceof MultipleFacing facing) facing.setFace(side.getOppositeFace(), true);
            for (int k = 0; k < length; k++) {
                area.set(vx, ly - k, vz, vine);
            }
            strands++;
        }
    }

    private static boolean isAirColumn(Area area, int x, int topY, int z, int length) {
        for (int k = 0; k < length; k++) {
            if (area.type(x, topY - k, z) != Material.AIR || !area.canEdit(x, topY - k, z)) return false;
        }
        return true;
    }

    private static Material floorPlant(TreeSpecies species, int roll) {
        return switch (species) {
            case SPRUCE -> roll < 5 ? Material.FERN : roll < 8 ? Material.LARGE_FERN : Material.BROWN_MUSHROOM;
            case DARK_OAK, PALE_OAK -> roll < 4 ? Material.FERN : roll < 7 ? Material.BROWN_MUSHROOM
                    : roll < 9 ? Material.RED_MUSHROOM : Material.MOSS_CARPET;
            case JUNGLE -> roll < 5 ? Material.FERN : roll < 9 ? Material.LARGE_FERN : Material.MOSS_CARPET;
            case ACACIA -> roll < 7 ? Material.SHORT_GRASS : Material.TALL_GRASS;
            case CHERRY -> roll < 6 ? Material.SHORT_GRASS : Material.PINK_PETALS;
            default -> roll < 6 ? Material.SHORT_GRASS : roll < 9 ? Material.FERN : Material.BROWN_MUSHROOM;
        };
    }
}

package com.estxbvnnn.overworldplus.structures.stronghold;

import com.estxbvnnn.overworldplus.BlockKey;
import com.estxbvnnn.overworldplus.CoordHash;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.entity.EntityType;
import org.bukkit.loot.LootTables;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A building surface in local coordinates: x runs to the right, y up, z forward (away from
 * the stronghold). The same design is built facing any of the four directions — every
 * position and every block facing goes through here, so a room written once comes out
 * right whichever way the wing points. Nothing touches the world: placements are collected
 * and applied later, in batches, by {@link StrongholdExpansion}.
 */
final class Canvas {

    enum Dir { FWD, BACK, LEFT, RIGHT }

    /** One block to place; loot / spawner are applied to the block's state after it's set. */
    record Placement(int x, int y, int z, BlockData data, LootTables loot, EntityType spawner) {}

    private final int ox, oy, oz;
    private final BlockFace forward, right;
    private final long seed;
    private final Map<Long, Placement> placements;

    Canvas(int ox, int oy, int oz, BlockFace forward, long seed) {
        this(ox, oy, oz, forward, seed, new LinkedHashMap<>());
    }

    private Canvas(int ox, int oy, int oz, BlockFace forward, long seed, Map<Long, Placement> placements) {
        this.ox = ox;
        this.oy = oy;
        this.oz = oz;
        this.forward = forward;
        this.right = clockwise(forward);
        this.seed = seed;
        this.placements = placements;
    }

    static BlockFace clockwise(BlockFace face) {
        return switch (face) {
            case NORTH -> BlockFace.EAST;
            case EAST -> BlockFace.SOUTH;
            case SOUTH -> BlockFace.WEST;
            case WEST -> BlockFace.NORTH;
            default -> throw new IllegalArgumentException("Not a horizontal face: " + face);
        };
    }

    /** A canvas whose origin is (lx, ly, lz) here and whose forward is {@code dir} here. Shares the placements. */
    Canvas sub(int lx, int ly, int lz, Dir dir) {
        return new Canvas(wx(lx, lz), oy + ly, wz(lx, lz), face(dir), seed, placements);
    }

    int wx(int lx, int lz) {
        return ox + right.getModX() * lx + forward.getModX() * lz;
    }

    int wy(int ly) {
        return oy + ly;
    }

    int wz(int lx, int lz) {
        return oz + right.getModZ() * lx + forward.getModZ() * lz;
    }

    BlockFace face(Dir dir) {
        return switch (dir) {
            case FWD -> forward;
            case BACK -> forward.getOppositeFace();
            case RIGHT -> right;
            case LEFT -> right.getOppositeFace();
        };
    }

    long seed() {
        return seed;
    }

    /** A fixed value in [0, bound) for this spot — same world, same stronghold, same wing. */
    int hash(int lx, int ly, int lz, int bound) {
        return CoordHash.pick(seed, wx(lx, lz) * 31 + wy(ly), wz(lx, lz), bound);
    }

    Map<Long, Placement> placements() {
        return placements;
    }

    // ---- placing ----

    void set(int lx, int ly, int lz, BlockData data) {
        put(new Placement(wx(lx, lz), wy(ly), wz(lx, lz), data, null, null));
    }

    void set(int lx, int ly, int lz, Material material) {
        set(lx, ly, lz, material.createBlockData());
    }

    /** Material with its data adjusted (facing, hanging, ...) — the facing helpers below make that one line. */
    @SuppressWarnings("unchecked")
    <T extends BlockData> void set(int lx, int ly, int lz, Material material, Class<T> type, Consumer<T> edit) {
        BlockData data = material.createBlockData();
        if (type.isInstance(data)) edit.accept((T) data);
        set(lx, ly, lz, data);
    }

    void fill(int x1, int y1, int z1, int x2, int y2, int z2, Material material) {
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
            for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
                for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
                    set(x, y, z, material);
                }
            }
        }
    }

    /** A hollow box: weathered stone-brick walls, floor and ceiling around an air interior. */
    void shell(int x1, int y1, int z1, int x2, int y2, int z2) {
        for (int x = x1; x <= x2; x++) {
            for (int y = y1; y <= y2; y++) {
                for (int z = z1; z <= z2; z++) {
                    boolean edge = x == x1 || x == x2 || y == y1 || y == y2 || z == z1 || z == z2;
                    set(x, y, z, edge ? brick(x, y, z) : Material.AIR);
                }
            }
        }
    }

    /** Stronghold masonry: mostly stone bricks, worn with moss and cracks — by position, never by dice. */
    Material brick(int lx, int ly, int lz) {
        int h = hash(lx, ly, lz, 100);
        if (h < 60) return Material.STONE_BRICKS;
        if (h < 80) return Material.MOSSY_STONE_BRICKS;
        if (h < 95) return Material.CRACKED_STONE_BRICKS;
        return Material.COBBLESTONE;
    }

    void chest(int lx, int ly, int lz, Dir facing, LootTables loot) {
        BlockData data = Material.CHEST.createBlockData();
        if (data instanceof org.bukkit.block.data.Directional d) d.setFacing(face(facing));
        put(new Placement(wx(lx, lz), wy(ly), wz(lx, lz), data, loot, null));
    }

    void barrel(int lx, int ly, int lz, Dir facing, LootTables loot) {
        BlockData data = Material.BARREL.createBlockData();
        if (data instanceof org.bukkit.block.data.Directional d) d.setFacing(face(facing));
        put(new Placement(wx(lx, lz), wy(ly), wz(lx, lz), data, loot, null));
    }

    void spawner(int lx, int ly, int lz, EntityType type) {
        put(new Placement(wx(lx, lz), wy(ly), wz(lx, lz), Material.SPAWNER.createBlockData(), null, type));
    }

    private void put(Placement placement) {
        placements.put(BlockKey.of(placement.x(), placement.y(), placement.z()), placement);
    }

    /**
     * Blocks are placed without physics (nothing may flow or pop off mid-build), so iron bars
     * wouldn't join up on their own — each gets the sides that touch another bar or a solid
     * block of the design, the way the game would have connected them.
     */
    void connectBars() {
        for (Map.Entry<Long, Placement> entry : placements.entrySet()) {
            Placement p = entry.getValue();
            if (p.data().getMaterial() != Material.IRON_BARS || !(p.data() instanceof MultipleFacing bars)) continue;
            for (BlockFace side : new BlockFace[]{BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST}) {
                Placement next = placements.get(BlockKey.of(p.x() + side.getModX(), p.y(), p.z() + side.getModZ()));
                boolean joins = next != null && (next.data().getMaterial() == Material.IRON_BARS
                        || next.data().getMaterial().isOccluding());
                bars.setFace(side, joins);
            }
        }
    }
}

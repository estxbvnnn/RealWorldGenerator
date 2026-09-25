package com.estxbvnnn.overworldplus.structures.stronghold;

import com.estxbvnnn.overworldplus.structures.stronghold.Canvas.Dir;
import org.bukkit.Axis;
import org.bukkit.Material;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Levelled;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Candle;
import org.bukkit.block.data.type.Lantern;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.entity.EntityType;
import org.bukkit.loot.LootTables;

import java.util.ArrayList;
import java.util.List;

/**
 * The new wing, in the wing's own frame (see {@link Canvas}): y 0 is the floor, level with the
 * stronghold corridor it's joined to; z 0 is the gatehouse door; x 0 runs down the middle.
 *
 * <pre>
 *   z  38 +------ great hall ------+
 *         |  dais + throne         |
 *      31 |=room=[corridor]  [corridor]=room=|   rooms: 13x13, 4 of 6 kinds
 *         |    pillars, chandeliers |
 *      15 |=room=[corridor]  [corridor]=room=|
 *       8 +-----------+------------+
 *       0    gatehouse (7 wide)
 *     -L    tunnel back to the stronghold corridor
 * </pre>
 *
 * Every choice that varies (which rooms, the wear on the bricks, where the cobwebs hang) is a
 * fixed function of the position, so a stronghold always gets the same wing.
 */
final class StrongholdWing {

    /** Outer extents in the wing frame — what the planner checks for room underground. */
    static final int HALF_WIDTH = 27;
    static final int LENGTH = 38;
    static final int HEIGHT = 11;

    private static final int HALL_HALF = 9;       // hall walls at x = +-9
    private static final int HALL_START = 8, HALL_END = 38;
    private static final int[] ROOM_CENTERS = {15, 31};
    private static final int[] PILLARS = {11, 19, 23, 27, 35};
    private static final int[] CHANDELIERS = {15, 23, 31};

    enum Room { LIBRARY, CRYPT, PRISON, ARMORY, ALCHEMY, VAULT }

    private StrongholdWing() {}

    /** Builds the whole wing plus a tunnel of {@code tunnelLength} back to the stronghold; returns the rooms chosen. */
    static List<Room> build(Canvas c, int tunnelLength) {
        tunnel(c, tunnelLength);
        gatehouse(c);
        hall(c);

        List<Room> rooms = chooseRooms(c);
        int i = 0;
        for (int side : new int[]{-1, 1}) {
            for (int center : ROOM_CENTERS) {
                Canvas wing = c.sub(side * HALL_HALF, 0, center, side < 0 ? Dir.LEFT : Dir.RIGHT);
                corridorAndShell(wing);
                room(wing, rooms.get(i++));
            }
        }
        c.connectBars();
        return rooms;
    }

    private static List<Room> chooseRooms(Canvas c) {
        List<Room> pool = new ArrayList<>(List.of(Room.values()));
        List<Room> chosen = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            chosen.add(pool.remove(c.hash(i * 7, 0, 3, pool.size())));
        }
        return chosen;
    }

    // ---- tunnel, gatehouse, hall ----

    private static void tunnel(Canvas c, int length) {
        for (int z = -length + 1; z <= -1; z++) {
            for (int x = -2; x <= 2; x++) {
                for (int y = 0; y <= 4; y++) {
                    boolean edge = x == -2 || x == 2 || y == 0 || y == 4;
                    c.set(x, y, z, edge ? c.brick(x, y, z) : Material.AIR);
                }
            }
            if ((z + length) % 6 == 3) hangingLantern(c, 0, 3, z, false);
        }
        // Through the stronghold corridor's own wall.
        c.fill(-1, 1, -length, 1, 3, -length, Material.AIR);
    }

    private static void gatehouse(Canvas c) {
        c.shell(-3, 0, 0, 3, 6, 7);
        c.fill(-1, 1, 0, 1, 3, 0, Material.AIR);          // from the tunnel
        c.fill(-1, 1, 7, 1, 4, 7, Material.AIR);          // into the hall
        for (int x = -2; x <= 2; x++) {
            for (int z = 1; z <= 6; z++) c.set(x, 0, z, (x + z) % 2 == 0 ? Material.POLISHED_ANDESITE : Material.STONE_BRICKS);
        }
        // Corner columns and a raised portcullis over the way in.
        for (int x : new int[]{-2, 2}) {
            for (int z : new int[]{1, 6}) c.fill(x, 1, z, x, 5, z, Material.CHISELED_STONE_BRICKS);
        }
        for (int x = -1; x <= 1; x++) c.set(x, 5, 2, Material.IRON_BARS);
        c.set(-2, 5, 2, Material.STONE_BRICKS);
        c.set(2, 5, 2, Material.STONE_BRICKS);
        hangingLantern(c, 0, 5, 4, false);
        cobweb(c, -2, 5, 5);
        cobweb(c, 2, 5, 3);
    }

    private static void hall(Canvas c) {
        c.shell(-HALL_HALF, 0, HALL_START, HALL_HALF, HEIGHT, HALL_END);
        c.fill(-1, 1, HALL_START, 1, 4, HALL_START, Material.AIR); // from the gatehouse

        // Checkered floor with a red runner down the middle to the throne.
        for (int x = -8; x <= 8; x++) {
            for (int z = HALL_START + 1; z < HALL_END; z++) {
                c.set(x, 0, z, (x + z) % 2 == 0 ? Material.POLISHED_ANDESITE : Material.STONE_BRICKS);
            }
        }
        for (int z = HALL_START + 1; z <= 32; z++) {
            for (int x = -1; x <= 1; x++) c.set(x, 1, z, x == 0 ? Material.RED_CARPET : Material.GRAY_CARPET);
        }

        // Pillars with ribs to the walls and lanterns hung under the ribs.
        for (int z : PILLARS) {
            for (int x : new int[]{-6, 6}) {
                c.set(x, 1, z, Material.CHISELED_STONE_BRICKS);
                c.fill(x, 2, z, x, 8, z, Material.STONE_BRICKS);
                c.set(x, 9, z, Material.CHISELED_STONE_BRICKS);
                c.set(x, 10, z, Material.STONE_BRICKS);
                int wall = x < 0 ? -8 : 8;
                for (int rib = Math.min(x, wall); rib <= Math.max(x, wall); rib++) c.set(rib, 10, z, Material.STONE_BRICKS);
                upsideDownStair(c, x, 10, z - 1, Dir.FWD);
                upsideDownStair(c, x, 10, z + 1, Dir.BACK);
                hangingLantern(c, x < 0 ? -7 : 7, 9, z, false);
            }
        }

        // Chandeliers down the middle.
        for (int z : CHANDELIERS) {
            c.set(0, 10, z, Material.IRON_CHAIN);
            c.set(0, 9, z, Material.IRON_CHAIN);
            c.set(0, 8, z, Material.IRON_CHAIN);
            hangingLantern(c, 0, 7, z, false);
        }

        // Doors out to the four side rooms.
        for (int center : ROOM_CENTERS) {
            c.fill(-HALL_HALF, 1, center - 1, -HALL_HALF, 3, center + 1, Material.AIR);
            c.fill(HALL_HALF, 1, center - 1, HALL_HALF, 3, center + 1, Material.AIR);
        }

        // The dais: one step up, a stone throne between two soul lights, the hall's strongbox.
        for (int x = -8; x <= 8; x++) {
            for (int z = 34; z < HALL_END; z++) c.set(x, 1, z, Material.POLISHED_ANDESITE);
        }
        for (int x = -3; x <= 3; x++) stair(c, x, 1, 33, Dir.FWD, Material.STONE_BRICK_STAIRS);
        stair(c, 0, 2, 36, Dir.FWD, Material.STONE_BRICK_STAIRS);
        c.set(-1, 2, 36, Material.STONE_BRICK_WALL);
        c.set(1, 2, 36, Material.STONE_BRICK_WALL);
        c.fill(0, 2, 37, 0, 4, 37, Material.CHISELED_STONE_BRICKS);
        for (int x : new int[]{-3, 3}) {
            c.set(x, 2, 36, Material.CHISELED_STONE_BRICKS);
            c.set(x, 3, 36, Material.SOUL_LANTERN);
        }
        c.chest(6, 2, 36, Dir.BACK, LootTables.STRONGHOLD_CROSSING);
        c.barrel(-6, 2, 36, Dir.BACK, null);

        // Age: webs gathered in the high corners.
        for (int z = HALL_START + 1; z < HALL_END; z++) {
            for (int x : new int[]{-8, 8}) {
                if (c.hash(x, 10, z, 5) == 0) cobweb(c, x, 10, z);
            }
        }
    }

    // ---- side rooms ----

    /** In a room's own frame: z 0 is the hall wall, z 1..5 the corridor, z 6..18 the room. */
    private static void corridorAndShell(Canvas r) {
        for (int z = 1; z <= 5; z++) {
            for (int x = -2; x <= 2; x++) {
                for (int y = 0; y <= 4; y++) {
                    boolean edge = x == -2 || x == 2 || y == 0 || y == 4;
                    r.set(x, y, z, edge ? r.brick(x, y, z) : Material.AIR);
                }
            }
        }
        hangingLantern(r, 0, 3, 3, false);
        r.shell(-6, 0, 6, 6, 8, 18);
        r.fill(-1, 1, 6, 1, 3, 6, Material.AIR);
    }

    private static void room(Canvas r, Room room) {
        switch (room) {
            case LIBRARY -> library(r);
            case CRYPT -> crypt(r);
            case PRISON -> prison(r);
            case ARMORY -> armory(r);
            case ALCHEMY -> alchemy(r);
            case VAULT -> vault(r);
        }
    }

    // Interior of every room: x -5..5, y 1..7, z 7..17; door at z 6, x -1..1.

    private static void library(Canvas r) {
        for (int x = -5; x <= 5; x++) {
            for (int z = 7; z <= 17; z++) r.set(x, 0, z, Material.OAK_PLANKS);
        }
        for (int y = 1; y <= 6; y++) {
            for (int z = 7; z <= 17; z++) {
                Material shelf = (z - 7) % 4 == 0 ? Material.OAK_LOG : Material.BOOKSHELF;
                r.set(-5, y, z, shelf);
                r.set(5, y, z, shelf);
            }
            for (int x = -4; x <= 4; x++) r.set(x, y, 17, x % 4 == 0 && x != 0 ? Material.OAK_LOG : Material.BOOKSHELF);
            for (int x : new int[]{-4, -3, 3, 4}) r.set(x, y, 7, Material.BOOKSHELF);
        }
        // A reading table under the chandelier, a lectern facing the door.
        for (int z = 11; z <= 13; z++) {
            r.set(-2, 1, z, Material.OAK_PLANKS);
            r.set(2, 1, z, Material.OAK_PLANKS);
        }
        candles(r, -2, 2, 11, Material.CANDLE, 3);
        candles(r, 2, 2, 13, Material.CANDLE, 2);
        r.set(0, 1, 15, Material.LECTERN, Directional.class, d -> d.setFacing(r.face(Dir.BACK)));
        r.set(0, 7, 12, Material.IRON_CHAIN);
        hangingLantern(r, 0, 6, 12, false);
        r.chest(4, 1, 16, Dir.LEFT, LootTables.STRONGHOLD_LIBRARY);
        cobweb(r, -4, 7, 16);
        cobweb(r, 4, 7, 8);
    }

    private static void crypt(Canvas r) {
        for (int x = -5; x <= 5; x++) {
            for (int z = 7; z <= 17; z++) {
                int h = r.hash(x, 0, z, 4);
                r.set(x, 0, z, h == 0 ? Material.SOUL_SOIL : h == 1 ? Material.MOSSY_COBBLESTONE : Material.STONE_BRICKS);
            }
        }
        // Three pairs of tombs, lidded with slabs; a skull on some.
        for (int x : new int[]{-3, 3}) {
            for (int z : new int[]{8, 11, 14}) {
                r.set(x, 1, z, Material.POLISHED_ANDESITE);
                r.set(x, 1, z + 1, Material.POLISHED_ANDESITE);
                r.set(x, 2, z + 1, Material.STONE_BRICK_SLAB);
                r.set(x, 2, z, r.hash(x, 2, z, 2) == 0 ? Material.SKELETON_SKULL : Material.STONE_BRICK_SLAB);
            }
        }
        // Bones stacked along the walls.
        for (int z = 8; z <= 16; z += 2) {
            for (int x : new int[]{-5, 5}) {
                r.set(x, 1, z, Material.BONE_BLOCK, Orientable.class, o -> o.setAxis(Axis.Y));
                if (r.hash(x, 2, z, 3) == 0) r.set(x, 2, z, Material.SKELETON_SKULL);
            }
        }
        r.spawner(0, 1, 12, EntityType.SKELETON);
        r.chest(0, 1, 17, Dir.BACK, LootTables.SIMPLE_DUNGEON);
        for (int x : new int[]{-4, 4}) {
            r.set(x, 1, 7, Material.SOUL_LANTERN);
            r.set(x, 1, 17, Material.SOUL_LANTERN);
        }
        candles(r, -1, 1, 16, Material.PURPLE_CANDLE, 3);
        for (int x = -5; x <= 5; x++) {
            for (int z = 7; z <= 17; z++) {
                if (r.hash(x, 7, z, 6) == 0) cobweb(r, x, 7, z);
            }
        }
    }

    private static void prison(Canvas r) {
        // Cells on both sides of a central aisle, three deep, walled apart at z 10 and 14.
        for (int side : new int[]{-1, 1}) {
            for (int y = 1; y <= 7; y++) {
                for (int dx = 2; dx <= 5; dx++) {
                    r.set(side * dx, y, 10, Material.STONE_BRICKS);
                    r.set(side * dx, y, 14, Material.STONE_BRICKS);
                }
            }
            for (int z = 7; z <= 17; z++) {
                if (z == 10 || z == 14) continue;
                for (int y = 1; y <= 7; y++) r.set(side * 2, y, z, y <= 4 ? Material.IRON_BARS : Material.STONE_BRICKS);
            }
        }
        // One cell's bars are broken open — and something still lives in it.
        r.set(-2, 1, 12, Material.AIR);
        r.set(-2, 2, 12, Material.AIR);
        r.spawner(-4, 1, 12, EntityType.ZOMBIE);
        for (int side : new int[]{-1, 1}) {
            for (int z : new int[]{8, 12, 16}) {
                r.set(side * 4, 7, z, Material.IRON_CHAIN);
                r.set(side * 4, 6, z, Material.IRON_CHAIN);
                if (r.hash(side * 3, 1, z, 2) == 0) cobweb(r, side * 3, 1, z + 1);
            }
        }
        r.set(4, 1, 8, Material.CAULDRON);
        r.chest(4, 1, 16, Dir.LEFT, LootTables.STRONGHOLD_CORRIDOR);
        hangingLantern(r, 0, 7, 9, false);
        hangingLantern(r, 0, 7, 15, false);
    }

    private static void armory(Canvas r) {
        for (int x = -5; x <= 5; x++) {
            for (int z = 7; z <= 17; z++) r.set(x, 0, z, (x + z) % 2 == 0 ? Material.POLISHED_ANDESITE : Material.STONE);
        }
        // Barrels racked along both walls, a smithy along the back.
        for (int z = 8; z <= 16; z++) {
            for (int side : new int[]{-1, 1}) {
                Dir inward = side < 0 ? Dir.RIGHT : Dir.LEFT;
                if (z % 3 == 0) {
                    r.set(side * 5, 1, z, Material.IRON_BARS);
                    r.set(side * 5, 2, z, Material.IRON_BARS);
                } else {
                    r.barrel(side * 5, 1, z, inward, z == 11 ? LootTables.STRONGHOLD_CORRIDOR : null);
                    if (z % 3 == 1) r.barrel(side * 5, 2, z, inward, null);
                }
            }
        }
        r.set(-4, 1, 17, Material.SMITHING_TABLE);
        r.set(-2, 1, 17, Material.ANVIL, Directional.class, d -> d.setFacing(r.face(Dir.RIGHT)));
        r.set(0, 1, 17, Material.BLAST_FURNACE, Directional.class, d -> d.setFacing(r.face(Dir.BACK)));
        r.set(2, 1, 17, Material.GRINDSTONE, Directional.class, d -> d.setFacing(r.face(Dir.BACK)));
        r.set(4, 1, 17, Material.WATER_CAULDRON, Levelled.class, l -> l.setLevel(l.getMaximumLevel()));
        r.chest(3, 1, 9, Dir.LEFT, LootTables.STRONGHOLD_CROSSING);
        r.chest(-3, 1, 15, Dir.RIGHT, LootTables.STRONGHOLD_CROSSING);
        hangingLantern(r, 0, 7, 10, false);
        hangingLantern(r, 0, 7, 14, false);
    }

    private static void alchemy(Canvas r) {
        for (int x = -5; x <= 5; x++) {
            for (int z = 7; z <= 17; z++) {
                r.set(x, 0, z, r.hash(x, 0, z, 3) == 0 ? Material.MOSSY_STONE_BRICKS : Material.POLISHED_DEEPSLATE);
            }
        }
        // Counters along both walls: brewing stands, cauldrons, potted fungi, candles.
        for (int side : new int[]{-1, 1}) {
            for (int z = 8; z <= 16; z++) {
                r.set(side * 5, 1, z, Material.POLISHED_ANDESITE);
                Material top = switch (z) {
                    case 9, 13 -> Material.BREWING_STAND;
                    case 11 -> Material.AIR;
                    case 15 -> side < 0 ? Material.POTTED_RED_MUSHROOM : Material.POTTED_BROWN_MUSHROOM;
                    default -> Material.AIR;
                };
                if (top != Material.AIR) r.set(side * 5, 2, z, top);
            }
            r.set(side * 5, 1, 11, Material.WATER_CAULDRON, Levelled.class, l -> l.setLevel(l.getMaximumLevel()));
            candles(r, side * 5, 2, 8, Material.GREEN_CANDLE, 2);
        }
        for (int x = -5; x <= 5; x++) {
            for (int y = 1; y <= 3; y++) r.set(x, y, 17, Material.BOOKSHELF);
        }
        r.chest(0, 1, 16, Dir.BACK, LootTables.STRONGHOLD_CROSSING);
        r.set(0, 1, 12, Material.POLISHED_ANDESITE);
        r.set(0, 2, 12, Material.BREWING_STAND);
        r.set(0, 7, 12, Material.IRON_CHAIN);
        r.set(0, 6, 12, Material.SOUL_LANTERN, Lantern.class, l -> l.setHanging(true));
        cobweb(r, -5, 7, 7);
        cobweb(r, 5, 7, 17);
    }

    private static void vault(Canvas r) {
        // The walls are riddled with silverfish: stone that isn't stone.
        for (int y = 1; y <= 7; y++) {
            for (int z = 7; z <= 17; z++) {
                r.set(-6, y, z, infested(r, -6, y, z));
                r.set(6, y, z, infested(r, 6, y, z));
            }
            for (int x = -5; x <= 5; x++) r.set(x, y, 18, infested(r, x, y, 18));
        }
        // A raised strongroom, caged in iron, two strongboxes inside.
        for (int x = -2; x <= 2; x++) {
            for (int z = 10; z <= 14; z++) r.set(x, 1, z, Material.POLISHED_ANDESITE);
        }
        for (int x = -2; x <= 2; x++) {
            for (int z = 10; z <= 14; z++) {
                boolean rim = x == -2 || x == 2 || z == 10 || z == 14;
                boolean gate = z == 10 && x == 0;
                if (rim && !gate) {
                    r.set(x, 2, z, Material.IRON_BARS);
                    r.set(x, 3, z, Material.IRON_BARS);
                }
            }
        }
        r.fill(-2, 4, 10, 2, 4, 14, Material.STONE_BRICK_SLAB);
        r.chest(-1, 2, 13, Dir.BACK, LootTables.STRONGHOLD_CORRIDOR);
        r.chest(1, 2, 13, Dir.BACK, LootTables.STRONGHOLD_CORRIDOR);
        for (int x : new int[]{-4, 4}) {
            for (int z : new int[]{8, 16}) {
                r.set(x, 1, z, Material.CHISELED_STONE_BRICKS);
                r.set(x, 2, z, Material.LANTERN);
            }
        }
        cobweb(r, -5, 7, 17);
        cobweb(r, 5, 7, 7);
    }

    // ---- small pieces ----

    private static Material infested(Canvas r, int x, int y, int z) {
        return switch (r.brick(x, y, z)) {
            case MOSSY_STONE_BRICKS -> Material.INFESTED_MOSSY_STONE_BRICKS;
            case CRACKED_STONE_BRICKS -> Material.INFESTED_CRACKED_STONE_BRICKS;
            default -> Material.INFESTED_STONE_BRICKS;
        };
    }

    private static void hangingLantern(Canvas c, int x, int y, int z, boolean soul) {
        c.set(x, y, z, soul ? Material.SOUL_LANTERN : Material.LANTERN, Lantern.class, l -> l.setHanging(true));
    }

    private static void cobweb(Canvas c, int x, int y, int z) {
        c.set(x, y, z, Material.COBWEB);
    }

    private static void candles(Canvas c, int x, int y, int z, Material candle, int count) {
        c.set(x, y, z, candle, Candle.class, cd -> {
            cd.setCandles(count);
            cd.setLit(true);
        });
    }

    private static void stair(Canvas c, int x, int y, int z, Dir facing, Material material) {
        c.set(x, y, z, material, Stairs.class, s -> s.setFacing(c.face(facing)));
    }

    private static void upsideDownStair(Canvas c, int x, int y, int z, Dir facing) {
        c.set(x, y, z, Material.STONE_BRICK_STAIRS, Stairs.class, s -> {
            s.setFacing(c.face(facing));
            s.setHalf(Bisected.Half.TOP);
        });
    }
}

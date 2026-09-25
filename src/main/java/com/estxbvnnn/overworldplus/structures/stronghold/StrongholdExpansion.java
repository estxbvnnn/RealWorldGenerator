package com.estxbvnnn.overworldplus.structures.stronghold;

import com.estxbvnnn.overworldplus.CoordHash;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.generator.structure.GeneratedStructure;
import org.bukkit.generator.structure.Structure;
import org.bukkit.generator.structure.StructurePiece;
import org.bukkit.loot.Lootable;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.BoundingBox;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Gives every stronghold a new wing (see {@link StrongholdWing}), leaving vanilla's own layout
 * — portal room, eye of ender, /locate — exactly as it was.
 *
 * A stronghold is far too big to touch while a chunk generates, so this runs when one of its
 * chunks is loaded: it picks a side where the whole wing sits safely underground, loads what
 * it needs in the background, and builds a few thousand blocks a tick. It's underground and
 * players approach a stronghold from far away, so it's finished long before anyone gets there;
 * it still waits if a player is already standing where it would build. The stronghold itself
 * remembers that it has its wing (in its own persistent data), so it's only ever done once.
 */
public final class StrongholdExpansion implements Listener {

    /** Blocks between the stronghold's outer edge and the gatehouse — the tunnel runs through it. */
    private static final int GAP = 4;
    /** Solid ground wanted over the wing's roof. */
    private static final int COVER = 6;
    private static final int MAX_TARGET_HEIGHT = 8;

    private final JavaPlugin plugin;
    private final FileConfiguration config;
    private final Logger logger;
    private final NamespacedKey wingKey;
    private final Set<String> inProgress = new HashSet<>();

    public StrongholdExpansion(JavaPlugin plugin, FileConfiguration config, Logger logger) {
        this.plugin = plugin;
        this.config = config;
        this.logger = logger;
        this.wingKey = new NamespacedKey(plugin, "stronghold_wing");
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        if (!"wing".equalsIgnoreCase(config.getString("structures.stronghold.mode", "better"))) return;
        if (!config.getBoolean("structures.stronghold.wing.enabled", true)) return;
        Chunk chunk = event.getChunk();
        if (chunk.getWorld().getEnvironment() != World.Environment.NORMAL) return;
        for (GeneratedStructure stronghold : chunk.getStructures(Structure.STRONGHOLD)) {
            consider(chunk.getWorld(), stronghold, null);
        }
    }

    /** What's recorded on a stronghold: null if it hasn't been looked at yet. */
    public String status(GeneratedStructure stronghold) {
        return stronghold.getPersistentDataContainer().get(wingKey, PersistentDataType.STRING);
    }

    /** Starts the wing for this stronghold unless it has one (or is getting one). {@code report} hears the outcome. */
    public void consider(World world, GeneratedStructure stronghold, Consumer<String> report) {
        String existing = status(stronghold);
        String id = idOf(world, stronghold.getBoundingBox());
        if (existing != null) {
            if (report != null) report.accept("Already done: " + existing);
            return;
        }
        if (!inProgress.add(id)) {
            if (report != null) report.accept("Already being built.");
            return;
        }
        List<int[]> pieces = new ArrayList<>();
        for (StructurePiece piece : stronghold.getPieces()) pieces.add(blocks(piece.getBoundingBox()));
        List<Plan> candidates = candidates(world, stronghold.getBoundingBox(), pieces);
        tryNext(world, stronghold, id, candidates.iterator(), report);
    }

    // ---- planning ----

    /** One way to attach the wing: facing out of one side, joined to one corridor. */
    private record Plan(BlockFace facing, int[] target, int originX, int originZ, int floorY, int tunnelLength) {
        int[] wingBox() {
            return box(this, -StrongholdWing.HALF_WIDTH, -tunnelLength, StrongholdWing.HALF_WIDTH, StrongholdWing.LENGTH,
                    floorY, floorY + StrongholdWing.HEIGHT);
        }
    }

    private List<Plan> candidates(World world, BoundingBox whole, List<int[]> pieces) {
        int[] all = blocks(whole);
        List<BlockFace> sides = new ArrayList<>(List.of(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST));
        // Which side is tried first is a fixed function of where the stronghold is.
        int turn = CoordHash.pick(world.getSeed(), all[0], all[2], 4);
        for (int i = 0; i < turn; i++) sides.add(sides.remove(0));

        List<Plan> plans = new ArrayList<>();
        for (BlockFace side : sides) {
            // Corridor-height pieces, outermost on this side first; never the portal room.
            List<int[]> targets = new ArrayList<>();
            for (int[] p : pieces) {
                if (p[4] - p[1] + 1 > MAX_TARGET_HEIGHT || isPortalRoom(p)) continue;
                targets.add(p);
            }
            targets.sort(Comparator.comparingInt(p -> -outward(p, side)));
            for (int t = 0; t < Math.min(3, targets.size()); t++) {
                int[] target = targets.get(t);
                int wall = outward(target, side);               // the target's outer wall, along `side`
                int origin = outward(all, side) + GAP;          // the gatehouse door, past everything
                int length = origin - wall;
                int lateral = side.getModX() != 0 ? (target[2] + target[5]) / 2 : (target[0] + target[3]) / 2;
                int ox = side.getModX() != 0 ? side.getModX() * origin : lateral;
                int oz = side.getModZ() != 0 ? side.getModZ() * origin : lateral;
                Plan plan = new Plan(side, target, ox, oz, target[1], length);
                if (tunnelClear(plan, pieces)) plans.add(plan);
            }
        }
        return plans;
    }

    /** Tries each plan in turn: load its ground in the background, then check it on the main thread. */
    private void tryNext(World world, GeneratedStructure stronghold, String id, Iterator<Plan> plans, Consumer<String> report) {
        if (!plans.hasNext()) {
            finish(stronghold, id, "none (no side with room underground)", report);
            return;
        }
        Plan plan = plans.next();
        int[] box = plan.wingBox();
        List<CompletableFuture<Chunk>> loads = new ArrayList<>();
        for (int cx = box[0] >> 4; cx <= box[3] >> 4; cx++) {
            for (int cz = box[2] >> 4; cz <= box[5] >> 4; cz++) loads.add(world.getChunkAtAsync(cx, cz));
        }
        CompletableFuture.allOf(loads.toArray(new CompletableFuture[0])).whenComplete((ignored, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null) {
                        logger.log(Level.WARNING, "Loading ground for a stronghold wing failed", error);
                        finish(stronghold, id, "none (couldn't load its ground)", report);
                        return;
                    }
                    if (!fitsUnderground(world, plan)) {
                        tryNext(world, stronghold, id, plans, report);
                        return;
                    }
                    build(world, stronghold, id, plan, report);
                }));
    }

    private boolean fitsUnderground(World world, Plan plan) {
        int[] box = plan.wingBox();
        if (box[1] - 1 <= world.getMinHeight() + 4) return false;
        for (int x = box[0]; x <= box[3]; x += 3) {
            for (int z = box[2]; z <= box[5]; z += 3) {
                if (world.getHighestBlockYAt(x, z, HeightMap.OCEAN_FLOOR) < box[4] + COVER) return false;
            }
        }
        return true;
    }

    private static boolean tunnelClear(Plan plan, List<int[]> pieces) {
        int[] tunnel = box(plan, -2, -plan.tunnelLength() + 1, 2, -1, plan.floorY(), plan.floorY() + 4);
        for (int[] p : pieces) {
            if (p == plan.target()) continue;
            if (overlaps(tunnel, p)) return false;
        }
        return true;
    }

    // ---- building ----

    private void build(World world, GeneratedStructure stronghold, String id, Plan plan, Consumer<String> report) {
        int[] box = plan.wingBox();
        if (playerNear(world, box)) {
            // Someone's already down there: try again in a while rather than build around them.
            Bukkit.getScheduler().runTaskLater(plugin, () -> build(world, stronghold, id, plan, report), 200L);
            return;
        }
        Canvas canvas = new Canvas(plan.originX(), plan.floorY(), plan.originZ(), plan.facing(), world.getSeed());
        List<StrongholdWing.Room> rooms = StrongholdWing.build(canvas, plan.tunnelLength());
        List<Canvas.Placement> queue = new ArrayList<>(canvas.placements().values());

        // Keep the ground loaded until the last block is in.
        List<Chunk> held = new ArrayList<>();
        for (int cx = box[0] >> 4; cx <= box[3] >> 4; cx++) {
            for (int cz = box[2] >> 4; cz <= box[5] >> 4; cz++) {
                Chunk chunk = world.getChunkAt(cx, cz);
                chunk.addPluginChunkTicket(plugin);
                held.add(chunk);
            }
        }
        int perTick = Math.max(500, config.getInt("structures.stronghold.wing.blocks-per-tick", 4000));
        int[] next = {0};
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            try {
                int end = Math.min(queue.size(), next[0] + perTick);
                for (; next[0] < end; next[0]++) place(world, queue.get(next[0]));
                if (next[0] < queue.size()) return;
                task.cancel();
                held.forEach(chunk -> chunk.removePluginChunkTicket(plugin));
                String summary = "facing " + plan.facing().name().toLowerCase() + " from " + plan.originX() + ","
                        + plan.floorY() + "," + plan.originZ() + ", " + queue.size() + " blocks, rooms "
                        + rooms.toString().toLowerCase();
                finish(stronghold, id, "built " + summary, report);
            } catch (Throwable t) {
                task.cancel();
                held.forEach(chunk -> chunk.removePluginChunkTicket(plugin));
                logger.log(Level.WARNING, "Building a stronghold wing failed", t);
                finish(stronghold, id, "failed", report);
            }
        }, 1L, 1L);
    }

    private void place(World world, Canvas.Placement p) {
        Block block = world.getBlockAt(p.x(), p.y(), p.z());
        block.setBlockData(p.data(), false);
        if (p.loot() == null && p.spawner() == null) return;
        BlockState state = block.getState();
        if (p.loot() != null && state instanceof Lootable lootable) {
            // Filled the first time it's opened, like vanilla's own stronghold chests.
            lootable.setLootTable(p.loot().getLootTable(), CoordHash.of(world.getSeed(), p.x() * 7 + p.y(), p.z()));
        }
        if (p.spawner() != null && state instanceof CreatureSpawner spawner) {
            spawner.setSpawnedType(p.spawner());
        }
        state.update(true, false);
    }

    private void finish(GeneratedStructure stronghold, String id, String outcome, Consumer<String> report) {
        stronghold.getPersistentDataContainer().set(wingKey, PersistentDataType.STRING, outcome);
        inProgress.remove(id);
        logger.info("Stronghold " + id + ": wing " + outcome + ".");
        if (report != null) report.accept(outcome);
    }

    private static boolean playerNear(World world, int[] box) {
        for (Player player : world.getPlayers()) {
            Location at = player.getLocation();
            if (at.getX() >= box[0] - 16 && at.getX() <= box[3] + 16 && at.getZ() >= box[2] - 16 && at.getZ() <= box[5] + 16
                    && at.getY() >= box[1] - 16 && at.getY() <= box[4] + 16) {
                return true;
            }
        }
        return false;
    }

    /**
     * A top-down slice of a stronghold and its wing, one character per block, {@code above}
     * blocks over the wing's floor — for checking a build without going there. Chunks that
     * aren't loaded show as '?'.
     */
    public List<String> map(World world, GeneratedStructure stronghold, int above) {
        String status = status(stronghold);
        int[] all = blocks(stronghold.getBoundingBox());
        int y = all[1] + 1;
        if (status != null && status.contains(" from ")) {
            y = Integer.parseInt(status.split(" from ")[1].split(",")[1]) + above;
        }
        int margin = StrongholdWing.LENGTH + StrongholdWing.HALF_WIDTH + GAP;
        List<String> rows = new ArrayList<>();
        rows.add("y=" + y + "  x " + (all[0] - margin) + ".." + (all[3] + margin) + "  z " + (all[2] - margin) + ".." + (all[5] + margin));
        StringBuilder listing = new StringBuilder("pieces:");
        for (StructurePiece piece : stronghold.getPieces()) {
            int[] b = blocks(piece.getBoundingBox());
            listing.append(' ').append(b[0]).append(',').append(b[1]).append(',').append(b[2])
                    .append("..").append(b[3]).append(',').append(b[4]).append(',').append(b[5]);
        }
        rows.add(listing.toString());
        for (int z = all[2] - margin; z <= all[5] + margin; z++) {
            StringBuilder row = new StringBuilder();
            for (int x = all[0] - margin; x <= all[3] + margin; x++) {
                // Only what's already loaded: generating a whole stronghold's surroundings here would stall the server.
                row.append(world.isChunkLoaded(x >> 4, z >> 4) ? symbol(world.getBlockAt(x, y, z).getType()) : '?');
            }
            rows.add(row.toString());
        }
        return rows;
    }

    private static char symbol(org.bukkit.Material m) {
        String n = m.name();
        if (m.isAir()) return ' ';
        if (n.contains("STONE_BRICK") || m == org.bukkit.Material.COBBLESTONE || m == org.bukkit.Material.MOSSY_COBBLESTONE) {
            return n.contains("STAIRS") || n.contains("SLAB") || n.contains("WALL") ? 's' : '#';
        }
        return switch (m) {
            case BOOKSHELF -> 'B';
            case CHEST, BARREL -> 'C';
            case SPAWNER -> 'S';
            case IRON_BARS -> '|';
            case LANTERN, SOUL_LANTERN -> 'L';
            case RED_CARPET, GRAY_CARPET -> '=';
            case END_PORTAL_FRAME -> 'P';
            case WATER -> '~';
            case LAVA -> '!';
            case COBWEB -> 'w';
            case OAK_DOOR, IRON_DOOR -> 'D';
            default -> m.isSolid() ? '.' : ',';
        };
    }

    // ---- geometry ----

    /**
     * A structure box as block bounds {minX, minY, minZ, maxX, maxY, maxZ}. Paper hands structure
     * and piece boxes over with vanilla's inclusive max (unlike an entity's box) — measured: read
     * as exclusive, a stronghold's 5x5x5 turns come out 4x4x4 and the 11x8x16 portal room isn't
     * recognised.
     */
    static int[] blocks(BoundingBox b) {
        return new int[]{
                (int) Math.floor(b.getMinX()), (int) Math.floor(b.getMinY()), (int) Math.floor(b.getMinZ()),
                (int) Math.floor(b.getMaxX()), (int) Math.floor(b.getMaxY()), (int) Math.floor(b.getMaxZ())
        };
    }

    /** How far a box reaches toward `side`, as a signed coordinate (larger = further out on that side). */
    private static int outward(int[] b, BlockFace side) {
        return switch (side) {
            case EAST -> b[3];
            case WEST -> -b[0];
            case SOUTH -> b[5];
            case NORTH -> -b[2];
            default -> throw new IllegalArgumentException(side.name());
        };
    }

    /** vanilla's portal room is the one 11 x 8 x 16 piece. */
    private static boolean isPortalRoom(int[] p) {
        int w = p[3] - p[0] + 1, h = p[4] - p[1] + 1, d = p[5] - p[2] + 1;
        return h == 8 && ((w == 11 && d == 16) || (w == 16 && d == 11));
    }

    /** World bounds of a rectangle given in the plan's wing frame (x lateral, z forward). */
    private static int[] box(Plan plan, int x1, int z1, int x2, int z2, int y1, int y2) {
        BlockFace f = plan.facing();
        BlockFace r = Canvas.clockwise(f);
        int ax = plan.originX() + r.getModX() * x1 + f.getModX() * z1;
        int az = plan.originZ() + r.getModZ() * x1 + f.getModZ() * z1;
        int bx = plan.originX() + r.getModX() * x2 + f.getModX() * z2;
        int bz = plan.originZ() + r.getModZ() * x2 + f.getModZ() * z2;
        return new int[]{Math.min(ax, bx), y1, Math.min(az, bz), Math.max(ax, bx), y2, Math.max(az, bz)};
    }

    private static boolean overlaps(int[] a, int[] b) {
        return a[0] <= b[3] && a[3] >= b[0] && a[1] <= b[4] && a[4] >= b[1] && a[2] <= b[5] && a[5] >= b[2];
    }

    private static String idOf(World world, BoundingBox b) {
        return world.getName() + "@" + (int) b.getMinX() + "," + (int) b.getMinY() + "," + (int) b.getMinZ();
    }
}

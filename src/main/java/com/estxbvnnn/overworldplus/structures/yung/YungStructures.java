package com.estxbvnnn.overworldplus.structures.yung;

import com.estxbvnnn.overworldplus.CoordHash;
import com.estxbvnnn.overworldplus.structures.yung.JigsawAssembler.Placed;
import com.estxbvnnn.overworldplus.structures.yung.JigsawLibrary.Marker;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.AsyncStructureGenerateEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.generator.structure.GeneratedStructure;
import org.bukkit.generator.structure.Structure;
import org.bukkit.generator.structure.StructurePiece;
import org.bukkit.loot.LootTable;
import org.bukkit.loot.LootTables;
import org.bukkit.loot.Lootable;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.BoundingBox;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * YUNG's structure overhauls (Better Strongholds, Jungle Temples, Witch Huts, Ocean Monuments —
 * all LGPL-3.0, by YUNGNICKYOUNG), rebuilt on Paper from the mods' own data.
 *
 * Vanilla still decides where each structure goes, so /locate, explorer maps, the eye of ender
 * and the structure's mob spawning all keep working. While a replaced vanilla structure
 * generates, its blocks are dropped ({@link AsyncStructureGenerateEvent}) — it stays registered,
 * invisible — and the first time its chunk loads, the YUNG version is laid out on the same spot
 * ({@link JigsawAssembler}) and placed a batch per tick with YUNG's processors
 * ({@link Processing}). Strongholds that generated before this existed get their vanilla rooms
 * filled back in first (one stronghold, one portal); other old structures are left as they are.
 * Better Witch Huts' stone circles (a structure of its own) are planned per region in swamps.
 */
public final class YungStructures implements Listener {

    enum Height { STRONGHOLD, SURFACE, ABSOLUTE }

    /** One overhaul: which vanilla structure it replaces and how YUNG's structure JSON places it. */
    record Kind(String key, JigsawLibrary library, Set<Structure> replaces, JigsawAssembler.Spec spec, Height height,
                int minOffset, int maxOffset, boolean underground, LootTables fallbackLoot) {}

    private final JavaPlugin plugin;
    private final FileConfiguration config;
    private final Logger logger;
    private final File dataFolder;
    private final List<Kind> kinds = new ArrayList<>();
    private final Kind circles;
    private final NamespacedKey circleDone;
    private final Set<String> inProgress = new HashSet<>();
    private final Set<String> suppressed = ConcurrentHashMap.newKeySet();
    private volatile boolean suppressedDirty;
    private final Map<String, org.bukkit.structure.Structure> loaded = new HashMap<>();
    private final Map<String, List<Processing.Step>> pipelines = new HashMap<>();

    public YungStructures(JavaPlugin plugin, File pluginJar, FileConfiguration config, Logger logger) {
        this.plugin = plugin;
        this.config = config;
        this.logger = logger;
        this.dataFolder = plugin.getDataFolder();
        this.circleDone = new NamespacedKey(plugin, "witch_circle");

        JigsawLibrary strongholds = new JigsawLibrary(pluginJar, "betterstrongholds", logger);
        JigsawLibrary jungle = new JigsawLibrary(pluginJar, "betterjungletemples", logger);
        JigsawLibrary witch = new JigsawLibrary(pluginJar, "betterwitchhuts", logger);
        JigsawLibrary monuments = new JigsawLibrary(pluginJar, "betteroceanmonuments", logger);

        // Straight from each mod's worldgen/structure JSON: start pool, anchor, size, reach, start height.
        kinds.add(new Kind("stronghold", strongholds, Set.of(Structure.STRONGHOLD),
                new JigsawAssembler.Spec("betterstrongholds:starts", "betterstrongholds:stronghold_anchor", 15, 116, -58, 60, "portal_room"),
                Height.STRONGHOLD, -30, 11, true, LootTables.STRONGHOLD_CORRIDOR));
        kinds.add(new Kind("jungle-temple", jungle, Set.of(Structure.JUNGLE_PYRAMID),
                new JigsawAssembler.Spec("betterjungletemples:starts", "betterjungletemples:anchor", 10, 128, -58, 319, null),
                Height.SURFACE, -30, -25, false, LootTables.JUNGLE_TEMPLE));
        kinds.add(new Kind("witch-hut", witch, Set.of(Structure.SWAMP_HUT),
                new JigsawAssembler.Spec("betterwitchhuts:starts", null, 20, 80, -58, 319, null),
                Height.SURFACE, 1, 1, false, LootTables.SIMPLE_DUNGEON));
        kinds.add(new Kind("ocean-monument", monuments, Set.of(Structure.MONUMENT),
                new JigsawAssembler.Spec("betteroceanmonuments:starts", "betteroceanmonuments:anchor", 20, 80, -58, 319, null),
                Height.ABSOLUTE, 54, 64, false, LootTables.UNDERWATER_RUIN_BIG));
        circles = new Kind("witch-circle", witch, Set.of(),
                new JigsawAssembler.Spec("betterwitchhuts:circles", null, 20, 80, -58, 319, null),
                Height.SURFACE, 0, 0, false, LootTables.SIMPLE_DUNGEON);

        for (Kind kind : kinds) {
            logger.info("YUNG " + kind.key() + " (LGPL-3.0): " + kind.library().summary() + (enabled(kind) ? "" : " — disabled in config"));
        }
        loadSuppressed();
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::saveSuppressedIfDirty, 100L, 100L);
    }

    private boolean enabled(Kind kind) {
        if (!kind.library().isLoaded()) return false;
        if (kind.key().equals("stronghold")) {
            return "better".equalsIgnoreCase(config.getString("structures.stronghold.mode", "better"));
        }
        return config.getBoolean("structures.yung." + kind.key() + ".enabled", true);
    }

    public boolean strongholdsEnabled() {
        return enabled(kinds.get(0));
    }

    // ---- generation: vanilla's blocks never go down for a replaced structure ----

    @EventHandler(priority = EventPriority.HIGH)
    public void onStructureGenerate(AsyncStructureGenerateEvent event) {
        if (event.getWorld().getEnvironment() != World.Environment.NORMAL) return;
        for (Kind kind : kinds) {
            if (!kind.replaces().contains(event.getStructure()) || !enabled(kind)) continue;
            NamespacedKey key = new NamespacedKey(plugin, "yung_" + kind.key().replace('-', '_'));
            // Keep whatever the world has — vanilla's pieces stay registered but place nothing.
            event.setBlockTransformer(key, (region, x, y, z, current, state) -> state.getWorld());
            event.setEntityTransformer(key, (region, x, y, z, entity, allowed) -> false);
            if (suppressed.add(id(event.getWorld(), event.getBoundingBox()))) suppressedDirty = true;
            return;
        }
    }

    // ---- the load path: lay the YUNG structure out where the vanilla one is ----

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        Chunk chunk = event.getChunk();
        World world = chunk.getWorld();
        if (world.getEnvironment() != World.Environment.NORMAL) return;
        for (Kind kind : kinds) {
            if (!enabled(kind)) continue;
            for (Structure type : kind.replaces()) {
                for (GeneratedStructure s : chunk.getStructures(type)) consider(kind, world, s, null);
            }
        }
        if (config.getBoolean("structures.yung.witch-circle.enabled", true) && circles.library().isLoaded()) {
            maybeCircle(chunk, event.isNewChunk());
        }
    }

    /** Starts the YUNG version of this structure unless it has one (or is getting one). {@code report} hears the outcome. */
    public void consider(Kind kind, World world, GeneratedStructure structure, Consumer<String> report) {
        NamespacedKey statusKey = new NamespacedKey(plugin, "yung_" + kind.key().replace('-', '_'));
        String existing = structure.getPersistentDataContainer().get(statusKey, PersistentDataType.STRING);
        // The first version of this feature used this key for strongholds; honour what it recorded.
        if (existing == null && kind.key().equals("stronghold")) {
            existing = structure.getPersistentDataContainer().get(new NamespacedKey(plugin, "better_stronghold"), PersistentDataType.STRING);
        }
        // A failed build is tried again (the vanilla structure is gone, so something has to stand there).
        if (existing != null && !existing.startsWith("failed")) {
            if (report != null) report.accept("Already done: " + existing);
            return;
        }
        String id = id(world, structure.getBoundingBox());
        Consumer<String> done = outcome -> {
            structure.getPersistentDataContainer().set(statusKey, PersistentDataType.STRING, outcome);
            inProgress.remove(id);
            logger.info(kind.key() + " " + id + ": " + outcome + ".");
            if (report != null) report.accept(outcome);
        };

        boolean wasSuppressed = suppressed.contains(id);
        List<int[]> vanilla = new ArrayList<>();
        for (StructurePiece piece : structure.getPieces()) vanilla.add(blocks(piece.getBoundingBox()));
        if (kind.key().equals("stronghold")) {
            // A stronghold that got the earlier "wing" expansion keeps it.
            String wing = structure.getPersistentDataContainer().get(new NamespacedKey(plugin, "stronghold_wing"), PersistentDataType.STRING);
            if (wing != null && wing.startsWith("built")) {
                done.accept("kept as vanilla + wing (it already had its wing)");
                return;
            }
        } else if (!wasSuppressed) {
            // Generated before this replaced it: vanilla's blocks are there and may well have been visited.
            done.accept("kept vanilla (generated before the YUNG version was installed)");
            return;
        }
        if (!inProgress.add(id)) {
            if (report != null) report.accept("Already being built.");
            return;
        }

        // Vanilla's first piece is its start (a stronghold's staircase, where the eye of ender leads).
        int[] start = vanilla.isEmpty() ? blocks(structure.getBoundingBox()) : vanilla.get(0);
        int[] whole = blocks(structure.getBoundingBox());
        int[] centre = kind.key().equals("stronghold") ? start : whole;
        int ax = (centre[0] + centre[3]) / 2, az = (centre[2] + centre[5]) / 2;
        List<int[]> refill = kind.key().equals("stronghold") && !wasSuppressed ? vanilla : List.of();
        prepare(kind, world, ax, az, refill, done);
    }

    /** Loads (and holds) everything the structure could reach, then lays it out and builds it. */
    private void prepare(Kind kind, World world, int ax, int az, List<int[]> refill, Consumer<String> done) {
        int reach = kind.spec().maxDistance() + 8;
        List<CompletableFuture<Chunk>> loads = new ArrayList<>();
        List<Chunk> held = new ArrayList<>();
        for (int cx = (ax - reach) >> 4; cx <= (ax + reach) >> 4; cx++) {
            for (int cz = (az - reach) >> 4; cz <= (az + reach) >> 4; cz++) {
                loads.add(world.getChunkAtAsync(cx, cz).thenApply(chunk -> {
                    // An async load alone keeps a chunk only briefly; the layout reads the ground across all of them.
                    chunk.addPluginChunkTicket(plugin);
                    synchronized (held) {
                        held.add(chunk);
                    }
                    return chunk;
                }));
            }
        }
        CompletableFuture.allOf(loads.toArray(new CompletableFuture[0])).whenComplete((ignored, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null) {
                        logger.log(Level.WARNING, "Loading the ground for a " + kind.key() + " failed", error);
                        release(held);
                        done.accept("failed (couldn't load its surroundings)");
                        return;
                    }
                    try {
                        List<Placed> layout = layout(kind, world, ax, az);
                        if (layout == null) {
                            release(held);
                            done.accept("none (no layout fits here)");
                            return;
                        }
                        build(kind, world, layout, refill, held, done);
                    } catch (Throwable t) {
                        logger.log(Level.WARNING, "Laying out a " + kind.key() + " failed", t);
                        release(held);
                        done.accept("failed");
                    }
                }));
    }

    private List<Placed> layout(Kind kind, World world, int ax, int az) {
        JigsawAssembler.Ground ground = kind.underground() ? (x1, z1, x2, z2) -> ceiling(world, x1, z1, x2, z2) : null;
        int span = kind.maxOffset() - kind.minOffset() + 1;
        int roll = kind.minOffset() + CoordHash.pick(world.getSeed(), ax, az, Math.max(1, span));
        int ay = switch (kind.height()) {
            case SURFACE -> groundY(world, ax, az) + roll;
            case ABSOLUTE -> roll;
            case STRONGHOLD -> Math.max(world.getMinHeight() + 20,
                    Math.min(roll, ceiling(world, ax - 16, az - 16, ax + 16, az + 16) - 16)); // entrance junction's roof: anchor + 15
        };
        long seed = world.getSeed() ^ (ax * 341873128712L + az * 132897987541L) ^ kind.key().hashCode();
        boolean bottom = kind.height() == Height.SURFACE;
        List<Placed> layout = new JigsawAssembler(kind.library(), kind.spec(), ground).assemble(seed, ax, ay, az, bottom);
        if (layout == null && ground != null) {
            // Nowhere fully underground (thin ground over a cave system, an ocean...): better a stronghold
            // that shows a corner than none at all — the vanilla one is already gone.
            layout = new JigsawAssembler(kind.library(), kind.spec(), null).assemble(seed, ax, ay, az, bottom);
        }
        return layout;
    }

    /**
     * The surface YUNG places on (the generation-time WORLD_SURFACE): down from the top past
     * plants, leaves and logs — which weren't there yet when the game computed it — to the first
     * solid block or water. A swamp hut stands on the water, not on the swamp's floor.
     */
    static int groundY(World world, int x, int z) {
        int y = world.getHighestBlockYAt(x, z, HeightMap.WORLD_SURFACE);
        while (y > world.getMinHeight()) {
            org.bukkit.block.Block block = world.getBlockAt(x, y, z);
            Material m = block.getType();
            String n = m.name();
            if (block.isLiquid() || (m.isSolid() && !n.endsWith("_LEAVES") && !n.endsWith("_LOG") && !n.endsWith("_WOOD"))) return y;
            y--;
        }
        return y;
    }

    /** Lowest ground (ocean floor counts) over a rectangle, less a few blocks of cover, capped at 60. */
    private static int ceiling(World world, int minX, int minZ, int maxX, int maxZ) {
        int lowest = Integer.MAX_VALUE;
        for (int x = minX; x <= maxX + 5; x += 6) {
            for (int z = minZ; z <= maxZ + 5; z += 6) {
                int cx = Math.min(x, maxX), cz = Math.min(z, maxZ);
                if (!world.isChunkLoaded(cx >> 4, cz >> 4)) return Integer.MIN_VALUE;
                lowest = Math.min(lowest, world.getHighestBlockYAt(cx, cz, HeightMap.OCEAN_FLOOR));
            }
        }
        return Math.min(60, lowest - 4);
    }

    // ---- building ----

    /** One unit of building work; {@link #run} does as much as it can before the deadline and says whether it's finished. */
    private interface Job {
        boolean run(long deadline);
    }

    private void build(Kind kind, World world, List<Placed> layout, List<int[]> refill, List<Chunk> held, Consumer<String> done) {
        int[] bounds = union(layout);
        if (playerNear(world, bounds)) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> build(kind, world, layout, refill, held, done), 200L);
            return;
        }
        List<Job> jobs = new ArrayList<>();
        for (int[] box : refill) jobs.add(deadline -> fillIn(world, box));
        for (Placed piece : layout) jobs.add(new PieceJob(kind, world, piece));
        jobs.add(deadline -> {
            layout.forEach(piece -> finishMarkers(kind, world, piece));
            return true;
        });

        // A time budget, not a block count: a monument's start piece alone is hundreds of
        // thousands of blocks, so a piece is spread over as many ticks as it needs.
        long budgetNanos = Math.max(2, config.getInt("structures.yung.ms-per-tick", 15)) * 1_000_000L;
        int[] next = {0};
        long started = System.currentTimeMillis();
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            try {
                long deadline = System.nanoTime() + budgetNanos;
                while (next[0] < jobs.size() && System.nanoTime() < deadline) {
                    if (!jobs.get(next[0]).run(deadline)) return;
                    next[0]++;
                }
                if (next[0] < jobs.size()) return;
                task.cancel();
                release(held);
                int[] first = layout.get(0).box();
                done.accept("built: " + layout.size() + " pieces from " + first[0] + "," + first[1] + "," + first[2]
                        + " (" + (System.currentTimeMillis() - started) + " ms)");
            } catch (Throwable t) {
                task.cancel();
                release(held);
                logger.log(Level.WARNING, "Building a " + kind.key() + " failed", t);
                done.accept("failed");
            }
        }, 1L, 1L);
    }

    /** Vanilla's old stronghold rooms go back to solid rock, so there's one stronghold and one portal. */
    private static boolean fillIn(World world, int[] box) {
        for (int x = box[0]; x <= box[3]; x++) {
            for (int z = box[2]; z <= box[5]; z++) {
                if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
                for (int y = box[1]; y <= box[4]; y++) {
                    world.getBlockAt(x, y, z).setType(y < 0 ? Material.DEEPSLATE : Material.STONE, false);
                }
            }
        }
        return true;
    }

    /**
     * Places one piece block by block from its template's palette — each block turned with the
     * piece, run through its processors, and set with its block entity data (loot table, spawner,
     * banner...) — then its entities. The server's own template placer would do the turning, but
     * with processors attached it only accepts pieces within 16 blocks of their origin chunk,
     * which YUNG's big rooms (and every monument) overrun.
     */
    private final class PieceJob implements Job {
        private final Kind kind;
        private final World world;
        private final Placed piece;
        private List<BlockState> blocks;
        private List<Processing.Step> steps;
        private Map<Long, BlockData> jigsaws;
        private StructureRotation rotation;
        private int index;

        PieceJob(Kind kind, World world, Placed piece) {
            this.kind = kind;
            this.world = world;
            this.piece = piece;
        }

        @Override
        public boolean run(long deadline) {
            if (blocks == null) prepare();
            int seaLevel = world.getSeaLevel();
            while (index < blocks.size()) {
                placeBlock(blocks.get(index++), seaLevel);
                if ((index & 63) == 0 && System.nanoTime() > deadline) return false;
            }
            spawnEntities();
            return true;
        }

        private void prepare() {
            org.bukkit.structure.Structure structure = template(piece);
            blocks = structure.getPaletteCount() == 0 ? List.of() : structure.getPalettes().get(0).getBlocks();
            steps = pipeline(kind, piece.processors());
            rotation = rotation(piece.rotation());
            jigsaws = new HashMap<>();
            for (JigsawLibrary.Jigsaw j : piece.template().jigsaws()) {
                int[] p = JigsawAssembler.worldPos(piece, j.x(), j.y(), j.z());
                BlockData finalState;
                try {
                    finalState = Bukkit.createBlockData(j.finalState() == null ? "minecraft:air" : j.finalState());
                } catch (IllegalArgumentException e) {
                    finalState = Material.AIR.createBlockData();
                }
                jigsaws.put(key(p[0], p[1], p[2]), finalState);
            }
        }

        private void placeBlock(BlockState template, int seaLevel) {
            int[] w = JigsawAssembler.worldPos(piece, template.getX(), template.getY(), template.getZ());
            if (w[1] < world.getMinHeight() || w[1] >= world.getMaxHeight()) return;
            org.bukkit.block.Block target = world.getBlockAt(w[0], w[1], w[2]);
            BlockData jigsaw = jigsaws.get(key(w[0], w[1], w[2]));
            if (jigsaw != null) {
                target.setBlockData(jigsaw, false);
                return;
            }
            BlockData data = template.getBlockData().clone();
            data.rotate(rotation);
            BlockState current = template.copy(target.getLocation());
            current.setBlockData(data);
            Processing.Ctx ctx = new Processing.Ctx(w[0], w[1], w[2], target, seaLevel);
            for (Processing.Step step : steps) current = step.block(ctx, current);
            if (current instanceof Lootable lootable) fallBackLoot(lootable, kind.fallbackLoot());
            // Processors hand back fresh states for replaced blocks; give those this position first.
            Location at = current.getLocation();
            if (at == null || at.getWorld() == null || at.getBlockX() != w[0] || at.getBlockY() != w[1] || at.getBlockZ() != w[2]) {
                current = current.copy(target.getLocation());
            }
            current.update(true, false);
        }

        /** Entities go where the game would put them: positions turned about the piece's corner, facing turned with it. */
        private void spawnEntities() {
            for (org.bukkit.entity.Entity template : template(piece).getEntities()) {
                Location local = template.getLocation();
                double x = local.getX(), z = local.getZ();
                double wx, wz;
                switch (piece.rotation() & 3) {
                    case 1 -> { wx = 1 - z; wz = x; }
                    case 2 -> { wx = 1 - x; wz = 1 - z; }
                    case 3 -> { wx = z; wz = 1 - x; }
                    default -> { wx = x; wz = z; }
                }
                Location at = new Location(world, piece.x() + wx, piece.y() + local.getY(), piece.z() + wz,
                        local.getYaw() + 90f * (piece.rotation() & 3), local.getPitch());
                org.bukkit.entity.Entity spawned;
                try {
                    spawned = template.copy(at);
                } catch (IllegalArgumentException e) {
                    continue;
                }
                if (spawned instanceof org.bukkit.entity.Hanging hanging) {
                    hanging.setFacingDirection(JigsawAssembler.rotate(hanging.getFacing(), piece.rotation()), true);
                }
                for (Processing.Step step : steps) {
                    if (!step.entity(at.getBlockX(), at.getBlockY(), at.getBlockZ(), spawned)) {
                        spawned.remove();
                        break;
                    }
                }
            }
        }
    }

    private org.bukkit.structure.Structure template(Placed piece) {
        return loaded.computeIfAbsent(piece.template().id(), idKey -> {
            try {
                return Bukkit.getStructureManager().loadStructure(new ByteArrayInputStream(piece.template().bytes()));
            } catch (IOException e) {
                throw new IllegalStateException("Can't load piece " + idKey, e);
            }
        });
    }

    private List<Processing.Step> pipeline(Kind kind, String processorList) {
        String key = kind.library().namespace + "|" + processorList;
        return pipelines.computeIfAbsent(key, k -> Processing.steps(kind.library().processorList(processorList)));
    }

    private void finishMarkers(Kind kind, World world, Placed piece) {
        List<Processing.Step> steps = pipeline(kind, piece.processors());
        for (Marker m : piece.template().markers()) {
            int[] w = JigsawAssembler.worldPos(piece, m.x(), m.y(), m.z());
            for (Processing.Step step : steps) step.after(world, w[0], w[1], w[2], m.type());
        }
    }

    /** Until the loot datapack is loaded (from the next server start), YUNG's chests use the matching vanilla loot. */
    private static void fallBackLoot(Lootable lootable, LootTables fallback) {
        LootTable table = lootable.getLootTable();
        if (table == null || "minecraft".equals(table.getKey().getNamespace())) return;
        if (Bukkit.getLootTable(table.getKey()) != null) return;
        String name = table.getKey().getKey();
        LootTables vanilla = fallback;
        if (fallback == LootTables.STRONGHOLD_CORRIDOR) {
            vanilla = name.contains("library") ? LootTables.STRONGHOLD_LIBRARY
                    : name.contains("armoury") || name.contains("mess") || name.contains("treasure") ? LootTables.STRONGHOLD_CROSSING
                    : name.contains("crypt") ? LootTables.SIMPLE_DUNGEON : LootTables.STRONGHOLD_CORRIDOR;
        }
        lootable.setLootTable(vanilla.getLootTable(), lootable.getSeed());
    }

    // ---- witch circles: a structure of their own, one candidate per 40x40-chunk region in swamps ----

    private static final int CIRCLE_SPACING = 40, CIRCLE_SEPARATION = 10;
    private static final Set<Biome> CIRCLE_BIOMES = Set.of(Biome.SWAMP, Biome.MANGROVE_SWAMP);

    private void maybeCircle(Chunk chunk, boolean newChunk) {
        World world = chunk.getWorld();
        int regionX = Math.floorDiv(chunk.getX(), CIRCLE_SPACING), regionZ = Math.floorDiv(chunk.getZ(), CIRCLE_SPACING);
        int span = CIRCLE_SPACING - CIRCLE_SEPARATION;
        long salt = world.getSeed() ^ 19797254L;
        int cx = regionX * CIRCLE_SPACING + CoordHash.pick(salt, regionX, regionZ, span);
        int cz = regionZ * CIRCLE_SPACING + CoordHash.pick(salt, regionZ, regionX, span);
        if (chunk.getX() != cx || chunk.getZ() != cz) return;
        if (chunk.getPersistentDataContainer().has(circleDone, PersistentDataType.BYTE)) return;
        int x = (cx << 4) + 8, z = (cz << 4) + 8;
        chunk.getPersistentDataContainer().set(circleDone, PersistentDataType.BYTE, (byte) 1);
        // Only new ground: a circle appearing in a swamp someone already knows would be jarring.
        if (!newChunk || !CIRCLE_BIOMES.contains(world.getBiome(x, groundY(world, x, z), z))) return;
        String id = world.getName() + "@circle" + cx + "," + cz;
        if (!inProgress.add(id)) return;
        prepare(circles, world, x, z, List.of(), outcome -> {
            inProgress.remove(id);
            logger.info("witch-circle " + id + ": " + outcome + ".");
        });
    }

    // ---- loot tables ----

    /** Writes all four mods' chest loot tables as one datapack in a world folder (loaded from the next start). */
    public void installLootDatapack(File worldFolder) {
        File pack = new File(worldFolder, "datapacks/overworldplus_yung");
        boolean wrote = false;
        try {
            Set<JigsawLibrary> libraries = new HashSet<>();
            for (Kind kind : kinds) libraries.add(kind.library());
            for (JigsawLibrary library : libraries) {
                for (Map.Entry<String, byte[]> table : library.lootTables().entrySet()) {
                    File out = new File(pack, "data/" + library.namespace + "/" + table.getKey());
                    if (out.exists()) continue;
                    out.getParentFile().mkdirs();
                    Files.write(out.toPath(), table.getValue());
                    wrote = true;
                }
            }
            File meta = new File(pack, "pack.mcmeta");
            if (!meta.exists()) {
                Files.writeString(meta.toPath(), "{\"pack\": {\"description\": \"YUNG's structure loot tables (LGPL-3.0), "
                        + "installed by OverworldPlus\", \"min_format\": 101, \"max_format\": 101}}");
                wrote = true;
            }
        } catch (IOException e) {
            logger.warning("Couldn't install the YUNG loot datapack: " + e.getMessage());
            return;
        }
        if (wrote) {
            logger.info("Installed YUNG's chest loot tables as a datapack in " + pack.getParentFile().getParentFile().getName()
                    + " — they load from the next server start (until then those chests use the matching vanilla loot).");
        }
    }

    // ---- bookkeeping ----

    private void loadSuppressed() {
        File file = new File(dataFolder, "yung_structures.txt");
        if (!file.exists()) return;
        try {
            suppressed.addAll(Files.readAllLines(file.toPath()));
        } catch (IOException e) {
            logger.warning("Couldn't read yung_structures.txt: " + e.getMessage());
        }
    }

    public void saveSuppressedIfDirty() {
        if (!suppressedDirty) return;
        suppressedDirty = false;
        try {
            dataFolder.mkdirs();
            Files.write(new File(dataFolder, "yung_structures.txt").toPath(), new ArrayList<>(suppressed));
        } catch (IOException e) {
            suppressedDirty = true;
            logger.warning("Couldn't save yung_structures.txt: " + e.getMessage());
        }
    }

    /** The stronghold kind, for the admin command. */
    public void considerStronghold(World world, GeneratedStructure structure, Consumer<String> report) {
        consider(kinds.get(0), world, structure, report);
    }

    private void release(List<Chunk> held) {
        synchronized (held) {
            held.forEach(chunk -> chunk.removePluginChunkTicket(plugin));
            held.clear();
        }
    }

    private static boolean playerNear(World world, int[] b) {
        for (Player player : world.getPlayers()) {
            Location at = player.getLocation();
            if (at.getX() >= b[0] - 16 && at.getX() <= b[3] + 16 && at.getZ() >= b[2] - 16 && at.getZ() <= b[5] + 16
                    && at.getY() >= b[1] - 32 && at.getY() <= b[4] + 32) {
                return true;
            }
        }
        return false;
    }

    private static int[] union(List<Placed> layout) {
        int[] u = layout.get(0).box().clone();
        for (Placed p : layout) {
            for (int i = 0; i < 3; i++) u[i] = Math.min(u[i], p.box()[i]);
            for (int i = 3; i < 6; i++) u[i] = Math.max(u[i], p.box()[i]);
        }
        return u;
    }

    /** Structure boxes come with vanilla's inclusive max. */
    static int[] blocks(BoundingBox b) {
        return new int[]{(int) Math.floor(b.getMinX()), (int) Math.floor(b.getMinY()), (int) Math.floor(b.getMinZ()),
                (int) Math.floor(b.getMaxX()), (int) Math.floor(b.getMaxY()), (int) Math.floor(b.getMaxZ())};
    }

    private static String id(World world, BoundingBox b) {
        return world.getName() + "@" + (int) Math.floor(b.getMinX()) + "," + (int) Math.floor(b.getMinY()) + "," + (int) Math.floor(b.getMinZ());
    }

    private static StructureRotation rotation(int quarterTurns) {
        return switch (quarterTurns & 3) {
            case 1 -> StructureRotation.CLOCKWISE_90;
            case 2 -> StructureRotation.CLOCKWISE_180;
            case 3 -> StructureRotation.COUNTERCLOCKWISE_90;
            default -> StructureRotation.NONE;
        };
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }
}

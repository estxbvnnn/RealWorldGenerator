package com.estxbvnnn.overworldplus;

import com.estxbvnnn.overworldplus.structures.StructureEnhancer;
import com.estxbvnnn.overworldplus.structures.VineCleanup;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.WorldInitEvent;
import org.bukkit.generator.structure.GeneratedStructure;
import org.bukkit.util.BoundingBox;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The two ways a chunk gets enhanced meet here.
 *
 * New chunks are handled at generation time by {@link OverworldPopulator},
 * registered on {@link WorldInitEvent} (this plugin loads at STARTUP so
 * that fires for every world, spawn chunks included). When such a chunk
 * then loads it's already finished; it's just marked as done.
 *
 * Chunks that predate the plugin get the same pipeline on the load path:
 * queued, drained a few per tick, and only once all eight neighbors are
 * loaded (a canopy pasted at an edge reaches two chunks over, and writing
 * into an unloaded chunk would force a synchronous load — a lag spike that
 * can cascade at the exploration frontier). Every chunk load also re-queues
 * its loaded, still-waiting neighbors, so a waiting chunk gets its turn the
 * moment its ring fills in. This is the only path where a player can see
 * the change happen, and only on a world explored before the plugin.
 *
 * Structure decoration (needs a loaded Chunk's structure registry) and the
 * one-off vine cleanup for chunks touched by older versions both ride the
 * same queue.
 */
public class ChunkEnhanceListener implements Listener {

    private final FileConfiguration config;
    private final ProcessedChunkTracker tracker;
    private final ChunkWorkQueue workQueue;
    private final EnhancementPipeline pipeline;
    private final OverworldPopulator populator;
    private final PopulatedChunkLedger ledger;
    private final Logger logger;

    public ChunkEnhanceListener(FileConfiguration config, ProcessedChunkTracker tracker, ChunkWorkQueue workQueue,
                                 EnhancementPipeline pipeline, OverworldPopulator populator,
                                 PopulatedChunkLedger ledger, Logger logger) {
        this.config = config;
        this.tracker = tracker;
        this.workQueue = workQueue;
        this.pipeline = pipeline;
        this.populator = populator;
        this.ledger = ledger;
        this.logger = logger;
    }

    @EventHandler
    public void onWorldInit(WorldInitEvent event) {
        attachPopulator(event.getWorld());
    }

    /** Idempotent: safe for worlds that already existed when the plugin enabled (e.g. after /reload). */
    public void attachPopulator(World world) {
        if (world.getEnvironment() != World.Environment.NORMAL) return;
        if (!world.getPopulators().contains(populator)) {
            world.getPopulators().add(populator);
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        World world = event.getWorld();
        if (world.getEnvironment() != World.Environment.NORMAL) return;
        Chunk chunk = event.getChunk();

        // The populator already did the work while this chunk was being generated. (Not
        // isNewChunk(): that's false for a chunk generated earlier, e.g. as the border of a
        // pre-generation, and only fully loaded now — and re-enhancing a finished chunk is
        // exactly what must never happen.)
        if (ledger.consume(world.getUID(), chunk.getX(), chunk.getZ())) {
            tracker.markEnhanced(chunk);
            tracker.markCleanupDone(chunk);
        }

        if (!tracker.isEnhanced(chunk)) {
            workQueue.enqueue(chunk, ChunkWorkQueue.Kind.ENHANCE);
        } else if (!tracker.isCleanupDone(chunk)) {
            workQueue.enqueue(chunk, ChunkWorkQueue.Kind.CLEANUP);
        }
        if (!tracker.isStructuresDone(chunk)) {
            workQueue.enqueue(chunk, ChunkWorkQueue.Kind.STRUCTURES);
        }

        // This load may be the last missing neighbor of a chunk that's been waiting on it.
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                int cx = chunk.getX() + dx, cz = chunk.getZ() + dz;
                if (!world.isChunkLoaded(cx, cz)) continue;
                Chunk neighbor = world.getChunkAt(cx, cz);
                if (!tracker.isEnhanced(neighbor)) workQueue.enqueue(neighbor, ChunkWorkQueue.Kind.ENHANCE);
            }
        }
    }

    /** Called from the queue drain task — never directly from the event. */
    public void process(Chunk chunk, ChunkWorkQueue.Kind kind) {
        World world = chunk.getWorld();
        try {
            switch (kind) {
                case ENHANCE -> enhanceOldChunk(world, chunk);
                case STRUCTURES -> {
                    if (tracker.isStructuresDone(chunk)) return;
                    if (config.getBoolean("structures.enabled", true)) StructureEnhancer.enhance(chunk, config);
                    tracker.markStructuresDone(chunk);
                }
                case CLEANUP -> {
                    if (tracker.isCleanupDone(chunk)) return;
                    VineCleanup.sweepChunk(world, chunk);
                    tracker.markCleanupDone(chunk);
                }
            }
        } catch (Throwable t) {
            // Catches Throwable, not just Exception, on purpose: a NoClassDefFoundError or similar
            // Error is NOT an Exception, and this runs inside a repeating scheduler task — if one
            // of those escapes uncaught, Paper cancels that task permanently, silently killing
            // enhancement for the rest of the server session. One bad chunk must never do that.
            logger.log(Level.WARNING, "Failed " + kind + " on chunk " + chunk.getX() + "," + chunk.getZ(), t);
            if (kind == ChunkWorkQueue.Kind.ENHANCE) tracker.markEnhanced(chunk);
        }
    }

    private void enhanceOldChunk(World world, Chunk chunk) {
        if (tracker.isEnhanced(chunk)) return;
        if (!allNeighborsLoaded(world, chunk)) return; // not marked — re-queued when the missing neighbor loads

        List<BoundingBox> structures = new ArrayList<>();
        for (GeneratedStructure generated : chunk.getStructures()) {
            structures.add(generated.getBoundingBox());
        }
        // Removal may only reach into neighbors that haven't been finished yet; a finished
        // neighbor's trees (possibly from an older version) are never touched.
        boolean[][] removable = new boolean[3][3];
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                removable[dx + 1][dz + 1] = (dx == 0 && dz == 0)
                        || !tracker.isEnhanced(world.getChunkAt(chunk.getX() + dx, chunk.getZ() + dz));
            }
        }
        Area area = Area.of(world, structures, (cx, cz) -> {
            int ix = cx - chunk.getX() + 1, iz = cz - chunk.getZ() + 1;
            return ix >= 0 && ix < 3 && iz >= 0 && iz < 3 && removable[ix][iz];
        });
        pipeline.run(area, chunk.getX(), chunk.getZ());
        tracker.markEnhanced(chunk);
        tracker.markCleanupDone(chunk);
    }

    private static boolean allNeighborsLoaded(World world, Chunk chunk) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (!world.isChunkLoaded(chunk.getX() + dx, chunk.getZ() + dz)) return false;
            }
        }
        return true;
    }

    public String statsLine() {
        return pipeline.statsLine(workQueue.size());
    }

    public int chunksEnhanced() {
        return pipeline.chunksEnhanced();
    }

    /** With nobody online there's nobody to lag, so old chunks can be caught up much faster. */
    public int drainBudget() {
        boolean idle = Bukkit.getOnlinePlayers().isEmpty();
        return Math.max(1, config.getInt(idle ? "performance.idle-chunks-per-tick" : "performance.chunks-per-tick", idle ? 20 : 1));
    }
}

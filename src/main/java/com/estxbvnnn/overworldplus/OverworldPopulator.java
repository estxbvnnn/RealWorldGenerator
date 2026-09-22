package com.estxbvnnn.overworldplus;

import org.bukkit.World;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.LimitedRegion;
import org.bukkit.generator.WorldInfo;

import java.util.Random;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The generation-time path: runs the full pipeline on every brand-new chunk
 * while it's still being built, before the server ever sends it to a
 * client. Nobody sees a vanilla tree first and a big one a second later —
 * the chunk simply arrives finished. Runs on Paper's generation worker
 * threads, inside a region that covers the chunk plus a buffer around it,
 * which is what lets a canopy at a chunk edge reach into its neighbors.
 */
public final class OverworldPopulator extends BlockPopulator {

    /** A real canopy reaches this far from its trunk; the buffer must cover it. */
    static final int REQUIRED_BUFFER = 13;

    private final EnhancementPipeline pipeline;
    private final PopulatedChunkLedger ledger;
    private final Logger logger;
    private volatile boolean bufferChecked;

    public OverworldPopulator(EnhancementPipeline pipeline, PopulatedChunkLedger ledger, Logger logger) {
        this.pipeline = pipeline;
        this.ledger = ledger;
        this.logger = logger;
    }

    @Override
    public void populate(WorldInfo info, Random random, int chunkX, int chunkZ, LimitedRegion region) {
        if (info.getEnvironment() != World.Environment.NORMAL) return;

        if (!bufferChecked) {
            bufferChecked = true;
            logger.info("Generation-time enhancement active; region buffer is " + region.getBuffer() + " blocks around each chunk.");
            if (region.getBuffer() < REQUIRED_BUFFER) {
                logger.warning("That buffer is under " + REQUIRED_BUFFER + " — canopies at chunk edges will be clipped.");
            }
        }

        try {
            pipeline.run(Area.of(info, region), chunkX, chunkZ);
        } catch (Throwable t) {
            // Never let one chunk's failure escape into the chunk system.
            logger.log(Level.WARNING, "Failed enhancing generating chunk " + chunkX + "," + chunkZ, t);
        } finally {
            ledger.record(info.getUID(), chunkX, chunkZ); // done or failed, it must never be done twice
        }
    }
}

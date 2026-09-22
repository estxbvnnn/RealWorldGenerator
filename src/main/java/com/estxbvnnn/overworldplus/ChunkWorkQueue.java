package com.estxbvnnn.overworldplus;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;

import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BiConsumer;

/**
 * Work on already-loaded chunks (the ones that predate the plugin, or that
 * need the structure/cleanup passes) is real work — hundreds of block edits
 * per tree. Doing it synchronously inside ChunkLoadEvent is what caused lag:
 * a player exploring loads many chunks per second, and each one would block
 * the main thread for its full share of edits in a single tick. This spreads
 * it: jobs get queued on load and drained a few per tick.
 */
public final class ChunkWorkQueue {

    public enum Kind { ENHANCE, STRUCTURES, CLEANUP }

    private record Job(String world, int x, int z, Kind kind) {}

    private final Queue<Job> pending = new ConcurrentLinkedQueue<>();
    private final Set<Job> queued = ConcurrentHashMap.newKeySet();

    public void enqueue(Chunk chunk, Kind kind) {
        Job job = new Job(chunk.getWorld().getName(), chunk.getX(), chunk.getZ(), kind);
        if (queued.add(job)) {
            pending.add(job);
        }
    }

    public int size() {
        return pending.size();
    }

    /** Processes up to {@code budget} queued jobs. Safe to call every tick. */
    public void drain(int budget, BiConsumer<Chunk, Kind> processor) {
        for (int i = 0; i < budget; i++) {
            Job job = pending.poll();
            if (job == null) return;
            queued.remove(job);

            World world = Bukkit.getWorld(job.world());
            if (world == null || !world.isChunkLoaded(job.x(), job.z())) {
                continue; // unloaded before we got to it — it'll re-queue itself next time it loads
            }
            processor.accept(world.getChunkAt(job.x(), job.z()), job.kind());
        }
    }
}

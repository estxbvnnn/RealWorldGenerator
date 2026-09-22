package com.estxbvnnn.overworldplus;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Which chunks the generation-time populator has already finished, kept
 * until each one first loads fully (at which point the fact moves into the
 * chunk's own NBT and leaves this ledger). Needed because
 * ChunkLoadEvent#isNewChunk() is false for a chunk that was generated
 * earlier (say, as the border ring of a pre-generation) and only fully
 * loaded now — and re-enhancing a finished chunk is exactly what must never
 * happen. Written to disk within a second of any change and on shutdown.
 */
public final class PopulatedChunkLedger {

    private final File file;
    private final Logger logger;
    private final Map<UUID, Set<Long>> byWorld = new ConcurrentHashMap<>();
    private volatile boolean dirty;

    public PopulatedChunkLedger(File dataFolder, Logger logger) {
        this.file = new File(dataFolder, "populated_chunks.dat");
        this.logger = logger;
        load();
    }

    /** Called from generation worker threads. */
    public void record(UUID world, int chunkX, int chunkZ) {
        byWorld.computeIfAbsent(world, w -> ConcurrentHashMap.newKeySet()).add(key(chunkX, chunkZ));
        dirty = true;
    }

    /** @return true if the populator finished this chunk (and forgets it, since the chunk now carries the mark). */
    public boolean consume(UUID world, int chunkX, int chunkZ) {
        Set<Long> chunks = byWorld.get(world);
        if (chunks == null) return false;
        boolean had = chunks.remove(key(chunkX, chunkZ));
        if (had) dirty = true;
        return had;
    }

    public void saveIfDirty() {
        if (!dirty) return;
        dirty = false;
        try (DataOutputStream out = new DataOutputStream(new FileOutputStream(file))) {
            out.writeInt(byWorld.size());
            for (Map.Entry<UUID, Set<Long>> entry : byWorld.entrySet()) {
                out.writeLong(entry.getKey().getMostSignificantBits());
                out.writeLong(entry.getKey().getLeastSignificantBits());
                Long[] keys = entry.getValue().toArray(new Long[0]);
                out.writeInt(keys.length);
                for (Long key : keys) out.writeLong(key);
            }
        } catch (IOException e) {
            dirty = true;
            logger.log(Level.WARNING, "Could not save populated_chunks.dat", e);
        }
    }

    private void load() {
        if (!file.exists()) return;
        try (DataInputStream in = new DataInputStream(new FileInputStream(file))) {
            int worlds = in.readInt();
            for (int w = 0; w < worlds; w++) {
                UUID world = new UUID(in.readLong(), in.readLong());
                int count = in.readInt();
                Set<Long> chunks = ConcurrentHashMap.newKeySet();
                for (int i = 0; i < count; i++) chunks.add(in.readLong());
                byWorld.put(world, chunks);
            }
        } catch (IOException e) {
            logger.log(Level.WARNING, "Could not read populated_chunks.dat — starting empty", e);
        }
    }

    private static long key(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }
}

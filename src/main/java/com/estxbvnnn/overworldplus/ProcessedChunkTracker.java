package com.estxbvnnn.overworldplus;

import org.bukkit.Chunk;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * What's already been done to a chunk, persisted in the chunk's own NBT
 * data. This — not "is this chunk new" — is what stops re-enhancement on
 * every reload, and what lets the plugin pick up a world that already
 * existed before it was installed: the first time each old chunk loads, it
 * gets enhanced once.
 *
 * Three independent marks: the main enhancement (trees/terrain/mountains/
 * biomes/houses), the structure-decoration pass (needs a loaded Chunk for
 * its structure registry, so it always runs on the load path), and a one-off
 * cleanup of stray vines left behind by earlier versions of this plugin.
 */
public final class ProcessedChunkTracker {

    private final NamespacedKey enhanced;
    private final NamespacedKey structures;
    private final NamespacedKey cleanup;

    public ProcessedChunkTracker(Plugin plugin) {
        this.enhanced = new NamespacedKey(plugin, "enhanced");
        this.structures = new NamespacedKey(plugin, "structures_done");
        this.cleanup = new NamespacedKey(plugin, "cleanup_v1");
    }

    public boolean isEnhanced(Chunk chunk) {
        return has(chunk, enhanced);
    }

    public void markEnhanced(Chunk chunk) {
        set(chunk, enhanced);
    }

    public boolean isStructuresDone(Chunk chunk) {
        return has(chunk, structures);
    }

    public void markStructuresDone(Chunk chunk) {
        set(chunk, structures);
    }

    public boolean isCleanupDone(Chunk chunk) {
        return has(chunk, cleanup);
    }

    public void markCleanupDone(Chunk chunk) {
        set(chunk, cleanup);
    }

    private static boolean has(Chunk chunk, NamespacedKey key) {
        return chunk.getPersistentDataContainer().has(key, PersistentDataType.BOOLEAN);
    }

    private static void set(Chunk chunk, NamespacedKey key) {
        PersistentDataContainer container = chunk.getPersistentDataContainer();
        container.set(key, PersistentDataType.BOOLEAN, true);
    }
}

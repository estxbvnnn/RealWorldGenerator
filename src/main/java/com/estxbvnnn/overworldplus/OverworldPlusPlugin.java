package com.estxbvnnn.overworldplus;

import com.estxbvnnn.overworldplus.commands.OverworldPlusCommand;
import com.estxbvnnn.overworldplus.mountains.SpireRegistry;
import com.estxbvnnn.overworldplus.structures.StructureSchematicLibrary;
import com.estxbvnnn.overworldplus.trees.SchematicTreeLibrary;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

public class OverworldPlusPlugin extends JavaPlugin implements Listener {

    private Pregenerator pregenerator;
    private ChunkEnhanceListener listener;
    private PopulatedChunkLedger ledger;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        SpireRegistry spireRegistry = new SpireRegistry(getDataFolder(), getLogger());
        SpireRegistry houseRegistry = new SpireRegistry(getDataFolder(), getLogger(), "abandoned_houses.yml");
        ProcessedChunkTracker tracker = new ProcessedChunkTracker(this);
        ChunkWorkQueue workQueue = new ChunkWorkQueue();
        SchematicTreeLibrary treeLibrary = new SchematicTreeLibrary(this);
        StructureSchematicLibrary structureLibrary = new StructureSchematicLibrary(this);

        EnhancementPipeline pipeline = new EnhancementPipeline(getConfig(), treeLibrary, structureLibrary, spireRegistry, houseRegistry);
        ledger = new PopulatedChunkLedger(getDataFolder(), getLogger());
        OverworldPopulator populator = new OverworldPopulator(pipeline, ledger, getLogger());
        listener = new ChunkEnhanceListener(getConfig(), tracker, workQueue, pipeline, populator, ledger, getLogger());
        pregenerator = new Pregenerator(this, getLogger(), workQueue::size);
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, ledger::saveIfDirty, 20L, 20L);

        getServer().getPluginManager().registerEvents(listener, this);
        getServer().getPluginManager().registerEvents(this, this);

        // This plugin loads at STARTUP, before any world exists, precisely so WorldInitEvent can
        // attach the populator ahead of spawn-chunk generation. After a /reload the worlds are
        // already there, so attach directly.
        for (World world : Bukkit.getWorlds()) {
            listener.attachPopulator(world);
            startAutomaticPregen(world);
        }

        // Old-chunk work is drained a few chunks per tick — see ChunkWorkQueue for why. A repeating
        // task that throws anything uncaught gets cancelled by Paper forever, so this is the
        // outermost safety net.
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            try {
                workQueue.drain(listener.drainBudget(), listener::process);
            } catch (Throwable t) {
                getLogger().log(Level.SEVERE, "Chunk work queue drain failed", t);
            }
        }, 1L, 1L);

        // A once-a-minute progress line, only while something is actually happening.
        final int[] lastReported = {0};
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (listener.chunksEnhanced() != lastReported[0]) {
                lastReported[0] = listener.chunksEnhanced();
                getLogger().info(listener.statsLine());
            }
        }, 1200L, 1200L);

        getCommand("overworldplus").setExecutor(new OverworldPlusCommand(this, treeLibrary, structureLibrary, listener, pregenerator));

        getLogger().info("OverworldPlus v" + getPluginMeta().getVersion()
                + " by estxbvnnn (https://github.com/estxbvnnn) enabled.");
    }

    @Override
    public void onDisable() {
        if (pregenerator != null) pregenerator.stop();
        if (ledger != null) ledger.saveIfDirty();
    }

    /** Vanilla has just finished its own spawn preparation for this world — ours starts right after. */
    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        startAutomaticPregen(event.getWorld());
    }

    private void startAutomaticPregen(World world) {
        if (world.getEnvironment() != World.Environment.NORMAL) return;
        if (!getConfig().getBoolean("pregen.enabled", true)) return;

        int radius = Math.max(1, getConfig().getInt("pregen.radius-chunks", 16));
        boolean blockJoins = getConfig().getBoolean("pregen.block-joins-until-done", true);
        pregenerator.startAutomatic(world, radius, blockJoins);
    }

    @EventHandler
    public void onLogin(PlayerLoginEvent event) {
        if (pregenerator.blocksJoins()) {
            event.disallow(PlayerLoginEvent.Result.KICK_OTHER,
                    "El servidor está preparando el mundo — " + pregenerator.progressLine() + "\nVolvé a intentar en un momento.");
        }
    }
}

package com.estxbvnnn.overworldplus;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.IntSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Pre-generates (and therefore pre-enhances) a square of chunks around
 * spawn, the way vanilla's own "Preparing spawn area" does, so the world a
 * player first walks into is already finished. Runs at startup on its own
 * when enabled, and on demand via {@code /overworldplus pregen}. Chunks
 * are requested asynchronously a few at a time, so it uses the generation
 * worker threads in parallel instead of stalling the main thread, and a
 * sliding window of chunk tickets keeps each chunk's neighbors loaded long
 * enough for chunks that predate the plugin to be caught up too.
 *
 * Remembers the radius already completed per world, so a restart doesn't
 * redo it; raising the configured radius just does the new ring.
 */
public final class Pregenerator {

    private static final int PARALLEL_LOADS = 6;
    private static final int TICKET_WINDOW = 400;

    private final Plugin plugin;
    private final Logger logger;
    private final File stateFile;
    private final IntSupplier pendingWork; // old-chunk jobs still queued — the run isn't done until they are
    private Run current;

    private final class Run {
        final World world;
        final int radius;
        final boolean blockJoins;
        final List<int[]> order;
        final Deque<int[]> ticketed = new ArrayDeque<>();
        final long startedAt = System.nanoTime();
        int next;
        int inFlight;
        int done;
        int lastLoggedPercent = -1;
        BukkitTask task;

        Run(World world, int radius, boolean blockJoins) {
            this.world = world;
            this.radius = radius;
            this.blockJoins = blockJoins;
            int cx = world.getSpawnLocation().getBlockX() >> 4;
            int cz = world.getSpawnLocation().getBlockZ() >> 4;
            this.order = spiral(cx, cz, radius);
        }

        int percent() {
            return order.isEmpty() ? 100 : (int) (done * 100L / order.size());
        }
    }

    public Pregenerator(Plugin plugin, Logger logger, IntSupplier pendingWork) {
        this.plugin = plugin;
        this.logger = logger;
        this.pendingWork = pendingWork;
        this.stateFile = new File(plugin.getDataFolder(), "pregen.yml");
    }

    public boolean isRunning() {
        return current != null;
    }

    public boolean blocksJoins() {
        return current != null && current.blockJoins;
    }

    public String progressLine() {
        if (current == null) return "No pre-generation running.";
        return "Preparing world '" + current.world.getName() + "': " + current.percent() + "% ("
                + current.done + "/" + current.order.size() + " chunks, radius " + current.radius + ")";
    }

    /** Startup entry: only does anything if the configured radius is bigger than what's already done. */
    public void startAutomatic(World world, int radius, boolean blockJoins) {
        if (radius <= completedRadius(world)) {
            logger.info("World '" + world.getName() + "' already pre-generated to radius " + completedRadius(world) + " chunks.");
            return;
        }
        start(world, radius, blockJoins);
    }

    public boolean start(World world, int radius, boolean blockJoins) {
        if (current != null) return false;
        current = new Run(world, radius, blockJoins);
        logger.info("Preparing world '" + world.getName() + "': " + current.order.size() + " chunks out to radius "
                + radius + (blockJoins ? " — players can't join until it's done." : "."));
        current.task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        return true;
    }

    public void stop() {
        if (current == null) return;
        Run run = current;
        current = null;
        run.task.cancel();
        run.world.removePluginChunkTickets(plugin);
        logger.info("Pre-generation of '" + run.world.getName() + "' stopped at " + run.percent() + "%.");
    }

    private void tick() {
        Run run = current;
        if (run == null) return;

        while (run.inFlight < PARALLEL_LOADS && run.next < run.order.size()) {
            int[] coord = run.order.get(run.next++);
            run.inFlight++;
            run.world.getChunkAtAsync(coord[0], coord[1], true).whenComplete((chunk, error) ->
                    Bukkit.getScheduler().runTask(plugin, () -> onLoaded(run, coord, chunk, error)));
        }

        if (run.next >= run.order.size() && run.inFlight == 0 && pendingWork.getAsInt() == 0) {
            finish(run);
        }
    }

    private void onLoaded(Run run, int[] coord, Chunk chunk, Throwable error) {
        if (current != run) return;
        run.inFlight--;
        run.done++;
        if (error != null) {
            logger.log(Level.WARNING, "Failed to generate chunk " + coord[0] + "," + coord[1], error);
        } else if (chunk != null) {
            run.world.addPluginChunkTicket(coord[0], coord[1], plugin);
            run.ticketed.addLast(coord);
            while (run.ticketed.size() > TICKET_WINDOW) {
                int[] old = run.ticketed.pollFirst();
                run.world.removePluginChunkTicket(old[0], old[1], plugin);
            }
        }

        int percent = run.percent();
        if (percent / 5 != run.lastLoggedPercent / 5 || percent == 100) {
            run.lastLoggedPercent = percent;
            long elapsed = (System.nanoTime() - run.startedAt) / 1_000_000_000L;
            logger.info("Preparing world '" + run.world.getName() + "': " + percent + "% (" + run.done + "/"
                    + run.order.size() + " chunks, " + elapsed + "s)");
        }
    }

    private void finish(Run run) {
        current = null;
        run.task.cancel();
        run.world.removePluginChunkTickets(plugin);
        long elapsed = (System.nanoTime() - run.startedAt) / 1_000_000_000L;
        logger.info("World '" + run.world.getName() + "' prepared out to radius " + run.radius + " chunks in " + elapsed + "s.");
        saveCompletedRadius(run.world, run.radius);
    }

    private int completedRadius(World world) {
        if (!stateFile.exists()) return 0;
        return YamlConfiguration.loadConfiguration(stateFile).getInt("completed." + world.getName(), 0);
    }

    private void saveCompletedRadius(World world, int radius) {
        YamlConfiguration yaml = stateFile.exists() ? YamlConfiguration.loadConfiguration(stateFile) : new YamlConfiguration();
        yaml.set("completed." + world.getName(), Math.max(radius, yaml.getInt("completed." + world.getName(), 0)));
        try {
            yaml.save(stateFile);
        } catch (IOException e) {
            logger.log(Level.WARNING, "Could not save pregen.yml", e);
        }
    }

    /** Ring by ring outward from the center, so the spawn area is finished first. */
    private static List<int[]> spiral(int cx, int cz, int radius) {
        List<int[]> order = new ArrayList<>();
        order.add(new int[]{cx, cz});
        for (int r = 1; r <= radius; r++) {
            for (int dx = -r; dx <= r; dx++) {
                order.add(new int[]{cx + dx, cz - r});
                order.add(new int[]{cx + dx, cz + r});
            }
            for (int dz = -r + 1; dz <= r - 1; dz++) {
                order.add(new int[]{cx - r, cz + dz});
                order.add(new int[]{cx + r, cz + dz});
            }
        }
        return order;
    }
}

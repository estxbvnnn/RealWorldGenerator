package com.estxbvnnn.overworldplus.mountains;

import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A generic "these points are spaced apart" tracker, so rare landmarks don't
 * cluster and don't respawn after a restart. Named after its first user
 * (rock spires) but reused as-is for abandoned houses via a different file.
 */
public class SpireRegistry {

    private record Point(String world, int x, int z) {}

    private static final String LIST_KEY = "points";

    private final File file;
    private final Logger logger;
    private final List<Point> points = new CopyOnWriteArrayList<>();

    public SpireRegistry(File dataFolder, Logger logger) {
        this(dataFolder, logger, "spires.yml");
    }

    public SpireRegistry(File dataFolder, Logger logger, String fileName) {
        this.file = new File(dataFolder, fileName);
        this.logger = logger;
        load();
    }

    public boolean isTooCloseToExisting(Location location, double minSpacing) {
        return isTooCloseToExisting(location.getWorld().getName(), location.getBlockX(), location.getBlockZ(), minSpacing);
    }

    public boolean isTooCloseToExisting(String world, int x, int z, double minSpacing) {
        double minSpacingSquared = minSpacing * minSpacing;
        for (Point point : points) {
            if (!point.world().equals(world)) continue;
            double dx = x - point.x();
            double dz = z - point.z();
            if (dx * dx + dz * dz < minSpacingSquared) return true;
        }
        return false;
    }

    public void add(Location location) {
        add(location.getWorld().getName(), location.getBlockX(), location.getBlockZ());
    }

    /** Safe from any thread — generation-time callers run on worker threads. */
    public void add(String world, int x, int z) {
        points.add(new Point(world, x, z));
        save();
    }

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (Object entry : yaml.getList(LIST_KEY, new ArrayList<>())) {
            if (!(entry instanceof Map<?, ?> map)) continue;
            points.add(new Point(
                    String.valueOf(map.get("world")),
                    ((Number) map.get("x")).intValue(),
                    ((Number) map.get("z")).intValue()
            ));
        }
    }

    public synchronized void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        List<Map<String, Object>> serialized = new ArrayList<>();
        for (Point point : points) {
            Map<String, Object> map = new java.util.LinkedHashMap<>();
            map.put("world", point.world());
            map.put("x", point.x());
            map.put("z", point.z());
            serialized.add(map);
        }
        yaml.set(LIST_KEY, serialized);
        try {
            yaml.save(file);
        } catch (IOException e) {
            logger.log(Level.WARNING, "Could not save " + file.getName(), e);
        }
    }
}

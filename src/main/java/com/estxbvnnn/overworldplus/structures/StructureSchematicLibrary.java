package com.estxbvnnn.overworldplus.structures;

import org.bukkit.plugin.Plugin;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.zip.GZIPInputStream;

/**
 * Loads the abandoned-house schematic (an ancient lighthouse ruin) bundled
 * as a plugin resource, extracted once at build time from the real .schem
 * file the user provided — see tools/extract_lighthouse.py in the repo.
 */
public final class StructureSchematicLibrary {

    private static final String RESOURCE_PATH = "structures/ancient_lighthouse.txt.gz";
    // The .schem was saved by 1.20.1; block ids renamed since then have to be translated or
    // Bukkit.createBlockData throws on them — which used to abort the paste halfway through.
    private static final java.util.Map<String, String> LEGACY_IDS = java.util.Map.of(
            "grass", "short_grass"
    );

    private final StructureSchematic abandonedHouse;

    public StructureSchematicLibrary(Plugin plugin) {
        this.abandonedHouse = load(plugin, RESOURCE_PATH);
        if (abandonedHouse != null) {
            plugin.getLogger().info("Loaded ancient-lighthouse schematic: "
                    + abandonedHouse.blocks().size() + " blocks, "
                    + abandonedHouse.containerOffsets().size() + " containers.");
        } else {
            plugin.getLogger().warning(RESOURCE_PATH + " not found in jar — abandoned houses will not spawn.");
        }
    }

    public Optional<StructureSchematic> abandonedHouse() {
        return Optional.ofNullable(abandonedHouse);
    }

    private StructureSchematic load(Plugin plugin, String path) {
        try (InputStream raw = plugin.getResource(path)) {
            if (raw == null) return null;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new GZIPInputStream(raw), StandardCharsets.UTF_8))) {
                int blockCount = Integer.parseInt(reader.readLine().trim());
                List<StructureSchematic.BlockPlacement> blocks = new ArrayList<>(blockCount);
                for (int i = 0; i < blockCount; i++) {
                    String line = reader.readLine();
                    int firstComma = line.indexOf(',');
                    int secondComma = line.indexOf(',', firstComma + 1);
                    int thirdComma = line.indexOf(',', secondComma + 1);
                    int dx = Integer.parseInt(line.substring(0, firstComma));
                    int dy = Integer.parseInt(line.substring(firstComma + 1, secondComma));
                    int dz = Integer.parseInt(line.substring(secondComma + 1, thirdComma));
                    String data = translateLegacyId(line.substring(thirdComma + 1));
                    blocks.add(new StructureSchematic.BlockPlacement(dx, dy, dz, data));
                }

                String marker = reader.readLine(); // "CONTAINERS,<count>"
                int containerCount = Integer.parseInt(marker.substring(marker.indexOf(',') + 1));
                List<int[]> containers = new ArrayList<>(containerCount);
                for (int i = 0; i < containerCount; i++) {
                    String[] parts = reader.readLine().split(",");
                    containers.add(new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2])});
                }

                return new StructureSchematic(blocks, containers);
            }
        } catch (IOException | RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Failed loading " + path, e);
            return null;
        }
    }

    private static String translateLegacyId(String data) {
        int bracket = data.indexOf('[');
        String id = bracket < 0 ? data : data.substring(0, bracket);
        String renamed = LEGACY_IDS.get(id);
        return renamed == null ? data : renamed + (bracket < 0 ? "" : data.substring(bracket));
    }
}

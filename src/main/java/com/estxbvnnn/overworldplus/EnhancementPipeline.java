package com.estxbvnnn.overworldplus;

import com.estxbvnnn.overworldplus.biomes.BiomeDecorator;
import com.estxbvnnn.overworldplus.mountains.MountainBiomes;
import com.estxbvnnn.overworldplus.mountains.MountainDecorator;
import com.estxbvnnn.overworldplus.mountains.RockSpireGenerator;
import com.estxbvnnn.overworldplus.mountains.SpireRegistry;
import com.estxbvnnn.overworldplus.structures.AbandonedHouseGenerator;
import com.estxbvnnn.overworldplus.structures.StructureSchematic;
import com.estxbvnnn.overworldplus.structures.StructureSchematicLibrary;
import com.estxbvnnn.overworldplus.terrain.TerrainDetailer;
import com.estxbvnnn.overworldplus.trees.SchematicTreeLibrary;
import com.estxbvnnn.overworldplus.trees.TreeEnhancer;
import org.bukkit.Material;
import org.bukkit.block.Biome;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Every pass, in order, for one chunk — the single definition of "enhanced".
 * Runs unchanged on either backend of {@link Area}: at generation time
 * (before the chunk is ever sent to a client) or later on a loaded chunk
 * that predates the plugin.
 */
public final class EnhancementPipeline {

    private static final Set<Biome> HOUSE_BIOMES = Set.of(
            Biome.PLAINS, Biome.SUNFLOWER_PLAINS, Biome.FOREST, Biome.BIRCH_FOREST, Biome.DARK_FOREST,
            Biome.OLD_GROWTH_BIRCH_FOREST, Biome.TAIGA, Biome.OLD_GROWTH_PINE_TAIGA, Biome.OLD_GROWTH_SPRUCE_TAIGA,
            Biome.SNOWY_TAIGA, Biome.SAVANNA, Biome.SAVANNA_PLATEAU, Biome.MEADOW, Biome.SWAMP,
            Biome.FLOWER_FOREST, Biome.CHERRY_GROVE // beaches belong to AbyssDepths' beach pieces
    );
    // The schematic's own recorded paste point (where its builder stood): local x=18 sits one
    // step east of its 18-wide footprint, z=4 sits centered on its 9-long footprint.
    private static final int HOUSE_SPAN_WEST = 18;
    private static final int HOUSE_SPAN_SIDE = 4;

    private final FileConfiguration config;
    private final SchematicTreeLibrary treeLibrary;
    private final StructureSchematicLibrary structureLibrary;
    private final SpireRegistry spireRegistry;
    private final SpireRegistry houseRegistry;

    private final AtomicInteger chunksEnhanced = new AtomicInteger();
    private final AtomicInteger treesPlaced = new AtomicInteger();
    private final AtomicInteger vanillaRemoved = new AtomicInteger();

    public EnhancementPipeline(FileConfiguration config, SchematicTreeLibrary treeLibrary,
                               StructureSchematicLibrary structureLibrary,
                               SpireRegistry spireRegistry, SpireRegistry houseRegistry) {
        this.config = config;
        this.treeLibrary = treeLibrary;
        this.structureLibrary = structureLibrary;
        this.spireRegistry = spireRegistry;
        this.houseRegistry = houseRegistry;
    }

    public void run(Area area, int chunkX, int chunkZ) {
        if (config.getBoolean("trees.enabled", true)) {
            TreeEnhancer.Result trees = TreeEnhancer.enhance(area, chunkX, chunkZ, config, treeLibrary);
            treesPlaced.addAndGet(trees.placed());
            vanillaRemoved.addAndGet(trees.removed());
        }
        // After trees: the tree pass reads vanilla trunks standing on vanilla soil, and this
        // pass may well turn that soil to rock or podzol.
        if (config.getBoolean("terrain.enabled", true)) {
            TerrainDetailer.detail(area, chunkX, chunkZ, config);
        }
        if (config.getBoolean("mountains.enabled", true)) {
            MountainDecorator.decorateChunk(area, chunkX, chunkZ, config);
            rockSpire(area, chunkX, chunkZ);
        }
        if (config.getBoolean("biomes.enabled", true)) {
            BiomeDecorator.decorate(area, chunkX, chunkZ, config);
        }
        if (config.getBoolean("abandoned-houses.enabled", true)) {
            abandonedHouse(area, chunkX, chunkZ);
        }
        chunksEnhanced.incrementAndGet();
    }

    public String statsLine(int queued) {
        return chunksEnhanced.get() + " chunk(s) enhanced this session — " + treesPlaced.get() + " big tree(s) placed, "
                + vanillaRemoved.get() + " vanilla tree(s) removed, " + queued + " old chunk(s) queued.";
    }

    public int chunksEnhanced() {
        return chunksEnhanced.get();
    }

    private void rockSpire(Area area, int chunkX, int chunkZ) {
        if (!config.getBoolean("mountains.rock-spire.enabled", true)) return;

        int x = (chunkX << 4) + 8;
        int z = (chunkZ << 4) + 8;
        int topY = SolidGroundFinder.findY(area, x, z);
        if (!MountainBiomes.ALL.contains(area.biome(x, topY, z))) return;

        int everyN = Math.max(1, (int) Math.round(1 / Math.max(0.0001, config.getDouble("mountains.rock-spire.spawn-chance-per-chunk", 0.01))));
        if (CoordHash.pick(area.seed(), chunkX + 101, chunkZ - 101, everyN) != 0) return;

        double spacing = config.getDouble("mountains.rock-spire.min-spacing", 300);
        if (spireRegistry.isTooCloseToExisting(area.worldName(), x, z, spacing)) return;

        RockSpireGenerator.build(area, x, topY + 1, z);
        spireRegistry.add(area.worldName(), x, z);
    }

    private void abandonedHouse(Area area, int chunkX, int chunkZ) {
        Optional<StructureSchematic> schematic = structureLibrary.abandonedHouse();
        if (schematic.isEmpty()) return;

        int x = (chunkX << 4) + 8;
        int z = (chunkZ << 4) + 8;
        int groundY = SolidGroundFinder.findY(area, x, z);
        if (!HOUSE_BIOMES.contains(area.biome(x, groundY, z))) return;
        if (area.type(x, groundY + 1, z) != Material.AIR) return; // needs flat open ground

        int everyN = Math.max(1, (int) Math.round(1 / Math.max(0.0001, config.getDouble("abandoned-houses.spawn-chance-per-chunk", 0.01))));
        if (CoordHash.pick(area.seed(), chunkX - 77, chunkZ + 77, everyN) != 0) return;

        double spacing = config.getDouble("abandoned-houses.min-spacing", 220);
        if (houseRegistry.isTooCloseToExisting(area.worldName(), x, z, spacing)) return;
        if (!isFootprintFlat(area, x, groundY, z)) return;

        AbandonedHouseGenerator.build(area, x, groundY, z, schematic.get());
        houseRegistry.add(area.worldName(), x, z);
    }

    /** Samples the structure's real footprint corners so an 18x9 building doesn't get half-buried on a slope. */
    private static boolean isFootprintFlat(Area area, int ax, int groundY, int az) {
        int[][] corners = {
                {ax, az}, {ax - HOUSE_SPAN_WEST, az},
                {ax, az - HOUSE_SPAN_SIDE}, {ax, az + HOUSE_SPAN_SIDE},
                {ax - HOUSE_SPAN_WEST, az - HOUSE_SPAN_SIDE}, {ax - HOUSE_SPAN_WEST, az + HOUSE_SPAN_SIDE}
        };
        for (int[] corner : corners) {
            if (!area.canEdit(corner[0], groundY, corner[1])) return false; // can't see the whole footprint
            int cornerY = SolidGroundFinder.findY(area, corner[0], corner[1]);
            if (Math.abs(cornerY - groundY) > 2) return false;
            Material type = area.type(corner[0], cornerY, corner[1]);
            if (type == Material.WATER || type == Material.LAVA) return false;
        }
        return true;
    }
}

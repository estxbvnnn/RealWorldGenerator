package com.estxbvnnn.overworldplus.mountains;

import org.bukkit.block.Biome;

import java.util.Set;

/** Biome is registry-backed (not a real enum) in current Paper, hence a plain Set. */
public final class MountainBiomes {

    public static final Set<Biome> ALL = Set.of(
            Biome.WINDSWEPT_HILLS, Biome.WINDSWEPT_GRAVELLY_HILLS, Biome.WINDSWEPT_FOREST, Biome.WINDSWEPT_SAVANNA,
            Biome.JAGGED_PEAKS, Biome.FROZEN_PEAKS, Biome.STONY_PEAKS, Biome.SNOWY_SLOPES, Biome.GROVE, Biome.MEADOW
    );

    public static final Set<Biome> SNOWY = Set.of(
            Biome.JAGGED_PEAKS, Biome.FROZEN_PEAKS, Biome.SNOWY_SLOPES
    );

    private MountainBiomes() {}
}

package com.estxbvnnn.overworldplus.terrain;

import org.bukkit.util.noise.SimplexOctaveGenerator;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Smooth, seeded noise fields — the reason the surface variation reads as
 * natural patches (a stretch of podzol under the pines, a band of gravel
 * along a bank, a shoulder of bare rock) instead of random speckle. Seeded
 * from the world seed, so the same world always gets the same patches, and
 * patches continue seamlessly across chunk borders because every chunk is
 * sampling the same continuous field.
 */
public final class TerrainNoise {

    private static final Map<Long, TerrainNoise> BY_SEED = new ConcurrentHashMap<>();

    private final SimplexOctaveGenerator patches;
    private final SimplexOctaveGenerator rock;
    private final SimplexOctaveGenerator vegetation;

    private TerrainNoise(long seed) {
        patches = new SimplexOctaveGenerator(seed ^ 0x5EED_0001L, 2);
        patches.setScale(1 / 40.0);
        rock = new SimplexOctaveGenerator(seed ^ 0x5EED_0002L, 2);
        rock.setScale(1 / 22.0);
        vegetation = new SimplexOctaveGenerator(seed ^ 0x5EED_0003L, 2);
        vegetation.setScale(1 / 30.0);
    }

    public static TerrainNoise of(long seed) {
        return BY_SEED.computeIfAbsent(seed, TerrainNoise::new);
    }

    /** Broad ground-material patches, ~40-80 blocks across. In [-1, 1]. */
    public double patch(int x, int z) {
        return patches.noise(x, z, 2.0, 0.5, true);
    }

    /** Tighter rocky outcrops, ~20-40 blocks across. In [-1, 1]. */
    public double rock(int x, int z) {
        return rock.noise(x, z, 2.0, 0.5, true);
    }

    /** Where the undergrowth is thick vs. sparse. In [-1, 1]. */
    public double vegetation(int x, int z) {
        return vegetation.noise(x, z, 2.0, 0.5, true);
    }
}

package com.estxbvnnn.overworldplus;

/**
 * A fixed function of world coordinates, used wherever a decision has to
 * vary from place to place without being a dice roll: the same spot in the
 * same world always gets the same answer, on every server start, forever.
 * That's the difference between "this chunk always has its waterfall" and
 * "this chunk had a 1-in-6 chance of one".
 */
public final class CoordHash {

    private CoordHash() {}

    public static int of(long seed, int x, int z) {
        long h = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL);
        h ^= (h >>> 29);
        h *= 0xBF58476D1CE4E5B9L;
        h ^= (h >>> 32);
        return (int) (h & 0x7FFFFFFF);
    }

    /** A value in [0, bound) for this spot. */
    public static int pick(long seed, int x, int z, int bound) {
        return of(seed, x, z) % bound;
    }
}

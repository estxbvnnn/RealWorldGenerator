package com.estxbvnnn.overworldplus;

/** Packs a block position into one long so positions can live in a HashSet cheaply. */
public final class BlockKey {

    private BlockKey() {}

    public static long of(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (long) (y & 0xFFF);
    }
}

package com.estxbvnnn.overworldplus;

import org.bukkit.HeightMap;

/**
 * The bug this exists to fix: HeightMap.WORLD_SURFACE stops at the first
 * non-air block, and tall grass/flowers/saplings/an existing snow layer all
 * count as "non-air" — so placing a snow layer (or anything else) directly
 * on top of that "surface" sits it above a paper-thin, easy-to-miss block
 * instead of the real ground, which reads as snow floating over nothing.
 * This walks down from the heightmap's guess until it hits an actually
 * solid block, and returns that block's Y.
 */
public final class SolidGroundFinder {

    private SolidGroundFinder() {}

    public static int findY(Area area, int x, int z) {
        int y = area.highestY(x, z, HeightMap.WORLD_SURFACE);
        int minY = area.minY();
        while (y > minY && !area.isSolid(x, y, z)) {
            y--;
        }
        return y;
    }
}

package com.estxbvnnn.overworldplus.mountains;

import com.estxbvnnn.overworldplus.Area;
import com.estxbvnnn.overworldplus.CoordHash;
import org.bukkit.Material;

/** A tall, tapering natural rock spire — purely scenic, meant to be visible from the valley below. */
public final class RockSpireGenerator {

    private static final Material[] PALETTE = { Material.STONE, Material.ANDESITE, Material.MOSSY_COBBLESTONE };

    private RockSpireGenerator() {}

    public static void build(Area area, int cx, int baseY, int cz) {
        long seed = area.seed();
        int height = 12 + CoordHash.pick(seed, cx, cz, 8);

        for (int dy = 0; dy < height; dy++) {
            int radius = Math.max(1, 3 - dy / 6);
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx * dx + dz * dz > radius * radius) continue;

                    int x = cx + dx, y = baseY + dy, z = cz + dz;
                    if (isCarvable(area.type(x, y, z))) {
                        area.set(x, y, z, PALETTE[CoordHash.pick(seed, x + y, z - y, PALETTE.length)]);
                    }
                }
            }
        }
    }

    private static boolean isCarvable(Material type) {
        return type == Material.AIR || type == Material.STONE || type == Material.ANDESITE
                || type == Material.GRANITE || type == Material.DIORITE || type.name().endsWith("_LEAVES");
    }
}

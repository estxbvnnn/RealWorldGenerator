package com.estxbvnnn.overworldplus;

import org.bukkit.Material;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;

import java.util.Set;

/**
 * Two-block plants (tall grass, large fern, the tall flowers) are two blocks
 * with half=lower / half=upper. Setting only the lower one — which is what a
 * plain setType does — leaves a broken half that renders as a stub and pops
 * the moment anything updates it. This sets both halves properly.
 */
public final class PlantPlacer {

    private static final Set<Material> DOUBLE_PLANTS = Set.of(
            Material.TALL_GRASS, Material.LARGE_FERN, Material.SUNFLOWER, Material.LILAC,
            Material.ROSE_BUSH, Material.PEONY
    );

    /** Blocks a small plant can stand on. */
    public static final Set<Material> PLANTABLE = Set.of(
            Material.GRASS_BLOCK, Material.DIRT, Material.PODZOL, Material.COARSE_DIRT,
            Material.MOSS_BLOCK, Material.ROOTED_DIRT, Material.MUD, Material.MYCELIUM
    );

    private PlantPlacer() {}

    /**
     * Plants on top of the ground block at (x, groundY, z).
     * @return true if placed; false if the spot wasn't free or the ground can't hold it.
     */
    public static boolean place(Area area, int x, int groundY, int z, Material plant) {
        int y = groundY + 1;
        if (area.type(x, y, z) != Material.AIR) return false;
        if (!PLANTABLE.contains(area.type(x, groundY, z))) return false;
        if (!area.canEdit(x, y, z)) return false;

        if (!DOUBLE_PLANTS.contains(plant)) {
            area.set(x, y, z, plant);
            return true;
        }

        if (area.type(x, y + 1, z) != Material.AIR || !area.canEdit(x, y + 1, z)) return false;

        BlockData lower = plant.createBlockData();
        BlockData upper = plant.createBlockData();
        if (lower instanceof Bisected l && upper instanceof Bisected u) {
            l.setHalf(Bisected.Half.BOTTOM);
            u.setHalf(Bisected.Half.TOP);
        }
        area.set(x, y, z, lower);
        area.set(x, y + 1, z, upper);
        return true;
    }

    /** Anything thin sitting on the ground that should go away when the ground turns to rock. */
    public static boolean isGroundCover(Material type) {
        return !type.isSolid() && type != Material.AIR && type != Material.WATER && type != Material.LAVA
                && type != Material.SNOW && type != Material.POWDER_SNOW;
    }
}

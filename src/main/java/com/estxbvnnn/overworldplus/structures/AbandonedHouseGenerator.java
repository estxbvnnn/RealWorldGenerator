package com.estxbvnnn.overworldplus.structures;

import com.estxbvnnn.overworldplus.Area;
import com.estxbvnnn.overworldplus.CoordHash;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.data.BlockData;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * A standalone find in the wild — a real hand-built structure (an ancient
 * lighthouse ruin), pasted exactly as its original builder made it: every
 * block placed here is the exact state extracted from the source .schem
 * (stair facing, door hinge, double-chest half, all of it), not a
 * procedural approximation. Replaced the three procedural styles
 * (collapsed/overgrown/burned) this plugin used to generate on its own.
 */
public final class AbandonedHouseGenerator {

    private AbandonedHouseGenerator() {}

    /**
     * @param groundY the solid ground block the structure should stand on; blocks are pasted
     *                one above it, matching the schematic's own recorded paste point (where
     *                its builder was standing).
     */
    public static void build(Area area, int ax, int groundY, int az, StructureSchematic schematic) {
        int ay = groundY + 1;

        for (StructureSchematic.BlockPlacement placement : schematic.blocks()) {
            BlockData data;
            try {
                data = Bukkit.createBlockData(placement.data());
            } catch (IllegalArgumentException e) {
                continue; // an id this server version doesn't know — skip that one block, never the building
            }
            area.set(ax + placement.dx(), ay + placement.dy(), az + placement.dz(), data);
        }

        for (int[] offset : schematic.containerOffsets()) {
            fillContainer(area, ax + offset[0], ay + offset[1], az + offset[2]);
        }
    }

    private static void fillContainer(Area area, int x, int y, int z) {
        if (!area.canEdit(x, y, z)) return;
        BlockState state = area.state(x, y, z);
        if (!(state instanceof Container container)) return;

        for (ItemStack item : rollLoot(area.type(x, y, z), CoordHash.of(area.seed(), x + y, z - y))) {
            container.getInventory().addItem(item);
        }
        area.apply(x, y, z, state);
    }

    private static List<ItemStack> rollLoot(Material containerType, int roll) {
        List<ItemStack> loot = new ArrayList<>();
        loot.add(new ItemStack(Material.BREAD, 1 + roll % 3));
        loot.add(new ItemStack(Material.IRON_INGOT, 1 + (roll / 3) % 3));
        if (containerType == Material.BARREL) {
            loot.add(new ItemStack(Material.GLOW_INK_SAC, 1 + (roll / 9) % 2));
        }
        if ((roll / 18) % 10 < 3) {
            loot.add(new ItemStack(Material.EMERALD, 1 + (roll / 180) % 2));
        }
        if ((roll / 360) % 5 == 0) {
            loot.add(new ItemStack(Material.MAP, 1));
        }
        return loot;
    }
}

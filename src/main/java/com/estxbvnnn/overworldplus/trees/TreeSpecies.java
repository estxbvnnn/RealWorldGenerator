package com.estxbvnnn.overworldplus.trees;

import org.bukkit.Material;

/**
 * Every overworld tree species now maps to a set of real, hand-designed
 * schematic trees — no procedural vanilla-tree species is generated or
 * embellished anymore. {@code resourceKey} is the bundled resource file
 * name (trees/&lt;key&gt;_variants.txt.gz), extracted at build time from a
 * real tree-schematic pack (see tools/ in the repo).
 */
public enum TreeSpecies {
    OAK("oak", Material.OAK_LOG, Material.OAK_WOOD, Material.OAK_LEAVES),
    BIRCH("birch", Material.BIRCH_LOG, Material.BIRCH_WOOD, Material.BIRCH_LEAVES),
    SPRUCE("spruce", Material.SPRUCE_LOG, Material.SPRUCE_WOOD, Material.SPRUCE_LEAVES),
    JUNGLE("jungle", Material.JUNGLE_LOG, Material.JUNGLE_WOOD, Material.JUNGLE_LEAVES),
    ACACIA("acacia", Material.ACACIA_LOG, Material.ACACIA_WOOD, Material.ACACIA_LEAVES),
    DARK_OAK("dark_oak", Material.DARK_OAK_LOG, Material.DARK_OAK_WOOD, Material.DARK_OAK_LEAVES),
    MANGROVE("mangrove", Material.MANGROVE_LOG, Material.MANGROVE_WOOD, Material.MANGROVE_LEAVES),
    CHERRY("cherry", Material.CHERRY_LOG, Material.CHERRY_WOOD, Material.CHERRY_LEAVES),
    PALE_OAK("pale_oak", Material.PALE_OAK_LOG, Material.PALE_OAK_WOOD, Material.PALE_OAK_LEAVES);

    private final String resourceKey;
    private final Material log;
    private final Material wood;
    private final Material leaves;

    TreeSpecies(String resourceKey, Material log, Material wood, Material leaves) {
        this.resourceKey = resourceKey;
        this.log = log;
        this.wood = wood;
        this.leaves = leaves;
    }

    public static TreeSpecies forLog(Material logMaterial) {
        for (TreeSpecies species : values()) {
            if (species.log == logMaterial) return species;
        }
        return null;
    }

    public String resourceKey() {
        return resourceKey;
    }

    public Material log() {
        return log;
    }

    public Material wood() {
        return wood;
    }

    public Material leaves() {
        return leaves;
    }
}

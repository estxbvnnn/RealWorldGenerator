package com.estxbvnnn.overworldplus.trees;

import java.util.List;

/**
 * One real, hand-designed tree shape (extracted from a WorldEdit schematic
 * at build time — see tools/ in the repo), stored as block offsets relative
 * to its trunk base. Pasted verbatim: no procedural generation involved.
 */
public record SchematicTree(int height, int spread, List<BlockOffset> blocks) {

    public enum Type { LOG_X, LOG_Y, LOG_Z, WOOD_X, WOOD_Y, WOOD_Z, LEAVES, ROOTS }

    public record BlockOffset(int dx, int dy, int dz, Type type) {}
}

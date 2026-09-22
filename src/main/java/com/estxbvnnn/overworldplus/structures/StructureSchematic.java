package com.estxbvnnn.overworldplus.structures;

import java.util.List;

/**
 * A real hand-built structure, extracted once from a WorldEdit .schem at
 * build time (see tools/extract_lighthouse.py) into a flat list of exact
 * block states — no procedural fallback, no orientation guesswork: whatever
 * state the original block had (stairs facing, door hinge, chest half) is
 * baked into {@link BlockPlacement#data()} verbatim.
 */
public record StructureSchematic(List<BlockPlacement> blocks, List<int[]> containerOffsets) {

    /** Offset from the paste anchor, plus the exact blockstate string (no "minecraft:" prefix needed). */
    public record BlockPlacement(int dx, int dy, int dz, String data) {}
}

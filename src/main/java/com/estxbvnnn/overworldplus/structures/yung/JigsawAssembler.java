package com.estxbvnnn.overworldplus.structures.yung;

import com.estxbvnnn.overworldplus.structures.yung.JigsawLibrary.Element;
import com.estxbvnnn.overworldplus.structures.yung.JigsawLibrary.Jigsaw;
import com.estxbvnnn.overworldplus.structures.yung.JigsawLibrary.Pool;
import com.estxbvnnn.overworldplus.structures.yung.JigsawLibrary.Template;
import org.bukkit.block.BlockFace;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Lays out a whole structure from YUNG's pools the way the game's own jigsaw placement does it —
 * breadth first from the start piece, each open jigsaw trying the pieces of its pool in weighted
 * order, every rotation, every matching jigsaw of the candidate, keeping the first that fits —
 * plus what YUNG's jigsaw adds: a per-piece {@code max_count}, a {@code min_required_depth},
 * {@code is_priority} pieces tried first, and {@code ignore_bounds} pieces that may overlap.
 *
 * A piece whose connection point lies inside its parent (statues, props, wall panels) must fit
 * inside that parent and clear of the parent's other inner pieces; anything else must stay
 * within the structure's reach and clear of every other piece. Pure layout: no world access,
 * except asking a {@link Ground} (when given) how high a piece may go.
 */
final class JigsawAssembler {

    /** How high a piece's top may reach over a rectangle — e.g. keeps a stronghold underground. */
    interface Ground {
        int ceiling(int minX, int minZ, int maxX, int maxZ);
    }

    /** A structure's jigsaw settings, as in its worldgen/structure JSON. */
    record Spec(String startPool, String anchor, int maxDepth, int maxDistance, int minY, int maxY, String required) {}

    /** One placed piece: template, where its (0,0,0) goes, clockwise quarter turns, and its world box. */
    record Placed(Template template, int x, int y, int z, int rotation, String processors, String name, int depth, int[] box) {}

    private record Open(Placed piece, int depth, List<int[]> inner) {}

    private final JigsawLibrary library;
    private final Spec spec;
    private final Ground ground;

    JigsawAssembler(JigsawLibrary library, Spec spec, Ground ground) {
        this.library = library;
        this.spec = spec;
        this.ground = ground;
    }

    /**
     * Lays the structure out with its anchor jigsaw at (x, y, z) — or, without an anchor, the start
     * piece centred there. With {@code yIsBottom} the start piece's bottom goes at y instead (the
     * game's "project start to heightmap": YUNG's surface structures give y as ground + offset).
     * Tries a few seeds until the required piece (if any) made it in.
     */
    List<Placed> assemble(long seed, int x, int y, int z, boolean yIsBottom) {
        for (int attempt = 0; attempt < 24; attempt++) {
            List<Placed> layout = attempt(new Random(seed * 31 + attempt), x, y, z, yIsBottom);
            if (layout == null) continue;
            if (spec.required() == null || layout.stream().anyMatch(p -> spec.required().equals(p.name()))) return layout;
        }
        return null;
    }

    private List<Placed> attempt(Random random, int ax, int ay, int az, boolean yIsBottom) {
        Pool starts = library.pool(spec.startPool());
        if (starts == null) return null;
        List<Element> startChoices = weightedOrder(starts.elements(), random);
        Element startElement = startChoices.stream().filter(e -> !e.empty()).findFirst().orElse(null);
        if (startElement == null) return null;
        Template start = library.template(startElement.location());
        if (start == null) return null;

        int rotation = random.nextInt(4);
        Placed first;
        Jigsaw anchor = spec.anchor() == null ? null
                : start.jigsaws().stream().filter(j -> spec.anchor().equals(j.name())).findFirst().orElse(null);
        if (anchor != null) {
            int[] a = rotate(anchor.x(), anchor.z(), rotation);
            first = place(start, ax - a[0], yIsBottom ? ay : ay - anchor.y(), az - a[1], rotation, startElement, 0);
        } else {
            int[] c = rotate(start.sx() / 2, start.sz() / 2, rotation);
            first = place(start, ax - c[0], ay, az - c[1], rotation, startElement, 0);
        }

        List<Placed> placed = new ArrayList<>();
        List<int[]> outer = new ArrayList<>();
        Map<String, Integer> counts = new HashMap<>();
        placed.add(first);
        outer.add(first.box());
        if (startElement.name() != null) counts.merge(startElement.name(), 1, Integer::sum);
        Deque<Open> queue = new ArrayDeque<>();
        queue.add(new Open(first, 0, new ArrayList<>()));

        int d = spec.maxDistance();
        int[] reach = {ax - d, spec.minY(), az - d, ax + d, spec.maxY(), az + d};

        while (!queue.isEmpty()) {
            Open open = queue.poll();
            Placed parent = open.piece();
            List<Jigsaw> jigsaws = new ArrayList<>(parent.template().jigsaws());
            shuffle(jigsaws, random);
            for (Jigsaw jigsaw : jigsaws) {
                Pool pool = library.pool(jigsaw.pool());
                if (pool == null) continue; // minecraft:empty, or a pool the data never defines
                int[] jp = worldPos(parent, jigsaw.x(), jigsaw.y(), jigsaw.z());
                BlockFace front = rotate(jigsaw.front(), parent.rotation());
                BlockFace top = rotate(jigsaw.top(), parent.rotation());
                int tx = jp[0] + front.getModX(), ty = jp[1] + front.getModY(), tz = jp[2] + front.getModZ();
                boolean inside = contains(parent.box(), tx, ty, tz);

                for (Element element : candidates(pool, open.depth(), counts, random)) {
                    if (element.empty()) break; // vanilla: an empty entry ends the search for this jigsaw
                    Template template = library.template(element.location());
                    if (template == null) continue;
                    Placed child = fit(template, element, jigsaw.target(), front, top, jigsaw.rollable(), tx, ty, tz,
                            inside, parent, open.inner(), outer, reach, open.depth() + 1, random);
                    if (child == null) continue;
                    placed.add(child);
                    if (!element.ignoreBounds()) {
                        if (inside) open.inner().add(child.box()); else outer.add(child.box());
                    }
                    if (element.name() != null) counts.merge(element.name(), 1, Integer::sum);
                    if (open.depth() + 1 <= spec.maxDepth()) queue.add(new Open(child, open.depth() + 1, new ArrayList<>()));
                    break;
                }
            }
        }
        return placed;
    }

    /** Priority pieces first, then the pool in weighted order, then its fallback — or only the fallback at max depth. */
    private List<Element> candidates(Pool pool, int depth, Map<String, Integer> counts, Random random) {
        List<Element> list = new ArrayList<>();
        if (depth != spec.maxDepth()) {
            List<Element> ordered = weightedOrder(pool.elements(), random);
            for (Element e : ordered) if (e.priority() && allowed(e, depth + 1, counts)) list.add(e);
            for (Element e : ordered) if (!e.priority() && allowed(e, depth + 1, counts)) list.add(e);
        }
        Pool fallback = pool.fallback() == null ? null : library.pool(pool.fallback());
        if (fallback != null) {
            for (Element e : weightedOrder(fallback.elements(), random)) if (allowed(e, depth + 1, counts)) list.add(e);
        }
        return list;
    }

    private static boolean allowed(Element e, int depth, Map<String, Integer> counts) {
        if (e.name() != null && e.maxCount() > 0 && counts.getOrDefault(e.name(), 0) >= e.maxCount()) return false;
        return depth >= e.minDepth();
    }

    private Placed fit(Template template, Element element, String wantedName, BlockFace front, BlockFace top, boolean rollable,
                       int tx, int ty, int tz, boolean inside, Placed parent, List<int[]> inner, List<int[]> outer,
                       int[] reach, int depth, Random random) {
        int[] rotations = {0, 1, 2, 3};
        shuffle(rotations, random);
        for (int rotation : rotations) {
            List<Jigsaw> own = new ArrayList<>(template.jigsaws());
            shuffle(own, random);
            for (Jigsaw j : own) {
                if (!wantedName.equals(j.name())) continue;
                if (rotate(j.front(), rotation) != front.getOppositeFace()) continue;
                if (!rollable && rotate(j.top(), rotation) != top) continue;
                int[] r = rotate(j.x(), j.z(), rotation);
                Placed child = place(template, tx - r[0], ty - j.y(), tz - r[1], rotation, element, depth);
                int[] box = child.box();
                if (element.ignoreBounds()) {
                    if (!within(box, reach)) continue;
                } else if (inside) {
                    if (!within(box, parent.box()) || overlapsAny(box, inner)) continue;
                } else {
                    if (!within(box, reach) || overlapsAny(box, outer)) continue;
                    if (ground != null && box[4] > ground.ceiling(box[0], box[2], box[3], box[5])) continue;
                }
                return child;
            }
        }
        return null;
    }

    // ---- geometry: quarter turns clockwise about the template's (0,0,0), as the game places them ----

    static int[] rotate(int x, int z, int rotation) {
        return switch (rotation & 3) {
            case 1 -> new int[]{-z, x};
            case 2 -> new int[]{-x, -z};
            case 3 -> new int[]{z, -x};
            default -> new int[]{x, z};
        };
    }

    static BlockFace rotate(BlockFace face, int rotation) {
        BlockFace f = face;
        for (int i = 0; i < (rotation & 3); i++) {
            f = switch (f) {
                case NORTH -> BlockFace.EAST;
                case EAST -> BlockFace.SOUTH;
                case SOUTH -> BlockFace.WEST;
                case WEST -> BlockFace.NORTH;
                default -> f;
            };
        }
        return f;
    }

    static int[] worldPos(Placed p, int lx, int ly, int lz) {
        int[] r = rotate(lx, lz, p.rotation());
        return new int[]{p.x() + r[0], p.y() + ly, p.z() + r[1]};
    }

    private static Placed place(Template t, int x, int y, int z, int rotation, Element element, int depth) {
        int[] a = rotate(0, 0, rotation), b = rotate(t.sx() - 1, t.sz() - 1, rotation);
        int[] box = {x + Math.min(a[0], b[0]), y, z + Math.min(a[1], b[1]),
                x + Math.max(a[0], b[0]), y + t.sy() - 1, z + Math.max(a[1], b[1])};
        return new Placed(t, x, y, z, rotation, element.processors(), element.name(), depth, box);
    }

    private static boolean contains(int[] b, int x, int y, int z) {
        return x >= b[0] && x <= b[3] && y >= b[1] && y <= b[4] && z >= b[2] && z <= b[5];
    }

    private static boolean within(int[] inner, int[] outer) {
        return inner[0] >= outer[0] && inner[1] >= outer[1] && inner[2] >= outer[2]
                && inner[3] <= outer[3] && inner[4] <= outer[4] && inner[5] <= outer[5];
    }

    private static boolean overlapsAny(int[] box, List<int[]> others) {
        for (int[] o : others) {
            if (box[0] <= o[3] && box[3] >= o[0] && box[1] <= o[4] && box[4] >= o[1] && box[2] <= o[5] && box[5] >= o[2]) {
                return true;
            }
        }
        return false;
    }

    /** A weighted random order (each entry drawn in proportion to its weight), like the game's shuffled pool. */
    private static List<Element> weightedOrder(List<Element> elements, Random random) {
        List<double[]> keyed = new ArrayList<>();
        for (int i = 0; i < elements.size(); i++) {
            int w = Math.max(1, elements.get(i).weight());
            keyed.add(new double[]{Math.pow(random.nextDouble(), 1.0 / w), i});
        }
        keyed.sort(Comparator.comparingDouble((double[] k) -> -k[0]));
        List<Element> out = new ArrayList<>();
        for (double[] k : keyed) out.add(elements.get((int) k[1]));
        return out;
    }

    private static <T> void shuffle(List<T> list, Random random) {
        for (int i = list.size() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            T t = list.get(i);
            list.set(i, list.get(j));
            list.set(j, t);
        }
    }

    private static void shuffle(int[] a, Random random) {
        for (int i = a.length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int t = a[i];
            a[i] = a[j];
            a[j] = t;
        }
    }
}

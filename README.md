# OverworldPlus

**by [estxbvnnn](https://github.com/estxbvnnn)**

Real hand-designed trees instead of vanilla's blobs, ground that varies
the way real ground does, richer mountains, abandoned ruins in the wild —
all done **while each chunk generates**, so no player ever sees a vanilla
version first. No resource pack, no custom `ChunkGenerator`, no world
regeneration required, and it also picks up a world that existed before
the plugin.

## Build

Requires a JDK 21+ and Maven:

```
mvn clean package
```

The jar is written to `target/OverworldPlus.jar`.

## How it works

### Two paths, one pipeline

Every pass is written against a small block-access layer (`Area`) that
works on either backend, so the exact same code produces the exact same
result on both:

- **Generation time** (new chunks): a `BlockPopulator`, attached to every
  Overworld world in `WorldInitEvent` (the plugin loads at `STARTUP` so
  that fires before spawn-chunk generation too), runs the full pipeline on
  each chunk inside Paper's `LimitedRegion` — the chunk plus a buffer around
  it — on the generation worker threads, before the chunk is ever sent to a
  client. The chunk simply arrives finished. This is the path that matters:
  nobody watches trees vanish and reappear.
- **Load time** (chunks from before the plugin): the same pipeline runs
  once on the loaded chunk, tracked by a flag in the chunk's own NBT (not by
  "is this chunk new"). Work is queued on load and drained a few chunks per
  tick (`performance.chunks-per-tick`, much faster when nobody is online),
  and only once all eight neighbors are loaded — a canopy at a chunk edge
  reaches two chunks over, and writing into an unloaded chunk would force a
  synchronous load that can cascade at the exploration frontier. This is the
  only path where a player can see a change happen, and only on old chunks.

### Startup pre-generation

Right after vanilla's own "Preparing spawn area", the plugin pre-generates
(and so pre-enhances) `pregen.radius-chunks` in every direction around
spawn — asynchronously, a few chunks in flight at a time, with progress in
the console — and, if `pregen.block-joins-until-done` is on, nobody can
join until it's finished. The completed radius is remembered per world in
`pregen.yml`, so a restart doesn't redo it; raising the radius just does
the new ring. `/overworldplus pregen <radius> [world]` does the same on
demand, `pregen stop` cancels.

### Nothing is a dice roll

Where a feature has to vary from place to place — where each tree stands,
which chunk gets the waterfall, where the podzol falls — it's a fixed
function of the world seed and the coordinates (`CoordHash`, seeded
simplex noise). The same spot comes out the same way on every restart and
on either path.

### Trees

Vanilla's own trees are removed (mangrove swamps are left alone, and the
one-log jungle floor bushes stay), and real, hand-designed trees from a
bundled schematic pack (`trees/*.txt.gz`, nine species, 123 variants —
see `tools/extract_trees.py`) are pasted where a **grid** says: the world is
cut into cells (`trees.spacing`, 20 blocks; `trees.jungle-spacing`, 10 for
jungle), each cell owns one candidate spot jittered by a coordinate hash,
and that spot gets a tree if its biome wants one there (every cell in a
forest, one in ten on the plains, none in a desert) and the ground can
carry it. So spacing is guaranteed everywhere including across chunk
borders with no per-chunk bookkeeping, and a chunk can work out exactly
which trees its neighbors have — same inputs, same answer.

That last point is what fixes "trees with no trunk": removal walks a
vanilla tree's connected logs out from its base and never steps onto a
block that belongs to a neighbor's big tree, and never into a neighbor
chunk that's already finished. Pasted leaves are all persistent, so leaf
clearing (natural, non-persistent only) can't touch a big canopy either.

Base blocks left hanging over a slope get propped up with rooted dirt
(up to 4 deep; steeper spots get no tree) — a tree on a hillside grips the
ground instead of floating. Foliage that dips below trunk level never
replaces solid ground. A fixed 1-in-5 leaf position is skipped per tree
for render cost; trunk and branch wood never is.

Around each tree: a fallen log (moss/mushroom on top) where the ground is
flat, two low shrubs of the tree's own leaves, ferns/grass/mushrooms by
species — and in the jungle, vines trailing from the canopy edges, hung the
way vanilla hangs them (top vine attached to a real leaf block, strand
below it), into columns of air only.

### Terrain

The ground itself, per column, from seeded noise: coherent patches of
podzol (conifer/forest), moss (jungle, dark forest, swamp — the jungle
floor is mostly moss and litter), coarse dirt (plains, savanna), and bare
rock/andesite/gravel outcrops shouldering through the grass; slopes too
steep to hold soil turn to stone (mossy where wet); gravel/sand banks
where grass meets water; andesite, tuff and scree on exposed mountain
stone; sandstone in deserts, gravel on beaches. Vegetation thickets
(grass, ferns, biome-appropriate flowers, melons in the jungle) fall where
a separate noise field says. Never changes terrain height; on the load
path it stays out of every vanilla structure's bounding box.

### Mountains, biomes, structures, ruins

Windswept/peak/grove/meadow biomes get denser snow, up to two boulder
outcrops per chunk, and a waterfall off a real cliff edge in one chunk out
of `waterfall-every-n-chunks` (each one is a flowing water source that
ticks forever — the old "every sample" rule could stack five in a chunk).
A rare rock spire landmark can appear. Every other land biome — deserts,
badlands, snowy plains, mushroom fields, cherry groves, jungle floors,
swamps (lily pads on the water), lush and dripstone caves — gets its own
ground decoration. Villages, strongholds and ruined portals (via the real
`Chunk#getStructures()` registry) get exterior flowers/lanterns, cracked
and mossy stone, overgrowth and debris — on the load path, since the
structure registry needs a loaded chunk. An abandoned house — a real
hand-built ancient-lighthouse schematic (`structures/ancient_lighthouse.txt.gz`,
`tools/extract_lighthouse.py`), pasted block-for-block with loot in its
chests — turns up in one eligible chunk in ~100, spaced 220 apart. Ids the
schematic uses that this server version renamed are translated
(`grass` → `short_grass`); anything unknown skips that one block, never the
building.

### Cleaning up after older versions

Chunks enhanced by earlier versions of this plugin may hold vines hanging
from nothing (from systems since removed). The first time such a chunk
loads, a one-off sweep removes every vine that isn't attached the way
vanilla requires (a face against a solid block, or hanging from a vine
above); natural jungle/swamp vines and ruined-portal vines pass untouched.
`/overworldplus cleanvines [radius]` does the same around you on demand.

## Important: this modifies the world permanently

Every change is a real block placement, saved to disk like any other part
of the world — not something that reverts if the plugin is removed.

## Commands

```
/overworldplus reload
/overworldplus stats
/overworldplus pregen <radius-in-chunks> [world]
/overworldplus pregen stop
/overworldplus testtree [species]
/overworldplus testhouse [world x z]
/overworldplus cleanvines [radius]
```
`stats` shows chunks enhanced / trees placed / vanilla trees removed this
session plus pre-generation progress. `testtree` pastes a tree at your
feet, `testhouse` the lighthouse (from console: with world/x/z). Needs
`overworldplus.admin` (default: op).

## Compatibility

Built against the current Paper API (26.1.2), using registry-based
`Biome`/`Material`/BlockData/`Structure` APIs current to this Paper version.

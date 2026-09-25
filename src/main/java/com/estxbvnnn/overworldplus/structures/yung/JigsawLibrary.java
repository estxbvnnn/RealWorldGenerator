package com.estxbvnnn.overworldplus.structures.yung;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Logger;

/**
 * One of YUNG's structure mods (all LGPL-3.0, by YUNGNICKYOUNG — licences under {@code yung/} in
 * the plugin jar), read straight from its own data under {@code yung/<namespace>/}: structure
 * templates, template pools, processor lists and chest loot tables. Nothing here touches a world.
 */
final class JigsawLibrary {

    /** A jigsaw block, as the template stores it (unrotated). */
    record Jigsaw(int x, int y, int z, BlockFace front, BlockFace top, String name, String target, String pool,
                  String finalState, boolean rollable) {}

    /** A block a processor finishes after placing (legs and pillars down to the ground, redstone). */
    record Marker(int x, int y, int z, Material type) {}

    record Template(String id, byte[] bytes, int sx, int sy, int sz, List<Jigsaw> jigsaws, List<Marker> markers, int blockCount) {}

    record Element(String location, int weight, String processors, String name, int maxCount, int minDepth,
                   boolean priority, boolean ignoreBounds) {
        boolean empty() {
            return location == null;
        }
    }

    record Pool(String id, List<Element> elements, String fallback) {}

    /** Blocks that some processor extends downwards or supports once everything is in place. */
    private static final Set<String> MARKERS = Set.of(
            "minecraft:yellow_stained_glass", "minecraft:orange_stained_glass", "minecraft:brown_stained_glass",
            "minecraft:gray_stained_glass", "minecraft:blue_stained_glass", "minecraft:crimson_fence", "minecraft:redstone_wire");

    final String namespace;
    private final Map<String, Template> templates = new HashMap<>();
    private final Map<String, Pool> pools = new HashMap<>();
    private final Map<String, List<JsonObject>> processorLists = new HashMap<>();
    private final Map<String, byte[]> lootTables = new HashMap<>();

    JigsawLibrary(File pluginJar, String namespace, Logger logger) {
        this.namespace = namespace;
        String root = "yung/" + namespace + "/";
        try (JarFile jar = new JarFile(pluginJar)) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String path = entry.getName();
                if (entry.isDirectory() || !path.startsWith(root)) continue;
                String rest = path.substring(root.length());
                byte[] bytes;
                try (InputStream in = jar.getInputStream(entry)) {
                    bytes = in.readAllBytes();
                }
                if (rest.startsWith("structure/") && rest.endsWith(".nbt")) {
                    String id = namespace + ":" + rest.substring("structure/".length(), rest.length() - 4);
                    templates.put(id, template(id, bytes));
                } else if (rest.startsWith("worldgen/template_pool/")) {
                    String id = namespace + ":" + rest.substring("worldgen/template_pool/".length(), rest.length() - 5);
                    pools.put(id, pool(id, json(bytes)));
                } else if (rest.startsWith("worldgen/processor_list/")) {
                    String id = namespace + ":" + rest.substring("worldgen/processor_list/".length(), rest.length() - 5);
                    List<JsonObject> list = new ArrayList<>();
                    for (JsonElement e : json(bytes).getAsJsonArray("processors")) list.add(e.getAsJsonObject());
                    processorLists.put(id, List.copyOf(list));
                } else if (rest.startsWith("loot_table/")) {
                    lootTables.put(rest, bytes);
                }
            }
        } catch (IOException e) {
            logger.severe("Couldn't read " + namespace + " from the plugin jar: " + e.getMessage());
        }
    }

    boolean isLoaded() {
        return !templates.isEmpty() && !pools.isEmpty();
    }

    Template template(String id) {
        return templates.get(id);
    }

    Pool pool(String id) {
        return pools.get(id);
    }

    List<JsonObject> processorList(String id) {
        return id == null ? List.of() : processorLists.getOrDefault(id, List.of());
    }

    Map<String, byte[]> lootTables() {
        return lootTables;
    }

    String summary() {
        return templates.size() + " pieces, " + pools.size() + " pools, " + processorLists.size() + " processor lists";
    }

    // ---- parsing ----

    static JsonObject json(byte[] bytes) {
        return JsonParser.parseReader(new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static Template template(String id, byte[] bytes) throws IOException {
        Map<String, Object> root = Nbt.readGzipped(new ByteArrayInputStream(bytes));
        List<Object> size = Nbt.list(root.get("size"));
        List<Object> palette = Nbt.list(root.get("palette"));
        List<Jigsaw> jigsaws = new ArrayList<>();
        List<Marker> markers = new ArrayList<>();
        int blockCount = 0;
        for (Object o : Nbt.list(root.get("blocks"))) {
            Map<String, Object> block = Nbt.compound(o);
            Map<String, Object> state = Nbt.compound(palette.get(Nbt.integer(block.get("state"))));
            String name = Nbt.string(state.get("Name"));
            List<Object> pos = Nbt.list(block.get("pos"));
            int x = Nbt.integer(pos.get(0)), y = Nbt.integer(pos.get(1)), z = Nbt.integer(pos.get(2));
            blockCount++;
            if ("minecraft:jigsaw".equals(name)) {
                Map<String, Object> nbt = Nbt.compound(block.get("nbt"));
                String orientation = Nbt.string(Nbt.compound(state.get("Properties")).get("orientation"));
                String[] faces = orientation == null ? new String[]{"north", "up"} : orientation.split("_");
                jigsaws.add(new Jigsaw(x, y, z, face(faces[0]), face(faces[1]),
                        Nbt.string(nbt.get("name")), Nbt.string(nbt.get("target")), Nbt.string(nbt.get("pool")),
                        Nbt.string(nbt.get("final_state")), !"aligned".equals(Nbt.string(nbt.get("joint")))));
            } else if (name != null && MARKERS.contains(name)) {
                Material type = Material.matchMaterial(name);
                if (type != null) markers.add(new Marker(x, y, z, type));
            }
        }
        return new Template(id, bytes, Nbt.integer(size.get(0)), Nbt.integer(size.get(1)), Nbt.integer(size.get(2)),
                List.copyOf(jigsaws), List.copyOf(markers), blockCount);
    }

    private static BlockFace face(String name) {
        return BlockFace.valueOf(name.toUpperCase());
    }

    private static Pool pool(String id, JsonObject json) {
        List<Element> elements = new ArrayList<>();
        for (JsonElement e : json.getAsJsonArray("elements")) {
            JsonObject entry = e.getAsJsonObject();
            JsonObject el = entry.getAsJsonObject("element");
            String type = str(el, "element_type");
            int weight = entry.has("weight") ? entry.get("weight").getAsInt() : 1;
            // YUNG's conditions here only switch on pieces for other mods (Create, Supplementaries,
            // Alex's Mobs) — never true on a Paper server, so those pieces are simply left out.
            if (el.has("condition")) continue;
            if (type != null && type.endsWith("empty_pool_element")) {
                elements.add(new Element(null, weight, null, null, 0, 0, false, false));
                continue;
            }
            elements.add(new Element(str(el, "location"), weight, str(el, "processors"), str(el, "name"),
                    el.has("max_count") ? el.get("max_count").getAsInt() : 0,
                    el.has("min_required_depth") ? el.get("min_required_depth").getAsInt() : 0,
                    el.has("is_priority") && el.get("is_priority").getAsBoolean(),
                    el.has("ignore_bounds") && el.get("ignore_bounds").getAsBoolean()));
        }
        return new Pool(id, List.copyOf(elements), str(json, "fallback"));
    }

    static String str(JsonObject o, String key) {
        return o != null && o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : null;
    }
}

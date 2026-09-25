package com.estxbvnnn.overworldplus.structures.yung;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Just enough of the NBT format to read a structure template's layout (size, jigsaw blocks,
 * marker blocks). Compounds become maps, lists become lists, numbers stay numbers. Placing the
 * template is left to the server's own structure loader, which also upgrades it.
 */
final class Nbt {

    private Nbt() {}

    @SuppressWarnings("unchecked")
    static Map<String, Object> readGzipped(InputStream in) throws IOException {
        try (DataInputStream data = new DataInputStream(new GZIPInputStream(in))) {
            byte type = data.readByte();
            if (type != 10) throw new IOException("Not an NBT compound");
            data.readUTF(); // root name
            return (Map<String, Object>) payload(data, type);
        }
    }

    private static Object payload(DataInputStream in, byte type) throws IOException {
        return switch (type) {
            case 1 -> in.readByte();
            case 2 -> in.readShort();
            case 3 -> in.readInt();
            case 4 -> in.readLong();
            case 5 -> in.readFloat();
            case 6 -> in.readDouble();
            case 7 -> {
                byte[] bytes = new byte[in.readInt()];
                in.readFully(bytes);
                yield bytes;
            }
            case 8 -> in.readUTF(); // NBT strings are Java's modified UTF-8, which is exactly this
            case 9 -> {
                byte itemType = in.readByte();
                int length = in.readInt();
                List<Object> list = new ArrayList<>(Math.max(0, length));
                for (int i = 0; i < length; i++) list.add(payload(in, itemType));
                yield list;
            }
            case 10 -> {
                Map<String, Object> compound = new LinkedHashMap<>();
                while (true) {
                    byte childType = in.readByte();
                    if (childType == 0) break;
                    String name = in.readUTF();
                    compound.put(name, payload(in, childType));
                }
                yield compound;
            }
            case 11 -> {
                int[] ints = new int[in.readInt()];
                for (int i = 0; i < ints.length; i++) ints[i] = in.readInt();
                yield ints;
            }
            case 12 -> {
                long[] longs = new long[in.readInt()];
                for (int i = 0; i < longs.length; i++) longs[i] = in.readLong();
                yield longs;
            }
            default -> throw new IOException("Unknown NBT tag type " + type);
        };
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> compound(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(Object o) {
        return o instanceof List<?> l ? (List<Object>) l : List.of();
    }

    static String string(Object o) {
        return o instanceof String s ? s : null;
    }

    static int integer(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }
}

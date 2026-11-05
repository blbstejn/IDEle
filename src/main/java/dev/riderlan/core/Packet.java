package dev.riderlan.core;

import java.util.Arrays;
import java.util.List;

/** Immutable application protocol message. Field lengths and count are enforced by PacketCodec. */
public record Packet(String type, List<String> fields) {
    public Packet {
        if (type == null || type.isBlank()) throw new IllegalArgumentException("Missing packet type");
        fields = List.copyOf(fields);
    }

    public static Packet of(String type, String... fields) {
        return new Packet(type, Arrays.asList(fields));
    }

    public String get(int index) {
        return fields.get(index);
    }

    public boolean has(int count) {
        return fields.size() == count;
    }
}

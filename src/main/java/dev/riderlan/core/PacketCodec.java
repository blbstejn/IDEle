package dev.riderlan.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/** Bounded plaintext packet serialization, used only *inside* encrypted v2 transport frames. */
public final class PacketCodec {
    public static final int MAX_FRAME = 16 * 1024;
    // The roster requires 2 fields per participant, i.e. 32 for a 16-person room.
    private static final int MAX_FIELDS = 64;
    private static final int MAX_FIELD_CHARS = 4096;

    private PacketCodec() { }

    public static void write(OutputStream output, Packet packet) throws IOException {
        if (packet.fields().size() > MAX_FIELDS || packet.type().length() > 64) {
            throw new IOException("Invalid outgoing packet");
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream body = new DataOutputStream(buffer);
        body.writeUTF(packet.type());
        body.writeByte(packet.fields().size());
        for (String value : packet.fields()) {
            if (value.length() > MAX_FIELD_CHARS) throw new IOException("Field is too long");
            body.writeUTF(value);
        }
        body.flush();
        byte[] frame = buffer.toByteArray();
        if (frame.length > MAX_FRAME) throw new IOException("Packet is too large");
        DataOutputStream writer = new DataOutputStream(output);
        writer.writeInt(frame.length);
        writer.write(frame);
        writer.flush();
    }

    /** Returns null only when the socket was cleanly closed before the next frame. */
    public static Packet read(InputStream input) throws IOException {
        // Only EOF *before the first header byte* is a clean socket shutdown.
        // A partial 1–3-byte header must instead be reported as a truncated frame.
        int first = input.read();
        if (first == -1) return null;
        DataInputStream reader = new DataInputStream(input);
        int length = (first << 24) | (reader.readUnsignedByte() << 16)
                | (reader.readUnsignedByte() << 8) | reader.readUnsignedByte();
        if (length < 4 || length > MAX_FRAME) throw new IOException("Invalid frame length");
        byte[] bytes = new byte[length];
        reader.readFully(bytes);
        DataInputStream body = new DataInputStream(new ByteArrayInputStream(bytes));
        String type = body.readUTF();
        int size = body.readUnsignedByte();
        if (type.isEmpty() || type.length() > 64 || size > MAX_FIELDS) {
            throw new IOException("Malformed packet header");
        }
        List<String> fields = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            String value = body.readUTF();
            if (value.length() > MAX_FIELD_CHARS) throw new IOException("Field is too long");
            fields.add(value);
        }
        if (body.available() != 0) throw new IOException("Unexpected packet data");
        return new Packet(type, fields);
    }
}

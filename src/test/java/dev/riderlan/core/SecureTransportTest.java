package dev.riderlan.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Ciphertext inspection and tamper detection with a real two-way handshake. */
final class SecureTransportTest {
    private static int assertions;

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }

    private static final class WireTap extends OutputStream {
        private final OutputStream output;
        private final ByteArrayOutputStream observed = new ByteArrayOutputStream();
        private boolean tamperNext;

        WireTap(OutputStream output) { this.output = output; }

        void tamperNextFrame() { tamperNext = true; }
        byte[] recordedBytes() { return observed.toByteArray(); }
        @Override public void write(int b) throws IOException {
            observed.write(b);
            output.write(b);
        }
        @Override public void write(byte[] buffer, int offset, int length) throws IOException {
            byte[] copy = Arrays.copyOfRange(buffer, offset, offset + length);
            if (tamperNext && length > 6) {
                // Leave the length prefix intact; flip an encrypted payload bit.
                copy[4] ^= 0x40;
                tamperNext = false;
            }
            observed.write(copy);
            output.write(copy);
        }
        @Override public void flush() throws IOException { output.flush(); }
        @Override public void close() throws IOException { output.close(); }
    }

    static void run() throws Exception {
        String key = SecureTransport.generateSessionKey();
        PipedInputStream hostInput = new PipedInputStream(65536);
        PipedOutputStream rawClientOutput = new PipedOutputStream(hostInput);
        WireTap clientWire = new WireTap(rawClientOutput);
        PipedInputStream clientInput = new PipedInputStream(65536);
        PipedOutputStream hostOutput = new PipedOutputStream(clientInput);
        try {
            String confidential = "only-ciphertext-should-cross-the-wire-9f13c5c2";
            FutureTask<Boolean> accept = new FutureTask<>(() -> {
                SecureTransport host = SecureTransport.host(hostInput, hostOutput, key);
                Packet received = host.read();
                if (received == null || !confidential.equals(received.get(0))) {
                    throw new AssertionError("Decryption failed after handshake");
                }
                try {
                    host.read();
                    throw new AssertionError("A modified encrypted packet was accepted");
                } catch (IOException expected) {
                    return expected.getMessage().contains("authentication");
                }
            });
            Thread server = new Thread(accept, "IDEle-security-handshake-test");
            server.setDaemon(true);
            server.start();
            SecureTransport client = SecureTransport.client(clientInput, clientWire, key);
            client.write(Packet.of("CHAT", confidential));
            check(!new String(clientWire.recordedBytes(), StandardCharsets.ISO_8859_1).contains(confidential),
                    "Confidential text was exposed on the wire");

            clientWire.tamperNextFrame();
            client.write(Packet.of("CHAT", "tamper-test"));
            check(accept.get(5, TimeUnit.SECONDS), "Modified frame was not rejected by AEAD authentication");
        } finally {
            clientWire.close();
            hostOutput.close();
            clientInput.close();
            hostInput.close();
        }
        System.out.println("PASS - " + assertions + " transport integrity/confidentiality assertions");
    }
}

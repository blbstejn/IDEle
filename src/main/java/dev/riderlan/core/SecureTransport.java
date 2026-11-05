package dev.riderlan.core;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;

/**
 * IDEle v2 authenticated encrypted transport. A fresh X25519 key exchange and
 * a randomly generated 256-bit, out-of-band session key authenticate both sides.
 * ALL application packets, including usernames, are AES-256-GCM protected.
 *
 * A bespoke protocol needs independent review before use with high-value secrets.
 * This transport encrypts connections TO THE HOST, not user-to-user E2EE;
 * the host can read private messages and every endpoint sees its peer IP.
 */
public final class SecureTransport {
    private static final String HMAC = "HmacSHA256";
    private static final String CIPHER = "AES/GCM/NoPadding";
    private static final byte[] DOMAIN = "IDEle authenticated transport v2".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] AAD = "IDEle-v2 encrypted frame".getBytes(StandardCharsets.US_ASCII);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 16;
    private static final int TAG_BYTES = 16;
    private static final int MAX_ENCRYPTED_FRAME = PacketCodec.MAX_FRAME + 4 + TAG_BYTES;

    private final InputStream input;
    private final OutputStream output;
    private final byte[] sendingKey;
    private final byte[] receivingKey;
    private final byte[] sendingPrefix;
    private final byte[] receivingPrefix;
    private long sent;
    private long received;

    private SecureTransport(InputStream input, OutputStream output, byte[] keyMaterial, byte[] transcript,
                            boolean hosting) throws GeneralSecurityException {
        this.input = input;
        this.output = output;
        // Bind derived keys to the shared session key, the ephemeral DH secret,
        // transcript, protocol and direction. Never reuse an AEAD key/nonce pair.
        byte[] extract = hmac(keyMaterial, transcript);
        byte[] c2s = hmac(extract, label("client-to-host AES key"));
        byte[] s2c = hmac(extract, label("host-to-client AES key"));
        byte[] c2sPrefix = hmac(extract, label("client-to-host nonce"));
        byte[] s2cPrefix = hmac(extract, label("host-to-client nonce"));
        sendingKey = hosting ? s2c : c2s;
        receivingKey = hosting ? c2s : s2c;
        sendingPrefix = Arrays.copyOf(hosting ? s2cPrefix : c2sPrefix, 4);
        receivingPrefix = Arrays.copyOf(hosting ? c2sPrefix : s2cPrefix, 4);
        Arrays.fill(extract, (byte) 0);
    }

    /** Each new hosted session MUST use a fresh, high-entropy random key. */
    public static String generateSessionKey() {
        byte[] bytes = new byte[KEY_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Only 256-bit random base64url keys are accepted (not guessable passwords). */
    public static String validateSessionKey(String text) {
        String key = text == null ? "" : text.strip();
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(key);
            if (bytes.length == KEY_BYTES && key.equals(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes))) {
                return key;
            }
        } catch (IllegalArgumentException ignored) { }
        throw new IllegalArgumentException("Paste the 43-character session key provided by the host");
    }

    public static SecureTransport client(Socket socket, String sessionKey) throws IOException {
        return client(socket.getInputStream(), socket.getOutputStream(), sessionKey);
    }

    /** Package-private stream entry point for byte-level tampering regression tests. */
    static SecureTransport client(InputStream rawInput, OutputStream rawOutput, String sessionKey) throws IOException {
        byte[] secret = decodeKey(sessionKey);
        InputStream input = new BufferedInputStream(rawInput);
        OutputStream output = new BufferedOutputStream(rawOutput);
        try {
            KeyPair pair = generatePair();
            byte[] clientNonce = randomNonce();
            byte[] clientPub = pair.getPublic().getEncoded();
            PacketCodec.write(output, Packet.of("KEY_HELLO", Rules.PROTOCOL, b64(clientPub), b64(clientNonce)));
            Packet challenge = PacketCodec.read(input);
            if (challenge == null || !"KEY_CHALLENGE".equals(challenge.type()) || !challenge.has(4)
                    || !Rules.PROTOCOL.equals(challenge.get(0))) {
                throw new IOException("Host does not support IDEle encrypted protocol v2");
            }
            byte[] serverPub = unb64(challenge.get(1), 44, 64);
            byte[] serverNonce = unb64(challenge.get(2), NONCE_BYTES, NONCE_BYTES);
            byte[] digest = transcript(clientPub, serverPub, clientNonce, serverNonce);
            if (!MessageDigest.isEqual(hmac(secret, authMessage("host", digest)), unb64(challenge.get(3), 32, 32))) {
                throw new IOException("The host could not authenticate. Check the session key.");
            }
            PacketCodec.write(output, Packet.of("KEY_AUTH", b64(hmac(secret, authMessage("client", digest)))));
            return create(input, output, pair, serverPub, secret, digest, false);
        } catch (GeneralSecurityException | IllegalArgumentException ex) {
            throw new IOException("Could not establish an encrypted connection", ex);
        } finally {
            Arrays.fill(secret, (byte) 0);
        }
    }

    public static SecureTransport host(Socket socket, String sessionKey) throws IOException {
        return host(socket.getInputStream(), socket.getOutputStream(), sessionKey);
    }

    static SecureTransport host(InputStream rawInput, OutputStream rawOutput, String sessionKey) throws IOException {
        byte[] secret = decodeKey(sessionKey);
        InputStream input = new BufferedInputStream(rawInput);
        OutputStream output = new BufferedOutputStream(rawOutput);
        try {
            Packet hello = PacketCodec.read(input);
            if (hello == null || !"KEY_HELLO".equals(hello.type()) || !hello.has(3)
                    || !Rules.PROTOCOL.equals(hello.get(0))) {
                throw new IOException("Peer uses an incompatible or unencrypted IDEle protocol");
            }
            byte[] clientPub = unb64(hello.get(1), 44, 64);
            byte[] clientNonce = unb64(hello.get(2), NONCE_BYTES, NONCE_BYTES);
            KeyPair pair = generatePair();
            byte[] serverPub = pair.getPublic().getEncoded();
            byte[] serverNonce = randomNonce();
            byte[] digest = transcript(clientPub, serverPub, clientNonce, serverNonce);
            PacketCodec.write(output, Packet.of("KEY_CHALLENGE", Rules.PROTOCOL,
                    b64(serverPub), b64(serverNonce), b64(hmac(secret, authMessage("host", digest)))));
            Packet auth = PacketCodec.read(input);
            if (auth == null || !"KEY_AUTH".equals(auth.type()) || !auth.has(1)
                    || !MessageDigest.isEqual(hmac(secret, authMessage("client", digest)),
                            unb64(auth.get(0), 32, 32))) {
                throw new IOException("Client could not authenticate the shared session key");
            }
            return create(input, output, pair, clientPub, secret, digest, true);
        } catch (GeneralSecurityException | IllegalArgumentException ex) {
            throw new IOException("Could not establish an encrypted connection", ex);
        } finally {
            Arrays.fill(secret, (byte) 0);
        }
    }

    private static SecureTransport create(InputStream input, OutputStream output, KeyPair local,
                                          byte[] remoteEncoded, byte[] secret, byte[] digest, boolean host)
            throws GeneralSecurityException {
        PublicKey remote = KeyFactory.getInstance("X25519")
                .generatePublic(new X509EncodedKeySpec(remoteEncoded));
        KeyAgreement agreement = KeyAgreement.getInstance("X25519");
        agreement.init(local.getPrivate());
        agreement.doPhase(remote, true);
        byte[] shared = agreement.generateSecret();
        // Extract using the session key and ephemeral DH result: knowing an intercepted
        // handshake and the transcript does not reveal transport keys.
        byte[] keyMaterial = hmac(secret, shared);
        Arrays.fill(shared, (byte) 0);
        try { return new SecureTransport(input, output, keyMaterial, digest, host); }
        finally { Arrays.fill(keyMaterial, (byte) 0); }
    }

    public synchronized void write(Packet packet) throws IOException {
        try {
            if (sent == Long.MAX_VALUE) throw new IOException("Encrypted sequence exhausted");
            ByteArrayOutputStream plain = new ByteArrayOutputStream();
            PacketCodec.write(plain, packet);
            byte[] payload = crypt(Cipher.ENCRYPT_MODE, sendingKey, nonce(sendingPrefix, sent), plain.toByteArray());
            if (payload.length > MAX_ENCRYPTED_FRAME) throw new IOException("Encrypted packet too large");
            DataOutputStream writer = new DataOutputStream(output);
            writer.writeInt(payload.length);
            writer.write(payload);
            writer.flush();
            sent++;
        } catch (GeneralSecurityException ex) {
            throw new IOException("Encryption failed", ex);
        }
    }

    /** Receiver must only be called by a single thread per socket. */
    public Packet read() throws IOException {
        try {
            if (received == Long.MAX_VALUE) throw new IOException("Encrypted sequence exhausted");
            int first = input.read();
            if (first < 0) return null;
            DataInputStream reader = new DataInputStream(input);
            int length = (first << 24) | (reader.readUnsignedByte() << 16)
                    | (reader.readUnsignedByte() << 8) | reader.readUnsignedByte();
            if (length < TAG_BYTES || length > MAX_ENCRYPTED_FRAME) {
                throw new IOException("Invalid encrypted frame length");
            }
            byte[] encrypted = new byte[length];
            reader.readFully(encrypted);
            byte[] plain = crypt(Cipher.DECRYPT_MODE, receivingKey, nonce(receivingPrefix, received), encrypted);
            received++;
            ByteArrayInputStream body = new ByteArrayInputStream(plain);
            Packet packet = PacketCodec.read(body);
            if (packet == null || body.available() != 0) throw new IOException("Malformed encrypted packet");
            return packet;
        } catch (AEADBadTagException ex) {
            throw new IOException("Encrypted frame authentication failed", ex);
        } catch (GeneralSecurityException ex) {
            throw new IOException("Decryption failed", ex);
        } catch (EOFException ex) {
            throw new IOException("Incomplete encrypted packet", ex);
        }
    }

    private static byte[] crypt(int mode, byte[] key, byte[] nonce, byte[] data) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(CIPHER);
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(AAD);
        return cipher.doFinal(data);
    }

    private static byte[] nonce(byte[] prefix, long index) {
        return ByteBuffer.allocate(12).put(prefix).putLong(index).array();
    }

    private static byte[] transcript(byte[] clientPub, byte[] hostPub, byte[] clientNonce, byte[] hostNonce)
            throws GeneralSecurityException {
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        hash.update(DOMAIN);
        hash.update(clientPub);
        hash.update(hostPub);
        hash.update(clientNonce);
        hash.update(hostNonce);
        return hash.digest();
    }

    private static byte[] authMessage(String role, byte[] transcript) {
        byte[] label = label("authenticated-" + role);
        byte[] content = new byte[label.length + transcript.length];
        System.arraycopy(label, 0, content, 0, label.length);
        System.arraycopy(transcript, 0, content, label.length, transcript.length);
        return content;
    }

    private static byte[] label(String text) { return ("IDEle/v2/" + text).getBytes(StandardCharsets.US_ASCII); }

    private static byte[] hmac(byte[] key, byte[] message) throws GeneralSecurityException {
        Mac mac = Mac.getInstance(HMAC);
        mac.init(new SecretKeySpec(key, HMAC));
        return mac.doFinal(message);
    }

    private static KeyPair generatePair() throws GeneralSecurityException {
        return KeyPairGenerator.getInstance("X25519").generateKeyPair();
    }

    private static byte[] randomNonce() {
        byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        return nonce;
    }

    private static String b64(byte[] value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(value); }

    private static byte[] unb64(String text, int minLength, int maxLength) throws IOException {
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(text);
            if (bytes.length < minLength || bytes.length > maxLength) throw new IOException("Invalid handshake field size");
            return bytes;
        } catch (IllegalArgumentException ex) {
            throw new IOException("Malformed handshake encoding", ex);
        }
    }

    private static byte[] decodeKey(String key) {
        return Base64.getUrlDecoder().decode(validateSessionKey(key));
    }
}

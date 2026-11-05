package dev.riderlan.core;

import dev.riderlan.game.GameRegistry;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Thin encrypted LAN client. Network reads AND writes run off Rider's Swing EDT.
 * Outgoing traffic is bounded so an unresponsive host cannot freeze the editor
 * or make a client's queue grow without limit.
 */
public final class ClientSession implements Session {
    private static final int MAX_OUTGOING = 128;

    private final SessionListener listener;
    private final Socket socket;
    private final SecureTransport transport;
    private final ArrayBlockingQueue<Packet> outgoing = new ArrayBlockingQueue<>(MAX_OUTGOING);
    private final Thread writer;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean receiving = new AtomicBoolean();
    private volatile String selfId = "";
    private volatile String closeReason = "Disconnected";

    public ClientSession(String username, String hostname, int port, String sessionKey,
                         SessionListener listener) throws IOException {
        this(username, hostname, port, sessionKey, listener, true);
    }

    /** The caller may wait to attach Swing listeners before receiving packets. */
    public ClientSession(String username, String hostname, int port, String sessionKey,
                         SessionListener listener, boolean startImmediately) throws IOException {
        this(new Socket(), username, hostname, port, sessionKey, listener, startImmediately);
    }

    /**
     * Allows a background connection attempt to be cancelled by closing its socket
     * from the UI. The caller must not reuse the supplied socket after this call.
     */
    public ClientSession(Socket connectionSocket, String username, String hostname, int port,
                         String sessionKey, SessionListener listener,
                         boolean startImmediately) throws IOException {
        String cleanName = Rules.username(username);
        if (hostname == null || hostname.isBlank()) throw new IllegalArgumentException("Enter the host IP or DNS name");
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid TCP port");
        String key = SecureTransport.validateSessionKey(sessionKey);
        this.listener = Objects.requireNonNull(listener, "listener");
        this.socket = Objects.requireNonNull(connectionSocket, "connectionSocket");
        try {
            socket.connect(new InetSocketAddress(hostname.strip(), port), 5000);
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);
            socket.setSoTimeout(15000);
            transport = SecureTransport.client(socket, key);
            // The only synchronous application write is on the calling worker,
            // before the ClientSession constructor returns and before it reaches the EDT.
            // The host only offers games that every player in a match has installed.
            transport.write(Packet.of("HELLO", cleanName, Rules.PROTOCOL,
                    String.join(",", GameRegistry.installed().ids())));
            socket.setSoTimeout(0);
            writer = new Thread(this::writeLoop, "IDEle-client-writer");
            writer.setDaemon(true);
            writer.start();
            if (startImmediately) startReceiving();
        } catch (IOException | RuntimeException failure) {
            try { socket.close(); } catch (IOException ignored) { }
            throw failure;
        }
    }

    /** Starts the receiver at most once; safe to call after the session was closed. */
    public void startReceiving() {
        if (closed.get() || !receiving.compareAndSet(false, true)) return;
        Thread receiver = new Thread(this::readLoop, "IDEle-client-receiver");
        receiver.setDaemon(true);
        receiver.start();
    }

    @Override public String selfId() { return selfId; }
    @Override public boolean hosting() { return false; }

    private void readLoop() {
        try {
            Packet packet;
            while (!closed.get() && (packet = transport.read()) != null) {
                if ("WELCOME".equals(packet.type()) && packet.has(1)) selfId = packet.get(0);
                if ("DENIED".equals(packet.type()) && packet.has(1)) closeReason = packet.get(0);
                if ("END".equals(packet.type()) && packet.has(1)) closeReason = packet.get(0);
                listener.onPacket(packet);
                if ("DENIED".equals(packet.type()) || "END".equals(packet.type())) break;
            }
            if (!closed.get() && "Disconnected".equals(closeReason)) {
                closeReason = "The host closed the connection";
            }
        } catch (SocketException ex) {
            if (!closed.get()) closeReason = "Connection to the host was lost";
        } catch (IOException ex) {
            if (!closed.get()) closeReason = "Network error: " + ex.getMessage();
        } finally {
            shutdown();
        }
    }

    private void writeLoop() {
        try {
            while (!closed.get()) {
                Packet packet = outgoing.take();
                if (closed.get()) break;
                transport.write(packet);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (IOException ex) {
            if (!closed.get()) closeReason = "Could not send to host: " + ex.getMessage();
        } finally {
            // Writer failure is a fatal connection failure; the receiver cannot
            // rely on a broken stream, and Rider must be notified exactly once.
            shutdown();
        }
    }

    private void command(Packet packet) {
        if (closed.get()) return;
        if (selfId.isEmpty()) {
            listener.onPacket(Packet.of("ERROR", "Wait for host approval"));
            return;
        }
        if (!outgoing.offer(packet)) {
            closeReason = "Host is not reading messages; outbound queue limit reached";
            shutdown();
        }
    }

    @Override public void chat(String message) {
        command(Packet.of("CHAT", Rules.message(message)));
    }
    @Override public void privateMessage(String target, String message) {
        command(Packet.of("PM", target, Rules.message(message)));
    }
    @Override public void challenge(String toId, String game) {
        command(Packet.of("CHALLENGE", toId, game));
    }
    @Override public void respond(String challengeId, boolean accept) {
        command(Packet.of("RESPONSE", challengeId, Boolean.toString(accept)));
    }
    @Override public void gameInput(String matchId, java.util.List<String> args) {
        command(GamePackets.input(matchId, args));
    }

    @Override public void close() {
        closeReason = "You left the session";
        // Closing TCP is sufficient to notify the host and, unlike writing a
        // LEAVE frame, cannot block Rider's EDT waiting on an unresponsive host.
        shutdown();
    }

    private void shutdown() {
        if (!closed.compareAndSet(false, true)) return;
        outgoing.clear();
        try { socket.close(); } catch (IOException ignored) { }
        if (Thread.currentThread() != writer) writer.interrupt();
        listener.onClosed(closeReason);
    }
}

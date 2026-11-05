package dev.riderlan.core;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** LAN host. Holds roster, join approvals, relay routing and authoritative game state. */
public final class HostSession implements Session {
    public static final String HOST_ID = "host";
    private final String name;
    private final SessionListener listener;
    private final ServerSocket server;
    private final Map<String, Peer> members = new LinkedHashMap<>();
    private final Map<String, Peer> pending = new LinkedHashMap<>();
    private final Map<String, Challenge> challenges = new LinkedHashMap<>();
    private final Map<String, GameMatch> matches = new LinkedHashMap<>();
    private final ScheduledExecutorService scheduler;
    private final Semaphore connectionSlots = new Semaphore(24);
    private final String sessionKey;
    private final int maxParticipants;
    private final long approvalTimeoutMillis;
    private final long challengeTimeoutMillis;
    private int serial;
    private boolean closed;

    private record Challenge(String id, String from, String to, String game, long when) { }

    private static Thread daemon(Runnable work, String label) {
        Thread thread = new Thread(work, label);
        thread.setDaemon(true);
        return thread;
    }

    public HostSession(String username, int port, SessionListener listener) throws IOException {
        this(username, port, SecureTransport.generateSessionKey(), Rules.MAX_PARTICIPANTS, listener);
    }

    public HostSession(String username, int port, String sessionKey, int maxParticipants,
                       SessionListener listener) throws IOException {
        this(username, port, sessionKey, maxParticipants, listener, 120_000, 120_000);
    }

    /** Package-private shortened deadlines are used only by localhost regression tests. */
    HostSession(String username, int port, SessionListener listener,
                long approvalTimeoutMillis, long challengeTimeoutMillis) throws IOException {
        this(username, port, SecureTransport.generateSessionKey(), Rules.MAX_PARTICIPANTS,
                listener, approvalTimeoutMillis, challengeTimeoutMillis);
    }

    HostSession(String username, int port, String sessionKey, int maxParticipants, SessionListener listener,
                long approvalTimeoutMillis, long challengeTimeoutMillis) throws IOException {
        this.name = Rules.username(username);
        this.sessionKey = SecureTransport.validateSessionKey(sessionKey);
        if (maxParticipants < 2 || maxParticipants > Rules.MAX_PARTICIPANTS) {
            throw new IllegalArgumentException("Maximum participants must be between 2 and 16");
        }
        this.maxParticipants = maxParticipants;
        this.listener = java.util.Objects.requireNonNull(listener, "listener");
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid TCP port");
        if (approvalTimeoutMillis < 1 || challengeTimeoutMillis < 1) {
            throw new IllegalArgumentException("Timeouts must be positive");
        }
        this.approvalTimeoutMillis = approvalTimeoutMillis;
        this.challengeTimeoutMillis = challengeTimeoutMillis;
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress("0.0.0.0", port), 24);
        ThreadFactory factory = runnable -> daemon(runnable, "IDEle-game-tick");
        scheduler = Executors.newSingleThreadScheduledExecutor(factory);
        scheduler.scheduleAtFixedRate(this::tick, 40, 40, TimeUnit.MILLISECONDS);
        daemon(this::acceptLoop, "IDEle-host-accept").start();
        listener.onPacket(Packet.of("STATUS", "Hosting securely on TCP port " + server.getLocalPort()));
        sendRoster();
    }

    public int port() { return server.getLocalPort(); }
    /** Share this random 256-bit secret out of band only with intended participants. */
    public String sessionKey() { return sessionKey; }
    public int maxParticipants() { return maxParticipants; }
    @Override public String selfId() { return HOST_ID; }
    @Override public boolean hosting() { return true; }

    private void acceptLoop() {
        while (!isClosed()) {
            try {
                Socket socket = server.accept();
                if (!connectionSlots.tryAcquire()) {
                    socket.close(); // Bound unapproved handshakes to avoid unbounded threads.
                    continue;
                }
                try {
                    socket.setTcpNoDelay(true);
                    socket.setKeepAlive(true);
                    socket.setSoTimeout(15000);
                    daemon(() -> {
                        try { servePeer(socket); }
                        finally { connectionSlots.release(); }
                    }, "IDEle-peer-" + socket.getPort()).start();
                } catch (IOException | RuntimeException ex) {
                    connectionSlots.release();
                    try { socket.close(); } catch (IOException ignored) { }
                    throw ex;
                }
            } catch (SocketException exit) {
                if (!isClosed()) listener.onPacket(Packet.of("ERROR", "Host socket error: " + exit.getMessage()));
                break;
            } catch (IOException ex) {
                if (!isClosed()) listener.onPacket(Packet.of("ERROR", "Connection error: " + ex.getMessage()));
            }
        }
    }

    private synchronized boolean isClosed() { return closed; }

    private void servePeer(Socket socket) {
        Peer peer = null;
        try {
            // No unencrypted application data is accepted or sent. A peer must
            // prove knowledge of the out-of-band key before requesting admission.
            SecureTransport transport = SecureTransport.host(socket, sessionKey);
            peer = new Peer(socket, transport);
            Packet hello = peer.read();
            if (hello == null || !"HELLO".equals(hello.type()) || !hello.has(2)) {
                peer.finish(Packet.of("DENIED", "Invalid handshake"));
                return;
            }
            if (!Rules.PROTOCOL.equals(hello.get(1))) {
                peer.finish(Packet.of("DENIED", "Incompatible IDEle protocol"));
                return;
            }
            String proposed = Rules.username(hello.get(0));
            synchronized (this) {
                if (closed) return;
                if (1 + members.size() >= maxParticipants) {
                    peer.finish(Packet.of("DENIED", "This session is full (maximum " + maxParticipants + " participants)"));
                    return;
                }
                if (pending.size() >= 16) {
                    peer.finish(Packet.of("DENIED", "Too many pending connection requests"));
                    return;
                }
                if (nameInUse(proposed)) {
                    peer.finish(Packet.of("DENIED", "That username is already taken"));
                    return;
                }
                peer.name = proposed;
                peer.requestId = "req" + (++serial);
                peer.approvalDeadline = System.nanoTime()
                        + TimeUnit.MILLISECONDS.toNanos(approvalTimeoutMillis);
                pending.put(peer.requestId, peer);
                peer.send(Packet.of("PENDING", "Waiting for host approval"));
                listener.onPacket(Packet.of("JOIN_REQUEST", peer.requestId, proposed,
                        peer.socket.getInetAddress().getHostAddress()));
            }
            // The scheduler handles pending expiry. Never put SO_TIMEOUT on an
            // authenticated frame stream: a timeout mid-frame loses framing.
            peer.socket.setSoTimeout(0);
            while (!isClosed()) {
                Packet message = peer.read();
                if (message == null) return;
                if (peer.approved) process(peer.id, message);
                else if ("LEAVE".equals(message.type())) return;
            }
        } catch (IllegalArgumentException invalid) {
            if (peer != null) peer.finish(Packet.of("DENIED", invalid.getMessage()));
        } catch (Exception ex) {
            // Includes pre-auth handshake failures; do not leak session details.
        } finally {
            if (peer != null) disconnect(peer);
            else try { socket.close(); } catch (IOException ignored) { }
        }
    }

    private synchronized boolean nameInUse(String proposed) {
        if (Rules.nameKey(name).equals(Rules.nameKey(proposed))) return true;
        for (Peer peer : members.values()) {
            if (Rules.nameKey(peer.name).equals(Rules.nameKey(proposed))) return true;
        }
        for (Peer peer : pending.values()) {
            if (Rules.nameKey(peer.name).equals(Rules.nameKey(proposed))) return true;
        }
        return false;
    }

    @Override public synchronized void approve(String requestId, boolean accept) {
        Peer peer = pending.remove(requestId);
        if (peer == null) return;
        listener.onPacket(Packet.of("JOIN_RESOLVED", requestId));
        if (closed || !accept || 1 + members.size() >= maxParticipants) {
            peer.finish(Packet.of("DENIED", accept ? "Session full" : "Host declined your request"));
            return;
        }
        String memberId = "u" + (++serial);
        peer.id = memberId;
        peer.approved = true;
        members.put(memberId, peer);
        try {
            peer.send(Packet.of("WELCOME", memberId));
            sendRoster();
            broadcast(Packet.of("NOTICE", peer.name + " joined the session"));
        } catch (IOException ex) {
            disconnect(peer);
        }
    }

    private synchronized void disconnect(Peer peer) {
        peer.close();
        if (!peer.requestId.isEmpty() && pending.remove(peer.requestId) != null) {
            listener.onPacket(Packet.of("JOIN_RESOLVED", peer.requestId));
        }
        if (!peer.id.isEmpty() && members.remove(peer.id) != null) {
            cancelPlayer(peer.id);
            sendRoster();
            broadcast(Packet.of("NOTICE", peer.name + " left the session"));
        }
    }

    private synchronized void sendRoster() {
        List<String> fields = new ArrayList<>();
        fields.add(HOST_ID);
        fields.add(name);
        for (Peer peer : members.values()) {
            fields.add(peer.id);
            fields.add(peer.name);
        }
        broadcast(new Packet("ROSTER", fields));
    }

    private synchronized void broadcast(Packet packet) {
        listener.onPacket(packet);
        for (Peer peer : List.copyOf(members.values())) {
            try { peer.send(packet); }
            catch (IOException bad) { disconnect(peer); }
        }
    }

    private synchronized void to(String userId, Packet packet) {
        if (HOST_ID.equals(userId)) listener.onPacket(packet);
        else {
            Peer peer = members.get(userId);
            if (peer != null) {
                try { peer.send(packet); }
                catch (IOException bad) { disconnect(peer); }
            }
        }
    }

    private void both(String first, String second, Packet packet) {
        to(first, packet);
        if (!first.equals(second)) to(second, packet);
    }

    private synchronized boolean exists(String userId) {
        return HOST_ID.equals(userId) || members.containsKey(userId);
    }

    @Override public void chat(String message) { process(HOST_ID, Packet.of("CHAT", message)); }
    @Override public void privateMessage(String toId, String message) {
        process(HOST_ID, Packet.of("PM", toId, message));
    }
    @Override public void challenge(String toId, String game) {
        process(HOST_ID, Packet.of("CHALLENGE", toId, game));
    }
    @Override public void respond(String challengeId, boolean accept) {
        process(HOST_ID, Packet.of("RESPONSE", challengeId, Boolean.toString(accept)));
    }
    @Override public void move(String matchId, int position) {
        process(HOST_ID, Packet.of("MOVE", matchId, Integer.toString(position)));
    }
    @Override public void paddle(String matchId, int direction) {
        process(HOST_ID, Packet.of("PADDLE", matchId, Integer.toString(direction)));
    }

    private synchronized void process(String from, Packet packet) {
        if (closed || !exists(from)) return;
        try {
            switch (packet.type()) {
                case "CHAT" -> {
                    require(packet, 1);
                    broadcast(Packet.of("CHAT", from, Rules.message(packet.get(0))));
                }
                case "PM" -> {
                    require(packet, 2);
                    String to = packet.get(0);
                    if (from.equals(to) || !exists(to)) throw new IllegalArgumentException("Choose another connected participant");
                    Packet message = Packet.of("PM", from, to, Rules.message(packet.get(1)));
                    both(from, to, message);
                }
                case "CHALLENGE" -> createChallenge(from, packet);
                case "RESPONSE" -> respondToChallenge(from, packet);
                case "MOVE" -> {
                    require(packet, 2);
                    GameMatch match = matches.get(packet.get(0));
                    if (!(match instanceof GridMatch grid) || !match.contains(from)) {
                        throw new IllegalArgumentException("Game is unavailable");
                    }
                    int position = Integer.parseInt(packet.get(1));
                    Packet state = grid.move(from, position);
                    both(match.first, match.second, state);
                }
                case "PADDLE" -> {
                    require(packet, 2);
                    GameMatch match = matches.get(packet.get(0));
                    if (!(match instanceof PongMatch pong) || !match.contains(from)) {
                        throw new IllegalArgumentException("Pong match is unavailable");
                    }
                    pong.control(from, Integer.parseInt(packet.get(1)));
                }
                case "LEAVE" -> {
                    if (!HOST_ID.equals(from)) {
                        Peer peer = members.get(from);
                        if (peer != null) disconnect(peer);
                    }
                }
                default -> throw new IllegalArgumentException("Unknown command");
            }
        } catch (IllegalArgumentException ex) {
            to(from, Packet.of("ERROR", ex.getMessage() == null ? "Invalid command" : ex.getMessage()));
        }
    }

    private static void require(Packet packet, int count) {
        if (!packet.has(count)) throw new IllegalArgumentException("Invalid command fields");
    }

    private synchronized boolean busy(String id) {
        for (GameMatch game : matches.values()) {
            if (!game.finished && game.contains(id)) return true;
        }
        return false;
    }

    private synchronized void createChallenge(String from, Packet packet) {
        require(packet, 2);
        String target = packet.get(0), game = packet.get(1);
        if (!Rules.supportedGame(game)) throw new IllegalArgumentException("Unsupported game");
        if (from.equals(target) || !exists(target)) throw new IllegalArgumentException("The selected player is unavailable");
        if (busy(from) || busy(target)) throw new IllegalArgumentException("Finish your current match first");
        expireChallenges();
        if (challenges.size() >= 20) throw new IllegalArgumentException("Too many pending challenges");
        for (Challenge challenge : challenges.values()) {
            if (challenge.from.equals(from) || challenge.to.equals(from) || challenge.from.equals(target) || challenge.to.equals(target)) {
                throw new IllegalArgumentException("One of these players already has a pending challenge");
            }
        }
        String id = "c" + (++serial);
        challenges.put(id, new Challenge(id, from, target, game, System.currentTimeMillis()));
        to(from, Packet.of("CHALLENGE_SENT", id, target, game));
        to(target, Packet.of("CHALLENGE_IN", id, from, game));
    }

    private synchronized void respondToChallenge(String from, Packet packet) {
        require(packet, 2);
        expireChallenges();
        Challenge choice = challenges.get(packet.get(0));
        if (choice == null || !choice.to.equals(from)) throw new IllegalArgumentException("Challenge has expired");
        if (!"true".equals(packet.get(1)) && !"false".equals(packet.get(1))) {
            throw new IllegalArgumentException("Invalid response");
        }
        challenges.remove(choice.id);
        boolean accepted = "true".equals(packet.get(1));
        if (!exists(choice.from) || !exists(choice.to) || busy(choice.from) || busy(choice.to)) {
            both(choice.from, choice.to, Packet.of("CHALLENGE_RESULT", choice.id, "unavailable"));
            return;
        }
        both(choice.from, choice.to, Packet.of("CHALLENGE_RESULT", choice.id, accepted ? "accepted" : "declined"));
        if (!accepted) return;
        String matchId = "g" + (++serial);
        GameMatch match = Rules.PONG.equals(choice.game)
                ? new PongMatch(matchId, choice.from, choice.to)
                : new GridMatch(matchId, choice.game, choice.from, choice.to);
        matches.put(matchId, match);
        // Bounded memory across a long-running session.
        if (matches.size() > 100) {
            Iterator<Map.Entry<String, GameMatch>> iterator = matches.entrySet().iterator();
            while (matches.size() > 80 && iterator.hasNext()) {
                if (iterator.next().getValue().finished) iterator.remove();
            }
        }
        both(match.first, match.second, match.startPacket());
        both(match.first, match.second, match.snapshot());
    }

    /** Remove expired challenges AND notify both participants, so neither remains stuck waiting. */
    private synchronized void expireChallenges() {
        long now = System.currentTimeMillis();
        List<Challenge> expired = new ArrayList<>();
        Iterator<Challenge> iterator = challenges.values().iterator();
        while (iterator.hasNext()) {
            Challenge choice = iterator.next();
            if (now - choice.when >= challengeTimeoutMillis) {
                iterator.remove();
                expired.add(choice);
            }
        }
        // Deliver after iterating: sending can itself disconnect a slow participant.
        for (Challenge choice : expired) {
            both(choice.from, choice.to, Packet.of("CHALLENGE_RESULT", choice.id, "expired"));
        }
    }

    private synchronized void cancelPlayer(String leftId) {
        List<Challenge> cancelled = new ArrayList<>();
        Iterator<Challenge> iterator = challenges.values().iterator();
        while (iterator.hasNext()) {
            Challenge c = iterator.next();
            if (c.from.equals(leftId) || c.to.equals(leftId)) {
                iterator.remove();
                cancelled.add(c);
            }
        }
        List<GameMatch> ended = new ArrayList<>();
        for (GameMatch match : matches.values()) {
            if (!match.finished && match.contains(leftId)) {
                match.finished = true;
                ended.add(match);
            }
        }
        // Nested disconnects during transmission cannot invalidate the above iterators.
        for (Challenge c : cancelled) {
            to(c.from.equals(leftId) ? c.to : c.from,
                    Packet.of("CHALLENGE_RESULT", c.id, "unavailable"));
        }
        for (GameMatch match : ended) {
            to(match.other(leftId), Packet.of("GAME_OVER", match.id, "Opponent disconnected"));
        }
    }

    private synchronized void tick() {
        if (closed) return;
        try {
            expirePending();
            expireChallenges();
            for (GameMatch match : matches.values()) {
                if (match instanceof PongMatch pong && !pong.finished) {
                    pong.tick();
                    both(pong.first, pong.second, pong.snapshot());
                }
            }
        } catch (Exception ex) {
            listener.onPacket(Packet.of("ERROR", "Game update failed: " + ex.getMessage()));
        }
    }

    private synchronized void expirePending() {
        long now = System.nanoTime();
        for (Peer peer : List.copyOf(pending.values())) {
            if (!peer.approved && now - peer.approvalDeadline >= 0) {
                pending.remove(peer.requestId);
                listener.onPacket(Packet.of("JOIN_RESOLVED", peer.requestId));
                peer.finish(Packet.of("DENIED", "Host approval timed out"));
            }
        }
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        scheduler.shutdownNow();
        try { server.close(); } catch (IOException ignored) { }
        for (Peer peer : members.values()) {
            peer.finish(Packet.of("END", "Host ended the session"));
        }
        for (Peer peer : pending.values()) {
            peer.finish(Packet.of("DENIED", "Host ended the session"));
        }
        members.clear();
        pending.clear();
        challenges.clear();
        matches.clear();
    }

    /**
     * One bounded outbound queue per participant. Never write to a remote TCP socket
     * while holding the host's session lock or on Rider's EDT: a non-reading client
     * must not freeze chat, Pong, project shutdown, or other participants.
     */
    private static final class Peer {
        private static final Packet STOP_WRITER = Packet.of("__INTERNAL_STOP");
        private static final int MAX_OUTGOING = 64;
        final Socket socket;
        private final SecureTransport transport;
        private final ArrayBlockingQueue<Packet> outgoing = new ArrayBlockingQueue<>(MAX_OUTGOING);
        private final AtomicBoolean closed = new AtomicBoolean();
        private final Thread writer;
        private volatile boolean finishing;
        volatile String requestId = "";
        volatile String id = "";
        volatile String name = "";
        volatile boolean approved;
        volatile long approvalDeadline;

        Peer(Socket socket, SecureTransport transport) {
            this.socket = socket;
            this.transport = transport;
            writer = daemon(this::writeLoop, "IDEle-peer-writer-" + socket.getPort());
            writer.start();
        }

        Packet read() throws IOException { return transport.read(); }

        synchronized void send(Packet packet) throws IOException {
            if (closed.get() || finishing) throw new IOException("Peer is disconnected");
            if (!outgoing.offer(packet)) {
                forceClose();
                throw new IOException("Slow participant exceeded the outbound queue limit");
            }
        }

        /** Queue a final notification and let the writer close the socket after flushing. */
        synchronized void finish(Packet lastPacket) {
            if (closed.get() || finishing) return;
            if (!outgoing.offer(lastPacket) || !outgoing.offer(STOP_WRITER)) {
                forceClose();
                return;
            }
            finishing = true;
            // Even a malicious/non-reading peer cannot hold a socket open indefinitely.
            daemon(() -> {
                try { Thread.sleep(2000); } catch (InterruptedException ignored) { }
                forceClose();
            }, "IDEle-peer-close-watchdog").start();
        }

        private void writeLoop() {
            try {
                while (true) {
                    Packet packet = outgoing.take();
                    if (packet == STOP_WRITER) break;
                    transport.write(packet);
                }
            } catch (IOException ignored) {
                // The reader's disconnect cleanup will remove this participant.
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                forceClose();
            }
        }

        void close() { if (!finishing) forceClose(); }

        private void forceClose() {
            if (!closed.compareAndSet(false, true)) return;
            try { socket.close(); } catch (IOException ignored) { }
            if (Thread.currentThread() != writer) writer.interrupt();
        }
    }
}

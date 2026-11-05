package dev.riderlan.core;

import dev.riderlan.game.GameDefinition;
import dev.riderlan.game.GameLogic;
import dev.riderlan.game.GameRegistry;
import dev.riderlan.game.GameState;
import dev.riderlan.game.GameView;
import dev.riderlan.game.GameViewContext;

import javax.swing.SwingUtilities;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Checks every installed game against the module contract, then runs each game's own
 * test class, found by convention: {@code <definition class>Test} with {@code static int run()}.
 * A new game therefore gets tested without editing any shared test file.
 */
final class GameContractTest {
    private GameContractTest() { }
    private static int checks;

    private static void check(boolean valid, String description) {
        checks++;
        if (!valid) throw new AssertionError(description);
    }

    private record Context(String selfId, List<String> players) implements GameViewContext {
        @Override public String displayName(String playerId) { return playerId; }
        @Override public void send(String... args) { }
    }

    private static void contract(GameDefinition game) throws Exception {
        List<String> players = game.minPlayers() == 1 ? List.of("A") : List.of("A", "B");
        GameLogic logic = game.createLogic(players);
        GameState state = logic.state();
        check(state != null && !state.finished(), game.id() + " must start in a running state");
        AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                GameView view = game.createView(new Context("A", players));
                check(view.component() != null, game.id() + " view has no component");
                view.update(state);
                view.status(state);
                view.stop();
            } catch (Throwable broken) { failure.set(broken); }
        });
        if (failure.get() != null) throw new AssertionError(game.id() + " view failed", failure.get());
        check(true, game.id() + " view contract");
    }

    private static void ownTests(GameDefinition game) throws Exception {
        Class<?> tests;
        try {
            tests = Class.forName(game.getClass().getName() + "Test");
        } catch (ClassNotFoundException none) {
            System.out.println("NOTE — " + game.id() + " has no " + game.getClass().getSimpleName() + "Test");
            return;
        }
        Method run = tests.getMethod("run");
        int count = (Integer) run.invoke(null);
        System.out.println("PASS — " + count + " " + game.displayName() + " assertions");
    }

    /** A game that throws must end only its own match, never the session. */
    private static void brokenGameIsIsolated() {
        GameDefinition broken = new GameDefinition() {
            @Override public String id() { return "broken"; }
            @Override public String displayName() { return "Broken"; }
            @Override public int minPlayers() { return 1; }
            @Override public int maxPlayers() { return 2; }
            @Override public GameView createView(GameViewContext context) { throw new UnsupportedOperationException(); }
            @Override public GameLogic createLogic(List<String> players) {
                return new GameLogic() {
                    @Override public boolean input(String player, List<String> args) {
                        if (args.isEmpty()) throw new IllegalArgumentException("Say something");
                        throw new IllegalStateException("bug");
                    }
                    @Override public GameState state() { return GameState.playing(); }
                };
            }
        };
        Match match = new Match("m1", broken, List.of("A", "B"));
        try {
            match.input("A", List.of());
            throw new AssertionError("Rejected input must stay an IllegalArgumentException");
        } catch (IllegalArgumentException expected) { checks++; }
        try {
            match.input("A", List.of("x"));
            throw new AssertionError("A crashing game was not contained");
        } catch (Match.Failure expected) { checks++; }

        List<String> failures = new ArrayList<>();
        List<GameState> states = new ArrayList<>();
        try (SoloGame solo = new SoloGame(broken, states::add, failures::add)) {
            solo.start();
            solo.input(List.of("x"));
        }
        check(states.size() == 1 && failures.size() == 1, "Solo runner must report a crashing game once");

        try {
            new GameState(false, "", java.util.Collections.nCopies(GameState.MAX_FIELDS + 1, "x"));
            throw new AssertionError("Oversized game state accepted");
        } catch (IllegalStateException expected) { checks++; }
        check(GameRegistry.of(List.of(broken, broken)).problems().size() == 1, "Duplicate game ids must be rejected");
    }

    /** A participant whose build lacks a game (e.g. another branch) cannot be challenged to it. */
    private static void missingGameIsNegotiated() throws Exception {
        int port;
        try (java.net.ServerSocket probe = new java.net.ServerSocket(0)) { port = probe.getLocalPort(); }
        java.util.concurrent.BlockingQueue<Packet> hostEvents = new java.util.concurrent.LinkedBlockingQueue<>();
        SessionListener listener = new SessionListener() {
            @Override public void onPacket(Packet packet) { hostEvents.add(packet); }
            @Override public void onClosed(String reason) { }
        };
        try (HostSession host = new HostSession("Host", port, listener);
             java.net.Socket socket = new java.net.Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            SecureTransport peer = SecureTransport.client(socket, host.sessionKey());
            peer.write(Packet.of("HELLO", "PongOnly", Rules.PROTOCOL, "pong"));
            host.approve(await(hostEvents, "JOIN_REQUEST").get(0), true);
            String peerId = null;
            for (Packet packet; peerId == null && (packet = peer.read()) != null; ) {
                if ("WELCOME".equals(packet.type())) peerId = packet.get(0);
            }
            check(peerId != null, "Peer was not admitted");
            host.challenge(peerId, "tic-tac-toe");
            check(await(hostEvents, "ERROR").get(0).contains("PongOnly does not have Tic-Tac-Toe"),
                    "Challenge for a game the peer lacks must be refused");
            host.challenge(peerId, "pong");
            Packet incoming;
            do { incoming = peer.read(); } while (incoming != null && !"CHALLENGE_IN".equals(incoming.type()));
            check(incoming != null && "pong".equals(incoming.get(2)), "Peer was not challenged to its installed game");
        }
    }

    private static Packet await(java.util.concurrent.BlockingQueue<Packet> events, String type) throws InterruptedException {
        long end = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < end) {
            Packet packet = events.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (packet != null && packet.type().equals(type)) return packet;
        }
        throw new AssertionError("Timed out awaiting " + type);
    }

    static void run() throws Exception {
        GameRegistry registry = GameRegistry.installed();
        check(registry.problems().isEmpty(), "Game registry problems: " + registry.problems());
        for (String id : List.of("tic-tac-toe", "connect-four", "pong", "lights-out")) {
            check(registry.contains(id), "Bundled game missing from META-INF/services: " + id);
        }
        for (GameDefinition game : registry.all()) {
            contract(game);
            ownTests(game);
        }
        brokenGameIsIsolated();
        missingGameIsNegotiated();
        System.out.println("PASS — " + checks + " game contract assertions over " + registry.all().size() + " games");
    }
}

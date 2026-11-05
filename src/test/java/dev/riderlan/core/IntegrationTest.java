package dev.riderlan.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** No external testing libraries: runs against real localhost TCP sockets and game timers. */
public final class IntegrationTest {
    private static int assertions;

    private static void check(boolean result, String message) {
        assertions++;
        if (!result) throw new AssertionError(message);
    }

    private static final class Collector implements SessionListener {
        private final List<Packet> packets = new ArrayList<>();
        private String closeReason;

        @Override public synchronized void onPacket(Packet packet) {
            packets.add(packet);
            notifyAll();
        }

        @Override public synchronized void onClosed(String reason) {
            closeReason = reason;
            notifyAll();
        }

        synchronized Packet waitFor(String type, Predicate<Packet> where) throws InterruptedException {
            long end = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < end) {
                for (int i = 0; i < packets.size(); i++) {
                    Packet packet = packets.get(i);
                    if (packet.type().equals(type) && where.test(packet)) {
                        packets.remove(i);
                        return packet;
                    }
                }
                wait(Math.max(1, end - System.currentTimeMillis()));
            }
            throw new AssertionError("Timed out awaiting " + type + " in " + packets);
        }

        Packet take(String type) throws InterruptedException { return waitFor(type, packet -> true); }

        synchronized boolean contains(String type) {
            return packets.stream().anyMatch(p -> p.type().equals(type));
        }

        synchronized String waitClosed() throws InterruptedException {
            long end = System.currentTimeMillis() + 5000;
            while (closeReason == null && System.currentTimeMillis() < end) {
                wait(Math.max(1, end - System.currentTimeMillis()));
            }
            check(closeReason != null, "Client was not notified of disconnect");
            return closeReason;
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket probe = new ServerSocket(0)) { return probe.getLocalPort(); }
    }

    private static void packetTest() throws IOException {
        Packet input = Packet.of("CHAT", "こんにちは!", "line1\nline2\t🙂");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PacketCodec.write(bytes, input);
        Packet parsed = PacketCodec.read(new ByteArrayInputStream(bytes.toByteArray()));
        check(input.equals(parsed), "Packet round-trip with Unicode failed");
        check(PacketCodec.read(new ByteArrayInputStream(new byte[0])) == null, "EOF should return null");
        ByteArrayOutputStream invalid = new ByteArrayOutputStream();
        new DataOutputStream(invalid).writeInt(1_000_000);
        try {
            PacketCodec.read(new ByteArrayInputStream(invalid.toByteArray()));
            throw new AssertionError("Oversized packet accepted");
        } catch (IOException expected) { assertions++; }
        // Clean EOF is valid; truncated length headers are NOT clean EOF.
        for (int size = 1; size < 4; size++) {
            try {
                PacketCodec.read(new ByteArrayInputStream(new byte[size]));
                throw new AssertionError("Truncated " + size + "-byte header accepted");
            } catch (IOException expected) { assertions++; }
        }
        try {
            Rules.message(" ");
            throw new AssertionError("Empty message accepted");
        } catch (IllegalArgumentException expected) { assertions++; }
        check(SecureTransport.generateSessionKey().length() == 43,
                "A session key must contain 256 bits of random data");
        for (String bad : new String[]{"", "password", "123456", "not-a-secure-session-key"}) {
            try {
                SecureTransport.validateSessionKey(bad);
                throw new AssertionError("A weak or malformed session key was accepted: " + bad);
            } catch (IllegalArgumentException expected) { assertions++; }
        }
    }

    private static void encryptionAndCapacityTest() throws Exception {
        int port = freePort();
        Collector hostEvents = new Collector();
        HostSession host = new HostSession("Host", port,
                SecureTransport.generateSessionKey(), 16, hostEvents);
        List<ClientSession> clients = new ArrayList<>();
        List<Collector> collectors = new ArrayList<>();
        try {
            // A v1 cleartext client cannot reach the approval queue: no insecure downgrade.
            try (Socket legacy = new Socket("127.0.0.1", port)) {
                PacketCodec.write(legacy.getOutputStream(), Packet.of("HELLO", "Legacy", "1"));
            }
            try {
                new ClientSession("Impostor", "127.0.0.1", port,
                        SecureTransport.generateSessionKey(), new Collector());
                throw new AssertionError("Wrong session key unexpectedly authenticated");
            } catch (IOException expected) { assertions++; }
            check(!hostEvents.contains("JOIN_REQUEST"), "An unauthenticated peer entered the approval queue");

            // All 15 joiners + host must fit in a roster containing 32 fields.
            for (int i = 1; i <= 15; i++) {
                Collector events = new Collector();
                ClientSession client = new ClientSession("User" + i, "localhost", port, host.sessionKey(), events);
                clients.add(client);
                collectors.add(events);
                events.take("PENDING");
                host.approve(hostEvents.take("JOIN_REQUEST").get(0), true);
                events.take("WELCOME");
                int size = (i + 1) * 2;
                events.waitFor("ROSTER", p -> p.fields().size() == size);
            }
            check(clients.size() == 15, "16-person host session did not admit all 15 guests");
            check(collectors.get(0).waitFor("ROSTER", p -> p.fields().size() == 32).fields().size() == 32,
                    "Oldest participant did not receive the 16-person roster");

            Collector overflow = new Collector();
            ClientSession extra = new ClientSession("Extra", "127.0.0.1", port, host.sessionKey(), overflow);
            check(overflow.take("DENIED").get(0).contains("full"), "17th participant must be rejected");
            extra.close();

            clients.get(14).chat("Encrypted hello from participant sixteen");
            hostEvents.waitFor("CHAT", p -> p.get(1).equals("Encrypted hello from participant sixteen"));
            collectors.get(0).waitFor("CHAT", p -> p.get(1).equals("Encrypted hello from participant sixteen"));
            check(true, "Encrypted chat fanout failed with 16 users");
        } finally {
            for (ClientSession client : clients) client.close();
            host.close();
        }

        // The host may choose a smaller room size without changing the global ceiling.
        Collector events = new Collector();
        int smallPort = freePort();
        HostSession small = new HostSession("Host", smallPort,
                SecureTransport.generateSessionKey(), 2, events);
        try {
            Collector one = new Collector();
            ClientSession first = new ClientSession("First", "127.0.0.1", smallPort, small.sessionKey(), one);
            try {
                one.take("PENDING");
                small.approve(events.take("JOIN_REQUEST").get(0), true);
                one.take("WELCOME");
                Collector two = new Collector();
                ClientSession refused = new ClientSession("Second", "127.0.0.1", smallPort, small.sessionKey(), two);
                check(two.take("DENIED").get(0).contains("full"), "Configured two-user cap was ignored");
                refused.close();
            } finally { first.close(); }
        } finally { small.close(); }
    }

    private static void sessionTest() throws Exception {
        Collector hostEvents = new Collector();
        int port = freePort();
        HostSession host = new HostSession("Host", port, SecureTransport.generateSessionKey(), 5, hostEvents);
        Collector aliceEvents = new Collector();
        Collector bobEvents = new Collector();
        ClientSession alice = null, bob = null, carol = null, dave = null;
        Collector carolEvents = new Collector(), daveEvents = new Collector();
        try {
            hostEvents.take("STATUS");
            alice = new ClientSession("Alice", "127.0.0.1", port, host.sessionKey(), aliceEvents);
            aliceEvents.take("PENDING");
            Packet firstRequest = hostEvents.take("JOIN_REQUEST");
            check("Alice".equals(firstRequest.get(1)), "Host should approve Alice");
            host.approve(firstRequest.get(0), true);
            Packet aliceWelcome = aliceEvents.take("WELCOME");
            check("u".equals(aliceWelcome.get(0).substring(0, 1)), "Client ID invalid");
            aliceEvents.waitFor("ROSTER", p -> p.fields().size() == 4);

            bob = new ClientSession("Bob", "127.0.0.1", port, host.sessionKey(), bobEvents);
            bobEvents.take("PENDING");
            Packet nextRequest = hostEvents.take("JOIN_REQUEST");
            host.approve(nextRequest.get(0), true);
            bobEvents.take("WELCOME");
            bobEvents.waitFor("ROSTER", p -> p.fields().size() == 6);
            aliceEvents.waitFor("ROSTER", p -> p.fields().size() == 6);

            // Public chat appears for everyone including host.
            alice.chat("Hello LAN!");
            Packet onHost = hostEvents.waitFor("CHAT", p -> p.get(1).equals("Hello LAN!"));
            Packet onBob = bobEvents.waitFor("CHAT", p -> p.get(1).equals("Hello LAN!"));
            check(onHost.get(0).equals(alice.selfId()) && onBob.get(0).equals(alice.selfId()), "Sender attribution");

            // Private content is relayed by host, never delivered to host UI unless addressed to it.
            alice.privateMessage(bob.selfId(), "This is private");
            Packet onAlicePrivate = aliceEvents.waitFor("PM", p -> p.get(2).equals("This is private"));
            Packet onBobPrivate = bobEvents.waitFor("PM", p -> p.get(2).equals("This is private"));
            check(onAlicePrivate.equals(onBobPrivate), "Private messages must reach sender and target");
            check(!hostEvents.contains("PM"), "Host UI must not display other players' PMs");

            // Generic GAME_STATE layout: matchId, playing|finished, winner, game data...
            // Tic-Tac-Toe: Alice wins top row.
            alice.challenge(bob.selfId(), "tic-tac-toe");
            bobEvents.take("CHALLENGE_IN");
            String c1 = aliceEvents.take("CHALLENGE_SENT").get(0);
            bob.respond(c1, true);
            Packet start = aliceEvents.take("GAME_START");
            bobEvents.take("GAME_START");
            String g1 = start.get(0);
            check("tic-tac-toe".equals(start.get(1)) && start.get(2).equals(alice.selfId())
                    && start.get(3).equals(bob.selfId()), "GAME_START must name the game and players in seat order");
            aliceEvents.waitFor("GAME_STATE", p -> p.get(0).equals(g1));
            bobEvents.waitFor("GAME_STATE", p -> p.get(0).equals(g1));
            Session[] turns = {alice, bob, alice, bob, alice};
            int[] squares = {0, 3, 1, 4, 2};
            for (int i = 0; i < turns.length; i++) {
                turns[i].gameInput(g1, List.of(Integer.toString(squares[i])));
                int step = i;
                String expected = i == turns.length - 1 ? "finished" : "playing";
                aliceEvents.waitFor("GAME_STATE", p -> p.get(0).equals(g1) && p.get(1).equals(expected)
                        && p.get(3).charAt(squares[step]) != '.');
                bobEvents.waitFor("GAME_STATE", p -> p.get(0).equals(g1) && p.get(1).equals(expected)
                        && p.get(3).charAt(squares[step]) != '.');
            }
            aliceEvents.waitFor("CHALLENGE_RESULT", p -> p.get(0).equals(c1));
            check(true, "Tic-Tac-Toe completed");

            // Invalid input is reported only to the sender; finished matches ignore late input.
            bob.gameInput(g1, List.of("4"));
            check(!bobEvents.contains("ERROR"), "Late input to a finished match should be ignored");

            // Solo-only and unknown games cannot be used for challenges.
            alice.challenge(bob.selfId(), "lights-out");
            check(aliceEvents.take("ERROR").get(0).contains("single-player"), "Solo game was offered as a challenge");
            alice.challenge(bob.selfId(), "no-such-game");
            check(aliceEvents.take("ERROR").get(0).contains("not have"), "Unknown game was accepted");

            // Connect Four: Bob wins column 0 with four vertical disks against host.
            bob.challenge("host", "connect-four");
            Packet requested = hostEvents.take("CHALLENGE_IN");
            host.respond(requested.get(0), true);
            String g2 = bobEvents.take("GAME_START").get(0);
            hostEvents.take("GAME_START");
            bobEvents.waitFor("GAME_STATE", p -> p.get(0).equals(g2));
            hostEvents.waitFor("GAME_STATE", p -> p.get(0).equals(g2));
            host.gameInput(g2, List.of("3"));
            check(hostEvents.take("ERROR").get(0).contains("turn"), "Out-of-turn input must be rejected");
            for (int i = 0; i < 7; i++) {
                if (i % 2 == 0) bob.gameInput(g2, List.of("0"));
                else host.gameInput(g2, List.of("1"));
                int step = i;
                String state = i == 6 ? "finished" : "playing";
                bobEvents.waitFor("GAME_STATE", p -> p.get(0).equals(g2) && p.get(1).equals(state)
                        && p.get(3).chars().filter(ch -> ch == 'X' || ch == 'O').count() == step + 1);
                hostEvents.waitFor("GAME_STATE", p -> p.get(0).equals(g2) && p.get(1).equals(state)
                        && p.get(3).chars().filter(ch -> ch == 'X' || ch == 'O').count() == step + 1);
            }
            check(true, "Connect Four completed");

            // Pong: hosted simulation sends live updates, controls move a paddle.
            host.challenge(alice.selfId(), "pong");
            Packet pongRequest = aliceEvents.take("CHALLENGE_IN");
            alice.respond(pongRequest.get(0), true);
            String g3 = aliceEvents.take("GAME_START").get(0);
            hostEvents.take("GAME_START");
            Packet initial = aliceEvents.waitFor("GAME_STATE", p -> p.get(0).equals(g3));
            check(initial.fields().size() == 9, "Pong payload invalid");
            host.gameInput(g3, List.of("-1"));
            double before = Double.parseDouble(initial.get(3));
            Packet moved = aliceEvents.waitFor("GAME_STATE", p -> p.get(0).equals(g3) &&
                    Double.parseDouble(p.get(3)) < before);
            check(Double.parseDouble(moved.get(3)) < before, "Host paddle not moving");
            host.gameInput(g3, List.of("0"));
            alice.gameInput(g3, List.of("1"));
            aliceEvents.waitFor("GAME_STATE", p -> p.get(0).equals(g3) &&
                    Double.parseDouble(p.get(4)) > 95);
            alice.gameInput(g3, List.of("0"));

            // A disallowed duplicate name cannot enter or shadow an approved participant.
            Collector duplicateEvents = new Collector();
            ClientSession duplicate = new ClientSession("alice", "127.0.0.1", port, host.sessionKey(), duplicateEvents);
            check(duplicateEvents.take("DENIED").get(0).contains("username"), "Duplicate name was accepted");
            duplicate.close();

            // Rejection is explicit and closes pending connections.
            Collector deniedEvents = new Collector();
            ClientSession denied = new ClientSession("Mallory", "127.0.0.1", port, host.sessionKey(), deniedEvents);
            deniedEvents.take("PENDING");
            Packet denyRequest = hostEvents.take("JOIN_REQUEST");
            host.approve(denyRequest.get(0), false);
            check(deniedEvents.take("DENIED").get(0).contains("declined"), "Rejection did not reach client");
            denied.close();

            // Five users TOTAL including the host is the hard session limit.
            carol = new ClientSession("Carol", "127.0.0.1", port, host.sessionKey(), carolEvents);
            carolEvents.take("PENDING");
            host.approve(hostEvents.take("JOIN_REQUEST").get(0), true);
            carolEvents.take("WELCOME");
            dave = new ClientSession("Dave", "127.0.0.1", port, host.sessionKey(), daveEvents);
            daveEvents.take("PENDING");
            host.approve(hostEvents.take("JOIN_REQUEST").get(0), true);
            daveEvents.take("WELCOME");
            daveEvents.waitFor("ROSTER", p -> p.fields().size() == 10);
            Collector overflowEvents = new Collector();
            ClientSession overflow = new ClientSession("Eve", "127.0.0.1", port, host.sessionKey(), overflowEvents);
            check(overflowEvents.take("DENIED").get(0).contains("full"), "Sixth participant was allowed");
            overflow.close();
        } finally {
            host.close();
            if (alice != null) {
                check(aliceEvents.waitClosed().contains("Host ended"), "Alice not informed host left");
                alice.close();
            }
            if (bob != null) {
                check(bobEvents.waitClosed().contains("Host ended"), "Bob not informed host left");
                bob.close();
            }
            if (carol != null) {
                check(carolEvents.waitClosed().contains("Host ended"), "Carol not informed host left");
                carol.close();
            }
            if (dave != null) {
                check(daveEvents.waitClosed().contains("Host ended"), "Dave not informed host left");
                dave.close();
            }
        }
    }

    private static void swingTest() throws Exception {
        System.setProperty("java.awt.headless", "true");
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            dev.riderlan.ui.LanPanel panel = new dev.riderlan.ui.LanPanel();
            check(panel.getComponentCount() == 2, "Tool window should have header and tabs");
            panel.close();
        });
    }

    public static void main(String[] args) throws Exception {
        packetTest();
        sessionTest();
        encryptionAndCapacityTest();
        SecureTransportTest.run();
        swingTest();
        RegressionTest.run();
        GameContractTest.run();
        System.out.println("PASS — " + assertions + " baseline assertions; socket, admission, privacy, chat, games and Swing smoke tests");
    }
}

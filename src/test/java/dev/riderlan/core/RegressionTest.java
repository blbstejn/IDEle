package dev.riderlan.core;

import dev.riderlan.ui.LanPanel;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPasswordField;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/** Targeted regressions for previously broken socket idle handling and Swing connection lifecycle. */
final class RegressionTest {
    private RegressionTest() { }
    private static int checks;

    private static void check(boolean condition, String explanation) {
        checks++;
        if (!condition) throw new AssertionError(explanation);
    }

    private static final class Events implements SessionListener {
        private final List<Packet> packets = new ArrayList<>();
        private String reason;

        @Override public synchronized void onPacket(Packet packet) {
            packets.add(packet);
            notifyAll();
        }
        @Override public synchronized void onClosed(String reason) {
            this.reason = reason;
            notifyAll();
        }
        synchronized boolean empty() { return packets.isEmpty(); }
        synchronized boolean open() { return reason == null; }

        synchronized Packet await(String type) throws InterruptedException {
            return await(type, packet -> true);
        }
        synchronized Packet await(String type, Predicate<Packet> condition) throws InterruptedException {
            long end = System.currentTimeMillis() + 5_000;
            while (true) {
                for (int i = 0; i < packets.size(); i++) {
                    Packet found = packets.get(i);
                    if (type.equals(found.type()) && condition.test(found)) {
                        packets.remove(i);
                        return found;
                    }
                }
                long remaining = end - System.currentTimeMillis();
                if (remaining <= 0) throw new AssertionError("Timed out waiting for " + type + ", got: " + packets);
                wait(remaining);
            }
        }
    }

    private static int port() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) { return socket.getLocalPort(); }
    }

    private static void socketIdleAndTimeouts() throws Exception {
        int port = port();
        Events hostEvents = new Events();
        Events acceptedEvents = new Events();
        Events rejectedEvents = new Events();
        // Tests the production implementation with shorter deadlines, without sleeping two minutes.
        HostSession host = new HostSession("Host", port, hostEvents, 400, 300);
        ClientSession accepted = null, rejected = null;
        try {
            accepted = new ClientSession("Approved", "127.0.0.1", port, host.sessionKey(), acceptedEvents, false);
            Thread.sleep(80);
            check(acceptedEvents.empty(), "Deferred client started reading before UI could attach it");
            accepted.startReceiving();
            accepted.startReceiving(); // idempotent
            acceptedEvents.await("PENDING");
            Packet request = hostEvents.await("JOIN_REQUEST");
            host.approve(request.get(0), true);
            acceptedEvents.await("WELCOME");
            acceptedEvents.await("ROSTER", p -> p.fields().size() == 4);
            Thread.sleep(750); // Outlasts both shortened approval AND challenge timeouts.
            check(acceptedEvents.open(), "Approved idle client was disconnected");
            accepted.chat("Still connected after idle");
            hostEvents.await("CHAT", p -> "Still connected after idle".equals(p.get(1)));
            check(acceptedEvents.open(), "Approved connection could not send after idle");

            // Expired challenges must be cleared and both parties must be told.
            host.challenge(accepted.selfId(), "tic-tac-toe");
            Packet incoming = acceptedEvents.await("CHALLENGE_IN");
            String challengeId = incoming.get(0);
            check("expired".equals(acceptedEvents.await("CHALLENGE_RESULT").get(1)),
                    "Recipient did not receive expired challenge result");
            check("expired".equals(hostEvents.await("CHALLENGE_RESULT", p -> p.get(0).equals(challengeId)).get(1)),
                    "Challenger was left waiting on expired challenge");
            host.challenge(accepted.selfId(), "connect-four");
            acceptedEvents.await("CHALLENGE_IN"); // Previously expired challenge does not block new one.

            rejected = new ClientSession("Pending", "127.0.0.1", port, host.sessionKey(), rejectedEvents);
            rejectedEvents.await("PENDING");
            Packet pending = hostEvents.await("JOIN_REQUEST", p -> "Pending".equals(p.get(1)));
            check(rejectedEvents.await("DENIED").get(0).contains("timed out"),
                    "Unapproved join was not closed after the timeout");
            hostEvents.await("JOIN_RESOLVED", p -> p.get(0).equals(pending.get(0)));
            check(acceptedEvents.open(), "An expiring pending user disconnected an approved user");
        } finally {
            host.close();
            if (accepted != null) accepted.close();
            if (rejected != null) rejected.close();
        }
    }

    private static <T> T field(Object object, String key, Class<T> type) throws Exception {
        Field field = object.getClass().getDeclaredField(key);
        field.setAccessible(true);
        return type.cast(field.get(object));
    }

    private static void onEdt(Runnable runnable) throws Exception {
        SwingUtilities.invokeAndWait(runnable);
    }

    private static boolean waitUntil(BooleanSupplier condition) throws Exception {
        long end = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < end) {
            AtomicReference<Boolean> value = new AtomicReference<>();
            onEdt(() -> value.set(condition.getAsBoolean()));
            if (Boolean.TRUE.equals(value.get())) return true;
            Thread.sleep(25);
        }
        return false;
    }

    private static void staleUiEventsDoNotKillNewSession() throws Exception {
        int port = port();
        AtomicReference<LanPanel> panelRef = new AtomicReference<>();
        AtomicReference<SessionListener> oldCallback = new AtomicReference<>();
        onEdt(() -> {
            try {
                LanPanel panel = new LanPanel();
                panelRef.set(panel);
                Method createListener = LanPanel.class.getDeclaredMethod("listenerFor", int.class);
                createListener.setAccessible(true);
                oldCallback.set((SessionListener) createListener.invoke(panel, 0));
                field(panel, "port", JTextField.class).setText(Integer.toString(port));
                field(panel, "hostButton", JButton.class).doClick();
            } catch (Exception ex) { throw new RuntimeException(ex); }
        });
        LanPanel panel = panelRef.get();
        try {
            check(waitUntil(() -> {
                try { return field(panel, "connectionLabel", JLabel.class).getText().startsWith("Hosting"); }
                catch (Exception ex) { throw new RuntimeException(ex); }
            }), "Host UI failed to start");
            // The callbacks from a previous session have already been queued but must be ignored.
            oldCallback.get().onPacket(Packet.of("STATUS", "FALSE OLD STATUS"));
            oldCallback.get().onClosed("Old client unexpectedly closed");
            onEdt(() -> {}); // Wait until queued callbacks have drained.
            AtomicReference<String> status = new AtomicReference<>();
            onEdt(() -> {
                try { status.set(field(panel, "connectionLabel", JLabel.class).getText()); }
                catch (Exception ex) { throw new RuntimeException(ex); }
            });
            check(status.get().startsWith("Hosting"), "Stale callbacks reset the new host UI");
            check(field(panel, "session", Session.class) != null, "Stale callback destroyed new session");
            onEdt(() -> {
                try {
                    String generated = new String(field(panel, "sessionKeyField", JPasswordField.class).getPassword());
                    check(SecureTransport.validateSessionKey(generated).length() == 43,
                            "The host UI did not generate a valid 256-bit session key");
                    check(field(panel, "sessionKeyField", JPasswordField.class).getEchoChar() != 0,
                            "The host UI exposed its session key in plaintext by default");
                    check(((Number) field(panel, "maxParticipants", JSpinner.class).getValue()).intValue() == 16,
                            "The room cap should default to sixteen");
                } catch (Exception ex) { throw new RuntimeException(ex); }
            });
        } finally {
            onEdt(panel::close);
        }
    }

    private static void joiningUiReceivesWelcomeAndDisconnect() throws Exception {
        int port = port();
        Events hostEvents = new Events();
        HostSession host = new HostSession("Host", port, hostEvents);
        AtomicReference<LanPanel> panelRef = new AtomicReference<>();
        try {
            onEdt(() -> {
                try {
                    LanPanel panel = new LanPanel();
                    panelRef.set(panel);
                    field(panel, "port", JTextField.class).setText(Integer.toString(port));
                    field(panel, "name", JTextField.class).setText("Joiner");
                    field(panel, "hostAddress", JTextField.class).setText("127.0.0.1");
                    field(panel, "sessionKeyField", JTextField.class).setText(host.sessionKey());
                    field(panel, "joinButton", JButton.class).doClick();
                } catch (Exception ex) { throw new RuntimeException(ex); }
            });
            Packet request = hostEvents.await("JOIN_REQUEST");
            host.approve(request.get(0), true); // May happen before the worker attaches its session.
            LanPanel panel = panelRef.get();
            check(waitUntil(() -> {
                try { return "Connected".equals(field(panel, "connectionLabel", JLabel.class).getText()); }
                catch (Exception ex) { throw new RuntimeException(ex); }
            }), "UI failed to process an immediate host approval");
            check(field(panel, "session", Session.class) != null, "Approved UI is missing its session");
            host.close();
            check(waitUntil(() -> {
                try { return "Offline".equals(field(panel, "connectionLabel", JLabel.class).getText()); }
                catch (Exception ex) { throw new RuntimeException(ex); }
            }), "UI failed to reset after host shutdown");
            check(field(panel, "session", Session.class) == null, "Offline UI retained dead client session");
            check(field(panel, "sessionKeyField", JPasswordField.class).getPassword().length == 0,
                    "Disconnected session left the group credential in the UI");
        } finally {
            if (panelRef.get() != null) onEdt(panelRef.get()::close);
            host.close();
        }
    }

    private static void slowPeerCannotFreezeHost() throws Exception {
        int port = port();
        Events events = new Events();
        HostSession host = new HostSession("Host", port, events);
        Socket unread = new Socket();
        try {
            unread.setReceiveBufferSize(1024);
            unread.connect(new InetSocketAddress("127.0.0.1", port), 2000);
            SecureTransport secure = SecureTransport.client(unread, host.sessionKey());
            secure.write(Packet.of("HELLO", "Unread", Rules.PROTOCOL));
            host.approve(events.await("JOIN_REQUEST").get(0), true);
            // Generate substantially more output than a non-reading socket can buffer.
            // The outbound queue must disconnect it instead of blocking the host or Rider EDT.
            String payload = "x".repeat(1200);
            Thread flood = new Thread(() -> {
                for (int i = 0; i < 5000; i++) host.chat(payload);
            }, "IDEle-stalled-peer-test");
            flood.setDaemon(true);
            flood.start();
            flood.join(5000);
            check(!flood.isAlive(), "Slow/non-reading socket blocked the host thread");
            long then = System.nanoTime();
            host.close();
            long elapsedMillis = (System.nanoTime() - then) / 1_000_000;
            check(elapsedMillis < 2000, "Stopping the host blocked on a slow socket");
        } finally {
            unread.close();
            host.close();
        }
    }

    /** Control characters and bidirectional controls must not forge chat/system lines. */
    private static void chatLogIdentityAndLineSpoofing() throws Exception {
        try {
            Rules.username("Evil\u202Ename");
            throw new AssertionError("Bidirectional name control was accepted");
        } catch (IllegalArgumentException expected) { checks++; }
        try {
            Rules.username("Evil\u2028name");
            throw new AssertionError("Username line separator was accepted");
        } catch (IllegalArgumentException expected) { checks++; }
        onEdt(() -> {
            try (LanPanel panel = new LanPanel()) {
                Method append = LanPanel.class.getDeclaredMethod(
                        "append", String.class, String.class, String.class);
                append.setAccessible(true);
                append.invoke(panel, "public", "Imposter\u202E", "hello\n[12:00] System: trusted");
                String shown = field(panel, "chatLog", JTextArea.class).getText();
                check(shown.contains("\\u202E") && shown.contains("\\u000A"),
                        "Chat log failed to visibly escape control characters");
                check(!shown.contains("\n[12:00] System: trusted"),
                        "Chat content created a forged system message line");
            } catch (Exception ex) { throw new RuntimeException(ex); }
        });
    }

    /** A joined client must not block the Swing thread if the host stops reading. */
    private static void slowHostCannotFreezeClient() throws Exception {
        String key = SecureTransport.generateSessionKey();
        Events events = new Events();
        CountDownLatch release = new CountDownLatch(1);
        try (ServerSocket listener = new ServerSocket(0)) {
            Thread fakeHost = new Thread(() -> {
                try (Socket socket = listener.accept()) {
                    socket.setReceiveBufferSize(1024);
                    SecureTransport secure = SecureTransport.host(socket, key);
                    Packet hello = secure.read();
                    if (hello == null || !"HELLO".equals(hello.type())) return;
                    secure.write(Packet.of("WELCOME", "u1"));
                    // Do not read another frame; emulate a host whose network loop is stalled.
                    release.await(5, TimeUnit.SECONDS);
                } catch (Exception ignored) { }
            }, "IDEle-unresponsive-fake-host");
            fakeHost.setDaemon(true);
            fakeHost.start();
            ClientSession client = new ClientSession("Sender", "127.0.0.1",
                    listener.getLocalPort(), key, events);
            try {
                events.await("WELCOME");
                String payload = "x".repeat(1200);
                long start = System.nanoTime();
                for (int i = 0; i < 6000; i++) client.chat(payload);
                long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                check(ms < 2000, "Client writes waited on an unresponsive host for " + ms + " ms");
                long closeStart = System.nanoTime();
                client.close();
                check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - closeStart) < 500,
                        "Client close blocked waiting for a socket write");
                check(!events.open(), "Client close was not reported exactly once");
            } finally {
                client.close();
                release.countDown();
            }
            fakeHost.join(2000);
            check(!fakeHost.isAlive(), "Fake host did not stop");
        }
    }

    /** Leave cancels the socket itself even when a host never answers KEY_HELLO. */
    private static void leaveCancelsPendingConnection() throws Exception {
        String key = SecureTransport.generateSessionKey();
        CountDownLatch accepted = new CountDownLatch(1);
        CountDownLatch released = new CountDownLatch(1);
        AtomicReference<LanPanel> panelRef = new AtomicReference<>();
        try (ServerSocket listener = new ServerSocket(0)) {
            Thread silentHost = new Thread(() -> {
                try (Socket socket = listener.accept()) {
                    accepted.countDown();
                    socket.getInputStream().read();
                    released.await(5, TimeUnit.SECONDS);
                } catch (Exception ignored) { }
            }, "IDEle-silent-handshake-host");
            silentHost.setDaemon(true);
            silentHost.start();
            onEdt(() -> {
                try {
                    LanPanel panel = new LanPanel();
                    panelRef.set(panel);
                    field(panel, "port", JTextField.class).setText(Integer.toString(listener.getLocalPort()));
                    field(panel, "hostAddress", JTextField.class).setText("127.0.0.1");
                    field(panel, "sessionKeyField", JTextField.class).setText(key);
                    field(panel, "joinButton", JButton.class).doClick();
                } catch (Exception ex) { throw new RuntimeException(ex); }
            });
            try {
                check(accepted.await(3, TimeUnit.SECONDS), "Client never started the pending connection");
                LanPanel panel = panelRef.get();
                long start = System.nanoTime();
                onEdt(() -> {
                    try { field(panel, "leaveButton", JButton.class).doClick(); }
                    catch (Exception ex) { throw new RuntimeException(ex); }
                });
                check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 700,
                        "Leaving while a handshake is pending stalled the UI");
                check(field(panel, "connectingSocket", Socket.class) == null,
                        "Cancelled connection retained the network socket");
                check(field(panel, "session", Session.class) == null,
                        "Cancelled connection installed a stale session");
                check("Offline".equals(field(panel, "connectionLabel", JLabel.class).getText()),
                        "Cancel did not restore offline UI");
                check(field(panel, "sessionKeyField", JPasswordField.class).getPassword().length == 0,
                        "Cancelled join left the session key in the UI");
            } finally {
                if (panelRef.get() != null) onEdt(panelRef.get()::close);
                released.countDown();
            }
            silentHost.join(2000);
            check(!silentHost.isAlive(), "Silent host did not stop after cancellation");
        }
    }

    static void run() throws Exception {
        chatLogIdentityAndLineSpoofing();
        slowHostCannotFreezeClient();
        leaveCancelsPendingConnection();
        slowPeerCannotFreezeHost();
        socketIdleAndTimeouts();
        staleUiEventsDoNotKillNewSession();
        joiningUiReceivesWelcomeAndDisconnect();
        System.out.println("PASS — " + checks + " regression assertions (idle, deadlines, cancellation, slow peers, UI join)");
    }
}

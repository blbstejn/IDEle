package dev.riderlan.ui;

import dev.riderlan.core.ClientSession;
import dev.riderlan.core.HostSession;
import dev.riderlan.core.Packet;
import dev.riderlan.core.Rules;
import dev.riderlan.core.Session;
import dev.riderlan.core.SessionListener;
import dev.riderlan.core.SecureTransport;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JCheckBox;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.net.Socket;
import java.io.IOException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Single Rider tool window, using standard Swing components to inherit the active IDE theme. */
// Rider owns this Swing component; it is never Java-serialized.
@SuppressWarnings("serial")
public final class LanPanel extends JPanel implements AutoCloseable {
    private record Target(String id, String label) {
        @Override public String toString() { return label; }
    }
    private record Waiting(String id, String name, String address) {
        @Override public String toString() { return name + "  (" + address + ")"; }
    }
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

    private final AtomicInteger generation = new AtomicInteger();
    private final Map<String, String> users = new LinkedHashMap<>();
    private final Map<String, StringBuilder> history = new LinkedHashMap<>();
    private final JTextField name = new JTextField("Player", 14);
    private final JTextField hostAddress = new JTextField("127.0.0.1", 14);
    private final JTextField port = new JTextField(Integer.toString(Rules.DEFAULT_PORT), 7);
    private final JPasswordField sessionKeyField = new JPasswordField(17);
    private final JButton copyKeyButton = new JButton("Copy session key");
    private final JCheckBox showKey = new JCheckBox("Show key");
    private final JSpinner maxParticipants = new JSpinner(new SpinnerNumberModel(16, 2, 16, 1));
    private final JButton hostButton = new JButton("Host Session");
    private final JButton joinButton = new JButton("Join Session");
    private final JButton leaveButton = new JButton("Leave");
    private final JLabel connectionLabel = new JLabel("Offline");
    private final JLabel addressLabel = new JLabel("LAN IPv4: " + localAddresses());
    private final DefaultListModel<String> playerModel = new DefaultListModel<>();
    private final DefaultListModel<Waiting> waitingModel = new DefaultListModel<>();
    private final JList<Waiting> waitingList = new JList<>(waitingModel);
    private final JButton acceptButton = new JButton("Approve");
    private final JButton rejectButton = new JButton("Reject");
    private final JComboBox<Target> chatRecipient = new JComboBox<>();
    private final JTextArea chatLog = new JTextArea();
    private final JTextField message = new JTextField();
    private final JButton sendButton = new JButton("Send");
    private final JComboBox<Target> challenger = new JComboBox<>();
    private final JComboBox<String> gameChoice = new JComboBox<>(new String[]{
            Rules.TIC_TAC_TOE, Rules.CONNECT_FOUR, Rules.PONG
    });
    private final JButton challengeButton = new JButton("Challenge");
    private final JLabel gameLabel = new JLabel("No active game");
    private final JLabel helpLabel = new JLabel("Select a participant to challenge.");
    private final GameCanvas canvas = new GameCanvas(this::playMove, this::movePaddle);
    private final JTabbedPane tabs = new JTabbedPane();

    private volatile Session session;
    private volatile Socket connectingSocket;
    private boolean connecting;
    private String matchId = "";
    private String gameP1 = "", gameP2 = "";
    private volatile boolean closing;

    public LanPanel() {
        super(new BorderLayout(0, 8));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        JLabel title = new JLabel("IDEle");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 16f));
        JPanel header = new JPanel(new BorderLayout());
        header.add(title, BorderLayout.WEST);
        header.add(connectionLabel, BorderLayout.EAST);
        add(header, BorderLayout.NORTH);
        tabs.addTab("Session", buildSession());
        tabs.addTab("Chat", buildChat());
        tabs.addTab("Games", buildGames());
        add(tabs, BorderLayout.CENTER);
        history.put("public", new StringBuilder());
        chatRecipient.addItem(new Target("public", "Everyone (shared chat)"));
        updateEnabled();
    }

    private static JPanel vertical() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        return panel;
    }

    private static JPanel row(java.awt.Component... content) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 5));
        for (java.awt.Component item : content) panel.add(item);
        panel.setAlignmentX(LEFT_ALIGNMENT);
        return panel;
    }

    private JPanel buildSession() {
        JPanel container = vertical();
        JPanel connect = vertical();
        connect.setBorder(BorderFactory.createTitledBorder("Connection"));
        connect.add(row(new JLabel("Username"), name));
        connect.add(row(new JLabel("TCP port"), port));
        connect.add(row(new JLabel("Host IP / DNS name"), hostAddress));
        connect.add(row(new JLabel("Session key"), sessionKeyField));
        connect.add(row(copyKeyButton, showKey));
        connect.add(row(new JLabel("Max participants (host included)"), maxParticipants));
        connect.add(row(hostButton, joinButton, leaveButton));
        addressLabel.setToolTipText("Use the host's LAN IP or a working DNS hostname. DNS does not hide IP addresses.");
        connect.add(row(addressLabel));
        connect.add(row(new JLabel("Share the generated key privately; all chat traffic is encrypted to the host.")));
        container.add(connect);
        hostButton.addActionListener(e -> hostSession());
        joinButton.addActionListener(e -> joinSession());
        leaveButton.addActionListener(e -> leaveSession());
        final char hidden = sessionKeyField.getEchoChar();
        showKey.addActionListener(e -> sessionKeyField.setEchoChar(showKey.isSelected() ? (char) 0 : hidden));
        copyKeyButton.addActionListener(e -> {
            if (session == null || !session.hosting()) return;
            try {
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
                        new StringSelection(((HostSession) session).sessionKey()), null);
            } catch (IllegalStateException | java.awt.HeadlessException ex) {
                error("Could not copy to clipboard: " + ex.getMessage());
            }
        });

        JPanel roster = new JPanel(new BorderLayout());
        roster.setAlignmentX(LEFT_ALIGNMENT);
        roster.setBorder(BorderFactory.createTitledBorder("Connected participants (including host)"));
        JList<String> names = new JList<>(playerModel);
        names.setVisibleRowCount(8);
        roster.add(new JScrollPane(names), BorderLayout.CENTER);
        roster.setPreferredSize(new Dimension(280, 135));
        container.add(roster);

        JPanel approvals = new JPanel(new BorderLayout(6, 4));
        approvals.setAlignmentX(LEFT_ALIGNMENT);
        approvals.setBorder(BorderFactory.createTitledBorder("Pending join requests — host only"));
        waitingList.setVisibleRowCount(3);
        waitingList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        approvals.add(new JScrollPane(waitingList), BorderLayout.CENTER);
        JPanel decisions = row(acceptButton, rejectButton);
        approvals.add(decisions, BorderLayout.SOUTH);
        acceptButton.addActionListener(e -> approve(true));
        rejectButton.addActionListener(e -> approve(false));
        approvals.setPreferredSize(new Dimension(280, 128));
        container.add(approvals);
        container.add(Box.createVerticalGlue());
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(new JScrollPane(container), BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildChat() {
        JPanel panel = new JPanel(new BorderLayout(6, 8));
        panel.add(row(new JLabel("Conversation:"), chatRecipient), BorderLayout.NORTH);
        chatLog.setEditable(false);
        chatLog.setLineWrap(true);
        chatLog.setWrapStyleWord(true);
        chatLog.setMargin(new java.awt.Insets(8, 8, 8, 8));
        panel.add(new JScrollPane(chatLog), BorderLayout.CENTER);
        JPanel compose = new JPanel(new BorderLayout(6, 0));
        compose.add(message, BorderLayout.CENTER);
        compose.add(sendButton, BorderLayout.EAST);
        panel.add(compose, BorderLayout.SOUTH);
        chatRecipient.addActionListener(e -> showHistory());
        sendButton.addActionListener(e -> sendMessage());
        message.addActionListener(e -> sendMessage());
        return panel;
    }

    private JPanel buildGames() {
        JPanel panel = new JPanel(new BorderLayout(6, 8));
        JPanel controls = vertical();
        controls.add(row(new JLabel("Opponent:"), challenger));
        controls.add(row(new JLabel("Game:"), gameChoice, challengeButton));
        JPanel gameTitle = row(gameLabel);
        controls.add(gameTitle);
        panel.add(controls, BorderLayout.NORTH);
        panel.add(canvas, BorderLayout.CENTER);
        JPanel below = new JPanel(new GridLayout(0, 1));
        below.add(helpLabel);
        JButton focus = new JButton("Focus arena for Pong keyboard controls");
        focus.addActionListener(e -> canvas.requestFocusInWindow());
        below.add(focus);
        panel.add(below, BorderLayout.SOUTH);
        challengeButton.addActionListener(e -> sendChallenge());
        return panel;
    }

    private void hostSession() {
        if (session != null || connecting) return;
        try {
            int selectedPort = selectedPort();
            int currentGeneration = generation.incrementAndGet();
            String key = SecureTransport.generateSessionKey();
            Session host = new HostSession(name.getText(), selectedPort, key,
                    ((Number) maxParticipants.getValue()).intValue(), listenerFor(currentGeneration));
            session = host;
            sessionKeyField.setText(key);
            connectionLabel.setText("Hosting");
            append("public", "System", "Secure session started on TCP port " + selectedPort
                    + ". Copy the session key and share it privately with participants.");
            updateEnabled();
        } catch (Exception ex) {
            error("Cannot start host: " + ex.getMessage());
        }
    }

    private void joinSession() {
        if (session != null || connecting) return;
        final int selectedPort;
        final String selectedName, selectedAddress, selectedKey;
        try {
            selectedPort = selectedPort();
            selectedName = Rules.username(name.getText());
            selectedAddress = hostAddress.getText().strip();
            if (selectedAddress.isEmpty()) throw new IllegalArgumentException("Enter a host IP or DNS name");
            selectedKey = SecureTransport.validateSessionKey(new String(sessionKeyField.getPassword()));
        } catch (Exception ex) { error(ex.getMessage()); return; }
        int currentGeneration = generation.incrementAndGet();
        Socket attempt = new Socket();
        connectingSocket = attempt;
        connecting = true;
        connectionLabel.setText("Connecting…");
        updateEnabled();
        Thread worker = new Thread(() -> {
            try {
                // Do NOT start client callbacks before assigning this session on the EDT.
                ClientSession client = new ClientSession(attempt, selectedName, selectedAddress, selectedPort, selectedKey,
                        listenerFor(currentGeneration), false);
                SwingUtilities.invokeLater(() -> {
                    if (connectingSocket == attempt) connectingSocket = null;
                    if (generation.get() != currentGeneration || closing) {
                        client.close();
                        return;
                    }
                    session = client;
                    connecting = false;
                    connectionLabel.setText("Waiting for approval…");
                    updateEnabled();
                    client.startReceiving();
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    if (connectingSocket == attempt) connectingSocket = null;
                    if (generation.get() != currentGeneration || closing) return;
                    connecting = false;
                    connectionLabel.setText("Offline");
                    updateEnabled();
                    error("Could not join host: " + ex.getMessage());
                });
            }
        }, "IDEle-connect");
        worker.setDaemon(true);
        worker.start();
    }

    private int selectedPort() {
        try {
            int value = Integer.parseInt(port.getText().strip());
            if (value < 1 || value > 65535) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException ex) { throw new IllegalArgumentException("TCP port must be between 1 and 65535"); }
    }

    private void leaveSession() {
        generation.incrementAndGet();
        cancelConnectionAttempt();
        connecting = false;
        Session previous = session;
        session = null;
        if (previous != null) previous.close();
        // Group credentials should not remain in the input field after leaving.
        sessionKeyField.setText("");
        resetUi("Offline");
    }

    /** Abort a pending TCP/DNS/handshake attempt when Leave or Dispose is clicked. */
    private void cancelConnectionAttempt() {
        Socket attempt = connectingSocket;
        connectingSocket = null;
        if (attempt != null) {
            try { attempt.close(); } catch (IOException ignored) { }
        }
    }

    private void resetUi(String status) {
        connectionLabel.setText(status);
        users.clear();
        playerModel.clear();
        waitingModel.clear();
        history.clear();
        history.put("public", new StringBuilder());
        matchId = gameP1 = gameP2 = "";
        canvas.clear();
        chatLog.setText("");
        gameLabel.setText("No active game");
        helpLabel.setText("Select a participant to challenge.");
        updateTargets();
        updateEnabled();
    }

    private void approve(boolean allowed) {
        Waiting chosen = waitingList.getSelectedValue();
        if (chosen == null || session == null || !session.hosting()) return;
        session.approve(chosen.id(), allowed);
    }

    private void sendMessage() {
        if (session == null) return;
        Target chosen = (Target) chatRecipient.getSelectedItem();
        if (chosen == null) return;
        try {
            String text = Rules.message(message.getText());
            if ("public".equals(chosen.id())) session.chat(text);
            else session.privateMessage(chosen.id(), text);
            message.setText("");
        } catch (IllegalArgumentException ex) { error(ex.getMessage()); }
    }

    private void sendChallenge() {
        if (session == null) return;
        Target user = (Target) challenger.getSelectedItem();
        if (user == null) return;
        session.challenge(user.id(), (String) gameChoice.getSelectedItem());
    }

    private void playMove(int position) {
        if (session != null && !matchId.isEmpty()) session.move(matchId, position);
    }

    private void movePaddle(int direction) {
        if (session != null && !matchId.isEmpty()) session.paddle(matchId, direction);
    }

    /** Each session gets its own callback generation; old sessions cannot update a new one. */
    private SessionListener listenerFor(int expectedGeneration) {
        return new SessionListener() {
            @Override public void onPacket(Packet packet) {
                SwingUtilities.invokeLater(() -> {
                    if (!closing && generation.get() == expectedGeneration) apply(packet);
                });
            }

            @Override public void onClosed(String reason) {
                SwingUtilities.invokeLater(() -> {
                    if (closing || generation.get() != expectedGeneration) return;
                    generation.incrementAndGet();
                    connecting = false;
                    session = null;
                    resetUi("Offline");
                    sessionKeyField.setText("");
                    append("public", "System", reason);
                });
            }
        };
    }

    private void apply(Packet packet) {
        try {
            switch (packet.type()) {
                case "STATUS" -> {
                    if (packet.has(1)) connectionLabel.setText(packet.get(0));
                }
                case "PENDING" -> connectionLabel.setText("Waiting for approval…");
                case "WELCOME" -> {
                    connectionLabel.setText("Connected");
                    updateEnabled();
                    append("public", "System", "Host approved your connection");
                }
                case "DENIED" -> { if (packet.has(1)) error(packet.get(0)); }
                case "END" -> { if (packet.has(1)) append("public", "System", packet.get(0)); }
                case "JOIN_REQUEST" -> {
                    if (packet.has(3) && session != null && session.hosting()) {
                        waitingModel.addElement(new Waiting(packet.get(0), packet.get(1), packet.get(2)));
                        tabs.setSelectedIndex(0);
                        updateEnabled();
                    }
                }
                case "JOIN_RESOLVED" -> {
                    if (packet.has(1)) {
                        for (int i = waitingModel.getSize() - 1; i >= 0; i--) {
                            if (waitingModel.get(i).id().equals(packet.get(0))) waitingModel.remove(i);
                        }
                        updateEnabled();
                    }
                }
                case "ROSTER" -> updateRoster(packet);
                case "NOTICE" -> {
                    if (packet.has(1)) append("public", "System", packet.get(0));
                }
                case "CHAT" -> {
                    if (packet.has(2)) append("public", display(packet.get(0)), packet.get(1));
                }
                case "PM" -> {
                    if (packet.has(3) && session != null) {
                        String partner = packet.get(0).equals(session.selfId()) ? packet.get(1) : packet.get(0);
                        append(partner, display(packet.get(0)) + " (private)", packet.get(2));
                    }
                }
                case "CHALLENGE_SENT" -> {
                    if (packet.has(3)) {
                        gameLabel.setText("Challenge sent to " + display(packet.get(1)));
                        helpLabel.setText("Waiting for the other player to accept or decline.");
                    }
                }
                case "CHALLENGE_IN" -> receiveChallenge(packet);
                case "CHALLENGE_RESULT" -> {
                    if (packet.has(2)) {
                        gameLabel.setText("Challenge " + packet.get(1));
                        if (!"accepted".equals(packet.get(1))) helpLabel.setText("Choose another participant or game.");
                    }
                }
                case "GAME_START" -> startGame(packet);
                case "GAME_GRID" -> gridUpdate(packet);
                case "GAME_PONG" -> pongUpdate(packet);
                case "GAME_OVER" -> {
                    if (packet.has(2) && packet.get(0).equals(matchId)) {
                        gameLabel.setText(packet.get(1));
                        helpLabel.setText("The match ended. You can start a new challenge.");
                        canvas.clear();
                    }
                }
                case "ERROR" -> { if (packet.has(1)) error(packet.get(0)); }
                default -> { /* Ignore unknown future host notifications. */ }
            }
        } catch (RuntimeException ex) {
            error("Could not process a session event: " + ex.getMessage());
        }
    }

    private void updateRoster(Packet packet) {
        if (packet.fields().size() % 2 != 0) return;
        users.clear();
        playerModel.clear();
        for (int i = 0; i < packet.fields().size(); i += 2) {
            users.put(packet.get(i), packet.get(i + 1));
            playerModel.addElement(packet.get(i + 1) + (HostSession.HOST_ID.equals(packet.get(i)) ? " (host)" : ""));
        }
        updateTargets();
        updateEnabled();
    }

    private void updateTargets() {
        Target before = (Target) chatRecipient.getSelectedItem();
        String previousId = before == null ? "public" : before.id();
        DefaultComboBoxModel<Target> chat = new DefaultComboBoxModel<>();
        chat.addElement(new Target("public", "Everyone (shared chat)"));
        DefaultComboBoxModel<Target> targets = new DefaultComboBoxModel<>();
        String own = session == null ? "" : session.selfId();
        for (Map.Entry<String, String> entry : users.entrySet()) {
            if (entry.getKey().equals(own)) continue;
            Target target = new Target(entry.getKey(), entry.getValue());
            chat.addElement(target);
            targets.addElement(target);
        }
        chatRecipient.setModel(chat);
        challenger.setModel(targets);
        for (int i = 0; i < chat.getSize(); i++) {
            if (chat.getElementAt(i).id().equals(previousId)) {
                chatRecipient.setSelectedIndex(i);
                break;
            }
        }
        showHistory();
    }

    private void showHistory() {
        Target chosen = (Target) chatRecipient.getSelectedItem();
        String key = chosen == null ? "public" : chosen.id();
        chatLog.setText(history.computeIfAbsent(key, id -> new StringBuilder()).toString());
        chatLog.setCaretPosition(chatLog.getDocument().getLength());
    }

    private void append(String key, String who, String text) {
        if (text.length() > 1500) return;
        StringBuilder log = history.computeIfAbsent(key, id -> new StringBuilder());
        if (log.length() > 150_000) log.delete(0, 50_000);
        log.append('[').append(LocalTime.now().format(CLOCK)).append("] ")
                .append(safeLog(who)).append(": ").append(safeLog(text)).append('\n');
        Target selected = (Target) chatRecipient.getSelectedItem();
        if (selected != null && selected.id().equals(key)) showHistory();
    }

    /** Escape embedded lines and Unicode direction controls so chat cannot imitate system notices. */
    private static String safeLog(String value) {
        StringBuilder shown = new StringBuilder(value.length());
        value.codePoints().forEach(point -> {
            int category = Character.getType(point);
            if (Character.isISOControl(point) || category == Character.FORMAT
                    || category == Character.LINE_SEPARATOR
                    || category == Character.PARAGRAPH_SEPARATOR
                    || category == Character.SURROGATE) {
                if (point <= 0xffff) shown.append(String.format(java.util.Locale.ROOT, "\\u%04X", point));
                else shown.append(String.format(java.util.Locale.ROOT, "\\U%08X", point));
            } else shown.appendCodePoint(point);
        });
        return shown.toString();
    }

    private String display(String id) { return users.getOrDefault(id, id); }

    private void receiveChallenge(Packet packet) {
        if (!packet.has(3) || session == null) return;
        String challengeId = packet.get(0), fromId = packet.get(1), type = packet.get(2);
        int result = JOptionPane.showConfirmDialog(this,
                display(fromId) + " challenges you to " + type + ". Accept?",
                "IDEle Game Challenge", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (session != null) session.respond(challengeId, result == JOptionPane.YES_OPTION);
    }

    private void startGame(Packet packet) {
        if (!packet.has(4) || session == null) return;
        matchId = packet.get(0);
        String type = packet.get(1);
        gameP1 = packet.get(2);
        gameP2 = packet.get(3);
        canvas.begin(type, session.selfId(), gameP1, gameP2);
        gameLabel.setText(type + ": " + display(gameP1) + " vs " + display(gameP2));
        helpLabel.setText(Rules.PONG.equals(type)
                ? "Click the arena to focus, then hold W/S or Up/Down. First to 7 wins."
                : "Player X starts. Click an available square/column on your turn.");
        tabs.setSelectedIndex(2);
    }

    private void gridUpdate(Packet packet) {
        if (!packet.has(5) || !packet.get(0).equals(matchId)) return;
        canvas.grid(packet.get(1), packet.get(2), packet.get(3), packet.get(4));
        if ("finished".equals(packet.get(3))) {
            gameLabel.setText(resultText(packet.get(4)));
            helpLabel.setText("Match finished. You can challenge someone again.");
        } else helpLabel.setText("Turn: " + display(packet.get(2)) +
                (session != null && session.selfId().equals(packet.get(2)) ? " (you)" : ""));
    }

    private void pongUpdate(Packet packet) {
        if (!packet.has(9) || !packet.get(0).equals(matchId)) return;
        canvas.pong(packet.get(1), packet.get(2), packet.get(3), packet.get(4),
                packet.get(5), packet.get(6), packet.get(7), packet.get(8));
        if ("finished".equals(packet.get(7))) {
            gameLabel.setText(resultText(packet.get(8)));
            helpLabel.setText("Match finished. You can challenge someone again.");
        }
    }

    private String resultText(String winner) {
        if ("draw".equals(winner)) return "Draw game";
        if (session != null && session.selfId().equals(winner)) return "You won!";
        return display(winner) + " won";
    }

    private void updateEnabled() {
        Session current = session;
        boolean active = current != null;
        boolean unavailable = active || connecting;
        boolean connected = active && !current.selfId().isEmpty();
        name.setEnabled(!unavailable);
        port.setEnabled(!unavailable);
        hostAddress.setEnabled(!unavailable);
        sessionKeyField.setEnabled(!unavailable);
        maxParticipants.setEnabled(!unavailable);
        copyKeyButton.setEnabled(active && current.hosting());
        hostButton.setEnabled(!unavailable);
        joinButton.setEnabled(!unavailable);
        leaveButton.setEnabled(unavailable);
        acceptButton.setEnabled(active && current.hosting() && !waitingModel.isEmpty());
        rejectButton.setEnabled(acceptButton.isEnabled());
        chatRecipient.setEnabled(connected);
        message.setEnabled(connected);
        sendButton.setEnabled(connected);
        challenger.setEnabled(connected && challenger.getItemCount() > 0);
        gameChoice.setEnabled(connected);
        challengeButton.setEnabled(challenger.isEnabled());
    }

    private void error(String text) {
        JOptionPane.showMessageDialog(this, text, "IDEle", JOptionPane.WARNING_MESSAGE);
    }

    private static String localAddresses() {
        List<String> found = new ArrayList<>();
        try {
            for (NetworkInterface item : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!item.isUp() || item.isLoopback() || item.isVirtual()) continue;
                for (java.net.InetAddress ip : Collections.list(item.getInetAddresses())) {
                    if (ip instanceof Inet4Address && ip.isSiteLocalAddress()) found.add(ip.getHostAddress());
                }
            }
        } catch (Exception ignored) { }
        return found.isEmpty() ? "Use ipconfig" : String.join(", ", found);
    }

    @Override public void close() {
        if (closing) return;
        closing = true;
        generation.incrementAndGet();
        cancelConnectionAttempt();
        connecting = false;
        Session previous = session;
        session = null;
        if (previous != null) previous.close();
        if (SwingUtilities.isEventDispatchThread()) sessionKeyField.setText("");
        else SwingUtilities.invokeLater(() -> sessionKeyField.setText(""));
    }
}

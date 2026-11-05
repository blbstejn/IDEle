package dev.riderlan.ui;

import dev.riderlan.core.Rules;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.UIManager;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.IntConsumer;

/** Native Swing game board. Nothing in here depends on a particular Rider IDE API. */
// IDE tool-window component; persistence uses no Java object serialization.
@SuppressWarnings("serial")
final class GameCanvas extends JPanel {
    private final IntConsumer move;
    private final IntConsumer paddle;
    private String game = "";
    private String board = "";
    private String phase = "";
    private String next = "";
    private String self = "";
    private String p1 = "";
    private String p2 = "";
    private String winner = "";
    private double leftY = 95, rightY = 95, ballX = 200, ballY = 120;
    private int leftScore, rightScore;
    private int paddleDirection;

    GameCanvas(IntConsumer move, IntConsumer paddle) {
        this.move = move;
        this.paddle = paddle;
        setPreferredSize(new Dimension(420, 290));
        setMinimumSize(new Dimension(220, 180));
        setFocusable(true);
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
                select(e.getX(), e.getY());
            }
        });
        addFocusListener(new FocusAdapter() {
            @Override public void focusLost(FocusEvent e) { changePaddle(0); }
        });
        key("pressed W", "w-down", -1);
        key("released W", "w-up", 0);
        key("pressed S", "s-down", 1);
        key("released S", "s-up", 0);
        key("pressed UP", "up-down", -1);
        key("released UP", "up-up", 0);
        key("pressed DOWN", "down-down", 1);
        key("released DOWN", "down-up", 0);
    }

    private void key(String stroke, String key, int direction) {
        getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(stroke), key);
        getActionMap().put(key, new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { changePaddle(direction); }
        });
    }

    private void changePaddle(int direction) {
        if ((!Rules.PONG.equals(game) || !"playing".equals(phase)) && direction != 0) return;
        if (paddleDirection != direction) {
            paddleDirection = direction;
            paddle.accept(direction);
        }
    }

    void begin(String type, String ownId, String first, String second) {
        changePaddle(0);
        game = type;
        self = ownId;
        p1 = first;
        p2 = second;
        board = Rules.TIC_TAC_TOE.equals(type) ? "........." : "..........................................";
        phase = "playing";
        next = first;
        winner = "";
        leftY = rightY = 95;
        ballX = 200;
        ballY = 120;
        leftScore = rightScore = 0;
        repaint();
    }

    void grid(String cells, String turn, String status, String victor) {
        board = cells;
        next = turn;
        phase = status;
        winner = victor;
        repaint();
    }

    void pong(String left, String right, String x, String y,
              String scoreLeft, String scoreRight, String status, String victor) {
        try {
            leftY = Double.parseDouble(left);
            rightY = Double.parseDouble(right);
            ballX = Double.parseDouble(x);
            ballY = Double.parseDouble(y);
            leftScore = Integer.parseInt(scoreLeft);
            rightScore = Integer.parseInt(scoreRight);
            phase = status;
            winner = victor;
            if (!"playing".equals(phase)) changePaddle(0);
            repaint();
        } catch (NumberFormatException ignored) { }
    }

    void clear() {
        changePaddle(0);
        game = "";
        phase = "";
        board = "";
        repaint();
    }

    String game() { return game; }
    String phase() { return phase; }
    String next() { return next; }
    String winner() { return winner; }

    private int[] metrics(int columns, int rows) {
        int cell = Math.max(1, Math.min((getWidth() - 28) / columns, (getHeight() - 48) / rows));
        int left = (getWidth() - cell * columns) / 2;
        int top = (getHeight() - cell * rows) / 2;
        return new int[]{left, top, cell};
    }

    private void select(int x, int y) {
        if (!"playing".equals(phase) || !self.equals(next)) return;
        if (Rules.TIC_TAC_TOE.equals(game) && board.length() == 9) {
            int[] pos = metrics(3, 3);
            int col = (x - pos[0]) / pos[2], row = (y - pos[1]) / pos[2];
            if (x >= pos[0] && y >= pos[1] && col >= 0 && col < 3 && row >= 0 && row < 3) {
                int index = row * 3 + col;
                if (board.charAt(index) == '.') move.accept(index);
            }
        } else if (Rules.CONNECT_FOUR.equals(game) && board.length() == 42) {
            int[] pos = metrics(7, 6);
            int col = (x - pos[0]) / pos[2];
            if (x >= pos[0] && y >= pos[1] && col >= 0 && col < 7 &&
                    y < pos[1] + pos[2] * 6) move.accept(col);
        }
    }

    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color foreground = UIManager.getColor("Label.foreground");
            if (foreground == null) foreground = Color.DARK_GRAY;
            Color accent = UIManager.getColor("Component.accentColor");
            if (accent == null) accent = new Color(62, 145, 226);
            Color opponent = new Color(227, 156, 61);
            if (game.isEmpty()) {
                center(g, "Start or join a session to play.", getHeight() / 2, foreground);
                return;
            }
            if (Rules.TIC_TAC_TOE.equals(game)) tic(g, accent, opponent, foreground);
            else if (Rules.CONNECT_FOUR.equals(game)) connect(g, accent, opponent, foreground);
            else if (Rules.PONG.equals(game)) pong(g, accent, opponent, foreground);
        } finally {
            g.dispose();
        }
    }

    private void tic(Graphics2D g, Color accent, Color opponent, Color foreground) {
        int[] m = metrics(3, 3);
        int l = m[0], t = m[1], cell = m[2];
        g.setColor(foreground);
        g.setStroke(new BasicStroke(2f));
        for (int i = 1; i <= 2; i++) {
            g.drawLine(l + i * cell, t, l + i * cell, t + 3 * cell);
            g.drawLine(l, t + i * cell, l + 3 * cell, t + i * cell);
        }
        if (board.length() != 9) return;
        for (int i = 0; i < 9; i++) {
            int x = l + (i % 3) * cell;
            int y = t + (i / 3) * cell;
            int inset = Math.max(8, cell / 6);
            if (board.charAt(i) == 'X') {
                g.setColor(accent);
                g.setStroke(new BasicStroke(4.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.drawLine(x + inset, y + inset, x + cell - inset, y + cell - inset);
                g.drawLine(x + cell - inset, y + inset, x + inset, y + cell - inset);
            } else if (board.charAt(i) == 'O') {
                g.setColor(opponent);
                g.setStroke(new BasicStroke(4f));
                g.drawOval(x + inset, y + inset, cell - 2 * inset, cell - 2 * inset);
            }
        }
    }

    private void connect(Graphics2D g, Color accent, Color opponent, Color foreground) {
        int[] m = metrics(7, 6);
        int l = m[0], t = m[1], cell = m[2];
        g.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 100));
        g.fillRoundRect(l, t, cell * 7, cell * 6, 12, 12);
        if (board.length() != 42) return;
        for (int row = 0; row < 6; row++) {
            for (int col = 0; col < 7; col++) {
                char mark = board.charAt(row * 7 + col);
                Color empty = getBackground().brighter();
                g.setColor(mark == 'X' ? accent : mark == 'O' ? opponent : empty);
                int inset = Math.max(3, cell / 10);
                g.fillOval(l + col * cell + inset, t + row * cell + inset,
                        cell - 2 * inset, cell - 2 * inset);
                g.setColor(foreground);
                g.setStroke(new BasicStroke(0.5f));
                g.drawOval(l + col * cell + inset, t + row * cell + inset,
                        cell - 2 * inset, cell - 2 * inset);
            }
        }
    }

    private void pong(Graphics2D g, Color accent, Color opponent, Color foreground) {
        double scale = Math.min((getWidth() - 20) / 400.0, (getHeight() - 40) / 240.0);
        double x0 = (getWidth() - 400 * scale) / 2.0;
        double y0 = (getHeight() - 240 * scale) / 2.0 + 8;
        g.translate(x0, y0);
        g.scale(scale, scale);
        g.setColor(foreground);
        g.setStroke(new BasicStroke(2));
        g.drawRect(0, 0, 400, 240);
        g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10, new float[]{8, 8}, 0));
        g.drawLine(200, 0, 200, 240);
        g.setFont(g.getFont().deriveFont(Font.BOLD, 23));
        g.drawString(Integer.toString(leftScore), 150, 30);
        g.drawString(Integer.toString(rightScore), 232, 30);
        g.setColor(accent);
        g.fillRoundRect(16, (int) leftY, 8, 50, 4, 4);
        g.setColor(opponent);
        g.fillRoundRect(376, (int) rightY, 8, 50, 4, 4);
        g.setColor(foreground);
        g.fillOval((int) ballX - 6, (int) ballY - 6, 12, 12);
    }

    private void center(Graphics2D g, String text, int y, Color color) {
        g.setColor(color);
        FontMetrics metrics = g.getFontMetrics();
        g.drawString(text, (getWidth() - metrics.stringWidth(text)) / 2, y);
    }
}

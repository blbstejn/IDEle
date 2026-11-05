package dev.riderlan.games.pong;

import dev.riderlan.game.GameGraphics;
import dev.riderlan.game.GameState;
import dev.riderlan.game.GameView;
import dev.riderlan.game.GameViewContext;

import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import static dev.riderlan.games.pong.PongLogic.HEIGHT;
import static dev.riderlan.games.pong.PongLogic.PADDLE_H;
import static dev.riderlan.games.pong.PongLogic.WIDTH;

final class PongView implements GameView {
    private final GameViewContext context;
    private final Arena arena = new Arena();
    private double leftY = (HEIGHT - PADDLE_H) / 2.0, rightY = leftY, ballX = WIDTH / 2.0, ballY = HEIGHT / 2.0;
    private int leftScore, rightScore;
    private int direction;
    private boolean running = true;

    PongView(GameViewContext context) { this.context = context; }

    @Override public JComponent component() { return arena; }

    @Override public void update(GameState state) {
        if (state.size() != 6) return;
        try {
            leftY = Double.parseDouble(state.get(0));
            rightY = Double.parseDouble(state.get(1));
            ballX = Double.parseDouble(state.get(2));
            ballY = Double.parseDouble(state.get(3));
            leftScore = Integer.parseInt(state.get(4));
            rightScore = Integer.parseInt(state.get(5));
        } catch (NumberFormatException ignored) {
            return;
        }
        if (state.finished()) stop();
        arena.repaint();
    }

    @Override public String status(GameState state) {
        return context.displayName(context.players().get(0)) + " " + leftScore + " : " + rightScore + " "
                + context.displayName(context.players().get(1));
    }

    @Override public void stop() {
        steer(0);
        running = false;
    }

    private void steer(int wanted) {
        if (wanted != 0 && (!running || context.seat() < 0)) return;
        if (direction != wanted) {
            direction = wanted;
            context.send(Integer.toString(wanted));
        }
    }

    // Swing component owned by the tool window; never serialized.
    @SuppressWarnings("serial")
    private final class Arena extends JPanel {
        Arena() {
            setPreferredSize(new Dimension(420, 290));
            setFocusable(true);
            addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) { requestFocusInWindow(); }
            });
            addFocusListener(new FocusAdapter() {
                @Override public void focusLost(FocusEvent e) { steer(0); }
            });
            key("pressed W", -1);
            key("released W", 0);
            key("pressed S", 1);
            key("released S", 0);
            key("pressed UP", -1);
            key("released UP", 0);
            key("pressed DOWN", 1);
            key("released DOWN", 0);
        }

        private void key(String stroke, int wanted) {
            getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(stroke), stroke);
            getActionMap().put(stroke, new AbstractAction() {
                @Override public void actionPerformed(ActionEvent e) { steer(wanted); }
            });
        }

        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                double scale = Math.min((getWidth() - 20) / (double) WIDTH, (getHeight() - 40) / (double) HEIGHT);
                g.translate((getWidth() - WIDTH * scale) / 2.0, (getHeight() - HEIGHT * scale) / 2.0 + 8);
                g.scale(scale, scale);
                Color foreground = GameGraphics.foreground();
                g.setColor(foreground);
                g.setStroke(new BasicStroke(2));
                g.drawRect(0, 0, WIDTH, HEIGHT);
                g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10, new float[]{8, 8}, 0));
                g.drawLine(WIDTH / 2, 0, WIDTH / 2, HEIGHT);
                g.setFont(g.getFont().deriveFont(Font.BOLD, 23));
                g.drawString(Integer.toString(leftScore), 150, 30);
                g.drawString(Integer.toString(rightScore), 232, 30);
                g.setColor(GameGraphics.accent());
                g.fillRoundRect(PongLogic.PADDLE_INSET, (int) leftY, PongLogic.PADDLE_WIDTH, PADDLE_H, 4, 4);
                g.setColor(GameGraphics.opponent());
                g.fillRoundRect(WIDTH - PongLogic.PADDLE_INSET - PongLogic.PADDLE_WIDTH, (int) rightY,
                        PongLogic.PADDLE_WIDTH, PADDLE_H, 4, 4);
                g.setColor(foreground);
                int r = PongLogic.BALL_R;
                g.fillOval((int) ballX - r, (int) ballY - r, 2 * r, 2 * r);
            } finally {
                g.dispose();
            }
        }
    }
}

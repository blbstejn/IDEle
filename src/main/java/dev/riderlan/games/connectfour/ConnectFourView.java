package dev.riderlan.games.connectfour;

import dev.riderlan.game.GameGraphics;
import dev.riderlan.game.GameState;
import dev.riderlan.game.GameView;
import dev.riderlan.game.GameViewContext;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import static dev.riderlan.games.connectfour.ConnectFourLogic.COLUMNS;
import static dev.riderlan.games.connectfour.ConnectFourLogic.ROWS;

final class ConnectFourView implements GameView {
    private final GameViewContext context;
    private final Board board = new Board();
    private String cells = ".".repeat(COLUMNS * ROWS);
    private String next = "";
    private boolean finished;

    ConnectFourView(GameViewContext context) { this.context = context; }

    @Override public JComponent component() { return board; }

    @Override public void update(GameState state) {
        if (state.size() != 2 || state.get(0).length() != COLUMNS * ROWS) return;
        cells = state.get(0);
        next = state.get(1);
        finished = state.finished();
        board.repaint();
    }

    @Override public String status(GameState state) {
        return "Turn: " + context.displayName(next) + (context.selfId().equals(next) ? " (you)" : "");
    }

    // Swing component owned by the tool window; never serialized.
    @SuppressWarnings("serial")
    private final class Board extends JPanel {
        Board() {
            setPreferredSize(new Dimension(420, 290));
            addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    if (finished || !context.selfId().equals(next)) return;
                    int index = GameGraphics.cellAt(GameGraphics.fitGrid(Board.this, COLUMNS, ROWS),
                            COLUMNS, ROWS, e.getX(), e.getY());
                    if (index >= 0) context.send(Integer.toString(index % COLUMNS));
                }
            });
        }

        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Rectangle grid = GameGraphics.fitGrid(this, COLUMNS, ROWS);
                int l = grid.x, t = grid.y, cell = grid.width;
                Color accent = GameGraphics.accent();
                g.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 100));
                g.fillRoundRect(l, t, cell * COLUMNS, cell * ROWS, 12, 12);
                Color empty = getBackground().brighter();
                int inset = Math.max(3, cell / 10);
                for (int row = 0; row < ROWS; row++) {
                    for (int col = 0; col < COLUMNS; col++) {
                        char mark = cells.charAt(row * COLUMNS + col);
                        g.setColor(mark == 'X' ? accent : mark == 'O' ? GameGraphics.opponent() : empty);
                        g.fillOval(l + col * cell + inset, t + row * cell + inset, cell - 2 * inset, cell - 2 * inset);
                        g.setColor(GameGraphics.foreground());
                        g.setStroke(new BasicStroke(0.5f));
                        g.drawOval(l + col * cell + inset, t + row * cell + inset, cell - 2 * inset, cell - 2 * inset);
                    }
                }
            } finally {
                g.dispose();
            }
        }
    }
}

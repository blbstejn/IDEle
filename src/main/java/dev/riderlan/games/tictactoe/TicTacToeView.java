package dev.riderlan.games.tictactoe;

import dev.riderlan.game.GameGraphics;
import dev.riderlan.game.GameState;
import dev.riderlan.game.GameView;
import dev.riderlan.game.GameViewContext;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BasicStroke;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

final class TicTacToeView implements GameView {
    private final GameViewContext context;
    private final Board board = new Board();
    private String cells = ".........";
    private String next = "";
    private boolean finished;

    TicTacToeView(GameViewContext context) { this.context = context; }

    @Override public JComponent component() { return board; }

    @Override public void update(GameState state) {
        if (state.size() != 2 || state.get(0).length() != 9) return;
        cells = state.get(0);
        next = state.get(1);
        finished = state.finished();
        board.repaint();
    }

    @Override public String status(GameState state) {
        String who = context.displayName(next);
        return "Turn: " + who + (context.selfId().equals(next) ? " (you)" : "");
    }

    // Swing component owned by the tool window; never serialized.
    @SuppressWarnings("serial")
    private final class Board extends JPanel {
        Board() {
            setPreferredSize(new Dimension(420, 290));
            addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    if (finished || !context.selfId().equals(next)) return;
                    int index = GameGraphics.cellAt(GameGraphics.fitGrid(Board.this, 3, 3), 3, 3, e.getX(), e.getY());
                    if (index >= 0 && cells.charAt(index) == '.') context.send(Integer.toString(index));
                }
            });
        }

        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Rectangle grid = GameGraphics.fitGrid(this, 3, 3);
                int l = grid.x, t = grid.y, cell = grid.width;
                g.setColor(GameGraphics.foreground());
                g.setStroke(new BasicStroke(2f));
                for (int i = 1; i <= 2; i++) {
                    g.drawLine(l + i * cell, t, l + i * cell, t + 3 * cell);
                    g.drawLine(l, t + i * cell, l + 3 * cell, t + i * cell);
                }
                for (int i = 0; i < 9; i++) {
                    int x = l + (i % 3) * cell, y = t + (i / 3) * cell;
                    int inset = Math.max(8, cell / 6);
                    if (cells.charAt(i) == 'X') {
                        g.setColor(GameGraphics.accent());
                        g.setStroke(new BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                        g.drawLine(x + inset, y + inset, x + cell - inset, y + cell - inset);
                        g.drawLine(x + cell - inset, y + inset, x + inset, y + cell - inset);
                    } else if (cells.charAt(i) == 'O') {
                        g.setColor(GameGraphics.opponent());
                        g.setStroke(new BasicStroke(4f));
                        g.drawOval(x + inset, y + inset, cell - 2 * inset, cell - 2 * inset);
                    }
                }
            } finally {
                g.dispose();
            }
        }
    }
}

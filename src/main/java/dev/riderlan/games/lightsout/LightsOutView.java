package dev.riderlan.games.lightsout;

import dev.riderlan.game.GameGraphics;
import dev.riderlan.game.GameState;
import dev.riderlan.game.GameView;
import dev.riderlan.game.GameViewContext;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import static dev.riderlan.games.lightsout.LightsOutLogic.SIZE;

final class LightsOutView implements GameView {
    private final GameViewContext context;
    private final Board board = new Board();
    private String cells = "0".repeat(SIZE * SIZE);
    private String moves = "0";
    private boolean finished;

    LightsOutView(GameViewContext context) { this.context = context; }

    @Override public JComponent component() { return board; }

    @Override public void update(GameState state) {
        if (state.size() != 2 || state.get(0).length() != SIZE * SIZE) return;
        cells = state.get(0);
        moves = state.get(1);
        finished = state.finished();
        board.repaint();
    }

    @Override public String status(GameState state) { return "Moves: " + moves; }

    // Swing component owned by the tool window; never serialized.
    @SuppressWarnings("serial")
    private final class Board extends JPanel {
        Board() {
            setPreferredSize(new Dimension(420, 290));
            addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    if (finished) return;
                    int index = GameGraphics.cellAt(GameGraphics.fitGrid(Board.this, SIZE, SIZE), SIZE, SIZE, e.getX(), e.getY());
                    if (index >= 0) context.send(Integer.toString(index));
                }
            });
        }

        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Rectangle grid = GameGraphics.fitGrid(this, SIZE, SIZE);
                int gap = Math.max(2, grid.width / 12);
                for (int i = 0; i < SIZE * SIZE; i++) {
                    int x = grid.x + (i % SIZE) * grid.width, y = grid.y + (i / SIZE) * grid.height;
                    g.setColor(cells.charAt(i) == '1' ? GameGraphics.opponent() : getBackground().darker());
                    g.fillRoundRect(x + gap, y + gap, grid.width - 2 * gap, grid.height - 2 * gap, 8, 8);
                }
            } finally {
                g.dispose();
            }
        }
    }
}

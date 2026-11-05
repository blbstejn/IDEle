package dev.riderlan.game;

import javax.swing.JComponent;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;

/** Small drawing helpers so game views match the active IDE theme without copying code. */
public final class GameGraphics {
    private GameGraphics() { }

    public static Color foreground() {
        Color color = UIManager.getColor("Label.foreground");
        return color == null ? Color.DARK_GRAY : color;
    }

    /** Colour of the first seat. */
    public static Color accent() {
        Color color = UIManager.getColor("Component.accentColor");
        return color == null ? new Color(62, 145, 226) : color;
    }

    /** Colour of the second seat. */
    public static Color opponent() { return new Color(227, 156, 61); }

    /**
     * Largest square-celled grid that fits in the component; {@code width}/{@code height} of the
     * result are the cell size, {@code x}/{@code y} the top-left corner.
     */
    public static Rectangle fitGrid(JComponent component, int columns, int rows) {
        int cell = Math.max(1, Math.min((component.getWidth() - 28) / columns,
                (component.getHeight() - 48) / rows));
        int left = (component.getWidth() - cell * columns) / 2;
        int top = (component.getHeight() - cell * rows) / 2;
        return new Rectangle(left, top, cell, cell);
    }

    /** Index of the grid cell under a point, or -1. */
    public static int cellAt(Rectangle grid, int columns, int rows, int x, int y) {
        if (x < grid.x || y < grid.y) return -1;
        int col = (x - grid.x) / grid.width, row = (y - grid.y) / grid.height;
        return col < columns && row < rows ? row * columns + col : -1;
    }

    public static void centerText(Graphics2D g, JComponent component, String text, int y) {
        FontMetrics metrics = g.getFontMetrics();
        g.drawString(text, (component.getWidth() - metrics.stringWidth(text)) / 2, y);
    }

    /** Parses an integer argument, rejecting anything else as invalid player input. */
    public static int intArg(java.util.List<String> args, int index, String what) {
        try {
            if (args.size() <= index) throw new NumberFormatException();
            return Integer.parseInt(args.get(index));
        } catch (NumberFormatException bad) {
            throw new IllegalArgumentException("Invalid " + what);
        }
    }
}

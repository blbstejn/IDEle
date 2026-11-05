package dev.riderlan.game;

import javax.swing.JComponent;

/** Client-side rendering and controls of one match. Every method runs on the Swing event thread. */
public interface GameView {
    /** The arena placed in the Games tab. It receives keyboard focus when the match starts. */
    JComponent component();

    /** Shows a new authoritative snapshot. */
    void update(GameState state);

    /** One line shown under the arena while the match runs, e.g. whose turn it is. */
    default String status(GameState state) { return ""; }

    /** The match ended or was replaced; stop timers and release any held input. */
    default void stop() { }
}

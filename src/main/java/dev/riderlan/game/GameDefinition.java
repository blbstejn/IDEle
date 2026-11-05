package dev.riderlan.game;

import java.util.List;

/**
 * Entry point of one game module. Implementations live in their own package under
 * {@code dev.riderlan.games}, need a public no-argument constructor, and are registered
 * with one line in {@code META-INF/services/dev.riderlan.game.GameDefinition}.
 *
 * <p>The host (or, for solo play, the local machine) runs the {@link GameLogic}. Every
 * participant renders the shared {@link GameState} with its own {@link GameView}.
 */
public interface GameDefinition {
    /** Stable wire identifier: 1–32 characters of {@code a-z}, {@code 0-9} and {@code -}. Never rename it. */
    String id();

    /** Name shown in the Games tab. */
    String displayName();

    /** 1 for games that can be played solo, 2 for head-to-head only. */
    int minPlayers();

    /** 1 for solo-only games, 2 for games that can be played against another participant. */
    int maxPlayers();

    /** Short instructions shown under the arena while the game runs. */
    default String help() { return ""; }

    /**
     * Real-time games receive {@link GameLogic#tick()} every {@link GameLogic#TICK_MILLIS} ms.
     * Turn-based games leave this false and only change state on input.
     */
    default boolean realtime() { return false; }

    /** Creates fresh authoritative state. {@code players} holds participant ids in turn/seat order. */
    GameLogic createLogic(List<String> players);

    /** Creates the Swing view for one match. Called on the Swing event thread. */
    GameView createView(GameViewContext context);
}

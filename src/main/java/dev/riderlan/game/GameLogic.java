package dev.riderlan.game;

import java.util.List;

/**
 * Authoritative rules and state of one match. Calls are serialized by the caller, so
 * implementations need no synchronization. Never trust {@code args}: they arrive from
 * other machines.
 */
public interface GameLogic {
    /** Interval between {@link #tick()} calls for {@link GameDefinition#realtime()} games. */
    int TICK_MILLIS = 40;

    /**
     * Applies one player's input.
     *
     * @return true when the state changed and should be sent to the players
     * @throws IllegalArgumentException with a short user-facing message to reject the input
     */
    boolean input(String player, List<String> args);

    /** Advances a real-time game by one step; returns true when the state should be sent. */
    default boolean tick() { return false; }

    /** Current snapshot sent to every player. */
    GameState state();
}

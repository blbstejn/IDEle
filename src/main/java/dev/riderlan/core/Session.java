package dev.riderlan.core;

public interface Session extends AutoCloseable {
    String selfId();
    boolean hosting();
    void chat(String message);
    void privateMessage(String toId, String message);
    /** Challenges another participant to the game with this {@link dev.riderlan.game.GameDefinition#id()}. */
    void challenge(String toId, String gameId);
    void respond(String challengeId, boolean accept);
    /** Forwards input to the authoritative game logic of a running match. */
    void gameInput(String matchId, java.util.List<String> args);
    default void approve(String requestId, boolean accept) {
        throw new IllegalStateException("Only hosts can approve participants");
    }
    @Override void close();
}

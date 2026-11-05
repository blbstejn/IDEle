package dev.riderlan.core;

public interface Session extends AutoCloseable {
    String selfId();
    boolean hosting();
    void chat(String message);
    void privateMessage(String toId, String message);
    void challenge(String toId, String game);
    void respond(String challengeId, boolean accept);
    void move(String matchId, int position);
    void paddle(String matchId, int direction);
    default void approve(String requestId, boolean accept) {
        throw new IllegalStateException("Only hosts can approve participants");
    }
    @Override void close();
}

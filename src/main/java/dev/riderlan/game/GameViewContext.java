package dev.riderlan.game;

import java.util.List;

/** What a {@link GameView} may know about and do in its match. */
public interface GameViewContext {
    /** Id of the local participant. */
    String selfId();

    /** Participant ids in the order passed to {@link GameDefinition#createLogic}. */
    List<String> players();

    /** Name shown for a participant id. */
    String displayName(String playerId);

    /** Sends input to the authoritative {@link GameLogic#input}. Never blocks. */
    void send(String... args);

    /** Seat of the local participant in {@link #players()}, or -1 when only watching. */
    default int seat() { return players().indexOf(selfId()); }
}

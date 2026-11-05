package dev.riderlan.game;

import java.util.Arrays;
import java.util.List;

/**
 * Snapshot of a match as it travels to every player. {@code data} is game-defined.
 * {@code winner} is empty while nobody has won, {@link #DRAW}, or a player id.
 *
 * <p>Snapshots must fit inside one encrypted frame, so the size is capped; exceeding it is a
 * programming error in the game and ends the match.
 */
public record GameState(boolean finished, String winner, List<String> data) {
    public static final String DRAW = "draw";
    public static final int MAX_FIELDS = 48;
    public static final int MAX_CHARS = 4000;

    public GameState {
        if (winner == null) winner = "";
        data = List.copyOf(data);
        if (data.size() > MAX_FIELDS) throw new IllegalStateException("Game state has too many fields");
        int total = winner.length();
        for (String field : data) total += field.length();
        if (total > MAX_CHARS) throw new IllegalStateException("Game state is too large");
        if (!finished && !winner.isEmpty()) throw new IllegalStateException("A running game cannot have a winner");
    }

    public static GameState playing(String... data) { return new GameState(false, "", Arrays.asList(data)); }
    public static GameState won(String winner, String... data) { return new GameState(true, winner, Arrays.asList(data)); }
    public static GameState draw(String... data) { return new GameState(true, DRAW, Arrays.asList(data)); }

    public String get(int index) { return data.get(index); }
    public int size() { return data.size(); }
}

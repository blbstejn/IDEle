package dev.riderlan.games.connectfour;

import dev.riderlan.game.GameGraphics;
import dev.riderlan.game.GameLogic;
import dev.riderlan.game.GameState;

import java.util.Arrays;
import java.util.List;

/** State: [0] 42 cells (6 rows of 7, top row first) of '.', 'X' or 'O'; [1] id of the player to move. */
final class ConnectFourLogic implements GameLogic {
    static final int COLUMNS = 7, ROWS = 6;
    private final List<String> players;
    private final char[] cells = new char[COLUMNS * ROWS];
    private String next;
    private String winner = "";
    private int played;

    ConnectFourLogic(List<String> players) {
        if (players.size() != 2) throw new IllegalArgumentException("Connect Four needs two players");
        this.players = List.copyOf(players);
        Arrays.fill(cells, '.');
        next = players.get(0);
    }

    @Override public boolean input(String who, List<String> args) {
        if (!winner.isEmpty()) throw new IllegalArgumentException("That game has ended");
        if (!next.equals(who)) throw new IllegalArgumentException("Wait for your turn");
        int column = GameGraphics.intArg(args, 0, "column");
        if (column < 0 || column >= COLUMNS) throw new IllegalArgumentException("Invalid column");
        int index = -1;
        for (int r = ROWS - 1; r >= 0; r--) {
            if (cells[r * COLUMNS + column] == '.') { index = r * COLUMNS + column; break; }
        }
        if (index < 0) throw new IllegalArgumentException("That column is full");
        char mark = players.get(0).equals(who) ? 'X' : 'O';
        cells[index] = mark;
        played++;
        if (won(index, mark)) winner = who;
        else if (played == cells.length) winner = GameState.DRAW;
        else next = players.get(0).equals(who) ? players.get(1) : players.get(0);
        return true;
    }

    private boolean won(int position, char mark) {
        int row = position / COLUMNS, col = position % COLUMNS;
        int[][] vectors = {{0, 1}, {1, 0}, {1, 1}, {1, -1}};
        for (int[] vector : vectors) {
            int count = 1;
            for (int sign : new int[]{-1, 1}) {
                int r = row + sign * vector[0], c = col + sign * vector[1];
                while (r >= 0 && r < ROWS && c >= 0 && c < COLUMNS && cells[r * COLUMNS + c] == mark) {
                    count++;
                    r += sign * vector[0];
                    c += sign * vector[1];
                }
            }
            if (count >= 4) return true;
        }
        return false;
    }

    @Override public GameState state() {
        String board = String.valueOf(cells);
        return winner.isEmpty() ? GameState.playing(board, next) : new GameState(true, winner, List.of(board, next));
    }
}

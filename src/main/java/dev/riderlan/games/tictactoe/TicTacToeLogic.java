package dev.riderlan.games.tictactoe;

import dev.riderlan.game.GameGraphics;
import dev.riderlan.game.GameLogic;
import dev.riderlan.game.GameState;

import java.util.Arrays;
import java.util.List;

/** State: [0] nine cells of '.', 'X' or 'O'; [1] id of the player to move. */
final class TicTacToeLogic implements GameLogic {
    private static final int[][] LINES = {
            {0, 1, 2}, {3, 4, 5}, {6, 7, 8},
            {0, 3, 6}, {1, 4, 7}, {2, 5, 8},
            {0, 4, 8}, {2, 4, 6}
    };
    private final List<String> players;
    private final char[] cells = new char[9];
    private String next;
    private String winner = "";
    private int played;

    TicTacToeLogic(List<String> players) {
        if (players.size() != 2) throw new IllegalArgumentException("Tic-Tac-Toe needs two players");
        this.players = List.copyOf(players);
        Arrays.fill(cells, '.');
        next = players.get(0);
    }

    @Override public boolean input(String who, List<String> args) {
        if (!winner.isEmpty()) throw new IllegalArgumentException("That game has ended");
        if (!next.equals(who)) throw new IllegalArgumentException("Wait for your turn");
        int square = GameGraphics.intArg(args, 0, "square");
        if (square < 0 || square >= 9 || cells[square] != '.') throw new IllegalArgumentException("Invalid square");
        char mark = players.get(0).equals(who) ? 'X' : 'O';
        cells[square] = mark;
        played++;
        if (won(mark)) winner = who;
        else if (played == 9) winner = GameState.DRAW;
        else next = players.get(0).equals(who) ? players.get(1) : players.get(0);
        return true;
    }

    private boolean won(char mark) {
        for (int[] line : LINES) {
            if (cells[line[0]] == mark && cells[line[1]] == mark && cells[line[2]] == mark) return true;
        }
        return false;
    }

    @Override public GameState state() {
        String board = String.valueOf(cells);
        return winner.isEmpty() ? GameState.playing(board, next) : new GameState(true, winner, List.of(board, next));
    }
}

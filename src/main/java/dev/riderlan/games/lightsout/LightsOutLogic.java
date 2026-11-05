package dev.riderlan.games.lightsout;

import dev.riderlan.game.GameGraphics;
import dev.riderlan.game.GameLogic;
import dev.riderlan.game.GameState;

import java.util.List;
import java.util.Random;

/** Input: [cell index]. State: [0] 25 cells of '1' (on) or '0' (off), row by row; [1] moves so far. */
final class LightsOutLogic implements GameLogic {
    static final int SIZE = 5;
    private final String player;
    private final boolean[] lit = new boolean[SIZE * SIZE];
    private int moves;
    private boolean solved;

    LightsOutLogic(List<String> players, Random random) {
        if (players.size() != 1) throw new IllegalArgumentException("Lights Out is played solo");
        player = players.get(0);
        // Scrambling with real presses guarantees that the puzzle can be solved.
        while (litCount() == 0) {
            for (int i = 0; i < 12; i++) toggle(random.nextInt(lit.length));
        }
    }

    @Override public boolean input(String who, List<String> args) {
        if (solved) throw new IllegalArgumentException("The puzzle is already solved");
        int cell = GameGraphics.intArg(args, 0, "cell");
        if (cell < 0 || cell >= lit.length) throw new IllegalArgumentException("Invalid cell");
        toggle(cell);
        moves++;
        solved = litCount() == 0;
        return true;
    }

    private void toggle(int cell) {
        int row = cell / SIZE, col = cell % SIZE;
        flip(row, col);
        flip(row - 1, col);
        flip(row + 1, col);
        flip(row, col - 1);
        flip(row, col + 1);
    }

    private void flip(int row, int col) {
        if (row >= 0 && row < SIZE && col >= 0 && col < SIZE) lit[row * SIZE + col] ^= true;
    }

    int litCount() {
        int count = 0;
        for (boolean on : lit) if (on) count++;
        return count;
    }

    @Override public GameState state() {
        StringBuilder cells = new StringBuilder(lit.length);
        for (boolean on : lit) cells.append(on ? '1' : '0');
        String[] data = {cells.toString(), Integer.toString(moves)};
        return solved ? GameState.won(player, data) : GameState.playing(data);
    }
}

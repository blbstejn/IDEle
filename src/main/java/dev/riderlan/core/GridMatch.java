package dev.riderlan.core;

import java.util.Arrays;

final class GridMatch extends GameMatch {
    private final char[] cells;
    private String next;
    private String winner = "";
    private int played;

    GridMatch(String id, String game, String first, String second) {
        super(id, game, first, second);
        cells = new char[Rules.TIC_TAC_TOE.equals(game) ? 9 : 42];
        Arrays.fill(cells, '.');
        next = first;
    }

    @Override Packet snapshot() {
        return Packet.of("GAME_GRID", id, String.valueOf(cells), next,
                finished ? "finished" : "playing", winner);
    }

    Packet move(String who, int position) {
        if (finished) throw new IllegalArgumentException("That game has ended");
        if (!next.equals(who)) throw new IllegalArgumentException("Wait for your turn");
        int index;
        if (Rules.TIC_TAC_TOE.equals(game)) {
            if (position < 0 || position >= 9 || cells[position] != '.') {
                throw new IllegalArgumentException("Invalid square");
            }
            index = position;
        } else {
            if (position < 0 || position >= 7) throw new IllegalArgumentException("Invalid column");
            index = -1;
            for (int r = 5; r >= 0; r--) {
                int candidate = r * 7 + position;
                if (cells[candidate] == '.') { index = candidate; break; }
            }
            if (index < 0) throw new IllegalArgumentException("That column is full");
        }
        char token = first.equals(who) ? 'X' : 'O';
        cells[index] = token;
        played++;
        boolean won = Rules.TIC_TAC_TOE.equals(game) ? ticWin(token) : connectWin(index, token);
        if (won) {
            finished = true;
            winner = who;
        } else if (played == cells.length) {
            finished = true;
            winner = "draw";
        } else {
            next = other(who);
        }
        return snapshot();
    }

    private boolean ticWin(char mark) {
        int[][] lines = {
                {0, 1, 2}, {3, 4, 5}, {6, 7, 8},
                {0, 3, 6}, {1, 4, 7}, {2, 5, 8},
                {0, 4, 8}, {2, 4, 6}
        };
        for (int[] line : lines) {
            if (cells[line[0]] == mark && cells[line[1]] == mark && cells[line[2]] == mark) return true;
        }
        return false;
    }

    private boolean connectWin(int position, char mark) {
        int row = position / 7, col = position % 7;
        int[][] vectors = {{0, 1}, {1, 0}, {1, 1}, {1, -1}};
        for (int[] vector : vectors) {
            int count = 1;
            for (int sign : new int[]{-1, 1}) {
                int r = row + sign * vector[0], c = col + sign * vector[1];
                while (r >= 0 && r < 6 && c >= 0 && c < 7 && cells[r * 7 + c] == mark) {
                    count++;
                    r += sign * vector[0];
                    c += sign * vector[1];
                }
            }
            if (count >= 4) return true;
        }
        return false;
    }
}

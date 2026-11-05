package dev.riderlan.games.tictactoe;

import dev.riderlan.game.Checks;
import dev.riderlan.game.GameState;

import java.util.List;

/** Found by naming convention (definition class name + "Test") and run by GameContractTest. */
public final class TicTacToeGameTest {
    private TicTacToeGameTest() { }

    private static void move(TicTacToeLogic game, String who, int square) {
        game.input(who, List.of(Integer.toString(square)));
    }

    public static int run() {
        Checks t = new Checks();
        TicTacToeLogic game = new TicTacToeLogic(List.of("A", "B"));
        t.rejects(() -> move(game, "B", 0), "out-of-turn move");
        t.rejects(() -> move(game, "A", -1), "square below range");
        t.rejects(() -> move(game, "A", 9), "square above range");
        t.rejects(() -> game.input("A", List.of("x")), "non-numeric square");
        t.rejects(() -> game.input("A", List.of()), "missing square");
        move(game, "A", 0);
        t.rejects(() -> move(game, "B", 0), "occupied square");
        move(game, "B", 1);
        move(game, "A", 4);
        move(game, "B", 2);
        move(game, "A", 8);
        GameState state = game.state();
        t.check(state.finished() && "A".equals(state.winner()), "Diagonal Tic-Tac-Toe win was missed");
        t.rejects(() -> move(game, "B", 3), "move after the game ended");

        TicTacToeLogic draw = new TicTacToeLogic(List.of("A", "B"));
        int[] squares = {0, 1, 2, 4, 3, 5, 7, 6, 8};
        for (int i = 0; i < squares.length; i++) move(draw, i % 2 == 0 ? "A" : "B", squares[i]);
        t.check(GameState.DRAW.equals(draw.state().winner()), "Full board without a line must be a draw");
        return t.count();
    }
}

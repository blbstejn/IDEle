package dev.riderlan.games.connectfour;

import dev.riderlan.game.Checks;
import dev.riderlan.game.GameState;

import java.util.List;

/** Found by naming convention (definition class name + "Test") and run by GameContractTest. */
public final class ConnectFourGameTest {
    private ConnectFourGameTest() { }

    private static void drop(ConnectFourLogic game, String who, int column) {
        game.input(who, List.of(Integer.toString(column)));
    }

    public static int run() {
        Checks t = new Checks();
        ConnectFourLogic game = new ConnectFourLogic(List.of("A", "B"));
        int[] moves = {0, 1, 1, 2, 4, 2, 2, 3, 5, 3, 6, 3, 3};
        for (int i = 0; i < moves.length; i++) drop(game, i % 2 == 0 ? "A" : "B", moves[i]);
        GameState state = game.state();
        t.check(state.finished() && "A".equals(state.winner()), "Connect Four diagonal victory was not recognized");
        t.check(state.get(0).charAt(5 * 7) == 'X', "Connect Four disks do not obey gravity");

        ConnectFourLogic column = new ConnectFourLogic(List.of("A", "B"));
        for (int i = 0; i < 6; i++) drop(column, i % 2 == 0 ? "A" : "B", 0);
        t.rejects(() -> drop(column, "A", 0), "drop into a full column");
        t.rejects(() -> drop(column, "A", 7), "column out of range");
        t.rejects(() -> drop(column, "B", 1), "out-of-turn drop");
        return t.count();
    }
}

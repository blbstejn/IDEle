package dev.riderlan.games.lightsout;

import dev.riderlan.game.Checks;
import dev.riderlan.game.GameState;

import java.util.List;
import java.util.Random;

/** Found by naming convention (definition class name + "Test") and run by GameContractTest. */
public final class LightsOutGameTest {
    private LightsOutGameTest() { }

    private static void press(LightsOutLogic game, int cell) {
        game.input("me", List.of(Integer.toString(cell)));
    }

    public static int run() {
        Checks t = new Checks();
        LightsOutLogic game = new LightsOutLogic(List.of("me"), new Random(42));
        String before = game.state().get(0);
        t.check(game.litCount() > 0, "A new puzzle must not start solved");
        t.rejects(() -> press(game, 25), "cell out of range");
        t.rejects(() -> game.input("me", List.of("centre")), "non-numeric cell");
        press(game, 12);
        String after = game.state().get(0);
        int changed = 0;
        for (int i = 0; i < before.length(); i++) if (before.charAt(i) != after.charAt(i)) changed++;
        t.check(changed == 5, "A centre press must toggle itself and four neighbours");
        press(game, 12);
        t.check(before.equals(game.state().get(0)), "Pressing twice must restore the board");
        t.check("2".equals(game.state().get(1)), "Moves were not counted");

        // Replaying the scramble's presses solves the puzzle (each press undoes itself).
        LightsOutLogic replay = new LightsOutLogic(List.of("me"), new Random(7));
        Random same = new Random(7);
        for (int i = 0; i < 12; i++) press(replay, same.nextInt(25));
        GameState solved = replay.state();
        t.check(solved.finished() && "me".equals(solved.winner()), "Switching every light off must win");
        t.rejects(() -> press(replay, 0), "move after the puzzle was solved");
        return t.count();
    }
}

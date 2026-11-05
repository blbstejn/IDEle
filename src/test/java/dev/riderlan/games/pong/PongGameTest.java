package dev.riderlan.games.pong;

import dev.riderlan.game.Checks;
import dev.riderlan.game.GameState;

import java.util.List;

/** Found by naming convention (definition class name + "Test") and run by GameContractTest. */
public final class PongGameTest {
    private PongGameTest() { }

    public static int run() {
        Checks t = new Checks();
        PongLogic game = new PongLogic(List.of("A", "B"));
        t.rejects(() -> game.input("A", List.of("-2")), "paddle speed below range");
        t.rejects(() -> game.input("B", List.of("2")), "paddle speed above range");
        t.rejects(() -> game.input("A", List.of("up")), "non-numeric movement");
        double start = Double.parseDouble(game.state().get(0));
        game.input("A", List.of("-1"));
        game.input("B", List.of("1"));
        game.tick();
        t.check(Double.parseDouble(game.state().get(0)) < start, "Left paddle did not move up");
        t.check(Double.parseDouble(game.state().get(1)) > start, "Right paddle did not move down");
        for (int i = 0; i < 30000 && !game.state().finished(); i++) game.tick();
        GameState end = game.state();
        t.check(end.finished(), "Pong did not finish after 30000 ticks");
        int left = Integer.parseInt(end.get(4)), right = Integer.parseInt(end.get(5));
        t.check(left >= PongLogic.TARGET || right >= PongLogic.TARGET, "Pong finished without reaching the target");
        t.check("A".equals(end.winner()) || "B".equals(end.winner()), "Pong winner must be one of the players");
        t.check(!game.tick(), "A finished Pong match must not keep ticking");
        return t.count();
    }
}

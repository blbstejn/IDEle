package dev.riderlan.games.pong;

import dev.riderlan.game.GameDefinition;
import dev.riderlan.game.GameLogic;
import dev.riderlan.game.GameView;
import dev.riderlan.game.GameViewContext;

import java.util.List;

public final class PongGame implements GameDefinition {
    @Override public String id() { return "pong"; }
    @Override public String displayName() { return "Pong"; }
    @Override public int minPlayers() { return 2; }
    @Override public int maxPlayers() { return 2; }
    @Override public boolean realtime() { return true; }
    @Override public String help() {
        return "Click the arena to focus it, then hold W/S or Up/Down. First to " + PongLogic.TARGET + " wins.";
    }
    @Override public GameLogic createLogic(List<String> players) { return new PongLogic(players); }
    @Override public GameView createView(GameViewContext context) { return new PongView(context); }
}

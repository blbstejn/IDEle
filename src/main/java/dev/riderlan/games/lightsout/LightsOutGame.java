package dev.riderlan.games.lightsout;

import dev.riderlan.game.GameDefinition;
import dev.riderlan.game.GameLogic;
import dev.riderlan.game.GameView;
import dev.riderlan.game.GameViewContext;

import java.util.List;

/** Single-player reference game: the smallest complete example of a solo module. */
public final class LightsOutGame implements GameDefinition {
    @Override public String id() { return "lights-out"; }
    @Override public String displayName() { return "Lights Out"; }
    @Override public int minPlayers() { return 1; }
    @Override public int maxPlayers() { return 1; }
    @Override public String help() {
        return "Clicking a light toggles it and its neighbours. Switch every light off.";
    }
    @Override public GameLogic createLogic(List<String> players) { return new LightsOutLogic(players, new java.util.Random()); }
    @Override public GameView createView(GameViewContext context) { return new LightsOutView(context); }
}

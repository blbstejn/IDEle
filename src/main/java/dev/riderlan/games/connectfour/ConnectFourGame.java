package dev.riderlan.games.connectfour;

import dev.riderlan.game.GameDefinition;
import dev.riderlan.game.GameLogic;
import dev.riderlan.game.GameView;
import dev.riderlan.game.GameViewContext;

import java.util.List;

public final class ConnectFourGame implements GameDefinition {
    @Override public String id() { return "connect-four"; }
    @Override public String displayName() { return "Connect Four"; }
    @Override public int minPlayers() { return 2; }
    @Override public int maxPlayers() { return 2; }
    @Override public String help() { return "Player X starts. Click a column on your turn; four in a row wins."; }
    @Override public GameLogic createLogic(List<String> players) { return new ConnectFourLogic(players); }
    @Override public GameView createView(GameViewContext context) { return new ConnectFourView(context); }
}

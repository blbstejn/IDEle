package dev.riderlan.games.tictactoe;

import dev.riderlan.game.GameDefinition;
import dev.riderlan.game.GameLogic;
import dev.riderlan.game.GameView;
import dev.riderlan.game.GameViewContext;

import java.util.List;

public final class TicTacToeGame implements GameDefinition {
    @Override public String id() { return "tic-tac-toe"; }
    @Override public String displayName() { return "Tic-Tac-Toe"; }
    @Override public int minPlayers() { return 2; }
    @Override public int maxPlayers() { return 2; }
    @Override public String help() { return "Player X starts. Click a free square on your turn."; }
    @Override public GameLogic createLogic(List<String> players) { return new TicTacToeLogic(players); }
    @Override public GameView createView(GameViewContext context) { return new TicTacToeView(context); }
}

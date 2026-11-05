package dev.riderlan.core;

import dev.riderlan.game.GameDefinition;
import dev.riderlan.game.GameLogic;
import dev.riderlan.game.GameState;

import java.util.ArrayList;
import java.util.List;

/**
 * Host-side wrapper around one game module's logic. Isolates the session from game bugs:
 * invalid input is reported to the sender, anything else ends only this match.
 */
final class Match {
    final String id;
    final GameDefinition game;
    final List<String> players;
    private final GameLogic logic;
    boolean finished;

    /** A game module failed in a way that is not the player's fault. */
    static final class Failure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        Failure(String message, Throwable cause) { super(message, cause); }
    }

    Match(String id, GameDefinition game, List<String> players) {
        this.id = id;
        this.game = game;
        this.players = List.copyOf(players);
        try {
            this.logic = game.createLogic(this.players);
        } catch (RuntimeException broken) {
            throw new Failure(game.displayName() + " could not start", broken);
        }
    }

    boolean contains(String player) { return players.contains(player); }

    /**
     * Input that arrives after the end (e.g. a key released as the match finished) is ignored.
     * @throws IllegalArgumentException for input the game rejected
     */
    boolean input(String player, List<String> args) {
        if (finished) return false;
        try {
            return logic.input(player, args);
        } catch (IllegalArgumentException rejected) {
            throw rejected;
        } catch (RuntimeException broken) {
            throw new Failure(game.displayName() + " failed", broken);
        }
    }

    boolean tick() {
        if (finished || !game.realtime()) return false;
        try {
            return logic.tick();
        } catch (RuntimeException broken) {
            throw new Failure(game.displayName() + " failed", broken);
        }
    }

    /** Current snapshot; marks the match finished once the game says so. */
    GameState state() {
        GameState state;
        try {
            state = logic.state();
        } catch (RuntimeException broken) {
            throw new Failure(game.displayName() + " produced an invalid state", broken);
        }
        if (state == null) throw new Failure(game.displayName() + " produced no state", null);
        String winner = state.winner();
        if (state.finished() && !winner.equals(GameState.DRAW) && !winner.isEmpty() && !players.contains(winner)) {
            throw new Failure(game.displayName() + " declared an unknown winner", null);
        }
        if (state.finished()) finished = true;
        return state;
    }

    Packet startPacket() {
        List<String> fields = new ArrayList<>();
        fields.add(id);
        fields.add(game.id());
        fields.addAll(players);
        return new Packet("GAME_START", fields);
    }

    Packet statePacket() { return GamePackets.state(id, state()); }
}

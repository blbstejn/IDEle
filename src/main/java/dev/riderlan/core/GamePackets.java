package dev.riderlan.core;

import dev.riderlan.game.GameState;

import java.util.ArrayList;
import java.util.List;

/**
 * Wire format of game traffic, shared by every game module:
 * <ul>
 *   <li>{@code GAME_START matchId gameId player...}</li>
 *   <li>{@code GAME_STATE matchId playing|finished winner data...}</li>
 *   <li>{@code GAME_INPUT matchId arg...} (client to host)</li>
 *   <li>{@code GAME_OVER matchId reason} (match aborted)</li>
 * </ul>
 */
public final class GamePackets {
    /** Upper bound on input arguments per GAME_INPUT packet. */
    public static final int MAX_INPUT_ARGS = 16;

    private GamePackets() { }

    static Packet state(String matchId, GameState state) {
        List<String> fields = new ArrayList<>(state.size() + 3);
        fields.add(matchId);
        fields.add(state.finished() ? "finished" : "playing");
        fields.add(state.winner());
        fields.addAll(state.data());
        return new Packet("GAME_STATE", fields);
    }

    /** Parses a GAME_STATE packet; returns null when it is malformed. */
    public static GameState parseState(Packet packet) {
        if (!"GAME_STATE".equals(packet.type()) || packet.fields().size() < 3) return null;
        String phase = packet.get(1);
        if (!"finished".equals(phase) && !"playing".equals(phase)) return null;
        try {
            return new GameState("finished".equals(phase), packet.get(2),
                    packet.fields().subList(3, packet.fields().size()));
        } catch (IllegalStateException malformed) {
            return null;
        }
    }

    static Packet input(String matchId, List<String> args) {
        if (args.size() > MAX_INPUT_ARGS) throw new IllegalArgumentException("Too many game input values");
        List<String> fields = new ArrayList<>(args.size() + 1);
        fields.add(matchId);
        fields.addAll(args);
        return new Packet("GAME_INPUT", fields);
    }
}

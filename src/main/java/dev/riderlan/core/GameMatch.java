package dev.riderlan.core;

/** Authoritative game state always lives on the host; only snapshots travel over the LAN. */
abstract class GameMatch {
    final String id;
    final String game;
    final String first;
    final String second;
    boolean finished;

    GameMatch(String id, String game, String first, String second) {
        this.id = id;
        this.game = game;
        this.first = first;
        this.second = second;
    }

    boolean contains(String player) {
        return first.equals(player) || second.equals(player);
    }

    String other(String player) {
        return first.equals(player) ? second : first;
    }

    Packet startPacket() {
        return Packet.of("GAME_START", id, game, first, second);
    }

    abstract Packet snapshot();
}

package dev.riderlan.core;

import dev.riderlan.game.GameDefinition;
import dev.riderlan.game.GameLogic;
import dev.riderlan.game.GameState;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Runs a single-player game on this machine, without any network session.
 * Callbacks may arrive on a background thread for real-time games.
 */
public final class SoloGame implements AutoCloseable {
    public static final String PLAYER_ID = "solo";

    private final Match match;
    private final Consumer<GameState> onState;
    private final Consumer<String> onFailure;
    private final ScheduledExecutorService ticker;
    private boolean closed;

    public SoloGame(GameDefinition game, Consumer<GameState> onState, Consumer<String> onFailure) {
        if (game.minPlayers() != 1) throw new IllegalArgumentException(game.displayName() + " cannot be played solo");
        this.onState = onState;
        this.onFailure = onFailure;
        this.match = new Match("solo", game, List.of(PLAYER_ID));
        if (game.realtime()) {
            ticker = Executors.newSingleThreadScheduledExecutor(work -> {
                Thread thread = new Thread(work, "IDEle-solo-tick");
                thread.setDaemon(true);
                return thread;
            });
            ticker.scheduleAtFixedRate(this::tick, GameLogic.TICK_MILLIS, GameLogic.TICK_MILLIS, TimeUnit.MILLISECONDS);
        } else ticker = null;
    }

    /** Publishes the initial state. Call once the view is ready. */
    public synchronized void start() {
        publish();
    }

    /** @throws IllegalArgumentException for input the game rejected */
    public synchronized void input(List<String> args) {
        if (closed) return;
        try {
            if (match.input(PLAYER_ID, args)) publish();
        } catch (Match.Failure broken) {
            fail(broken);
        }
    }

    private synchronized void tick() {
        if (closed) return;
        try {
            if (match.tick()) publish();
        } catch (Match.Failure broken) {
            fail(broken);
        }
    }

    private void publish() {
        try {
            GameState state = match.state();
            onState.accept(state);
            if (state.finished()) close();
        } catch (Match.Failure broken) {
            fail(broken);
        }
    }

    private void fail(Match.Failure broken) {
        close();
        onFailure.accept(broken.getMessage());
    }

    @Override public synchronized void close() {
        closed = true;
        if (ticker != null) ticker.shutdownNow();
    }
}

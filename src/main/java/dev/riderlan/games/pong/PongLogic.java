package dev.riderlan.games.pong;

import dev.riderlan.game.GameGraphics;
import dev.riderlan.game.GameLogic;
import dev.riderlan.game.GameState;

import java.util.List;
import java.util.Locale;

/**
 * 400x240 logical arena, one step per tick (25 per second), first player to {@link #TARGET}.
 * Input: [direction] of -1 (up), 0 or 1 (down).
 * State: [0] left paddle y, [1] right paddle y, [2] ball x, [3] ball y, [4] left score, [5] right score.
 */
final class PongLogic implements GameLogic {
    static final int WIDTH = 400, HEIGHT = 240, PADDLE_H = 50, TARGET = 7;
    static final int PADDLE_INSET = 16, PADDLE_WIDTH = 8, BALL_R = 6;

    private final List<String> players;
    private double leftY = (HEIGHT - PADDLE_H) / 2.0;
    private double rightY = leftY;
    private double x = WIDTH / 2.0, y = HEIGHT / 2.0;
    private double vx = 4.2, vy = 2.3;
    private int leftInput, rightInput;
    private int leftScore, rightScore;
    private int pauseTicks = 20;
    private String winner = "";

    PongLogic(List<String> players) {
        if (players.size() != 2) throw new IllegalArgumentException("Pong needs two players");
        this.players = List.copyOf(players);
    }

    @Override public boolean input(String who, List<String> args) {
        if (!winner.isEmpty()) return false;
        int direction = GameGraphics.intArg(args, 0, "movement");
        if (direction < -1 || direction > 1) throw new IllegalArgumentException("Invalid movement");
        if (players.get(0).equals(who)) leftInput = direction;
        else rightInput = direction;
        return false; // The next tick broadcasts the result.
    }

    @Override public boolean tick() {
        if (!winner.isEmpty()) return false;
        leftY = clamp(leftY + leftInput * 6.0, 0, HEIGHT - PADDLE_H);
        rightY = clamp(rightY + rightInput * 6.0, 0, HEIGHT - PADDLE_H);
        if (pauseTicks > 0) { pauseTicks--; return true; }
        x += vx;
        y += vy;
        if (y < BALL_R) { y = BALL_R; vy = Math.abs(vy); }
        if (y > HEIGHT - BALL_R) { y = HEIGHT - BALL_R; vy = -Math.abs(vy); }
        if (vx < 0 && x - BALL_R <= PADDLE_INSET + PADDLE_WIDTH &&
                x >= PADDLE_INSET && y >= leftY - BALL_R && y <= leftY + PADDLE_H + BALL_R) {
            bounce(leftY, true);
        }
        if (vx > 0 && x + BALL_R >= WIDTH - PADDLE_INSET - PADDLE_WIDTH &&
                x <= WIDTH - PADDLE_INSET && y >= rightY - BALL_R && y <= rightY + PADDLE_H + BALL_R) {
            bounce(rightY, false);
        }
        if (x < -BALL_R) {
            rightScore++;
            reset(false);
        } else if (x > WIDTH + BALL_R) {
            leftScore++;
            reset(true);
        }
        if (leftScore >= TARGET || rightScore >= TARGET) winner = leftScore >= TARGET ? players.get(0) : players.get(1);
        return true;
    }

    private void bounce(double paddleY, boolean fromLeft) {
        double normalized = (y - paddleY - PADDLE_H / 2.0) / (PADDLE_H / 2.0);
        vx = (fromLeft ? 1 : -1) * Math.min(8.0, Math.abs(vx) + 0.3);
        vy = clamp(normalized * 4.0, -4.5, 4.5);
        if (Math.abs(vy) < 0.9) vy = vy < 0 ? -0.9 : 0.9;
        x = fromLeft ? PADDLE_INSET + PADDLE_WIDTH + BALL_R + 1 : WIDTH - PADDLE_INSET - PADDLE_WIDTH - BALL_R - 1;
    }

    private void reset(boolean towardsRight) {
        x = WIDTH / 2.0;
        y = HEIGHT / 2.0;
        vx = towardsRight ? 4.2 : -4.2;
        vy = (leftScore + rightScore) % 2 == 0 ? 2.3 : -2.3;
        pauseTicks = 20;
    }

    private static double clamp(double n, double a, double b) {
        return Math.max(a, Math.min(b, n));
    }

    @Override public GameState state() {
        List<String> data = List.of(f(leftY), f(rightY), f(x), f(y),
                Integer.toString(leftScore), Integer.toString(rightScore));
        return new GameState(!winner.isEmpty(), winner, data);
    }

    private static String f(double d) {
        return String.format(Locale.ROOT, "%.1f", d);
    }
}

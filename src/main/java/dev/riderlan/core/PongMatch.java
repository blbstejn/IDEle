package dev.riderlan.core;

import java.util.Locale;

/** 400x240 logical arena, 25 authoritative frames per second, first player to seven. */
final class PongMatch extends GameMatch {
    static final int WIDTH = 400;
    static final int HEIGHT = 240;
    static final int PADDLE_H = 50;
    private static final int PADDLE_INSET = 16;
    private static final int PADDLE_WIDTH = 8;
    private static final int BALL_R = 6;

    private double leftY = (HEIGHT - PADDLE_H) / 2.0;
    private double rightY = leftY;
    private double x = WIDTH / 2.0, y = HEIGHT / 2.0;
    private double vx = 4.2, vy = 2.3;
    private int leftInput, rightInput;
    private int leftScore, rightScore;
    private int pauseTicks = 20;
    private String winner = "";

    PongMatch(String id, String first, String second) {
        super(id, Rules.PONG, first, second);
    }

    void control(String who, int direction) {
        if (finished || !contains(who)) return;
        if (direction < -1 || direction > 1) throw new IllegalArgumentException("Invalid movement");
        if (first.equals(who)) leftInput = direction;
        else rightInput = direction;
    }

    void tick() {
        if (finished) return;
        leftY = clamp(leftY + leftInput * 6.0, 0, HEIGHT - PADDLE_H);
        rightY = clamp(rightY + rightInput * 6.0, 0, HEIGHT - PADDLE_H);
        if (pauseTicks > 0) { pauseTicks--; return; }
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
        if (leftScore >= 7 || rightScore >= 7) {
            finished = true;
            winner = leftScore >= 7 ? first : second;
        }
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

    @Override Packet snapshot() {
        return Packet.of("GAME_PONG", id,
                i(leftY), i(rightY), i(x), i(y),
                Integer.toString(leftScore), Integer.toString(rightScore),
                finished ? "finished" : "playing", winner);
    }

    private static String i(double d) {
        return String.format(Locale.ROOT, "%.1f", d);
    }
}

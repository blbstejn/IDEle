package dev.riderlan.game;

/** Minimal assertion helpers for game tests; the project deliberately uses no test framework. */
public final class Checks {
    private int count;

    public void check(boolean valid, String description) {
        count++;
        if (!valid) throw new AssertionError(description);
    }

    /** The command must be rejected as invalid player input. */
    public void rejects(Runnable command, String description) {
        try {
            command.run();
        } catch (IllegalArgumentException expected) {
            count++;
            return;
        }
        throw new AssertionError("Accepted: " + description);
    }

    public int count() { return count; }
}

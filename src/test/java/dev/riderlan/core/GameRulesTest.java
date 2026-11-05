package dev.riderlan.core;

/** Deterministic board validation, including turn enforcement, gravity and diagonal wins. */
final class GameRulesTest {
    private GameRulesTest() { }
    private static int checks;

    private static void check(boolean valid, String description) {
        checks++;
        if (!valid) throw new AssertionError(description);
    }

    private static void expectInvalid(Runnable command) {
        try {
            command.run();
            throw new AssertionError("Illegal game command was accepted");
        } catch (IllegalArgumentException expected) {
            checks++;
        }
    }

    private static void ticTacToe() {
        GridMatch match = new GridMatch("g1", Rules.TIC_TAC_TOE, "A", "B");
        expectInvalid(() -> match.move("B", 0)); // Out-of-turn move.
        expectInvalid(() -> match.move("A", -1));
        expectInvalid(() -> match.move("A", 9));
        match.move("A", 0);
        expectInvalid(() -> match.move("B", 0)); // Occupied square.
        match.move("B", 1);
        match.move("A", 4);
        match.move("B", 2);
        match.move("A", 8);
        check(match.finished, "Diagonal Tic-Tac-Toe win was missed");
        check("A".equals(match.snapshot().get(4)), "Wrong Tic-Tac-Toe winner");
        expectInvalid(() -> match.move("B", 3)); // Finished games are immutable.
    }

    private static void connectFour() {
        GridMatch match = new GridMatch("g2", Rules.CONNECT_FOUR, "A", "B");
        int[] moves = {0, 1, 1, 2, 4, 2, 2, 3, 5, 3, 6, 3, 3};
        for (int i = 0; i < moves.length; i++) {
            String player = (i % 2 == 0) ? "A" : "B";
            match.move(player, moves[i]);
        }
        check(match.finished && "A".equals(match.snapshot().get(4)),
                "Connect Four diagonal victory was not recognized");
        check(match.snapshot().get(1).charAt(5 * 7) == 'X', "Connect Four disks do not obey gravity");

        GridMatch column = new GridMatch("g3", Rules.CONNECT_FOUR, "A", "B");
        for (int i = 0; i < 6; i++) column.move((i % 2 == 0) ? "A" : "B", 0);
        expectInvalid(() -> column.move("A", 0));
        expectInvalid(() -> column.move("A", 7));
    }

    private static void pong() {
        PongMatch match = new PongMatch("g4", "A", "B");
        expectInvalid(() -> match.control("A", -2));
        expectInvalid(() -> match.control("B", 2));
        match.control("A", -1);
        match.control("B", 1);
        for (int i = 0; i < 30000 && !match.finished; i++) match.tick();
        check(match.finished, "Pong did not finish after 30000 ticks");
        Packet finalState = match.snapshot();
        check("finished".equals(finalState.get(7)), "Pong final state was not transmitted");
        int left = Integer.parseInt(finalState.get(5));
        int right = Integer.parseInt(finalState.get(6));
        check(left >= 7 || right >= 7, "Pong finished without reaching 7 points");
        check("A".equals(finalState.get(8)) || "B".equals(finalState.get(8)),
                "Pong winner must be one of the players");
    }

    static void run() {
        ticTacToe();
        connectFour();
        pong();
        System.out.println("PASS — " + checks + " game-rule assertions");
    }
}

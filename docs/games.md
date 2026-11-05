# Writing a game for IDEle

Games are plugin-style modules. Each game lives in its own package and talks to the rest of IDEle only through the small API in `dev.riderlan.game`. Session, chat and UI code never mention a specific game, so you can build or rework a game on its own branch while others work on the rest of the plugin.

## How a game is wired up

| Piece | Runs on | Job |
| --- | --- | --- |
| `GameDefinition` | everywhere | Id, name, player counts, help text; creates the logic and the view |
| `GameLogic` | the host (or locally for solo) | The authoritative rules: validates input, produces `GameState` |
| `GameView` | every player | Draws the latest `GameState` and sends input with `context.send(...)` |
| `GameState` | over the network | `finished`, `winner` (player id, `"draw"` or empty) and a list of string fields that you define |

The flow is the same for every game: a click or key in the view calls `send("3")`. That reaches `GameLogic.input(player, ["3"])` on the host, which either throws `IllegalArgumentException("Wait for your turn")` (shown to that player) or returns `true`. The host then sends `state()` to every player's view.

The core handles everything else: challenges, encryption, checking that both players have the game installed, ending a match when someone leaves, and containing crashes. If your logic throws anything other than `IllegalArgumentException`, only that match ends, with a "Game error" message.

## Branch workflow

1. Branch from `main`, e.g. `game/snake` for a new game or `game/pong-powerups` for a change to Pong.
2. Keep your changes inside your game's package, its test, and the one registration line below.
3. Run the tests (see below) and open a pull request.

Two game branches only touch the same file in one place, the registration file, and adding one line each to that file merges cleanly.

## Step by step: adding a game

Use `dev.riderlan.games.lightsout` (solo) or `dev.riderlan.games.tictactoe` (two players) as a template.

1. **Create the package** `src/main/java/dev/riderlan/games/<yourgame>/` with three classes:

   ```java
   public final class SnakeGame implements GameDefinition {
       @Override public String id() { return "snake"; }          // stable, a-z 0-9 -, max 32 chars; never rename
       @Override public String displayName() { return "Snake"; }
       @Override public int minPlayers() { return 1; }            // 1 = can be played solo
       @Override public int maxPlayers() { return 1; }            // 2 = can be challenged
       @Override public boolean realtime() { return true; }       // get tick() every 40 ms
       @Override public String help() { return "Arrow keys steer."; }
       @Override public GameLogic createLogic(List<String> players) { return new SnakeLogic(players); }
       @Override public GameView createView(GameViewContext context) { return new SnakeView(context); }
   }
   ```

   - **`SnakeLogic implements GameLogic`:** `input(player, args)` and, for real-time games, `tick()` return `true` when the state should be sent. `state()` returns `GameState.playing(...)`, `GameState.won(playerId, ...)` or `GameState.draw(...)`.
   - **`SnakeView implements GameView`:** `component()` returns your Swing panel, `update(state)` repaints it, and `status(state)` returns the line shown under the arena, e.g. whose turn it is. `stop()` is called when the match ends, so release any held keys or timers there.

2. **Register it** with one line, in alphabetical order, in `src/main/resources/META-INF/services/dev.riderlan.game.GameDefinition`:

   ```
   dev.riderlan.games.snake.SnakeGame
   ```

3. **Test it** in `src/test/java/dev/riderlan/games/<yourgame>/SnakeGameTest.java`. The class name is your definition's name plus `Test`, and it needs a `public static int run()` that returns the number of checks. Use `dev.riderlan.game.Checks`. The test is found by name, so you don't need to edit any shared test file. Every installed game also goes through a shared contract check: it must start in a running state, and its view must build and render.

## Rules of thumb

- **Validate every input.** Arguments come from other machines. Use `GameGraphics.intArg(args, 0, "column")` to parse numbers, and reject anything out of range with `IllegalArgumentException`.
- **Keep state small.** A `GameState` holds at most 48 fields and 4,000 characters in total. Send the board, not a history.
- **Never trust the view.** The view decides only what to *send*; the logic decides what *happens*.
- **No threads or I/O in logic.** Calls are already serialized; real-time games use `tick()`.
- **Use `GameGraphics`** for theme colours and grid hit-testing, so all games match the IDE theme.
- **Keep the id fixed.** Players running different builds only see each other's games when the ids match, and a challenge is refused if either player lacks the game.
- **Changing an existing game's state format** affects every player of that game. Make sure everyone updates together, or give the reworked game a new id.

## Running the tests

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\build-offline.ps1
```

Or with Gradle: `gradle integrationTest`. Each game prints its own `PASS` line.

## Current limits

Matches have one or two players: solo games run locally, and two-player games start from a challenge. Games for three or more players would need a lobby in the core; the API already passes a list of players, so game modules won't need to change for it.

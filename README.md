# IDEle — LAN chat and games for JetBrains Rider

**IDEle** is a Rider tool-window plugin for teams on the same private network. It provides encrypted shared chat, direct messages, host-approved sessions of 2–16 people, and a pluggable set of games: Tic-Tac-Toe, Connect Four and Pong against other participants, plus Lights Out to play solo. No cloud server is needed.

> **Security note.** The transport uses Java's standard X25519, HMAC-SHA256 and AES-256-GCM primitives, but the protocol combining them is custom and has not been independently audited. Don't use IDEle for secrets, credentials or sensitive communications, or on untrusted or public networks.

## Install

1. Build `IDEle-1.2.0.zip` (see [Building](#building)) or download it from the GitHub releases.
2. In Rider: **Settings → Plugins → ⚙ → Install Plugin from Disk**, choose the ZIP (don't extract it), and restart if asked.
3. Open the **IDEle** tool window from the right-hand stripe or **View → Tool Windows**.

Everyone in a session needs IDEle 1.2.x (protocol 3). Earlier versions can't join 1.2 sessions.

## Hosting and joining

**Host:** enter a username, TCP port (default **43137**) and maximum participants (2–16, including you), then press **Host Session**. IDEle generates a random session key. Use **Copy session key** to share it privately with the people you invite, together with your LAN IP (shown in the Session tab) or a hostname that resolves to it. Approve or reject each join request. Pending requests expire after about two minutes.

**Join:** enter a unique username, the host's IP or hostname, the port and the 43-character session key, then press **Join Session** and wait for approval. A wrong key is rejected before the host sees a request. The key is kept in memory only and is cleared from the field when you leave.

**Firewall:** allow the port only through a Windows **Private** network firewall rule, and never forward it to the internet. Nothing is stored on disk; closing the host session disconnects everyone.

## Chat and games

- **Chat:** choose **Everyone** for shared chat or a participant for a direct message. Direct messages are relayed and readable by the host; they are not end-to-end encrypted.
- **Head-to-head games:** in the **Games** tab, pick a game and an opponent and press **Challenge**. The host runs the rules for every match, and each player sees the shared state. You can only challenge someone who has the same game installed.
- **Solo games:** pick a game marked *solo* and press **Play solo**. Solo games run locally and work without a session.
- **Pong:** click the arena, then hold **W/S** or **↑/↓**. First to seven wins.

## Adding or changing a game

Each game is a self-contained module in its own package under `src/main/java/dev/riderlan/games/`, built on the small API in `dev.riderlan.game`. You can develop a new game, or rework an existing one, on its own branch without touching the session, chat or UI code. See **[docs/games.md](docs/games.md)** for the step-by-step guide.

## Project layout

| Path | Contents |
| --- | --- |
| `src/main/java/dev/riderlan/core` | Sessions, encrypted transport, packet codec, generic match runner |
| `src/main/java/dev/riderlan/game` | Game module API: `GameDefinition`, `GameLogic`, `GameView`, `GameState`, `GameRegistry` |
| `src/main/java/dev/riderlan/games/*` | One package per game |
| `src/main/java/dev/riderlan/ui` | The Swing tool window |
| `src/main/resources/META-INF` | `plugin.xml` and the game registration file |
| `src/test/java` | Protocol, session, UI and game tests (no external test libraries) |
| `offline-stubs` | Compile-time IntelliJ signatures for the offline build; never packaged |

The plugin ID (`dev.riderlan`) and Java package names are kept from the plugin's earlier name, Rider LAN, so existing installations upgrade in place.

## Building

**Gradle** (JDK 21+, Gradle 9+, network access to JetBrains repositories) compiles against the Rider 2024.2 SDK and runs the plugin verifier:

```powershell
gradle integrationTest buildPlugin verifyPlugin
```

The plugin ZIP is written to `build/distributions/`.

**Offline** (JDK 21 with `java`, `javac` and `jar`) compiles against the stubs in `offline-stubs/`, runs all tests and writes `IDEle-1.2.0.zip` to the project root:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\build-offline.ps1
```

On Linux or macOS: `sh tools/build-offline.sh` (also needs `zip`).

The plugin supports Rider 2024.2 (build 242) and later.

## Security details

- Each client's connection to the host is encrypted and integrity-protected. Only people with the session key can connect, and the host must still approve each of them.
- The session key is a shared group secret, not a personal identity. Anyone who has it can try to join or impersonate a member. If it leaks, end the session and host a new one.
- IP addresses are not hidden: the host and the network can see who connects, whether you use an IP address or a hostname.
- Copying the key may store it in Windows clipboard history. Share it over a trusted channel.
- A slow or unresponsive participant is disconnected instead of freezing Rider or the other players.

## Troubleshooting

- **Can't authenticate:** check that everyone uses IDEle 1.2.x and the same host, port and key.
- **Can't connect:** check LAN routing, hostname resolution, the Windows firewall rule and that the host is listening on the port.
- **No join request appears:** the host only sees requests after the key check succeeds, so a wrong key never shows up.
- **"… does not have … installed":** the other player's IDEle build doesn't include that game. Both players need it.
- **Room full:** the host sets the limit (2–16, including the host).
- **Pong doesn't respond to keys:** click the arena first so it has keyboard focus.

More test and review notes are in [docs/verification.md](docs/verification.md) and [docs/review-1.1.1.md](docs/review-1.1.1.md).

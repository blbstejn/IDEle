# IDEle v1.1.1 — encrypted LAN chat and games for JetBrains Rider

**IDEle** (previously **Rider LAN**) is a Windows-first Rider tool-window plugin for collaboration on a private LAN. It provides shared chat, host-relayed direct messages, approval-controlled sessions, Tic-Tac-Toe, Connect Four and Pong. A host can configure a total room capacity of **2–16 participants, including the host**. No cloud server is required.

> **Preview and security warning.** The distributable included with this project is an **unsigned, offline-built preview**, not a Rider-SDK-verified production release. The v1.1 transport uses Java's standard X25519, HMAC-SHA256 and AES-256-GCM primitives, but the *protocol combining them is custom and has not been independently security-audited*. Do **not** use it yet for company secrets, credentials, legally sensitive communications or untrusted/public networks. For production chat, independently review the cryptography and validate the plugin with real Rider builds.

## Reliability and security review in v1.1.1

- Replaced synchronous client socket writes with a **bounded asynchronous queue**. Sending chat, game commands and closing a connection no longer waits for a stalled remote TCP receiver on Rider's Swing UI thread. An outbound overflow disconnects the affected client rather than freezing Rider.
- **Leave / project disposal now cancels in-progress connections** by closing the pending socket, including while the peer never finishes the authentication handshake.
- The session-key input is **cleared when a session ends or a join is cancelled**. Clipboard copies are not automatically erased.
- Usernames reject Unicode formatting controls, line separators and unpaired surrogates, and the chat log escapes embedded controls/newlines to prevent messages visually impersonating system notices.
- The offline ZIP build now deletes any existing destination archive first; removed classes can no longer survive in-place `zip` updates. Source and tests compile with `-Xlint:all -Werror`.
- Added regression checks for slow/non-reading hosts, mid-handshake cancellation, key cleanup and log spoofing. **94 local assertions pass**.
- **No encrypted protocol change:** v1.1.1 still uses protocol 2. Existing v1.1.0 clients and v1.1.1 clients share the same wire protocol, though upgrading all clients is strongly recommended.

## Features introduced in v1.1.0

- Renamed the visible plugin, tool window and ZIP to **IDEle**. The plugin ID remains `dev.riderlan`, so Rider can treat it as an upgrade; Java package names are kept stable for compatibility.
- Configurable session cap of **2–16**, including the host. Expanded roster protocol framing to carry all 16 names.
- **Authenticated encryption in transit for every network application packet**, including shared/direct chat, usernames, join approvals, participant rosters and games.
- A fresh 256-bit session key is generated for each hosted session; the host shares it **out of band** with invited members. The key is required before the host even sees a join request, and **the host must also approve** the participant.
- Connection uses an ephemeral X25519 exchange authenticated with the shared key, directional AES-256-GCM encryption with per-frame counters and message integrity protection. Plaintext v1.0.x clients cannot connect or force a downgrade.
- Joining now accepts a **host IPv4 address or working DNS hostname**. A DNS name makes an IP easier to remember; **DNS does not hide any IP address**. No DNS server was added.
- Pending approvals expire on the host scheduler without using socket timeouts mid-frame; approved clients can remain idle.

## Install / upgrade

1. Use the **`IDEle-1.1.1.zip`** file without extracting it. All participants must use IDEle v1.1.x (encrypted protocol 2); using v1.1.1 on all machines is recommended.
2. Rider: **Settings → Plugins → gear icon → Install Plugin from Disk**. Restart if requested.
3. Open the **IDEle** tool window from the right tool-window stripe or **View → Tool Windows**.
4. The internal plugin ID is still `dev.riderlan`, matching the earlier Rider LAN preview. If Rider refuses to upgrade from v1.0.1, disable/uninstall that preview, restart Rider, then install v1.1.1. Do not install both versions simultaneously.

**Version incompatibility:** IDEle v1.1.x uses encrypted protocol `2` and **cannot join RiderLAN v1.0.0/v1.0.1 sessions**. Everyone must use v1.1.x or later with a compatible protocol.

## Hosting and joining

**Host:** Choose a username, TCP port (default **43137**) and **Max participants** (default 16). Select **Host Session**. IDEle creates a **new random session key** and displays it in a masked field. Use **Copy session key** to share that key privately with invited participants. Share the host's local IPv4 address (shown in the Session tab) or a DNS hostname that resolves to it. Approve or reject each incoming request; the host is counted toward the limit. Pending requests expire after roughly two minutes.

**Joiner:** Enter a unique username, host **IP/DNS name**, matching port, and the host's **43-character session key**. Select **Join Session** and wait for approval. Joining with a wrong key is rejected *before* the approval dialog. The key is held in memory, not written to disk by IDEle, and its input field is cleared when the session is left or closed. Previously copied clipboard entries may remain elsewhere.

**Firewall:** Keep the host reachable only through an appropriately restricted Windows **Private** firewall rule on your trusted LAN. Do not port-forward TCP 43137 to the public internet. Messages and games remain in memory; closing the host disconnects everyone. Client writes are buffered in a bounded queue, and a slow/non-reading host causes the client to disconnect rather than block the UI. No message history is persisted or restored.

## Chat and games

- **Chat:** Select **Everyone** for shared chat or another participant for direct chat. IDEle encrypts each client's connection with the host, but the **host decrypts and relays direct messages**. These are **not end-to-end encrypted between the two users**, nor are they anonymous from the host.
- **Tic-Tac-Toe / Connect Four:** Select an opponent, send a challenge, wait for acceptance and take turns. Rules and game state are authoritative on the host.
- **Pong:** Click the arena to focus, hold **W/S** or **Up/Down** to move, and play to seven points. The host runs a 25 Hz simulation. A slow/non-reading client is disconnected rather than blocking everyone else's connection.

## Security boundaries and DNS clarification

- The encryption protects traffic **in transit between each client and the host** from passive interception and rejects modified ciphertext. Only invited people with the randomly generated shared session key can authenticate to the service; the host must approve them separately.
- **The shared key is a group credential**, not an individual user's identity. Anyone with it can attempt to impersonate a member or a host. There is no key revocation, membership-specific certificate, verified user identity, 2FA, E2EE or persistent trust store. If a key is leaked, **end the session and create a new one**.
- **No verified IP hiding.** Every client connects to an IP after resolving the supplied hostname; the host and underlying network infrastructure can observe connection IPs. Changing a hostname or operating a DNS server does not conceal IP addresses. IP hiding requires a separate relay/VPN architecture, which this LAN-only plugin does not implement.
- The code uses standard JDK crypto primitives but a newly written application handshake and transport, not TLS 1.3 or a vetted Noise implementation. Tests are **not a substitute for independent security review**. Do not rely on IDEle for sensitive workplace communications until it has undergone that review.
- Clipboard copying may expose the session key to Windows clipboard history/sync; share keys through an appropriate trusted channel and clear clipboard history when necessary.

## Source and build

`src/main/java/dev/riderlan/core` contains the network/protocol/game engine, including `SecureTransport`. `src/main/java/dev/riderlan/ui` implements the native Swing tool window. `src/main/resources/META-INF/plugin.xml` identifies the plugin and makes it Rider-specific. The **package name** and **plugin ID** are intentionally unchanged from Rider LAN to allow upgrades.

### Official Gradle build (recommended for distribution)

Requires an installed **JDK 21+**, **Gradle 9+**, and network access to JetBrains/Gradle dependencies. The project is configured to compile against the **Rider 2024.2 SDK**, targeting Java 21 bytecode, and requests plugin verification against Rider 2024.2, 2025.3 and 2026.2.2:

```powershell
gradle integrationTest buildPlugin verifyPlugin
```

For a genuine release, use the **Gradle-built ZIP** in `build/distributions/` after passing verification and installation tests. The project does not bundle a Gradle Wrapper; install Gradle separately if needed.

### Offline preview build (cannot confirm Rider API compatibility)

With **JDK 21** installed (needs `java`, `javac`, `jar`):

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\build-offline.ps1
```

On Linux/macOS with JDK 21 and `zip`: `sh tools/build-offline.sh`.

The scripts compile the tiny JetBrains IDE integration layer against **compile-time signatures in `offline-stubs/`**, *never package the stubs*, run localhost tests and create `IDEle-1.1.1.zip`. The Linux script was executed in this environment; the Windows PowerShell script was reviewed but not run.

**Rider 2026.2.2 / RD-262.10315.191** is the intended main target. Minimum declared build remains `242` (2024.2), with no upper bound. **The actual IDE compatibility claim remains unverified** until an SDK-backed compilation, Plugin Verifier and real Rider installation succeed.

See [docs/verification.md](docs/verification.md) for tests, remaining risks and acceptance checklist, and [docs/review-1.1.1.md](docs/review-1.1.1.md) for the code-review findings.

## Troubleshooting

- **Incorrect key / cannot authenticate:** Ensure everyone uses the **IDEle v1.1.x with matching encrypted protocol**, host IP/hostname, port and 43-character key. Host approval is separate from key authentication.
- **Cannot connect:** Confirm LAN routing, hostname resolution (if using a name), private Windows firewall settings, and the host's listening port.
- **Approval missing:** The host sees join requests only **after** key authentication; bad keys will not create requests.
- **Room full:** Capacity is set by the host from 2 to 16, including themselves.
- **Rider reports incompatibility / tool window missing:** Rebuild with the official Rider SDK and check [verification notes](docs/verification.md).
- **Pong controls:** Click the arena or press its Focus button before using W/S or arrow keys.

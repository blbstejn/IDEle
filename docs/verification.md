# IDEle 1.1.1 — verification and release checklist

## Test results from offline environment

| Area | Result |
|---|---|
| Java 21 compilation (`--release 21`) against local Rider API stubs | PASS |
| Encrypted client/host loopback protocol, chat, private chat and 3 games | PASS |
| Capacity: 16 total members, 32-field rosters, overflow rejection | PASS |
| Configurable smaller capacity (2) | PASS |
| Incorrect shared key and v1 plaintext attempt blocked before join approval | PASS |
| Captured wire data excludes plaintext chat text | PASS |
| AES-GCM tampering rejected by decryption | PASS |
| Idle clients, pending join timeouts, expired challenges, stale Swing callbacks | PASS |
| Deliberately non-reading client doesn't block host | PASS |
| Non-reading host cannot freeze client chat/close | PASS |
| Abandoned/handshake-stalled join is cancelled by Leave | PASS |
| Keys cleared on disconnect/cancel/disposal; control characters escaped in logs | PASS |
| Static Java compiler warnings (`-Xlint:all -Werror`) | PASS |
| Swing tool-window headless smoke tests | PASS |
| Official Rider SDK compilation / Plugin Verifier | **NOT RUN** |
| Installation in Rider 2026.2.2 or other IDE builds | **NOT RUN** |
| Windows 11 multi-machine test | **NOT RUN** |
| Independent cryptographic audit / threat model review | **NOT DONE** |

Local test command: `sh tools/build-offline.sh` (Java 21, Linux). **94 assertions passed** across protocol/security, network, games, UI and regression tests. These tests are not the JetBrains Plugin Verifier.

## Known risks and limitations

1. **Unsigned preview ZIP compiled against stubs.** Compile with actual JetBrains Rider 2024.2 SDK and run `gradle verifyPlugin`, then install/test in Rider 2026.2.2 and supported older releases. Do not represent this preview as production compatible.
2. **New unaudited secure transport.** X25519 + authenticated HMAC transcript + AES-256-GCM were tested over real sockets and modified/captured frames; the security design is still custom and may contain unseen flaws. Require independent review before confidential use. Favor established TLS 1.3 and authenticated user identity for a production communications channel.
3. **Group-key identity, not individual identity.** Anyone with the key can act as a client or impersonate the host, subject to network position/permissions. Rotate by restarting the hosted session and distributing a new key.
4. **Host decrypts direct messages.** Users have encryption from client to host, *not user-to-user end-to-end encryption*. All connection IP addresses remain visible to peers and infrastructure as ordinary LAN traffic; no DNS server or privacy relay is provided.
5. **Volatile in-memory history.** No backups, permanent audit trail, message delivery receipts, key recovery, accounts or cross-session authentication. Hosts can end sessions at any time.
6. **DoS/abuse.** Handshake and outgoing buffers are bounded; flooding from legitimate key holders and hostile network attackers is not independently penetration-tested.
7. **Windows PowerShell exporter / Windows firewall.** The PS1 script was source-reviewed only; test it on Windows with JDK21. Configure Windows firewall Private profile rule and do not expose the port publicly.

## Acceptance checklist before using IDEle as a chat channel

1. Install JDK 21+ and Gradle 9+, extract the source and run `gradle integrationTest buildPlugin verifyPlugin` with internet access to JetBrains artifacts. Fix any SDK/API or compatibility errors first.
2. Install the official SDK-built `IDEle` ZIP on test Rider 2026.2.2 and, where feasible, Rider 2024.2 / 2025.3. Confirm the IDEle tool window opens and closes without leaking connections.
3. On two Windows 11 PCs, host and join using a random 43-character key, approve a join, exchange shared and private messages, challenge each other in all three games, and check firewall rules.
4. Test up to **16 total participants** (including host) and ensure the **17th is denied**. Repeat with a host-configured 2-person room. Check user rosters and game state with all clients connected.
5. Try wrong keys, malformed packets, a v1.0.x plaintext client, idle clients, failed DNS resolution, rejected/expired approvals, a slow-reading client and host shutdown.
6. Commission cryptographic/security review; only then consider IDEle for sensitive or regulated work communications.

## Packaging audit

Expected `IDEle-1.1.1.zip` layout:

```
IDEle/
    lib/
        idele.jar
            META-INF/plugin.xml
            dev/riderlan/... [compiled IDEle classes]
```

The bundled JAR must **not** include JetBrains signature stubs (`com/intellij/`), test code, developer secrets or external tooling. JetBrains plugin ID **remains `dev.riderlan`** to support upgrades from earlier previews despite the renamed display label and archive.

## Review-specific checks (v1.1.1)

- Client commands are never synchronously written to a remote socket on the Rider UI thread. A bounded (128-packet) outgoing queue disconnects a blocked client instead of allowing indefinite memory growth.
- TCP connection attempts expose a cancellable socket; leaving while waiting for `KEY_CHALLENGE` closes it. DNS lookups may still block their background thread briefly but cannot block the UI.
- The chat log escapes newlines, tabs, bidirectional format controls and other invisible control characters; usernames containing formatting controls are refused. This reduces confusing visual spoofing but is **not an identity verification system**.
- A stale plugin ZIP is deleted before regenerating the offline preview. The Windows script also removes the old ZIP before export.
- The session-key field is cleared at session end; copies in system clipboard history or external tools **cannot be cleared by the plugin**.

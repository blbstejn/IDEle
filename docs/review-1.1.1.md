# IDEle 1.1.1 — source and setup audit

Review date: 2026-10-13. Starting input: `IDEle-source-1.1.0.zip` and `IDEle-1.1.0.zip`.

## Corrected issues

1. **Potential Rider UI freeze on send or disconnect (high):** v1.1.0 called encrypted `Socket` writes from action handlers on Swing's event-dispatch thread. A non-reading host or congested TCP link could stall Rider indefinitely. v1.1.1 routes all post-handshake outgoing messages through a daemon writer and bounds the outgoing queue to 128 packets. Closing a client socket no longer synchronously writes a LEAVE frame. The host detects TCP closure automatically.
2. **Cancelled joins kept connecting in the background (medium):** an unanswered KEY_CHALLENGE could stall a previous connection for up to the socket timeout after Leave. The UI now creates and retains a cancellable socket, closes it when leaving/disposed, and rejects stale worker completions using session generations.
3. **Offline ZIP could contain removed files from previous exports (medium):** `zip -r` updates rather than always rebuilding an existing archive. Both offline exporters now remove the previous ZIP before repackaging; stale compiled output is also removed.
4. **Residual session-key UI state (medium):** the key input persisted after disconnect, after some client leaves, and after a cancelled connection. The field is now cleared in these cases and on disposal. Clipboard content can still persist and must be handled by the user.
5. **Chat/system notice visual spoofing (medium):** usernames could contain Unicode bidirectional formatting controls, and messages could inject newlines into the plaintext chat log to imitate separate system entries. Username validation now rejects control/format/separator characters and rendering visibly escapes such characters.
6. **Regression coverage (medium):** added tests for stalled outbound sockets, cancellation mid-handshake, UI secret clearing and logging safety. Compilation now treats Java warnings as errors (`-Xlint:all -Werror`) in offline export scripts.

## Reviewed setup

- Plugin display name: **IDEle**, artifact: `IDEle-1.1.1.zip`, internal plugin ID: `dev.riderlan` (unchanged for upgrades), Rider-only dependency: `com.intellij.modules.rider`.
- Platform compatibility declaration starts with `since-build=242`; the `until-build` upper limit is intentionally unset. Java bytecode is compiled for Java 21.
- The Java, Swing and crypto runtime dependencies come from the IDE/JDK. Offline API signature stubs are **compile-time only** and are verified absent from the plugin JAR; sources/tests are not bundled.
- The LAN protocol is still encrypted `2` and supports 2–16 participants including the host. Existing v1.1.0 clients should be wire-compatible with v1.1.1, although mixed-version behavior has not been comprehensively tested.

## Local verification performed

- `sh tools/build-offline.sh` — **94 assertions passed** (40 baseline, 17 game logic, 35 regressions, 2 encrypted transport).
- Java 21 compilation with `-Xlint:all -Werror` — PASS.
- Reviewed archive layout and plugin descriptor, and checked for bundled Rider stub/test code and Java class-file version 65 — PASS.
- Re-executed the exporter with a pre-injected stale ZIP entry; verified the old entry was removed — PASS.

## Not verified / release blockers

- **Actual JetBrains Rider SDK compilation, Gradle `buildPlugin`, `verifyPlugin` and real IDE startup were not possible** in the review container. Gradle is not installed and the container cannot resolve JetBrains/Gradle artifact servers. The ZIP is an **offline-built, unsigned preview**, not an SDK-verified release.
- Windows PowerShell exporter and Windows 11 multi-machine networking remain untested in this Linux container.
- **Security is not production-certified:** transport still uses an unaudited custom protocol; it has no individual user authentication, persistent messages, enterprise policies, or end-to-end private messaging. The host can decrypt chat and direct messages. DNS does not hide IP addresses. Do not use for confidential company chat without an independent security review and official Rider build verification.

## Steps before workplace use

1. In a network-enabled machine with JDK 21 and Gradle 9+, run `gradle integrationTest buildPlugin verifyPlugin` with the **real Rider SDK** downloaded by the Gradle plugin.
2. Install the **Gradle-built** ZIP under `build/distributions/` in Rider 2026.2.2, then test older Rider 2024.2/2025.3 if support is claimed.
3. Test with at least two Windows 11 PCs, a restricted private-LAN firewall rule, approval, 16 members, chat/Pong and failed-key/broken-network cases.
4. Commission a cryptographic/security assessment before using IDEle for sensitive communications.

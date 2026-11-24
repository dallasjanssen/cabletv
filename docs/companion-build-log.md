# Phone companion: build record

How the phone companion was built, who built which part, what each part decided, and what was
checked. The design and protocol are in [companion-protocol.md](companion-protocol.md).

## How the work was split

The integrator wrote the shared core and the protocol spec first, so every part had one contract to
build against. Three sub-agents then worked at the same time, each in its own git worktree and its
own set of files, so none could break another's work. The integrator reviewed each part, applied it
to the branch, ran the Android build in CI, fixed what failed and wrote this record.

| Part | Built by | Commit on the branch | Size | Unit tests |
| --- | --- | --- | --- | --- |
| Shared core and spec | Integrator | `6706857`, `a836776` | 6 files, about 780 lines | 9 |
| Android TV remote library | Agent C | `6ee0573` | 12 files, 1401 lines | 33 |
| TV server | Agent A | `e23031c` | 27 files, 3002 lines added, 44 removed | 92 |
| Build fix | Integrator | `d8fa93d` | 1 line | |
| Phone app | Agent B | `75ec8f5` | 37 files, 4883 lines added, 5 removed | 81 |

Every agent got the same ground rules: follow the spec exactly and report any deviation, add no
libraries, keep logic worth testing in plain Kotlin so it runs on the JVM, keep Android calls in thin
wrappers, never block the main thread, and write text people see in short plain sentences. None
could compile Android code, because the Android SDK download host is blocked in the build
environment, so each ran its plain-Kotlin tests in a JVM copy of the project and left the Android
compile to CI.

## Shared core (integrator)

- `docs/companion-protocol.md`: discovery, TLS with certificate pinning, pairing, every message,
  the settings and sync formats, the PIN rules, errors, the remote library's public API and the
  phone app's behaviour.
- `CompanionProtocol.kt`: constants, the remote key list (Android key codes written out, so it
  needs no Android classes), pairing codes, the pairing proof (HMAC-SHA256 keyed by the code over
  both certificate fingerprints), and one-JSON-object-per-line framing with a 2 MiB limit.
- `CompanionIdentity.kt`: one key per role in the Android keystore with a self-signed certificate
  (P-256 for the TV and the phone, RSA 2048 for the Android TV remote), a key manager that always
  presents that one key, and a trust manager that accepts any certificate so callers can pin the
  fingerprint from pairing instead.
- `CompanionDevice.kt`: `isTelevision`, so the TV and the phone decide the same way.
- CI run 77 compiled this core on Android and passed its tests before any agent started.

## Agent C: Android TV remote library (`data/atvremote/`)

Talks to the Android TV Remote Service (protocol version 2) that Google TV runs, the same service
Home Assistant's Android TV Remote integration uses. It gives the phone power, volume, Home and
opening Cable TV while it is closed.

- Files: `AtvApi.kt` (the spec's public types), `ProtoWire.kt` (a small protobuf reader and
  writer), `Framing.kt` (varint length prefixes, partial reads, 256 KiB limit), `PoloMessages.kt`
  (pairing messages), `RemoteMessages.kt` (remote messages and state updates), `PairingSecret.kt`
  (code check and secret), `AtvPairing.kt` (port 6467), `AtvRemote.kt` (port 6466).
- Protocol details taken from the reference implementation, androidtvremote2 0.3.3: pairing
  messages carry protocol version 2 and status 200, the service name is `atvremote`, the phone asks
  for a 6-character hexadecimal code as the input role, and the secret is SHA-256 over both RSA
  moduli and exponents (unsigned, without a sign byte) and the code after its first two characters,
  whose first byte must match those two characters. On the remote channel it answers the TV's
  configure message with the features it wants (ping, keys, IME, power, volume, app links), echoes
  set active and pings, sends keys as short presses and opens app links.
- Reference files were read as data, never run. The message definitions were recovered from the
  reference's generated files by reading their embedded descriptors, and every expected byte
  string in the tests was produced independently with the official protobuf library.
- Decisions: a mistyped code fails on the phone before anything is sent. A refused handshake, or
  the TV closing the connection before it says it is ready, means the phone is not paired. Key
  presses and app links go through a bounded queue with one writer, so they never block.
- Tests: 33, covering varints, unknown fields of every wire type, framing with partial input, exact
  bytes of every message sent, parsing of the TV's messages, and the secret.
- Its worktree started from `main` instead of the branch, so it lacked the core and the spec. The
  integrator told it to fast-forward, and it did before writing code.
- Review: the socket and threading code was read in full. No changes were needed.

## Agent A: TV server (`data/companion/server/` and hooks in the TV app)

Runs inside the TV app while it is on screen and answers paired phones.

- Files: `CompanionServer.kt` (TLS server, threads, NSD, pushes), `MessageRouter.kt` (who may send
  what, PIN guard, error codes), `PairingManager.kt`, `PinGate.kt`, `PairedPhones.kt`,
  `CompanionPrefs.kt` (install id and paired phones, kept out of backups), `CompanionJson.kt`,
  `SettingsUpdate.kt`, `SyncData.kt`, `AppCompanionBackend.kt`, `CompanionBackend.kt`,
  `CompanionHooks.kt`, `MainThread.kt`, `PushThrottle.kt`, and `ui/CompanionPairing.kt` for the
  "Pair a phone" popup.
- Changes to existing files: `App.kt` (server lifecycle, links from phones through the existing link
  handling, the pairing popup, and the Jellyfin save pulled into one function), `Settings.kt`
  (paired phones with Remove in Set up with your phone), `HomeAssistant.kt` (exposes the now playing
  state it already tracked, sending unchanged), `SettingsStore.kt` and `UserStateStore.kt` (small
  additions for sync).
- Decisions: whether a phone is paired is checked on every message, so removing one takes effect at
  once. A pairing code belongs to the phone that asked for it. Settings changes run every check
  first and apply only if all pass. Back from a phone falls through to the activity's back handling
  when the screen does not use it. Sync needs a known format and known section names.
- Tests: 92, covering pairing sessions, the PIN lockout, every message rule, the settings and state
  JSON, settings updates and sync merges.
- It stopped once on the account's usage limit and was resumed after the reset with its work intact.
- Review: the server, router, backend, pairing and every App.kt change were read in full. CI run 78
  compiled this part early and found one missing import in the popup (`getValue` for
  `by rememberUpdatedState`). The integrator fixed it in `d8fa93d`.

## Agent B: phone app (`data/companion/client/`, `ui/companion/`, `MainActivity.kt`)

What a phone shows: My TVs, pairing, the remote, now playing, settings, sync and Watch on this phone.

- Plain Kotlin: `PairedTv.kt`, `DiscoveredTv.kt`, `CompanionMessages.kt`, `ReplyMatcher.kt`,
  `TvConnection.kt`, `TvSession.kt`, `TvPairing.kt`, `PinGate.kt`, `SyncRunner.kt`, `RemoteKeys.kt`,
  `TvSettings.kt`, `TvAddress.kt`, `NowPlaying.kt`.
- Android wrappers: `PairedTvStore.kt`, `TvDiscovery.kt` (resolves one service at a time),
  `TlsLinkConnector.kt`, `AtvController.kt`.
- Screens: `CompanionApp.kt`, `MyTvsScreen.kt`, `TvScreen.kt`, `RemoteTab.kt`, `SettingsTab.kt`,
  `SyncScreen.kt`, with `CompanionModel.kt`, `TvScreenModel.kt` and `CompanionComponents.kt`.
- Decisions: the connection takes a pluggable connector, so tests run the real connection logic
  against a fake TV over plain sockets. The TV's fingerprint is checked before anything is sent, and
  a change shows "This TV's identity changed. Pair it again." Keys go through the Android TV remote
  when it is connected, because that works in every app, and through the companion otherwise. The
  PIN is asked for only when the TV wants it and is kept in memory per TV. The phone keeps the screen
  on only while it shows the TV app.
- Tests: 81, covering pairing with the real proof, request and reply matching, timeouts,
  reconnects, a changed certificate, PIN retries, sync, settings payloads, key routing, the stored
  list, and a check that no message text uses a dash.
- It also stopped once on the usage limit and was resumed after the reset.
- Review: the routing in MainActivity and the TLS connector and fingerprint check were read in
  full. Everything compiled on the first combined run.

## Builds

| CI run | What it built | Result |
| --- | --- | --- |
| 77 | Shared core | Passed |
| 78 | Core, remote library and TV server | Failed: one missing import in `CompanionPairing.kt`, fixed in `d8fa93d` |
| 79 | Everything | Passed: unit tests and the debug APK, no new compiler warnings |

## Not yet checked on real devices

Unit tests cover the logic, but these need a phone and the TVs:

- Pairing the phone with a TV over TLS with the keystore keys, and NSD discovery on the home network.
- Remote keys pressed in the TV app, including Back, and focus coming back after the pairing popup.
- Pairing with the Android TV Remote Service, and power, volume, Home and opening Cable TV.
- Settings changes and sync between the Living Room and Bedroom TVs, with and without a PIN.
- How the phone screens look and feel on a real phone.

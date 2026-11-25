# Phone companion: design and protocol

This is the contract between the parts of the phone companion. The TV side, the phone side and
the Android TV remote library are built separately against it, so anything another part relies on
is written down here. When the code and this document disagree, fix one of them.

## Parts

| Part | Where | What it does |
| --- | --- | --- |
| Shared core | `data/companion/CompanionProtocol.kt`, `data/companion/CompanionIdentity.kt`, `data/companion/CompanionDevice.kt` | Constants, message framing, the pairing proof, keystore identities and TLS contexts. Used by both sides. |
| TV server | `data/companion/server/` plus hooks in `ui/App.kt` and `ui/Settings.kt` | Runs inside the TV app while it is on screen. Answers paired phones: remote keys, channel links, what is playing, settings and sync. |
| Phone app | `data/companion/client/`, `ui/companion/`, routing in `MainActivity.kt` | What a phone shows: My TVs, pairing, remote, now playing, settings, sync, and Watch on this phone. |
| Android TV remote | `data/atvremote/` | Talks to the TV's own Android TV Remote Service, the same one Home Assistant uses, for power, volume, Home and opening the app while it is closed. Used by the phone app. |

Phone or TV: `isTelevision(context)` in `data/companion/CompanionDevice.kt` decides. It is true when
`packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)` is true or
`UiModeManager.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION`. Everything else is a phone.

## Discovery

The TV registers a network service with `NsdManager` while the app is on screen, and removes it
when the app leaves the screen.

- Service type: `_cabletv._tcp` (`CompanionProtocol.SERVICE_TYPE`)
- Service name: the TV's name from `tvDeviceName(context)`. NSD may add a suffix when two TVs share
  a name, so the phone never relies on the name to identify a TV.
- Port: `CompanionProtocol.DEFAULT_PORT` (8766), or any free port when that one is taken.
- TXT attributes: `id` = the TV's install id, a random UUID created once and kept in the TV's
  companion preferences. `v` = `CompanionProtocol.PROTOCOL_VERSION` as a string.

The phone browses for the same service type, resolves each one for host and port, and matches it
to a paired TV by `id`. A paired TV that is not found shows as "Cable TV is closed or the TV is off".
The phone also offers adding a TV by IP address and port, for networks where discovery is blocked.

## Transport

- TLS 1.2 or newer over TCP. Both sides use `CompanionIdentity.sslContext(role)`: the TV uses
  `IdentityRole.TV`, the phone uses `IdentityRole.PHONE`.
- The TV's server socket sets `needClientAuth = true`, so every connection presents the phone's
  certificate.
- Neither side trusts a certificate authority. Both accept any certificate during the handshake,
  then check the other side's certificate fingerprint (`CompanionIdentity.fingerprint`) against
  what pairing stored. This is what makes the connection private: a TV only acts on phones it
  paired with, and a phone only talks to the TV it paired with.
- Messages are UTF-8 JSON objects, one per line, ending in `\n`. `CompanionLineChannel` reads and
  writes them and refuses a line longer than `CompanionProtocol.MAX_LINE_BYTES` (2 MiB).
- A request carries an integer `id`. Its reply carries the same number in `re`. Messages the TV
  sends on its own, such as `state`, carry no `re`.
- The phone sends `ping` every 20 seconds. The TV closes a connection that has sent nothing for
  60 seconds, and keeps at most 4 connections at once.

## Pairing

A phone pairs once per TV. Pairing proves the person can see the TV screen, and binds the two
certificates together so nobody in the middle can stand in for either side.

1. The phone connects and sends `hello`. The TV answers `welcome` with `"paired": false` when it
   does not know the phone's certificate.
2. The phone sends `pair_start` with its name. The TV makes a 6-digit code
   (`CompanionProtocol.newPairCode()`) and shows it over whatever is on screen:
   "Pair Pixel 10? Enter this code on the phone: 482 913", with a Cancel button. The code lasts
   2 minutes (`PAIR_CODE_LIFETIME_MS`). A new `pair_start` replaces the old code.
3. The person types the code on the phone. The phone sends `pair_finish` with
   `proof = CompanionProtocol.pairingProof(code, tvFingerprint, phoneFingerprint)`, where
   `tvFingerprint` is the fingerprint of the certificate the TV presented in this TLS session, and
   `phoneFingerprint` is the phone's own.
4. The TV computes the same proof from its own certificate and the certificate the phone
   presented, and compares with `CompanionProtocol.proofMatches`. On a match it stores the phone's
   fingerprint (hex), name and the time, closes the code, and answers `paired`. The phone stores
   the TV's id, name, fingerprint, host and port.
5. Five wrong proofs (`PAIR_MAX_WRONG`) close the code with `locked`. An expired code answers
   `code_expired`. Cancel on the TV closes the code, and a later `pair_finish` answers
   `code_expired`.

Someone who records the exchange could try all million codes offline against the proof. That is the
same trade-off as Android TV's own remote pairing, and acceptable on a home network.

The TV lists paired phones in Settings, Set up with your phone, with a Remove button for each.
Removing a phone closes its open connections. Paired phones are kept in the TV's own companion
preferences, which backups and sync leave out on purpose: a pairing belongs to one TV.

## Messages

Every message has a `type`. Requests from the phone also have `id`.

| Phone sends | TV answers | Notes |
| --- | --- | --- |
| `hello` `{phone_name, app_build}` | `welcome` `{paired, tv_name, tv_id, app_build, pin_set, protocol}` | Allowed before pairing. `pin_set` only when paired. |
| `pair_start` `{phone_name}` | `ok` | Allowed before pairing. Shows the code on the TV. |
| `pair_finish` `{proof}` | `paired` `{tv_name, tv_id}` or `error` | Allowed before pairing. |
| `ping` | `pong` | Allowed before pairing. |
| `get_state` | `state` | See below. |
| `key` `{key}` | `ok` | `key` is a name from `CompanionProtocol.REMOTE_KEYS`. The TV presses it in the TV app (key down, then key up). |
| `open` `{link}` | `ok` | A `cabletv:` link, handled exactly like one from Home Assistant. Anything else is `bad_request`. |
| `get_favorites` | `favorites` `{favorites: [{id, name, number, logo}]}` | The favorites of every profile on the TV that are in the channel list, sorted by number. `number` is 0 and `logo` is left out when unknown. |
| `get_settings` `{pin?}` | `settings` `{settings}` | PIN rules apply. |
| `put_settings` `{pin?, settings}` | `saved` `{messages, settings}` or `error` | PIN rules apply. |
| `export_sync` `{pin?, sections}` | `sync_data` `{data}` | PIN rules apply. |
| `import_sync` `{pin?, data}` | `saved` `{messages}` or `error` | PIN rules apply. |
| `get_debug_log` `{pin?}` | `debug_log` `{enabled, log}` | PIN rules apply. See Debug log. |
| `set_debug_log` `{pin?, enabled?, clear?}` | `debug_log` `{enabled, log}` | PIN rules apply. `enabled` turns logging on or off. `clear` empties the log first. |

Every message except `hello`, `pair_start`, `pair_finish` and `ping` answers `not_paired` until the
phone has paired.

### What is playing

The TV sends `state` in answer to `get_state`, and on its own to every paired connection whenever
the screen or what is playing changes, at most once a second.

```json
{"type": "state", "screen": "live", "state": "playing", "kind": "live",
 "title": "SportsCenter", "channel": "ESPN", "channel_number": 206, "channel_id": "1234",
 "start": "2026-11-24T18:00:00Z", "end": "2026-11-24T19:00:00Z", "image": "http://..."}
```

`screen` is one of `live`, `home`, `guide`, `on_demand`, `search`, `settings`, `welcome`, `multi`.
The other fields are the same as the Home Assistant now playing message (`nowPlayingJson`), without
`event` and `device`, so the two can never drift apart.

### Settings

`get_settings` answers:

```json
{"type": "settings", "re": 5, "settings": {
  "live": {"server": "", "username": "", "password": "", "playlist": "", "guide": "",
           "stalker_portal": "", "stalker_mac": "", "connected": true, "channel_count": 812,
           "subscription_expiry": 0},
  "library": {"server": "", "signed_in": false},
  "channels": {"hidden": "", "locked": "", "strip_prefixes": false},
  "home_assistant": {"webhook": ""},
  "profiles": [{"id": "main", "name": "Main", "active": true}],
  "pin_set": false}}
```

- `live` holds the same fields as Settings, Live TV, including the password, because the phone
  edits them. They only travel over the paired TLS connection.
- `channels.hidden` is the active profile's hidden filters, the same as Settings, Channel list.

`put_settings` takes any of these sections, each optional:

- `live`: any of the live fields. The TV fills the missing ones from what is saved, then runs the
  same check as Check and save (`checkLiveLogin`). A failed check answers `error` `invalid` with
  the same message the TV would show, and nothing is saved.
- `library`: `{server, username, password}` signs in to Jellyfin like Settings, Movies & Shows
  (`signInJellyfin`). `{sign_out: true}` signs out the active profile.
- `channels`: any of `hidden` (active profile), `locked`, `strip_prefixes`.
- `home_assistant`: `{webhook}`, normalized with `normalizeWebhookUrl` and checked with
  `isWebhookUrl`.

A change takes effect on the TV straight away, as if it was made on the TV, including reloading the
channels after a login change. `saved` carries the messages the TV would show, such as
"Live TV is connected.", and the settings as they are now.

### Sync

Sync copies chosen parts from one TV to another. The phone exports from one and imports into the
other. Each part is a key under `sections`:

```json
{"format": 1, "source_tv": "Living Room TV", "sections": {
  "logins": {"xtream_server": "", "xtream_user": "", "xtream_pass": "", "m3u_url": "",
             "epg_url": "", "stalker_url": "", "stalker_mac": "", "jellyfin_server": "",
             "jellyfin_token": "", "jellyfin_user_id": "", "subscription_expiry": 0},
  "channels": {"hidden_filters": "", "strip_prefixes": false,
               "profiles": [{"name": "Main", "hidden_filters": null}]},
  "favorites": {"profiles": [{"name": "Main", "favorites": ["1234", "5678"]}]},
  "lock": {"pin_record": "", "locked_filters": ""}}}
```

`export_sync` takes `sections`, a list of the keys wanted, and answers with only those.
`import_sync` applies each section present:

- `logins` replaces those settings on the target. There is no login check, because the source TV
  already uses them. The target reloads its channels.
- `channels` sets the household hidden filters and the prefix setting, and each profile's own
  hidden filters (`null` clears a profile's own filters, so it uses the household ones).
- `favorites` replaces each profile's favorites.
- `lock` sets the PIN (`pin_record` is the stored salt and hash, so the PIN never travels as typed;
  empty removes the PIN) and the locked groups.
- Profiles are matched by name, ignoring case. A profile missing on the target is created.

Never part of sync: the Home Assistant webhook, the last channel, recents, resume points, track
choices, reminders, recordings and paired phones. They belong to one TV.

### Debug log

The TV keeps a debug log while debug logging is on. The phone turns it on and off from the Debug
tab of a TV's screen. The log records playback and phone connection events, such as a channel
loading, a player error or a phone pairing. Each line goes through the same redaction as crash
reports, so stream addresses, logins and Authorization headers are hidden before a line is kept.
Nothing is written while logging is off. The log is kept in the TV's app storage and is cut back
to its newest part once it passes about 256 KB. It is never part of sync.

`debug_log` answers with `enabled` (whether logging is on) and `log` (the text, oldest line first).
Both requests need the PIN when the TV has one, because the log shows what was watched.

### PIN rules

When the TV has a parental PIN, `get_settings`, `put_settings`, `export_sync` and `import_sync`
need `pin`. Without it the TV answers `pin_required`; a wrong one answers `wrong_pin`. Five wrong
PINs in a row answer `pin_wait` for 30 seconds. The remote, channel links, `state` and favorites
never need the PIN: a locked channel still asks for it on the TV screen, as it always does.

### Errors

```json
{"type": "error", "re": 7, "code": "wrong_pin", "message": "That PIN did not match."}
```

`code` is one of `CompanionProtocol.Errors`: `bad_request`, `not_paired`, `wrong_code`,
`code_expired`, `locked`, `pin_required`, `wrong_pin`, `pin_wait`, `invalid`, `busy`,
`unsupported`. `message` is a plain sentence the phone can show as it is. `wrong_code` also carries
`attempts_left`.

## Android TV remote library

The phone uses this to reach the TV's own remote service. It works whether Cable TV is open or not,
and is the only way to send power, volume and Home.

```kotlin
package com.dallas.cabletv.data.atvremote

/** Pairs with the TV's Android TV Remote Service on port 6467. One use per instance. */
class AtvPairing(host: String) : java.io.Closeable {
    /** Connects and asks the TV to show a 6-character code. Returns once the TV shows it. */
    suspend fun start(clientName: String)
    /** Sends the code the person typed. Returns once the TV accepts it. */
    suspend fun finish(code: String)
    override fun close()
}

/** A connection to the TV's Android TV Remote Service on port 6466, after pairing. */
class AtvRemote(host: String) : java.io.Closeable {
    val state: kotlinx.coroutines.flow.StateFlow<AtvState>
    /** Connects. Throws AtvRemoteException with NOT_PAIRED when the TV does not know this phone. */
    suspend fun connect()
    /** A short press of an Android key code, such as AtvKeys.POWER. Ignored while not connected. */
    fun sendKey(keyCode: Int)
    /** Opens an app link on the TV, such as "cabletv://live". Ignored while not connected. */
    fun launch(link: String)
    override fun close()
}

data class AtvState(
    val connected: Boolean = false,
    val poweredOn: Boolean? = null,
    val currentApp: String? = null,
    val volumeLevel: Int? = null,
    val volumeMax: Int? = null,
    val muted: Boolean? = null
)

class AtvRemoteException(val reason: Reason, message: String) : Exception(message) {
    enum class Reason { UNREACHABLE, NOT_PAIRED, BAD_CODE, PROTOCOL }
}

/** Android key codes the phone uses. The remote service takes the same numbers. */
object AtvKeys {
    const val HOME = 3; const val BACK = 4
    const val DPAD_UP = 19; const val DPAD_DOWN = 20; const val DPAD_LEFT = 21
    const val DPAD_RIGHT = 22; const val DPAD_CENTER = 23
    const val VOLUME_UP = 24; const val VOLUME_DOWN = 25; const val POWER = 26
    const val MEDIA_PLAY_PAUSE = 85; const val VOLUME_MUTE = 164
    const val INFO = 165; const val CHANNEL_UP = 166; const val CHANNEL_DOWN = 167
    const val GUIDE = 172; const val LAST_CHANNEL = 229
    /** KEYCODE_0 to KEYCODE_9 are 7 to 16. */
    fun digit(d: Int): Int = 7 + d
}
```

The library uses `CompanionIdentity.sslContext(IdentityRole.ATV_CLIENT)`, an RSA identity, because
the pairing secret is computed from both RSA public keys. The pairing code is 6 hexadecimal
characters. The first two are a check on the other four, so a mistyped code fails on the phone
before anything is sent (`BAD_CODE`).

## Phone app behaviour

- On a phone, the app opens on **My TVs**, unless it was opened by a `cabletv:` link, which still
  goes straight to the TV app as before. **Watch on this phone** opens the existing app.
- **My TVs** lists paired TVs, marking which are on the network now, and TVs found on the network
  that are not paired yet. Tapping an unpaired one pairs it. A paired one opens its screen.
- A TV's screen has **Remote** and **Settings**. A **Sync** screen sits beside My TVs, because it
  involves two TVs.
- **Remote**: what is playing at the top. Power, volume and Home need the Android TV remote, offered
  as "Set up power and volume" until it is paired. The D-pad, OK, Back, Guide, Info, Last, channel
  up and down, play/pause and the number pad go through the Android TV remote when it is
  connected, because that works in every app, and through the companion connection otherwise.
  Favorites are a list, and tapping one opens `cabletv://channel?id=<id>`. When Cable TV is closed
  but the Android TV remote is paired, an **Open Cable TV** button launches `cabletv://live`.
- **Settings**: the sections from `get_settings`, with save buttons per section and the TV's
  messages shown after saving. Ask for the PIN when `pin_set`, and keep it in memory for that TV
  until the app closes.
- **Sync**: pick the TV to copy from and the TV to copy to, tick Logins, Channel customizations,
  Favorites and Parental lock, then Copy. Show what the target TV answered.

## Rules for all parts

- No new libraries. Kotlin 2.0, Compose BOM 2024.09.02, minSdk 26, compileSdk 34, OkHttp 4.12,
  coroutines 1.8, org.json.
- Network and disk work never runs on the main thread. Anything that touches Compose state or the
  activity runs on the main thread.
- Text people see is short, plain and calm, like the rest of the app. No em dashes.
- Comments explain behaviour in plain words, the way the existing code does.
- Pure logic goes in plain Kotlin that unit tests can run on the JVM, separate from Android calls.

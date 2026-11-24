# Cable TV

A personal, sideloaded Android and Android TV app that pulls together:

- **Live TV** from an Xtream Codes login, an M3U playlist, a Stalker / Ministra portal, or all of them together
- **Program guide** from the login, from a playlist, or from a guide link you paste in Settings, about two days ahead and two days behind
- **On Demand** from your Jellyfin server and from the provider's movie and show categories

It opens straight into live TV on the last channel you watched. Menus sit on top of the picture, so the channel keeps playing behind Home and in a corner of the guide. Back from live TV opens Home, and Back again leaves the app. Keep watching on Home returns to the picture. Pressing the remote's Home button stops the picture and lets go of the provider connection; it starts again when you come back.

The screens are laid out for Android TV's 960 by 540 grid, with one typeface (Figtree), a dark low-glare background, and one focus style: whatever is selected turns white. Everything works with only the arrows, OK and Back. Channel and number keys are shortcuts for remotes that have them.

## Screens

- **Live TV**: up and down change channel. OK opens a bar with Guide, Home, Search, Channel list, Favorite, Pause, Watch from the start, Record, Two channels, and Audio and captions. Choosing a caption track turns captions on, including a track that has no language code. Off turns them off. Left opens the channel list while you are at the live edge. After a pause, Left and Right move inside the few minutes already buffered, and a badge shows while you are paused or behind live. A replay can be paused and skipped; Back returns from the replay to the live channel, and a replay that reaches its end returns there by itself. Playlist catch-up uses the tags in the M3U. Up and down skip locked channels. A locked channel says so on screen, and OK asks for the PIN. When the channels do not load, Try again and Settings are on screen.
- **Home**: what is on now, with Keep watching and Guide buttons, then rows for favorite and recent channels, half-watched titles, and new movies and shows.
- **Guide**: a time grid covering tomorrow and the recent past, with a line for now, details of the highlighted show, and a preview of the channel playing. Refresh guide downloads the listings again. Left from a channel name picks a category. Up from the category reaches Refresh guide. OK on a channel name offers Watch now and Favorite. OK on a show that has not started yet offers a reminder or a recording. OK on the show on now offers Watch now and, when the channel keeps an archive, Watch from the start. OK on a show that has ended plays it from the start. A channel with no listings goes straight back to its name with Left.
- **Movies & Shows**: provider categories and Jellyfin genres, plus continue and recently added. A title opens a details page with the plot and year when the provider sends them. The next episode waits ten seconds and can be cancelled. Each profile can have its own Jellyfin login, so continue watching is not shared.
- **Search**: an on-screen keyboard with results beside it, for channels, shows on now or later, movies and series. Accents and punctuation are ignored, so "tele" finds "Télé". A row of symbol keys sits under the letters.
- **Two channels**: two pictures side by side. Left and right pick a picture, and only that one plays sound. Up and down change its channel. Locked channels are skipped.
- **Settings**: phone setup, live TV (including a Guide URL and subscription expiry), Jellyfin, channel list, profiles, parental lock, recordings, Home Assistant, backup and restore, and updates. The phone setup page can edit live/library logins, channel customizations, the Home Assistant webhook, and download or upload a backup file. Hidden channels belong to the profile. Locked groups need the PIN before they play; movie and show categories can be locked too. After five wrong PINs, the PIN pad waits 30 seconds. Each section saves by itself, and leaving with unsaved typing says so. A running recording can be stopped in Recordings.
- **First launch**: with nothing set up, the app opens on a QR code. Focus starts on New code.

## Remote and keyboard controls

| Action | Remote |
| --- | --- |
| Change channel | Up / down, or CH+ / CH- |
| Previous channel | Last |
| Everything you can do while watching | OK |
| Channel list | Left, while the channel is live |
| Rewind a few minutes | Pause, then Left. Right moves forward. Back to live is in the OK bar |
| Jump to a channel | Type the number (up to 4 digits), in live TV or anywhere on the guide. OK tunes straight away |
| Home, while watching | Back |
| Leave the app | Back again, from Home |
| Skip in a replay | Left 10 seconds, right 30 seconds |
| Pause a replay | Play / pause. OK opens the options, which include Pause |
| Skip 10 seconds in a movie | Left / right |
| Pause a movie | OK, or play / pause |

## Build and install (Android Studio)

1. Install Android Studio (Koala or newer).
2. Open the `CableTV` folder. Let Gradle sync finish. Accept any prompt to create the Gradle wrapper.
3. Phone: pick your phone at the top and press Run.
4. Android TV: build the APK with **Build > Build Bundle(s) / APK(s) > Build APK(s)**. The file is at `app/build/outputs/apk/debug/app-debug.apk`.

When the app opens and a newer build is out, it offers it on screen with Install now and Later. Later puts it away until the app is opened again an hour or more later. The app looks at most once an hour, and Settings, Updates can always check by hand.

In-app updates only work with the release build: the `cabletv-release-apk` artifact from the CI run, or the APK attached to a GitHub release. A debug build is signed with a different key, so Android will not install a release update over it. Use the debug build for development.

### Installing on the TV

1. On the TV, open **Settings > Device Preferences > About** (or **System > About** on some models) and select **Build** seven times to unlock Developer options.
2. Open **Developer options** and turn on **USB debugging** or **Network debugging** (ADB).
3. Note the TV's IP address under **Settings > Network**.
4. On your computer, with Android platform tools installed:

```
adb connect 192.168.1.50:5555
adb install -r app-debug.apk
```

Replace the IP with your TV's address. Accept the debugging prompt on the TV the first time.

## Google Home and Home Assistant

### What is playing

The app tells Android what is on screen: the channel, the show from the guide, and the channel logo, or the movie or episode with its poster. Google Home, the Google TV Now Playing panel and "Hey Google, pause" can see it and pause it. Next and previous change channel. It stops as soon as the app leaves the screen, so nothing can start playing in the background.

### Turning on a channel

The app opens these links, and starts if it is closed:

| Link | Opens |
| --- | --- |
| `cabletv://channel/12` | Channel number 12 |
| `cabletv://channel/ESPN` | The channel named ESPN. Case, accents and punctuation are ignored, and "ESPN" finds "ESPN HD" before "ESPN2" |
| `cabletv://channel?name=ESPN`, `?number=12`, `?id=1234` | The same, written as a query. In a query, write a plus sign as `%2B` |
| `cabletv://live` | Live TV on the last channel |

A locked channel still asks for the PIN. A channel that is hidden or not in the list shows a message instead.

From Home Assistant, use the Android TV Remote integration for the Google TV box. This script turns the TV on if needed and changes the channel:

```yaml
alias: Watch channel
mode: restart
fields:
  tv:
    name: TV
    required: true
    selector:
      entity:
        filter:
          - domain: remote
            integration: androidtv_remote
  channel:
    name: Channel number or name
    required: true
    selector:
      text:
sequence:
  - action: remote.turn_on
    target:
      entity_id: "{{ tv }}"
    data:
      activity: "cabletv://channel/{{ channel | string | trim | urlencode }}"
```

From a computer: `adb shell am start -a android.intent.action.VIEW -d "cabletv://channel/12"`

### Telling Home Assistant what is playing

Make a webhook in Home Assistant, then paste its URL in Settings, Home Assistant on the TV, or in the phone setup page. Send test checks that Home Assistant answers. Give each TV its own webhook ID.

The TV sends a JSON message when the channel, show or pause state changes, and when it leaves the screen:

```json
{"device": "Living Room TV", "state": "playing", "kind": "live", "title": "SportsCenter",
 "channel": "ESPN", "channel_number": 206, "channel_id": "1234",
 "start": "2026-11-24T18:00:00Z", "end": "2026-11-24T19:00:00Z", "image": "http://..."}
```

`state` is `playing`, `paused` or `idle`. `kind` is `live`, `replay`, `movie` or `episode`. Movies and episodes send `series`, `season`, `episode` and `year` instead of the channel fields. `device` is the TV's name from its own settings, under About, Device name. Fields with nothing to say are left out, and `idle` sends only `device` and `state`.

This sensor, in `configuration.yaml`, keeps the latest message for one TV:

```yaml
template:
  - triggers:
      - trigger: webhook
        webhook_id: cable-tv-living-room
        allowed_methods:
          - POST
        local_only: true
    sensor:
      - name: Living Room Cable TV
        unique_id: cable_tv_living_room
        icon: mdi:television-classic
        state: "{{ trigger.json.state }}"
        attributes:
          kind: "{{ trigger.json.kind | default(none) }}"
          title: "{{ trigger.json.title | default(none) }}"
          channel: "{{ trigger.json.channel | default(none) }}"
          channel_number: "{{ trigger.json.channel_number | default(none) }}"
          series: "{{ trigger.json.series | default(none) }}"
          start: "{{ trigger.json.start | default(none) }}"
          end: "{{ trigger.json.end | default(none) }}"
          image: "{{ trigger.json.image | default(none) }}"
```

The TV's webhook URL is then `http://<home assistant address>:8123/api/webhook/cable-tv-living-room`.

## Security notes

- Credentials are stored with AES-256 encryption through Android's EncryptedSharedPreferences.
- The Jellyfin password is used once to sign in and is not stored. The app keeps the access token only.
- Nothing secret belongs in source files. Do not commit real credentials to a repository.
- The Home Assistant webhook URL is stored with the other settings. Anyone with it can write to that webhook, so treat it like a password. A webhook with `local_only: true` only accepts messages from your home network.

## Known limits (v1)

- Jellyfin playback uses direct streams. If the TV cannot decode a file's codec, that title will fail. Transcoding can be added later.
- Channels with no listings in the XMLTV feed fall back to the provider's now and next listing.
- A playlist can name its guide with `url-tvg`. A provider that gives you a separate EPG address goes in Settings, Live TV, Guide URL. Refresh guide, on the Guide screen and in Settings, Live TV, downloads those listings again. The app also downloads them about once an hour. The listings are kept on the device, so after the app has been closed the guide shows them straight away; when they are less than an hour old the app waits before downloading again. A gzip file is read as well as plain XML. Xtream, a playlist and a Stalker portal can all be saved at once. The guide matches a channel by its id, the same id the playlist calls `tvg-id`, ignoring case and punctuation. When that id has nothing on now, the channel name is tried against the guide's display name, and an Xtream login also uses the provider's now and next.
- Recordings can be stopped from the notification and from Settings, Recordings. The phone sign-in code expires after 10 minutes and locks after 10 wrong codes. Press New code to get another.
- Recordings are one at a time. A future recording starts only while the app is still open. Pausing live TV only reaches what the player has already buffered, about three minutes.
- Two channels at once uses two provider connections. A login that allows one stream will refuse one of them.

## Credits

The Figtree typeface is by The Figtree Project Authors, under the SIL Open Font License. The license ships in the app at `assets/licenses/Figtree-OFL.txt`.

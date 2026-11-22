# Cable TV

A personal, sideloaded Android and Android TV app that pulls together:

- **Live TV** from an Xtream Codes IPTV login (or any M3U playlist URL)
- **TV Guide** with a full program grid, from the provider's XMLTV feed or your own XMLTV URL
- **On Demand** from your Jellyfin server: movies, shows, search, genres, resume, and next up

The look is a 2000s cable guide: a deep navy grid, light blue info panels, a yellow highlight on the selected program, and a colored button legend along the bottom.

## Features

### Live TV
- Full-screen playback with a channel banner showing the program now and next, with progress
- Channel list panel (press LEFT) with group filters, search by name or number, and favorites
- Favorites (press Y, or tap the + and * in the list)
- Number entry, channel up and down, and LAST to jump back to the previous channel
- Audio and subtitle menu (press MENU)
- Automatic reconnects when a stream drops, with a manual RETRY when it gives up

### TV Guide
- Three-hour grid with a half-hour time header and a marker for the current time
- Info panel with the selected program's title, time, rating, and description
- Live preview of the channel that is tuned
- Left and right move across programs, up and down move across channels
- Page Up and Page Down (or the rewind and fast forward keys) move three hours at a time
- BLUE jumps to now, GREEN reloads the guide
- Guide data is cached for six hours on the device

### On Demand (Jellyfin)
- Home with Continue Watching, Next Up, and Recently Added
- Movies and Shows tabs with genre filters and paging
- Search across movies and shows
- Detail pages with Play, Resume, and Start Over, and season and episode lists
- Player controls: play and pause, 10 second and 1 minute seeks, audio and subtitle menu
- Automatic next episode, and CH+ and CH- to skip between episodes
- Watch progress is reported back to Jellyfin, so Continue Watching stays accurate
- If the TV cannot decode a file, the app asks Jellyfin to transcode it to H.264 and AAC (can be turned off in Settings)

### Settings
- Connection test buttons for Live TV, the playlist, the guide, and On Demand
- Reload guide button
- Transcode fallback switch

## Remote and keyboard controls

| Action | Remote |
| --- | --- |
| Channel up / down | CH+ / CH- or D-pad up / down |
| Open channel list | D-pad left |
| Jump to a channel | Type the number (up to 4 digits) |
| Last channel | LAST (or the TV's last channel key) |
| Show channel banner | OK / center button |
| Favorite the current channel | Y button (gamepad style remotes), or tap + in the channel list |
| Audio and subtitles | MENU |
| Open the guide | GUIDE |
| Back | Back |

In the guide, use the D-pad to move, OK to watch, and the colored keys listed at the bottom of the screen.

## Build and install (Android Studio)

1. Install Android Studio (Koala or newer).
2. Open the `CableTV` folder. Let Gradle sync finish. The Gradle wrapper is included, so no extra setup is needed.
3. Phone: pick your phone at the top and press Run.
4. Android TV: build the APK with **Build > Build Bundle(s) / APK(s) > Build APK(s)**. The file is at `app/build/outputs/apk/debug/app-debug.apk`.

To run the logic tests (parsers and channel logic), run **Build > Test** or `./gradlew test` (with Java 17 or newer).

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

## Project layout

```
app/src/main/java/com/dallas/cabletv/
  CableViewModel.kt      shared state: settings, channels, favorites, guide, connection tests
  MainActivity.kt
  data/
    Models.kt            channels, programs, On Demand items, settings
    ChannelLogic.kt      filtering, channel stepping, guide time math (pure, tested)
    M3uParser.kt         playlist parser (pure, tested)
    XmlTvParser.kt       streaming guide parser (pure, tested)
    EpgRepository.kt     guide download, disk cache, lookups
    XtreamClient.kt      Xtream Codes API: channels, guide, XMLTV feed
    JellyfinClient.kt    Jellyfin API: library, search, resume, progress reports, transcodes
    ChannelSource.kt     picks Xtream or M3U as the live source
    SettingsStore.kt     encrypted credentials, plain favorites and last channel
  ui/
    Theme.kt             palette and shared widgets
    App.kt               main menu and screen routing
    LiveTv.kt            full-screen live TV, channel list, banner
    Guide.kt             program grid and info panel
    OnDemand.kt          Jellyfin browser and player
    Playback.kt          the single shared player: reconnects, transcode fallback, tracks
    Overlays.kt          audio and subtitle menu, signal messages
    Settings.kt          settings screen with connection tests
    Format.kt            time and duration formatting
```

## Security notes

- Credentials are stored with AES-256 encryption through Android's EncryptedSharedPreferences.
- The Jellyfin password is used once to sign in and is not stored. The app keeps the access token only.
- The guide cache on the device is kept in the app's private cache folder. If you use an XMLTV URL that contains a password, that password is in the cache file.
- Nothing secret belongs in source files. Do not commit real credentials to a repository.

## Known limits

- Live TV reconnects use a short backoff (up to five tries). A channel the provider has taken down will still show NO SIGNAL.
- On phones the guide shows 90 minutes at a time instead of three hours, to keep the cells readable.
- The guide shows listings up to two days ahead, and only what the provider publishes.
- M3U-only sources have no guide data unless you give an XMLTV URL in Settings.
- The preview in the guide shows the channel that is tuned, not the channel under the cursor. Moving the cursor does not change streams, so the provider's connection limit is not used up while browsing.
- Subtitle and audio menus show the tracks the stream exposes. Some streams expose none.
- The Jellyfin transcode fallback and the device behavior need a check against a real server and TV. They could not be tested in the development environment.

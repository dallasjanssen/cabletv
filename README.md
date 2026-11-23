# Cable TV

A personal, sideloaded Android and Android TV app that pulls together:

- **Live TV** from an Xtream Codes IPTV login (or any M3U playlist URL)
- **Program guide** from the provider's XMLTV feed, or the guide link in an M3U playlist
- **On Demand** from your Jellyfin server

It opens straight into live TV on the last channel you watched. Menus sit on top of the picture, so the channel keeps playing behind Home and in a corner of the guide. Back from live TV opens Home, and Back again leaves the app. Keep watching on Home returns to the picture.

The screens are laid out for Android TV's 960 by 540 grid, with one typeface (Figtree), a dark low-glare background, and one focus style: whatever is selected turns white. Everything works with only the arrows, OK and Back. Channel and number keys are shortcuts for remotes that have them.

## Screens

- **Live TV**: up and down change channel. OK opens a bar with Guide, Home, Search, Channel list, Favorite, Watch from the start (channels with catch-up) and Audio and captions. Left opens the channel list, and Left again opens its categories. The guide and the channel list share the category you pick. A replay can be paused and skipped; Back returns from the replay to the live channel. OK on a guide listing that has already started plays it from the beginning when the channel has catch-up.
- **Home**: what is on now, with Keep watching and Guide buttons, then rows for favorite and recent channels, half-watched titles, and new movies and shows.
- **Guide**: a time grid with a line for now, details of the highlighted show, and a preview of the channel playing. Left from a channel name picks a category. OK on a channel name adds it to favorites.
- **Movies & Shows**: rows from Jellyfin and the provider. A title opens a details page with Resume, Start over, seasons and episodes. The player has its own controls and a panel for audio, captions and picture size.
- **Search**: an on-screen keyboard with results beside it, for channels, shows on now, movies and series.
- **Settings**: sections for phone setup, live TV, Jellyfin, channel list, profiles, parental lock and updates. Each section saves by itself. If the app ever closes unexpectedly, Updates shows the error so it can be reported.
- **First launch**: with nothing set up, the app opens on a QR code so setup happens on a phone.

## Remote and keyboard controls

| Action | Remote |
| --- | --- |
| Change channel | Up / down, or CH+ / CH- |
| Previous channel | Last |
| Everything you can do while watching | OK |
| Channel list | Left |
| Jump to a channel | Type the number (up to 4 digits), in live TV or the guide |
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

## Security notes

- Credentials are stored with AES-256 encryption through Android's EncryptedSharedPreferences.
- The Jellyfin password is used once to sign in and is not stored. The app keeps the access token only.
- Nothing secret belongs in source files. Do not commit real credentials to a repository.

## Known limits (v1)

- Jellyfin playback uses direct streams. If the TV cannot decode a file's codec, that title will fail. Transcoding can be added later.
- Channels with no listings in the XMLTV feed fall back to the provider's now and next listing.
- M3U-only sources have no guide data unless the playlist's channels map to guide entries. Xtream gives the best experience.

## Credits

The Figtree typeface is by The Figtree Project Authors, under the SIL Open Font License. The license ships in the app at `assets/licenses/Figtree-OFL.txt`.

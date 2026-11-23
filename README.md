# Cable TV

A personal, sideloaded Android and Android TV app that pulls together:

- **Live TV** from an Xtream Codes login, an M3U playlist, a Stalker / Ministra portal, or all of them together
- **Program guide** from the login, from a playlist, or from a guide link you paste in Settings, about two days ahead and two days behind
- **On Demand** from your Jellyfin server and from the provider's movie and show categories

It opens straight into live TV on the last channel you watched. Menus sit on top of the picture, so the channel keeps playing behind Home and in a corner of the guide. Back from live TV opens Home, and Back again leaves the app. Keep watching on Home returns to the picture.

The screens are laid out for Android TV's 960 by 540 grid, with one typeface (Figtree), a dark low-glare background, and one focus style: whatever is selected turns white. Everything works with only the arrows, OK and Back. Channel and number keys are shortcuts for remotes that have them.

## Screens

- **Live TV**: up and down change channel. OK opens a bar with Guide, Home, Search, Channel list, Favorite, Pause, Watch from the start, Record, Two channels, and Audio and captions. Left opens the channel list while you are at the live edge. After a pause, Left and Right move inside the few minutes already buffered. A replay can be paused and skipped; Back returns from the replay to the live channel. Playlist catch-up uses the tags in the M3U.
- **Home**: what is on now, with Keep watching and Guide buttons, then rows for favorite and recent channels, half-watched titles, and new movies and shows.
- **Guide**: a time grid covering tomorrow and the recent past, with a line for now, details of the highlighted show, and a preview of the channel playing. Refresh guide downloads the listings again. Left from a channel name picks a category. Up from the category reaches Refresh guide. OK on a channel name offers Watch now and Favorite. OK on a show that has not started yet offers a reminder or a recording.
- **Movies & Shows**: provider categories and Jellyfin genres, plus continue and recently added. A title opens a details page with the plot and year when the provider sends them. The next episode waits ten seconds and can be cancelled. Each profile can have its own Jellyfin login, so continue watching is not shared.
- **Search**: an on-screen keyboard with results beside it, for channels, shows on now or later, movies and series.
- **Settings**: phone setup, live TV (including a Guide URL and subscription expiry), Jellyfin, channel list, profiles, parental lock, recordings and updates. Hidden channels belong to the profile. Locked groups need the PIN before they play. Each section saves by itself.
- **First launch**: with nothing set up, the app opens on a QR code. Focus starts on New code.

## Remote and keyboard controls

| Action | Remote |
| --- | --- |
| Change channel | Up / down, or CH+ / CH- |
| Previous channel | Last |
| Everything you can do while watching | OK |
| Channel list | Left, while the channel is live |
| Rewind a few minutes | Pause, then Left. Right moves forward. Back to live is in the OK bar |
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

## Security notes

- Credentials are stored with AES-256 encryption through Android's EncryptedSharedPreferences.
- The Jellyfin password is used once to sign in and is not stored. The app keeps the access token only.
- Nothing secret belongs in source files. Do not commit real credentials to a repository.

## Known limits (v1)

- Jellyfin playback uses direct streams. If the TV cannot decode a file's codec, that title will fail. Transcoding can be added later.
- Channels with no listings in the XMLTV feed fall back to the provider's now and next listing.
- A playlist can name its guide with `url-tvg`. A provider that gives you a separate EPG address goes in Settings, Live TV, Guide URL. Refresh guide, on the Guide screen and in Settings, Live TV, downloads those listings again. The app also downloads them about once an hour. A gzip file is read as well as plain XML. Xtream, a playlist and a Stalker portal can all be saved at once. The guide matches a channel by its id, the same id the playlist calls `tvg-id`, ignoring case and punctuation. When that id has nothing on now, the channel name is tried against the guide's display name, and an Xtream login also uses the provider's now and next.
- Recordings can be stopped from the notification and from Settings, Recordings. The phone sign-in code expires after 10 minutes and locks after 10 wrong codes. Press New code to get another.
- Recordings are one at a time. A future recording starts only while the app is still open. Pausing live TV only reaches what the player has already buffered, about three minutes.
- Two channels at once uses two provider connections. A login that allows one stream will refuse one of them.

## Credits

The Figtree typeface is by The Figtree Project Authors, under the SIL Open Font License. The license ships in the app at `assets/licenses/Figtree-OFL.txt`.

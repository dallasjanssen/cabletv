# Cable TV

A personal, sideloaded Android and Android TV app that pulls together:

- **Live TV** from an Xtream Codes IPTV login (or any M3U playlist URL)
- **Program guide** from the provider's short EPG
- **On Demand** from your Jellyfin server

The look is a 2005 to 2010 cable box: green LED text, amber channel numbers, scan lines, a channel banner that slides up when you change channels, and number-key channel entry.

## Remote and keyboard controls

| Action | Remote |
| --- | --- |
| Channel up / down | CH+ / CH- or D-pad up / down |
| Jump to a channel | Type the number (up to 4 digits) |
| Show channel banner | OK / center button |
| Back to menu | Back |

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
- The guide shows the current and next program only. It does not render a full grid timeline yet.
- M3U-only sources have no guide data unless the playlist's channels map to guide entries. Xtream gives the best experience.
- Watch progress is not reported back to Jellyfin yet.

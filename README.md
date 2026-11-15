# APocketMp3

A small native Android player for local `.mp3` files. Android 8.0 or newer. No streaming, internet permission, accounts, ads, third-party libraries, bundled music, or downloaded codecs. Playback uses Android's built-in `MediaPlayer`.

**Current version:** 1.6 · **Minimum Android:** 8.0 (API 26) · **Target Android:** 15 (API 35)

[Download APocketMp3-v1.6.apk](https://github.com/bash-vibecoder/Mp3/releases/download/v1.6/APocketMp3-v1.6.apk) · [Latest release](https://github.com/bash-vibecoder/Mp3/releases/latest)

**Android package ID:** `com.apocketmp3.player`. Version 1.5 introduced this identity and displayed **APocketMp3** on the launcher and in Android settings. Android installs it as a separate app from version 1.4 and earlier. Imported-file access, favorites, saved settings, and playback bookmarks from the old package do not transfer automatically; scan or pick your MP3s and grant permissions again in the new app.

On the first launch, APocketMp3 explains music-library access and battery optimization, requests music permission through Android, and then offers the battery optimization exemption. You can choose **Use file picker** instead of granting whole-library access. The setup prompt appears once; the battery icon remains available for later changes.

## Features

- Tap **Scan phone** and grant audio-library access to find MP3s already indexed by Android.
- Or tap **Pick MP3s** / **+** to select local MP3 files without granting whole-library access. Multiple selection is supported. Files remain in their original location.
- Tap a song to play it. Search by title or artist; tap hearts to save favorites.
- Reopening the app restores your last song, position, and queue, paused and ready for **Play**. The seek bar also works before resuming. Position is saved every five seconds while playing, and on pause, seek, stop, and leaving the app. An abrupt process kill can lose the last few seconds. A moved/deleted file or revoked file access must be fixed before that song can play again.
- Use the seek bar, previous/next, shuffle, and repeat controls. Repeat cycles through off, all, and one. Shuffle, repeat, and your selected All songs/Favorites tab are saved when changed and restored after reopening the app, including after a phone restart.
- With shuffle on, **Previous** returns to the song you actually heard, and **Next** always makes a fresh random pick from the current song, including after going back. For example: A → B → Previous plays A → Next can play C. The visited A is recorded so you can still go back through C → A → B → A. Previous works on the first tap; at the beginning of shuffled history it restarts the current song. A fresh random draw can still choose a song heard earlier; it excludes the current song.
- The black and blue interface loads embedded MP3 artwork in the song list and behind the player, and supplies it to Android's media notification and lock screen. Pictures load locally in the background. MP3s without embedded artwork use a blue vinyl cover; nothing is fetched online.
- Playback continues with the screen off. Media notifications and the lock screen provide playback controls. Disconnecting headphones pauses playback; other apps' audio focus is respected.
- Tap the **battery icon** beside **+**, then **Allow**, and accept Android's prompt to exempt the app from battery optimization. The dialog also links to **App settings**: if your phone still stops music, choose **Battery → Unrestricted** or allow background activity and auto-start where available. The icon refreshes its status when you return. This setting belongs to Android and requires your approval on the phone; some manufacturers have additional controls.
- Long-press a song for file information. Manually picked files can be removed from the library without deleting the file.
- The player shows song title, artist, duration, and elapsed time. Missing metadata uses readable defaults, and unreadable or moved files produce a playback error so you can select another song.

## Local data and background playback

The app lists only files named `.mp3`. Move new music into the phone's Music folder, or pick it directly if the Android media index has not found it yet. Files must remain accessible on the device. Favorites, manually picked file references, playback settings, the current queue, and the last song's position are saved locally. The last 512 listening-history entries are kept in memory and cleared when the playback service is recreated or you select a new song directly. Decoded artwork is resized to at most 384 pixels on its longer edge and shared in a cache capped at 4 MiB.

Active playback uses a foreground media service and MediaPlayer's playback wake lock; an extra preparation wake lock covers track loading and transitions. It is bounded to 60 seconds and released when playback starts, pauses, stops, or fails. Dismissing the recent-app task does not request the playback service to stop. Android force-stop, its active-app Stop button, memory pressure, and manufacturer battery controls can still stop the app; it does not automatically restart or autoplay after those actions.

Permissions are limited to reading the audio library when requested, running a foreground music service, keeping the CPU awake during playback/preparation, and requesting a battery optimization exemption. Picking MP3s through Android's file picker works without granting whole-library access. The app has no internet permission, and Android app backup is disabled.

## Changelog

### 1.6

- Add a one-time first-launch setup flow for music-library permission and battery optimization.
- Allow users to continue with the file picker without granting whole-library access.
- Replace the launcher artwork with a circular black icon and blue inverted audio mark.
- Update the versioned APK link to `APocketMp3-v1.6.apk`.

### 1.5

- Change the Android application ID and Java namespace to `com.apocketmp3.player`.
- Display **APocketMp3** in the launcher, player, notifications, and battery settings messages.
- Update source paths, build configuration, and tests for the renamed package.
- Clear generated compilation outputs before builds to prevent obsolete package classes from entering the APK.
- Use a separate Android app identity; existing data under the previous package remains with the old app.

### 1.4

- Remember the last song, playback position, and queue across app restarts.
- Restore paused and seek to the saved position before playback starts.
- Save position every five seconds and on pause, seek, stop, and leaving the app.
- Preserve the latest seek request during rapid seeks and handle canceled preparation safely.
- Name release APKs `APocketMp3-v<version>.apk`, starting with `APocketMp3-v1.4.apk`.
- Add queue restoration, snapshot isolation, and restored shuffle tests.

### 1.3

- Remember shuffle, repeat mode, and the selected All songs/Favorites tab.
- Add a battery settings button with Android exemption and app-settings access.
- Keep the CPU awake during preparation and track transitions with a bounded wake lock.
- Explicitly keep the playback service running when the recent-app task is dismissed.
- Handle Android foreground-service restrictions with a playback error instead of crashing.

### 1.2

- Fix artwork sizing that could collapse the song list by painting it as the player background.
- Use a compact header to leave more room for the library.
- Make every shuffled Next a fresh random choice, including after Previous.
- Retain songs visited while going back so Previous can retrace the listening history.

### 1.1

- Introduce the black and blue interface.
- Display embedded MP3 artwork in the song list, player, notification, and lock screen.
- Load artwork locally in the background with a bounded cache and a fallback cover.
- Add listening history so Previous returns to songs actually heard in shuffle mode.

### 1.0

- Initial native local MP3 player for Android 8.0 and newer.
- Scan the phone's audio library or pick multiple MP3 files.
- Search songs, save favorites, and inspect file information.
- Play/pause, seek, skip tracks, shuffle, and repeat off/all/one.
- Support background playback, media notification and lock-screen controls, headphone-disconnect pause, and audio focus handling.
- Provide a standalone signed APK build and queue behavior tests.

## Build

Open the project in Android Studio with JDK 17 and Android SDK 35, let Gradle sync, and build the `app` module. There are no app dependencies. The Gradle release build requires your own signing configuration for distribution.

For an ARM Linux environment without Google's x86 build tools, the provided `scripts/build.sh` uses native `aapt`, `zipalign`, `apksigner`, Java, and D8:

```bash
sudo apt-get install --no-install-recommends openjdk-17-jdk-headless aapt zipalign apksigner zip unzip curl
mkdir -p .tools
curl -fL https://raw.githubusercontent.com/Sable/android-platforms/master/android-35/android.jar -o .tools/android.jar
curl -fL https://raw.githubusercontent.com/Sable/android-platforms/master/android-34/android.jar -o .tools/android-34.jar
curl -fL https://storage.googleapis.com/r8-releases/raw/8.3.37/r8.jar -o .tools/r8.jar
bash scripts/build.sh
```

The standalone build compiles against API 35 and packages resources using API 34 because the Ubuntu ARM resource tool predates API 35's compact resource format. All referenced resource IDs are present in API 34. The app still targets API 35. Build tools stay outside the APK.

The signed output is `dist/APocketMp3-v1.6.apk`. The script reads the version from `AndroidManifest.xml` to name future builds automatically.

The standalone script generates a development signing key in `.tools/pocket-dev.jks` and reuses it for updates. Keep that key if you want to install subsequent builds over this one. Use your own protected release key for a public release. `.tools`, build outputs, and keys are ignored by Git.

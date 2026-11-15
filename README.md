# Pocket MP3

A small native Android player for local `.mp3` files. Android 8.0 or newer. No streaming, internet permission, accounts, ads, third-party libraries, bundled music, or downloaded codecs. Playback uses Android's built-in `MediaPlayer`.

**Current version:** 1.4 · **Minimum Android:** 8.0 (API 26) · **Target Android:** 15 (API 35)

## Install

The signed APK is generated at `dist/PocketMP3-v1.4.apk`. The build script takes the version from the manifest and includes it at the end of the filename. Copy it to your phone, open it, and allow your file manager to install this app if Android asks.

Install updates over the existing app using the same signing key to retain imported files, favorites, and saved settings. APKs and signing keys are not stored in this repository; see [Build](#build) to create an APK. The last-song bookmark begins saving when you play music in version 1.4.

## Features

- Tap **Scan phone** and grant audio-library access to find MP3s already indexed by Android.
- Or tap **Pick MP3s** / **+** to select local MP3 files without granting whole-library access. Multiple selection is supported. Files remain in their original location.
- Tap a song to play it. Search by title or artist; tap hearts to save favorites.
- Reopening the app restores your last song, position, and queue, paused and ready for **Play**. The seek bar also works before resuming. Position is saved every five seconds while playing, and on pause, seek, stop, and leaving the app. An abrupt process kill can lose the last few seconds. A moved/deleted file or revoked file access must be fixed before that song can play again.
- Use the seek bar, previous/next, shuffle, and repeat controls. Repeat cycles through off, all, and one. Shuffle, repeat, and your selected All songs/Favorites tab are saved when changed and restored after reopening the app, including after a phone restart.
- With shuffle on, **Previous** returns to the song you actually heard, and **Next** always makes a fresh random pick from the current song, including after going back. For example: A → B → Previous plays A → Next can play C. The visited A is recorded so you can still go back through C → A → B → A. Previous works on the first tap; at the beginning of shuffled history it restarts the current song. A fresh random draw can still choose a song heard earlier; it excludes the current song.
- The black and blue interface loads embedded MP3 artwork in the song list and behind the player, and supplies it to Android's media notification and lock screen. Pictures load locally in the background. MP3s without embedded artwork use a blue vinyl cover; nothing is fetched online.
- Playback continues with the screen off. Media notifications and the lock screen provide playback controls. Disconnecting headphones pauses playback; other apps' audio focus is respected.
- Tap the **battery icon** beside **+**, then **Allow**, and accept Android's prompt to exempt Pocket MP3 from battery optimization. The dialog also links to **App settings**: if your phone still stops music, choose **Battery → Unrestricted** or allow background activity and auto-start where available. The icon refreshes its status when you return. This setting belongs to Android and requires your approval on the phone; some manufacturers have additional controls.
- Long-press a song for file information. Manually picked files can be removed from the library without deleting the file.
- The player shows song title, artist, duration, and elapsed time. Missing metadata uses readable defaults, and unreadable or moved files produce a playback error so you can select another song.

## Local data and background playback

The app lists only files named `.mp3`. Move new music into the phone's Music folder, or pick it directly if the Android media index has not found it yet. Files must remain accessible on the device. Favorites, manually picked file references, playback settings, the current queue, and the last song's position are saved locally. The last 512 listening-history entries are kept in memory and cleared when the playback service is recreated or you select a new song directly. Decoded artwork is resized to at most 384 pixels on its longer edge and shared in a cache capped at 4 MiB.

Active playback uses a foreground media service and MediaPlayer's playback wake lock; an extra preparation wake lock covers track loading and transitions. It is bounded to 60 seconds and released when playback starts, pauses, stops, or fails. Dismissing the recent-app task does not request the playback service to stop. Android force-stop, its active-app Stop button, memory pressure, and manufacturer battery controls can still stop the app; it does not automatically restart or autoplay after those actions.

Permissions are limited to reading the audio library when requested, running a foreground music service, keeping the CPU awake during playback/preparation, and requesting a battery optimization exemption. Picking MP3s through Android's file picker works without granting whole-library access. The app has no internet permission, and Android app backup is disabled.

## Changelog

### 1.4

- Remember the last song, playback position, and queue across app restarts.
- Restore paused and seek to the saved position before playback starts.
- Save position every five seconds and on pause, seek, stop, and leaving the app.
- Preserve the latest seek request during rapid seeks and handle canceled preparation safely.
- Generate APK filenames from the app version, starting with `PocketMP3-v1.4.apk`.
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

The standalone script generates a development signing key in `.tools/pocket-dev.jks` and reuses it for updates. Keep that key if you want to install subsequent builds over this one. Use your own protected release key for a public release. `.tools`, build outputs, and keys are ignored by Git.

## Verification

`scripts/build.sh` compiles the app, creates DEX bytecode, packages it, verifies its APK signature and ZIP alignment, and runs Java tests for queue completion, manual skipping, repeat modes, shuffle, Previous history, the exact A → B → A → C example for manual and automatic Next, retention of visited songs, shuffle toggling, history reset/bounds, empty queues, metadata fallbacks, and restoring a saved queue selection.

Device checks to perform: first-run permission grant/denial, selecting MP3s, restarting with saved imports/favorites, playing valid and corrupted MP3s, seeking, shuffled Previous/Next from the screen and notification, screen-off playback, headphone disconnect, incoming-call audio focus, embedded artwork and missing/corrupted pictures, recycled rows during scrolling, and quick skips while artwork loads. A successful build does not substitute for those device checks.

For version 1.3, enable shuffle, choose each repeat mode, and select Favorites; force-stop and reopen to check that the choices remain. Repeat after rebooting. Check that declining the battery prompt leaves playback usable and that accepting it updates the battery icon on return. Play several short tracks with the screen off, dismiss the app from Recents, and verify continued playback and track transitions; pause/stop and check that playback wake locks are released. Test both the Android exemption and any manufacturer-specific battery settings on the target phone.

For version 1.4, play and seek into a song, pause, force-stop, then reopen: the song and saved time should appear without audio starting. Press Play to continue from that point and confirm Next/shuffle use the previous queue. Repeat after rebooting and after stopping from the media notification. Test rapid seeks, pausing or disconnecting headphones while the saved position is loading, a removed file, and revoked audio access. Force-stopping during playback should restore within about five seconds of the last position.

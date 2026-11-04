# Retro FM Android

A dedicated Android app for listening to Retro FM without going through aggregators that add ads before playback. It supports phones, Android Auto projection and Android Automotive OS (AAOS) through Media3's `MediaBrowserService`.

## Features

- Ad-free (aggregator-free) playback of Retro FM.
- Play/pause with large, car-friendly controls.
- Shows the current song title and artist with artwork.
- Background playback with a media notification.
- Handles media buttons (headset, steering-wheel controls, car UI).
- Exposed as a media source in Android Auto and Android Automotive OS.
- Google Cast (Chromecast): cast the stream to Chromecast/Nest devices (phone app only).
- Reconnects automatically after network drops.
- Detects server-injected ads and mutes them, with a "Reklam" countdown in the UI
  (can be turned off with `RetroFmConfig.MUTE_ADS`).
- Song info comes from the stream's own ICY metadata, exactly at the song change. Since
  2026-08-08 the app plays the station's own Icecast (Mad Men Media) instead of Bauer's old
  relay, whose metadata froze in July when Retro FM left Bauer/RadioPlay. No API is polled any
  more. Since 2026-09-14 the stream is Revma (RCS). History and measurements:
  `.claude/skills/now-playing-metadata/SKILL.md`.
- Sends field logs and metrics over OpenTelemetry to home-server's telemetry edge for
  debugging in the car (production cars have no adb) — never tokens, credentialed URLs or PII
  (see `AGENTS.md`, "Log hygiene is a wire contract", and `.claude/skills/field-logs/SKILL.md`).

## Tech stack

- Kotlin + Jetpack Compose (Material 3)
- AndroidX Media3 (ExoPlayer + MediaSession + Cast)
- Retrofit + Kotlinx Serialization
- Coil
- MVVM + Repository

Exact versions are in the modules' `build.gradle.kts` — they move continuously and are not
documented here.

## Build

The project needs JDK 17 and the Android SDK (API 36, build-tools 36.0.0).

```bash
./gradlew :app:assembleDebug         # phone + Android Auto
./gradlew :app:assembleRelease
./gradlew :automotive:assembleDebug  # Android Automotive OS (built-in car display)
./gradlew :automotive:assembleRelease
```

## Tests

```bash
./gradlew :core:testDebugUnitTest
```

## Install on a device

```bash
./gradlew :app:installDebug
adb shell am start -n com.magter.retrofm/com.retrofm.android.ui.MainActivity
```

## Android Auto / Android Automotive OS

The app registers a `MediaLibraryService` (Media3) so that it appears as a media source in both
Android Auto and Android Automotive OS — but through two separate APKs (`:app` and
`:automotive`), since one artifact cannot support both under Google's guidelines. See the
`:automotive` entry under "Project structure" below for details.

**Android Auto** (the phone projected onto the car's display): works automatically when `:app`
is installed on the phone and it is connected to any car with Android Auto support — the car's
own operating system plays no part here.

**Android Automotive OS** (the app runs natively on the car's own display, without a phone), test in an emulator:

1. Start the Android Automotive emulator.
2. Install the car-specific APK: `adb install automotive/build/outputs/apk/debug/automotive-debug.apk`.
3. Open the media player in the car UI and choose "Retro FM".

Note: real cars (Volvo's AAOS units, for example) often block sideloading and developer mode in
production. Sideloading works only in the emulator; a real car needs Play Store distribution
through the dedicated Automotive OS track in Play Console.

## Signing and release (Google Play)

Real releases go through GitHub Actions, not manual uploads: bump `versionName` in both `app/`
and `automotive/build.gradle.kts`, tag `vX.Y.Z` (the same as versionName) and push the tag. The
workflow builds, signs and uploads both bundles to Play internal testing (phone → track
`internal`, automotive → `automotive:internal`). `versionCode` is derived from the run number
and is never bumped by hand. Details and traps are in `AGENTS.md`, section "Release".

For local release builds: `:app`/`:automotive` have a signing config that reads its values from
Gradle properties — **no secrets are in the repo**. Without the properties the release artifact
is built unsigned (useful for R8 and bundle verification). The values (the existing upload key,
kept outside the repo) go in `~/.gradle/gradle.properties` or are passed as `-P` flags:

```properties
RETROFM_UPLOAD_STORE_FILE=/absolute/path/to/upload-keystore.jks
RETROFM_UPLOAD_STORE_PASSWORD=…
RETROFM_UPLOAD_KEY_ALIAS=upload
RETROFM_UPLOAD_KEY_PASSWORD=…
```

## Configuration

All stream URLs, API endpoints, station identity and behaviour knobs (buffers, reconnect
backoff, ad mute) are in `core/src/main/java/com/retrofm/android/data/config/RetroFmConfig.kt`.

## Project structure

The project is split into three Gradle modules:

- **`:core`** — shared code: network and data (`data/`, `di/`), playback and
  `MediaLibraryService` (`playback/`). No UI, no launcher activity. Used by both `:app` and
  `:automotive`.
- **`:app`** — the phone app. Compose UI, `MainActivity`, and the Android Auto markers
  (`com.google.android.gms.car.application`) in the manifest.
- **`:automotive`** — the Android Automotive OS app (runs natively on the car's own display,
  without a phone). The same `applicationId` as `:app`, to share one Play Store listing, but its
  own manifest with no launcher activity and `android.hardware.type.automotive` set to
  `required="true"`.

Top-level packages (file-by-file lists rot — see the source tree for detail):

```
core/       com.retrofm.android.data      (api, config, model, repository, di)
            com.retrofm.android.playback  (player/session, ICY and ad detection,
                                           album-art ContentProvider, media tree)
            com.retrofm.android.telemetry (OpenTelemetry logs and metrics — see the field-logs skill)
app/        com.retrofm.android.ui        (Compose UI, ViewModel, theme)
automotive/ manifest + resources only     (all code comes from :core)
```

## Notes

- Without upload values (see "Signing and release" above) the release artifact is built unsigned. With them it is signed automatically.
- The Retro FM logo and stream belong to Bauer Media. The app is intended for personal use.

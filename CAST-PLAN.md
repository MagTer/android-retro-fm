# Retro FM Android — Chromecast Support & Play Store Trial Plan

**Date:** 2026-07-22
**Goal:** Add Google Cast (Chromecast) support to the phone app (`:app`), then publish as a private (closed-testing) trial on Google Play. The `:automotive` module must remain cast-free and unaffected.
**Written as a work order:** each step has a location and a concrete change. Execute phases in order; each phase ends in a buildable state.

---

## Approach decision

Media3 1.8.0 (Aug 2025) introduced `CastPlayer.Builder`, which turns `CastPlayer` into a **unified local+remote player**: it wraps the existing `ExoPlayer` as its local player and switches automatically between local output and a Cast device when the route changes (integrated with the Android Output Switcher). This replaces the old pattern of holding two players and swapping them on the `MediaSession` via `SessionAvailabilityListener`.

We are already on Media3 **1.10.1**, so we use the unified `CastPlayer.Builder` path. Fallback if it proves buggy for our live-stream case: `RemoteCastPlayer` + manual `MediaSession.setPlayer()` switching (the classic pattern, still supported).

**Receiver:** the Cast **Default Media Receiver** (no registration in the Cast Developer Console needed, no fee). It plays plain audio URLs like our MP3 Icecast stream. A styled receiver (custom branding on the TV) can be added later — that requires a $5 Cast Console registration and a receiver app ID in a custom `CastOptionsProvider`.

**Module split strategy:** cast classes live in `:core` (where `PlayerManager` is), but Cast is *activated* by the `OPTIONS_PROVIDER_CLASS_NAME` manifest meta-data, which we declare **only in `:app`**. On the automotive build (and any device without Google Play services) `CastContext` initialization fails, we catch it, and the service falls back to plain `ExoPlayer`. One service class, no reflection, automotive keeps working. Cost: `media3-cast` + its transitive `play-services-cast-framework`/`mediarouter` are included (unused) in the automotive APK (~2–3 MB after R8); acceptable for now, can be split into a `PlayerFactory` seam later if it bothers us.

---

## Phase 1 — Dependencies & manifests

### 1.1 `:core` — `core/build.gradle.kts`
```kotlin
implementation("androidx.media3:media3-cast:1.10.1")
```

### 1.2 `:app` — `app/build.gradle.kts`
```kotlin
implementation("androidx.mediarouter:mediarouter:1.7.0")   // MediaRouteButton (use latest stable)
implementation("androidx.appcompat:appcompat:1.7.1")       // themed context for mediarouter dialogs
```
(`play-services-cast-framework` arrives transitively via `media3-cast`; only pin it explicitly if version conflicts appear.)

### 1.3 `:app` manifest — `app/src/main/AndroidManifest.xml`, inside `<application>`
```xml
<!-- Activates Google Cast for the phone build only. :automotive has no such
     meta-data, so CastContext init fails there and playback stays local. -->
<meta-data
    android:name="com.google.android.gms.cast.framework.OPTIONS_PROVIDER_CLASS_NAME"
    android:value="androidx.media3.cast.DefaultCastOptionsProvider" />

<!-- Enables Output Switcher transfer between phone and cast devices. -->
<receiver
    android:name="androidx.mediarouter.media.MediaTransferReceiver"
    android:exported="false" />
```
`DefaultCastOptionsProvider` (ships in `media3-cast`) uses the Default Media Receiver and enables remote-to-local transfer. Only write a custom `OptionsProvider` if we later need a styled receiver ID or to tune resume behavior.

**Do not touch** `core/src/main/AndroidManifest.xml` or `automotive/src/main/AndroidManifest.xml`.

---

## Phase 2 — Playback layer (`:core`)

### 2.1 `MediaItemTree.kt` — mimeType is mandatory for cast
The cast item converter throws if a `MediaItem` has no mimeType. On `stationMediaItem` add:
```kotlin
.setMimeType(MimeTypes.AUDIO_MPEG)   // androidx.media3.common.MimeTypes
```
(Harmless for local ExoPlayer; it already sniffs the stream.)

### 2.2 `PlayerManager.kt` — wrap ExoPlayer in a unified CastPlayer when available
- Keep building `exoPlayer` exactly as today (reconnect policy, audio attributes, etc.).
- Add a public `val player: Player` that the service uses for the session:
```kotlin
val player: Player = try {
    CastPlayer.Builder(context).setLocalPlayer(exoPlayer).build()
} catch (e: Exception) {
    // No Play services / no cast meta-data (e.g. :automotive) → local-only.
    exoPlayer
}
```
- Route `play()`, `pause()`, `updateMediaItem()`, `release()` through `player` instead of `exoPlayer` (CastPlayer delegates to the local player when not casting). On `release()`, release the CastPlayer (it releases/wraps the local player — verify 1.10.1 behavior; if it doesn't release the wrapped player, release both).
- Move the `PlayerEventListener` registration to `player` so reconnect logic sees errors from whichever route is active. Reconnect semantics while casting: `prepare()` on CastPlayer re-loads on the receiver — acceptable.
- The live-edge resume logic (`seekToDefaultPosition()` on resume) stays keyed on `player`.

### 2.3 `RetroFmPlaybackService.kt`
- Build the `MediaLibrarySession` with `playerManager.player` instead of `playerManager.exoPlayer`.
- The `PlaybackStateListener` (metadata-poll gating) attaches to `playerManager.player`.
- Everything else (library tree, resumption, notification) is player-agnostic and stays as-is.

### 2.4 Known risk — metadata updates while casting (`replaceMediaItem`)
`applyTrackMetadata()` calls `replaceMediaItem(0, item)` on every track change. Locally this is a seamless in-place update. On the cast route it may translate to a queue update that **re-loads the stream on the receiver** (audible gap every ~3 minutes). This must be tested on a real Chromecast (Phase 4). If it interrupts:
1. Preferred fix: in the service, check `player.deviceInfo.playbackType`; when `PLAYBACK_TYPE_REMOTE`, skip `replaceMediaItem` and accept static "Retro FM" branding on the receiver for v1 (phone UI will also show static metadata while casting — acceptable trade-off for a trial).
2. Better later: custom `MediaItemConverter` that maps our metadata into cast `MediaMetadata` plus receiver-side media-status updates, or a styled receiver that fetches now-playing itself.

### 2.5 Optional (only if the receiver misbehaves on the endless stream)
The default converter marks items as buffered streams. If the receiver UI shows a broken seek bar or stalls, supply a custom `MediaItemConverter` to `CastPlayer.Builder` that sets `MediaInfo.STREAM_TYPE_LIVE`. Don't do this preemptively.

---

## Phase 3 — Phone UI (`:app`)

### 3.1 Theme — `app/src/main/res/values/themes.xml`
The mediarouter chooser/controller dialogs inflate against AppCompat attributes; the current parent is the platform `android:Theme.Material.Light.NoActionBar` and will crash the dialogs. Change to:
```xml
<style name="Theme.RetroFM" parent="Theme.AppCompat.DayNight.NoActionBar">
```
(keep the three color items; the visible UI is 100% Compose so nothing else changes).

### 3.2 `MainActivity.kt`
`MediaRouteButton` shows its chooser as a fragment dialog and **requires a `FragmentActivity` host**. Change:
```kotlin
class MainActivity : FragmentActivity() { … }
```
(`FragmentActivity` extends `ComponentActivity`; `setContent`, edge-to-edge and the permission launcher keep working. `androidx.fragment` arrives via mediarouter/appcompat.)

### 3.3 `PlayerScreen.kt` — cast button
Add a cast icon in the top-right corner (Box-aligned above the existing column), wrapping the classic view:
```kotlin
@Composable
private fun CastButton(modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            MediaRouteButton(context).also { CastButtonFactory.setUpMediaRouteButton(context, it) }
        }
    )
}
```
Notes:
- The factory `context` is the Activity-scoped Compose context — required so the button can find the `FragmentActivity` (do not pass application context).
- Guard rendering: only compose the button if `CastContext` initialized (e.g. a `isCastAvailable` flag exposed from the ViewModel or a simple `remember { runCatching { CastContext.getSharedInstance(context) }.isSuccess }`). On devices without Play services the button then simply doesn't appear.
- Tint: the default mediarouter button is dark-on-light; set `mediaRouteButtonTint` via theme overlay or use `app:mediaRouteButtonTint`-equivalent programmatically if it's invisible on the dark background.

### 3.4 Optional polish (nice for the trial, not blocking)
- "Casting to <device>" caption under the artwork: listen to `onDeviceInfoChanged` in `PlayerViewModel`; when `playbackType == PLAYBACK_TYPE_REMOTE` show the route name (`CastContext.sessionManager.currentCastSession?.castDevice?.friendlyName`).
- While casting, the play/pause button already controls the receiver through the same `MediaController` — no extra work.

---

## Phase 4 — Verification protocol (device testing)

Local regression (phone):
1. Play ≥5 min: no gaps at metadata poll boundaries; title/artist/artwork update on track change.
2. Pause ≥1 min → play: resumes at live edge.
3. Airplane mode 10 s during playback → back: reconnects with backoff.
4. Background playback + notification controls + headset button.

Cast (needs a real Chromecast/Nest speaker on the same Wi-Fi):
5. Cast button appears; connect while playing → audio transfers, phone goes silent.
6. Start cast while idle → press play on phone → receiver plays.
7. Pause/play from phone notification and from the Output Switcher chip.
8. Volume: phone volume keys control receiver volume while casting.
9. **Track change while casting → listen for stream interruption (the §2.4 risk). Decide fix path.**
10. Disconnect cast → playback returns to local (MediaTransferReceiver) or stops gracefully — verify which, both acceptable if intentional.
11. Kill the app while casting → receiver keeps playing (cast sessions outlive the sender) → reopen app → reconnects to session.

Regression (car targets):
12. Android Auto via DHU: browse tree, play, metadata — unchanged.
13. Automotive emulator (`:automotive` APK): installs, plays, **no cast button anywhere, no crash at service start** (proves the try/catch fallback).

Release build:
14. `:app:bundleRelease`, install the universal APK from the bundle (or `assembleRelease`): verify playback, now-playing parsing (R8 + kotlinx-serialization), **and casting** (R8 + cast framework: add keep rules only if it actually breaks). Add `-dontwarn`/keep rules to `app/proguard-rules.pro` only as needed.

---

## Phase 5 — Play Store private trial checklist

Build & config:
- [ ] **Signing:** generate an upload keystore (`keytool -genkeypair … -validity 10000`), store outside the repo, add `signingConfigs.release` to `app/build.gradle.kts` reading credentials from `~/.gradle/gradle.properties` or env vars. Enroll in Play App Signing on upload (default).
- [ ] **AAB, not APK:** Play requires App Bundles — `./gradlew :app:bundleRelease`.
- [ ] **targetSdk 36:** from **2026-08-31**, new apps *and updates* must target API 36 (Android 16). The trial starts now on 35, but any update after that date needs 36 — since `compileSdk` is already 36, bump `targetSdk` to 36 in `:app` (and later `:automotive`) as part of this work and retest.
- [ ] **Adaptive icon:** only legacy PNG mipmaps exist. Add `mipmap-anydpi-v26` adaptive icon (foreground vector + `#000F2B` background) so the launcher icon isn't shrunk into a white circle on modern devices.
- [ ] Bump `versionCode`/`versionName` per upload. (Later, when the automotive APK joins the same listing, its `versionCode` must differ from the phone artifact's.)

Play Console:
- [ ] Create app → **Closed testing** track (email-list of testers; this is the "private trial"). Note: closed-testing releases still pass Google review.
- [ ] **Privacy policy URL** — required for all apps; a one-page "this app collects no personal data" page is enough.
- [ ] **Data safety form** — declare no data collected/shared.
- [ ] **Foreground service declaration** — targetSdk ≥34 requires declaring the `mediaPlayback` FGS type usage in the console (with a short demo video of background playback).
- [ ] Store listing assets: 512×512 icon, feature graphic 1024×500, ≥2 phone screenshots.
- [ ] If the developer account is a *personal* account created after Nov 2023: production later requires a 12-tester/14-day closed test first — the trial itself satisfies/starts that clock.
- [ ] Cast needs **no** Play Console or Cast Console setup when using the Default Media Receiver.

Legal/risk (decide, don't ignore):
- [ ] The app uses Bauer Media's name, logo, and undocumented stream/API. Google review may flag impersonation/IP even on a closed track. Mitigations for the trial: neutral listing name (e.g. "Retro Player (trial)"), own icon rather than the Bauer logo in the listing, listing marked as closed test. Accepting the risk as-is is also defensible for a tester-only track — but be aware rejection or takedown is possible, per REQUIREMENTS.md §Risker.

---

## Suggested execution order

1. Phase 1 + 2 + 3 (one PR-sized change; builds after each phase).
2. Phase 4 steps 1–4 + 12–13 (regressions) → then 5–11 on real cast hardware → resolve §2.4.
3. Phase 5 build items (signing, targetSdk 36, adaptive icon) → release-build retest (step 14).
4. Play Console setup and first closed-testing upload.

---

## Delegated implementation scope (work order for the coding agent)

**Date added:** 2026-07-22. Everything below is implementable in code without cast hardware or a Play Console account. Work on a feature branch off `main` (e.g. `feature/chromecast-play-trial`), one atomic commit per work package, matching the existing code style (comment density, naming, Swedish user-facing strings in resources).

### Environment note
The dev machine has **no JDK and no Android SDK** (`java` missing, no `ANDROID_HOME`, no `local.properties`). Before implementing, attempt to provision a build environment so changes can be compile-verified:
1. JDK 17+ (Gradle 9.4.1 requires ≥17): system package, or a user-local install (e.g. Temurin tarball into `~/.local/`).
2. Android SDK: download commandline-tools from `https://dl.google.com/android/repository/` into `~/android-sdk`, accept licenses, install `platforms;android-36` and `build-tools;36.0.0`, write `local.properties` (must be gitignored — verify `.gitignore` covers it, add if missing).
3. Verify with `./gradlew :core:testDebugUnitTest :app:assembleDebug :automotive:assembleDebug`.

If provisioning fails (network/permission limits), implement statically with extra care, run no builds, and **state clearly in the final report that nothing was compile-verified**.

### Work packages

**WP1 — Cast playback integration (`:core` + `:app` wiring):** Phase 1 (all of it) + Phase 2 §2.1–2.3 exactly as specified above.

**WP2 — Metadata-while-casting guard:** since §2.4 cannot be hardware-tested now, implement the safe default preemptively: in `RetroFmPlaybackService.applyTrackMetadata`, skip the `replaceMediaItem` call when `player.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE` (with a comment referencing this section; revisit after hardware testing). This guarantees casting is never interrupted by track changes, at the cost of static receiver metadata in v1.

**WP3 — Phone UI (`:app`):** Phase 3 §3.1–3.3, plus the §3.4 "Casting to <device>" caption (drive it from `onDeviceInfoChanged` in `PlayerViewModel`; add the string to `app/src/main/res/values/strings.xml` in Swedish like the rest, e.g. "Castar till %1$s").

**WP4 — Up-to-date pass:** bump `targetSdk` to **36** in `:app` and `:automotive` (compileSdk already 36; check Android 16 behavior changes relevant to a media app). Check Google Maven/Maven Central for newer **stable patch** releases of the pinned libraries (Media3 1.10.x, Compose BOM, Coil, mediarouter, etc.) and apply safe bumps only — no major-version migrations. Keep `REQUIREMENTS.md` version table and `README.md` in sync with what actually changed.

**WP5 — Play compliance (code side only):**
- Adaptive icon: `mipmap-anydpi-v26/ic_launcher.xml` (+ round) in `:core` with a vector foreground and `#000F2B` background; keep legacy PNGs as fallback.
- Release signing scaffold in `app/build.gradle.kts`: a `signingConfigs.release` populated from Gradle properties (`RETROFM_UPLOAD_STORE_FILE`, `RETROFM_UPLOAD_STORE_PASSWORD`, `RETROFM_UPLOAD_KEY_ALIAS`, `RETROFM_UPLOAD_KEY_PASSWORD`); when the properties are absent the release build must still work (unsigned) — do **not** generate a keystore or commit any secret.
- Document the signing setup + AAB build (`:app:bundleRelease`) in `README.md`.

**WP6 — Tests & verification:** add/extend unit tests where they add real value (e.g. station `MediaItem` carries `AUDIO_MPEG` mimeType; metadata-guard logic if extractable). Run the full test + assemble matrix for all three modules, plus `:app:bundleRelease` if a signing-less release build is possible. Fix R8 issues (cast framework keep rules only if the build actually surfaces them).

### Out of scope for the agent (human follow-up)
Keystore generation and secret storage; Play Console setup (listing, data safety, FGS declaration, privacy policy); all Phase 4 hardware testing (cast device, DHU, automotive emulator); the trademark/naming decision.

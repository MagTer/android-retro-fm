# Retro FM Android — Code Review & Improvement Report

**Date:** 2026-07-05
**Reviewed by:** Claude (Fable 5), static review of the initial Kimi Code implementation + live verification of external endpoints.
**Audience:** This report is written to be fed to an AI coding agent (Sonnet 5) as a work order. Each finding has a location, an explanation, and a concrete fix. Findings are ordered by priority. The app builds but has **not yet been run on a device** — P0 items below are expected to produce audible/user-visible failures on first real use.

---

## Live verification performed (2026-07-05)

| Check | Result |
|---|---|
| `GET https://listenapi.planetradio.co.uk/api9.2/nowplaying/res` | ✅ 200, valid JSON, matches DTO (extra unknown fields present; `ignoreUnknownKeys` handles them) |
| Stream `https://live-bauerse-fm.sharp-stream.com/retrofm_mp3?direct=true` | ✅ 200, `audio/mpeg` |
| Playlist endpoint **as actually coded** (`nowplaying/?StationCode=res`) | ❌ 200 but `text/xml` — wrong endpoint, would crash the JSON converter (see P0-4) |

---

## P0 — Critical bugs (fix before first run)

### P0-1. Stream restarts every 30 seconds during playback (metadata poll resets the player)

**Location:** `app/src/main/java/com/retrofm/android/playback/RetroFmPlaybackService.kt:70-100` and `app/src/main/java/com/retrofm/android/playback/PlayerManager.kt:54-61`

Every successful metadata poll (every 30 s, even when the track has not changed) calls `PlayerManager.updateMediaItem()`, which does `setMediaItem()` + `prepare()` + `play()`. On ExoPlayer, `setMediaItem` resets the player: the current stream connection is torn down, the buffer is discarded, and the live stream is re-opened. **The user will hear a gap/rebuffer every 30 seconds while listening.** This defeats F1 (playback) in practice.

Additionally, when paused (`wasPlaying == false`) the poll still calls `setMediaItem` + `prepare`, which re-opens the network connection and buffers every 30 s while paused — wasted data/battery.

**Fix:**
1. In the service, compare the new track against the last applied one (e.g. by `EventId` or title+artist) and skip the update if unchanged.
2. Replace `setMediaItem`+`prepare` with `exoPlayer.replaceMediaItem(0, item)`. Media3's `ProgressiveMediaSource` supports in-place metadata updates (`canUpdateMediaItem`) when the URI is unchanged, so playback is **not** interrupted. Remove the `wasPlaying`/`play()` dance entirely.
3. Keep the URI identical on the rebuilt item (it already is, via `getStationItem().buildUpon()`), otherwise `replaceMediaItem` will re-prepare.

### P0-2. Broken reconnect logic: tight infinite retry loop, no backoff, ignores pause state (requirement F10)

**Location:** `app/src/main/java/com/retrofm/android/playback/PlayerManager.kt:71-93`

`onPlayerError` → `scheduleReconnect()` immediately calls `prepare()` + `play()` with **no delay**. If the network is down, this is an infinite hot loop: error → prepare → error → … draining battery and hammering the server. It also calls `play()` unconditionally, so an error while paused would force playback to start. The constants `NETWORK_RETRY_INTERVAL_MS` and `PLAYER_RECONNECT_INTERVAL_MS` in `RetroFmConfig.kt:24-25` exist but are **never used** — the "schedule" in `scheduleReconnect` is a misnomer; nothing is scheduled.

**Fix:**
1. On `onPlayerError`, retry with exponential backoff (e.g. 1 s, 2 s, 5 s, 10 s, cap at 30 s; reset counter on successful `STATE_READY`). `PlayerManager` needs a `CoroutineScope` (inject from the service) to `delay()` before `prepare()`.
2. Only auto-resume (`play()`) if the player was playing when the error occurred (`playWhenReady` was true).
3. Special-case `PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW` → `seekToDefaultPosition()` + `prepare()` immediately (standard live-stream handling).
4. Consider additionally configuring `DefaultLoadErrorHandlingPolicy` on the media source factory for transient HTTP errors, and give the live item a `LiveConfiguration`.
5. Stop retrying after N consecutive failures and surface a persistent error state to the UI instead.

### P0-3. MediaController leaks on configuration change (Activity leak + orphaned controllers)

**Location:** `app/src/main/java/com/retrofm/android/ui/MainActivity.kt:22-35` and `app/src/main/java/com/retrofm/android/ui/PlayerViewModel.kt:27-77`

`MainActivity.onCreate` builds a new `MediaController` future on **every** create (including every rotation/theme change) and passes it to `viewModel { … }`. The `viewModel {}` factory only runs once, so:
- On every config change, a fresh `MediaController` is built, connects to the session, and is **never released** (orphaned connection, leaked binding).
- The controller is built with the Activity as context (`MediaController.Builder(this, …)`), and the retained ViewModel holds it → **the first Activity instance is leaked for the ViewModel's lifetime**.
- `onCleared()` calls `mediaController?.release()`, but if the future has not completed yet, the pending future is never cancelled (`MediaController.releaseFuture` is the correct API).

**Fix:** Move controller creation into the ViewModel (or a small controller holder) using `applicationContext`, created once per ViewModel and released with `MediaController.releaseFuture(future)` in `onCleared()`. `MainActivity` should only provide the application context, e.g. via `AndroidViewModel` or a ViewModel factory taking `SessionToken(context.applicationContext, …)`.

### P0-4. Playlist API call resolves to the wrong URL (verified live)

**Location:** `app/src/main/java/com/retrofm/android/data/api/RetroFmApi.kt:10-11`, `app/src/main/java/com/retrofm/android/di/NetworkModule.kt:19`

`@GET("?StationCode=res")` is resolved against the base URL `…/api9.2/nowplaying/`, producing `…/nowplaying/?StationCode=res` — **not** the playlist endpoint `…/playlist/?StationCode=res` from REQUIREMENTS.md §3. Verified live: the coded URL returns `text/xml`, which the kotlinx-serialization JSON converter will throw on. `RetroFmConfig.PLAYLIST_URL` is defined but unused. The feature is not yet surfaced in the UI (future "recently played" view), but the repository method and its passing unit test give false confidence — the fake-API test can never catch a URL bug.

**Fix:** Use one Retrofit instance with base URL `https://listenapi.planetradio.co.uk/api9.2/` and full relative paths: `@GET("nowplaying/res")` and `@GET("playlist/")` with `@Query("StationCode") stationCode: String`. Update `RetroFmConfig` accordingly. Add a MockWebServer test that asserts the actual request paths.

---

## P1 — Functional gaps and deviations from REQUIREMENTS.md

### P1-1. Metadata polling runs forever, regardless of playback state

**Location:** `RetroFmPlaybackService.kt:70-80`

REQUIREMENTS.md §2 says poll "under uppspelning" (during playback). The current loop starts at service creation and never stops until the service dies — network + battery drain while paused/idle. Note the service is created (and the controller connects) as soon as `MainActivity` opens.

**Fix:** Gate polling on playback: start the poll loop on `onIsPlayingChanged(true)` (fetch immediately, then loop), cancel it on pause/stop. Optionally do one fetch on service start so the UI has metadata before play is pressed. Enhancement: the API returns `EventFinish` — schedule the next poll at `min(30s, EventFinish - now + 2s)` for snappier track changes.

### P1-2. Player prepares (buffers) the live stream at service creation, before the user presses play

**Location:** `PlayerManager.kt:35-40`

`prepare()` with `playWhenReady = false` at construction opens the stream and buffers as soon as the app launches. For a live radio app this is wasted data on every app open, and a stale buffer if the user presses play much later (they hear old audio).

**Fix:** Don't `prepare()` in the initializer; set the media item only. Prepare on first `play()`. Also consider: on resume-from-pause of a live stream, `seekToDefaultPosition()` (jump to live edge) instead of resuming the stale buffer — decide the desired UX (radio convention is "live edge").

### P1-3. DTO too strict for non-song events → stale "now playing" during ads/news/jingles

**Location:** `app/src/main/java/com/retrofm/android/data/model/NowPlayingResponse.kt:14,16`

`TrackTitle` and `ArtistName` are non-nullable with no default. The API's `EventType` field ("S" = song) implies other event types exist (ads, news) where these fields may be absent/empty; the response can also be empty between events. Serialization then throws, the `Result` fails silently, and the UI keeps showing the previous track indefinitely — a violation of F3's spirit.

**Fix:** Give all DTO fields defaults (`String? = null` / `""`). In the repository, treat a response with blank title, wrong `EventType`, or `EventFinish` in the past as "no track" and map to a station-branding fallback (`STATION_NAME` / `STATION_STRAPLINE` / logo). Also apply the fallback artwork in `RetroFmPlaybackService.updateMediaMetadata` — currently `setArtworkUri(null)` when the track has no image, which drops the station logo from the notification.

### P1-4. Dead/incoherent error-state plumbing; hardcoded Swedish strings in code

**Location:** `PlayerManager.kt:14-23,63-69,86-93`, `PlayerViewModel.kt:92-99`

`PlayerManager.PlaybackState` (including its `errorMessage`) is a `StateFlow` that **nothing observes** — the UI gets state via `MediaController` listeners instead. Two different hardcoded Swedish error strings live in code (`PlayerManager.kt:90`, `PlayerViewModel.kt:96`) while `R.string.playback_error` sits unused in `strings.xml`. Also, `updateFromController` rebuilds the whole `PlayerUiState` with `errorMessage = null`, racing with `onPlayerErrorChanged` (listener ordering is not guaranteed), so the snackbar can be wiped.

**Fix:** Delete `PlaybackState`/`state` from `PlayerManager` (or actually use it). Move user-facing strings to resources. In the ViewModel, update fields with `copy()` instead of reconstructing the state, and only clear `errorMessage` explicitly (on dismiss or successful playback).

### P1-5. Manifest: missing `MediaSessionService` intent action; POST_NOTIFICATIONS never requested

**Location:** `app/src/main/AndroidManifest.xml:35-43,8`

- The service intent-filter declares `androidx.media3.session.MediaLibraryService` + legacy `android.media.browse.MediaBrowserService` (good — the legacy action is what Android Auto/AAOS use). Add `androidx.media3.session.MediaSessionService` as well, per Media3 guidance, so generic Media3 controllers can discover the session.
- `POST_NOTIFICATIONS` is declared but never requested at runtime. Media-session notifications are exempt on Android 13+, so playback controls should still appear — but verify on a device; if the notification does not show, request the permission from `MainActivity` on first launch.
- The `ic_notification` drawable is only referenced by the legacy car meta-data; the Media3 notification uses the default icon. Wire it via `DefaultMediaNotificationProvider.Builder(…).setSmallIcon(R.drawable.ic_notification)` (F6 asks for station branding in the notification).

### P1-6. `onAddMediaItems` returns unplayable items for unknown IDs

**Location:** `RetroFmPlaybackService.kt:131-142`

Items arriving from controllers have their URIs stripped; returning `mediaItems` unchanged for anything that isn't `STATION_ID` yields unplayable items. Since this app has exactly one station, always resolve to the station item (also handles voice-initiated "play Retro FM" from Auto/Assistant more robustly). Consider implementing `onPlaybackResumption` (Media3) so the system's "resume media" UI works after reboot/service kill.

---

## P2 — Code quality, hygiene, build

### P2-1. Repo hygiene (do first, trivial)
- **No `.gitignore`** and nothing committed yet. Add a standard Android `.gitignore` (`.gradle/`, `build/`, `local.properties`, `*.hprof`, `.idea/`, `captures/`, `.kotlin/`).
- **`java_pid1366505.hprof` (672 MB)** sits in the repo root — a JVM heap dump from a build-time OOM. Delete it. Its existence plus `org.gradle.daemon=false` in `gradle.properties` suggests the build previously ran out of memory; if builds are slow/flaky, re-enable the daemon and keep `-Xmx4g`.
- Make an initial commit once P0 fixes land, so future changes are reviewable diffs.

### P2-2. ViewModel misses `onIsPlayingChanged`
**Location:** `PlayerViewModel.kt:79-100`. `isPlaying` also depends on playback suppression (transient audio-focus loss). Listening only to `onPlaybackStateChanged`/`onPlayWhenReadyChanged` can leave the UI showing "playing" while ducked/suppressed. Add `onIsPlayingChanged` (it can replace both existing callbacks for the play/pause icon) and `onMediaMetadataChanged` is already handled — good.

### P2-3. Build script oddities
**Location:** `app/build.gradle.kts:7-14,50-52`
- `configurations.all { force("org.jetbrains.kotlin:kotlin-stdlib:2.2.10") … }` is a workaround smell (AGP 9.2 has built-in Kotlin). Try removing it; if the build still resolves, drop it.
- `composeCompiler { enableStrongSkippingMode = true }` — strong skipping is the default since Compose compiler 2.0.20; the property is deprecated. Remove the block.
- Consider a `gradle/libs.versions.toml` version catalog — REQUIREMENTS.md emphasizes keeping the (unstable, undocumented) URLs and versions centralized and updatable.

### P2-4. Minor cleanups
- `MediaItemTree` IDs `"[rootID]"`/`"[stationID]"` are copied from the Media3 demo; use meaningful IDs (`"root"`, `"retro_fm_station"`). Cosmetic but they surface in Auto logs/debugging.
- `RetroFmApplication` contains only a debug log — either remove the class or use it for real init (e.g., a proper DI/service-locator seam replacing the `NetworkModule` singleton).
- Unused: `R.string.buffering`, `R.string.playback_error`, `RetroFmConfig.NETWORK_RETRY_INTERVAL_MS`, `PLAYER_RECONNECT_INTERVAL_MS` (will be used by P0-2), `TrackInfo.startTime/finishTime` (will be used by P1-1).
- `PlayerScreen`: snackbar "retry" calls `togglePlayPause()` — if the player still reports `isPlaying` momentarily, retry would pause. Use an explicit `retry()` that calls `prepare()+play()`.
- `proguard-rules.pro` keeps DTOs broadly; with kotlinx-serialization the generated serializers need the standard serialization keep rules instead — verify a **release** build actually parses the API (R8 + kotlinx-serialization is a classic silent-failure combo).

### P2-5. Test coverage is misleadingly green
Only `NowPlayingRepositoryTest` exists, testing a hand-rolled fake that mirrors the assumptions of the code (it could never catch P0-4). Add:
- MockWebServer tests asserting real request URLs and parsing of a captured real API response (including a non-song/malformed event → fallback behavior, P1-3).
- A `PlayerManager`/service test with a fake `Player` (or Robolectric) asserting: metadata update does **not** reset playback (P0-1), reconnect backoff behavior (P0-2).

---

## Requirements traceability (F1–F10)

| Req | Status | Notes |
|---|---|---|
| F1 Play stream | ⚠️ At risk | Works, but interrupted every 30 s by P0-1 |
| F2 Pause | ✅ | Consider live-edge resume (P1-2) |
| F3 Show title/artist | ⚠️ | Stale during ads/news (P1-3); disrupted by P0-1 |
| F4 Show artwork | ⚠️ | Falls back to nothing instead of logo (P1-3) |
| F5 Background playback | ✅ likely | Media3 foreground service; verify on device |
| F6 Media notification | ⚠️ | Default Media3 notification; custom small icon not wired (P1-5) |
| F7 Media keys | ✅ likely | MediaSession + `handleAudioBecomingNoisy`; verify |
| F8 AAOS media source | ⚠️ | Browse tree + legacy action present; fine for sideload/emulator. Play Store AAOS distribution needs a dedicated automotive flavor with `android.hardware.type.automotive` `required="true"` |
| F9 Android Auto | ✅ likely | `automotive_app_desc` + legacy browser action present; validate with Android Auto "Desktop Head Unit" (DHU) |
| F10 Reconnect on network loss | ❌ | Implementation is a no-backoff hot loop that also overrides pause (P0-2) |

Non-functional: startup-within-2 s is helped by eager `prepare()` but at a data cost (P1-2 proposes lazy prepare — measure before/after). Accessibility: basic contentDescriptions exist; run TalkBack once. Security: HTTPS-only, no cleartext — fine.

---

## Suggested enhancements (post-fix, optional)

1. **ICY in-stream metadata instead of (or alongside) polling.** ExoPlayer requests `Icy-MetaData: 1` on progressive streams and surfaces `StreamTitle` via `onMediaMetadataChanged`. If the sharp-stream Icecast server sends ICY metadata, track changes arrive exactly on time with zero polling. Evaluate first (check response headers for `icy-metaint`); keep the API poll for artwork either way.
2. **Poll scheduling by `EventFinish`** (see P1-1) for near-instant track updates.
3. **AAOS product flavor** (`automotive` dimension) for eventual Play distribution.
4. **"Recently played" view** using the (fixed) playlist endpoint — already anticipated in REQUIREMENTS.md §3.
5. **AAC stream fallback** — REQUIREMENTS.md lists the AAC+ URL; use it as automatic fallback if the MP3 URL starts failing (their URLs are undocumented and may change; RetroFmConfig centralization is already in place).
6. README overclaims ("Återansluter automatiskt vid nätverksavbrott") — keep README in sync with actual behavior after P0-2.

## Suggested execution order for Sonnet 5

1. P2-1 (hygiene: `.gitignore`, delete hprof, initial commit) — get a clean baseline.
2. P0-1 → P0-2 → P0-3 → P0-4 (each with a test where feasible).
3. P1-1 … P1-6.
4. Run on phone + Android Automotive emulator + DHU; walk the F1–F10 table above as a manual test protocol.
5. P2-2 … P2-5, then enhancements as desired.

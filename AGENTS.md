# Retro FM — agent instructions

Personal, unofficial Android app for the Retro FM live radio stream. Three Gradle modules:
`:core` (shared Media3 player/session, ExoPlayer, cast, ICY/now-playing, log sink), `:app`
(phone + Android Auto, Compose UI), `:automotive` (Android Automotive OS / Volvo). Code
namespace `com.retrofm.android`; `applicationId` `com.magter.retrofm` (permanent, shared by
`:app` and `:automotive`).

`CLAUDE.md` only imports this file. Knowledge that serves one kind of task is a project skill
under `.claude/skills/` (`.agents/skills` is a symlink to it, for clients that read that path);
what is here binds every task.

## Project skills

| Skill | Load it when |
|---|---|
| `now-playing-metadata` | changing or debugging the track title, artist or cover: the stream and its ICY `StreamTitle`, iTunes artwork scoring, the `retrofm.se` page fallback, end-of-track and freeze timing, and the station probes already run so nobody re-runs them |
| `field-logs` | reading the car's or phone's field logs and metrics, or changing a log line, a metric or anything under `core/.../telemetry/` |

Until 2026-11-01 both were sections of `CLAUDE.md`, which loaded on every turn (`git show
dce7c09:CLAUDE.md`, "Now-playing metadata" and "Field logs").

## Build

Local builds need a JDK 17 and the Android SDK on the environment (nothing is on the default
PATH on the dev host):

```bash
export JAVA_HOME=~/.local/jdk/jdk-17.0.19+10   # dev host location
export ANDROID_HOME=~/android-sdk
./gradlew :core:testDebugUnitTest              # verification — run before and after a change
./gradlew :app:bundleRelease :automotive:bundleRelease
```

- **`:core:testDebugUnitTest` is the whole test suite** and it is JVM-only. It runs in CI on every
  PR and on main since 2026-11-06 (`.github/workflows/ci.yml`); `release.yml` builds bundles
  and nothing else, so a tag proves no tests by itself. Gradle marks it `UP-TO-DATE` and skips it when nothing
  changed; pass `--rerun` when you need the run itself as evidence.
- It covers the pure logic only: artwork ranking, StreamTitle parsing, the browse tree, the
  artwork host allowlist, the freeze clock — plus the telemetry client end to end against a fake
  edge (MockWebServer), which is the one place it exercises a real socket. `RetroFmPlaybackService` has **no** test harness, so
  anything that matters inside it belongs in an extracted class (that is why `TrackPlayingClock`
  exists) or it is untested.

- The Gradle heap is capped at `-Xmx2g` in `gradle.properties` — **do not raise it**; a 4g heap
  OOM-killed the whole session on this 5.8 GB host (swap has since been added, but keep the cap).
- Release R8 builds take a few minutes; prefer running them in the background so a long build
  can't stall the session.
- Release signing is driven by Gradle properties (`RETROFM_UPLOAD_*`, from `~/.gradle/gradle.properties`
  or `-P`). Absent them, the release bundle is produced **unsigned** — never generate a keystore
  or commit secrets.
- Telemetry is driven the same way (`RETROFM_TELEMETRY_URL`, `_KEY`, `_CF_ID`, `_CF_SECRET`; see
  the `field-logs` skill). When set in `~/.gradle/gradle.properties`, **every** local build — debug and
  release — talks to the edge they name; absent, it has telemetry off and says so once, in
  logcat (`Telemetry: telemetry off — …`). Check by name, never by value: `grep -oE '^RETROFM_TELEMETRY_[A-Z_]+'
  ~/.gradle/gradle.properties`.
- New OpenTelemetry classes can need R8 rules that only a release build reveals; they live in
  `core/consumer-rules.pro`, which both apps inherit.

## Release (do not hand-upload)

Releases go out through GitHub Actions, not manual Play Console uploads:

- Push a tag `git tag vX.Y.Z && git push origin vX.Y.Z` (or run the "Release to Play internal
  testing" workflow manually). It builds + signs both bundles and pushes them to Play **internal
  testing**: phone → `internal`, automotive → `automotive:internal` (two separate steps — the
  standard `internal` track rejects the automotive form factor, so they cannot share a release).
- **versionCode** is auto-derived from the run number (phone `100 + run`, automotive `1100 + run`);
  never bump it by hand. **versionName** is the literal in `app/` and `automotive/build.gradle.kts`
  — bump it per release (keep both modules in sync) and make the tag match.
- versionCode ranges are load-bearing: phone stays < 1000, automotive 1000+, so the car always
  prefers the automotive artifact.
- `.github/workflows/list-tracks.yml` is a diagnostic that prints the app's real Play track names.
- **Play "Automatic integrity protection" must stay OFF** (Play Console → App integrity). When on,
  Play injects a pairip licensing stub into the served APK; at car boot it can't reach the
  not-yet-started Play Store, shows a repeating "check that Google Play is enabled" dialog and
  kills the process — CarMediaService respawns the last media source, looping the dialog for
  minutes (root-caused 2026-07-25, fixed by disabling the toggle + shipping 1.0.34). It is a
  console-side toggle with no trace in this repo, and Play may enable it by default in release
  flows — check it if the boot dialog ever returns.

## Conventions & gotchas

- **Automotive artwork must be `content://`.** AAOS ignores remote `https` artwork URIs and
  embedded bitmaps — it renders only local URIs. All art routes through `AlbumArtContentProvider`
  (`:core`), which proxies+caches the remote image behind a `content://` URI. Never set a raw
  `https` `artworkUri`/`artworkData` for the car.
- **An in-place update can leave CarMediaService bound to the dead process, and only an
  infotainment restart clears it.** Symptom (2026-08-13, 1.0.54): blank screen, no audio, but
  the app is demonstrably alive — it logs its process start, answers `onGetLibraryRoot` from
  both `com.android.car.media` and `com.volvocars.launcher`, and keeps shipping network events
  for hours. The tell is that **`onGetChildren` is never called** and no playback is ever
  requested (`prepare gated — playback not requested yet` forever), where every healthy cold
  start goes root → `onGetChildren(stations) -> 1 children` → `playWhenReady=true` within
  ~20 s. Reading it as an app-side regression is the trap: the app's answers are correct, but
  a dead binding is asking.
  - It happens when Play installs the update while the media source is *active* — the old
    process is killed mid-session and the system service keeps the stale binding.
  - **Uninstalling and reinstalling does not fix it.** The bad state lives in a different
    process, not in the app; the app's uid changing across starts is proof the reinstall
    happened while the symptom persisted. Restart the infotainment system.
  - So: before bisecting or reverting a release on a "the car won't start" report, get a cold
    boot. A whole investigation went into a diff that could not reach the failing path — the
    changed code had not executed even once, since no track boundary ever arrived.
- **Cast needs the exact opposite, and one MediaItem serves both.** A Cast receiver is a
  different device on the network: it cannot resolve `content://com.magter.retrofm.artwork/…`,
  because that provider only exists inside this process. Until 2026-08-22 every LOAD payload
  carried one anyway, so the receiver never showed a cover — the Automotive rule above applied
  one step too early, to the shared item rather than to the surface that needs it.
  - The seam is `RetroFmMediaItemConverter.forReceiver`, which maps the URI back with
    `AlbumArtContentProvider.remoteUriOf` (the inverse of `mapUri`, and the same decoder
    `describe` uses). **Receiver-specific translation belongs in that converter** — it is
    already where LIVE stream type and `contentId` are fixed — never in `buildStationItem` and
    never as a route check in the service, which would spread Cast knowledge across surfaces
    that do not care.
  - **And it has to be undone on the way back in, which was missed until 2026-08-23.**
    Media3's `DefaultMediaItemConverter.toMediaItem` rebuilds the MediaMetadata from the Cast
    metadata — `setArtworkUri(metadata.images[0].url)` — and `CastTimelineTracker` is what
    calls it, so whatever the receiver was sent becomes the session's current MediaItem. The
    https URL we deliberately put in the payload came straight back into the shared item. The
    tell in the field log is the artwork line changing shape: `loadBitmap
    https://media.bauerradio.com/image/upload/…` right after a transfer (2026-08-23 11:24:37)
    where every local-route load reads `loadBitmap media.bauerradio.com/dwxxo0kehcboelrutfnm.png`
    — `describe` only shortens our own `content://` URIs, so a long line *is* the symptom.
    `forSession` maps it back, and `RetroFmMediaItemConverterTest` pins the round trip as the
    identity.
    - It rendered fine on the phone, which is why it survived. It is still the Automotive rule
      broken from the other side — a raw `https` `artworkUri` in the shared item, one
      round-trip away from a surface that renders local URIs only — and it put a
      ~110-character URL in a log line that is otherwise ~40.
    - **Only allowlisted hosts are mapped back.** The Cast queue can hold items this app never
      built; wrapping an arbitrary URL would hand the session a `content://` that `openFile`
      then refuses, turning a cover that would have rendered into nothing. The rule lives once,
      in `AlbumArtContentProvider.servesHost` — `AlbumArtHostAllowlistTest` used to keep its
      own copy of the allowlist and now calls that instead.
  - **Device type is deliberately not consulted.** Cast's only hard rule for audio-only
    devices (Home Mini) is "do not send a *video* stream"; metadata images are simply ignored
    by a device with no screen, and the "avoid image assets" advice in Google's audio guide is
    addressed to *receiver app* authors. So one correct https URL is right for a Nest Hub and a
    speaker alike. The one place device type genuinely matters is **volume**: on an audio
    device the sender controls the full device range and should use smaller increments. Not
    implemented.
  - **We announce the wrong `contentType` to the receiver and it is knowingly left that way.**
    The mount serves `audio/aacp` (raw ADTS HE-AAC, verified from its live headers 2026-08-22);
    we send `audio/mpeg`. Harmless locally — ExoPlayer sniffs — but the Default Media Receiver
    picks its pipeline from that field. It was first suspected of causing the slow flapping
    start; **that was wrong and the measurement says so.** Fixing the artwork URI alone took the
    same flow — playing locally, then transferring, with the live-edge nudge firing in both
    cases — from **38 s to 3 s**, with one clean LOAD instead of two. The receiver had been
    failing on an image it could not fetch, landing in IDLE, and the nudge's `prepare()` then
    re-loaded it with the wrong item. So the wrong contentType costs nothing yet measured. It is
    still not a one-line fix:
    Cast's supported-media list documents HE-AAC only as `audio/mp4; codecs="mp4a.40.5"`, an
    MP4 container this stream does not have, and does not mention `audio/aacp` or raw ADTS at
    all — so the accurate value may simply be refused while the wrong one demonstrably plays.
    `RetroFmConfig.CAST_CONTENT_TYPE` is the knob and carries the three candidates; change it,
    cast once, and measure the gap between `cast transfer: LOCAL -> REMOTE` and `isPlaying=true`.
    - **Set to `audio/aac` in 1.0.69 (2026-10-05) as a trial, and the trial cleared it**: the
      receiver accepted the type and stalled identically (one transfer, same timings to the
      tenth of a second). It is kept because it is accurate. The cause was the handed-over
      position — see "A transfer must not carry the local position" below. The trial's
      motivation, kept as the record: With `audio/mpeg`, 3 of 3 transfers to a Nest Hub on 1.0.68
      went READY + `isPlaying=true` ~1.6 s after the LOAD, back to BUFFERING 0.5 s later, and
      stayed silent until the watchdog's 45 s re-load — which then played within 0.7 s. No
      `cast receiver error`, and the first hop answered 302 on every probe that evening. That
      the wrong type explains it is a **hypothesis**: the next cast's field log settles it. If
      the stall survives unchanged, the type is cleared and the receiver's own CDN requests
      (bot protection / admission — the operator's earlier theory) are what is left.
  - **A transfer must not carry the local position — this is what made Cast slow after the
    move to Revma (fixed 1.0.70, 2026-10-05).** Media3's `TransferCallback.DEFAULT` is
    `PlayerTransferState.fromPlayer(from).setToPlayer(to)`, i.e. `setMediaItems(items, index,
    from.currentPosition)` (read from the 1.10.1 bytecode), so the receiver was LOADed at
    "however long the phone had been playing" on an unseekable live stream. On 1.0.68/69 every
    transfer to a Nest Hub went READY ~1.6 s after the LOAD, BUFFERING 0.5 s later, silent until
    the 45 s watchdog re-load, which then played in 0.7 s (4 of 4 stalled at 45 s, 3 of them logged at DEBUG, 2026-10-05).
    - It was always so; **`nudgeCastToLiveEdge` hid it.** That 2 s seek-to-live-edge existed for
      exactly this stall and is why casting used to start in ~3 s (the 2026-08-22 measurement
      above has it "firing in both cases"). It samples `isPlaying` **once**, and on Revma the
      receiver's 0.5 s READY window straddled the 2 s mark in 3 of 3 transfers — so it never
      fired. A one-shot check of a flapping state is not a check.
    - The transfer callback now crosses TO the receiver with `C.TIME_UNSET` (live edge) and logs
      `cast transfer: dropping local position N ms`. **Confirmed on 1.0.70 the same evening:**
      no `cast receiver silent` in 4 transfers. It removed the 45 s hang, **not the stall**: an
      autoplay LOAD still went READY at +1.7 s, BUFFERING 0.5 s later, and the receiver found
      its own way out at ~+7.5 s (2 of 2). Casting first and pressing play later was instant —
      the receiver LOADs paused, buffers, and the resume-from-pause live-edge seek played in
      0.5–0.6 s (2 of 2). That asymmetry is what the operator noticed.
    - **So since 1.0.71 the nudge watches instead of sampling** (`CastLiveEdgeNudge`, pinned by
      its test against the 23:22 field timings): every 250 ms from +2 s to +10 s, the first
      not-playing observation seeks to the live edge, once per transfer. **Confirmed on 1.0.71,
      2026-10-05 (2 of 2):** the nudge fired at +2.5 and +2.0 s, READY 0.68 and 0.58 s later,
      audio at **+3.2 and +2.6 s** from `LOCAL -> REMOTE` against 7.5–7.7 s on 1.0.70. So a seek
      *during* the stall is as fast as one from a paused, pre-buffered receiver — that was the
      open question. The cast-first path stayed instant and the nudge correctly did nothing
      there. n=2 on one Nest Hub; a speaker-only receiver is unmeasured.
  - **Latent and unfixed: `nudgeCastToLiveEdge` can re-LOAD the receiver with the wrong item.**
    When it fires against a receiver sitting in `STATE_IDLE`, `play()` goes through
    `PlayGatedPlayer`'s IDLE branch into `prepare()`, and the resulting second LOAD carried the
    *station branding* rather than the current track (2026-08-21: LOAD #1 "If You Don't Know Me
    By Now", LOAD #2 "Retro FM" four seconds later — the picture visibly jumping). Since the
    receiver cannot be sent new metadata afterwards, whatever that last LOAD carried is what it
    shows for the whole session. The trigger became rare once the artwork URI was fixed, so it
    was left alone; the cause was never established, and the plausible one — that the re-load
    reads the CastPlayer wrapper's own timeline, which still holds the item set at construction
    rather than the one `replaceMediaItem` gave the active player — is a **hypothesis, not a
    finding.** It also seeks a stream that has no seekable range, which Cast's live guidance
    (seek to `LiveSeekableRange.end`) does not cover.
- **Cast is off in the car.** `PlayerManager` never builds a `CastPlayer` on `FEATURE_AUTOMOTIVE`,
  and `:automotive` excludes the whole `com.google.android.gms` + `com.google.android.datatransport`
  dependency (their startup components trigger a "needs Google Play services" error on head units).
- **The connect-time announcement is the only one until the track ends — never drop it.**
  `onMetadata` ignores ICY while `playWhenReady` is false, which is right for the tail a paused
  player keeps buffering, but the comment justifying it ("resume re-syncs at the live edge within
  seconds") only holds while the stream is *already open*. Opened fresh a few seconds before the
  play press, the announcement that was dropped was the whole song's only description: on
  2026-08-22 the display sat on the station logo through all of "Material Girl" and flashed the
  title for two seconds at the end, when the end-of-track marker finally arrived two minutes
  later. The log tells the two apart — `icy held — playback not requested` followed by
  `replaying held icy frame` is the healthy shape.
  - `PendingIcyFrame` holds it for `ICY_HELD_MAX_AGE_MS` (30 s). **The expiry is load-bearing**:
    resuming seeks to the live edge, so an older frame describes audio the player has already
    skipped past, and replaying it would put a confidently wrong title on screen — worse than
    branding. `PendingIcyFrameTest` pins both halves.
- **Cast shows the station logo and "ExoPlayer Default Receiver", and that is a decision, not a
  bug (2026-08-22).** Track info never updates on the receiver: `applyTrackMetadata` skips the
  remote route (CAST-PLAN §2.4) *and* the Default Media Receiver genuinely cannot update
  now-playing without a queue reload, which on a live stream would rebuffer every few minutes.
  The name comes from `androidx.media3.cast.DefaultCastOptionsProvider`, which registers Media3's
  own receiver app id **`A12D4273`** — we are borrowing someone else's registered app, and the
  name shown is that registration's.
  - **Rejected:** registering our own receiver. A Styled Media Receiver (5 USD one-time, Google
    hosted) would fix only the name; live track info needs a Custom Web Receiver, i.e. our own
    HTML/JS on a publicly reachable HTTPS host — a new service to run and secure. The maintainer
    declined both on 2026-08-22: not worth paying for and not worth another deployment surface
    for a private build. **The station logo on the receiver is therefore permanent by choice.**
    Re-open only if that trade changes; the mechanism is understood, so no investigation is
    needed first.
- **Ads have been absent since the CDN move, and the ad code is now unexercised.** Zero ad
  markers across every device in the 14 days to 2026-08-22, and none in the car in the five days
  to 2026-08-27 either (`--grep "adw_ad|Reklam|ad state"` over the field logs). `IcyAdMarker`,
  `MUTE_ADS` and the ad-state machinery still run but nothing
  in the field has tested them against Mad Men Media's stream — the format that made them work
  was Bauer's. Expect to redo that implementation when the station starts running ads again, and
  do not read "ad muting works" from the absence of complaints.
- **Ad muting is a private-circle decision, internal-only.** `RetroFmConfig.MUTE_ADS` mutes the
  broadcaster's spliced ads — acceptable for a personal internal-testing build, but it must NOT
  ship to a public/production track without resolving Retro FM/Bauer licensing (restreaming their
  station publicly is a licensing matter regardless of the mute).
- **Fetching the station's own page is a per-device dependency, accepted deliberately
  (2026-08-20).** `StationNowPlaying` fetches `retrofm.se` from each install, one to three times
  per track change — ~20–40 requests/hour on one device. Accepted by the maintainer on the
  grounds that this is a private build with a single user, so the app makes no more requests
  than any other client of a public, unauthenticated page. **That reasoning does not survive a
  wider audience.** If this ever goes beyond the private circle, it needs a single shared relay
  fanning out to clients, and a courtesy note to Mad Men Media — the same objection that ruled
  out the Blazor circuit (`now-playing-metadata` skill, "retrofm.se: dead ends"). It sits under the same licensing caveat as ad muting, above.
- **Log hygiene is a wire contract.** Field logs leave the device via OpenTelemetry (Timber +
  `TelemetryTree`, so every Timber call site is on the wire); never log tokens, credentialed
  URLs, or PII. Reviewed across all call sites 2026-10-04.
- Live stream: reconnect retries indefinitely while playback is wanted and recovers on *validated*
  internet (`NET_CAPABILITY_VALIDATED`), reopening at the live edge — no stale buffer, no hard
  give-up. Don't reintroduce a fixed reconnect cap.
- **That recovery is driven by `onPlayerError`, and a stopped Cast receiver never raises one.**
  It changes state instead, so for a long time nothing retried it: two field sessions ended in
  permanent silence with the player left `playWhenReady=true` in `STATE_IDLE` for *hours* while
  the phone had validated internet (2026-08-21 22:19, receiver stalling 48 min into a session;
  2026-08-22 07:50, a one-second `network lost`/`available` flap tearing the session down, the
  receiver then flapping READY/IDLE three times and giving up). `retryNowIfRecovering` ran on
  every `validated=true` callback throughout and no-oped each time, because it requires
  `playerError != null`. **A state-only failure is invisible to an error-driven recovery** —
  check that assumption before trusting any "it retries forever" claim.
  - `CastStallWatchdog` covers it: at `CAST_STALL_RECOVER_MS` (45 s) re-load the stream onto the
    receiver, at `CAST_STALL_HANDBACK_MS` (90 s) end the Cast session so the phone takes over
    (`CAST_HANDBACK_ENABLED` kills the audible half). It is **remote-only on purpose** — see the
    measurement in that constant's KDoc: over 14 days all 23 local stalls recovered by
    themselves, the longest after 656 s, so a watchdog there could only cut short a recovery
    that works.
  - **The arming discipline is the load-bearing part, not the arithmetic.** The stall clock is
    set by the *first* stalled observation and never restarted while the stall continues; the
    2026-08-22 receiver flapped three times in two seconds, and a timer reset per transition
    would never fire. `CastStallWatchdogTest` pins exactly that.
  - **It works: first confirmed field save 2026-08-23 11:27:31**, `cast receiver silent 139 s
    — re-loading the stream` followed by `READY` + `isPlaying=true` one second later. The
    hand-back half has still never fired.
  - **But the poll is not punctual, and the escalation used to assume it was.** That save came
    at **139 s**, not 45: `CAST_STALL_POLL_MS` is a coroutine `delay`, and while casting the
    phone plays nothing itself and holds no wake lock, so nothing keeps the CPU awake to
    service it. Both clocks agree on the number — the log timestamp and the watchdog's own
    `stalledMs` are the same device wall clock — so the loop genuinely did not tick for ~94 s.
    Corroborating but not proof: a `Network capabilities` callback fires in the *same second*
    after being equally silent, which looks like one wake-up releasing both.
    - So **`CAST_STALL_RECOVER_MS` is a floor on how long a stall must last, never a bound on
      when the watchdog is asked.** Anything timed off the stall start inherits that.
    - `CAST_STALL_HANDBACK_MS` was measured from the stall start, so at 139 s the hand-back was
      already overdue and the *next* tick would have ended the Cast session — one second of
      grace instead of forty-five. It only escaped because that re-load worked. **The
      hand-back is now timed from the re-load** (grace = the difference between the two
      constants, so a punctual escalation is bit-for-bit unchanged). Fixed 2026-08-23.
    - Not attempted: making the poll itself punctual. That means an AlarmManager or a wake
      lock held while the phone is only a remote control, which costs battery on every cast
      session to buy precision on a path that already recovers. Re-open only if a hand-back is
      ever needed and arrives far too late to help.
  - **Still to evaluate.** The 45 s threshold's upper bound rests on a *single* sample — one
    29.6 s hand-over at app start, against 32 stalls of ≤3.5 s. A 4 h 19 min cast session on
    2026-08-23 added five more self-recovering stalls, **all ≤1 s**, so the empty band
    3.5–29.6 s is still unpopulated and the upper bound is still n=1. Keep checking the field
    logs for `cast receiver silent` and `handing playback back`, and whether the hand-back is
    welcome or startling in practice. The episodes are `isPlaying=false` while
    `playWhenReady=true` with `route=REMOTE`.
  - **The receiver is now named on the outbound transfer line** (`receiver=Google Nest Hub`),
    fixed 2026-09-21 — this note used to say the logs never named it and that it was worth
    adding the next time the area was touched. It is the **model**, never `friendlyName`: the
    friendly name is user-chosen and routinely carries a person's name or a room, which the log
    hygiene rule keeps off the wire, and the model is what the original question ("is this a
    Nest Hub or a speaker?") actually wanted. Only on the way out: coming back the session is
    already gone, so the answer would be `unknown` every time.
  - **A Cast session that dies inside 90 s can be the stream, not the app — measured
    2026-09-21.** Casting broke completely right after the 2026-09-14 move to Revma, and the
    diff was the wrong place to look: no Cast file had changed in ten days (`git log --since=…
    -- '*Cast*'` was empty) and the only behaviour change was `STREAM_URL`. The first hop,
    `stream.rcs.revma.com`, was refusing almost everything: **43 of 45 requests answered a bare
    HTTP 503** (no headers, no body) from the dev host over 23 minutes, the last 24 consecutively.
    Once admitted, the edge node is healthy — one session delivered 1.14 MB in 90 s at a steady
    ~96 kbps, `content-type: audio/aac`, ICY intact.
    - **The asymmetry is what turns an upstream wobble into "casting is dead".** Local playback
      retries indefinitely (`RECONNECT_BACKOFF_MS`, no cap — eleven attempts logged in twelve
      minutes that morning, and it came back). The Cast path gets **exactly two**: `RELOAD` at
      `CAST_STALL_RECOVER_MS`, `HAND_BACK` at `CAST_STALL_HANDBACK_MS`, then the session is
      ended and the user must cast again by hand. At a ~4 % admission rate the phone always gets
      in eventually and the receiver essentially never does. Giving the watchdog more attempts
      before the hand-back is the obvious lever and is **not** implemented — the hand-back exists
      to get audio off a dead receiver, so lengthening it trades one failure mode for the other
      and needs a decision, not a tweak.
    - The shapes either side of the move, from the field logs: sessions of 35 min, 15 min,
      36 min, ~3 h and 69 min on 2026-08-22→29, against 86 s, 89 s and 13 s on 2026-09-20. The
      line `cast session ended without us asking` appears nowhere in August.
    - **What the log now carries so the next one needs no CDN probe** (added 2026-09-21):
      `cast receiver error <type>/<reason> code=N`, straight from the receiver's own
      `onMediaError` — at WARN on purpose, since the sink's level resets to WARN on every
      redeploy and a diagnostic that only exists at DEBUG is absent when it is needed — and the
      receiver's state on both escalation lines, which separates "it tried and failed"
      (`playerState=IDLE idleReason=ERROR`) from "it is still trying" (`playerState=BUFFERING`).
      `CastReceiverStatus` renders them and is pure so `:core`'s suite can reach it;
      `CastReceiverProbe` holds the gms half and is constructed **only** on the Cast path,
      because `:automotive` strips that group.
    - **Ruled out as the cause of the 503 episode, so nobody fixes them blind:**
      `CAST_CONTENT_TYPE` (`audio/mpeg` was equally wrong all through the working period, and
      the receiver rarely got far enough to see the bytes — but that period was HE-AAC on Mad
      Men's mount; on Revma's AAC-LC the receiver *does* reach the bytes and then stalls, which
      is why the type is on trial again, see the contentType note above); CORS (`Access-Control-Allow-Origin: *` on both hops); and the
      `rj-ttl=5` token (the same edge URL still answered 200 when re-used after 95 s).
    - **Not our probing.** The phone logged `http=503` at 07:00:10, sixteen minutes before the
      first dev-host request, and the car had hit the same status on 2026-09-16. Whether the 503
      is capacity, a listener cap or a mount being wound down is **unknown** and not answerable
      from here — it is station-side, and worth a note to the station if it persists.
  - Media3's `CastPlayer` has **no veto on the local→remote transfer**: `setTransferCallback`
    hands you `transferState(from, to)` *while* the switch happens. So "don't switch back to a
    receiver that isn't ready" is not implementable, and the hand-back above is what actually
    gets the audio back. It is **not** logging-only, which this note used to say: it cannot
    refuse a transfer but it can decide what state crosses, which is where the rule below lives.
  - **Losing the Cast session does not start the audio on the phone** (`CAST_RESUME_LOCALLY_ON
    _SESSION_LOSS`, false since 1.0.62). The receiver fetches the stream itself, so when the
    *phone* walks out of Wi-Fi range only the control link dies — the speakers carry on.
    `TransferCallback.DEFAULT` copies `playWhenReady` across regardless, so the phone joined in
    on top of them: audio in a pocket, over music already playing in the room, from a device the
    user had deliberately handed playback away from (2026-08-29 18:35:55, `cast transfer:
    REMOTE -> LOCAL (state=3 playWhenReady=true)` one second after `network lost`). Starting a
    cast is a statement about *where* the sound belongs and losing Wi-Fi does not retract it.
    - **The watchdog's hand-back is exempt and must stay exempt.** `CAST_STALL_HANDBACK_MS`
      exists to get audio back off a dead receiver, so that path claims the hand-over before
      ending the session and plays. `CastHandOverPolicy` owns the claim; the rule worth pinning
      is its **lifetime**, not the boolean — a claim that leaks into the next hand-over
      un-silences exactly the case this exists for, and one consumed early mutes the rescue.
      Both directions are in `CastHandOverPolicyTest`, including the abandoned claim when
      `endCurrentSession` throws.
    - **Accepted consequence:** stopping the cast from the system UI now pauses too, where it
      used to continue on the phone. The two are not distinguishable without registering a Cast
      `SessionManagerListener` and trusting its suspend reason — more surface for a guess, and
      the failure modes are not symmetric: unwanted silence costs one tap, unwanted audio costs
      whatever it interrupts. Flip the constant to restore Media3's default for both.

# Retro FM — agent instructions

Personal, unofficial Android app for the Retro FM live radio stream. Three Gradle modules:
`:core` (shared Media3 player/session, ExoPlayer, cast, ICY/now-playing, log sink), `:app`
(phone + Android Auto, Compose UI), `:automotive` (Android Automotive OS / Volvo). Code
namespace `com.retrofm.android`; `applicationId` `com.magter.retrofm` (permanent, shared by
`:app` and `:automotive`).

## Build

Local builds need a JDK 17 and the Android SDK on the environment (nothing is on the default
PATH on the dev host):

```bash
export JAVA_HOME=~/.local/jdk/jdk-17.0.19+10   # dev host location
export ANDROID_HOME=~/android-sdk
./gradlew :core:testDebugUnitTest              # verification — run before and after a change
./gradlew :app:bundleRelease :automotive:bundleRelease
```

- **`:core:testDebugUnitTest` is the whole test suite** and it is JVM-only. It does not run in
  CI — `.github/workflows/release.yml` builds bundles and nothing else — so a green suite is
  only as good as the last local run. Gradle marks it `UP-TO-DATE` and skips it when nothing
  changed; pass `--rerun` when you need the run itself as evidence.
- It covers the pure logic only: artwork ranking, StreamTitle parsing, the browse tree, the
  artwork host allowlist, the freeze clock. `RetroFmPlaybackService` has **no** test harness, so
  anything that matters inside it belongs in an extracted class (that is why `TrackPlayingClock`
  exists) or it is untested.

- The Gradle heap is capped at `-Xmx2g` in `gradle.properties` — **do not raise it**; a 4g heap
  OOM-killed the whole session on this 5.8 GB host (swap has since been added, but keep the cap).
- Release R8 builds take a few minutes; prefer running them in the background so a long build
  can't stall the session.
- Release signing is driven by Gradle properties (`RETROFM_UPLOAD_*`, from `~/.gradle/gradle.properties`
  or `-P`). Absent them, the release bundle is produced **unsigned** — never generate a keystore
  or commit secrets.

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
  out the Blazor circuit. It sits under the same licensing caveat as ad muting, above.
- **Log hygiene is a wire contract.** Field logs leave the device via the remote sink (Timber +
  LogsinkTree); never log tokens, credentialed URLs, or PII.
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
    - **Ruled out as the cause, so nobody fixes them blind:** `CAST_CONTENT_TYPE` (`audio/mpeg`
      was equally wrong all through the working period, and the receiver rarely gets far enough
      to see the bytes); CORS (`Access-Control-Allow-Origin: *` on both hops); and the
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

## Now-playing metadata: Bauer is dead, the station moved

Retro FM **left Bauer/RadioPlay**, and the app followed it to the station's own Icecast on
2026-08-08. Read the next section for what to build on; this one exists only so nobody writes
code waiting for the old platform to recover. It will not.

Verified 2026-08-08: `radioplay.se/retrofm` is a **404** and RadioPlay SE now carries only Mix
Megapol, NRJ, Nostalgi and Rockklassiker; `listenapi.planetradio.co.uk/api9.2/nowplaying/res`
answers `[]`; the playlist endpoint froze **2026-07-09**; `stations/…` returns 44 stations, all
UK. `brand/SE_RETROFM` still returns a record, but it is a leftover row pointing at that 404 —
not evidence the station is still there.

The old Bauer mounts still serve audio and **must not be used**: `retrofm_mp3`'s ICY froze
2026-07-31 (330 s of stream, one metadata block, same song forever — the "always Talk Talk"
the app showed for a week), and `retrofm_aacp` sends an empty `StreamTitle`. Switching between
them never bought back metadata.

The web players work because they never used Bauer: `retrofm.se` runs Caster (Blazor Server),
`radio-sveriges.se` is myTuner with its own HMAC-signed API — signed for their app, **not ours
to call**.

### The station moved to a new CDN — that is the answer (found 2026-08-08, superseded 2026-09-14)

**2026-09-14 the station moved again, to Revma (RCS).** What `retrofm.se` itself plays, read live
from the page's player in headless Chromium 2026-09-16:

```
https://stream.rcs.revma.com/25knctp5vepwv     96 kbps AAC-LC, 48 kHz stereo (audio/aac), icy-metaint 16000
```

The URL 302-redirects to an edge node with a short-lived token (`rj-ttl=5`); ExoPlayer follows
it, re-sending the request headers on the hop, so ICY survives the redirect. The edge hostname
varies per request (`n02-eu`, `n13-eu` minutes apart), so opening the stream costs two
DNS+TCP+TLS handshakes to two hosts — worth remembering against the car's 5 s
`STREAM_CONNECT_TIMEOUT_MS` on a cold modem. ICY metadata is intact — connect-time `StreamTitle`
matched the site's now-playing page on verification day.

**The bitrate is 96 kbps, not the ~128 this note first claimed** (measured 2026-09-17 by parsing
the ADTS frame headers: 590 contiguous frames, AAC-LC, 48 kHz, 96.1 kbps CBR). The first figure
was wire bytes over wall clock and it measured the **connect burst** — the same capture delivered
12.6 s of audio in 7.9 s. Measure bytes per *audio* second, never per wall-clock second. So the
move off Mad Men Media was not a bitrate change at all: 96 kbps both times, HE-AAC (AAC+) →
AAC-LC. One capture from one edge node, so re-measure rather than trust it if it ever matters:
`curl -sL --max-time 20 <url> -o s.aac`, then walk the ADTS frames (syncword `0xFFF`, 13-bit
length at bits 30–42, 1024 samples per frame) and divide total frame bytes by frames×1024/48000.

The Mad Men Icecast below was retired the same day: `/retro` went 404 and every
mount except Relax FM was renamed `<mount>_old` (~20:00 UTC). `/retro_old` still carried the
programme in sync with the site two days later, but the name says it can vanish any day — do not
build on it. Revma's ad insertion is unobserved as of 2026-09-16; `MUTE_ADS` may be a no-op there.

The rest of this section is kept as the record of the 2026-08-08 move off Bauer.

**We were listening to the wrong server.** `live-bauerse-fm.sharp-stream.com/retrofm_mp3` is a
legacy Bauer relay. The station's real stream — the one `retrofm.se` itself plays — is a plain
**Icecast 2.4.4** server:

```
https://stream.madmenmedia.se/retro            96 kbps AAC+ (audio/aacp), icy-metaint 16000
https://stream.madmenmedia.se/status-json.xsl  standard Icecast JSON, live "title" per mount
https://stream.madmenmedia.se/retro.xspf       same data as XSPF
```

The ICY metadata is **live and in the stream we would play** — no API, no polling, no third-party
dependency, nothing to ask permission for. Found by running the real page in headless Chromium
(Playwright) and watching what it connected to; the stream URL is only assigned when playback
starts, so it never appears in the served HTML.

Notes before switching:
- **Bitrate is a downgrade**: 96 kbps AAC+ vs the 192 kbps MP3 we take from the stale Bauer relay.
  Every *other* station on this Icecast has a 192 kbps `<mount>_high` sibling — `retro_high` is
  404, so for Retro FM 96 kbps AAC+ is the only mount. Re-check occasionally; if `retro_high`
  appears, prefer it.
- `icy-name` is "Retro FM Sweden Online" here vs "Retro FM Skane" on the Bauer relay. Confirm the
  two carry the same programme before assuming the switch is transparent.
- Icecast 2.4.4's `status-json.xsl` emits **mojibake for non-ASCII** (seen: "Molly SandÃ©n"), i.e.
  UTF-8 bytes re-encoded as latin-1. The in-stream ICY is clean.
- `status-json.xsl` also sits behind Cloudflare and answered **403** to a default Python UA while
  serving curl fine. The audio mount itself has no such problem. Two more reasons to read ICY
  from the stream rather than poll the JSON.

Measured liveness (2026-08-08): a fresh connect announced "It Must Have Been Love - Roxette" and
flipped to "Private Dancer - Tina Turner" 15 s later — real track boundaries, in-stream, on the
mount we would be playing. Contrast the Bauer relay: 330 s, one block, frozen since 2026-07-31.

The switch shipped in 1.0.40; the whole Bauer data layer (Retrofit API, repository, DTOs,
metadata polling, the schedule-staleness machinery) was deleted with it — `git log` has the
inventory. Three consequences that are not obvious from the code:

- **`TrackInfo.eventId` is a synthetic positive hash of the StreamTitle**, because the stream
  carries no upstream id. `eventId > 0` still means "a real, identified track", which is what
  keeps the branding (`-1`) and ad (`-2`) sentinels working.
- **`TrackInfo.fromStreamTitle` splits on `\s+-\s+`, not a literal `" - "`.** The injector emits
  ragged spacing (`What Is Love  - Haddaway`), which a literal split turns into a trailing-space
  title and an empty artist.
- Retrofit and kotlinx-serialization are now unused by `:core` but still declared — left in
  place deliberately, since a replacement source would likely want them back.

**Album art has two sources: iTunes Search first, the station's own page as the fallback when
iTunes finds nothing** (order reversed in 1.0.60 — it was the other way round from 1.0.56).

`StationNowPlaying` was preferred at first for a real reason: iTunes can only match the announced
`Title - Artist` text, and that text is sometimes not enough to identify the *record*. "Wouldn't
It Be Good - Nik Kershaw" resolves to the 1984 original while the station is spinning a later
remix and showing the remix sleeve, and no amount of candidate scoring reaches that — the
distinguishing information is not in the string being matched. **That argument is still true and
it still lost**, on the measurement below: the page's album is worse more often than it is
better, and its errors have no ceiling. Reversing costs the remix case, ~1 track in 37, whose
failure mode is the right song by the right artist under an older sleeve.

A plain `GET https://retrofm.se/` server-renders the current track (no JavaScript, no Blazor
circuit, ~12.8 KB gzipped) with an album id, and `/nowPlayingMedia/albums/{id}-{th|sm|md|lg}.jpg`
serves the cover at 100/300/600/1000 px. Unlike the rest of that site, `/nowPlayingMedia/`
returns a **real 404** for a wrong name instead of the 56 KB SPA fallback, so a 200 from it can
be trusted — the "a 200 means not found" rule below does not apply to that route.

**The page is not always describing the same playout as the mount, and that is the whole design
constraint.** Measured across 32 boundaries on 2026-08-20:

- Page lag behind the mount, upper bounds of the twelve agreements: **0.20, 0.23, 0.23, 0.48,
  0.60, 0.68, 0.82, 0.84, 0.87, 0.92, 1.51, 2.18 s** — ten of thirteen inside one second. An
  earlier capture said "≈5.3 s" for five of them; that was a 5 s polling step, not the site.
  Re-measured cleanly on 2026-08-22 — one thread doing nothing but read the mount, another
  polling the page, no shared blocking — and it holds: **12 boundaries over 50 minutes lagged
  1, 3, 4, 5, 6, 11, 12, 13, 14 and 26 s**, median 11 s. The page is normally within seconds,
  which is also what the maintainer sees using the station's own web player.
- **The real failure is that the page inserts tracks the mount never announces.** Same clean
  run: 3 of 15 page changes had no mount counterpart at all — "Liberian Girl", "I'll Be
  Around", "Easy" — and one of them held the page for **3 min 18 s** while the mount was
  playing Mustang Sally. That single episode is the only lag above 26 s in the whole run.
  `agrees` rejects the window, so it costs the 1.2 s budget and buys nothing; it is a cost,
  never a wrong cover.
- **A 55-boundary capture the same day read as "a whole track behind" for 45 minutes. Treat that
  as unexplained, not as a property of the site.** It has not reproduced, and its harness fetched
  the page on the *same thread* that read the mount, so the socket went unread for 1–3 s at every
  boundary and the mount timestamps cannot be trusted. The obvious rescue — that the mount skips
  announcements, which would make a correct page look one behind — was tested against that data
  and **rejected**: gaps before the lagging rows were normal (median 236 s against 239 s for the
  rest). This note has now been revised twice on new evidence. If a third capture disagrees,
  suspect the harness before the station, and never share a thread between the reader and the
  fetches.
- The **first** fetch after a boundary is usually not the one that agrees: right immediately 6
  times, no player block at all 3 times, still showing another track 4 times. A second fetch a
  few hundred ms later takes it from 6/13 to 10/13, hence `STATION_NOWPLAYING_ATTEMPTS`.
- The page serves markup with **no player block** in ~8 % of fetches (6 of 73), scattered
  *inside* songs, not clustered at boundaries. It is "no answer", never a signal.
- Roughly once in fifteen boundaries the page shows a track the mount never announces and stays
  there for minutes (2026-08-20 17:18, and again 16:47 the same morning).

So the album id is used **only when the page's title and artist both agree with the track being
displayed** (`StationNowPlaying.agrees`, whitespace- and case-insensitive, nothing looser). A
disagreement returns null and iTunes takes over. That guard is load-bearing, not defensive:
without it the two stale-page cases would have put Art Garfunkel's cover on Jon Secada and Donna
Summer's on Whitney Houston. **Never relax it into a title-only match.**

**The guard checks the *track*, and the station can still name the wrong *album*. That is the
open question about this whole source.** `agrees` confirms the page is describing the song we
are about to display; it cannot check what record the album id points at, and the page offers
nothing to check it with — no album name, no album artist (the cover `<img>`'s `alt` is the
track title; markup read 2026-08-22). Two field cases where the page agreed perfectly and the id
was simply wrong upstream:

- **Günther's "Pleasureman" for Samantha Fox – "Touch Me (I Want Your Body)"** (2026-08-22, the
  first track the source ever served). Presumably a title match on "Touch Me".
- **"Tina: The Tina Turner Musical (Original Cast Recording)" for Tina Turner – "The Best"** —
  a cast recording, not her.

**Measured head-to-head, 37 tracks where the page agreed** (captured over 4 h, then each cover
fetched and judged against what `ArtworkLookup.pick` chose for the same track — scored by the
app's own Kotlin, not a re-implementation): **station better 8, tie 14, iTunes better 15**, plus
the one gross misattribution above. Roughly half the station's albums are compilations ("The
Essential…", "Greatest Hits", "Platinum & Gold Collection") — the very category `pick` was tuned
over 123 tracks to avoid. Where the station wins it wins alone: the **Nik Kershaw remix sleeve**
(the founding case, confirmed live), Kiss → *Dynasty* over a best-of, Carl Douglas, Pet Shop
Boys → *Introspective*, Sandra, Yazoo, the ABBA single sleeve, and Hall & Oates where iTunes
returns nothing at all.

**The asymmetry, not the score, is the argument:** the iTunes path has a scoring layer measured
over 123 tracks; the station path has none and **cannot be given one**, so its error mode is
unbounded while iTunes' is bounded. A recommendation to invert the priority (iTunes first,
station only when iTunes returns nothing) was put to the maintainer on 2026-08-22 and
**accepted the same day**, knowing it costs the Nik Kershaw case. Shipped in 1.0.60.

**What that means in practice: the page is now barely used — and in the field, not used at
all.** iTunes produced a pick for 36 of the 37 corpus tracks, so the fallback was expected on
the order of one boundary in thirty — the measured case being "Hall & Oates – Maneater", where
Apple credits "Daryl Hall & John Oates" and nothing matches the credit. The first five days of
1.0.60 in the car (2026-08-22→08-27, 44 boundaries) went further: **44 lookups, zero `no match`,
so the page was never consulted once.** `retrofm.se` is therefore no longer fetched at all on a
normal drive, which retires most of the per-device dependency flagged further down.

The flip side is that the fallback path is now **unexercised in the field**: nothing has proved
`StationNowPlaying` still works against the live site since the reversal. Do not read "the page
source is fine" from the absence of failures — there is no traffic to fail. If it matters, prove
it directly against the site rather than waiting for a log line that may never come.

**Rejected at the same time, and worth knowing why:** a hybrid that used the page only when
iTunes' own pick was weak (null, or `ownRelease = false`). On the corpus it looked better than
either pure order — it removes the Günther class while keeping the station's wins over
compilations — but it was **designed from the same 37 tracks it was scored on**, and this file
already carries two designs that a replay overturned. It was not adopted on that basis. If the
current order disappoints, replay the hybrid against a *fresh* corpus before believing it.

**And we are not fetching it wrong — that was tested, so nobody re-runs it.** The obvious
suspicion is that a real browser sees fresher data than our prerender fetch, since retrofm.se
holds a Blazor circuit. Driving the real page in headless Chromium and comparing the live DOM
against a simultaneous prerender fetch, 42 samples: **track title identical in 31 of 34, album id
identical in 39 of 40**, and the only difference had the *prerender ahead*. Cloudflare does not
cache it (`cf-cache-status: DYNAMIC`, `no-store`, no `age`) and a browser User-Agent gets
byte-identical fields. During the same window the page showed "Give It Up" for ~100 s — a track
the mount never announced — **and the browser DOM showed it too**. The page's disagreements with
the audio are the station's own, visible to any visitor.

**The two used to run in parallel; since 1.0.60 they run in sequence, and the reason the old
rule existed no longer applies.** Parallel was mandatory while the page was *preferred*: the
display waited on it, and a serial second request would have inherited the car's cold-modem
penalty — every drive's first iTunes lookup timed out at 8 s during the week of 2026-08-13. Now
the page is only consulted **after** iTunes has come back empty, by which point the title has
already been published with the logo and nothing is racing the display. A serial request that
nobody is waiting for costs nothing.

`ARTWORK_FIRST_APPLY_BUDGET_MS` (1.5 s) still bounds what the display waits for, and
`STATION_ARTWORK_BUDGET_MS` (1.2 s) now bounds the fallback rather than a leg of a race. All the
timings here are from a **fixed line**; field coverage will be lower, and that is never a reason
to raise the budgets.

The station's text carries HTML entities (`Yazz &amp; The Plastic Population`, seen live
2026-08-20), so the fields are unescaped before comparing — without that the ICY string's plain
`&` never matches and the cover is silently lost.

**`unescape` decodes character references by rule, not by enumeration — and that is why.** It
used to be a chain of literal replacements listing the *decimal* `&#39;`, but ASP.NET emits the
**hex** `&#x27;` for an apostrophe. Every title with one failed `agrees` even when the page
agreed perfectly (`no agreement — page says Nothing&#x27;s Gonna Stop Me Now` in the field log,
seen on the phone, in the car and in a direct capture): **6 of 37 agreements lost, 18 %** on the
2026-08-22 corpus, one of them "Wouldn't It Be Good — Nik Kershaw", the exact track this source
exists for. Fixed in 1.0.60 by decoding `&#nnn;`, `&#xHH;` and the named handful in **one pass**
— chaining `&amp;` first would turn a literal `&amp;#39;` into an apostrophe the station never
wrote. Anything unrecognised is left verbatim, so a stray `&` survives. Don't go back to a list:
the next spelling will not be on it either.

**Album art comes from iTunes Search** (`ArtworkLookup`). The mount carries no artwork, so the
first field test of 1.0.40 showed the station logo on every track — the pipeline was fine, but
every track had the same `imageUrl`, and Media3's `CacheBitmapLoader` dedupes on the URI, so
exactly one bitmap load happened all drive. Covers are looked up by "artist title" against the
public keyless `itunes.apple.com/search` (resolved every track tested, including obscure ones),
one request per boundary at most, hits *and* misses cached for the process lifetime. The
`artworkUrl100` the API returns is upsized by swapping the rendition segment to `600x600bb`.

**`AlbumArtContentProvider.ALLOWED_HOSTS` must list the artwork host.** 1.0.41 shipped without
`mzstatic.com` on it, so `openFile` blocked every cover and returned null — the car rendered its
own two-circle placeholder and the logs showed no artwork activity at all. A new artwork source
is two changes, not one: the lookup *and* the allowlist (`AlbumArtHostAllowlistTest` guards it).

**Update each track's metadata exactly once.** 1.0.41 applied the title with the station logo and
swapped the cover in afterwards; in the car that read as the logo flashing up and then breaking.
The cover is now resolved *before* the first apply, bounded by
`ARTWORK_FIRST_APPLY_BUDGET_MS` (1.5 s) so a slow lookup can't hold the title hostage.

**Never trust the API's first result.** Relevance ranking regularly puts a karaoke rendition or a
different primary artist featuring the credited one on top ("ZZang KARAOKE – I'll Be Missing You",
"Craig David – Rise & Fall (feat. Sting)" for a track credited to Sting). `ArtworkLookup.pick`
scores 15 candidates — artist agreement above title agreement, junk renditions rejected outright —
and returns **nothing** when the field is weak, because the station logo beats a confidently wrong
album cover. `ArtworkLookupTest` pins the real cases.

**The cover belongs to the *album*, not the track — score the release too.** A candidate can
match artist and title perfectly and still be wrong: "Take That – Back For Good" resolved to a
100-track various-artists ballads compilation, so the car showed a generic montage (field-
reported 2026-08-09). All fifteen candidates carried a parenthetical, so the plain-title
tiebreak could not separate them and the pick fell through to Apple's own ordering.
`collectionArtistName` is the signal, ranked above the plain-title preference and below
artist/title agreement — a compilation still wins when it is the only candidate, since any
cover beats the logo.

**Detect it structurally, never by its text.** The field is absent on an artist's own album and
set to an album-level credit on a compilation; the rule is "present and disagrees with the
artist". Matching the literal `"Various Artists"` looked generic and was not: `country=SE`
makes Apple localise it, so every Swedish row reads **"Blandade Artister"** and slipped
straight through — "Pointer Sisters – I'm So Excited" landed on a 100-track "80s 100 Hits" the
day after the rule shipped.

**Track count is not a usable signal, and this was measured.** "Prefer the shorter release"
sounds like it should favour an original album over a best-of. Replaying all eighteen logged
tracks against the live API showed it wrecking six: Status Quo to a live album, Clapton to a
soundtrack, Louis Armstrong to a Christmas record, Madonna and Tina Turner to singles, Take
That to an EP. It is deliberately absent from the score.

**Two ways the credit strings themselves fail to line up**, both found 2026-08-10 and both
showing the logo rather than a wrong cover, which is why they were invisible until the album
started appearing in the log:

- *A leading "The".* Whole-word containment only looks for the wanted artist **inside** the
  candidate's, so a wanted name one word longer matches nothing — "The Four Tops" rejected all
  fifteen rows, every one credited "Four Tops". Artist comparison drops a leading article on
  both sides (`artistKey`).
- *A joined credit is too specific to search.* "John Travolta + Olivia Newton-John You're the
  One That I Want" returns **one** unrelated result. Searching the lead artist alone finds the
  Grease soundtrack, whose "John Travolta & Olivia Newton-John" then matches the full credit
  exactly. So a miss retries with `leadArtist`, and the candidates are still scored against
  the **full** credit — a narrower search must never lower the bar.
- *The join spelled as a word.* Punctuation normalises to whitespace, so `&` and `+` already
  agree — but Apple often writes it out. "Katrina & The Waves" is `katrina the waves` against
  Apple's `katrina and the waves`, one word adrift, and all fifteen rows were rejected with the
  self-titled album sitting at rank 1 (field-reported 2026-08-12). `artistKey` drops standalone
  "and" on both sides, which is also what keeps "Mike & The Mechanics" matching Apple's
  "Mike + The Mechanics".

**Two more credit shapes that reach nothing, both found 2026-08-22 and both still open:**

- *A short form Apple spells out in full.* The station announces "Hall & Oates"; Apple credits
  "Daryl Hall & John Oates". Whole-word containment needs the wanted credit to appear as a *run*
  inside the candidate's, and `hall oates` is not a run of `daryl hall john oates` — the words
  are interleaved. The `leadArtist` retry does not help, because the problem is the comparison
  and not the query: searching "Hall Maneater" still returns rows credited the long way.
  `ArtworkLookup.pick` returned **no match at all**, so the station's cover was the only reason
  Maneater had one.
- *A name the station splits into two words.* "Six Pence None The Richer" against Apple's
  "Sixpence None the Richer" — a space, not a typo, and `artistKey` cannot bridge it. Logged as
  `no match (15 candidates)`, and the page happened to be a track behind at that moment, so the
  song showed the station logo. Related to the known "the station's own typos" case below but
  more tractable; leave it until the log shows more than one.

**A dotted acronym is a mismatch, not a near miss — and it produces a *wrong* cover, not a
logo.** Punctuation becoming whitespace splits "U.S.A." into three single-letter words, so
"Born In The USA" discarded Apple's actual album at rank 0 and matched the one candidate that
spelled it without dots: a 1996 Berlin live recording on a "Missing EP" (field-reported
2026-08-12). `normalize` collapses two or more letter-dot pairs in a row and nothing else — it
leaves "Boney M." and "Mr. Big" alone, and deliberately does not touch apostrophes, since
asymmetric apostrophes ("Ain't" vs "Aint") are a separate problem nothing has measured yet.

**The credit can be too long as well as too short.** Whole-word containment looks for the wanted
artist *inside* the candidate's, so when Apple credits only the lead and moves the guest into
the track name the wanted credit is the longer one and nothing matches: "Tom Jones & The
Cardigans – Burning Down The House" rejected all fifteen rows, every one credited "Tom Jones /
Burning Down the House (feat. The Cardigans)". `pick` accepts the mirror image at the **lowest**
artist tier, so it can never outrank a fuller agreement. Replaying the 123-track corpus: two
misses become correct covers (Tom Jones, Narada Michael Walden & Patti Austin), nothing else
moves.

**A duet is one act however it is billed — and the comparison below the top tier is ordered.**
The station announced "Kenny Rogers + Dolly Parton"; Apple credits the studio recording to
"Dolly Parton & Kenny Rogers" on six rows and to "Kenny Rogers & Dolly Parton" on exactly one —
an all-star tribute concert, which was therefore the only candidate that scored at all and the
cover the car showed (field-reported 2026-08-15). Whole-word containment needs the wanted name
to appear as a *run* inside the candidate's, so a swapped credit matches nothing. `sameCredit`
compares the two artist keys as word multisets and scores in the same tier as an exact match.

**A re-recording or a live take must never outrank the real record** (`SearchResult.isRendition`,
ranked above `ownRelease`). "The artist's own release" was doing exactly that: 1.0.54 put
"Ultimate Berlin Live" on *Take My Breath Away* and a "(Re-Recorded / Remastered)" single sleeve
on *Maniac*, because in both cases the real recording only exists on soundtracks and
compilations — `ownRelease = false` — while the re-recording is the artist's own. The test reads
the track's **bracketed qualifiers and the release title only**, never the bare track name:
"Live Is Life", "Living In A Box" and "Live and Let Die" are songs. Remasters are deliberately
not renditions — a remaster is the original recording.

**Still unfixed, and it is the soundtrack case:** the Top Gun and Flashdance covers are the
right ones for those two songs, and they are unreachable. A soundtrack is legitimately credited
"Various Artists", so `ownRelease` cannot tell it from a hits montage. The only field that names
one is `primaryGenreName` ("Soundtracks"), and it is **measurably localised** — the same
`country=SE` response set carries "Hårdrock", "Musikaler", "Alternativt" and "Barnmusik" — so it
falls under the rule below. The reachable alternative, ranking Apple's own order above
`ownRelease`, was replayed and **rejected: 34 of 123 picks move and most are regressions**
("Pop Heroes", "Rockklassiker Vol. 2", "80s 100 Hits", "Millennium Party", "Stranger Things
Remix", a dozen remasters and live takes). What shipped instead demotes the rendition, which
gets the *wrong* cover off the screen without reaching the right one.

**A stray spacing accent kills the search outright, and that is not a typo — it is encoding.**
"Somebody´s watching me (edit)" was the only query in the whole 1.0.54 corpus that returned
**zero** candidates. Measured 2026-08-17, one query each: `´` (U+00B4) → 0, `` ` `` (U+0060) →
0, `’` (U+2019) → 15, `'` → 15, **no apostrophe at all** → 15. So Apple tolerates a missing
apostrophe but not a diacritic mark standing in for one. `searchable` replaces those two
characters in the outgoing term only — the cache key and the log line keep the station's own
spelling, so the next odd character shows up instead of being silently repaired. The "(edit)"
suffix in the same title was measured innocent, which is why qualifiers are not stripped: the
four other bracketed titles that week all resolved as announced.

Known and deliberately unfixed: **the station's own typos.** "Starship – We Build This City"
finds nothing because the song is "We *Built* This City". Fuzzy title matching would fix one
sample and put a wrong cover on who knows how many others; leave it until the log shows a
pattern.

Three habits worth keeping: **log the album, not just the track** (the old line read "Take That
/ Back for Good (Radio Mix)" and looked like a perfect hit); **replay real queries** to judge a
ranking change instead of reasoning about the scoring — `entity=song&limit=15` is cheap and a
whole drive fits in one pass, and it is what killed the track-count idea; and treat **any
API string that Apple localises as unusable for logic** — `country=SE` is on every request.

The replay is worth doing properly, because it has now overturned a design twice. Fetch every
distinct `artist title` the logs show **once** into a file, then judge every scoring variant
offline against that file — a variant costs nothing to evaluate after the fetch, and the fetch
is the only thing that touches Apple. 123 tracks at 5 s spacing is ~10 min and drew no 429.
Two things that only showed up that way, on the very change described above: a set-equality
artist tier looked free and silently regressed *Ain't Nobody* to a live album until the
rendition term was added in the same commit, and "trust Apple's rank" looked obviously right
and moved 34 picks. Also expect **two or three picks to differ from what the log recorded** for
reasons that are not yours: where two candidates tie on every term, `-index` decides, and Apple
reorders between then and now (seen on Queen and Rick Astley, 2026-08-17). A tiebreak resting
on Apple's ordering is not reproducible — do not chase those as bugs.

It reaches the field too, not only the replay bench: the car looked up
`Bill Medley & Jennifer Warnes (I've Had) The Time of My Life` twice two days apart and got
*The Best of Bill Medley* on 2026-08-22 and *Dirty Dancing (Original Motion Picture
Soundtrack)* on 08-24 — same query, same code, different cover on screen. It was the only
unstable pick in 44 boundaries, and that time the drift went the right way. Do not treat "the
cover changed for a song I saw yesterday" as a regression without checking the query is stable
first.

Two mechanics that make a replay describe the app rather than the harness (learned 2026-08-22):

- **Score with `ArtworkLookup.pick` itself, never a re-implementation.** A Python copy of the
  scoring compares *that copy* to the station, not the app — and the scoring is the part with
  123 tracks of tuning in it. Drive the real thing from a throwaway JUnit test that reads the
  captured corpus as TSV and writes the picks back out; delete it afterwards. Note that the test
  JVM inherits the **Gradle daemon's** environment, so an exported shell variable never reaches
  it — pass the path some other way.
- **Include the `leadArtist` retry or you will undersell iTunes.** `artworkUrl` searches a second
  time with the lead credit when the first query finds nothing, and a corpus fetched with only
  the full credit misses it: "John Travolta + Olivia Newton-John" scored as a miss until the
  retry query was fetched too, after which it resolved. Fetch the retry for every track that
  comes back empty, and keep scoring against the **full** credit.

That replay has a measured rate limit: 41 queries at 1.2 s spacing earned a **429** on the
42nd (2026-08-12). Space a corpus replay several seconds apart, and if one does trip, wait it
out rather than retrying — the app's own budget is one request per track boundary, and a
diagnostic must not be what teaches Apple to throttle this client.

**Transport failures must not be cached.** A miss is cached only when the API actually answered.
Caching a boot-time connection failure would poison that song for the whole process — the car
starts before the modem is up, so that turns into "artwork works on some starts and not others".

**The car's link, not the API, decides whether art appears.** Field logs 2026-08-09 caught
twelve consecutive lookups timing out over 15 minutes — every track of the drive — while the
audio stream played uninterrupted and the modem dropped three times around that window. The
timeout was 5 s, which does not cover DNS + TCP + TLS to a host the connection pool has just
lost; a single retransmitted SYN (1 s, 2 s, 4 s) overruns it alone. Payload was never the
issue: iTunes gzips, so 15 candidates are ~3 KB on the wire, not 24 KB. Fixed in 1.0.45 by a
20 s ceiling with 8 s phase timeouts and a 10 min connection pool — tracks are 3–4 min apart,
so OkHttp's 5 min default was dropping the connection just often enough to matter. None of this
delays the display: `ARTWORK_FIRST_APPLY_BUDGET_MS` still publishes the title at 1.5 s.

**Lookups log their elapsed time on every outcome.** That drive had to be diagnosed by
subtracting the timeout from log timestamps; a number in the line makes the next one a
measurement instead. It paid for itself the same evening — one recovered drive gave 14
successes at a **median of 607 ms** (worst 931 ms) against 3 failures, all of them stalling at
exactly the 8 s connect timeout. So the 20 s ceiling is not what bites; the connect phase is.

**Only the first lookup of a playback session fails — but most first lookups are fine.** Every
failure ever logged has been the first track after playback started: the modem is warm for the
audio stream but cold for a new host, and nothing else pays that cost. The converse is not true
and a 2026-08-22→08-27 capture measures it: over 44 car boundaries (47 attempts, counting the
retries), **13 lookups started within 10 s of a `network available` and 3 of those failed; the
31 warm ones failed 0 times**. So a cold first lookup is a ~1-in-4 risk, not a certainty — do
not read a successful drive as evidence the cold path is fixed. Hence
`ARTWORK_LOOKUP_ATTEMPTS = 2`. Do not raise it — a third attempt would be pressing an API that
is plainly unreachable.

Warm-path latency from the same capture, 41 first-attempt successes: **median 852 ms, worst
1597 ms**. Exactly one exceeded `ARTWORK_FIRST_APPLY_BUDGET_MS` — by 97 ms — and paid for it
with the double apply the budget exists to prevent (`apply` → `late artwork applied` → `apply`,
inside one second). The budget sits in the tail of the distribution, so that will keep happening
occasionally; it is not a regression.

**But an *immediate* retry is a wasted request, and this note used to claim the opposite.** It
was added believing the second attempt would land in under a second, since only the first pays
a cold path. Three field cases since say no: Four Tops (2026-08-10), The Corrs and Lynyrd
Skynyrd (2026-08-11, 1.0.51–1.0.53) — **3 of 3 retries also timed out**, each pair reading
8 s then 16 s from the same lookup start. Every one began within ~10 s of a `network available`
callback, so the head unit reports the link up well before it can carry a fresh DNS + TCP + TLS
to a host outside the pool, and both attempts spent themselves inside that same dead window.
`ARTWORK_RETRY_DELAY_MS` (15 s) puts the second attempt past it; worst case is ~31 s against a
3–4 min track and the display never waits. What used to rescue these songs was the mount's
re-announcement re-running the lookup minutes later — that is the "artwork appears just as the
song ends" symptom, not a second chance worth designing around.

**The delay works, confirmed in the field 2026-08-27.** Over 2026-08-22→08-27 on 1.0.60 the car
logged three first-lookup timeouts — Kokomo, The Logical Song, Cryin' — each breaking at ~8.0 s
on the connect phase, and **all three succeeded on attempt 2** at a total of ~23.9 s. Against
the 3-of-3 failures that motivated the delay, that is the measurement that closes it. The cost
is visible and accepted: the display carried the station logo for ~24 s before the cover
appeared.

**The mount re-announces a title mid-track.** Confirmed 2026-08-09: the same `StreamTitle`
arrives again 50–90 s into a song (`apply skipped (dedup)` when the metadata is unchanged).

**Since the station page was added, that repeat can swap the cover mid-song — unfixed.** The
repeat runs the full boundary path, which was harmless when there was one artwork source and a
cache: the second lookup returned the same URL and dedup swallowed it. Now the page is consulted
again, and a repeat is *by definition* an end-of-track marker, so the page has usually already
moved to the next song and `agrees` correctly says no — after which the iTunes cover is applied
over a station cover that was already right. Seen three times on 2026-08-21 (Let It Be, The One
And Only, Have You Ever Seen The Rain?); the direction varies, so the bug is the unpredictable
flip, not that the result is always worse. A repeat should only be allowed to *add* artwork the
track does not yet have, never to replace what is already resolved.
Two consequences. It is *not* a reconnect, so don't read it as one. And it is what made the
first-lookup failures self-heal before the retry existed — the re-announcement re-ran the
lookup, so the cover appeared roughly three minutes late, right as the song ended and the
station jingle played. That was the reported "flickering" symptom, not a rendering bug.

Two consequences worth knowing before touching this:
- The title is applied first and the artwork upgrades it in a second apply. That only works
  because dedup compares the **whole** `TrackInfo`, not `eventId` — comparing ids would swallow
  the artwork apply. Don't "optimise" that back.
- A lookup is skipped when the parsed artist is the station name, which is what
  `fromStreamTitle` yields for a separator-less StreamTitle ("Nyheterna"). Searching on that
  returns confident nonsense.

Art still routes through `AlbumArtContentProvider` as a `content://` URI — the AAOS rule above is
unchanged, only the source of the remote URL moved.

**Freeze protection is elapsed-time only** (`TRACK_FROZEN_AFTER_MS`, 8 min). The old defence
proved a track stale from its `eventFinish`; this stream carries no timestamps, so nothing but
the clock is available. The threshold comes from listening to the mount directly for 50 minutes
(2026-08-10, 14 consecutive tracks): the longest a real title legitimately held the display was
**312 s**, so 8 min has a >50 % margin while still catching an injector stuck for days — the
"always Talk Talk" failure that forced the migration.

**The clock it spends is *playing* time, not wall clock** (`TrackPlayingClock`, driven from
`isPlaying` by the playback heartbeat). Wall clock was wrong and shipped that way: the car's
modem stalls the stream for minutes mid-song, and on 2026-08-15 a 3.5 min rebuffer inside
"Black Velvet" logged `metadata frozen — held 492 s` against the 480 s threshold and blanked a
title that was correct, one second before the next one arrived. A stalled stream is not a
frozen injector — a frozen injector freezes while the audio keeps playing, so it still trips.
`TrackPlayingClockTest` replays that case; the service itself has no test harness, so the
accounting lives in that class deliberately.

**Non-music is invisible.** During news, jingles and ads the mount emits *nothing* — not an
empty `StreamTitle`, not "Nyheterna" — so the last song's title stays on screen through the
bulletin.

A plain silence timeout cannot fix that: the confirmed news episode held **312 s** and the
longest legitimate song ("Piano Man") held **312 s**. Those do not merely overlap, they
coincide.

**And the station's own page does not help either — measured, so nobody chases it a third
time.** The page carries four fields the mount never sends (`title`, `artist`, `dj`, `show`), so
it looked like the obvious place to find a non-music signal. A capture over the 18:00 CEST
bulletin on 2026-08-20 (page sampled every 20 s, mount held open) says no. The mount went silent
for **145 s** starting at 17:59:20 local — right on the hour — and throughout it the page kept
showing the previous song with its album id, exactly as the mount did. `show` and `dj` held the
programme name the whole window; they track the **schedule**, not the current item. There is no
news marker, no null album, no state change of any kind. Non-music remains invisible on every
surface the station exposes.

**But the re-announcement is a genuine end-of-track marker, and an earlier version of this note
wrongly dismissed it.** Measuring it from the track's *start* gives a useless 84–309 s spread.
Measured to the *next* title it is tight: **3–14 s in 13 of 14 samples** (2026-08-10 capture).
The app already receives these blocks — they are the `icy boundary` lines followed by `apply
skipped (dedup)`.

What it cannot do is catch the news, because in every suspected news case the marker never
arrived at all (ABBA before a 278 s gap; Jennifer Rush before the car's 312 s gap). If the
marker means "the next song is queued", its absence is the news signal — and absence is only
observable as a timeout from the track start, which puts us back at 312-versus-312.

What it *can* do is catch "the song ended and nothing musical followed", and that is what
`TRACK_HANDOVER_GRACE_MS` does. The value is the empty band in the marker-to-next-title
distribution, and **the band moved once a week of field logs replaced the first capture.**

- 2026-08-10/11, 17 hand-overs from a direct mount capture: 3–14 s for fifteen, one at 25 s,
  then nothing until 148 s. 15 s was chosen inside the jingle range on that evidence.
- 2026-08-13→17, **109 hand-overs from the car on 1.0.54**: 83 at 3–13 s, 22 at 15–25 s, then
  **nothing between 25 s and 32 s**, then 32, 43, 54, 61, 71, 83, 105, 131, 143 s.

15 s sat in the middle of the jingle range and it was field-visible: **16 of 30 branding
reverts had the next title arrive 1–12 s later** — the logo blinking between two ordinary
songs, shortest 0.98 s (2026-08-15 09:30:24). **The value is now 30 s**, inside the new empty
band; it removes 13 of those 22 reverts and still catches all 9 real interruptions (≥32 s),
paying 30 s of stale title instead of 15 s when the interruption is real.

Re-measure the band before touching it again — three times now the distribution has been the
whole argument, and no two have agreed. The samples are already in the field logs as `icy
boundary` followed by `apply skipped (dedup)`; the earlier capture is a ~40-line script that
connects once with `Icy-MetaData: 1`, reads `icy-metaint` bytes, reads the length byte and
prints non-empty blocks with a timestamp. One listener connection, no polling.

**Third capture, 2026-08-22→08-27 on 1.0.60 (car, 25 usable hand-overs):** 1, 5, 5, 7, 7, 7, 7,
8, 8, 9, 9, 13, 13, 16 — then **nothing until 32** — then 32, 40, 47, 49, 52, 53, 83, 95, 111,
220, 277. The empty band reads 16 s → 32 s here, i.e. 30 s now sits at its very edge rather than
in its middle. **Not a reason to move the value:** n=25 against the 109 that set it, and the
1.0.54 week put 22 samples in the 15–25 s range this one simply did not sample. Six of the gaps
(32–53 s) are real track changes where the logo still blinked for 2–23 s, which is the standing
cost of 30 s and was already known. Re-measure on a larger sample before changing anything.

**The marker is not always the end of the track — partly fixed 2026-08-27, and the rest is
open.** The mount re-announces a title 1–5 times, usually 150–290 s in, but sometimes at +7 s,
and it announces the current title **on connect** as well. Six times in the 1.0.54 week the
display went song → logo → *the same song again* (Imagine, 2026-08-15 09:03: announced at
+0, +7, +30 and +71 s, blanked at +22 s, restored at +30 s); `All Out Of Love` was announced
four times in nine seconds. **On 1.0.60 it got worse, not better: 5 of 11 hand-over reverts were
false** over 2026-08-22→08-27 in the car — four of them on 08-27 alone — with the logo standing
26, 29, 67, 152 and 181 s over a song that was still playing.

`RetroFmConfig.TRACK_HANDOVER_MIN_AGE_MS` (25 s) is the floor that now closes two of the three
shapes, enforced by `EndOfTrackMarker`. The ages tell them apart: the repeats behind the false
reverts arrived **3, 7, 13, 21 s** into the title, the ones behind correct reverts at **59, 137,
186, 192, 196, 215, 262 s**. Replayed against that capture the floor removes 3 confirmed false
reverts and 1 near-certain, and loses **no** correct revert.

- **The clock restarts on a stream re-open as well as on a title change, and that second reset
  is half the fix.** An earlier note here proposed "only arm when the marker arrives ≥60 s into
  the track" — that reads the wrong clock. "Love Is All Around" (2026-08-27 15:23) had been
  playing 80 s when the modem dropped; the stream reopened and the mount re-announced what was
  already on screen. The *title* was old, the *connection* was not. What the gate measures is
  therefore playing time on one unbroken stream.
- **The residual is the genuine mid-song re-announcement and no timing rule reaches it.** "Joe
  Le Taxi" repeated at +98 s and "You Get What You Give" at +124 s, neither the end of the song
  — while real markers landed at +59, +123, +137, +186 s in the same capture. The distributions
  overlap outright. Raising the floor to catch them would start eating correct reverts; leave it
  and expect ~2 blinks per 40 boundaries.
- The suppressed case logs `title repeat N s in — too early to be an end-of-track marker`, so
  the next capture can count what the gate is actually rejecting.
- **`EndOfTrackMarker.titleAgeMs()` banks the clock before answering, and must keep doing so.**
  The playback heartbeat ticks every 30 s but a marker arrives whenever the mount sends it;
  reading the raw accumulator would turn the 25 s floor into an unpredictable 25–55 s one. The
  freeze defence has no such problem because it is checked inside the heartbeat.

### retrofm.se: dead ends, so nobody re-runs them (probed 2026-08-08, settled 2026-08-20)

The page itself is *not* a dead end — see the artwork section above; it is where the station's
own covers come from. These are the two push-style surfaces, and neither is worth building on:

- **`/nowplayinghub` — closed, and now proven rather than suspected.** An earlier version of
  this note said it was "blocked on one unknown: the group name", and advised asking the station
  for that string. **That framing was wrong.** The surface was confirmed on 2026-08-20:
  `AddToGroup(string)` and `RemoveFromGroup(string)` both exist (against a deliberate
  `Method does not exist` control) and the server callback is `Send` — taking a **single
  string**, which by itself cannot carry an artwork object. Two connections held open across
  boundaries confirmed live on the mount produced **zero** pushes: one joined to the station's
  own GUID (discovered from `/uploads/stations/…-w150.png`, not guessed), one joined to nothing.
  The join echo is verbatim the stock ASP.NET SignalR groups sample text, and in nine minutes no
  other client ever joined. The settling observation: **retrofm.se's own browser never connects
  to the hub** — a full network capture of a real page load shows exactly one negotiate,
  `/_blazor/negotiate`, there is no SignalR module in the 176-entry importmap, and none of the
  Caster JS bundles mention `signalr`, `HubConnection` or `AddToGroup`. Nothing publishes into
  it and no client-side artefact can reveal a group name, because no client uses it. Do not
  spend more guesses here.
- `/_blazor` — the site's own Blazor Server circuit, which does deliver live title/artist/art.
  Rejected on principle: it holds per-client server state, so a fleet of phones parked on it is a
  real cost to someone else's site, and it parses undocumented UI internals. Superseded anyway:
  the prerendered HTML carries the same data with no circuit and no server state.

Three reusable lessons from that hunt:

- **Check the response size before believing a 200.** Every `/api/*`, `/swagger`, `/openapi`
  guess returns the SPA fallback: 200 with ~56 KB of page HTML. That is how this site says 404.
- **A Blazor circuit needs its render batches acknowledged** (`OnRenderCompleted(batchId, null)`)
  or the server stalls once the unacked buffer fills — which reads exactly like "the station
  stopped updating". That cost a whole misread experiment.
- **Headless Chromium (Playwright, on the dev host) is what cracked this.** The stream URL is
  assigned only when playback starts, so it appears in no served HTML and no amount of curl-ing
  finds it. When a site's own behaviour is the question, drive the real page and watch what it
  connects to.

When probing upstream during an investigation, stay polite: single requests, no poll loops
against third-party APIs, and never scrape a signed endpoint belonging to another app.

## Field logs

The app ships logs to a remote sink; read them for car/phone debugging (the car has no adb). The
DEBUG level is set via `applogs.falle.se/admin` (Entra-gated) and **resets to WARN on every
redeploy** of the log infra, so re-enable DEBUG before an investigation. The exact query recipe
(SSH → VictoriaLogs) is in the maintainer's personal notes, not the repo.

- The log client (`se.falle.logsink` in `:core`) is **vendored verbatim** from
  `github.com/MagTer/logsink-clients` — never edit it only here. Change upstream first, then
  re-vendor the files with the new commit hash in their 3-line header (the rest must stay
  byte-identical to upstream).
- **Durable spool** (client `spoolFile`, wired in `RetroFmApplication`, knobs and kill switch
  `RetroFmConfig.LOG_SPOOL_*`). The car's modem drops repeatedly mid-drive; the in-memory buffer
  survives that, but not the process being killed at park while still offline — which is why a
  drive's tail never reached the sink. The spool closes only that gap.
  - It is a last resort, not a mirror: **nothing touches disk on the logging path**, and a
    normal online drive writes nothing at all. Disk is touched only after a flush has actually
    failed (≥2 min apart, skipped when nothing new was logged) and at teardown via
    `persistNow()`. Budget: ~15 writes of ≤64 KB per half-hour with no coverage.
  - A first attempt (`DiskLogTree`, 1.0.28) **took logging down completely** — one line per boot,
    then silence, worse each restart. It appended every line synchronously on the logging thread
    and replayed an ever-growing backlog on the main thread inside `Application.onCreate`, so
    the car ANR'd at boot and was killed before it could ship anything. Do not reintroduce
    per-line I/O, main-thread replay, or an uncapped file.
  - If that signature ever returns, flip `LOG_SPOOL_ENABLED` to false and ship — that is what
    it is for.
- The shim (`github.com/MagTer/logsink-shim`) **allowlists ingest fields server-side** — a new
  per-line field the client sends also needs a shim allowlist entry, release and redeploy
  before it reaches VictoriaLogs (it is silently stripped until then).
- **A silent sink is not necessarily the app's fault — check the edge.** On 2026-08-09 the car
  shipped 20 lines at boot and then nothing for 40 minutes, while its config polls kept
  succeeding every 5 min. Cause was three hops away: Traefik's `public-buffering` middleware
  (`maxRequestBodyBytes: 4096`, written for a static site) was also on the log *ingest* route,
  so every NDJSON batch over 4 KB was refused at the edge and never reached the shim. The
  client retried the identical bytes forever, blocking every line behind it. Fixed on both
  sides: a dedicated `ingest-buffering` middleware at 512 KB matching the shim's own cap
  (home-server repo), and a client that halves a 413'd batch instead of repeating it
  (logsink-clients). **Diagnostic order that worked: VictoriaLogs → shim access log → proxy
  log.** The shim log was decisive precisely because it showed *no* POSTs at all.
- **A state change with no recorded cause is not diagnosable, and that cost a whole
  investigation (2026-08-29).** After a Cast session dropped, the phone logged 30
  `BUFFERING -> READY` round trips, **29 of them completing in ≤0.1 s** and eighteen exactly 2 s
  apart. That rules out a rebuffer — `BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS` is 10 s and no
  realtime stream delivers ten seconds of audio in a hundred milliseconds — but nothing recorded
  whether the buffer was discarded by a **seek** or the loader was quietly retrying underneath
  (`DefaultLoadErrorHandlingPolicy(6)` swallows six attempts before raising anything). Different
  causes, different fixes, and the record could not separate them. **The mechanism is still
  unknown; do not repeat either guess as a finding.** Three lines were added in 1.0.62 so the
  next occurrence settles it:
  - `discontinuity <REASON> N -> N s` from `onPositionDiscontinuity`. `SEEK` means a caller asked;
    `INTERNAL` is the player acting on itself, which is the loader's signature.
  - `stream <verb> — <cause>` at every deliberate `seek`/`prepare` call site, so a `SEEK` always
    has a named caller instead of an argument about which one it was.
  - The transport on every Network line (`network available (wifi)`), because the callback is
    registered for the *default* network: a Wi-Fi → cellular handover appears as a bare
    `network available` with no `lost` beside it (2026-08-29 18:53:02) and was unreadable.
    Reports `unknown` when the capabilities are already gone — normal in `onLost` — rather than
    guessing a transport.
- **Log lines cost wire bytes, so keep them short.** The artwork `content://` URIs are the
  remote URL base64'd into the path — ~300 chars, twice per bitmap load. They alone filled the
  batches that the 4 KB cap then rejected. `AlbumArtContentProvider.describe` renders them as
  `host/name` instead; prefer that shape for anything logged in a loop.
- **But short is not the same as identifying, and that cost a whole investigation.** `describe`
  used to take the *last* path segment. Apple serves every cover under the same rendition
  filename, so all artwork logged as `is1-ssl.mzstatic.com/600x600bb.jpg` — one indistinguishable
  line per track, per drive. When a wrong cover was reported from the car (2026-08-21, Günther's
  "Pleasureman" under Samantha Fox) the logs could not say which image had been on screen; every
  candidate cover had to be re-fetched from the CDN by hand and eyeballed. It now skips a
  `WxH….ext` segment and names the one before it — Apple's is the release UPC.
  `AlbumArtDescribeTest` pins it, including that two different covers cannot render alike.
  **A shortened identifier that is equal for every value is not a log line, it is a constant.**

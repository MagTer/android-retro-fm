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
./gradlew :app:bundleRelease :automotive:bundleRelease
```

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
- **Cast is off in the car.** `PlayerManager` never builds a `CastPlayer` on `FEATURE_AUTOMOTIVE`,
  and `:automotive` excludes the whole `com.google.android.gms` + `com.google.android.datatransport`
  dependency (their startup components trigger a "needs Google Play services" error on head units).
- **Ad muting is a private-circle decision, internal-only.** `RetroFmConfig.MUTE_ADS` mutes the
  broadcaster's spliced ads — acceptable for a personal internal-testing build, but it must NOT
  ship to a public/production track without resolving Retro FM/Bauer licensing (restreaming their
  station publicly is a licensing matter regardless of the mute).
- **Log hygiene is a wire contract.** Field logs leave the device via the remote sink (Timber +
  LogsinkTree); never log tokens, credentialed URLs, or PII.
- Live stream: reconnect retries indefinitely while playback is wanted and recovers on *validated*
  internet (`NET_CAPABILITY_VALIDATED`), reopening at the live edge — no stale buffer, no hard
  give-up. Don't reintroduce a fixed reconnect cap.

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

### The station moved to a new CDN — that is the answer (found 2026-08-08)

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

**Album art comes from iTunes Search now** (`ArtworkLookup`). The mount carries no artwork, so the
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
measurement instead.

Two consequences worth knowing before touching this:
- The title is applied first and the artwork upgrades it in a second apply. That only works
  because dedup compares the **whole** `TrackInfo`, not `eventId` — comparing ids would swallow
  the artwork apply. Don't "optimise" that back.
- A lookup is skipped when the parsed artist is the station name, which is what
  `fromStreamTitle` yields for a separator-less StreamTitle ("Nyheterna"). Searching on that
  returns confident nonsense.

Art still routes through `AlbumArtContentProvider` as a `content://` URI — the AAOS rule above is
unchanged, only the source of the remote URL moved.

**Freeze protection is gone with the API.** The old defence proved a track stale from its
`eventFinish`; the new stream carries no timestamps at all, so if this injector ever freezes the
app will happily show one title forever. Nothing detects that today. A fix would need a
text-based heuristic (same StreamTitle across an implausible span) — deliberately not written
blind, because it would also suppress a legitimately long block.

### retrofm.se: dead ends, so nobody re-runs them (probed 2026-08-08)

Two live metadata sources exist on `retrofm.se`. **Both were investigated in full and neither is
worth building on** now that the stream carries ICY:

- `/nowplayinghub` — a real ASP.NET Core SignalR hub (gives itself away by answering
  `400 Connection ID required` instead of the SPA fallback). Whole public surface is
  `AddToGroup`/`RemoveFromGroup` with a `Send` callback. Blocked on one unknown: the group name.
  ~20 candidates were excluded by sitting in the group across verified track boundaries. If it is
  ever needed, ask the station for that one string rather than guessing.
- `/_blazor` — the site's own Blazor Server circuit, which does deliver live title/artist/art.
  Rejected on principle: it holds per-client server state, so a fleet of phones parked on it is a
  real cost to someone else's site, and it parses undocumented UI internals.

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
- **Log lines cost wire bytes, so keep them short.** The artwork `content://` URIs are the
  remote URL base64'd into the path — ~300 chars, twice per bitmap load. They alone filled the
  batches that the 4 KB cap then rejected. `AlbumArtContentProvider.describe` renders them as
  `host/lastSegment` instead; prefer that shape for anything logged in a loop.

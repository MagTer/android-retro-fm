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

Retro FM has **left Bauer/RadioPlay**, and the app followed it to the station's own Icecast on
2026-08-08 (see "The station moved to a new CDN" below for what to build on — this section is the
evidence that the old platform is gone for good, so nobody writes code waiting for it to recover).
Verified 2026-08-08:

- `radioplay.se/retrofm` → **404**. RadioPlay SE now carries only Mix Megapol (`mme`), NRJ
  (`nrs`), Nostalgi (`ntg`), Rockklassiker (`rok`).
- `listenapi.planetradio.co.uk/api9.2/nowplaying/res` → `[]` (all api versions, any casing).
- `…/playlist/?StationCode=res` → answers, but frozen since **2026-07-09**.
- `…/brand/SE_RETROFM` still returns a record — it is a leftover row whose `BrandWebsiteUrl`
  points at the 404 above. Its existence is not evidence the station is still on the platform.
- `…/stations/…` returns 44 stations, **all UK**.

The old Bauer mounts still serve audio — both `retrofm_mp3` (192 kbps MP3) and `retrofm_aacp`
(96 kbps AAC+) return 200; Icecast status pages are off. **They are legacy relays; do not go
back to them.** Their ICY injectors are fed by the decommissioned playout system:

- `retrofm_mp3` is **frozen since 2026-07-31 20:47** on `eventdata/401045588`. Measured: 330 s
  of stream, one single metadata block, zero updates. `StreamTitle` names the same song forever.
  This is the "always Talk Talk – It's My Life" the app showed for a week.
- `retrofm_aacp` sends an **empty** `StreamTitle` instead. Not frozen, but no data either — so
  switching between the Bauer mounts never bought back metadata.

**The web players work because they never used Bauer.** `retrofm.se` runs Caster
(`CasterPlayBlazorUi`, Blazor Server); `radio-sveriges.se` is myTuner with its own HMAC-signed
`metadata-api.mytuner.mobi`. myTuner's is signed for their own app — **not ours to call**.

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

**The switch is done** (2026-08-08, uncommitted at time of writing). What it changed:

- `RetroFmConfig.STREAM_URL` (was `STREAM_URL_MP3`) points at the new mount.
- The whole Bauer data layer is deleted: `RetroFmApi`, `NowPlayingRepository`, `NetworkModule`,
  both response DTOs and their tests. `stationFallback` moved to `TrackInfo.Companion`.
- Parsing lives in `TrackInfo.fromStreamTitle`, which splits on `\s+-\s+` rather than a literal
  `" - "` — the injector emits ragged spacing (`What Is Love  - Haddaway`), which a literal split
  turns into a trailing-space title and an empty artist.
- No upstream id exists any more, so `TrackInfo.eventId` is a synthetic positive hash of the
  StreamTitle. The `eventId > 0` test still means "a real, identified track", keeping the
  branding (`-1`) and ad (`-2`) sentinels working untouched.
- Gone with the API: metadata polling, `isStaleScheduleTrack`, `parseEventTimeMillis`,
  `resyncNowPlayingAfterAdBreak`, `icyDriven`, `IcyAdMarker.parseEventId`, and the
  `METADATA_POLL_*` / `SCHEDULE_EVENT_STALE_AFTER_MS` config. The post-ad resync is unnecessary
  because the mount re-announces the current title on connect.

Retrofit and kotlinx-serialization are now unused by `:core` but still declared in its
`build.gradle.kts` — left in place deliberately, since a future replacement source will likely
want them back.

**Album art comes from iTunes Search now** (`ArtworkLookup`). The mount carries no artwork, so the
first field test of 1.0.40 showed the station logo on every track — the pipeline was fine, but
every track had the same `imageUrl`, and Media3's `CacheBitmapLoader` dedupes on the URI, so
exactly one bitmap load happened all drive. Covers are looked up by "artist title" against the
public keyless `itunes.apple.com/search` (resolved every track tested, including obscure ones),
one request per boundary at most, hits *and* misses cached for the process lifetime. The
`artworkUrl100` the API returns is upsized by swapping the rendition segment to `600x600bb`.

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

Everything below documents the dead ends, kept so nobody re-runs the investigation.

### retrofm.se internals (probed 2026-08-08)

`retrofm.se` has **no REST endpoint** — every `/api/*`, `/swagger`, `/openapi` guess returns the
SPA fallback (200, ~56 KB of page HTML). **Check the response size before believing a 200**; that
fallback is how this site says 404. A real route looks different: `/spellista` and `/tabla` are
~62 KB, and `/nowPlayingMedia/albums/<guid>-{sm,lg,th}.jpg` returns `image/jpeg`.

`https://www.retrofm.se/nowplayinghub` is a dedicated ASP.NET Core SignalR hub, separate from the
site's own Blazor circuit. It gives itself away by answering `400 Connection ID required`
(22 bytes, `text/plain`) instead of the SPA fallback. **Superseded by the Icecast stream above —
do not build on this.** Contract, verified against the server, kept for reference:

```
POST /nowplayinghub/negotiate?negotiateVersion=1   → connectionToken
     transports: WebSockets | ServerSentEvents | LongPolling
WS   wss://www.retrofm.se/nowplayinghub?id=<connectionToken>
     → {"protocol":"json","version":1}\x1e          (SignalR JSON, \x1e-delimited)
     → AddToGroup(<group>)                          server->client callback is "Send"
     → RemoveFromGroup(<group>)
```

`AddToGroup` and `RemoveFromGroup` are the **entire** public surface — every other name tried
answers `HubException: Method does not exist`. The site itself does not use this hub (no page JS
references it; the site renders now-playing server-side over `/_blazor`), so it exists for
external consumers.

**Blocker: the group name is unknown.** `AddToGroup` accepts any string and echoes
`"<connId> has joined the group <name>."` to the group, so acceptance proves nothing — and that
echo is verbatim Microsoft's SignalR groups sample, i.e. boilerplate. Excluded by sitting in the
group across a *verified* track boundary: the station guid
(`484fb6d7-71d8-4905-84c3-6a339fce1e15`, from `/uploads/stations/<guid>-w150.png`) in all four
C# spellings, `nowplaying_`/`station_`/`NowPlaying_`/`Station_` prefixes, `retrofm.se`,
`www.retrofm.se`, `Retro FM`, `retrofm`, `RetroFM`, `Retro FM Skane`, `Retro FM Skåne`,
`retro-fm`, `RETROFM`, `nowplaying`, `all`, `1`. Ask the station for this one string rather than
guessing further — the rest of the contract is already known, which makes it a small ask.

**Fallback that works today: the Blazor circuit at `/_blazor`.** negotiate → `blazorpack`
handshake → `StartCircuit(baseUri, uri, <the page's two server component descriptors>,
<component state>)`. It returns the correct live song while our own stream still announces the
frozen July track. **Gotcha that cost a whole misread experiment: you must acknowledge every
render batch** by invoking `OnRenderCompleted(batchId, null)`, or the server stalls once its
unacked buffer fills and the circuit goes silent — which looks exactly like "the station stopped
updating". Batches are diffs, so a track change carries only the changed text, not its CSS class.
With acks in place it delivers title, artist, album-art guid, show/host, and a timestamped recent
list. Values sit right after their marker in the payload's UTF-8 string table:
`cp-player-track-title`, `cp-player-artist-name`, `mmm-cover-track-title`,
`cp-playlist-song-{title,artist}-mini`.

The circuit is **not a good dependency to ship**: it holds per-client server state for as long as
it is open, so a fleet of phones parked on it is a real cost to someone else's site, and the
payload is undocumented UI internals that any layout change breaks. If it is ever used as a
stopgap: one circuit only while playback runs, closed on pause, with reconnect backoff.

A SignalR client for the hub route needs **no new dependency** — the JSON protocol above is
negotiate-POST + WebSocket + `\x1e`-delimited frames, which OkHttp (already in `:core`) and
kotlinx-serialization cover. `com.microsoft.signalr` would drag in RxJava3, Gson and slf4j.

When probing upstream during an investigation, stay polite: single requests, no poll loops
against third-party APIs, and never scrape a signed endpoint belonging to another app.

Headless Chromium (Playwright, already on the dev host) is the tool that cracked this: the stream
URL is assigned only when playback starts, so it exists in no served HTML and no amount of
curl-ing finds it. When a site's own behaviour is the question, drive the real page and watch
what it connects to.

## Field logs

The app ships logs to a remote sink; read them for car/phone debugging (the car has no adb). The
DEBUG level is set via `applogs.falle.se/admin` (Entra-gated) and **resets to WARN on every
redeploy** of the log infra, so re-enable DEBUG before an investigation. The exact query recipe
(SSH → VictoriaLogs) is in the maintainer's personal notes, not the repo.

- The log client (`se.falle.logsink` in `:core`) is **vendored verbatim** from
  `github.com/MagTer/logsink-clients` — never edit it only here. Change upstream first, then
  re-vendor the files with the new commit hash in their 3-line header (the rest must stay
  byte-identical to upstream).
- The shim (`github.com/MagTer/logsink-shim`) **allowlists ingest fields server-side** — a new
  per-line field the client sends also needs a shim allowlist entry, release and redeploy
  before it reaches VictoriaLogs (it is silently stripped until then).

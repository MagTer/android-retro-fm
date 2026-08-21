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
- **Cast is off in the car.** `PlayerManager` never builds a `CastPlayer` on `FEATURE_AUTOMOTIVE`,
  and `:automotive` excludes the whole `com.google.android.gms` + `com.google.android.datatransport`
  dependency (their startup components trigger a "needs Google Play services" error on head units).
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

**Album art has two sources: the station's own page first, iTunes Search as the fallback.**
`StationNowPlaying` is preferred because iTunes can only match the announced `Title - Artist`
text, and that text is sometimes not enough to identify the *record*: "Wouldn't It Be Good - Nik
Kershaw" resolves to the 1984 original while the station is playing a later remix and showing
the remix sleeve. No amount of candidate scoring reaches that — the distinguishing information
is not in the string being matched. Everything below about `ArtworkLookup` still applies; it now
runs as the second-choice source.

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

**The two sources run in parallel, never in sequence.** The station page is bounded by
`STATION_ARTWORK_BUDGET_MS` (1.2 s) inside the overall `ARTWORK_FIRST_APPLY_BUDGET_MS` (1.5 s),
so a late or disagreeing page costs nothing — the iTunes answer is already in hand when we stop
waiting. Serial would be actively harmful: the car's modem timed out every drive's *first*
iTunes lookup at 8 s during the week of 2026-08-13, and a second serial request would inherit
that. All the timings above are from a **fixed line**; field coverage will be lower, and that is
never a reason to raise the budgets.

The station's text carries HTML entities (`Yazz &amp; The Plastic Population`, seen live
2026-08-20), so the fields are unescaped before comparing — without that the ICY string's plain
`&` never matches and the cover is silently lost.

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

**Only the first lookup of a playback session fails.** All three failures were the first track
after playback started — the modem is warm for the audio stream but cold for a new host, and
nothing else pays that cost. Hence `ARTWORK_LOOKUP_ATTEMPTS = 2`. Do not raise it — a third
attempt would be pressing an API that is plainly unreachable.

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

**The mount re-announces a title mid-track.** Confirmed 2026-08-09: the same `StreamTitle`
arrives again 50–90 s into a song (`apply skipped (dedup)` when the metadata is unchanged).
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

Re-measure the band before touching it again — twice now the distribution has been the whole
argument, and the second one contradicted the first. The samples are already in the field logs
as `icy boundary` followed by `apply skipped (dedup)`; the earlier capture is a ~40-line script
that connects once with `Icy-MetaData: 1`, reads `icy-metaint` bytes, reads the length byte and
prints non-empty blocks with a timestamp. One listener connection, no polling.

**Known and unfixed: the marker is not always the end of the track.** The mount re-announces a
title 1–5 times, usually 150–290 s in, but sometimes at +7 s. Six times in that week the
display went song → logo → *the same song again* (Imagine, 2026-08-15 09:03: announced at
+0, +7, +30 and +71 s, blanked at +22 s, restored at +30 s). `All Out Of Love` was announced
four times in nine seconds. A guard that only arms the timer when the marker arrives ≥60 s into
the track would cover every observed case — the earliest *last* marker in the week is 28 s —
but it is not implemented.

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

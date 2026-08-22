package com.retrofm.android.data.config

object RetroFmConfig {
    const val STATION_NAME = "Retro FM"
    const val STATION_STRAPLINE = "Tidernas största hits"
    const val STATION_ID = 459
    const val BRAND_COLOR_HEX = "#000F2B"

    /**
     * The station's own Icecast (Mad Men Media), which is what `retrofm.se` itself plays. It
     * replaced `live-bauerse-fm.sharp-stream.com/retrofm_mp3` on 2026-08-08: Retro FM left
     * Bauer/RadioPlay, and that mount is a legacy relay whose ICY injector froze on 2026-07-31.
     *
     * Everything now-playing rides this stream: the mount sends `StreamTitle='Title - Artist'`
     * inline (`icy-metaint 16000`), live and at real track boundaries, and announces the current
     * track immediately on connect — so there is no schedule API to poll and nothing to resync
     * after a gap. See CLAUDE.md, "The station moved to a new CDN".
     *
     * 96 kbps AAC+ is the only mount for this station: every sibling station here has a 192 kbps
     * `<mount>_high`, but `retro_high` is 404. Re-check occasionally and prefer it if it appears.
     */
    const val STREAM_URL =
        "https://stream.madmenmedia.se/retro"

    // The station's lock-screen asset: same artwork as the "logo" rendition but 1200x1200.
    // The original logo URL (…/v1588755887/…/ujznetkonskklgdql1yd.png) serves only 47x40 and
    // looks blocky anywhere bigger than a list row; the CDN refuses upscaling parameters.
    const val LOGO_PNG_URL =
        "https://media.bauerradio.com/image/upload/c_crop,g_custom/v1592840994/brand_manager/stations/dwxxo0kehcboelrutfnm.png"

    /** Remote log sink ingest (ADR-011, home-server repo). Key comes via BuildConfig. */
    const val LOGSINK_INGEST_URL = "https://applogs.falle.se/ingest"

    /**
     * Durable log spool (LogsinkClient's opt-in `spoolFile`). The car's modem drops repeatedly
     * mid-drive — field logs show "network lost" several times a day — and the in-memory buffer
     * dies with the process when the car is parked while offline, which is exactly why a drive's
     * tail never reaches the sink.
     *
     * Kill switch: set false and ship. An earlier consumer-side spool took logging down
     * completely (see the client's KDoc), so this must stay trivially revocable without code
     * surgery. If the sink ever shows one line per boot and then silence, flip this first.
     */
    const val LOG_SPOOL_ENABLED = true

    const val LOG_SPOOL_FILE_NAME = "logsink-spool.ndjson"

    /**
     * Deliberately conservative for the car: the head unit's SSD is expensive to replace and
     * the hardware is slow. With these numbers a normal online drive writes **nothing**, and a
     * 30 min stretch entirely without coverage costs at most ~15 writes of ≤64 KB — under 1 MB.
     * Even a pessimistic 2 MB/day is well under a gigabyte a year.
     */
    const val LOG_SPOOL_MAX_BYTES = 64 * 1024
    const val LOG_SPOOL_MIN_WRITE_INTERVAL_MS = 120_000L
    const val LOG_SPOOL_MAX_REPLAY_LINES = 500

    /**
     * Per-track album art (see ArtworkLookup). The station's Icecast carries no artwork, so
     * covers are resolved by "artist title" against the public, keyless iTunes Search API —
     * at most one request per track boundary, with hits and misses cached.
     */
    const val ARTWORK_SEARCH_URL = "https://itunes.apple.com/search"

    /**
     * Candidates fetched per lookup. Must stay well above 1: the API's top hit is regularly a
     * karaoke rendition or a different primary artist featuring the credited one, so
     * ArtworkLookup needs a field to score rather than a single answer to trust.
     */
    const val ARTWORK_SEARCH_LIMIT = 15

    /**
     * Requested artwork rendition. Apple serves any size from the same URL by swapping this
     * path segment; 100x100 (what the API returns) is unusably small on a car display, and
     * 1200x1200 is ~280 KB per track for no visible gain over 600x600's ~90 KB.
     */
    const val ARTWORK_RENDITION = "600x600bb"

    /**
     * Hard ceiling on an artwork lookup. Generous on purpose: it does **not** gate the display —
     * ARTWORK_FIRST_APPLY_BUDGET_MS below does that — so the only thing this bounds is how long
     * a doomed request may sit on a socket. One request is in flight at a time and the next
     * track boundary cancels it, so the real ceiling is the length of a song.
     *
     * It was 5 s until 2026-08-09, and that was too tight for the car. Field logs from that
     * drive show **twelve consecutive lookups timing out** over 15 minutes while the audio
     * stream played fine — the head unit's modem was flapping ("network lost" three times
     * around that window), and every lookup had to pay DNS + TCP + TLS to a host the pool had
     * just lost. One retransmitted SYN (1 s, then 2 s, then 4 s) alone overruns a 5 s budget.
     * Payload is not the problem: iTunes gzips, so the 15-candidate response is ~3 KB on the
     * wire.
     */
    const val ARTWORK_LOOKUP_TIMEOUT_MS = 20_000L

    /**
     * Per-phase timeouts inside that ceiling, so one wedged phase can't consume the whole budget
     * while OkHttp still has another route it could have tried.
     */
    const val ARTWORK_CONNECT_TIMEOUT_MS = 8_000L
    const val ARTWORK_READ_TIMEOUT_MS = 8_000L

    /**
     * Attempts per lookup before giving up on the transport.
     *
     * Field data 2026-08-09, one recovered drive: 14 successes with a **median of 607 ms** and
     * a worst case of 931 ms, against 3 failures — and all three timed out at exactly 8 s in
     * the connect phase, on the first lookup after playback started (Madonna "Take A Bow",
     * Céline Dion "My Heart Will Go On", Tina Turner "We Don't Need Another Hero"). The car's
     * modem is warm for the audio stream but cold for a new host, and only the first request
     * pays it.
     *
     * Those songs did eventually get their cover — but only because the mount re-announces a
     * title mid-track, which re-ran the lookup ~3 minutes later. That is what "the artwork
     * appears just as the song ends, during the jingle" was. Two is the cap: a third would be
     * pressing an API that is clearly unreachable.
     */
    const val ARTWORK_LOOKUP_ATTEMPTS = 2

    /**
     * How long the second attempt waits before it tries. **Not a politeness backoff — the
     * failure it exists for lasts longer than an immediate retry does.**
     *
     * The retry was added believing it would land in under a second, on the reasoning that only
     * the first request pays a cold path. Three field cases since (Four Tops 2026-08-10, The
     * Corrs and Lynyrd Skynyrd 2026-08-11, versions 1.0.51–1.0.53) say otherwise: **3 of 3
     * retries also timed out**, each pair reading 8 s then 16 s from the same lookup start. Every
     * one began within ~10 s of a `network available` callback, so the head unit reports the link
     * up well before it can carry a fresh DNS + TCP + TLS to a host that is not in the pool, and
     * both attempts spent themselves inside that same dead window.
     *
     * 15 s puts the retry outside it while staying far inside a song: worst case is
     * connect + delay + connect ≈ 31 s against a 3–4 min track, and the display never waits —
     * ARTWORK_FIRST_APPLY_BUDGET_MS published the title at 1.5 s and the cover upgrades late.
     * Note that the failure line's elapsed time now includes this pause; the per-attempt numbers
     * are what to read for transport health.
     */
    const val ARTWORK_RETRY_DELAY_MS = 15_000L

    /**
     * How long an idle connection to the artwork API is kept for reuse. Longer than OkHttp's
     * 5 min default because track boundaries are 3–4 min apart: at the default a fair share of
     * lookups just miss the window and pay a fresh handshake on a link where that is exactly
     * what times out. At 10 min a normal drive does one handshake, not one per song.
     */
    const val ARTWORK_KEEP_ALIVE_MINUTES = 10L

    /**
     * How long a track boundary waits for its cover before publishing metadata anyway.
     *
     * The surfaces are updated ONCE per track, with the art already in hand, because updating
     * twice is visibly wrong in the car: 1.0.41 applied the title with the station logo and
     * swapped in the cover a moment later, which read as the logo flashing up and then breaking
     * (field-tested 2026-08-08). Cache hits resolve instantly, so this budget only bites on the
     * first play of a song. If it is exceeded the title goes out with the logo and the cover
     * upgrades whenever it lands — a rare, late single swap rather than one on every song.
     */
    const val ARTWORK_FIRST_APPLY_BUDGET_MS = 1_500L

    /** Entries kept in the in-memory artwork cache; a drive rarely revisits more than a few. */
    const val ARTWORK_CACHE_ENTRIES = 100

    /**
     * The station's own now-playing page (see [com.retrofm.android.data.api.StationNowPlaying]).
     *
     * Server-rendered, no JavaScript and no Blazor circuit: a plain GET carries the current
     * title, artist and album id. ~12.8 KB gzipped.
     */
    const val STATION_NOWPLAYING_URL = "https://retrofm.se/"

    /** Cover for an album id from that page. `/nowPlayingMedia/` returns real 404s, so a 200 is real. */
    const val STATION_ARTWORK_URL_PREFIX = "https://retrofm.se/nowPlayingMedia/albums/"

    /** `md` is 600x600, matching [ARTWORK_RENDITION] so both sources deliver the same size. */
    const val STATION_ARTWORK_SUFFIX = "-md.jpg"

    /**
     * Fetches allowed per track boundary before giving up on the station's page.
     *
     * The first fetch after a boundary is usually *not* the one that agrees. Measured over 13
     * boundaries on 2026-08-20: right immediately 6 times, no player block at all 3 times, still
     * showing another track 4 times — and a second fetch a few hundred ms later took it from
     * 6/13 to 10/13. A third adds almost nothing, because what has not agreed by then is a page
     * describing a different playout, not a page that is merely late.
     */
    const val STATION_NOWPLAYING_ATTEMPTS = 3

    /** Spacing between those attempts. Short: the page settles in well under a second. */
    const val STATION_NOWPLAYING_RETRY_MS = 400L

    /**
     * How much of [ARTWORK_FIRST_APPLY_BUDGET_MS] the station's page may spend before the iTunes
     * result is used instead.
     *
     * The two run in parallel, so this is not additive — it is the point at which we stop
     * waiting for the better answer and take the one already in hand. Measured page lag (upper
     * bounds, 12 agreements, 2026-08-20): 0.20, 0.23, 0.23, 0.48, 0.60, 0.68, 0.82, 0.84, 0.87,
     * 0.92, 1.51, 2.18 s — ten of thirteen inside one second. 1.2 s keeps the whole budget
     * intact for the iTunes fallback while covering the bulk of that distribution.
     *
     * Do not raise this to catch the 2.18 s tail. It buys one boundary in thirteen and pays for
     * it with title latency on every boundary where neither source answers, which is the same
     * trade ARTWORK_FIRST_APPLY_BUDGET_MS already refused.
     *
     * **These numbers are from a fixed line.** The car's modem is the real variable — every
     * drive's first iTunes lookup timed out at 8 s during the week of 2026-08-13 — so treat the
     * field coverage as lower than the measurement, and never as a reason to wait longer.
     */
    const val STATION_ARTWORK_BUDGET_MS = 1_200L

    /** Sent to the station's page so its logs show a real client rather than a bare default. */
    const val STATION_USER_AGENT = "RetroFM-Android/1.0 (+https://github.com/MagTer/android-retro-fm)"

    /**
     * Backoff schedule for stream reconnect attempts after a player error. Escalates and then
     * holds at the last value — reconnect is retried indefinitely while playback is wanted (no
     * hard give-up), so the stream self-heals whenever validated internet returns.
     */
    val RECONNECT_BACKOFF_MS = listOf(1_000L, 2_000L, 5_000L, 10_000L, 30_000L)

    /**
     * Mute the player while a server-spliced ad (see IcyAdMarker) is playing. The UI keeps
     * showing the "Reklam" countdown so the silence is explained. Deliberate decision for the
     * private friends-and-family distribution (2026-07-22); flip to false if the app is ever
     * distributed more widely, since this suppresses the station's own monetization.
     *
     * UNVERIFIED on the Mad Men Media mount (2026-08-08 switch): the AdsWizz `adw_ad` markers
     * this depends on came from Bauer's injector, and the new mount was only observed sending a
     * bare `StreamTitle`. If the new provider splices ads without markers, ad muting silently
     * does nothing and ads become audible — a behaviour change to listen for, not a code bug.
     */
    const val MUTE_ADS = true

    /**
     * Extra mute beyond the announced ad duration. Field-tested 2026-07-22: the last ~second
     * of the ad was audible after unmute at the exact announced deadline. Better to miss a
     * second of the song than hear the tail of an ad.
     */
    const val AD_MUTE_TAIL_MS = 2_000L

    /**
     * Ramp the volume back up over this long when an ad-break mute lifts, instead of a hard
     * cut from silence to full level. Quadratic curve: linear gain sounds like everything
     * happens in the last third; fraction² spreads the perceived rise across the second.
     */
    const val AD_UNMUTE_FADE_MS = 1_000L
    const val AD_UNMUTE_FADE_STEP_MS = 50L

    /**
     * Now-playing branding during a muted ad break. Goes into the media metadata itself so
     * every surface (car UI, notification, phone screen) stops claiming an artist is playing
     * while the audio is muted — field-tested confusion: raise the volume on a "song", get
     * blasted when the mute lifts.
     */
    const val AD_DISPLAY_TITLE = "Reklam"
    const val AD_DISPLAY_SUBTITLE = "Sändningen fortsätter strax"
    /**
     * Live-countdown subtitle for the ad break, used to tick the car's now-playing subtitle
     * (and the media notification) down to the unmute moment — the car can't run our own
     * countdown UI like the phone, so we update the metadata text once a second. Wording
     * mirrors the phone's R.string.ad_countdown. `%d` = whole seconds remaining.
     */
    const val AD_COUNTDOWN_FORMAT = "Sändningen börjar om %d s"
    /** Sentinel eventId for the ad-branding metadata (never collides with API event ids). */
    const val AD_EVENT_ID = -2L

    /**
     * Delay before auto-seeking a freshly connected Cast receiver to the live edge. The
     * transfer hands the receiver the local playback position, which an unseekable Icecast
     * stream can't honor — the receiver stalls until something seeks it to the live edge
     * (field-tested: a manual pause/resume unstuck it). Long enough for the LOAD to settle,
     * short enough to feel like normal connect time.
     */
    const val CAST_LIVE_EDGE_NUDGE_DELAY_MS = 2_000L

    /**
     * `contentType` announced to the Cast receiver. **Known to be wrong, deliberately left
     * alone until it can be measured — do not "fix" it blind.**
     *
     * The mount actually serves `audio/aacp` at 96 kbps (read live from its headers
     * 2026-08-22, `content-type: audio/aacp`, `icy-br: 96`), i.e. raw ADTS HE-AAC. We announce
     * MP3. Locally that is harmless — ExoPlayer sniffs — but the Default Media Receiver picks
     * its pipeline from this field, which makes it a candidate for the slow, flapping start
     * seen on a real receiver that day (38 s from transfer to stable audio).
     *
     * What stops a one-line correction is that none of the three candidates is safe on the
     * documentation alone:
     *  - `audio/mpeg` (current) — a **supported** Cast type, wrong for these bytes, and it
     *    demonstrably plays once it settles.
     *  - `audio/aacp` — accurate, but Cast's supported-media list does not mention it, nor raw
     *    ADTS at all. An unlisted type may simply be refused.
     *  - `audio/mp4; codecs="mp4a.40.5"` — the documented HE-AAC spelling, but it claims an MP4
     *    container this stream does not have.
     *
     * So this is a knob, not a conclusion: change the value, cast once, and read the field log
     * for how long it takes to reach `isPlaying=true` after `cast transfer: LOCAL -> REMOTE`.
     * A regression shows up immediately as a receiver that never leaves BUFFERING.
     *
     * This is Cast's notion of the content, not the player's: [MediaItemTree]'s `mimeType`
     * feeds ExoPlayer's local extractor choice and is a separate decision.
     */
    const val CAST_CONTENT_TYPE = "audio/mpeg"

    /**
     * How long the Cast receiver may sit "playback wanted, nothing playing" before the stream
     * is re-loaded onto it.
     *
     * The app's only recovery path used to be [androidx.media3.common.Player.Listener.onPlayerError]
     * → scheduleReconnect. A receiver that stops does **not** raise a PlaybackException — it
     * just changes state — so nothing ever retried. Two field failures ended in permanent
     * silence that way (2026-08-21 22:19, the receiver stalling 48 min into a session; and
     * 2026-08-22 07:50, a one-second network flap tearing down the session, the receiver then
     * flapping READY/IDLE three times and giving up). In both the player was left
     * `playWhenReady=true` in STATE_IDLE for hours while the phone had validated internet.
     *
     * The threshold comes from 14 days of field logs (2026-08-08…22), counting every episode
     * where playback was wanted and `isPlaying` was false. On the remote route 33 such stalls
     * recovered by themselves: **32 within 3.5 s**, and one at 29.6 s — the very first
     * hand-over to the receiver, two seconds after the initial LOAD. Then nothing until the
     * two that never recovered. 45 s sits in that empty band with >50 % margin over the one
     * long legitimate case, the same rule of thumb as [TRACK_FROZEN_AFTER_MS].
     *
     * **Deliberately remote-only.** The same 14 days hold 23 local-route stalls and *every one*
     * recovered on its own, the longest after 656 s — an 11-minute car-modem stall that came
     * back by itself. A watchdog there would only risk cutting short a recovery that is
     * demonstrably working, so the local route keeps the error-driven path it already has.
     *
     * **The upper bound rests on a single sample** (n=1 at 29.6 s), so this wants re-measuring
     * once more cast sessions have accumulated. The episodes are visible in the field logs as
     * `isPlaying=false` while `playWhenReady=true` with `route=REMOTE`.
     */
    const val CAST_STALL_RECOVER_MS = 45_000L

    /**
     * How long a stalled receiver may keep failing before the audio is handed back to the phone.
     *
     * Measured from the same stall start as [CAST_STALL_RECOVER_MS], so this is the *second*
     * step: re-load first, and only if the receiver still will not play, end the Cast session
     * so the local player takes over. A receiver that has been unplugged, or whose own network
     * is gone, cannot be revived by any number of re-loads — and silence everywhere is the
     * worst outcome, which is exactly what both field failures produced.
     *
     * **This is audible and can surprise**: the phone starts playing, possibly at a volume set
     * while it was only a remote control. That is the accepted trade against permanent silence
     * for a private build; [CAST_HANDBACK_ENABLED] turns it off without touching code.
     */
    const val CAST_STALL_HANDBACK_MS = 90_000L

    /** Kill switch for the hand-back half of the Cast stall watchdog. See above. */
    const val CAST_HANDBACK_ENABLED = true

    /**
     * How often the Cast stall watchdog re-reads the player while a stall is running.
     *
     * A stalled receiver can stay silent without emitting another event, so the escalation
     * cannot be driven by callbacks alone. The poll runs only while stalled, so it costs
     * nothing in normal playback.
     */
    const val CAST_STALL_POLL_MS = 1_000L

    /**
     * Compensation for the station's metadata lead. Icecast splices ICY metadata into the
     * byte stream at the wall-clock moment the studio switches tracks, but the matching audio
     * passes the same stream position only after the studio→encoder→ingest pipeline — so in
     * the stream, metadata runs a few constant seconds ahead of the audible transition.
     * ExoPlayer already presents ICY at the buffer-corrected playback position; this delay
     * covers only the upstream lead. Calibrated by ear 2026-07-22: with 6 s the info lagged
     * the audible change by ~6 s → the lead is ~0 for this stream. Kept as a knob; if the
     * title starts flipping N s early again, set this to N * 1000.
     */
    const val ICY_UPSTREAM_LEAD_MS = 0L

    /**
     * How long the last track's metadata may stay on an idle (not playing) session before the
     * display reverts to station branding. Mirrors the audio-side rule that a resume never
     * replays a stale buffer: when the user returns to the car after this long, the song on
     * screen is not the song a resume will play, so showing it is misinformation.
     *
     * Enforced twice, because the idle timer alone cannot be trusted in the car: coroutine
     * delay() counts on the uptime clock, which stands still while the car suspends to RAM
     * (and a frozen cached process runs nothing at all) — so a 15 min parking never advances
     * the 5 min timer. A wall-clock heartbeat (PLAYBACK_HEARTBEAT_MS) therefore records the
     * last time playback was demonstrably alive, and the same threshold is re-checked against
     * wall clock when playback resumes (see maybeHandleIdleGap).
     */
    const val TRACK_INFO_STALE_AFTER_MS = 5 * 60_000L

    /** Cadence of the wall-clock alive stamp while audio is playing (see above). */
    const val PLAYBACK_HEARTBEAT_MS = 30_000L

    /**
     * How long the display keeps a finished song's title after the mount has signalled that the
     * song is ending, before handing back to station branding.
     *
     * The mount repeats the current `StreamTitle` once shortly before the next one — an
     * end-of-track marker in all but name. The number is the empty band in the marker-to-next-
     * title distribution, and the band moved once a real week of field logs replaced the
     * original 17-transition capture.
     *
     * **Measured over 109 hand-overs on 1.0.54 (car, 2026-08-13 → 08-17, 7 h 21 min):**
     * 83 samples at 3–13 s, 22 more at 15–25 s, then **nothing between 25 s and 32 s**, then
     * 32, 43, 54, 61, 71, 83, 105, 131, 143 s. The 2026-08-10/11 capture (17 samples) saw the
     * band as 25 s → 148 s and 15 s was chosen inside the jingle range on that basis; with
     * 6× the sample the jingle range plainly reaches 25 s and 15 s sits in the middle of it.
     *
     * The cost of 15 s was field-visible and is what this value fixes: **16 of 30 branding
     * reverts had the next title arrive 1–12 s later**, i.e. the logo blinked between two
     * ordinary songs (shortest 0.98 s, 2026-08-15 09:30:24). 30 s sits in the empty band and
     * removes 13 of those 22 reverts while leaving all 9 real interruptions (≥32 s) caught.
     * Paid for it: 30 s of stale title instead of 15 s when the interruption is real.
     *
     * If the logo ever starts flashing between ordinary songs again, re-measure the band from
     * the `icy boundary` / `apply skipped (dedup)` pairs in the field logs rather than nudging
     * this — twice now the distribution has been the whole argument.
     *
     * This does **not** catch the news bulletin, which is announced by nothing at all — see
     * TRACK_FROZEN_AFTER_MS. It catches a song that ended into something long and unannounced.
     */
    const val TRACK_HANDOVER_GRACE_MS = 30_000L

    /**
     * How long one title may stay on screen *while playing* before it is treated as a frozen
     * injector and the display reverts to station branding.
     *
     * "While playing" is literal: the budget is consumed only by time the player is actually
     * playing audio, not by wall clock. It used to be wall clock from the moment the title was
     * applied, and a 3.5 min rebuffer inside one song was enough to trip it — 2026-08-15
     * 09:25:14 logged `metadata frozen — 'Black Velvet' held 492 s` for a title that was
     * correct the whole time, and the next real title arrived one second after the blank.
     * A stalled stream is not a frozen injector; it is a stalled stream.
     *
     * This is the defence that did not exist when Bauer's relay froze on 2026-07-31 and the app
     * showed "Talk Talk – It's My Life" for a week — the failure that started the whole
     * migration. The new mount carries no timestamps, so nothing but elapsed time can detect it.
     *
     * Threshold picked from measurement, not taste. Listening to the mount directly for 50 min
     * (2026-08-10, 14 consecutive tracks) the longest a real title legitimately held the display
     * was **312 s** — "Piano Man". Car logs agree: max 312 s. 8 min leaves a >50 % margin over
     * the observed worst case, so a long block cannot trip it, while a genuinely stuck injector
     * (which held for *days*) is caught within one song's length.
     *
     * **This does not fix the news bulletin, and no timeout can.** The mount emits nothing at
     * all for non-music — no empty StreamTitle, no "Nyheterna" — so a news slot looks exactly
     * like a long song. Measured: the confirmed news episode held 312 s, the same as Piano Man.
     * The distributions do not merely overlap, they coincide. Anything that blanks the display
     * during news would blank it mid-song just as often. Do not re-derive this from a smaller
     * sample and "fix" it; if the station ever starts announcing non-music, use that instead.
     */
    const val TRACK_FROZEN_AFTER_MS = 8 * 60_000L

    /**
     * Constant attenuation of the local player, aligning the stream's loudness with what
     * normalized services play at (Spotify et al. normalize to −14 LUFS; the user matched
     * volume-knob positions against Spotify on the car). The stream carries full FM broadcast
     * processing — measured 2026-08-02 over 3 min with ffmpeg ebur128: integrated −5.2 LUFS,
     * LRA 1.1 LU — so −8.8 dB lands it at −14: 10^(−8.8/20) ≈ 0.36. Applied to the local
     * ExoPlayer only (a Cast receiver keeps its own level); lossless, since ExoPlayer scales
     * in the float pipeline.
     *
     * STALE as of the 2026-08-08 CDN switch: that measurement was taken on Bauer's 192 kbps MP3
     * relay, and the app now plays Mad Men Media's 96 kbps AAC+ mount, which is a different
     * encoder behind different processing. The value is deliberately left unchanged rather than
     * guessed — re-measure (`ffmpeg -i <mount> -af ebur128 -f null -` over ~3 min) and set
     * 10^((−14 − integrated)/20), or calibrate by ear against Spotify on the car as before.
     */
    const val PLAYER_BASE_GAIN = 0.36f

    /** Buffer required before playback starts — low so pressing play feels instant. */
    const val BUFFER_FOR_PLAYBACK_MS = 1_000
    /**
     * Buffer required to resume after a stall. Deliberately high: on a poor connection this
     * gives one longer pause instead of repeated micro-stalls, and falling ~10 s behind the
     * live edge is imperceptible for radio.
     */
    const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 10_000
    /** Tighter than ExoPlayer's 8 s defaults so a dead connection surfaces as an error fast. */
    const val STREAM_CONNECT_TIMEOUT_MS = 5_000
    const val STREAM_READ_TIMEOUT_MS = 5_000
}

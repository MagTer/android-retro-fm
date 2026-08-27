package com.retrofm.android.playback

import com.retrofm.android.data.config.RetroFmConfig

/**
 * Whether the mount repeating the current `StreamTitle` actually means "this song is ending".
 *
 * The mount announces the current title **on connect as well as at changes**, and it also
 * re-announces a playing title one to five times mid-song. Only some of those repeats precede
 * the next track, but [RetroFmPlaybackService.armHandoverTimeout] treated every one of them as
 * an end-of-track marker — so the display went song → station logo → *the same song again*.
 * Measured on the car over 2026-08-22 → 08-27 (1.0.60): **5 of 11 hand-over reverts were false**
 * that way, four of them on 08-27 alone, the logo standing for 26–181 s mid-song.
 *
 * Two of the three shapes behind those are recognisable by age alone, which is what this gate
 * rejects:
 *
 *  - **The connect-time announcement's echo.** A fresh open announces the title, and a second
 *    block repeats it seconds later — "Heart Of Gold" was announced 4 s after the stream opened
 *    and repeated 3 s after that (2026-08-27 15:17:04), which then blanked a song that had
 *    barely started.
 *  - **The reconnect.** When the modem drops mid-song the stream reopens and the mount
 *    re-announces what is already on screen, which is not new information at all. "Love Is All
 *    Around" reconnected 76 s into the song and its connect announcement (2026-08-27 15:23:02,
 *    7 s after the reopen) read as the song ending.
 *
 * Hence the clock restarts on **both** a title change and a stream re-open: what it measures is
 * how long the title has held the display *on one unbroken connection*. Playing time, not wall
 * clock, for the same reason as [TrackPlayingClock] — a parked car must not age the marker.
 *
 * The third shape is a genuine mid-song re-announcement far into the track ("Joe Le Taxi" at
 * +98 s, "You Get What You Give" at +124 s) and **this gate does not catch it**: those are
 * indistinguishable by timing from a real end-of-track marker, which arrived at +59, +123,
 * +137, +186 s and up in the same capture. Two of the five false reverts therefore remain.
 *
 * Not thread-safe; every caller is on the Main dispatcher.
 */
internal class EndOfTrackMarker(
    private val minAgeMs: Long,
    now: () -> Long = System::currentTimeMillis,
) {

    private val clock = TrackPlayingClock(now)

    /**
     * Playing time the current title has held the display on one unbroken stream.
     *
     * Banks up to *now* before answering. The clock is otherwise only advanced by the playback
     * heartbeat, which runs every [RetroFmConfig.PLAYBACK_HEARTBEAT_MS] — and a marker arrives
     * whenever the mount feels like it, not on that cadence. Reading the raw accumulator would
     * under-report the age by up to a whole heartbeat, turning a 25 s floor into an unpredictable
     * 25–55 s one. (The freeze defence has no such problem: it is checked from inside the
     * heartbeat, one line after its own tick.)
     */
    fun titleAgeMs(): Long {
        clock.tick()
        return clock.playingMs
    }

    /** Audio started; the heartbeat drives [tick] and [playbackStopped] from `isPlaying`. */
    fun playbackStarted() = clock.start()

    fun tick() = clock.tick()

    fun playbackStopped() = clock.stop()

    /** A different title is now displayed — its age starts here. */
    fun titleChanged() = clock.restart()

    /**
     * The stream was (re)opened, so the mount's next announcement is its connect-time one.
     * Same reset as a title change: whatever is on screen is, as far as this connection knows,
     * brand new — see the class comment for the two field cases this exists for.
     */
    fun streamReopened() = clock.restart()

    /** True when a repeat of the current title is old enough to be the song's end. */
    fun isEndOfTrack(): Boolean = titleAgeMs() >= minAgeMs
}

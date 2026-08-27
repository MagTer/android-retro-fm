package com.retrofm.android.playback

import com.retrofm.android.data.config.RetroFmConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replays the 2026-08-27 field cases where the car went song → station logo → *the same song
 * again*. Each test is one episode from the car's own log; the gate's only job is to tell the
 * mount's connect announcement from the marker that really precedes the next song, so the cases
 * that must still pass through are pinned just as hard as the ones that must not.
 */
class EndOfTrackMarkerTest {

    /** Wall clock under test control; ms. */
    private var now = 1_000_000L
    private val marker = EndOfTrackMarker(RetroFmConfig.TRACK_HANDOVER_MIN_AGE_MS) { now }

    /** Playing time passing, with the heartbeat banking it as the service does. */
    private fun play(ms: Long) {
        now += ms
        marker.tick()
    }

    private fun streamOpens() {
        marker.streamReopened()
        marker.playbackStarted()
    }

    /**
     * "Heart Of Gold", 2026-08-27 15:17. The stream opened at 15:16:49, announced the title at
     * 15:17:00 and repeated it at 15:17:04. That repeat blanked the display 30 s later and the
     * song came back at 15:20:06 — 152 s of station logo over a song that was still playing.
     */
    @Test
    fun `the connect announcement's echo is not an end-of-track marker`() {
        streamOpens()
        play(1_000)
        marker.titleChanged() // 15:17:00, the announcement itself
        play(3_000) // 15:17:04, the echo

        assertFalse(marker.isEndOfTrack())
    }

    /**
     * "Love Is All Around", 2026-08-27 15:23. The song had been playing 80 s when the modem
     * dropped; the stream reopened at 15:22:58 and the mount re-announced what was already on
     * screen. The title was old, the *connection* was not — which is why the clock has to
     * restart on a re-open and not only on a title change.
     */
    @Test
    fun `a reconnect's announcement is not an end-of-track marker even mid-song`() {
        streamOpens()
        marker.titleChanged()
        play(80_000) // the song plays

        marker.playbackStopped() // modem drops
        now += 42_000 // 15:22:16 -> 15:22:58, no audio: must not age anything
        streamOpens()
        play(7_000) // 15:23:02, the connect announcement

        assertFalse(marker.isEndOfTrack())
    }

    /**
     * "Circle Of Life", 2026-08-27 08:08:01 — repeated 21 s in, blanked at 08:08:31, back at
     * 08:11:32. The shortest real song of the whole capture ran 3 min.
     */
    @Test
    fun `a repeat 21 seconds into a song is not an end-of-track marker`() {
        streamOpens()
        marker.titleChanged()
        play(21_000)

        assertFalse(marker.isEndOfTrack())
    }

    /**
     * "The Riddle", 2026-08-22 17:14:18 — the earliest *correct* marker in the capture, at 59 s.
     * The next title arrived 53 s later, so the revert was right. Nothing about this fix may
     * cost it.
     */
    @Test
    fun `the earliest correct marker still arms the timer`() {
        streamOpens()
        marker.titleChanged()
        play(59_000)

        assertTrue(marker.isEndOfTrack())
    }

    /**
     * The residual, pinned so it is not mistaken for covered: "Joe Le Taxi" repeated 98 s in
     * and that was *not* the end of the song, but a real marker arrived at 123 s in the same
     * capture. Timing cannot separate them, so this one still gets through — and the display
     * still blinks. See EndOfTrackMarker's comment.
     */
    @Test
    fun `a genuine mid-song re-announcement late in the track is not caught`() {
        streamOpens()
        marker.titleChanged()
        play(98_000)

        assertTrue(marker.isEndOfTrack())
    }

    /**
     * The marker arrives whenever the mount sends it, not on the heartbeat's 30 s cadence. If
     * the gate read the raw accumulator it would see 0 s here and suppress a marker that is
     * comfortably past the floor — the 25 s floor would silently become a 25–55 s one.
     */
    @Test
    fun `a marker between two heartbeats is judged on its real age`() {
        streamOpens()
        marker.titleChanged()
        play(RetroFmConfig.PLAYBACK_HEARTBEAT_MS) // one heartbeat banked
        now += 30_000 // 30 s more playing, no tick yet

        assertTrue(marker.isEndOfTrack())
    }

    /**
     * The car parks mid-song and wakes hours later. Wall clock would call every repeat a marker;
     * playing time is what the gate counts, exactly as [TrackPlayingClock] does for the freeze
     * defence.
     */
    @Test
    fun `time parked does not age the marker`() {
        streamOpens()
        marker.titleChanged()
        play(5_000)
        marker.playbackStopped()
        now += 6 * 60 * 60_000L // parked overnight

        marker.playbackStarted()
        play(2_000)

        assertFalse(marker.isEndOfTrack())
    }
}

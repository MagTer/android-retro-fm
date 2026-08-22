package com.retrofm.android.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the field case from 2026-08-22 and the reason the held frame expires.
 *
 * The mount announced "Material Girl" when the stream opened at 10:53:51, four seconds before
 * the play press. The frame was dropped, and because the mount does not announce again until
 * the track ends the display sat on the station logo for the whole song — the title finally
 * appeared for two seconds at 10:55:54, as it ended.
 *
 * [RetroFmPlaybackService] has no test harness, which is why the rule lives in its own class.
 */
class PendingIcyFrameTest {

    private var clock = 0L
    private fun frame() = PendingIcyFrame<String>(maxAgeMs = 30_000, now = { clock })

    /** The measured case: announcement, four seconds, play press. */
    @Test
    fun `a frame held briefly before the play press is replayed`() {
        val held = frame()
        held.hold("Material Girl - Madonna")
        clock += 4_000
        assertEquals("Material Girl - Madonna", held.take())
    }

    /**
     * Resuming seeks to the live edge, so an old frame describes audio already skipped past.
     * A confidently wrong title is worse than branding.
     */
    @Test
    fun `a stale frame is discarded rather than shown`() {
        val held = frame()
        held.hold("Material Girl - Madonna")
        clock += 30_001
        assertNull(held.take())
    }

    /** Exactly at the bound still counts — the limit is inclusive. */
    @Test
    fun `a frame at the age limit is still used`() {
        val held = frame()
        held.hold("Kyrie - Mr. Mister")
        clock += 30_000
        assertEquals("Kyrie - Mr. Mister", held.take())
    }

    /** Drained from both the play press and the first isPlaying; the second must find nothing. */
    @Test
    fun `a frame is replayed at most once`() {
        val held = frame()
        held.hold("Kyrie - Mr. Mister")
        assertEquals("Kyrie - Mr. Mister", held.take())
        assertNull(held.take())
    }

    /** A stale take must not leave the frame behind for a later, even staler replay. */
    @Test
    fun `an expired frame is dropped, not left in the slot`() {
        val held = frame()
        held.hold("Material Girl - Madonna")
        clock += 60_000
        assertNull(held.take())
        clock = 0
        assertNull(held.take())
    }

    /** Several frames can arrive before the press; only the newest describes what plays. */
    @Test
    fun `a newer frame replaces an older one`() {
        val held = frame()
        held.hold("Material Girl - Madonna")
        clock += 5_000
        held.hold("Kyrie - Mr. Mister")
        clock += 1_000
        assertEquals("Kyrie - Mr. Mister", held.take())
    }

    /** Stopping playback forgets it: nothing held may survive into a later session. */
    @Test
    fun `clearing forgets the held frame`() {
        val held = frame()
        held.hold("Material Girl - Madonna")
        held.clear()
        assertNull(held.take())
    }

    @Test
    fun `taking from an empty slot is harmless`() {
        assertNull(frame().take())
    }
}

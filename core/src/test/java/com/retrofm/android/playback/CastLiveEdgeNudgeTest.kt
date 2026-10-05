package com.retrofm.android.playback

import com.retrofm.android.playback.CastLiveEdgeNudge.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Replays the 2026-10-05 field transfers. The case worth pinning is the flap: a one-shot check
 * at 2 s landed inside the receiver's brief READY window every time and never fired.
 */
class CastLiveEdgeNudgeTest {

    private val nudge = CastLiveEdgeNudge(armAfterMs = 2_000, watchForMs = 10_000)

    /** (ms since transfer, isPlaying) as the receiver reported it, polled every 250 ms. */
    private fun firstVerdict(samples: List<Pair<Long, Boolean>>, playWhenReady: Boolean = true) =
        samples.asSequence()
            .map { (t, playing) ->
                t to nudge.observe(t, remote = true, playWhenReady = playWhenReady, playing = playing)
            }
            .firstOrNull { it.second != Verdict.WAIT }

    /** 1.0.70, 23:22:18: READY at +1.71 s, BUFFERING at +2.20 s, READY on its own at +7.73 s. */
    private fun fieldTransfer(atMs: Long): Boolean = atMs in 1_711 until 2_196 || atMs >= 7_731

    @Test
    fun `the flap that hid from a single sample at 2 s is caught on the next poll`() {
        // The old nudge's one look: playing, so it did nothing.
        assertEquals(Verdict.WAIT, nudge.observe(2_000, true, true, fieldTransfer(2_000)))
        val samples = (0L..12_000L step 250).map { it to fieldTransfer(it) }
        assertEquals(2_250L to Verdict.NUDGE, firstVerdict(samples))
    }

    @Test
    fun `nothing is done before the LOAD has had time to settle`() {
        assertEquals(Verdict.WAIT, nudge.observe(1_999, true, true, playing = false))
    }

    /** August's shape: the receiver never started at all — the original reason for the nudge. */
    @Test
    fun `a receiver that never starts is nudged at the arming time`() {
        val samples = (0L..12_000L step 250).map { it to false }
        assertEquals(2_000L to Verdict.NUDGE, firstVerdict(samples))
    }

    @Test
    fun `a receiver that plays cleanly is left alone and the watch ends`() {
        val samples = (0L..12_000L step 250).map { it to (it >= 1_700) }
        assertEquals(10_250L to Verdict.DONE, firstVerdict(samples))
    }

    /** Cast first, play later: the resume-from-pause seek starts it, so there is nothing to do. */
    @Test
    fun `a paused transfer ends the watch at once`() {
        assertEquals(Verdict.DONE, nudge.observe(0, true, playWhenReady = false, playing = false))
    }

    @Test
    fun `a stall after the window is the watchdog's, not this`() {
        assertEquals(Verdict.DONE, nudge.observe(10_001, true, true, playing = false))
    }

    @Test
    fun `leaving the receiver ends the watch`() {
        assertEquals(Verdict.DONE, nudge.observe(3_000, remote = false, playWhenReady = true, playing = false))
    }
}

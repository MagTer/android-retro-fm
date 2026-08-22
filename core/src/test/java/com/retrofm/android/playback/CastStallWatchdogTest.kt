package com.retrofm.android.playback

import com.retrofm.android.playback.CastStallWatchdog.Action
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the two field failures that produced permanent silence while casting, and the flapping
 * that would defeat a naive implementation of the fix.
 *
 * [PlayerManager] has no test harness, which is why the decision was extracted at all.
 */
class CastStallWatchdogTest {

    private var clock = 0L
    private fun watchdog(handBack: Boolean = true) =
        CastStallWatchdog(
            recoverAfterMs = 45_000,
            handBackAfterMs = 90_000,
            handBackEnabled = handBack,
            now = { clock }
        )

    /** Playing normally on the receiver: nothing is ever due. */
    @Test
    fun `a playing receiver is never acted on`() {
        val w = watchdog()
        repeat(10) {
            clock += 30_000
            w.update(remote = true, playWhenReady = true, playing = true)
            assertEquals(Action.NONE, w.due())
        }
    }

    /** Field data: 32 of 33 remote stalls recovered within 3.5 s. None of them may trip this. */
    @Test
    fun `a short stall that recovers is left alone`() {
        val w = watchdog()
        w.update(remote = true, playWhenReady = true, playing = false)
        clock += 3_500
        assertEquals(Action.NONE, w.due())
        w.update(remote = true, playWhenReady = true, playing = true)
        clock += 600_000
        assertEquals(Action.NONE, w.due())
    }

    /** 2026-08-21 22:19: the receiver stalled mid-session and never came back. */
    @Test
    fun `a receiver that stays silent is re-loaded and then handed back`() {
        val w = watchdog()
        w.update(remote = true, playWhenReady = true, playing = false)

        clock += 44_000
        assertEquals(Action.NONE, w.due())

        clock += 2_000                                   // 46 s
        assertEquals(Action.RELOAD, w.due())
        assertEquals("re-load fires once", Action.NONE, w.due())

        clock += 20_000                                  // 66 s — hand-back not due yet
        assertEquals(Action.NONE, w.due())

        clock += 30_000                                  // 96 s
        assertEquals(Action.HAND_BACK, w.due())
    }

    /**
     * 2026-08-22 07:50, the case that decides the design: the receiver flapped READY/IDLE
     * three times in two seconds. A watchdog that restarts its clock on each transition never
     * fires, and the app stays silent — which is exactly what shipped.
     */
    @Test
    fun `momentary state churn does not restart the stall clock`() {
        val w = watchdog()
        w.update(remote = true, playWhenReady = true, playing = false)
        repeat(3) {
            clock += 1_000
            w.update(remote = true, playWhenReady = true, playing = false)
        }
        clock += 42_000                                  // 45 s since the FIRST stalled report
        assertEquals(Action.RELOAD, w.due())
    }

    /** A real resume clears the stall; a later one starts counting from scratch. */
    @Test
    fun `playing again resets the clock`() {
        val w = watchdog()
        w.update(remote = true, playWhenReady = true, playing = false)
        clock += 40_000
        w.update(remote = true, playWhenReady = true, playing = true)
        w.update(remote = true, playWhenReady = true, playing = false)
        clock += 40_000                                  // only 40 s into the *new* stall
        assertEquals(Action.NONE, w.due())
        clock += 5_000
        assertEquals(Action.RELOAD, w.due())
    }

    /** The user pausing is not a stall, however long it lasts. */
    @Test
    fun `a paused player is not a stall`() {
        val w = watchdog()
        w.update(remote = true, playWhenReady = false, playing = false)
        clock += 600_000
        assertEquals(Action.NONE, w.due())
    }

    /**
     * The local route is deliberately excluded: all 23 local stalls in the same 14 days
     * recovered on their own, the longest after 656 s.
     */
    @Test
    fun `the local route is never acted on`() {
        val w = watchdog()
        w.update(remote = false, playWhenReady = true, playing = false)
        clock += 700_000
        assertEquals(Action.NONE, w.due())
    }

    /** The kill switch leaves the re-load in place and only drops the audible half. */
    @Test
    fun `the hand-back can be disabled without losing the re-load`() {
        val w = watchdog(handBack = false)
        w.update(remote = true, playWhenReady = true, playing = false)
        clock += 45_000
        assertEquals(Action.RELOAD, w.due())
        clock += 600_000
        assertEquals(Action.NONE, w.due())
    }
}

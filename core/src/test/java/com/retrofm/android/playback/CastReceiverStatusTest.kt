package com.retrofm.android.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Pins the rendering of the receiver's own account of a Cast failure.
 *
 * This is a diagnosability guard, like [AlbumArtDescribeTest]: the value of these lines is that
 * a future investigation can tell the failure modes apart from the log alone. The 2026-09-21
 * Cast outage is the case — `cast receiver silent 45 s` was all the record held, so whether the
 * receiver had failed to fetch the stream or was still trying had to be established by probing
 * the station's CDN from the dev host instead.
 *
 * Plain JUnit, no Robolectric: [CastReceiverStatus] is pure by construction, which is what lets
 * it be tested at all — [PlayerManager] has no harness and `:automotive` cannot load anything
 * that touches gms.
 */
class CastReceiverStatusTest {

    // Values from com.google.android.gms.cast.MediaStatus, play-services-cast 22.1.0.
    private val idle = 1
    private val playing = 2
    private val buffering = 4
    private val reasonNone = 0
    private val reasonError = 4

    @Test
    fun `an idle receiver carries the reason it went idle`() {
        assertEquals(
            "playerState=IDLE idleReason=ERROR",
            CastReceiverStatus.describe(idle, reasonError)
        )
    }

    /**
     * The distinction the escalation lines exist to make: a receiver that gave up (IDLE/ERROR)
     * versus one still trying (BUFFERING). Both were merely "silent" to the watchdog.
     */
    @Test
    fun `a buffering receiver reads differently from a failed one`() {
        assertEquals("playerState=BUFFERING", CastReceiverStatus.describe(buffering, reasonNone))
        assertNotEquals(
            CastReceiverStatus.describe(buffering, reasonNone),
            CastReceiverStatus.describe(idle, reasonError)
        )
    }

    /**
     * The idle reason is only set by the SDK while the receiver is IDLE. Rendering it in any
     * other state would put a stale cause in the log — a confidently wrong answer that outlives
     * a visible gap.
     */
    @Test
    fun `the idle reason is suppressed outside the idle state`() {
        assertEquals("playerState=PLAYING", CastReceiverStatus.describe(playing, reasonError))
    }

    /** An unknown code says so rather than falling back to something plausible. */
    @Test
    fun `unrecognised codes render as themselves`() {
        assertEquals("playerState=?99", CastReceiverStatus.describe(99, reasonNone))
        assertEquals("playerState=IDLE idleReason=?7", CastReceiverStatus.describe(idle, 7))
    }

    @Test
    fun `an error renders type, reason and detailed code`() {
        assertEquals(
            "LOAD_FAILED/GENERIC_LOAD_ERROR code=905",
            CastReceiverStatus.describeError("LOAD_FAILED", "GENERIC_LOAD_ERROR", 905)
        )
    }

    /**
     * Every field is optional in the Cast protocol. A receiver that reports nothing must read as
     * "unknown" — never as a fabricated code, and never as an empty line that looks like success.
     */
    @Test
    fun `missing fields are dropped rather than invented`() {
        assertEquals("LOAD_FAILED", CastReceiverStatus.describeError("LOAD_FAILED", null, null))
        assertEquals("unknown code=905", CastReceiverStatus.describeError(null, "", 905))
        assertEquals("unknown", CastReceiverStatus.describeError(null, null, null))
        assertEquals("unknown", CastReceiverStatus.describeError("", "", null))
    }
}

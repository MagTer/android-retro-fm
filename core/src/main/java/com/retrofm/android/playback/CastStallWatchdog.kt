package com.retrofm.android.playback

/**
 * Decides when a silent Cast receiver has been silent long enough to act on.
 *
 * The app's only recovery path was `onPlayerError` → reconnect, and a receiver that stops
 * raises no [androidx.media3.common.PlaybackException] — it just changes state. Two field
 * failures ended in permanent silence that way (2026-08-21 22:19 and 2026-08-22 07:50), the
 * player left `playWhenReady=true` in STATE_IDLE for hours while the phone had working
 * internet. See [com.retrofm.android.data.config.RetroFmConfig.CAST_STALL_RECOVER_MS] for the
 * measurement behind the thresholds and for why the local route is deliberately excluded.
 *
 * This lives in its own class because [PlayerManager] has no test harness — the same reason
 * [TrackPlayingClock] exists. The rule worth pinning is not the arithmetic but the arming
 * discipline: **a stall that is already running is never restarted by state churn.** The
 * 2026-08-22 failure flapped READY/IDLE three times in two seconds, and a watchdog that reset
 * its clock on every transition would have waited forever on a receiver that was never going
 * to play.
 *
 * Not thread-safe; every caller is on the Main dispatcher.
 */
internal class CastStallWatchdog(
    private val recoverAfterMs: Long,
    private val handBackAfterMs: Long,
    private val handBackEnabled: Boolean,
    private val now: () -> Long = System::currentTimeMillis
) {

    enum class Action {
        /** Nothing due yet. */
        NONE,

        /** Re-load the stream onto the receiver. */
        RELOAD,

        /** Give up on the receiver and let the local player take over. */
        HAND_BACK
    }

    private var stalledSince: Long? = null
    private var reloaded = false

    /** How long the current stall has lasted, or 0 when nothing is stalled. */
    val stalledMs: Long get() = stalledSince?.let { (now() - it).coerceAtLeast(0) } ?: 0

    /**
     * Feed the player's current condition. A stall is "the receiver has playback, the user
     * wants it, and nothing is coming out" — the local route is never a stall here, because
     * every local stall in the field recovered on its own.
     */
    fun update(remote: Boolean, playWhenReady: Boolean, playing: Boolean) {
        if (!(remote && playWhenReady && !playing)) {
            stalledSince = null
            reloaded = false
            return
        }
        // Only the FIRST stalled observation sets the clock. Re-arming here is the bug.
        if (stalledSince == null) stalledSince = now()
    }

    /**
     * What is due right now. Each step fires at most once per stall: [Action.RELOAD] when the
     * receiver has been silent for `recoverAfterMs`, then [Action.HAND_BACK] at
     * `handBackAfterMs` measured from the same stall start — so the hand-back is always the
     * second attempt, never a competing first one.
     */
    fun due(): Action {
        val since = stalledSince ?: return Action.NONE
        val elapsed = now() - since
        if (!reloaded) {
            if (elapsed < recoverAfterMs) return Action.NONE
            reloaded = true
            return Action.RELOAD
        }
        if (!handBackEnabled || elapsed < handBackAfterMs) return Action.NONE
        // One hand-back per stall: clear the clock so a receiver that stays silent afterwards
        // starts a fresh cycle rather than ending the session again every poll.
        stalledSince = null
        reloaded = false
        return Action.HAND_BACK
    }
}

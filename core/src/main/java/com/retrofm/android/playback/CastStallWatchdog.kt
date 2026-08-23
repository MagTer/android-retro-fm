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
 * to play. The second is that the hand-back is timed from the **re-load**, because the caller's
 * poll can be arbitrarily late — see [due].
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
    private var reloadedAt: Long? = null

    /**
     * How long the receiver gets to answer a re-load before the audio is taken off it.
     *
     * Derived so that a *punctual* escalation is unchanged: a stall observed at t=0 re-loads
     * at [recoverAfterMs] and hands back at [handBackAfterMs], exactly as before. What it
     * fixes is the late one — see [due].
     */
    private val handBackGraceMs = (handBackAfterMs - recoverAfterMs).coerceAtLeast(0)

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
            reloadedAt = null
            return
        }
        // Only the FIRST stalled observation sets the clock. Re-arming here is the bug.
        if (stalledSince == null) stalledSince = now()
    }

    /**
     * What is due right now. Each step fires at most once per stall: [Action.RELOAD] when the
     * receiver has been silent for `recoverAfterMs`, then [Action.HAND_BACK] once the re-load
     * has had [handBackGraceMs] to work.
     *
     * **The hand-back is timed from the re-load, not from the stall start, and that is the
     * load-bearing part.** It used to be measured from the stall start, on the assumption that
     * the caller's poll is punctual. It is not: on 2026-08-23 11:25:12 a receiver stalled on
     * the phone and the RELOAD did not fire until **139 s**, not 45 — the 1 s poll is a plain
     * coroutine `delay`, and while casting the phone holds no wake lock and plays nothing
     * itself, so nothing keeps the CPU awake to service it. By the time the re-load was sent,
     * 90 s had long passed, and the *next* tick one second later would have ended the Cast
     * session. The receiver would have got one second to answer instead of forty-five. It only
     * escaped because that re-load happened to work (`isPlaying=true` 1 s later).
     *
     * So `recoverAfterMs` is a floor on how long a stall must last, never a bound on when this
     * is asked — the escalation must not assume the two are the same.
     */
    fun due(): Action {
        val since = stalledSince ?: return Action.NONE
        val reloaded = reloadedAt
        if (reloaded == null) {
            if (now() - since < recoverAfterMs) return Action.NONE
            reloadedAt = now()
            return Action.RELOAD
        }
        if (!handBackEnabled || now() - reloaded < handBackGraceMs) return Action.NONE
        // One hand-back per stall: clear the clock so a receiver that stays silent afterwards
        // starts a fresh cycle rather than ending the session again every poll.
        stalledSince = null
        reloadedAt = null
        return Action.HAND_BACK
    }
}

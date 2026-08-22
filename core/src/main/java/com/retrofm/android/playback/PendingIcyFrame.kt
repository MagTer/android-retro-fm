package com.retrofm.android.playback

import android.os.SystemClock

/**
 * Holds one ICY frame that arrived before playback was requested, so it can be honoured at the
 * play press instead of thrown away.
 *
 * The mount announces the current title **once when the stream opens** and then not again until
 * the track ends. `onMetadata` drops frames while `playWhenReady` is false — correct in itself,
 * since a paused player keeps buffering a tail whose frames must not flip the display — but when
 * the stream is opened *fresh* a few seconds before the user presses play, that connect-time
 * announcement is the only description of the song now playing. Losing it costs the whole track:
 * on 2026-08-22 the display sat on the station logo through all of "Material Girl" and showed it
 * for two seconds at the end, when the end-of-track marker finally arrived two minutes later.
 *
 * **The age bound is the part that matters.** Resuming from a pause seeks to the live edge
 * (`PlayGatedPlayer`), so a frame held for long enough describes audio the player has already
 * skipped past — replaying it would put a stale title on screen with confidence. Held frames
 * therefore expire; the measured case needed 4 s.
 *
 * Generic over the frame type so the rule can be tested without Media3 or an Android runtime —
 * [RetroFmPlaybackService] has no test harness, the same reason [TrackPlayingClock] exists.
 *
 * Not thread-safe; every caller is on the Main dispatcher.
 */
internal class PendingIcyFrame<T>(
    private val maxAgeMs: Long,
    private val now: () -> Long = SystemClock::elapsedRealtime
) {

    private var frame: T? = null
    private var heldAt = 0L

    /** Keep [value] instead of dropping it. A newer frame always replaces an older one. */
    fun hold(value: T) {
        frame = value
        heldAt = now()
    }

    /**
     * The held frame if it is still fresh enough to describe what is about to play, else null.
     * Either way the slot is emptied, so a frame is replayed at most once.
     */
    fun take(): T? {
        val held = frame ?: return null
        frame = null
        return held.takeIf { now() - heldAt <= maxAgeMs }
    }

    /** Forget anything held — playback stopped, or the route changed under us. */
    fun clear() {
        frame = null
    }
}

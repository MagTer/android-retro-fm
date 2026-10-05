package com.retrofm.android.playback

/**
 * Decides whether a receiver that has just been handed playback needs a seek to the live edge.
 *
 * A seek to the live edge on a receiver that has the stream loaded produces audio in ~0.6 s
 * (2 of 2, 2026-10-05, the "resume from pause" path). A receiver LOADed with autoplay instead
 * goes READY ~1.7 s after the LOAD, falls back to BUFFERING ~0.5 s later and only finds its
 * own way out at ~+7.5 s (2 of 2 the same evening, 1.0.70). The nudge exists to cut that short.
 *
 * **It must watch, not sample.** The first version checked `isPlaying` once at 2 s, and the
 * receiver's half-second READY window straddled that mark in 5 of 5 transfers across 1.0.68–70,
 * so it never fired once. Here every observation from [armAfterMs] to [watchForMs] counts, and
 * the first one that is not playing is the one acted on — the flap cannot hide from it.
 *
 * At most one nudge per transfer: the caller stops on [Verdict.NUDGE] and on [Verdict.DONE].
 * A receiver still silent after the window belongs to [CastStallWatchdog], not to this.
 *
 * Pure, because the service it serves has no test harness — the same reason
 * [CastStallWatchdog] and [TrackPlayingClock] exist.
 */
internal class CastLiveEdgeNudge(
    private val armAfterMs: Long,
    private val watchForMs: Long
) {

    enum class Verdict {
        /** Keep observing. */
        WAIT,

        /** Seek the receiver to the live edge now, then stop observing. */
        NUDGE,

        /** Stop observing; nothing to do. */
        DONE
    }

    /**
     * One observation, [sinceTransferMs] after the receiver took over.
     *
     * Not wanted (`playWhenReady` false — cast first, press play later) or no longer remote ends
     * the watch at once: a paused receiver is started by the resume-from-pause seek, which
     * already lands on the live edge.
     */
    fun observe(
        sinceTransferMs: Long,
        remote: Boolean,
        playWhenReady: Boolean,
        playing: Boolean
    ): Verdict = when {
        !remote || !playWhenReady -> Verdict.DONE
        sinceTransferMs > watchForMs -> Verdict.DONE
        sinceTransferMs < armAfterMs -> Verdict.WAIT
        !playing -> Verdict.NUDGE
        else -> Verdict.WAIT
    }
}

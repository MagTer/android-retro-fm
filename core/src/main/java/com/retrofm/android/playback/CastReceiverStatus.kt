package com.retrofm.android.playback

/**
 * Renders the Cast receiver's own account of itself into one short log line.
 *
 * Why this exists: on 2026-09-21 a Cast session died within 90 s on every attempt, and the
 * field log could say only that the receiver was silent — `cast receiver silent 45 s` and then
 * a hand-back, with nothing about *why*. The cause turned out to be upstream (the station's new
 * Revma token authority refusing 43 of 45 connection attempts with HTTP 503, measured from the
 * dev host that morning), and it took a direct probe of the CDN to establish that, because
 * nothing in the app recorded the receiver's side of the failure. A receiver that cannot fetch
 * the stream and a receiver that is not being asked to play look identical from here otherwise.
 *
 * Pure by construction: Ints and Strings in, one String out, **no `com.google.android.gms`
 * types**. That is deliberate twice over — `:automotive` strips the whole gms group, so nothing
 * that might be loaded on that build may reference it, and [PlayerManager] has no test harness,
 * so the rendering belongs somewhere `:core:testDebugUnitTest` can reach (the same reason
 * [TrackPlayingClock] and [CastStallWatchdog] exist). The gms side lives in [CastReceiverProbe].
 */
internal object CastReceiverStatus {

    /**
     * `playerState=IDLE idleReason=ERROR`, or just `playerState=BUFFERING`.
     *
     * The idle reason is only rendered when the receiver is actually IDLE, because that is the
     * only state in which the Cast SDK sets it — carrying a stale one everywhere else would put
     * a confident wrong cause in the log, which is worse than a short line.
     */
    fun describe(playerState: Int, idleReason: Int): String {
        val state = playerState(playerState)
        if (playerState != PLAYER_STATE_IDLE) return "playerState=$state"
        return "playerState=$state idleReason=${idleReason(idleReason)}"
    }

    /**
     * `LOAD_FAILED/GENERIC_LOAD_ERROR code=905`, dropping whatever the receiver left out.
     *
     * Every field is optional in the Cast protocol, so all three can be absent; the result is
     * then `unknown` rather than a fabricated code. A receiver that reports nothing is itself
     * the finding — see the note on degrading to "unknown, because X" in the working agreement.
     */
    fun describeError(type: String?, reason: String?, detailedCode: Int?): String {
        val what = listOfNotNull(type?.ifBlank { null }, reason?.ifBlank { null })
            .joinToString("/")
            .ifEmpty { "unknown" }
        return if (detailedCode == null) what else "$what code=$detailedCode"
    }

    /**
     * Values read from `com.google.android.gms.cast.MediaStatus` in play-services-cast 22.1.0
     * (`javap -constants`, 2026-09-21) rather than copied from the documentation. They are
     * duplicated here only because naming the constants would drag gms into a class that must
     * stay loadable on `:automotive`; if the SDK ever renumbers them, an unknown code renders as
     * `?N` and says so instead of silently reading as something else.
     */
    private fun playerState(state: Int): String = when (state) {
        0 -> "UNKNOWN"
        PLAYER_STATE_IDLE -> "IDLE"
        2 -> "PLAYING"
        3 -> "PAUSED"
        4 -> "BUFFERING"
        5 -> "LOADING"
        else -> "?$state"
    }

    private fun idleReason(reason: Int): String = when (reason) {
        0 -> "NONE"
        1 -> "FINISHED"
        2 -> "CANCELED"
        3 -> "INTERRUPTED"
        4 -> "ERROR"
        else -> "?$reason"
    }

    private const val PLAYER_STATE_IDLE = 1
}

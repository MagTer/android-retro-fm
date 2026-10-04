package com.retrofm.android.telemetry

/**
 * Where the three declared metrics go. Telemetry provides the OpenTelemetry-backed one; with
 * telemetry off it is [NONE] and nothing is recorded.
 *
 * Every attribute value is from a small closed set — the edge refuses a source past 500
 * distinct series a day, and a histogram costs one series per bucket. Never a URL, never an
 * exception message, never a device or person.
 */
interface PlaybackMetricSink {
    /** `retrofm_stream_connect_seconds`: play wanted → first audio. */
    fun connect(seconds: Double, cause: String, route: String)

    /** `retrofm_rebuffer_total`: audio stopped mid-stream while playback was still wanted. */
    fun rebuffer(route: String)

    /** `retrofm_playback_errors_total`, by [reason] and [httpClass] (`4xx`, `5xx`, `none`). */
    fun error(reason: String, httpClass: String)

    companion object {
        val NONE = object : PlaybackMetricSink {
            override fun connect(seconds: Double, cause: String, route: String) = Unit
            override fun rebuffer(route: String) = Unit
            override fun error(reason: String, httpClass: String) = Unit
        }
    }
}

/**
 * Turns the player's observable state into the playback metrics. Pure — fed by
 * `PlayerManager`'s listener, which the service has no test harness for, so the judgement lives
 * here where `:core`'s suite can reach it (the same reason `TrackPlayingClock` exists).
 *
 * The definitions, from the player's state alone:
 *  - **connect** — the clock starts the first time playback is wanted and audio is absent, and
 *    stops at the first `isPlaying`. Started by the user pressing play it is `cause=start`;
 *    started by the stream dying under audio that was playing (the player went IDLE with
 *    playback still wanted) it is `cause=reconnect`, and its duration includes the reconnect
 *    backoff, which is the outage as heard. The clock is armed once and never restarted while
 *    the wait continues — the arming discipline `CastStallWatchdog` taught: a reconnect loop
 *    that re-armed per attempt would only ever report its last attempt.
 *  - **rebuffer** — audio was playing, playback is still wanted, and the player went
 *    BUFFERING. Counted once per stall, however long it lasts or however it flaps.
 *  - pausing clears both: a wait the user abandoned is not a connect time.
 */
class PlaybackMeter(
    private val sink: PlaybackMetricSink,
    private val now: () -> Long,
) {
    private var connectStartMs: Long? = null
    private var connectCause = CAUSE_START
    private var audible = false
    private var stalled = false

    /** [state] is a Media3 `Player.STATE_*` value; [remote] is the Cast route. */
    fun update(playWhenReady: Boolean, state: Int, isPlaying: Boolean, remote: Boolean) {
        val route = if (remote) ROUTE_REMOTE else ROUTE_LOCAL
        when {
            !playWhenReady -> {
                connectStartMs = null
                audible = false
                stalled = false
            }
            isPlaying -> {
                connectStartMs?.let { sink.connect((now() - it) / 1000.0, connectCause, route) }
                connectStartMs = null
                audible = true
                stalled = false
            }
            audible && state == STATE_BUFFERING -> {
                if (!stalled) sink.rebuffer(route)
                stalled = true
            }
            audible && (state == STATE_IDLE || state == STATE_ENDED) -> {
                // The stream died under playing audio; what follows is a reconnect.
                audible = false
                stalled = false
                connectCause = CAUSE_RECONNECT
                connectStartMs = now()
            }
            !audible && connectStartMs == null -> {
                connectCause = CAUSE_START
                connectStartMs = now()
            }
        }
    }

    /** A failure the player reported (`onPlayerError`). */
    fun playerError(errorCodeName: String, httpStatus: Int?) {
        sink.error(reasonOf(errorCodeName), httpClassOf(httpStatus))
    }

    /** A failure the player never reports — the Cast stall watchdog's escalations. */
    fun watchdogEscalation(reason: String) {
        sink.error(reason, HTTP_NONE)
    }

    companion object {
        // Media3's Player.STATE_* values, mirrored so this stays a pure class.
        const val STATE_IDLE = 1
        const val STATE_BUFFERING = 2
        const val STATE_READY = 3
        const val STATE_ENDED = 4

        const val CAUSE_START = "start"
        const val CAUSE_RECONNECT = "reconnect"
        const val ROUTE_LOCAL = "local"
        const val ROUTE_REMOTE = "remote"
        const val HTTP_NONE = "none"
        const val REASON_CAST_RELOAD = "cast_stall_reload"
        const val REASON_CAST_HAND_BACK = "cast_stall_hand_back"

        /**
         * `ERROR_CODE_IO_BAD_HTTP_STATUS` → `io_bad_http_status`. Media3's code names are a
         * closed enum (plus "custom error code" / "invalid error code" for the rest), so this
         * stays bounded; the length cap and character filter are there in case that changes.
         */
        fun reasonOf(errorCodeName: String): String =
            errorCodeName.removePrefix("ERROR_CODE_").lowercase()
                .map { if (it in 'a'..'z' || it in '0'..'9') it else '_' }
                .joinToString("").take(48).ifEmpty { "unknown" }

        /** The class, not the code: 404-vs-502 is the distinction that matters (CLAUDE.md). */
        fun httpClassOf(status: Int?): String = when (status) {
            null -> HTTP_NONE
            in 400..499 -> "4xx"
            in 500..599 -> "5xx"
            else -> "other"
        }
    }
}

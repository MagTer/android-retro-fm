package com.retrofm.android.playback

/**
 * How long the displayed track has been on screen **while audio was actually playing**.
 *
 * The freeze defence ([RetroFmPlaybackService.maybeHandleFrozenMetadata]) needs "this title has
 * outlasted any real song" and must not confuse it with "the stream stalled". Wall clock cannot
 * tell those apart: the car's modem stalls playback for minutes mid-song, and on 2026-08-15 a
 * 3.5 min rebuffer inside "Black Velvet" pushed a *correct* title to 492 s against the 480 s
 * threshold, blanking the display one second before the next title arrived.
 *
 * So the clock runs only between [start] and [stop], which the playback heartbeat drives off
 * `isPlaying`. A genuinely frozen injector still trips the threshold, because it freezes while
 * the audio keeps playing.
 *
 * Wall clock rather than uptime for the same reason as everywhere else in this service: the car
 * suspends to RAM and uptime stands still while it does. A suspend therefore looks like playing
 * time, which is the safe direction — the display is wrong either way once the car wakes.
 *
 * Not thread-safe; every caller is on the Main dispatcher.
 */
internal class TrackPlayingClock(private val now: () -> Long = System::currentTimeMillis) {

    private var accumulatedMs = 0L

    /** Wall clock of the last banked tick, or null while the clock is stopped. */
    private var lastTickMs: Long? = null

    /** Playing time accrued by the current track. */
    val playingMs: Long get() = accumulatedMs

    /**
     * Audio started. Time from here counts; the gap since [stop] does not, which is the whole
     * point of the class.
     */
    fun start() {
        lastTickMs = now()
    }

    /** Bank the time since the last tick. A no-op while stopped. */
    fun tick() {
        val last = lastTickMs ?: return
        val t = now()
        accumulatedMs += (t - last).coerceAtLeast(0)
        lastTickMs = t
    }

    /** Audio stopped or stalled. Banks what was played up to here, then stops counting. */
    fun stop() {
        tick()
        lastTickMs = null
    }

    /**
     * A different track is now displayed. Closes out the outgoing track's slice — without that,
     * time played before the boundary would land on the new track's budget.
     */
    fun restart() {
        if (lastTickMs != null) lastTickMs = now()
        accumulatedMs = 0
    }
}

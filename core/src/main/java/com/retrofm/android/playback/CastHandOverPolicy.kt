package com.retrofm.android.playback

/**
 * Whether playback coming back from a Cast receiver should be audible on this device.
 *
 * A Cast receiver fetches the stream itself, so when the phone walks out of Wi-Fi range only the
 * control link dies — the speakers keep playing. Media3's `TransferCallback.DEFAULT` copies
 * `playWhenReady` across anyway, so the phone joined in on top of them (2026-08-29 18:35:55,
 * one second after `network lost`). Starting a cast says *where* the sound belongs, and losing
 * Wi-Fi does not retract that.
 *
 * The one hand-over that must stay audible is the one this app asks for: the stall watchdog
 * ending a session because the receiver has gone silent
 * ([com.retrofm.android.data.config.RetroFmConfig.CAST_STALL_HANDBACK_MS]) exists precisely to
 * get the audio back. So the claim is made before the session is ended and consumed by the
 * hand-over it belongs to.
 *
 * This lives in its own class because [PlayerManager] has no test harness — the same reason
 * [CastStallWatchdog] and [TrackPlayingClock] do. The rule worth pinning is not the boolean but
 * the **lifetime of the claim**: it must survive exactly one hand-over and never leak into the
 * next one, because a leaked claim un-silences precisely the case this exists for.
 *
 * Not thread-safe; every caller is on the Main dispatcher.
 */
internal class CastHandOverPolicy(private val resumeLocallyOnSessionLoss: Boolean) {

    private var requested = false

    /**
     * This app is about to end the Cast session itself. Called *before* ending it: the transfer
     * callback fires from inside that call, so claiming afterwards would be too late.
     */
    fun handBackRequested() {
        requested = true
    }

    /**
     * The hand-back never happened — ending the session threw, so no transfer will consume the
     * claim. Dropping it here is what keeps a failed rescue from silently exempting the *next*
     * hand-over, which is the unrequested one this class exists to silence.
     */
    fun handBackAbandoned() {
        requested = false
    }

    /**
     * True when the local player must be left paused. Consumes the claim, but only on an actual
     * hand-over — a transfer in the other direction leaves it standing for the one it was made
     * for.
     */
    fun silenceOnHandOver(fromRemote: Boolean, toRemote: Boolean): Boolean {
        if (!fromRemote || toRemote) return false
        val wasRequested = requested
        requested = false
        return !wasRequested && !resumeLocallyOnSessionLoss
    }
}

package com.retrofm.android.playback

import android.content.Context
import com.google.android.gms.cast.MediaError
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import timber.log.Timber

/**
 * Reads the Cast receiver's own state, so a silent receiver leaves a reason behind.
 *
 * The 2026-09-21 failure is the case this is built for: every Cast session died inside 90 s,
 * and all the log could say was `cast receiver silent 45 s — re-loading the stream` followed by
 * a hand-back. Whether the receiver had failed to fetch the stream, been handed a URL it
 * refused, or simply never been asked to play was indistinguishable, so the cause had to be
 * established by probing the CDN from the dev host instead. The receiver knows which it was and
 * says so in [RemoteMediaClient]; nothing was listening.
 *
 * **Every gms access is guarded and this class is only ever constructed on the Cast path.**
 * `:automotive` excludes the whole `com.google.android.gms` group, so loading this class there
 * would fail verification — [PlayerManager] therefore instantiates it inside the same branch
 * that builds the [androidx.media3.cast.CastPlayer], which that build never enters. The
 * try/catch blocks are the second line: they cover a device with no Play services, and a
 * session that ends between the check and the call.
 *
 * Nothing here ever degrades to a plausible default. A reading that cannot be taken is reported
 * as `unavailable` with the reason, because a confident "fine" from a broken probe is exactly
 * what would send the next investigation back to the CDN.
 *
 * Main-thread only, like every other caller in [PlayerManager].
 */
internal class CastReceiverProbe(private val appContext: Context) {

    private var watched: RemoteMediaClient? = null

    /**
     * The one line that names the cause. `onMediaError` carries the receiver's own error type,
     * reason and detailed code — including reasons no amount of sender-side state could infer,
     * `CONCURRENT_STREAM_LIMIT` and `GENERIC_LOAD_ERROR` among them.
     *
     * WARN, not DEBUG: it fires once per failure rather than in a loop, and it is the decisive
     * fact. The field log level resets to WARN on every redeploy of the log infra, and this must
     * survive that — a diagnostic that only exists at DEBUG is not there when it is needed.
     */
    private val callback = object : RemoteMediaClient.Callback() {
        override fun onMediaError(error: MediaError) {
            Timber.tag(TAG).w(
                "cast receiver error %s",
                CastReceiverStatus.describeError(
                    error.type, error.reason, error.detailedErrorCode
                )
            )
        }
    }

    /** Start listening to the receiver that playback has just moved to. */
    fun attach() {
        detach()
        val client = client() ?: return
        try {
            client.registerCallback(callback)
            watched = client
        } catch (e: Exception) {
            Timber.tag(TAG).d("could not watch the receiver: %s", e.javaClass.simpleName)
        }
    }

    /** Stop listening, on the way back to the local player or at teardown. */
    fun detach() {
        val client = watched ?: return
        watched = null
        try {
            client.unregisterCallback(callback)
        } catch (e: Exception) {
            Timber.tag(TAG).d("could not stop watching the receiver: %s", e.javaClass.simpleName)
        }
    }

    /**
     * The receiver's current state, for the escalation lines that used to carry only a stall
     * duration. `playerState=IDLE idleReason=ERROR` says the receiver tried and failed;
     * `playerState=BUFFERING` says it is still trying and the stream is the suspect.
     */
    fun describe(): String = try {
        val client = client()
        if (client == null) "receiver status unavailable (no session)"
        else CastReceiverStatus.describe(client.playerState, client.idleReason)
    } catch (e: Exception) {
        "receiver status unavailable (${e.javaClass.simpleName})"
    }

    /**
     * Which kind of receiver this is — `Chromecast`, `Google Nest Hub`, `Google Home Mini`.
     *
     * CLAUDE.md has carried "the logs never name the receiver" as a known gap since 2026-08-23,
     * because neither the transfer lines nor the LOAD payload say which device a session is on
     * and "analyse these cast sessions" could not separate a Nest Hub from a speaker.
     *
     * The **model**, deliberately, and never `friendlyName`: the friendly name is chosen by the
     * user and routinely carries a person's name or a room, which the log-hygiene rule keeps off
     * the wire. The model answers the question that was actually being asked.
     */
    fun deviceModel(): String = try {
        CastContext.getSharedInstance(appContext)
            .sessionManager.currentCastSession
            ?.castDevice?.modelName
            ?.ifBlank { null }
            ?: "unknown"
    } catch (e: Exception) {
        "unknown"
    }

    private fun client(): RemoteMediaClient? =
        CastContext.getSharedInstance(appContext)
            .sessionManager.currentCastSession
            ?.remoteMediaClient

    private companion object {
        const val TAG = "RetroFmCast"
    }
}

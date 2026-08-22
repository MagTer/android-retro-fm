package com.retrofm.android.playback

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.RemoteCastPlayer
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.common.ForwardingPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import com.google.android.gms.cast.framework.CastContext
import com.retrofm.android.data.config.RetroFmConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

class PlayerManager(context: Context, private val scope: CoroutineScope) {

    private var reconnectAttempts = 0
    private var reconnectJob: Job? = null
    private var wasPlayingBeforeError = false

    /**
     * Armed while the Cast receiver is stalled; see [RetroFmConfig.CAST_STALL_RECOVER_MS].
     *
     * Deliberately *not* restarted by state churn: the 2026-08-22 failure flapped READY/IDLE
     * three times in two seconds, and a timer reset on every transition would have kept the
     * watchdog from ever firing. It is armed once when the stall starts and cancelled only
     * when playback actually resumes, the route goes local, or the user stops.
     */
    private var castStallJob: Job? = null

    private val castStallWatchdog = CastStallWatchdog(
        recoverAfterMs = RetroFmConfig.CAST_STALL_RECOVER_MS,
        handBackAfterMs = RetroFmConfig.CAST_STALL_HANDBACK_MS,
        handBackEnabled = RetroFmConfig.CAST_HANDBACK_ENABLED
    )

    private val appContext = context.applicationContext

    private val exoPlayer: ExoPlayer = ExoPlayer.Builder(
        context,
        DefaultMediaSourceFactory(
            DefaultDataSource.Factory(
                context,
                DefaultHttpDataSource.Factory()
                    .setConnectTimeoutMs(RetroFmConfig.STREAM_CONNECT_TIMEOUT_MS)
                    .setReadTimeoutMs(RetroFmConfig.STREAM_READ_TIMEOUT_MS)
            )
        ).setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(6))
    )
        .setLoadControl(
            // Live stream: the buffer can never grow beyond what the server has sent, so the
            // only meaningful knobs are the start and resume thresholds (see RetroFmConfig).
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                    DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
                    RetroFmConfig.BUFFER_FOR_PLAYBACK_MS,
                    RetroFmConfig.BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
                )
                .build()
        )
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .setUsage(C.USAGE_MEDIA)
                .build(),
            true
        )
        .setHandleAudioBecomingNoisy(true)
        .build()

    /**
     * The player the [MediaLibrarySession] is built on. When Google Play services and the
     * Cast meta-data are present (the phone build), this is a unified [CastPlayer] that wraps
     * [exoPlayer] as its local player and switches automatically to a Cast device when a route
     * is selected — no manual player swapping. On builds without Cast activation (`:automotive`,
     * or any device without Play services) `CastContext` initialization throws and we fall back
     * to plain [exoPlayer], keeping local-only playback working.
     *
     * All playback control is routed through this property: when not casting, [CastPlayer]
     * delegates to the wrapped [exoPlayer]; when casting, it targets the receiver.
     */
    val player: Player = PlayGatedPlayer(
        if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            // Never probe Cast on Automotive OS — the phone APK can be installed in cars via the
            // Play device catalog, and the cast framework then nags about the car's Play services
            // version. Casting from a car makes no sense anyway.
            exoPlayer
        } else {
            try {
                CastPlayer.Builder(context)
                    .setLocalPlayer(exoPlayer)
                    // Observation only — the state hand-over still runs the default way. The
                    // route was previously visible just as onDeviceInfoChanged *after* the
                    // fact, which is how a transfer back to a not-yet-ready receiver read as
                    // "the app went silent for no reason" (2026-08-22). There is no veto here:
                    // transferState is called while the switch happens, so this cannot delay
                    // or refuse it — the recovery for a receiver that will not start is the
                    // stall watchdog below.
                    .setTransferCallback { from, to ->
                        Timber.tag(TAG).i(
                            "cast transfer: %s -> %s (state=%d playWhenReady=%b)",
                            describePlayer(from), describePlayer(to),
                            from.playbackState, from.playWhenReady
                        )
                        CastPlayer.TransferCallback.DEFAULT.transferState(from, to)
                    }
                    .setRemotePlayer(
                        RemoteCastPlayer.Builder(context)
                            // Marks the stream LIVE for the receiver; the default converter's
                            // BUFFERED type left the Default Media Receiver loading forever.
                            .setMediaItemConverter(RetroFmMediaItemConverter())
                            .build()
                    )
                    .build()
            } catch (e: Exception) {
                // No Play services / no cast meta-data (e.g. :automotive) → local-only.
                exoPlayer
            }
        }
    ).apply {
        setMediaItem(MediaItemTree.getStationItem())
        addListener(PlayerEventListener())
    }

    /**
     * Gates every network-touching player transition on the user actually wanting playback.
     *
     * The car's media host calls prepare() on the session the moment it binds at boot — a
     * prefetch. Un-gated, that opened the live stream minutes before the user pressed play:
     * data spent, artwork fetched, and — worst — the connect-time preroll ad announced its ICY
     * marker while playWhenReady was still false, so the marker was ignored and the buffered
     * preroll later played UNMUTED when the user finally pressed play. That is why ad handling
     * looked intermittent: it depended on whether the boot prefetch or the user opened the
     * stream.
     *
     * Rules, applying to every control surface (car UI, phone UI, voice, media buttons) because
     * the session drives this wrapped player directly:
     *  - prepare() is a no-op until playback is requested — nothing touches the network before
     *    play. Play flows that prepare-then-play still work: the play half prepares itself.
     *  - play (= setPlayWhenReady(true)) self-prepares from IDLE/ENDED, and a resume from pause
     *    first seeks to the live edge — the radio convention (never replay a stale buffer),
     *    and the invariant clearAdState-on-pause relies on.
     */
    private inner class PlayGatedPlayer(delegate: Player) : ForwardingPlayer(delegate) {
        override fun prepare() {
            if (!playWhenReady) {
                Timber.tag(TAG).d("prepare gated — playback not requested yet")
                return
            }
            super.prepare()
        }

        override fun play() = setPlayWhenReady(true)

        override fun setPlayWhenReady(playWhenReady: Boolean) {
            if (!playWhenReady) {
                super.setPlayWhenReady(false)
                return
            }
            when {
                playbackState == Player.STATE_IDLE -> {
                    super.setPlayWhenReady(true)
                    super.prepare()
                }
                playbackState == Player.STATE_ENDED -> {
                    super.seekToDefaultPosition()
                    super.setPlayWhenReady(true)
                    super.prepare()
                }
                !this.playWhenReady -> {
                    // Resume from pause: back to the live edge, never the stale buffer.
                    super.seekToDefaultPosition()
                    super.setPlayWhenReady(true)
                }
                else -> super.setPlayWhenReady(true)
            }
        }
    }

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    // Fire on VALIDATED internet, not mere availability. The car's modem reports the network
    // "available" seconds before it can actually reach the stream, so onAvailable retried too
    // early (and then the reconnect loop backed off past the point where real internet arrived).
    // NET_CAPABILITY_VALIDATED is the "the internet actually works now" signal. Also fires for
    // the current network at registration — harmless: with no player error it's a no-op.
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            val internet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            Timber.tag("Network").d("capabilities: internet=%b validated=%b", internet, validated)
            if (validated) {
                scope.launch { retryNowIfRecovering() }
            }
        }

        override fun onAvailable(network: Network) {
            Timber.tag("Network").d("network available")
        }

        override fun onLost(network: Network) {
            Timber.tag("Network").w("network lost")
        }
    }

    init {
        // Loudness alignment with normalized services — on the local player only, so a Cast
        // receiver's level is untouched. The ad-mute logic captures/restores player.volume
        // generically, so this baseline flows through it unchanged.
        exoPlayer.volume = RetroFmConfig.PLAYER_BASE_GAIN
        connectivityManager.registerDefaultNetworkCallback(networkCallback)
        logNetworkSnapshot()
    }

    /** One-shot snapshot of connectivity at process start — tests whether the car has real
     *  internet the moment it launches us (the suspected trigger for launch-time verification). */
    private fun logNetworkSnapshot() {
        val net = connectivityManager.activeNetwork
        val caps = net?.let { connectivityManager.getNetworkCapabilities(it) }
        Timber.tag("Network").i(
            "startup snapshot: activeNetwork=%b internet=%b validated=%b",
            net != null,
            caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
            caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        )
    }

    fun release() {
        connectivityManager.unregisterNetworkCallback(networkCallback)
        reconnectJob?.cancel()
        castStallJob?.cancel()
        // CastPlayer.release() also releases the wrapped local player (ExoPlayer supports
        // COMMAND_RELEASE), so releasing `player` alone is correct in both the cast and the
        // local-fallback case — no separate exoPlayer.release() (verified for media3 1.10.1).
        player.release()
    }

    /** Swaps in updated metadata (title/artist/artwork) without interrupting the live stream. */
    fun updateMediaItem(item: MediaItem) {
        player.replaceMediaItem(0, item)
    }

    /**
     * Connectivity came back: skip the remaining backoff and retry immediately instead of
     * waiting out the timer. Only acts when an error interrupted actual playback — never
     * re-opens the stream when the user had paused.
     */
    private fun retryNowIfRecovering() {
        val waitingToReconnect = reconnectJob?.isActive == true || player.playerError != null
        if (!waitingToReconnect || !wasPlayingBeforeError) return
        // playWhenReady is the live truth — the user may have paused since the error.
        if (!player.playWhenReady) return
        reconnectJob?.cancel()
        reconnectAttempts = 0
        player.prepare()
        player.play()
    }

    private fun scheduleReconnect() {
        // Keep retrying for as long as playback is wanted rather than giving up after a fixed
        // count. The car frequently powers on before its modem has validated internet, and the
        // old hard cap (~1 min) left the stream dead — buffer drains, then silence until a
        // manual restart. Backoff escalates to RECONNECT_BACKOFF_MS.last() and holds there; the
        // validated-internet callback (onCapabilitiesChanged) short-circuits the wait the moment
        // the connection is real. When the user has paused, wasPlayingBeforeError is false and
        // we don't loop.
        if (!wasPlayingBeforeError) return
        val delayMs = RetroFmConfig.RECONNECT_BACKOFF_MS.getOrElse(reconnectAttempts) {
            RetroFmConfig.RECONNECT_BACKOFF_MS.last()
        }
        reconnectAttempts++
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(delayMs)
            // The user may have pressed pause while we waited — a paused player must stay quiet.
            if (!player.playWhenReady) return@launch
            // prepare() reopens the live stream at the live edge, so recovery is never stale.
            // On the cast route this re-loads the stream on the receiver — acceptable.
            player.prepare()
            player.play()
        }
    }

    private fun describePlayer(p: Player): String =
        if (p.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE) "REMOTE" else "LOCAL"

    private fun onRemoteRoute(): Boolean =
        player.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE

    /** Playback is wanted, the receiver has it, and nothing is coming out. */
    private fun castStalled(): Boolean = onRemoteRoute() && player.playWhenReady && !player.isPlaying

    /**
     * Arms or cancels the Cast stall watchdog after any event that could change the answer.
     *
     * The decision lives in [CastStallWatchdog] so it can be tested; this only feeds it the
     * player's condition and carries out what it returns. While a stall is running the state
     * is re-fed on a short poll, because the escalation is time-based and the receiver can
     * stay silent without emitting a single further event — which is exactly what happened on
     * 2026-08-21.
     */
    private fun updateCastStallWatchdog() {
        castStallWatchdog.update(onRemoteRoute(), player.playWhenReady, player.isPlaying)
        if (!castStalled()) {
            castStallJob?.cancel()
            castStallJob = null
            return
        }
        if (castStallJob?.isActive == true) return      // already counting — never restart it
        castStallJob = scope.launch {
            while (true) {
                delay(RetroFmConfig.CAST_STALL_POLL_MS)
                castStallWatchdog.update(onRemoteRoute(), player.playWhenReady, player.isPlaying)
                when (castStallWatchdog.due()) {
                    CastStallWatchdog.Action.NONE -> if (!castStalled()) return@launch
                    CastStallWatchdog.Action.RELOAD -> {
                        Timber.tag(TAG).w(
                            "cast receiver silent %d s — re-loading the stream",
                            castStallWatchdog.stalledMs / 1000
                        )
                        // Reuse the error path's notion of "playback was wanted", so a later
                        // failure lands in the same reconnect machinery, not a parallel one.
                        wasPlayingBeforeError = true
                        player.prepare()
                        player.play()
                    }
                    CastStallWatchdog.Action.HAND_BACK -> {
                        Timber.tag(TAG).w(
                            "cast receiver still silent — handing playback back to this device"
                        )
                        handBackToLocal()
                        return@launch
                    }
                }
            }
        }
    }

    /**
     * Ends the Cast session so [CastPlayer] falls back to the local player.
     *
     * Isolated in its own function and guarded: `:automotive` strips the whole
     * `com.google.android.gms` group, so CastContext does not exist there. It can only be
     * reached from the remote route, which that build never enters, but a lone method keeps a
     * failed class resolution from touching anything else.
     */
    private fun handBackToLocal() {
        try {
            CastContext.getSharedInstance(appContext).sessionManager.endCurrentSession(true)
        } catch (e: Exception) {
            Timber.tag(TAG).w("could not end the cast session: %s", e.javaClass.simpleName)
        }
    }

    private inner class PlayerEventListener : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            val name = when (playbackState) {
                Player.STATE_IDLE -> "IDLE"
                Player.STATE_BUFFERING -> "BUFFERING"
                Player.STATE_READY -> "READY"
                Player.STATE_ENDED -> "ENDED"
                else -> "?$playbackState"
            }
            Timber.tag(TAG).d("playbackState=%s playWhenReady=%b", name, player.playWhenReady)
            if (playbackState == Player.STATE_READY) {
                reconnectAttempts = 0
            }
            updateCastStallWatchdog()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            Timber.tag(TAG).i("isPlaying=%b", isPlaying)
            updateCastStallWatchdog()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            Timber.tag(TAG).d("playWhenReady=%b reason=%d", playWhenReady, reason)
            updateCastStallWatchdog()
        }

        // The route itself decides whether the watchdog applies at all.
        override fun onDeviceInfoChanged(deviceInfo: DeviceInfo) {
            updateCastStallWatchdog()
        }

        override fun onPlayerError(error: PlaybackException) {
            Timber.tag(TAG).w(
                "player error %s (reconnect attempt %d, playWhenReady=%b)",
                error.errorCodeName, reconnectAttempts, player.playWhenReady
            )
            wasPlayingBeforeError = player.playWhenReady
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                player.seekToDefaultPosition()
                player.prepare()
                return
            }
            scheduleReconnect()
        }
    }

    private companion object {
        const val TAG = "Playback"
    }
}

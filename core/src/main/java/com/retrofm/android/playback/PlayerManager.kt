package com.retrofm.android.playback

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.RemoteCastPlayer
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
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
    val player: Player = if (
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
    ) {
        // Never probe Cast on Automotive OS — the phone APK can be installed in cars via the
        // Play device catalog, and the cast framework then nags about the car's Play services
        // version. Casting from a car makes no sense anyway.
        exoPlayer
    } else {
        try {
            CastPlayer.Builder(context)
                .setLocalPlayer(exoPlayer)
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
    }.apply {
        setMediaItem(MediaItemTree.getStationItem())
        playWhenReady = false
        addListener(PlayerEventListener())
    }

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    // Also fires for the current network right at registration — harmless: with no player
    // error and no pending reconnect, retryNowIfRecovering() is a no-op.
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            scope.launch { retryNowIfRecovering() }
        }
    }

    init {
        connectivityManager.registerDefaultNetworkCallback(networkCallback)
    }

    fun play() {
        if (player.playbackState == Player.STATE_IDLE) {
            player.prepare()
        } else if (!player.isPlaying) {
            // Radio convention: resuming from pause jumps back to the live edge
            // rather than replaying a stale buffer.
            player.seekToDefaultPosition()
        }
        player.play()
    }

    fun pause() {
        player.pause()
    }

    fun release() {
        connectivityManager.unregisterNetworkCallback(networkCallback)
        reconnectJob?.cancel()
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
        reconnectJob?.cancel()
        reconnectAttempts = 0
        player.prepare()
        player.play()
    }

    private fun scheduleReconnect() {
        if (reconnectAttempts >= RetroFmConfig.MAX_RECONNECT_ATTEMPTS) {
            Timber.tag(TAG).e(
                "giving up after %d reconnect attempts", RetroFmConfig.MAX_RECONNECT_ATTEMPTS
            )
            return
        }
        val delayMs = RetroFmConfig.RECONNECT_BACKOFF_MS.getOrElse(reconnectAttempts) {
            RetroFmConfig.RECONNECT_BACKOFF_MS.last()
        }
        reconnectAttempts++
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(delayMs)
            // On the cast route this re-loads the stream on the receiver — acceptable.
            player.prepare()
            if (wasPlayingBeforeError) {
                player.play()
            }
        }
    }

    private inner class PlayerEventListener : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                reconnectAttempts = 0
            }
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

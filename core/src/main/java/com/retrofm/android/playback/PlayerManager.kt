package com.retrofm.android.playback

import android.content.Context
import androidx.media3.cast.CastPlayer
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import com.retrofm.android.data.config.RetroFmConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class PlayerManager(context: Context, private val scope: CoroutineScope) {

    private var reconnectAttempts = 0
    private var reconnectJob: Job? = null
    private var wasPlayingBeforeError = false

    private val exoPlayer: ExoPlayer = ExoPlayer.Builder(
        context,
        DefaultMediaSourceFactory(context)
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(6))
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
    val player: Player = try {
        CastPlayer.Builder(context).setLocalPlayer(exoPlayer).build()
    } catch (e: Exception) {
        // No Play services / no cast meta-data (e.g. :automotive) → local-only.
        exoPlayer
    }.apply {
        setMediaItem(MediaItemTree.getStationItem())
        playWhenReady = false
        addListener(PlayerEventListener())
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

    private fun scheduleReconnect() {
        if (reconnectAttempts >= RetroFmConfig.MAX_RECONNECT_ATTEMPTS) {
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
            wasPlayingBeforeError = player.playWhenReady
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                player.seekToDefaultPosition()
                player.prepare()
                return
            }
            scheduleReconnect()
        }
    }
}

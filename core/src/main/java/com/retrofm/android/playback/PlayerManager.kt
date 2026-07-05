package com.retrofm.android.playback

import android.content.Context
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

    val exoPlayer: ExoPlayer = ExoPlayer.Builder(
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
        .apply {
            setMediaItem(MediaItemTree.getStationItem())
            playWhenReady = false
            addListener(PlayerEventListener())
        }

    fun play() {
        if (exoPlayer.playbackState == Player.STATE_IDLE) {
            exoPlayer.prepare()
        } else if (!exoPlayer.isPlaying) {
            // Radio convention: resuming from pause jumps back to the live edge
            // rather than replaying a stale buffer.
            exoPlayer.seekToDefaultPosition()
        }
        exoPlayer.play()
    }

    fun pause() {
        exoPlayer.pause()
    }

    fun release() {
        reconnectJob?.cancel()
        exoPlayer.release()
    }

    /** Swaps in updated metadata (title/artist/artwork) without interrupting the live stream. */
    fun updateMediaItem(item: MediaItem) {
        exoPlayer.replaceMediaItem(0, item)
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
            exoPlayer.prepare()
            if (wasPlayingBeforeError) {
                exoPlayer.play()
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
            wasPlayingBeforeError = exoPlayer.playWhenReady
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                exoPlayer.seekToDefaultPosition()
                exoPlayer.prepare()
                return
            }
            scheduleReconnect()
        }
    }
}

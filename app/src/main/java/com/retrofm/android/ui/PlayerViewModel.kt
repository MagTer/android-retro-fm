package com.retrofm.android.ui

import android.app.Application
import android.content.ComponentName
import android.os.Bundle
import android.os.SystemClock
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.android.gms.cast.framework.CastContext
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.retrofm.android.R
import com.retrofm.android.data.config.RetroFmConfig
import com.retrofm.android.playback.RetroFmPlaybackService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@Immutable
data class PlayerUiState(
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val trackTitle: String = RetroFmConfig.STATION_NAME,
    val artistName: String = RetroFmConfig.STATION_STRAPLINE,
    val imageUrl: String? = RetroFmConfig.LOGO_PNG_URL,
    val errorMessage: String? = null,
    /** Friendly name of the active Cast device while casting; null when playing locally. */
    val castDeviceName: String? = null,
    /** Seconds left of a server-spliced ad ("Reklam" countdown); null when no ad is playing. */
    val adSecondsRemaining: Int? = null
)

class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private var mediaController: MediaController? = null

    private val controllerFuture: ListenableFuture<MediaController> = MediaController.Builder(
        application,
        SessionToken(application, ComponentName(application, RetroFmPlaybackService::class.java))
    )
        .setListener(SessionListener())
        .buildAsync()

    init {
        controllerFuture.addListener({
            val controller = controllerFuture.get()
            mediaController = controller
            controller.addListener(PlayerListener())
            updateFromController(controller)
            // Catch up on an ad already in progress when the UI (re)connects mid-countdown.
            onAdExtrasChanged(controller.sessionExtras)
        }, MoreExecutors.directExecutor())
    }

    fun togglePlayPause() {
        val controller = mediaController ?: return
        if (controller.isPlaying) {
            controller.pause()
        } else {
            controller.play()
        }
    }

    fun retry() {
        val controller = mediaController ?: return
        controller.prepare()
        controller.play()
    }

    fun dismissError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    private fun updateFromController(controller: MediaController) {
        _uiState.value = _uiState.value.copy(
            isPlaying = controller.isPlaying,
            isBuffering = controller.playbackState == Player.STATE_BUFFERING,
            trackTitle = controller.mediaMetadata.title?.toString()
                ?: RetroFmConfig.STATION_NAME,
            artistName = controller.mediaMetadata.artist?.toString()
                ?: RetroFmConfig.STATION_STRAPLINE,
            imageUrl = controller.mediaMetadata.artworkUri?.toString()
                ?: RetroFmConfig.LOGO_PNG_URL
        )
    }

    override fun onCleared() {
        MediaController.releaseFuture(controllerFuture)
        super.onCleared()
    }

    private inner class PlayerListener : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            mediaController?.let { updateFromController(it) }
            if (isPlaying) {
                _uiState.value = _uiState.value.copy(errorMessage = null)
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            mediaController?.let { updateFromController(it) }
        }

        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            mediaController?.let { updateFromController(it) }
        }

        override fun onPlayerErrorChanged(error: PlaybackException?) {
            mediaController?.let { updateFromController(it) }
            _uiState.value = _uiState.value.copy(
                errorMessage = error?.let {
                    getApplication<Application>().getString(R.string.playback_error)
                }
            )
        }

        override fun onDeviceInfoChanged(deviceInfo: DeviceInfo) {
            val remote = deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE
            _uiState.value = _uiState.value.copy(
                castDeviceName = if (remote) currentCastDeviceName() else null
            )
        }
    }

    /** Best-effort friendly name of the active Cast session; null if Cast is unavailable. */
    private fun currentCastDeviceName(): String? = runCatching {
        CastContext.getSharedInstance(getApplication())
            .sessionManager.currentCastSession?.castDevice?.friendlyName
    }.getOrNull()

    private var adCountdownJob: Job? = null

    private inner class SessionListener : MediaController.Listener {
        override fun onExtrasChanged(controller: MediaController, extras: Bundle) {
            onAdExtrasChanged(extras)
        }
    }

    /** Ticks the "Reklam" countdown against the elapsedRealtime deadline from the service. */
    private fun onAdExtrasChanged(extras: Bundle) {
        val untilElapsedMs = extras.getLong(RetroFmPlaybackService.EXTRA_AD_UNTIL_ELAPSED_MS, 0L)
        adCountdownJob?.cancel()
        adCountdownJob = if (untilElapsedMs <= SystemClock.elapsedRealtime()) {
            _uiState.value = _uiState.value.copy(adSecondsRemaining = null)
            null
        } else {
            viewModelScope.launch {
                while (true) {
                    val remainingMs = untilElapsedMs - SystemClock.elapsedRealtime()
                    if (remainingMs <= 0) break
                    _uiState.value = _uiState.value.copy(
                        adSecondsRemaining = ((remainingMs + 999) / 1000).toInt()
                    )
                    delay(250)
                }
                _uiState.value = _uiState.value.copy(adSecondsRemaining = null)
            }
        }
    }
}

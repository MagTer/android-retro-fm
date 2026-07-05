package com.retrofm.android.ui

import android.app.Application
import android.content.ComponentName
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.retrofm.android.R
import com.retrofm.android.data.config.RetroFmConfig
import com.retrofm.android.playback.RetroFmPlaybackService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Immutable
data class PlayerUiState(
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val trackTitle: String = RetroFmConfig.STATION_NAME,
    val artistName: String = RetroFmConfig.STATION_STRAPLINE,
    val imageUrl: String? = RetroFmConfig.LOGO_PNG_URL,
    val errorMessage: String? = null
)

class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private var mediaController: MediaController? = null

    private val controllerFuture: ListenableFuture<MediaController> = MediaController.Builder(
        application,
        SessionToken(application, ComponentName(application, RetroFmPlaybackService::class.java))
    ).buildAsync()

    init {
        controllerFuture.addListener({
            val controller = controllerFuture.get()
            mediaController = controller
            controller.addListener(PlayerListener())
            updateFromController(controller)
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
    }
}

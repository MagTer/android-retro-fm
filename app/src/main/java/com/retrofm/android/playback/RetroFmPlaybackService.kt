package com.retrofm.android.playback

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionToken
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.retrofm.android.R
import com.retrofm.android.data.config.RetroFmConfig
import com.retrofm.android.data.model.TrackInfo
import com.retrofm.android.data.repository.NowPlayingRepository
import com.retrofm.android.di.NetworkModule
import com.retrofm.android.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class RetroFmPlaybackService : MediaLibraryService() {

    private lateinit var playerManager: PlayerManager
    private lateinit var mediaLibrarySession: MediaLibrarySession
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var metadataJob: Job? = null
    private var lastAppliedEventId: Long? = null

    override fun onCreate() {
        super.onCreate()
        playerManager = PlayerManager(this, serviceScope)
        playerManager.exoPlayer.addListener(PlaybackStateListener())

        mediaLibrarySession = MediaLibrarySession.Builder(
            this,
            playerManager.exoPlayer,
            RetroFmMediaLibraryCallback()
        )
            .setSessionActivity(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java)
                        .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()

        val notificationProvider = DefaultMediaNotificationProvider.Builder(this).build()
        notificationProvider.setSmallIcon(R.drawable.ic_notification)
        setMediaNotificationProvider(notificationProvider)

        // One-shot fetch so the UI has metadata before playback starts; the poll loop
        // itself only runs while actively playing (see PlaybackStateListener).
        fetchNowPlayingOnce()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession {
        return mediaLibrarySession
    }

    override fun onDestroy() {
        metadataJob?.cancel()
        serviceScope.cancel()
        mediaLibrarySession.release()
        playerManager.release()
        super.onDestroy()
    }

    private fun fetchNowPlayingOnce() {
        val repository = NowPlayingRepository(NetworkModule.retroFmApi)
        serviceScope.launch {
            repository.fetchNowPlaying().onSuccess { track -> applyTrackMetadata(track) }
        }
    }

    private fun startMetadataPolling() {
        if (metadataJob?.isActive == true) return
        val repository = NowPlayingRepository(NetworkModule.retroFmApi)
        metadataJob = serviceScope.launch {
            while (isActive) {
                val delayMs = repository.fetchNowPlaying().fold(
                    onSuccess = { track ->
                        applyTrackMetadata(track)
                        nextPollDelayMs(track.finishTime)
                    },
                    onFailure = { RetroFmConfig.METADATA_POLL_INTERVAL_MS }
                )
                delay(delayMs)
            }
        }
    }

    private fun stopMetadataPolling() {
        metadataJob?.cancel()
        metadataJob = null
    }

    private fun nextPollDelayMs(finishTime: String?): Long {
        val finishMillis = finishTime?.let { parseEventTimeMillis(it) }
            ?: return RetroFmConfig.METADATA_POLL_INTERVAL_MS
        val remaining = finishMillis - System.currentTimeMillis() + 2_000L
        return remaining.coerceIn(
            RetroFmConfig.METADATA_POLL_MIN_INTERVAL_MS,
            RetroFmConfig.METADATA_POLL_INTERVAL_MS
        )
    }

    private fun parseEventTimeMillis(value: String): Long? = try {
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        format.parse(value)?.time
    } catch (e: ParseException) {
        null
    }

    private fun applyTrackMetadata(track: TrackInfo) {
        if (track.eventId == lastAppliedEventId) return
        lastAppliedEventId = track.eventId

        val item = MediaItemTree.getStationItem()
            .buildUpon()
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.title)
                    .setArtist(track.artist)
                    .setDisplayTitle(track.title)
                    .setSubtitle(track.artist)
                    .setArtworkUri(track.imageUrl?.let { Uri.parse(it) })
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
                    .build()
            )
            .build()

        playerManager.updateMediaItem(item)
    }

    private inner class PlaybackStateListener : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                startMetadataPolling()
            } else {
                stopMetadataPolling()
            }
        }
    }

    private inner class RetroFmMediaLibraryCallback : MediaLibrarySession.Callback {
        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: MediaLibraryService.LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            return Futures.immediateFuture(
                LibraryResult.ofItem(MediaItemTree.getRootItem(), params)
            )
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: MediaLibraryService.LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val children = if (parentId == MediaItemTree.ROOT_ID) {
                ImmutableList.of(MediaItemTree.getStationItem())
            } else {
                ImmutableList.of()
            }
            return Futures.immediateFuture(
                LibraryResult.ofItemList(children, params)
            )
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            // Single-station app: any request (including voice-initiated "play Retro FM")
            // resolves to the one station item.
            return Futures.immediateFuture(mutableListOf(MediaItemTree.getStationItem()))
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(
                    ImmutableList.of(MediaItemTree.getStationItem()),
                    0,
                    0L
                )
            )
        }
    }
}

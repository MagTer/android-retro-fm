package com.retrofm.android.playback

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.Player
import androidx.media3.extractor.metadata.icy.IcyInfo
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionToken
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.retrofm.android.core.R
import com.retrofm.android.data.config.RetroFmConfig
import com.retrofm.android.data.model.TrackInfo
import com.retrofm.android.data.repository.NowPlayingRepository
import com.retrofm.android.di.NetworkModule
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

    companion object {
        /**
         * Session-extras key: [SystemClock.elapsedRealtime] deadline until which a spliced-in
         * ad is playing. Absent when no ad is active.
         */
        const val EXTRA_AD_UNTIL_ELAPSED_MS = "com.retrofm.android.EXTRA_AD_UNTIL_ELAPSED_MS"
    }

    private lateinit var playerManager: PlayerManager
    private lateinit var mediaLibrarySession: MediaLibrarySession
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var metadataJob: Job? = null
    private var lastAppliedEventId: Long? = null
    private var adUntilElapsedMs: Long? = null
    private var adUnmuteJob: Job? = null
    private var preAdVolume: Float? = null

    override fun onCreate() {
        super.onCreate()
        playerManager = PlayerManager(this, serviceScope)
        // playerManager.player is the unified CastPlayer on the phone build (local+remote),
        // or plain ExoPlayer where Cast is unavailable — see PlayerManager.player.
        playerManager.player.addListener(PlaybackStateListener())

        val sessionBuilder = MediaLibrarySession.Builder(
            this,
            playerManager.player,
            RetroFmMediaLibraryCallback()
        )
        // Resolved by package at runtime rather than a compile-time Activity reference, since
        // this class is shared between the phone module (has a launcher Activity) and the
        // Android Automotive OS module (must not declare one).
        packageManager.getLaunchIntentForPackage(packageName)?.let { launchIntent ->
            sessionBuilder.setSessionActivity(
                PendingIntent.getActivity(
                    this,
                    0,
                    launchIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
        }
        mediaLibrarySession = sessionBuilder.build()

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

        // CAST-PLAN §2.4 (WP2): while casting, replaceMediaItem can translate to a queue
        // reload on the receiver, producing an audible gap on every track change. Skip the
        // in-place update on the remote route and accept static "Retro FM" branding on the
        // receiver (and phone UI) for v1. lastAppliedEventId is left untouched so the current
        // track is applied as soon as playback returns to the local route. Revisit once this
        // can be verified on real cast hardware (Phase 4, step 9).
        if (playerManager.player.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE) {
            return
        }
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

    /**
     * Publishes "an ad is playing until X" to all controllers via session extras. The deadline
     * is on the [SystemClock.elapsedRealtime] clock (monotonic, shared across processes).
     * With [RetroFmConfig.MUTE_ADS] the player is also muted (app-internal volume, not the
     * device volume) until the announced duration elapses or regular track metadata arrives.
     */
    private fun setAdState(untilElapsedMs: Long) {
        adUntilElapsedMs = untilElapsedMs
        mediaLibrarySession.setSessionExtras(
            Bundle().apply { putLong(EXTRA_AD_UNTIL_ELAPSED_MS, untilElapsedMs) }
        )
        if (RetroFmConfig.MUTE_ADS) {
            // Back-to-back ads re-announce themselves: only capture the volume on the first,
            // so a mid-break marker can't overwrite the saved level with our own 0.
            if (preAdVolume == null) {
                preAdVolume = playerManager.player.volume
                playerManager.player.volume = 0f
            }
            adUnmuteJob?.cancel()
            adUnmuteJob = serviceScope.launch {
                delay(untilElapsedMs - SystemClock.elapsedRealtime())
                // Fallback unmute at the announced deadline; usually the ad-end metadata
                // (clearAdState via onMetadata) lands first or shortly after.
                clearAdState()
            }
        }
    }

    private fun clearAdState() {
        if (adUntilElapsedMs == null) return
        adUntilElapsedMs = null
        adUnmuteJob?.cancel()
        adUnmuteJob = null
        preAdVolume?.let { playerManager.player.volume = it }
        preAdVolume = null
        mediaLibrarySession.setSessionExtras(Bundle())
    }

    private inner class PlaybackStateListener : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                startMetadataPolling()
            } else {
                stopMetadataPolling()
                // A paused live stream resumes at the live edge, where this ad is over.
                clearAdState()
            }
        }

        // ICY in-stream metadata: the server announces spliced-in ads (preroll at connect,
        // midrolls in the same format) with an adw_ad marker and their exact duration — see
        // IcyAdMarker. Regular track metadata doubles as the ad-end signal, which also covers
        // the deadline drifting late when playback stalls mid-ad. Only fires on the local
        // route: while casting the receiver fetches the stream itself, so no false labels.
        override fun onMetadata(metadata: Metadata) {
            for (i in 0 until metadata.length()) {
                val icy = metadata.get(i) as? IcyInfo ?: continue
                val durationMs = IcyAdMarker.parseDurationMs(icy.rawMetadata)
                if (durationMs != null) {
                    setAdState(SystemClock.elapsedRealtime() + durationMs)
                } else {
                    clearAdState()
                }
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

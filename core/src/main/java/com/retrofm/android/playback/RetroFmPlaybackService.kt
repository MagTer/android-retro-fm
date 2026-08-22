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
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionToken
import com.google.common.collect.ImmutableList
import com.retrofm.android.RetroFmApplication
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.retrofm.android.core.R
import com.retrofm.android.data.api.ArtworkLookup
import com.retrofm.android.data.api.StationNowPlaying
import com.retrofm.android.data.config.RetroFmConfig
import com.retrofm.android.data.model.TrackInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

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
    private var lastAppliedTrack: TrackInfo? = null
    private var artworkJob: Job? = null

    /** Armed by the mount's end-of-track marker; see [armHandoverTimeout]. */
    private var handoverJob: Job? = null

    /** An announcement that arrived before the play press — see [PendingIcyFrame]. */
    private val pendingIcy = PendingIcyFrame<Metadata>(RetroFmConfig.ICY_HELD_MAX_AGE_MS)
    private var currentTrack: TrackInfo? = null

    /**
     * How long [currentTrack] has been displayed *while audio was playing*. Driven by the
     * playback heartbeat, so a rebuffer or a pause contributes nothing — see
     * [maybeHandleFrozenMetadata] and [TrackPlayingClock] for why that distinction is the point.
     */
    private val trackClock = TrackPlayingClock()
    private var adUntilElapsedMs: Long? = null
    private var adUnmuteJob: Job? = null
    private var adCountdownJob: Job? = null
    private var staleInfoJob: Job? = null
    private var preAdVolume: Float? = null
    private var volumeFadeJob: Job? = null
    private var playbackHeartbeatJob: Job? = null
    // Wall-clock stamp of the last moment playback was demonstrably alive. Wall clock on
    // purpose: it is the only clock that keeps running while the car suspends to RAM — the
    // uptime clock (and every coroutine delay with it) freezes, and a frozen process runs
    // nothing. Compared against on resume to catch "parked 15 min, everything on screen is
    // from the previous drive".
    private var lastAliveWallMs: Long? = null
    // A real track title announced while an ad break was active — held back so it can't
    // replace the "Reklam" label over muted audio, applied when the break lifts.
    private var pendingAdTrack: TrackInfo? = null

    // On AAOS this app has no activity, so the Application's ON_STOP flush never fires in
    // the car — playback stopping / service destruction are the only end-of-drive signals.
    private val logsinkClient get() = (application as? RetroFmApplication)?.logsinkClient

    override fun onCreate() {
        super.onCreate()
        Timber.tag("Lifecycle").i("service onCreate")
        playerManager = PlayerManager(this, serviceScope)
        // playerManager.player is the unified CastPlayer on the phone build (local+remote),
        // or plain ExoPlayer where Cast is unavailable — see PlayerManager.player.
        playerManager.player.addListener(PlaybackStateListener())

        val sessionBuilder = MediaLibrarySession.Builder(
            this,
            playerManager.player,
            RetroFmMediaLibraryCallback()
        )
            // Same loader Media3 uses by default, wrapped with logging: makes the car's
            // artwork path (placeholder mystery) observable through the log sink.
            .setBitmapLoader(
                ArtworkLoggingBitmapLoader(
                    CacheBitmapLoader(DataSourceBitmapLoader(this))
                )
            )
        // Resolved by package at runtime rather than a compile-time Activity reference, since
        // this class is shared between the phone module (has a launcher Activity) and the
        // Android Automotive OS module (must not declare one).
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        Timber.tag("Lifecycle").d("launcher activity present=%b", launchIntent != null)
        launchIntent?.let {
            sessionBuilder.setSessionActivity(
                PendingIntent.getActivity(
                    this,
                    0,
                    it.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
        }
        mediaLibrarySession = sessionBuilder.build()

        val notificationProvider = DefaultMediaNotificationProvider.Builder(this).build()
        notificationProvider.setSmallIcon(R.drawable.ic_notification)
        setMediaNotificationProvider(notificationProvider)

        // No metadata prefetch here: while idle the session shows the station branding from
        // MediaItemTree, and once audio actually plays the ICY pipeline applies the track
        // that is being heard. A prefetch would show a song the user isn't hearing.
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession {
        return mediaLibrarySession
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.tag("Lifecycle").d("onStartCommand action=%s", intent?.action)
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        Timber.tag("Lifecycle").i("service onDestroy")
        serviceScope.cancel()
        mediaLibrarySession.release()
        playerManager.release()
        // Last chance to ship the tail of the session before the process goes quiet.
        // serviceScope is already cancelled, so ride the process-lifecycle scope.
        logsinkClient?.let { client ->
            ProcessLifecycleOwner.get().lifecycleScope.launch {
                client.flush()
                // Service teardown is the car's end-of-drive signal; persist whatever the
                // flush could not ship so a park without coverage is not a blind spot.
                client.persistNow()
            }
        }
        super.onDestroy()
    }

    private fun applyTrackMetadata(track: TrackInfo) {
        // Hold real track metadata for the duration of an ad break. Audio is muted and the
        // surface shows "Reklam", so a title must not replace the ad label mid-break — field
        // logs showed songs flashing up over muted ad audio. The ad-end path clears ad state
        // before applying its track, so it is unaffected; the held track is applied by
        // clearAdState when the break lifts.
        if (adUntilElapsedMs != null && track.eventId != RetroFmConfig.AD_EVENT_ID) {
            pendingAdTrack = track
            Timber.tag("NowPlaying").d("apply held during ad break eventId=%d", track.eventId)
            return
        }
        // Dedup on the whole track, not just its id: album art arrives a moment after the title
        // (ArtworkLookup is a network round-trip), and that second apply differs only in
        // imageUrl. Comparing ids would swallow it and leave the station logo on screen.
        if (track == lastAppliedTrack) {
            Timber.tag("NowPlaying").d("apply skipped (dedup) eventId=%d", track.eventId)
            return
        }

        // CAST-PLAN §2.4 (WP2): while casting, replaceMediaItem can translate to a queue
        // reload on the receiver, producing an audible gap on every track change. Skip the
        // in-place update on the remote route and accept static "Retro FM" branding on the
        // receiver (and phone UI) for v1. lastAppliedTrack is left untouched so the current
        // track is applied as soon as playback returns to the local route. Revisit once this
        // can be verified on real cast hardware (Phase 4, step 9).
        if (playerManager.player.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE) {
            Timber.tag("NowPlaying").d("apply skipped (remote route) eventId=%d", track.eventId)
            return
        }
        lastAppliedTrack = track
        Timber.tag("NowPlaying").d("apply eventId=%d '%s - %s'", track.eventId, track.title, track.artist)

        playerManager.updateMediaItem(buildStationItem(track))

        // Restarted only when the track itself changes, so the artwork's second apply does not
        // restart the freeze clock (see maybeHandleFrozenMetadata).
        if (track.eventId != currentTrack?.eventId) {
            trackClock.restart()
        }

        // Live browse tile: the station's browse representation mirrors the current track,
        // so tell connected browsers (the car's media host) to re-fetch it.
        currentTrack = track
        mediaLibrarySession.notifyChildrenChanged(MediaItemTree.STATIONS_TAB_ID, 1, null)
    }

    /**
     * The playing MediaItem carrying [track]'s metadata. Artwork is exposed as a content:// URI
     * (via AlbumArtContentProvider) because AAOS renders only local artwork URIs — a remote
     * https URI or an embedded bitmap leaves the car's now-playing art a placeholder.
     */
    private fun buildStationItem(track: TrackInfo): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setDisplayTitle(track.title)
            .setSubtitle(track.artist)
            .setArtworkUri(track.imageUrl?.let { AlbumArtContentProvider.mapUri(Uri.parse(it)) })
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
            .build()
        return MediaItemTree.getStationItem().buildUpon()
            .setMediaMetadata(metadata)
            .build()
    }

    /** The ad-branding MediaItem with a live [subtitle] (the countdown), title stays "Reklam". */
    private fun buildAdItem(subtitle: String): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(RetroFmConfig.AD_DISPLAY_TITLE)
            .setArtist(subtitle)
            .setDisplayTitle(RetroFmConfig.AD_DISPLAY_TITLE)
            .setSubtitle(subtitle)
            .setArtworkUri(AlbumArtContentProvider.mapUri(Uri.parse(RetroFmConfig.LOGO_PNG_URL)))
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
            .build()
        return MediaItemTree.getStationItem().buildUpon()
            .setMediaMetadata(metadata)
            .build()
    }

    /**
     * Ticks the ad subtitle down to [untilElapsedMs] (the unmute deadline) once a second by
     * pushing metadata updates — the only channel the car's system-drawn now-playing gives us.
     * Uses ceil seconds to stay in step with the phone's own extras-driven countdown. Skips the
     * remote (cast) route, where the receiver runs its own preroll we can't measure.
     */
    private fun startAdCountdown(untilElapsedMs: Long) {
        adCountdownJob?.cancel()
        adCountdownJob = serviceScope.launch {
            while (isActive) {
                if (playerManager.player.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE) break
                val remainingMs = untilElapsedMs - SystemClock.elapsedRealtime()
                if (remainingMs <= 0) break
                val secs = ((remainingMs + 999) / 1000).toInt()
                playerManager.updateMediaItem(buildAdItem(RetroFmConfig.AD_COUNTDOWN_FORMAT.format(secs)))
                delay(1_000)
            }
        }
    }

    /**
     * Publishes "an ad is playing until X" to all controllers via session extras. The deadline
     * is on the [SystemClock.elapsedRealtime] clock (monotonic, shared across processes).
     * With [RetroFmConfig.MUTE_ADS] the player is also muted (app-internal volume, not the
     * device volume) until the announced duration elapses or regular track metadata arrives.
     */
    private fun setAdState(untilElapsedMs: Long) {
        // Ad handling (label, countdown, mute) only makes sense during active playback. ExoPlayer
        // buffers the live stream — and reads its ICY ad markers — even before the user presses
        // play, which surfaced the "Reklam" countdown pre-play. Ignore ad markers until playback
        // is actually wanted; a real ad on air when play starts is caught by the next marker.
        if (!playerManager.player.playWhenReady) {
            Timber.tag("NowPlaying").d("ad marker ignored — playback not started")
            return
        }
        Timber.tag("NowPlaying").d("ad break starts, until=+%d ms", untilElapsedMs - SystemClock.elapsedRealtime())
        adUntilElapsedMs = untilElapsedMs
        mediaLibrarySession.setSessionExtras(
            Bundle().apply { putLong(EXTRA_AD_UNTIL_ELAPSED_MS, untilElapsedMs) }
        )
        // Ad branding into the media metadata itself: the session extras only reach our own
        // phone UI, but the car's now-playing and the notification read the metadata — they
        // kept showing the previous track over muted audio (volume-shock confusion). The next
        // ICY track event restores real metadata after the break.
        applyTrackMetadata(
            TrackInfo(
                eventId = RetroFmConfig.AD_EVENT_ID,
                title = RetroFmConfig.AD_DISPLAY_TITLE,
                artist = RetroFmConfig.AD_DISPLAY_SUBTITLE,
                imageUrl = RetroFmConfig.LOGO_PNG_URL
            )
        )
        // The car has no room for our own countdown UI (the system draws now-playing), so tick
        // the ad subtitle down to the unmute moment via one-per-second metadata updates.
        startAdCountdown(untilElapsedMs)
        if (RetroFmConfig.MUTE_ADS) {
            // A marker can land mid-fade (the unmute ramp after the previous break): stop the
            // ramp before touching the volume so it can't raise it again underneath us.
            volumeFadeJob?.cancel()
            volumeFadeJob = null
            // Back-to-back ads re-announce themselves: only capture the volume on the first,
            // so a mid-break marker can't overwrite the saved level with our own 0. A running
            // fade still owns preAdVolume (cleared only on ramp completion), so a mid-fade
            // marker keeps the true pre-break level rather than capturing a half-raised one.
            if (preAdVolume == null) {
                preAdVolume = playerManager.player.volume
            }
            playerManager.player.volume = 0f
            adUnmuteJob?.cancel()
            adUnmuteJob = serviceScope.launch {
                delay(untilElapsedMs - SystemClock.elapsedRealtime())
                // Fallback unmute at the announced deadline; usually the ad-end metadata
                // (clearAdState via onMetadata) lands first or shortly after.
                clearAdState(fade = true)
            }
        }
    }

    /**
     * @param fade Ramp the volume back up over [RetroFmConfig.AD_UNMUTE_FADE_MS] instead of a
     *   hard cut. Only for the paths where music is audibly continuing (ICY ad-end, mute
     *   deadline); a pause, cast transfer, or idle-gap reset restores instantly — there is
     *   nothing to ease into.
     */
    private fun clearAdState(fade: Boolean = false) {
        if (adUntilElapsedMs == null) return
        Timber.tag("NowPlaying").d("ad break cleared")
        adUntilElapsedMs = null
        adUnmuteJob?.cancel()
        adUnmuteJob = null
        adCountdownJob?.cancel()
        adCountdownJob = null
        volumeFadeJob?.cancel()
        volumeFadeJob = null
        preAdVolume?.let { target ->
            if (fade) {
                startUnmuteFade(target)
            } else {
                playerManager.player.volume = target
                preAdVolume = null
            }
        }
        mediaLibrarySession.setSessionExtras(Bundle())
        // Restore the real track the moment the break lifts. adUntilElapsedMs is already null,
        // so this apply is no longer suppressed; an ICY ad-end frame (if that is what cleared
        // the break) applies its own track right after, deduped if identical.
        val pending = pendingAdTrack
        pendingAdTrack = null
        // No post-ad resync any more: the Icecast mount announces the current StreamTitle on
        // connect and at every boundary, so a break that ends without an ICY frame is corrected
        // by the next one within a song, and a reconnect re-announces immediately. The old
        // schedule fetch that filled this gap is gone with the Bauer API.
        if (pending != null) applyTrackMetadata(pending)
    }

    /**
     * Eases the post-ad unmute from 0 up to [target] over [RetroFmConfig.AD_UNMUTE_FADE_MS].
     * fraction² keeps the rise perceptually even (linear gain bunches it into the last third).
     * Owns [preAdVolume] until the ramp completes: a new ad marker mid-ramp cancels this job
     * and re-mutes, and must find the true pre-break level still captured, not a half-raised
     * value (see setAdState).
     */
    private fun startUnmuteFade(target: Float) {
        volumeFadeJob?.cancel()
        volumeFadeJob = serviceScope.launch {
            val steps = (RetroFmConfig.AD_UNMUTE_FADE_MS / RetroFmConfig.AD_UNMUTE_FADE_STEP_MS)
                .toInt().coerceAtLeast(1)
            for (step in 1..steps) {
                val fraction = step.toFloat() / steps
                playerManager.player.volume = target * fraction * fraction
                delay(RetroFmConfig.AD_UNMUTE_FADE_STEP_MS)
            }
            playerManager.player.volume = target
            preAdVolume = null
        }
    }

    /**
     * The audio side never resumes a stale buffer (a resume seeks the live edge); this is the
     * display's counterpart. Left alone, the last song of a drive sits on the idle now-playing
     * screen until the process dies — hours later it names a song a resume will not play.
     * After [RetroFmConfig.TRACK_INFO_STALE_AFTER_MS] without playback the display reverts to
     * station branding; cancelled the moment playback runs again.
     */
    private fun scheduleStaleInfoReset() {
        staleInfoJob?.cancel()
        staleInfoJob = serviceScope.launch {
            delay(RetroFmConfig.TRACK_INFO_STALE_AFTER_MS)
            // playWhenReady covers the connect/buffering window of a fresh play press.
            if (playerManager.player.isPlaying || playerManager.player.playWhenReady) return@launch
            Timber.tag("NowPlaying").d("track info stale — reverting to station branding")
            pendingAdTrack = null
            applyTrackMetadata(TrackInfo.stationFallback())
        }
    }

    /**
     * The wall-clock counterpart of [scheduleStaleInfoReset], for the car: parked, the whole
     * system suspends — the uptime clock (and the 5 min delay with it) freezes, so the idle
     * timer never fires and the previous drive's song greets the next one. Called whenever
     * playback is (about to be) running again; if the last alive stamp is older than
     * [RetroFmConfig.TRACK_INFO_STALE_AFTER_MS], everything display-related from before the
     * gap is stale: drop it to station branding and let the next ICY frame fill in the real
     * track. The reconnect announces the current StreamTitle immediately, so the branding is
     * on screen for seconds, not for the rest of the drive.
     *
     * [lastAppliedTrack] is cleared too: the returning track may well be the one that was
     * already showing, and dedup would otherwise swallow its re-apply.
     */
    private fun maybeHandleIdleGap() {
        val last = lastAliveWallMs ?: return
        val gapMs = System.currentTimeMillis() - last
        if (gapMs < RetroFmConfig.TRACK_INFO_STALE_AFTER_MS) return
        lastAliveWallMs = null // one-shot per gap; re-stamped by the heartbeat
        Timber.tag("NowPlaying").i("idle gap %d s — resetting stale display state", gapMs / 1000)
        pendingAdTrack = null
        clearAdState()
        lastAppliedTrack = null
        applyTrackMetadata(TrackInfo.stationFallback())
    }

    private fun startPlaybackHeartbeat() {
        if (playbackHeartbeatJob?.isActive == true) return
        // Start the track clock from now, not from whenever it last stopped: the gap in
        // between is exactly the stalled time that must not count. See maybeHandleFrozenMetadata.
        trackClock.start()
        playbackHeartbeatJob = serviceScope.launch {
            while (isActive) {
                lastAliveWallMs = System.currentTimeMillis()
                trackClock.tick()
                maybeHandleFrozenMetadata()
                delay(RetroFmConfig.PLAYBACK_HEARTBEAT_MS)
            }
        }
    }

    /**
     * The current track just announced itself again, which on this mount means it is ending.
     * If no new title follows within the grace period, whatever is on air is not the song still
     * named on screen — hand the display back to station branding.
     *
     * Measured, not guessed: see [RetroFmConfig.TRACK_HANDOVER_GRACE_MS]. Cancelled by the next
     * real boundary, so a normal song change never reaches the timeout.
     */
    private fun armHandoverTimeout() {
        handoverJob?.cancel()
        handoverJob = serviceScope.launch {
            delay(RetroFmConfig.TRACK_HANDOVER_GRACE_MS)
            val current = currentTrack ?: return@launch
            if (current.eventId <= 0 || adUntilElapsedMs != null) return@launch
            Timber.tag("NowPlaying").i(
                "no new title %d s after '%s' signalled its end — reverting to branding",
                RetroFmConfig.TRACK_HANDOVER_GRACE_MS / 1000, current.title
            )
            lastAppliedTrack = null
            applyTrackMetadata(TrackInfo.stationFallback())
        }
    }

    /**
     * Drop back to station branding when one title has been on screen, while playing, for
     * longer than any real track — the injector has frozen and the display is lying.
     *
     * This is the defence that did not exist when the old Bauer relay froze on 2026-07-31 and
     * the app showed "Talk Talk – It's My Life" for a week. It is deliberately blunt: it
     * cannot tell a frozen injector from a very long block, so the threshold sits far above
     * anything observed (see [RetroFmConfig.TRACK_FROZEN_AFTER_MS]).
     *
     * "While playing" is the load-bearing half, and [TrackPlayingClock] is what makes it true:
     * the budget is time the player was actually playing, not wall clock since the title was
     * applied. The car's modem stalls the stream for minutes at a time mid-song, and wall clock
     * made a 3.5 min rebuffer inside "Black Velvet" read as a frozen injector on 2026-08-15
     * (492 s against the 480 s threshold), blanking a title that was correct one second before
     * the next one arrived. A frozen injector still trips this: it freezes while audio plays.
     *
     * It does **not** solve the news bulletin, and no timeout can — see the config comment.
     */
    private fun maybeHandleFrozenMetadata() {
        val current = currentTrack ?: return
        if (current.eventId <= 0) return // already branding, or an ad
        if (adUntilElapsedMs != null) return
        val heldMs = trackClock.playingMs
        if (heldMs < RetroFmConfig.TRACK_FROZEN_AFTER_MS) return
        Timber.tag("NowPlaying").w(
            "metadata frozen — '%s' held %d s while playing, reverting to branding",
            current.title, heldMs / 1000
        )
        lastAppliedTrack = null
        applyTrackMetadata(TrackInfo.stationFallback())
    }

    private fun stopPlaybackHeartbeat() {
        playbackHeartbeatJob?.cancel()
        playbackHeartbeatJob = null
        // Bank what was played up to the stall, then stop the clock: everything from here until
        // audio returns is stalled time and must not count towards the freeze budget.
        trackClock.stop()
        lastAliveWallMs = System.currentTimeMillis()
        // Nothing is on air, so an end-of-track marker from before the pause must not fire a
        // branding swap into a stopped session — the idle path owns the display from here.
        handoverJob?.cancel()
    }

    private inner class PlaybackStateListener : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            // Fires at the play press itself, seconds before audio starts — the earliest
            // moment to swap a previous drive's song off the screen.
            if (playWhenReady) {
                maybeHandleIdleGap()
                // After the idle reset, so a held frame wins over the branding it just applied.
                drainPendingIcy()
            } else {
                pendingIcy.clear()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                staleInfoJob?.cancel()
                // Also here, not only at the play press: after a suspend mid-playback the car
                // resumes with playWhenReady still true, so onPlayWhenReadyChanged never fires.
                maybeHandleIdleGap()
                drainPendingIcy()
                startPlaybackHeartbeat()
            } else {
                stopPlaybackHeartbeat()
                // isPlaying also drops during a mid-ad REBUFFER (playWhenReady still true) —
                // clearing then unmuted the tail of the ad after every stall, which made ad
                // muting look intermittent in the car. Only a real pause clears: a paused
                // stream resumes at the live edge (PlayGatedPlayer), where this ad is over.
                if (!playerManager.player.playWhenReady) {
                    clearAdState()
                }
                scheduleStaleInfoReset()
                // Playback stopping is often the last event of a drive; ship what we have
                // while the process is still alive instead of waiting out the flush interval.
                logsinkClient?.let { client ->
                    serviceScope.launch {
                        client.flush()
                        client.persistNow()
                    }
                }
            }
        }

        override fun onDeviceInfoChanged(deviceInfo: DeviceInfo) {
            Timber.tag("RetroFmCast").i(
                "route=${if (deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE) "REMOTE" else "LOCAL"} " +
                    "state=${playerManager.player.playbackState} " +
                    "playWhenReady=${playerManager.player.playWhenReady} " +
                    "item=${playerManager.player.currentMediaItem?.localConfiguration?.uri} " +
                    "error=${playerManager.player.playerError?.errorCodeName}"
            )
            if (deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE) {
                // Ad state tracks the LOCAL stream's ICY markers; the receiver opens its own
                // session (with its own preroll we can neither detect nor mute). Clearing
                // also restores the volume, so a mute active at transfer can't leave the
                // receiver stuck at volume 0.
                clearAdState()
                nudgeCastToLiveEdge()
            }
        }

        // ICY in-stream metadata: the server announces spliced-in ads (preroll at connect,
        // midrolls in the same format) with an adw_ad marker and their exact duration — see
        // IcyAdMarker. Regular track metadata doubles as the ad-end signal, which also covers
        // the deadline drifting late when playback stalls mid-ad. Only fires on the local
        // route: while casting the receiver fetches the stream itself, so no false labels.
        override fun onMetadata(metadata: Metadata) {
            // No ICY processing unless playback is wanted. Normally the PlayGatedPlayer means
            // the stream isn't even open before play, but a paused player keeps buffering its
            // tail for a while — those frames must not fetch eventdata, flip metadata, or
            // engage ad state.
            //
            // Held rather than dropped, because "resume re-syncs within seconds" — what this
            // comment used to claim — is only true while the stream is already open. Opened
            // fresh, the mount's connect-time announcement is the ONLY one until the track
            // ends, and losing it costs the whole song (2026-08-22, "Material Girl": two
            // minutes of station logo). See PendingIcyFrame for why it expires.
            if (!playerManager.player.playWhenReady) {
                pendingIcy.hold(metadata)
                Timber.tag("NowPlaying").d("icy held — playback not requested")
                return
            }
            handleIcyMetadata(metadata)
        }

    }

    private fun handleIcyMetadata(metadata: Metadata) {
        for (i in 0 until metadata.length()) {
            val icy = metadata.get(i) as? IcyInfo ?: continue
            val durationMs = IcyAdMarker.parseDurationMs(icy.rawMetadata)
            if (durationMs != null) {
                // The tail extends both the mute and the "Reklam" label so they lift
                // together (see RetroFmConfig.AD_MUTE_TAIL_MS).
                setAdState(
                    SystemClock.elapsedRealtime() + durationMs + RetroFmConfig.AD_MUTE_TAIL_MS
                )
            } else {
                // Regular metadata doubles as the ad-end signal — music is audibly
                // resuming, so ease the volume back instead of a hard cut.
                clearAdState(fade = true)
                onIcyTrackMetadata(icy)
            }
        }
    }

    /**
     * Replays the announcement that arrived just before the play press, if it is still fresh.
     *
     * Idempotent: [PendingIcyFrame.take] empties the slot, so being called from both the play
     * press and the first isPlaying costs nothing — the second call finds nothing.
     */
    private fun drainPendingIcy() {
        val held = pendingIcy.take() ?: return
        Timber.tag("NowPlaying").d("replaying held icy frame")
        handleIcyMetadata(held)
    }

    /**
     * The transfer to a Cast receiver carries the local playback position, which the
     * unseekable live stream can't honor — the receiver stalls buffering until seeked to the
     * live edge (exactly what a manual pause/resume did). If the receiver hasn't started
     * playing shortly after the transfer, seek it to the live edge automatically.
     */
    private fun nudgeCastToLiveEdge() {
        serviceScope.launch {
            delay(RetroFmConfig.CAST_LIVE_EDGE_NUDGE_DELAY_MS)
            val player = playerManager.player
            val stillStuckOnRemote =
                player.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE &&
                    player.playWhenReady && !player.isPlaying
            if (stillStuckOnRemote) {
                Timber.tag("RetroFmCast").i("receiver stuck after transfer — seeking live edge")
                player.seekToDefaultPosition()
                player.play()
            }
        }
    }

    /**
     * A track boundary announced by the stream itself, delivered exactly when it becomes
     * audible. Since the 2026-08-08 move to the station's own Icecast this is the *only*
     * now-playing source: the mount sends `StreamTitle='Title - Artist'` with no StreamUrl and
     * no event id, so the title text is parsed directly (see [TrackInfo.fromStreamTitle]).
     *
     * The mount also announces the current title on connect, not just at changes, which is why
     * no schedule fetch is needed to recover the display after a reconnect or an ad break.
     * That announcement is the **only** one until the track ends, so it must not be dropped —
     * when it lands before the play press it is held rather than ignored (see [PendingIcyFrame]).
     */
    private fun onIcyTrackMetadata(icy: IcyInfo) {
        Timber.tag("NowPlaying").d("icy boundary: title='%s'", icy.title)
        val track = TrackInfo.fromStreamTitle(icy.title)
        if (track == null) {
            // Empty StreamTitle: jingles, between-song gaps, news. Reverting to the station
            // logo on each of these made the car surface flash the logo between every pair of
            // songs, so keep a real track that is already on screen (and no ad running) until
            // the next boundary; only fall back to branding when there is nothing to keep.
            val current = currentTrack
            if (current != null && current.eventId > 0 && adUntilElapsedMs == null) {
                Timber.tag("NowPlaying").d("empty icy frame — keeping current eventId=%d", current.eventId)
                return
            }
            applyTrackMetadata(TrackInfo.stationFallback())
            return
        }
        if (track.eventId == currentTrack?.eventId) {
            // The mount repeats the current title once, a few seconds before the next one —
            // effectively an end-of-track marker (see RetroFmConfig.TRACK_HANDOVER_GRACE_MS).
            // Arm the handover timer and let the rest of the boundary handling run: the repeat
            // is also the second chance for an artwork lookup that failed the first time.
            armHandoverTimeout()
        } else {
            handoverJob?.cancel()
        }
        // One lookup in flight at a time: a new boundary cancels the previous one, so a slow
        // response for a song that has already ended can't overwrite its successor.
        artworkJob?.cancel()
        artworkJob = serviceScope.launch {
            // Hold before applying: compensates the station-side metadata lead (see
            // RetroFmConfig.ICY_UPSTREAM_LEAD_MS, currently 0). Launch order on the Main
            // dispatcher preserves boundary order for back-to-back events.
            delay(RetroFmConfig.ICY_UPSTREAM_LEAD_MS)

            // Resolve the cover BEFORE publishing, so each track updates the surfaces exactly
            // once. Applying the title first and the art second made the car flash the station
            // logo on every song (field-tested 2026-08-08).
            //
            // Two sources, started together and never in sequence. The station's own page knows
            // which *record* is playing — iTunes can only match the announced text, and returns
            // the 1984 original for a remix the station is actually spinning — so it is
            // preferred when it confirms this track. But it is the slower and less certain of
            // the two, and the car's modem punishes a serial second request: every drive's
            // first iTunes lookup timed out at 8 s during the week of 2026-08-13. In parallel,
            // a station page that is late or disagreeing costs nothing, because the iTunes
            // answer is already in hand by the time we stop waiting for it.
            val station = async(Dispatchers.IO) {
                StationNowPlaying.artworkUrl(track.title, track.artist)
            }
            val lookup = async(Dispatchers.IO) {
                ArtworkLookup.artworkUrl(track.artist, track.title)
            }
            val startedAt = SystemClock.elapsedRealtime()
            val stationUrl =
                withTimeoutOrNull(RetroFmConfig.STATION_ARTWORK_BUDGET_MS) { station.await() }
            if (stationUrl == null) station.cancel()
            val remaining = RetroFmConfig.ARTWORK_FIRST_APPLY_BUDGET_MS -
                (SystemClock.elapsedRealtime() - startedAt)
            val url = stationUrl
                ?: if (remaining > 0) withTimeoutOrNull(remaining) { lookup.await() } else null
            applyTrackMetadata(url?.let { track.copy(imageUrl = it) } ?: track)
            if (url != null) return@launch

            // Budget blown: the title is already out with the logo. Let the lookup finish and
            // upgrade the cover if this is still the track being played (or the one held back
            // by an ad break). Rare — cache hits and normal responses land inside the budget.
            val late = runCatching { lookup.await() }.getOrNull() ?: return@launch
            val stillRelevant = currentTrack?.eventId == track.eventId ||
                pendingAdTrack?.eventId == track.eventId
            if (!stillRelevant) {
                Timber.tag("Artwork").d("late artwork discarded — track moved on")
                return@launch
            }
            Timber.tag("Artwork").d("late artwork applied for eventId=%d", track.eventId)
            applyTrackMetadata(track.copy(imageUrl = late))
        }
    }

    private inner class RetroFmMediaLibraryCallback : MediaLibrarySession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            // Log EVERY controller that connects — including the car launcher / any Google
            // verification client — so we can see whether the "check Google Play" flow ever
            // reaches us and what it is. We accept all (default).
            Timber.tag("Connect").i(
                "onConnect from %s uid=%d controllerVersion=%d",
                controller.packageName, controller.uid, controller.controllerVersion
            )
            return super.onConnect(session, controller)
        }

        override fun onPostConnect(session: MediaSession, controller: MediaSession.ControllerInfo) {
            Timber.tag("Connect").d("onPostConnect %s", controller.packageName)
        }

        override fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) {
            Timber.tag("Connect").i("onDisconnected %s", controller.packageName)
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: MediaLibraryService.LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            // DEBUG-level so a remote investigation (sink level DEBUG) captures exactly what
            // the car's media host asks for — undebuggable via adb in a real car.
            Timber.tag("MediaLibrary").d(
                "onGetLibraryRoot from %s (recent=%b, suggested=%b)",
                browser.packageName, params?.isRecent == true, params?.isSuggested == true
            )
            // Opt out of media resumption: the RECENT root is what the car uses to auto-resume
            // the last media app at boot. That auto-launch happens during the ~2 min window when
            // the Play Store isn't ready yet, so the launcher's Play verification of this
            // internal-testing app fails and shows "check that Google Play is enabled" on repeat.
            // Declining the recent root means the car won't auto-launch us at boot — the user
            // starts playback manually once things have settled (and Play is up).
            if (params?.isRecent == true) {
                Timber.tag("MediaLibrary").d("declining recent root (no auto-resume)")
                return Futures.immediateFuture(
                    LibraryResult.ofError(LibraryResult.RESULT_ERROR_NOT_SUPPORTED)
                )
            }
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
            val children = MediaItemTree.getChildren(parentId, currentTrack)
            Timber.tag("MediaLibrary").d(
                "onGetChildren(%s) from %s -> %d children",
                parentId, browser.packageName, children.size
            )
            return Futures.immediateFuture(
                LibraryResult.ofItemList(ImmutableList.copyOf(children), params)
            )
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
            Timber.tag("MediaLibrary").d("onGetItem(%s) from %s", mediaId, browser.packageName)
            val item = when (mediaId) {
                MediaItemTree.ROOT_ID -> MediaItemTree.getRootItem()
                MediaItemTree.STATIONS_TAB_ID -> MediaItemTree.getStationsTabItem()
                MediaItemTree.STATION_ID -> MediaItemTree.getStationItem()
                else -> null
            }
            return Futures.immediateFuture(
                if (item != null) LibraryResult.ofItem(item, null)
                else LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
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

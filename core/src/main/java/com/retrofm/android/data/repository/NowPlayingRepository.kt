package com.retrofm.android.data.repository

import com.retrofm.android.data.api.RetroFmApi
import com.retrofm.android.data.config.RetroFmConfig
import com.retrofm.android.data.model.EventDataResponse
import com.retrofm.android.data.model.NowPlayingResponse
import com.retrofm.android.data.model.TrackInfo

class NowPlayingRepository(private val api: RetroFmApi) {

    suspend fun fetchNowPlaying(): Result<TrackInfo> = fetch {
        api.getNowPlaying().toTrackInfo()
    }

    /** Exact lookup of the event the stream itself announced via ICY metadata. */
    suspend fun fetchEventData(eventId: Long): Result<TrackInfo> = fetch {
        api.getEventData(eventId).toTrackInfo()
    }

    suspend fun fetchRecentPlaylist(): Result<List<TrackInfo>> = fetch {
        api.getRecentPlaylist(RetroFmConfig.STATION_CODE).map { it.toTrackInfo() }
    }

    private inline fun <T> fetch(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: Exception) {
        Result.failure(e)
    }

    private fun EventDataResponse.toTrackInfo(): TrackInfo {
        val isSongEvent =
            eventType == "Song" && eventSongTitle.isNotBlank() && eventSongArtist.isNotBlank()
        return if (isSongEvent) {
            TrackInfo(
                eventId = eventId,
                title = eventSongTitle,
                artist = eventSongArtist,
                imageUrl = eventImageUrl?.takeIf { it.isNotBlank() }
                    ?: eventImageUrlSmall?.takeIf { it.isNotBlank() }
                    ?: RetroFmConfig.LOGO_PNG_URL,
                startTime = eventStart,
                finishTime = eventFinish
            )
        } else {
            stationFallback(eventId, eventStart, eventFinish)
        }
    }

    private fun NowPlayingResponse.toTrackInfo(): TrackInfo {
        val isSongEvent = eventType == "S" && trackTitle.isNotBlank() && artistName.isNotBlank()
        return if (isSongEvent) {
            TrackInfo(
                eventId = eventId,
                title = trackTitle,
                artist = artistName,
                imageUrl = imageUrl?.takeIf { it.isNotBlank() }
                    ?: artistImageUrl?.takeIf { it.isNotBlank() }
                    ?: imageUrlSmall?.takeIf { it.isNotBlank() }
                    ?: RetroFmConfig.LOGO_PNG_URL,
                startTime = eventStart,
                finishTime = eventFinish
            )
        } else {
            stationFallback(eventId, eventStart, eventFinish)
        }
    }

    companion object {
        /** Station-branding placeholder for moments without a song event (news, jingles, ads). */
        fun stationFallback(
            eventId: Long,
            startTime: String? = null,
            finishTime: String? = null
        ): TrackInfo = TrackInfo(
            eventId = eventId,
            title = RetroFmConfig.STATION_NAME,
            artist = RetroFmConfig.STATION_STRAPLINE,
            imageUrl = RetroFmConfig.LOGO_PNG_URL,
            startTime = startTime,
            finishTime = finishTime
        )
    }
}

package com.retrofm.android.data.repository

import com.retrofm.android.data.api.RetroFmApi
import com.retrofm.android.data.config.RetroFmConfig
import com.retrofm.android.data.model.NowPlayingResponse
import com.retrofm.android.data.model.TrackInfo

class NowPlayingRepository(private val api: RetroFmApi) {

    suspend fun fetchNowPlaying(): Result<TrackInfo> = fetch {
        api.getNowPlaying().toTrackInfo()
    }

    suspend fun fetchRecentPlaylist(): Result<List<TrackInfo>> = fetch {
        api.getRecentPlaylist(RetroFmConfig.STATION_CODE).map { it.toTrackInfo() }
    }

    private inline fun <T> fetch(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: Exception) {
        Result.failure(e)
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
            TrackInfo(
                eventId = eventId,
                title = RetroFmConfig.STATION_NAME,
                artist = RetroFmConfig.STATION_STRAPLINE,
                imageUrl = RetroFmConfig.LOGO_PNG_URL,
                startTime = eventStart,
                finishTime = eventFinish
            )
        }
    }
}

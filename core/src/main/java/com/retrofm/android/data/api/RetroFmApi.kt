package com.retrofm.android.data.api

import com.retrofm.android.data.model.EventDataResponse
import com.retrofm.android.data.model.NowPlayingResponse
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface RetroFmApi {
    @GET("nowplaying/res")
    suspend fun getNowPlaying(): NowPlayingResponse

    /** Exact event lookup; the id comes from the stream's ICY StreamUrl (see IcyAdMarker). */
    @GET("eventdata/{eventId}")
    suspend fun getEventData(@Path("eventId") eventId: Long): EventDataResponse

    @GET("playlist/")
    suspend fun getRecentPlaylist(@Query("StationCode") stationCode: String): List<NowPlayingResponse>
}

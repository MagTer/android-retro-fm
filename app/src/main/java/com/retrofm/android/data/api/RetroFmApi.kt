package com.retrofm.android.data.api

import com.retrofm.android.data.model.NowPlayingResponse
import retrofit2.http.GET
import retrofit2.http.Query

interface RetroFmApi {
    @GET("nowplaying/res")
    suspend fun getNowPlaying(): NowPlayingResponse

    @GET("playlist/")
    suspend fun getRecentPlaylist(@Query("StationCode") stationCode: String): List<NowPlayingResponse>
}

package com.retrofm.android.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.retrofm.android.data.config.RetroFmConfig

object MediaItemTree {
    const val ROOT_ID = "root"
    const val STATION_ID = "retro_fm_station"

    private val rootMediaItem: MediaItem = MediaItem.Builder()
        .setMediaId(ROOT_ID)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(RetroFmConfig.STATION_NAME)
                .setSubtitle(RetroFmConfig.STATION_STRAPLINE)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
                .build()
        )
        .build()

    private val stationMediaItem: MediaItem = MediaItem.Builder()
        .setMediaId(STATION_ID)
        .setUri(RetroFmConfig.STREAM_URL_MP3)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(RetroFmConfig.STATION_NAME)
                .setSubtitle(RetroFmConfig.STATION_STRAPLINE)
                .setArtist(RetroFmConfig.STATION_NAME)
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
                .setArtworkUri(Uri.parse(RetroFmConfig.LOGO_PNG_URL))
                .build()
        )
        .build()

    fun getRootItem(): MediaItem = rootMediaItem
    fun getStationItem(): MediaItem = stationMediaItem
}

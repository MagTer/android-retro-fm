package com.retrofm.android.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
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
        // Mandatory for casting: the Media3 cast item converter throws on a MediaItem with no
        // mimeType. Harmless locally — ExoPlayer already sniffs the MP3 stream.
        .setMimeType(MimeTypes.AUDIO_MPEG)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(RetroFmConfig.STATION_NAME)
                .setSubtitle(RetroFmConfig.STATION_STRAPLINE)
                // Strapline, not the station name again — the UI/notification render this as
                // the second line under the title.
                .setArtist(RetroFmConfig.STATION_STRAPLINE)
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

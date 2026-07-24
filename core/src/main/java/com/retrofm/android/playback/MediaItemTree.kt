package com.retrofm.android.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import com.retrofm.android.data.config.RetroFmConfig
import com.retrofm.android.data.model.TrackInfo

/**
 * Browse tree shaped for the Automotive OS media UI, which renders the ROOT's children as
 * TABS and expects each tab to be a browsable node whose children are the playable items.
 * The old two-level tree (root -> playable station directly) made the car render one tab
 * with nothing browsable inside it — shown as "Inget innehåll finns för bläddring" over the
 * whole browse pane. Android Auto tolerated it; the built-in car UI does not.
 *
 *   root (browsable)
 *   └── stations tab (browsable, MEDIA_TYPE_FOLDER_RADIO_STATIONS)
 *       └── Retro FM (playable live stream)
 */
object MediaItemTree {
    const val ROOT_ID = "root"
    const val STATIONS_TAB_ID = "stations"
    const val STATION_ID = "retro_fm_station"

    private val rootMediaItem: MediaItem = MediaItem.Builder()
        .setMediaId(ROOT_ID)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(RetroFmConfig.STATION_NAME)
                .setSubtitle(RetroFmConfig.STATION_STRAPLINE)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS)
                .build()
        )
        .build()

    private val stationsTabItem: MediaItem = MediaItem.Builder()
        .setMediaId(STATIONS_TAB_ID)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(RetroFmConfig.STATION_NAME)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS)
                .setArtworkUri(AlbumArtContentProvider.mapUri(Uri.parse(RetroFmConfig.LOGO_PNG_URL)))
                // Children of this tab render as grid tiles (big artwork cards).
                .setExtras(gridHintExtras())
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
                .setArtworkUri(AlbumArtContentProvider.mapUri(Uri.parse(RetroFmConfig.LOGO_PNG_URL)))
                .build()
        )
        .build()

    fun getRootItem(): MediaItem = rootMediaItem
    fun getStationsTabItem(): MediaItem = stationsTabItem
    fun getStationItem(): MediaItem = stationMediaItem

    /**
     * Children per browsable node; empty for unknown/leaf ids. The station's BROWSE
     * representation is live: it carries the currently playing track's title/artist/artwork
     * (falling back to station branding) so the car's big browse pane works as a now-playing
     * card instead of a static sign. The service calls notifyChildrenChanged on track changes.
     */
    fun getChildren(parentId: String, nowPlaying: TrackInfo? = null): List<MediaItem> =
        when (parentId) {
            ROOT_ID -> listOf(stationsTabItem)
            STATIONS_TAB_ID -> listOf(stationBrowseItem(nowPlaying))
            else -> emptyList()
        }

    private fun stationBrowseItem(nowPlaying: TrackInfo?): MediaItem {
        if (nowPlaying == null) return stationMediaItem
        return stationMediaItem.buildUpon()
            .setMediaMetadata(
                stationMediaItem.mediaMetadata.buildUpon()
                    .setTitle(nowPlaying.title)
                    .setSubtitle(nowPlaying.artist)
                    .setArtist(nowPlaying.artist)
                    .setArtworkUri(nowPlaying.imageUrl?.let { AlbumArtContentProvider.mapUri(Uri.parse(it)) })
                    .build()
            )
            .build()
    }

    /**
     * Legacy content-style hints (documented Android Auto/AAOS keys, bridged by Media3):
     * render playable children as GRID tiles — a big artwork card instead of a list row.
     */
    private fun gridHintExtras(): Bundle = Bundle().apply {
        putInt("android.media.browse.CONTENT_STYLE_PLAYABLE_HINT", 2 /* grid */)
    }
}

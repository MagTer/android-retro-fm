package com.retrofm.android.playback

import androidx.media3.cast.DefaultMediaItemConverter
import androidx.media3.cast.MediaItemConverter
import androidx.media3.common.MediaItem
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaQueueItem

/**
 * Wraps [DefaultMediaItemConverter] but marks the content as a LIVE stream. The default
 * converter hardcodes STREAM_TYPE_BUFFERED, which makes the Default Media Receiver treat the
 * endless Icecast stream as a file whose duration it waits to determine — observed on a Nest
 * Hub as "loading forever". Everything else (contentId, contentType, metadata, and the
 * customData that round-trips the Media3 MediaItem) is preserved from the default conversion.
 */
class RetroFmMediaItemConverter : MediaItemConverter {

    private val delegate = DefaultMediaItemConverter()

    override fun toMediaQueueItem(mediaItem: MediaItem): MediaQueueItem {
        val defaultItem = delegate.toMediaQueueItem(mediaItem)
        val info = requireNotNull(defaultItem.media)
        // contentId must be the actual stream URL: the Default Media Receiver treats
        // contentId as the media URL and may ignore the newer contentUrl field — with the
        // default conversion it tried to load the literal mediaId "retro_fm_station"
        // (observed: session connects, volume works, media loads forever). The Media3
        // round-trip (toMediaItem) is unaffected: it restores the MediaItem from customData.
        val contentId = mediaItem.localConfiguration?.uri?.toString() ?: info.contentId
        val liveInfo = MediaInfo.Builder(contentId)
            .setStreamType(MediaInfo.STREAM_TYPE_LIVE)
            .setStreamDuration(MediaInfo.UNKNOWN_DURATION)
            .setContentType(info.contentType ?: "audio/mpeg")
            .setMetadata(info.metadata)
            .apply {
                info.contentUrl?.let { setContentUrl(it) }
                info.customData?.let { setCustomData(it) }
            }
            .build()
        return MediaQueueItem.Builder(liveInfo)
            .setAutoplay(defaultItem.autoplay)
            .apply { defaultItem.customData?.let { setCustomData(it) } }
            .build()
    }

    override fun toMediaItem(mediaQueueItem: MediaQueueItem): MediaItem =
        delegate.toMediaItem(mediaQueueItem)
}

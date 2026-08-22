package com.retrofm.android.playback

import timber.log.Timber
import androidx.media3.cast.DefaultMediaItemConverter
import androidx.media3.cast.MediaItemConverter
import androidx.media3.common.MediaItem
import com.retrofm.android.data.config.RetroFmConfig
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaQueueItem

/**
 * The boundary between this app's MediaItem and what a Cast receiver is actually sent.
 *
 * Three things are receiver-specific and all of them belong here rather than in the shared
 * item, which is also read by the phone UI, the notification, Android Auto and Automotive:
 *  - **LIVE stream type.** [DefaultMediaItemConverter] hardcodes STREAM_TYPE_BUFFERED, which
 *    makes the Default Media Receiver treat the endless Icecast stream as a file whose
 *    duration it waits to determine — observed on a Nest Hub as "loading forever".
 *  - **contentId as the real stream URL**, because that receiver treats contentId as the media
 *    URL and may ignore the newer contentUrl.
 *  - **Artwork as a reachable https URL** rather than the `content://` proxy Automotive needs
 *    — see [forReceiver].
 *
 * The customData that round-trips the Media3 MediaItem is preserved from the default
 * conversion, so [toMediaItem] still restores the original item.
 */
class RetroFmMediaItemConverter : MediaItemConverter {

    private val delegate = DefaultMediaItemConverter()

    override fun toMediaQueueItem(mediaItem: MediaItem): MediaQueueItem {
        val defaultItem = delegate.toMediaQueueItem(forReceiver(mediaItem))
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
            // Cast's notion of the content, from one documented place — see
            // RetroFmConfig.CAST_CONTENT_TYPE, which records that this is knowingly wrong
            // for the actual bytes and why correcting it needs a measurement first.
            .setContentType(RetroFmConfig.CAST_CONTENT_TYPE)
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
            .also { Timber.tag(TAG).i("LOAD payload: %s", it.toJson()) }
    }

    override fun toMediaItem(mediaQueueItem: MediaQueueItem): MediaItem =
        delegate.toMediaItem(mediaQueueItem)

    /**
     * Rewrites the artwork back to the URL it came from, because the receiver is a different
     * device.
     *
     * Every MediaItem carries its artwork as a `content://` URI: Automotive renders **only**
     * local URIs, so [AlbumArtContentProvider] proxies the remote image behind one. A Cast
     * receiver has the opposite requirement — the Cast SDK hands the image URL to a device on
     * the network, which has no way to reach an Android ContentProvider in this app's process.
     * Until 2026-08-22 the LOAD payload carried `content://com.magter.retrofm.artwork/…`, so
     * the receiver could never show a cover and we spent ~200 characters of base64 per LOAD
     * saying so.
     *
     * This is the right seam for it. The one MediaItem is read by the phone UI, the
     * notification, Android Auto, Automotive and Cast, and only the last of those needs the
     * mapping undone — so it is undone here, at the Cast boundary, rather than by teaching the
     * service which route it is on. A device-type check is deliberately **not** involved: an
     * audio-only speaker simply ignores the image, and Cast's only hard rule for such devices
     * concerns video streams.
     */
    internal fun forReceiver(mediaItem: MediaItem): MediaItem {
        val art = mediaItem.mediaMetadata.artworkUri ?: return mediaItem
        val remote = AlbumArtContentProvider.remoteUriOf(art) ?: return mediaItem
        return mediaItem.buildUpon()
            .setMediaMetadata(
                mediaItem.mediaMetadata.buildUpon().setArtworkUri(remote).build()
            )
            .build()
    }

    private companion object {
        const val TAG = "RetroFmCast"
    }
}

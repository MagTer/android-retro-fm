package com.retrofm.android.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.retrofm.android.data.config.RetroFmConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Guards the Cast-boundary rewrite of the artwork URI.
 *
 * A Cast receiver is a different device on the network and cannot resolve an Android
 * ContentProvider URI, but every MediaItem carries its artwork as one because Automotive
 * renders only local URIs. Until 2026-08-22 the LOAD payload was sent with
 * `content://com.magter.retrofm.artwork/…` and the receiver could never show a cover.
 *
 * Only the two rewrites are testable here — building the real MediaQueueItem needs Play
 * services, which is not on the JVM classpath, so the payload itself is verified in the field
 * logs (`RetroFmCast LOAD payload`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RetroFmMediaItemConverterTest {

    private val converter = RetroFmMediaItemConverter()
    private val cover =
        "https://is1-ssl.mzstatic.com/image/thumb/Music122/v4/2f/13/8d/x/5099945531354.jpg/" +
            "${RetroFmConfig.ARTWORK_RENDITION}.jpg"

    private fun itemWithArtwork(uri: Uri?): MediaItem =
        MediaItem.Builder()
            .setMediaId("retro_fm_station")
            .setUri(RetroFmConfig.STREAM_URL)
            .setMediaMetadata(MediaMetadata.Builder().setArtworkUri(uri).build())
            .build()

    @Test
    fun `the receiver is sent a reachable https cover, never a content uri`() {
        val local = AlbumArtContentProvider.mapUri(Uri.parse(cover))
        assertEquals("content", local.scheme)          // what every other surface gets

        val forCast = converter.forReceiver(itemWithArtwork(local))
        assertEquals(Uri.parse(cover), forCast.mediaMetadata.artworkUri)
    }

    /** The station logo takes the same path and must survive it. */
    @Test
    fun `the branding cover is rewritten too`() {
        val local = AlbumArtContentProvider.mapUri(Uri.parse(RetroFmConfig.LOGO_PNG_URL))
        val forCast = converter.forReceiver(itemWithArtwork(local))
        assertEquals(Uri.parse(RetroFmConfig.LOGO_PNG_URL), forCast.mediaMetadata.artworkUri)
    }

    @Test
    fun `an item without artwork is passed through untouched`() {
        val item = itemWithArtwork(null)
        assertNull(converter.forReceiver(item).mediaMetadata.artworkUri)
    }

    /** Never rewrite something this app did not map — it would corrupt a foreign URL. */
    @Test
    fun `a plain https cover is left alone`() {
        val direct = Uri.parse(cover)
        assertEquals(direct, converter.forReceiver(itemWithArtwork(direct)).mediaMetadata.artworkUri)
    }

    /**
     * The receiver's form must not become the session's. Media3's own converter rebuilds the
     * MediaMetadata from the Cast metadata, artwork included, so without this the https URL we
     * sent comes straight back as the shared item's artworkUri — observed in the field on
     * 2026-08-23 11:24:37 as `loadBitmap https://media.bauerradio.com/image/upload/…` where
     * every local-route load logs the `content://` form.
     */
    @Test
    fun `the receiver's https cover is mapped back to a content uri`() {
        val local = AlbumArtContentProvider.mapUri(Uri.parse(cover))
        val backIn = converter.forSession(itemWithArtwork(Uri.parse(cover)))
        assertEquals(local, backIn.mediaMetadata.artworkUri)
    }

    /** The round trip is what matters: out to the receiver and back in must be the identity. */
    @Test
    fun `a cast round trip leaves the artwork exactly as it started`() {
        val local = AlbumArtContentProvider.mapUri(Uri.parse(cover))
        val out = converter.forReceiver(itemWithArtwork(local))
        assertEquals(local, converter.forSession(out).mediaMetadata.artworkUri)
    }

    /**
     * A queue item this app never built can carry any cover. Mapping it would hand the session
     * a content:// that openFile then refuses — a cover that would have rendered becomes
     * nothing at all, which is worse than leaving it alone.
     */
    @Test
    fun `a cover this provider cannot serve is left as it is`() {
        val foreign = Uri.parse("https://example.com/someone-elses-cover.jpg")
        assertEquals(foreign, converter.forSession(itemWithArtwork(foreign)).mediaMetadata.artworkUri)
    }

    /** A content:// artwork must never be wrapped a second time. */
    @Test
    fun `an item already carrying a content uri is untouched`() {
        val local = AlbumArtContentProvider.mapUri(Uri.parse(cover))
        assertEquals(local, converter.forSession(itemWithArtwork(local)).mediaMetadata.artworkUri)
    }

    @Test
    fun `an item without artwork survives the way back too`() {
        assertNull(converter.forSession(itemWithArtwork(null)).mediaMetadata.artworkUri)
    }
}

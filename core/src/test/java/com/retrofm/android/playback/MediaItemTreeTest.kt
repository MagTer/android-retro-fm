package com.retrofm.android.playback

import androidx.media3.common.MimeTypes
import com.retrofm.android.data.config.RetroFmConfig
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric-backed so [android.net.Uri] and [androidx.media3.common.MediaItem] behave like on
 * device. Pinned to SDK 34 (Uri/MediaItem behaviour is identical) to stay independent of the
 * compileSdk/targetSdk level.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaItemTreeTest {

    @Test
    fun `station item carries the AUDIO_MPEG mimeType required for casting`() {
        // The Media3 cast item converter throws on a MediaItem with no mimeType, so this is a
        // hard requirement for casting to work at all (CAST-PLAN §2.1).
        val config = MediaItemTree.getStationItem().localConfiguration
        assertEquals(MimeTypes.AUDIO_MPEG, config?.mimeType)
    }

    @Test
    fun `station item keeps the configured MP3 stream uri`() {
        val config = MediaItemTree.getStationItem().localConfiguration
        assertEquals(RetroFmConfig.STREAM_URL_MP3, config?.uri?.toString())
    }

    @Test
    fun `tree is car-shaped - browsable tab under root, playable station under tab`() {
        // The AAOS media UI renders root children as tabs and needs them browsable;
        // a playable item directly under root shows "no browsable content" in the car.
        val tabs = MediaItemTree.getChildren(MediaItemTree.ROOT_ID)
        assertEquals(1, tabs.size)
        assertEquals(true, tabs[0].mediaMetadata.isBrowsable)

        val items = MediaItemTree.getChildren(MediaItemTree.STATIONS_TAB_ID)
        assertEquals(1, items.size)
        assertEquals(true, items[0].mediaMetadata.isPlayable)
        assertEquals(MediaItemTree.STATION_ID, items[0].mediaId)

        assertEquals(0, MediaItemTree.getChildren("bogus").size)
    }
}

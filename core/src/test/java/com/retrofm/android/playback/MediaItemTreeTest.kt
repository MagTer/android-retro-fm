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
}

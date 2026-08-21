package com.retrofm.android.playback

import android.net.Uri
import com.retrofm.android.data.api.StationNowPlaying
import com.retrofm.android.data.config.RetroFmConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression guard for a diagnosability bug, not a rendering one: every iTunes cover logged as
 * the same line.
 *
 * [AlbumArtContentProvider.describe] shortens the ~300-character content:// URI for the field
 * logs, and it used to render host + *last* path segment. Apple serves every cover under the
 * same rendition filename, so that was "is1-ssl.mzstatic.com/600x600bb.jpg" for every track of
 * every drive. When a wrong cover was reported from the car on 2026-08-21 the logs could not say
 * which image had been on screen — the covers had to be re-fetched from the CDN by hand to find
 * out. The segment before the rendition is the release's UPC and does identify it.
 *
 * Robolectric-backed so [Uri] and `android.util.Base64` behave like on device, pinned to SDK 34
 * as in [MediaItemTreeTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AlbumArtDescribeTest {

    private fun describeRemote(url: String): String =
        AlbumArtContentProvider.describe(AlbumArtContentProvider.mapUri(Uri.parse(url)))

    /** Real shapes, taken from an iTunes Search response (verified 2026-08-21). */
    private val limahlSoundtrack =
        "https://is1-ssl.mzstatic.com/image/thumb/Music122/v4/2f/13/8d/" +
            "2f138d2f-07fd-2ae5-febc-cce670987391/5099945531354.jpg/" +
            "${RetroFmConfig.ARTWORK_RENDITION}.jpg"
    private val limahlDontSuppose =
        "https://is1-ssl.mzstatic.com/image/thumb/Music112/v4/4c/e1/31/" +
            "4ce131b3-30b3-b541-440f-ba8183aaed32/5099930948259.jpg/" +
            "${RetroFmConfig.ARTWORK_RENDITION}.jpg"

    @Test
    fun `two different itunes covers do not describe identically`() {
        // The whole point: these two are different records by the same artist, and the old
        // rendering made them the same log line.
        assertNotEquals(describeRemote(limahlSoundtrack), describeRemote(limahlDontSuppose))
    }

    @Test
    fun `an itunes cover is named by its release, not by the rendition`() {
        assertEquals("is1-ssl.mzstatic.com/5099945531354.jpg", describeRemote(limahlSoundtrack))
    }

    /** Log lines are shipped over the wire, so their size is a real cost. */
    @Test
    fun `the description stays short enough to log in a loop`() {
        assertTrue(describeRemote(limahlSoundtrack).length <= 60)
    }

    @Test
    fun `the station's own cover keeps its album id`() {
        val album = "fb5a6f3a-ba0f-450a-8bcb-1251b3896da8"
        val description = describeRemote(StationNowPlaying.artworkUrlFor(album))
        assertEquals("retrofm.se/$album${RetroFmConfig.STATION_ARTWORK_SUFFIX}", description)
    }

    /** The logo has no rendition segment; it must survive the skip untouched. */
    @Test
    fun `the station logo keeps its filename`() {
        assertEquals(
            "media.bauerradio.com/dwxxo0kehcboelrutfnm.png",
            describeRemote(RetroFmConfig.LOGO_PNG_URL)
        )
    }

    @Test
    fun `a uri this provider does not serve is passed through unchanged`() {
        val foreign = "content://com.example.other/thing"
        assertEquals(foreign, AlbumArtContentProvider.describe(Uri.parse(foreign)))
    }
}

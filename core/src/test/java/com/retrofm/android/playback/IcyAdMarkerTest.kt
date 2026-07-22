package com.retrofm.android.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IcyAdMarkerTest {

    @Test
    fun `parses duration from a real preroll marker`() {
        // Captured live from the Retro FM stream 2026-07-22.
        val raw = "StreamTitle='';StreamUrl='';adw_ad='true';durationMilliseconds='30024';" +
            "adId='232764';insertionType='preroll';"
        assertEquals(30_024L, IcyAdMarker.parseDurationMs(raw.toByteArray()))
    }

    @Test
    fun `ad marker without duration falls back to default`() {
        val raw = "StreamTitle='';adw_ad='true';insertionType='midroll';"
        assertEquals(30_000L, IcyAdMarker.parseDurationMs(raw.toByteArray()))
    }

    @Test
    fun `regular track metadata is not an ad`() {
        val raw = "StreamTitle='Kyrie - Mr. Mister';" +
            "StreamUrl='https://listenapi.planetradio.co.uk/api9.2/eventdata/398586160';"
        assertNull(IcyAdMarker.parseDurationMs(raw.toByteArray()))
    }

    @Test
    fun `empty no-event metadata is not an ad`() {
        val raw = "StreamTitle='';StreamUrl='https://listenapi.planetradio.co.uk/api9.2/eventdata/-1';"
        assertNull(IcyAdMarker.parseDurationMs(raw.toByteArray()))
    }
}

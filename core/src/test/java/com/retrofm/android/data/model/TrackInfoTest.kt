package com.retrofm.android.data.model

import com.retrofm.android.data.config.RetroFmConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TrackInfo.fromStreamTitle] is the app's only now-playing source since the 2026-08-08 move to
 * the station's own Icecast, so the parsing edge cases below are load-bearing, not cosmetic.
 */
class TrackInfoTest {

    @Test
    fun `parses title and artist from a StreamTitle`() {
        val track = TrackInfo.fromStreamTitle("It Must Have Been Love - Roxette")!!
        assertEquals("It Must Have Been Love", track.title)
        assertEquals("Roxette", track.artist)
    }

    /** Observed live on the mount: the injector emits ragged spacing around the separator. */
    @Test
    fun `tolerates ragged spacing around the separator`() {
        val track = TrackInfo.fromStreamTitle("What Is Love  - Haddaway")!!
        assertEquals("What Is Love", track.title)
        assertEquals("Haddaway", track.artist)
    }

    @Test
    fun `an empty or blank StreamTitle is not a track`() {
        assertNull(TrackInfo.fromStreamTitle(""))
        assertNull(TrackInfo.fromStreamTitle("   "))
        assertNull(TrackInfo.fromStreamTitle(null))
    }

    @Test
    fun `a title with no separator keeps the whole text and falls back to the station name`() {
        val track = TrackInfo.fromStreamTitle("Nyheterna")!!
        assertEquals("Nyheterna", track.title)
        assertEquals(RetroFmConfig.STATION_NAME, track.artist)
    }

    /** A hyphen inside the title: everything after the first separator is the artist. */
    @Test
    fun `splits on the first separator only`() {
        val track = TrackInfo.fromStreamTitle("Sgt. Pepper - Reprise - The Beatles")!!
        assertEquals("Sgt. Pepper", track.title)
        assertEquals("Reprise - The Beatles", track.artist)
    }

    @Test
    fun `event id is stable per title and always positive`() {
        val a = TrackInfo.fromStreamTitle("Luka - Suzanne Vega")!!
        val b = TrackInfo.fromStreamTitle("Luka - Suzanne Vega")!!
        val other = TrackInfo.fromStreamTitle("Cryin' - Aerosmith")!!
        assertEquals(a.eventId, b.eventId)
        assertNotEquals(a.eventId, other.eventId)
        // `eventId > 0` is how the ICY and ad paths recognise "a real, identified track", and
        // must never collide with the -1 branding / -2 ad sentinels.
        assertTrue(a.eventId > 0)
        assertTrue(other.eventId > 0)
    }

    @Test
    fun `station fallback is branding, outside the real-track id range`() {
        val fallback = TrackInfo.stationFallback()
        assertEquals(RetroFmConfig.STATION_NAME, fallback.title)
        assertEquals(RetroFmConfig.STATION_STRAPLINE, fallback.artist)
        assertTrue(fallback.eventId <= 0)
        assertNotEquals(RetroFmConfig.AD_EVENT_ID, fallback.eventId)
    }
}

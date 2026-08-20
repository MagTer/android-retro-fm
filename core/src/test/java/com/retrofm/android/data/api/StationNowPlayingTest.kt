package com.retrofm.android.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The station's page as an artwork source, pinned against the real markup and the real ways it
 * was measured to disagree with the mount on 2026-08-20.
 *
 * The point of nearly every case below is the same: a disagreement must produce **no** album id,
 * because the alternative is a confidently wrong cover on screen for the length of a song.
 */
class StationNowPlayingTest {

    /** The player block, verbatim from a 2026-08-20 fetch. */
    private fun page(
        title: String = "Do You Really Want To Hurt Me",
        artist: String = "Culture Club",
        album: String? = "c09ed951-f7ac-460f-b54e-c69d3203a861"
    ): String {
        val art = album?.let {
            """<img src="/nowPlayingMedia/albums/$it-sm.jpg" alt="$title" class="cp-player-album-art" />"""
        }.orEmpty()
        return """
            <div class="cp-player-frame"><div class="cp-player-track-info">
            $art
            <span class="cp-player-track-title">$title</span>
            <span class="cp-player-artist-name">$artist</span>
            <span class="cp-player-dj-name">Jocke Forsell</span>
            <span class="cp-player-show-time">Tidernas Största Hits med Jocke Forsell</span>
            </div></div>
        """.trimIndent()
    }

    @Test
    fun `the player block yields title artist and album id`() {
        val snapshot = StationNowPlaying.parse(page())
        assertEquals("Do You Really Want To Hurt Me", snapshot.title)
        assertEquals("Culture Club", snapshot.artist)
        assertEquals("c09ed951-f7ac-460f-b54e-c69d3203a861", snapshot.albumId)
    }

    /**
     * 6 of 73 fetches on 2026-08-20 returned the page with no player block at all, scattered
     * through songs rather than clustered at boundaries. That is "no answer" — never a signal,
     * and never an album id.
     */
    @Test
    fun `a page with no player block yields nothing`() {
        val snapshot = StationNowPlaying.parse("<html><body>no player here</body></html>")
        assertNull(snapshot.title)
        assertNull(snapshot.artist)
        assertNull(snapshot.albumId)
        assertFalse(StationNowPlaying.agrees(snapshot, "Anything", "Anybody"))
    }

    @Test
    fun `agreement on title and artist is what unlocks the album id`() {
        val snapshot = StationNowPlaying.parse(page())
        assertTrue(
            StationNowPlaying.agrees(snapshot, "Do You Really Want To Hurt Me", "Culture Club")
        )
    }

    /**
     * Field case 2026-08-20 16:54:44: the mount announced "Broken Wings - Mr. Mister" while the
     * page was still on the previous song. One fetch later it had caught up — but the first
     * fetch must not be trusted, or the previous track's cover goes on screen.
     */
    @Test
    fun `the previous track still on the page is not agreement`() {
        val snapshot = StationNowPlaying.parse(page("Ma Baker", "Boney M"))
        assertFalse(StationNowPlaying.agrees(snapshot, "Broken Wings", "Mr. Mister"))
    }

    /**
     * Field case 2026-08-20 17:18:31: the mount announced "5.7.0.5. - City Boy" and the page sat
     * on a track the mount never announced, for 34 s and counting. The page is not always
     * describing the same playout; this is the case that makes the guard load-bearing rather
     * than defensive.
     */
    @Test
    fun `a page describing a different playout is not agreement`() {
        val snapshot = StationNowPlaying.parse(page("You Keep Me Hangin' On", "Kim Wilde"))
        assertFalse(StationNowPlaying.agrees(snapshot, "5.7.0.5.", "City Boy"))
    }

    /** Right song, wrong artist is still wrong: covers belong to a recording, not a title. */
    @Test
    fun `a matching title under a different artist is not agreement`() {
        val snapshot = StationNowPlaying.parse(page("Islands In The Stream", "The Shires"))
        assertFalse(
            StationNowPlaying.agrees(snapshot, "Islands In The Stream", "Kenny Rogers + Dolly Parton")
        )
    }

    /**
     * The mount's injector emits ragged spacing ("What Is Love  - Haddaway"), so the comparison
     * collapses whitespace and case. It does nothing more than that — anything looser would
     * start accepting the disagreements above.
     */
    @Test
    fun `ragged spacing and case do not break agreement`() {
        val snapshot = StationNowPlaying.parse(page("What Is Love", "Haddaway"))
        assertTrue(StationNowPlaying.agrees(snapshot, "what is  love", "HADDAWAY"))
    }

    @Test
    fun `an album id without an agreeing title is refused`() {
        val snapshot = StationNowPlaying.parse(page(album = "c09ed951-f7ac-460f-b54e-c69d3203a861"))
        assertFalse(StationNowPlaying.agrees(snapshot, "Some Other Song", "Culture Club"))
    }

    /** No album id means no cover, even when the text agrees perfectly. */
    @Test
    fun `agreement without an album id is not usable`() {
        val snapshot = StationNowPlaying.parse(page(album = null))
        assertEquals("Do You Really Want To Hurt Me", snapshot.title)
        assertNull(snapshot.albumId)
        assertFalse(
            StationNowPlaying.agrees(snapshot, "Do You Really Want To Hurt Me", "Culture Club")
        )
    }

    @Test
    fun `entities in the station's own text are decoded before comparing`() {
        val snapshot = StationNowPlaying.parse(page("Rock &amp; Roll", "Led Zeppelin"))
        assertEquals("Rock & Roll", snapshot.title)
        assertTrue(StationNowPlaying.agrees(snapshot, "Rock & Roll", "Led Zeppelin"))
    }

    /** 600 px, the same rendition the iTunes path requests, so both sources look alike. */
    @Test
    fun `the artwork url is the 600px rendition of the album id`() {
        assertEquals(
            "https://retrofm.se/nowPlayingMedia/albums/c09ed951-f7ac-460f-b54e-c69d3203a861-md.jpg",
            StationNowPlaying.artworkUrlFor("c09ed951-f7ac-460f-b54e-c69d3203a861")
        )
    }

    /**
     * A separator-less StreamTitle parses to the station name as artist ("Nyheterna"). Nothing
     * on the page will ever agree with that, so it must not cost a request either.
     */
    @Test
    fun `the station name as artist is refused without a request`() {
        assertNull(StationNowPlaying.artworkUrl("Nyheterna", "Retro FM"))
        assertNull(StationNowPlaying.artworkUrl("", "Culture Club"))
    }
}

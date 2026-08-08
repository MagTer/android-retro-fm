package com.retrofm.android.data.api

import com.retrofm.android.data.api.ArtworkLookup.SearchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Candidate scoring, pinned against the real iTunes responses that produced wrong covers in the
 * car. Field complaint (2026-08-08): "oftast bra men ibland fel artist featuring rätt artist".
 */
class ArtworkLookupTest {

    private fun r(artist: String, track: String) =
        SearchResult(artist, track, "https://is1-ssl.mzstatic.com/x/100x100bb.jpg")

    @Test
    fun `exact artist and title wins`() {
        val best = ArtworkLookup.pick(
            "Roxette", "It Must Have Been Love",
            listOf(r("Roxette", "It Must Have Been Love"))
        )
        assertEquals("Roxette", best?.artistName)
    }

    /** The reported failure: another primary artist featuring the credited one. */
    @Test
    fun `a different primary artist featuring the credited one is rejected`() {
        val best = ArtworkLookup.pick(
            "Sting", "Rise & Fall",
            listOf(r("Craig David", "Rise & Fall (feat. Sting)"))
        )
        assertNull(best)
    }

    /** Karaoke renditions outrank the original on relevance often enough to matter. */
    @Test
    fun `karaoke and tribute renditions are rejected`() {
        val best = ArtworkLookup.pick(
            "Faith Evans", "I'll Be Missing You",
            listOf(
                r("ZZang KARAOKE", "I'll Be Missing You (Feat. Faith Evans & 112)"),
                r("Puff Daddy & Faith Evans", "I'll Be Missing You (feat. 112)")
            )
        )
        assertEquals("Puff Daddy & Faith Evans", best?.artistName)
    }

    /** Duets, credited either way round, are the same recording and the same cover. */
    @Test
    fun `duet billed in the other order still matches`() {
        val best = ArtworkLookup.pick(
            "Aretha Franklin", "I Knew You Were Waiting",
            listOf(r("George Michael & Aretha Franklin", "I Knew You Were Waiting (For Me)"))
        )
        assertEquals("George Michael & Aretha Franklin", best?.artistName)
    }

    /** The artist's own recording beats a collaborator's, even if the API ranks it lower. */
    @Test
    fun `the credited artist outranks a collaboration listing it later`() {
        val best = ArtworkLookup.pick(
            "David Bowie", "Under Pressure",
            listOf(
                r("Queen", "Under Pressure (feat. David Bowie)"),
                r("David Bowie", "Under Pressure")
            )
        )
        assertEquals("David Bowie", best?.artistName)
    }

    @Test
    fun `the plain single beats a live or remix entry`() {
        val best = ArtworkLookup.pick(
            "Haddaway", "What Is Love",
            listOf(
                r("Haddaway", "What Is Love (7\" Mix)"),
                r("Haddaway", "What Is Love")
            )
        )
        assertEquals("What Is Love", best?.trackName)
    }

    @Test
    fun `diacritics and punctuation do not block a match`() {
        val best = ArtworkLookup.pick(
            "Freddie Mercury & Montserrat Caballe", "Barcelona",
            listOf(r("Freddie Mercury & Montserrat Caballé", "Barcelona (Single Version)"))
        )
        assertEquals("Barcelona (Single Version)", best?.trackName)
    }

    /** Whole-word containment only — a short name must not match inside a longer one. */
    @Test
    fun `artist substring inside a longer word is not a match`() {
        assertNull(ArtworkLookup.pick("Sting", "Sunrise", listOf(r("Stingray", "Sunrise"))))
    }

    @Test
    fun `a wrong title under the right artist is rejected`() {
        assertNull(
            ArtworkLookup.pick(
                "Queen", "Who Wants To Live Forever",
                listOf(r("Queen", "Bohemian Rhapsody"))
            )
        )
    }

    @Test
    fun `a candidate without artwork is skipped`() {
        val best = ArtworkLookup.pick(
            "Seal", "Kiss From a Rose",
            listOf(
                SearchResult("Seal", "Kiss from a Rose", null),
                r("Seal", "Kiss from a Rose")
            )
        )
        assertEquals("https://is1-ssl.mzstatic.com/x/100x100bb.jpg", best?.artworkUrl100)
    }

    @Test
    fun `an empty field yields no artwork rather than a guess`() {
        assertNull(ArtworkLookup.pick("Roxette", "It Must Have Been Love", emptyList()))
    }
}

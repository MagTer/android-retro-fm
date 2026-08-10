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

    private fun r(artist: String, track: String, album: String? = null, albumArtist: String? = null) =
        SearchResult(
            artist, track, "https://is1-ssl.mzstatic.com/x/100x100bb.jpg", album, albumArtist
        )

    /**
     * Field case 2026-08-09: the car showed a generic "Love Songs - 100 Hits" montage for
     * Take That. Every one of the fifteen candidates carried a parenthetical, so the
     * plain-title tiebreak could not separate them and the pick fell through to Apple's own
     * ordering — whose first hit was a various-artists compilation. Order below is verbatim
     * from the live API.
     */
    @Test
    fun `a various-artists compilation loses to the artist's own album`() {
        val best = ArtworkLookup.pick(
            "Take That", "Back For Good",
            listOf(
                r("Take That", "Back for Good (Radio Mix)", "Love Songs - 100 Hits", "Various Artists"),
                r("Take That", "Back for Good (Radio Mix)", "Nobody Else (Expanded Edition)"),
                r("Take That", "Back for Good (Radio Mix)", "Nobody Else (30th Anniversary Edition)"),
                r("Take That", "Back for Good (Odyssey Mix)", "Odyssey"),
                r("Take That", "Back for Good (Radio Mix)", "Girl Group vs Boy Band", "Various Artists")
            )
        )
        assertEquals("Nobody Else (Expanded Edition)", best?.collectionName)
    }

    /**
     * The compilation credit is **localised** — `country=SE` returns "Blandade Artister", not
     * "Various Artists". Matching the English literal missed every Swedish row and put
     * "Pointer Sisters – I'm So Excited" on a 100-track "80s 100 Hits" (field-reported
     * 2026-08-10). The rule must be structural: the field is set, and disagrees with the
     * artist.
     */
    @Test
    fun `a localised compilation credit is still a compilation`() {
        val best = ArtworkLookup.pick(
            "Pointer Sisters", "I'm So Excited",
            listOf(
                r("The Pointer Sisters", "I'm So Excited", "80s 100 Hits", "Blandade Artister"),
                r("The Pointer Sisters", "I'm So Excited", "So Excited!")
            )
        )
        assertEquals("So Excited!", best?.collectionName)
    }

    /** An album credited to the artist themselves is their own release, not a compilation. */
    @Test
    fun `an album credit matching the artist is not a compilation`() {
        val best = ArtworkLookup.pick(
            "Madonna", "Take A Bow",
            listOf(
                r("Madonna", "Take a Bow", "Hits Collection", "Blandade Artister"),
                r("Madonna", "Take a Bow", "Bedtime Stories", "Madonna")
            )
        )
        assertEquals("Bedtime Stories", best?.collectionName)
    }

    /**
     * "The Four Tops – Loco In Acapulco" rejected all fifteen candidates and showed the logo:
     * Apple credits the band "Four Tops", and whole-word containment only looks for the wanted
     * name *inside* the candidate's, so a wanted name one word longer matches nothing
     * (field-reported 2026-08-10).
     */
    @Test
    fun `a leading definite article does not break the artist match`() {
        val best = ArtworkLookup.pick(
            "The Four Tops", "Loco In Acapulco",
            listOf(r("Four Tops", "Loco in Acapulco", "Indestructible"))
        )
        assertEquals("Indestructible", best?.collectionName)
    }

    @Test
    fun `the article is optional in both directions`() {
        val best = ArtworkLookup.pick(
            "Pointer Sisters", "I'm So Excited",
            listOf(r("The Pointer Sisters", "I'm So Excited", "So Excited!"))
        )
        assertEquals("So Excited!", best?.collectionName)
    }

    /** Lead artist, used to widen a search that found nothing. */
    @Test
    fun `a joined credit yields its lead artist`() {
        assertEquals("John Travolta", ArtworkLookup.leadArtist("John Travolta + Olivia Newton-John"))
        assertEquals("Youssou N'Dour", ArtworkLookup.leadArtist("Youssou N'Dour & Neneh Cherry"))
        assertEquals("Sting", ArtworkLookup.leadArtist("Sting feat. Craig David"))
    }

    @Test
    fun `a single artist has no lead to fall back to`() {
        assertNull(ArtworkLookup.leadArtist("Roxette"))
        assertNull(ArtworkLookup.leadArtist("Alanis Morissette"))
    }

    /** The full credit still has to match — a narrower search must not lower the bar. */
    @Test
    fun `a joined credit matches a candidate that spells the join differently`() {
        val best = ArtworkLookup.pick(
            "John Travolta + Olivia Newton-John", "You're the One That I Want",
            listOf(
                r("Olivia Newton-John", "Hopelessly Devoted to You", "Grease"),
                r("John Travolta & Olivia Newton-John", "You're the One That I Want", "Grease")
            )
        )
        assertEquals("John Travolta & Olivia Newton-John", best?.artistName)
    }

    /** A compilation is a last resort, not a disqualification — a cover beats the logo. */
    @Test
    fun `a compilation still wins when it is the only candidate`() {
        val best = ArtworkLookup.pick(
            "Take That", "Back For Good",
            listOf(r("Take That", "Back for Good (Radio Mix)", "Love Songs", "Various Artists"))
        )
        assertEquals("Love Songs", best?.collectionName)
    }

    /** Artist agreement still outranks the compilation signal. */
    @Test
    fun `an own-release by the wrong artist never beats the right artist`() {
        val best = ArtworkLookup.pick(
            "Take That", "Back For Good",
            listOf(
                r("Robbie Williams", "Back for Good (Live Version)", "Angels - EP"),
                r("Take That", "Back for Good (Radio Mix)", "Love Songs", "Various Artists")
            )
        )
        assertEquals("Take That", best?.artistName)
    }

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

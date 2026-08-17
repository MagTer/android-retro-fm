package com.retrofm.android.data.api

import com.retrofm.android.data.api.ArtworkLookup.SearchResult
import com.retrofm.android.data.config.RetroFmConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    /**
     * "Katrina & The Waves – Walking On Sunshine" rejected all fifteen candidates and showed the
     * logo (field-reported 2026-08-12). Apple spells the join as a word, and whole-word
     * containment cannot bridge a differing word: `katrina the waves` is not a sublist of
     * `katrina and the waves`. Order below is verbatim from the live API — rank 0 and 1 were
     * both perfectly good covers.
     */
    @Test
    fun `an ampersand join matches a candidate that spells it as a word`() {
        val best = ArtworkLookup.pick(
            "Katrina & The Waves", "Walking On Sunshine",
            listOf(
                r("Katrina and the Waves", "Walking On Sunshine", "Anthology"),
                r("Katrina and the Waves", "Walking On Sunshine", "Katrina and the Waves"),
                r("Katrina and the Waves", "Walking On Sunshine", "Feel-Good Jams", "Various Artists"),
                r("Katrina and the Waves", "Walking On Sunshine", "Throwback Tunes: 80s", "Blandade Artister"),
                r(
                    "CARSTN, Katrina and the Waves & Agent Zed", "Walking on Sunshine",
                    "Walking on Sunshine - Single"
                )
            )
        )
        assertEquals("Anthology", best?.collectionName)
    }

    /** Dropping the join word must not cost the matches that already worked. */
    @Test
    fun `a band whose name contains a join still matches either spelling`() {
        val best = ArtworkLookup.pick(
            "Mike & The Mechanics", "Over My Shoulder",
            listOf(r("Mike + The Mechanics", "Over My Shoulder", "Beggar On a Beach of Gold"))
        )
        assertEquals("Beggar On a Beach of Gold", best?.collectionName)
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

    /**
     * Field case 2026-08-15: the car showed an all-star tribute concert's cover for
     * "Islands In The Stream". The station credits the duet "Kenny Rogers + Dolly Parton";
     * Apple credits the studio recording to "Dolly Parton & Kenny Rogers" on every row but one,
     * and whole-word containment is ordered, so all six were discarded — leaving the single
     * "Kenny Rogers & Dolly Parton" row, a live all-star concert. Order verbatim from the API.
     */
    @Test
    fun `a duet credited in the other order is the same act`() {
        val best = ArtworkLookup.pick(
            "Kenny Rogers + Dolly Parton", "Islands In The Stream",
            listOf(
                r("Dolly Parton & Kenny Rogers", "Islands In the Stream", "Ultimate Dolly Parton", "Dolly Parton"),
                r("Dolly Parton & Kenny Rogers", "Islands in the Stream", "Dolly", "Dolly Parton"),
                r("Dolly Parton & Kenny Rogers", "Islands In the Stream", "Greatest Hits", "Dolly Parton"),
                r("Dolly Parton & Kenny Rogers", "Islands In the Stream", "The Essential Dolly Parton", "Dolly Parton"),
                r("Dolly Parton & Kenny Rogers", "Islands In the Stream", "Holiday Summer Mix", "Summer Hits"),
                r("Dolly Parton & Kenny Rogers", "Islands In the Stream", "Les 100 plus grands titres Country", "Various Artists"),
                r("Kenny Rogers & Dolly Parton", "Islands In The Stream (Live)", "Kenny Rogers: All In For The Gambler – All-Star Concert Celebration (Live)", "Various Artists"),
                r("The Shires", "Islands in the Stream", "Brave (Deluxe)")
            )
        )
        assertEquals("Ultimate Dolly Parton", best?.collectionName)
    }

    /**
     * Accepting the swapped credit must not hand the pick to a live take. "Rufus and Chaka
     * Khan" now scores as the same act as the station's "Chaka Khan + Rufus", and its row is a
     * live album that ranks above the studio one — the rendition term is what keeps it out.
     * Order verbatim from the API.
     */
    @Test
    fun `the swapped credit does not open the door to a live take`() {
        val best = ArtworkLookup.pick(
            "Chaka Khan + Rufus", "Ain't Nobody",
            listOf(
                r("Chaka Khan", "Ain't Nobody", "Epiphany: The Best of Chaka Khan, Vol. 1"),
                r("Rufus and Chaka Khan", "Ain't Nobody (Live)", "Stompin' at The Savoy (Live)"),
                r("Chaka Khan & Rufus", "Ain't Nobody", "MILESTONES", "Various Artists"),
                r("Chaka Khan & Rufus", "Ain't Nobody", "100 beste festlåter", "Various Artists")
            )
        )
        assertEquals("MILESTONES", best?.collectionName)
    }

    /**
     * Field case 2026-08-15: "Ultimate Berlin Live" on screen for the Top Gun ballad. It is the
     * only row that is both plain-titled and an own release, so it beat everything on `own`.
     *
     * What this test does *not* claim: that the Top Gun soundtrack at rank 0 wins. It cannot —
     * it is credited "Various Artists" like any hits compilation, and the only field that names
     * a soundtrack (`primaryGenreName`) is localised by `country=SE`, so it is unusable for
     * logic. Ranking Apple's own order above `own` would reach it, and replaying the 123-track
     * corpus showed that wrecking 34 picks. See the note in ArtworkLookup.pick.
     */
    @Test
    fun `a live album never beats the record being played`() {
        val best = ArtworkLookup.pick(
            "Berlin", "Take My Breath Away",
            listOf(
                r("Berlin", "Take My Breath Away (Love Theme from \"Top Gun\")", "Top Gun (Original Motion Picture Soundtrack) [Special Expanded Edition]", "Various Artists"),
                r("Berlin", "Take My Breath Away (Love Theme from \"Top Gun\")", "Anos 80 - Nostalgia Internacionais", "Various Artists"),
                r("Berlin", "Take My Breath Away (Love Theme from \"Top Gun\")", "Movie Hits", "Various Artists"),
                r("Berlin", "Take My Breath Away (Love Theme from \"Top Gun\")", "Power Ballads: All Out of Love", "Various Artists"),
                r("Berlin", "Take My Breath Away (Re-Recorded)", "Take My Breath Away (Re-Recorded Versions) - Single"),
                r("Berlin", "Take My Breath Away (as heard in Top Gun) (Re-Recorded / Remastered)", "Take My Breath Away (as heard in Top Gun) (Re-Recorded / Remastered)"),
                r("Berlin", "Take My Breath Away (Love Theme from \"Top Gun\")", "Love Songs", "Various Artists"),
                r("Berlin", "Take My Breath Away", "Ultimate Berlin Live"),
                r("Berlin", "Take My Breath Away (Live)", "Live: Sacred & Profane"),
                r("Berlin", "Take My Breath Away (Love Theme From \"Top Gun\")", "Die Hit Giganten - Film Hits", "Various Artists"),
                r("Berlin", "Take My Breath Away (Main Version)", "Take My Breath Away"),
                r("Berlin", "Take My Breath Away (Orchestral Version)", "Strings Attached")
            )
        )
        assertEquals("Take My Breath Away", best?.collectionName)
    }

    /**
     * Field case 2026-08-14: a "(Re-Recorded / Remastered)" single sleeve for "Maniac". Same
     * shape as the Berlin case — the re-recording is the artist's own release and the real
     * recording sits on soundtracks and compilations. Order verbatim from the API.
     */
    @Test
    fun `a re-recording never beats the record being played`() {
        val best = ArtworkLookup.pick(
            "Michael Sembello", "Maniac",
            listOf(
                r("Michael Sembello", "Maniac", "Flashdance (Original Soundtrack from the Motion Picture)", "Various Artists"),
                r("Michael Sembello", "Maniac (Flashdance Version) (Re-Recorded / Remastered)", "Maniac (Flashdance Version) (Re-Recorded / Remastered)"),
                r("Michael Sembello", "Maniac (New Version)", "The Lost Years"),
                r("Michael Sembello", "Maniac", "Classic 80s Movie Songs", "Various Artists"),
                r("Michael Sembello", "Maniac (Album Version) (Re-Recorded / Remastered)", "Maniac (Flashdance Version) (Re-Recorded / Remastered)"),
                r("Michael Sembello", "Maniac", "80's Hits Night", "Various Artists"),
                r("Michael Sembello", "Maniac (Re-Recorded)", "Maniac (Re-Recorded) - Single")
            )
        )
        assertEquals("The Lost Years", best?.collectionName)
    }

    /**
     * The rendition test reads bracketed qualifiers and the release title, never the bare track
     * name — "Live Is Life" and "Living In A Box" are songs, and demoting them would cost a
     * correct cover to fix nothing. Both are real 1.0.54 tracks.
     */
    @Test
    fun `a song whose title contains live is not a rendition`() {
        assertEquals(
            "Millennium Edition",
            ArtworkLookup.pick(
                "Opus", "Live Is Life",
                listOf(
                    r("Opus", "Live Is Life", "Millennium Edition"),
                    r("Opus", "Live Is Life (Live)", "Live At The Stadthalle")
                )
            )?.collectionName
        )
        assertEquals(
            "Living In A Box",
            ArtworkLookup.pick(
                "Living in a box", "Living in a box",
                listOf(r("Living In a Box", "Living In A Box", "Living In A Box"))
            )?.collectionName
        )
    }

    /** A remaster is the original recording, so it is not demoted. */
    @Test
    fun `a remaster is not a rendition`() {
        val best = ArtworkLookup.pick(
            "Maggie Reilly", "Everytime we touch",
            listOf(
                r("Maggie Reilly", "Everytime We Touch (Radio Mix Remastered)",
                    "Everytime We Touch (Remastered) - Single"),
                r("Maggie Reilly", "Everytime We Touch (Live)", "Live In Concert")
            )
        )
        assertEquals("Everytime We Touch (Remastered) - Single", best?.collectionName)
    }

    /**
     * Field case 2026-08-15: the logo for "Burning Down The House". The station credits the
     * duet in full, Apple credits only the lead and moves the guest into the track name, so the
     * wanted credit is *longer* than every candidate's and whole-word containment finds nothing
     * — all fifteen rows rejected. Order verbatim from the API.
     */
    @Test
    fun `a candidate crediting only the lead artist still matches`() {
        val best = ArtworkLookup.pick(
            "Tom Jones & The Cardigans", "Burning Down The House",
            listOf(
                r("Tom Jones", "Burning Down the House (feat. The Cardigans)", "Best of the Cardigans", "The Cardigans"),
                r("Tom Jones", "Burning Down the House (feat. The Cardigans)", "Reload"),
                r("Tom Jones", "Burning Down The House (feat. The Cardigans)", "1999 Best of by uDiscover", "Various Artists"),
                r("Taylor Swift", "Wildest Dreams (Taylor's Version)", "Wildest Dreams (Taylor's Version) - Single")
            )
        )
        assertEquals("Reload", best?.collectionName)
    }

    /** The weakest artist tier must never outrank a candidate that agrees more fully. */
    @Test
    fun `the lead-only match loses to the full credit`() {
        val best = ArtworkLookup.pick(
            "Tom Jones & The Cardigans", "Burning Down The House",
            listOf(
                r("Tom Jones", "Burning Down the House (feat. The Cardigans)", "Reload"),
                r("Tom Jones & The Cardigans", "Burning Down the House", "Reload (Deluxe)")
            )
        )
        assertEquals("Reload (Deluxe)", best?.collectionName)
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

    /**
     * The stream announced "Somebody´s watching me (edit)" and the search returned zero
     * candidates — the only 0-result query in the whole 1.0.54 corpus. Measured 2026-08-17: the
     * acute and grave accents both return 0, while `’`, `'` and no apostrophe at all return 15.
     * Only the two spacing accents are repaired, and only in the outgoing term.
     */
    @Test
    fun `a spacing accent standing in for an apostrophe is repaired in the search term`() {
        assertEquals(
            "Rockwell Somebody's watching me (edit)",
            ArtworkLookup.searchable("Rockwell Somebody´s watching me (edit)")
        )
        assertEquals("Somebody's", ArtworkLookup.searchable("Somebody`s"))
    }

    /** Spellings Apple already accepts are left exactly as the station wrote them. */
    @Test
    fun `apostrophes Apple accepts are left alone`() {
        for (term in listOf("Somebody's watching me", "Somebody’s watching me", "Somebodys")) {
            assertEquals(term, ArtworkLookup.searchable(term))
        }
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

    /**
     * The car showed a 1996 live recording's EP cover for "Born In The USA" (field-reported
     * 2026-08-12): punctuation becomes whitespace, so Apple's "U.S.A." split into three
     * single-letter words and every studio entry was discarded — leaving the one candidate that
     * happened to spell it without dots. Order below is verbatim from the live API; the album at
     * rank 0 is the cover that belongs on screen.
     */
    @Test
    fun `a dotted acronym matches its undotted spelling`() {
        val best = ArtworkLookup.pick(
            "Bruce Springsteen", "Born In The USA",
            listOf(
                r("Bruce Springsteen", "Born In the U.S.A.", "Born In the U.S.A."),
                r("Bruce Springsteen", "Dancing In the Dark", "Born In the U.S.A."),
                r("Bruce Springsteen", "Born in the U.S.A.", "Greatest Hits"),
                r(
                    "Bruce Springsteen",
                    "Born in the U.S.A. (Live at Giants Stadium, E. Rutherford, NJ - 8/22/1985)",
                    "The Born in the U.S.A. Tour '84 - '85"
                ),
                r(
                    "Bruce Springsteen",
                    "Born In the U.S.A. (Live at LA Coliseum, Los Angeles, CA - September 1985)",
                    "Live / 1975-85", "Bruce Springsteen & The E Street Band"
                ),
                r(
                    "Bruce Springsteen",
                    "Born In the USA (Live at ICC SAAL 1, Berlin, Germany - April 1996)",
                    "Missing EP"
                )
            )
        )
        assertEquals("Born In the U.S.A.", best?.collectionName)
    }

    /** Narrow on purpose: a lone abbreviating dot is not an acronym and must survive as a break. */
    @Test
    fun `a single trailing dot is not treated as an acronym`() {
        val best = ArtworkLookup.pick(
            "Boney M", "Rivers Of Babylon",
            listOf(r("Boney M.", "Rivers of Babylon", "Nightflight to Venus"))
        )
        assertEquals("Nightflight to Venus", best?.collectionName)
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

    /**
     * The retry only earns its request if it lands *after* the window that killed the first
     * attempt. Three field cases (Four Tops 2026-08-10, The Corrs and Lynyrd Skynyrd
     * 2026-08-11) had both attempts time out back to back, 8 s then 16 s from the same start,
     * each within ~10 s of the link being reported up. A retry inside the connect timeout is
     * one wasted request, not a second chance.
     */
    @Test
    fun `the retry waits out the window that killed the first attempt`() {
        assertTrue(
            RetroFmConfig.ARTWORK_RETRY_DELAY_MS > RetroFmConfig.ARTWORK_CONNECT_TIMEOUT_MS
        )
    }
}

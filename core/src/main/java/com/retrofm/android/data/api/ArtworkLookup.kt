package com.retrofm.android.data.api

import com.retrofm.android.data.config.RetroFmConfig
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.net.URLEncoder
import java.text.Normalizer
import java.util.concurrent.TimeUnit

/**
 * Per-track album art, looked up by "artist title" against the public iTunes Search API.
 *
 * The station's Icecast announces only `StreamTitle='Title - Artist'` — no artwork, no ids — so
 * this is where covers come from. Apple documents no key requirement but does rate-limit, so
 * the budget is one request per announced boundary and nothing loops: results are cached, one
 * call is in flight at a time, and the next boundary cancels the previous. The only repeat is
 * when the mount re-announces a title whose lookup failed in transport — deliberate, since that
 * outcome is not cached, and still only a couple of requests per song.
 *
 * **Taking the API's first result is not good enough.** Relevance ranking happily returns a
 * karaoke rendition ("ZZang KARAOKE – I'll Be Missing You (Feat. Faith Evans)") or a different
 * primary artist featuring the one we asked for ("Craig David – Rise & Fall (feat. Sting)" for
 * a track the stream credits to Sting) — both wrong covers, and exactly what field testing
 * turned up. Candidates are therefore scored (see [pick]) and a weak field yields *no* artwork:
 * the station logo is better than confidently showing the wrong album.
 */
object ArtworkLookup {

    @Serializable
    private data class SearchResponse(
        @SerialName("results") val results: List<SearchResult> = emptyList()
    )

    @Serializable
    internal data class SearchResult(
        @SerialName("artistName") val artistName: String = "",
        @SerialName("trackName") val trackName: String = "",
        @SerialName("artworkUrl100") val artworkUrl100: String? = null,
        /** Album the artwork actually belongs to. Logged, so a wrong cover is diagnosable. */
        @SerialName("collectionName") val collectionName: String? = null,
        /** Album-level credit. Absent on an artist's own release; set on a compilation. */
        @SerialName("collectionArtistName") val collectionArtistName: String? = null
    ) {
        /**
         * True when the artwork is the artist's own release rather than a compilation. A
         * compilation's cover is generic by construction — the station's logo would be no
         * worse — and Apple's relevance ranking is happy to put one first.
         *
         * Detected structurally: the field is **absent** on an artist's own album and **set**
         * to an album-level credit on a compilation. Do not match its text. The first attempt
         * compared it to the literal "Various Artists" and missed every Swedish row, because
         * `country=SE` makes Apple localise it — "Pointer Sisters – I'm So Excited" resolved
         * to a 100-track "80s 100 Hits" whose credit read **"Blandade Artister"**
         * (field-reported 2026-08-10). Presence-and-disagreement carries no language at all.
         */
        val ownRelease: Boolean
            get() = collectionArtistName.isNullOrBlank() ||
                normalize(collectionArtistName) == normalize(artistName)

        /**
         * True when this row is a live take, a remix or a re-recording rather than the record
         * the station is playing.
         *
         * Ranked above [ownRelease] in [pick], because "the artist's own release" was happily
         * picking a re-recording over the original: the car showed "Ultimate Berlin Live" for
         * *Take My Breath Away* and a "(Re-Recorded / Remastered)" single sleeve for
         * *Maniac* (both field-visible on 1.0.54, 2026-08-13→17). A compilation of the real
         * recording is a better cover than a pristine sleeve for a different performance.
         *
         * Read from the track's **bracketed qualifiers only** plus the release title — never
         * the bare track name. "Live Is Life", "Living In A Box" and "Live and Let Die" are
         * songs, and matching `live` across the whole title would demote all three. Remasters
         * are deliberately absent: a remaster is the original recording.
         */
        val isRendition: Boolean
            get() = RENDITION.containsMatchIn(
                BRACKETED.findAll(trackName).joinToString(" ") { it.value }
            ) || RENDITION.containsMatchIn(collectionName.orEmpty())
    }

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Shared so consecutive track boundaries reuse one TLS connection — see
     * [RetroFmConfig.ARTWORK_KEEP_ALIVE_MINUTES] for why the pool outlives OkHttp's default.
     */
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .callTimeout(RetroFmConfig.ARTWORK_LOOKUP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .connectTimeout(RetroFmConfig.ARTWORK_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(RetroFmConfig.ARTWORK_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .connectionPool(
                ConnectionPool(2, RetroFmConfig.ARTWORK_KEEP_ALIVE_MINUTES, TimeUnit.MINUTES)
            )
            .build()
    }

    /**
     * Bounded LRU of query -> artwork URL. A definitive "no good match" is cached too, so a
     * track the catalogue cannot satisfy is not re-queried on every boundary. Transport
     * failures are deliberately NOT cached — see [artworkUrl].
     */
    private val cache = object : LinkedHashMap<String, String?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String?>?) =
            size > RetroFmConfig.ARTWORK_CACHE_ENTRIES
    }

    /**
     * Artwork URL for a track, or null when nothing trustworthy was found. Blocking — call on
     * IO. Never throws: artwork is a nicety and must not disturb playback or the title already
     * on screen.
     */
    fun artworkUrl(artist: String, title: String): String? {
        // The parser hands back the station name as artist when a StreamTitle carries no
        // separator (jingles, "Nyheterna"). Searching on that returns confident nonsense.
        if (artist.isBlank() || title.isBlank() || artist == RetroFmConfig.STATION_NAME) return null

        val query = "$artist $title"
        synchronized(cache) { if (cache.containsKey(query)) return cache[query] }

        val startedAt = System.currentTimeMillis()
        val primary = search(query, startedAt) ?: return null
        var best = pick(artist, title, primary)
        var candidates = primary.size

        // A joined credit can be too specific to match anything Apple indexes: the stream's
        // "John Travolta + Olivia Newton-John" returned a single unrelated result, while the
        // lead artist alone finds the Grease soundtrack whose credit then matches the full
        // string exactly (field-reported 2026-08-10). Only on a miss, and the candidates are
        // still scored against the FULL credit — a narrower search must not lower the bar.
        if (best == null) {
            val lead = leadArtist(artist)
            if (lead != null) {
                val retry = search("$lead $title", startedAt) ?: return null
                best = pick(artist, title, retry)
                candidates = retry.size
            }
        }

        // Past this point the API answered, so whatever we conclude is a real answer worth
        // remembering — including "nothing good enough".
        val result = best?.artworkUrl100?.replace(SMALL_RENDITION, RetroFmConfig.ARTWORK_RENDITION)
        synchronized(cache) { cache[query] = result }
        // The album is logged, not just the track: the cover comes from the *release*, so
        // "Take That / Back for Good (Radio Mix)" looked like a perfect hit in the log while
        // the car was showing a compilation's montage.
        Timber.tag("Artwork").d(
            "lookup '%s' -> %s in %d ms", query,
            best?.let { "${it.artistName} / ${it.trackName} [${it.collectionName ?: "?"}]" }
                ?: "no match ($candidates candidates)",
            System.currentTimeMillis() - startedAt
        )
        return result
    }

    /**
     * One search against the API. Returns the candidate list, or null when the request never
     * got an answer — a transport failure must stay uncached, so the caller has to be able to
     * tell it apart from an empty result.
     *
     * [startedAt] is the whole lookup's start, so the logged elapsed time is what the car
     * actually waited: the link is the variable that decides whether artwork appears, and
     * without a number in the log the only way to reason about it is inference from
     * timestamps. See the 2026-08-09 entry on ARTWORK_LOOKUP_TIMEOUT_MS.
     */
    private fun search(term: String, startedAt: Long): List<SearchResult>? {
        val url = RetroFmConfig.ARTWORK_SEARCH_URL +
            "?term=" + URLEncoder.encode(term, "UTF-8") +
            "&entity=song&limit=" + RetroFmConfig.ARTWORK_SEARCH_LIMIT + "&country=SE"

        for (attempt in 1..RetroFmConfig.ARTWORK_LOOKUP_ATTEMPTS) {
            try {
                client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (!response.isSuccessful) {
                        Timber.tag("Artwork").w(
                            "lookup HTTP %d for '%s' after %d ms",
                            response.code, term, System.currentTimeMillis() - startedAt
                        )
                        // The API answered, it just refused. Retrying immediately would only
                        // press a server that is already saying no.
                        return null
                    }
                    return json.decodeFromString<SearchResponse>(
                        response.body?.string().orEmpty()
                    ).results
                }
            } catch (e: Exception) {
                // No connectivity yet at boot, a timeout, a malformed body. Caching this would
                // poison the track for the whole process — a car that starts before the modem
                // is up would then show the logo for every song it plays. Leave the cache
                // untouched, so a repeat announcement of the same title gets a second chance.
                val elapsed = System.currentTimeMillis() - startedAt
                if (attempt < RetroFmConfig.ARTWORK_LOOKUP_ATTEMPTS) {
                    Timber.tag("Artwork").w(
                        "lookup attempt %d failed for '%s' after %d ms: %s — retrying in %d ms",
                        attempt, term, elapsed, e.toString(), RetroFmConfig.ARTWORK_RETRY_DELAY_MS
                    )
                    // The car's link is not usable again the instant this attempt gives up —
                    // see RetroFmConfig.ARTWORK_RETRY_DELAY_MS for the three field cases where
                    // a back-to-back retry burned itself in the same dead window.
                    if (!pauseBeforeRetry()) return null
                    continue
                }
                Timber.tag("Artwork").w(
                    "lookup failed for '%s' after %d ms: %s", term, elapsed, e.toString()
                )
                return null
            }
        }
        return null
    }

    /**
     * Wait out [RetroFmConfig.ARTWORK_RETRY_DELAY_MS] before a retry. False when the wait was
     * interrupted, which means whoever owns this thread wants it back — artwork is a nicety and
     * gives up quietly.
     */
    private fun pauseBeforeRetry(): Boolean = try {
        Thread.sleep(RetroFmConfig.ARTWORK_RETRY_DELAY_MS)
        true
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }

    /**
     * The first credited artist when [artist] is a joined credit, else null.
     *
     * Used only to widen a search that found nothing, so a band name that merely contains a
     * separator ("Mike & The Mechanics") costs at most one wasted request — the result is
     * scored against the full credit either way.
     */
    internal fun leadArtist(artist: String): String? {
        val cut = CREDIT_SEPARATOR.find(artist) ?: return null
        return artist.substring(0, cut.range.first).trim().ifBlank { null }
    }

    /**
     * Best candidate for the announced artist/title, or null if none is convincing.
     *
     * Artist agreement is ranked above title agreement, because the wrong artist means the
     * wrong album cover even when the song title matches perfectly — the failure mode this
     * exists to prevent. A candidate that fails either dimension is discarded outright rather
     * than accepted as a "close enough" fallback.
     */
    internal fun pick(artist: String, title: String, results: List<SearchResult>): SearchResult? {
        val wantArtist = artistKey(artist)
        val wantTitle = baseTitle(title)
        if (wantArtist.isEmpty() || wantTitle.isEmpty()) return null

        var best: SearchResult? = null
        var bestScore: List<Int>? = null

        results.forEachIndexed { index, candidate ->
            if (JUNK.containsMatchIn(candidate.artistName) ||
                JUNK.containsMatchIn(candidate.trackName)
            ) return@forEachIndexed
            if (candidate.artworkUrl100.isNullOrBlank()) return@forEachIndexed

            val gotArtist = artistKey(candidate.artistName)
            val artistScore = when {
                // Same tier for a credit spelled in the other order: the station writes
                // "Kenny Rogers + Dolly Parton" and Apple credits the same duet as "Dolly
                // Parton & Kenny Rogers". See sameCredit.
                gotArtist == wantArtist || sameCredit(gotArtist, wantArtist) -> 3
                // "Kylie Minogue & Jason Donovan" when the stream credited only the first name.
                gotArtist.startsWith("$wantArtist ") -> 2
                // Duets the other way round: "George Michael & Aretha Franklin" for "Aretha
                // Franklin". Whole-word containment, so "Sting" never matches "Stingray".
                containsWords(gotArtist, wantArtist) -> 1
                else -> return@forEachIndexed
            }

            val gotTitle = baseTitle(candidate.trackName)
            val titleScore = when {
                gotTitle == wantTitle -> 2
                gotTitle.startsWith("$wantTitle ") || wantTitle.startsWith("$gotTitle ") -> 1
                else -> return@forEachIndexed
            }

            // Prefer the plain single over live/remix/version entries when both qualify.
            val plain = if (candidate.trackName.contains('(') || candidate.trackName.contains('[')) 0 else 1
            // Above `own`, because "the artist's own release" was picking re-recordings and
            // live albums over the real record — see SearchResult.isRendition for the two
            // field cases. Replaying the whole 1.0.54 corpus (123 tracks) moved three picks
            // and regressed none.
            val original = if (candidate.isRendition) 0 else 1
            // Above `plain`, because a compilation cover is wrong in a way a "(Radio Mix)"
            // suffix is not: it shows an unrelated montage instead of the album. Field case
            // 2026-08-09 — "Take That / Back For Good" returned fifteen candidates that ALL
            // carried a parenthetical, so `plain` could not separate them and the tiebreak
            // fell through to Apple's order, whose first hit was a 100-track compilation.
            val own = if (candidate.ownRelease) 1 else 0
            // Deliberately NOT a term here: track count. "Prefer the shorter release" sounds
            // like it should favour an original album over a best-of, and replaying every
            // logged track against the live API showed it wrecking six of eighteen picks —
            // Status Quo to a live album, Clapton to a soundtrack, Louis Armstrong to a
            // Christmas record, Madonna and Tina Turner to singles. Measure before adding a
            // dimension that merely sounds reasonable.
            //
            // Also deliberately NOT here: Apple's own ranking above `own`. It looks attractive
            // because rank 0 is right in exactly the cases `own` gets wrong (the Top Gun and
            // Flashdance soundtracks). Replaying the 123-track corpus says no — it moves 34
            // picks and most are regressions: "Pop Heroes", "Rockklassiker Vol. 2", "80s 100
            // Hits", "Millennium Party", "Stranger Things Remix", a dozen remasters and live
            // takes. `own` and `plain` are load-bearing and stay above the index.
            val score = listOf(artistScore, titleScore, original, own, plain, -index)
            if (bestScore == null || compare(score, bestScore!!) > 0) {
                best = candidate
                bestScore = score
            }
        }
        return best
    }

    private fun compare(a: List<Int>, b: List<Int>): Int {
        for (i in a.indices) {
            val c = a[i].compareTo(b[i])
            if (c != 0) return c
        }
        return 0
    }

    /**
     * True when two artist keys are the same credit written in a different order.
     *
     * A duet is one act however it is billed, but the comparison below it is ordered — whole-
     * word containment needs the wanted name to appear as a run inside the candidate's — so a
     * swapped credit matches nothing at all. The station announced "Kenny Rogers + Dolly
     * Parton"; Apple credits six rows of the studio recording to "Dolly Parton & Kenny Rogers"
     * and exactly one row to "Kenny Rogers & Dolly Parton" — an all-star tribute concert, which
     * was therefore the only candidate that scored and the cover the car showed (2026-08-15).
     *
     * Word *multiset* equality, which after [artistKey] is strict: same words, same count, join
     * spellings already flattened. It ranks in the same tier as an exact match, since the two
     * spellings name the same recording.
     */
    private fun sameCredit(a: String, b: String): Boolean =
        a.split(' ').sorted() == b.split(' ').sorted()

    /** Whole-word containment, so a short artist name can't match inside a longer word. */
    private fun containsWords(haystack: String, needle: String): Boolean {
        val h = haystack.split(' ')
        val n = needle.split(' ')
        if (n.isEmpty() || n.size > h.size) return false
        return (0..h.size - n.size).any { h.subList(it, it + n.size) == n }
    }

    /**
     * [normalize] plus a dropped leading definite article and dropped join words, for comparing
     * artist names.
     *
     * Both removals exist because the mismatch is *fatal* rather than cosmetic: whole-word
     * containment only searches for the wanted name inside the candidate's, so a wanted name
     * that differs by one word matches nothing at all.
     *
     * - The article: "The Four Tops – Loco In Acapulco" rejected all fifteen candidates, every
     *   one of them credited "Four Tops" (field-reported 2026-08-10).
     * - The join: the station writes "Katrina & The Waves" and Apple writes "Katrina and the
     *   Waves". `&` and `+` already collapse to whitespace in [normalize], so the *word* "and"
     *   is the only surviving asymmetry — and it cost all fifteen candidates for "Walking On
     *   Sunshine", including the self-titled album that was sitting at rank 1 (field-reported
     *   2026-08-12). Dropping it on both sides makes every spelling of a joined credit compare
     *   equal, which is also what keeps "Mike & The Mechanics" matching Apple's "Mike + The
     *   Mechanics".
     */
    private fun artistKey(value: String): String =
        normalize(value)
            .removePrefix("the ")
            .replace(CREDIT_JOIN_WORD, " ")
            .trim()
            .replace(WHITESPACE, " ")

    /** Lowercase, strip diacritics and punctuation, collapse whitespace. */
    private fun normalize(value: String): String =
        Normalizer.normalize(undot(value), Normalizer.Form.NFKD)
            .replace(COMBINING, "")
            .lowercase()
            .replace(NON_ALNUM, " ")
            .trim()
            .replace(WHITESPACE, " ")

    /**
     * Collapse a dotted acronym to its letters, so "U.S.A." and "USA" normalize alike.
     *
     * Punctuation otherwise becomes whitespace, which splits an acronym into single-letter
     * words — and that is a mismatch, not a near miss. "Born In The USA" matched *only* the one
     * candidate that spelled it without dots, a 1996 live recording on a bootleg-ish EP, while
     * the actual album at rank 0 ("Born In the U.S.A.") was discarded; the car showed the wrong
     * cover for the whole song (field-reported 2026-08-12).
     *
     * Deliberately narrow: it takes two or more letter-dot pairs in a row, so "Boney M." and
     * "Mr. Big" are untouched, and it never removes an apostrophe — asymmetric apostrophes are
     * a separate, unmeasured problem and should not be swept in here.
     */
    private fun undot(value: String): String =
        value.replace(DOTTED_ACRONYM) { it.value.replace(".", "") }

    /** [normalize] plus the qualifiers Apple appends: "(feat. X)", "[Live]", "- Remastered". */
    private fun baseTitle(value: String): String =
        normalize(value.replace(BRACKETED, " ").split(DASH_QUALIFIER, limit = 2)[0])

    private val COMBINING = Regex("\\p{Mn}+")
    private val NON_ALNUM = Regex("[^a-z0-9]+")
    private val WHITESPACE = Regex("\\s+")
    private val BRACKETED = Regex("\\(.*?\\)|\\[.*?]")
    private val DASH_QUALIFIER = Regex("\\s+-\\s+")
    private val DOTTED_ACRONYM = Regex("(?:\\p{L}\\.){2,}")

    /** The one join spelling [normalize] cannot flatten on its own. See [artistKey]. */
    private val CREDIT_JOIN_WORD = Regex("\\band\\b")

    /** Joins between co-credited artists in a StreamTitle. See [leadArtist]. */
    private val CREDIT_SEPARATOR = Regex(
        "\\s*(?:[+&,/]|\\b(?:feat\\.?|featuring|with|vs\\.?|duet with)\\b)\\s*",
        RegexOption.IGNORE_CASE
    )
    /**
     * A different performance of the same song. Matched against the track's bracketed
     * qualifiers and the release title only — see [SearchResult.isRendition] for why never the
     * bare track name, and why "remaster" is absent.
     */
    private val RENDITION = Regex(
        "re-?record|\\bremix\\b|\\blive\\b|\\bunplugged\\b|\\bacoustic\\b",
        RegexOption.IGNORE_CASE
    )
    private val JUNK = Regex(
        "karaoke|tribute|made famous|instrumental|cover band|backing track|8-bit|lullaby",
        RegexOption.IGNORE_CASE
    )

    private const val SMALL_RENDITION = "100x100bb"
}

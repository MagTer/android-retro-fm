package com.retrofm.android.data.api

import com.retrofm.android.data.config.RetroFmConfig
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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
 * this is where covers come from. Apple documents no key requirement but does rate-limit:
 * one request per track boundary at most (~one per 3–4 min), nothing loops or retries.
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
        @SerialName("artworkUrl100") val artworkUrl100: String? = null
    )

    private val json = Json { ignoreUnknownKeys = true }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .callTimeout(RetroFmConfig.ARTWORK_LOOKUP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
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

        val url = RetroFmConfig.ARTWORK_SEARCH_URL +
            "?term=" + URLEncoder.encode(query, "UTF-8") +
            "&entity=song&limit=" + RetroFmConfig.ARTWORK_SEARCH_LIMIT + "&country=SE"

        val results = try {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.tag("Artwork").w("lookup HTTP %d for '%s'", response.code, query)
                    return null // transport-ish failure: do not cache, retry on a later boundary
                }
                json.decodeFromString<SearchResponse>(response.body?.string().orEmpty()).results
            }
        } catch (e: Exception) {
            // No connectivity yet at boot, a timeout, a malformed body. Caching this would
            // poison the track for the whole process — a car that starts before the modem is
            // up would then show the logo for every song it plays. Leave the cache untouched.
            Timber.tag("Artwork").w("lookup failed for '%s': %s", query, e.toString())
            return null
        }

        // Past this point the API answered, so whatever we conclude is a real answer worth
        // remembering — including "nothing good enough".
        val best = pick(artist, title, results)
        val result = best?.artworkUrl100?.replace(SMALL_RENDITION, RetroFmConfig.ARTWORK_RENDITION)
        synchronized(cache) { cache[query] = result }
        Timber.tag("Artwork").d(
            "lookup '%s' -> %s", query,
            best?.let { "${it.artistName} / ${it.trackName}" } ?: "no match (${results.size} candidates)"
        )
        return result
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
        val wantArtist = normalize(artist)
        val wantTitle = baseTitle(title)
        if (wantArtist.isEmpty() || wantTitle.isEmpty()) return null

        var best: SearchResult? = null
        var bestScore: List<Int>? = null

        results.forEachIndexed { index, candidate ->
            if (JUNK.containsMatchIn(candidate.artistName) ||
                JUNK.containsMatchIn(candidate.trackName)
            ) return@forEachIndexed
            if (candidate.artworkUrl100.isNullOrBlank()) return@forEachIndexed

            val gotArtist = normalize(candidate.artistName)
            val artistScore = when {
                gotArtist == wantArtist -> 3
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
            val score = listOf(artistScore, titleScore, plain, -index)
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

    /** Whole-word containment, so a short artist name can't match inside a longer word. */
    private fun containsWords(haystack: String, needle: String): Boolean {
        val h = haystack.split(' ')
        val n = needle.split(' ')
        if (n.isEmpty() || n.size > h.size) return false
        return (0..h.size - n.size).any { h.subList(it, it + n.size) == n }
    }

    /** Lowercase, strip diacritics and punctuation, collapse whitespace. */
    private fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKD)
            .replace(COMBINING, "")
            .lowercase()
            .replace(NON_ALNUM, " ")
            .trim()
            .replace(WHITESPACE, " ")

    /** [normalize] plus the qualifiers Apple appends: "(feat. X)", "[Live]", "- Remastered". */
    private fun baseTitle(value: String): String =
        normalize(value.replace(BRACKETED, " ").split(DASH_QUALIFIER, limit = 2)[0])

    private val COMBINING = Regex("\\p{Mn}+")
    private val NON_ALNUM = Regex("[^a-z0-9]+")
    private val WHITESPACE = Regex("\\s+")
    private val BRACKETED = Regex("\\(.*?\\)|\\[.*?]")
    private val DASH_QUALIFIER = Regex("\\s+-\\s+")
    private val JUNK = Regex(
        "karaoke|tribute|made famous|instrumental|cover band|backing track|8-bit|lullaby",
        RegexOption.IGNORE_CASE
    )

    private const val SMALL_RENDITION = "100x100bb"
}

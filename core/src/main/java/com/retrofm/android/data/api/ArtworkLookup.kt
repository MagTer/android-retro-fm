package com.retrofm.android.data.api

import com.retrofm.android.data.config.RetroFmConfig
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Per-track album art, looked up by "artist title" against the public iTunes Search API.
 *
 * The station's Icecast announces only `StreamTitle='Title - Artist'` — no artwork, no ids — so
 * when the Bauer eventdata lookups were removed on 2026-08-08 every track fell back to the
 * station logo. This restores real covers from the only free, keyless source that resolved every
 * track we tested against the live stream, including obscure ones (Masquerade – Guardian Angel).
 *
 * Deliberately gentle: one request per track boundary at most (~one per 3–4 min), results and
 * misses both cached for the life of the process, so a repeated song never queries twice. Apple
 * documents no key requirement but does rate-limit; nothing here loops or retries.
 */
object ArtworkLookup {

    @Serializable
    private data class SearchResponse(
        @SerialName("resultCount") val resultCount: Int = 0,
        @SerialName("results") val results: List<SearchResult> = emptyList()
    )

    @Serializable
    private data class SearchResult(
        @SerialName("artworkUrl100") val artworkUrl100: String? = null
    )

    private val json = Json { ignoreUnknownKeys = true }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .callTimeout(RetroFmConfig.ARTWORK_LOOKUP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
    }

    /**
     * Bounded LRU of query -> artwork URL. Null values are cached too: a track the API cannot
     * resolve must not re-query on every boundary for the rest of the drive.
     */
    private val cache = object : LinkedHashMap<String, String?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String?>?) =
            size > RetroFmConfig.ARTWORK_CACHE_ENTRIES
    }

    /**
     * Artwork URL for a track, or null when there is nothing usable. Blocking — call on IO.
     * Never throws: artwork is a nicety, and a lookup failure must not disturb playback or the
     * title that is already on screen.
     */
    fun artworkUrl(artist: String, title: String): String? {
        // The parser hands back the station name as artist when a StreamTitle carries no
        // separator (jingles, "Nyheterna"). Searching on that returns confident nonsense.
        if (artist.isBlank() || title.isBlank() || artist == RetroFmConfig.STATION_NAME) return null

        val query = "$artist $title"
        synchronized(cache) { if (cache.containsKey(query)) return cache[query] }

        val url = RetroFmConfig.ARTWORK_SEARCH_URL +
            "?term=" + URLEncoder.encode(query, "UTF-8") +
            "&entity=song&limit=1&country=SE"

        val result = try {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.tag("Artwork").w("lookup HTTP %d for '%s'", response.code, query)
                    return@use null
                }
                val body = response.body?.string().orEmpty()
                json.decodeFromString<SearchResponse>(body).results.firstOrNull()
                    ?.artworkUrl100
                    // The path segment is the requested rendition; Apple serves any size from
                    // the same URL, and 100x100 is unusably small on a car display.
                    ?.replace(SMALL_RENDITION, RetroFmConfig.ARTWORK_RENDITION)
            }
        } catch (e: Exception) {
            Timber.tag("Artwork").w("lookup failed for '%s': %s", query, e.toString())
            null
        }

        synchronized(cache) { cache[query] = result }
        Timber.tag("Artwork").d("lookup '%s' -> %s", query, if (result != null) "hit" else "miss")
        return result
    }

    private const val SMALL_RENDITION = "100x100bb"
}

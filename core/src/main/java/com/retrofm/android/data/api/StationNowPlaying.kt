package com.retrofm.android.data.api

import com.retrofm.android.data.config.RetroFmConfig
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Album art published by the station itself, read from its own now-playing page.
 *
 * The mount carries no artwork, so covers otherwise come from iTunes Search ([ArtworkLookup]),
 * which can only match on the announced `Title - Artist` text. That text is sometimes not enough
 * to identify the *record*: for "Wouldn't It Be Good - Nik Kershaw" iTunes returns the 1984
 * original while the station is playing a later remix and shows the remix sleeve. No amount of
 * candidate scoring reaches that, because the distinguishing information is not in the string
 * being matched. The station knows which record it is playing, and says so.
 *
 * `GET https://retrofm.se/` server-renders the current track — no JavaScript, no Blazor circuit —
 * including an album id, and `/nowPlayingMedia/albums/{id}-{size}.jpg` serves the cover. Unlike
 * the rest of that site, `/nowPlayingMedia/` returns a real 404 for a wrong name rather than the
 * 56 KB SPA fallback, so a 200 from it can be trusted.
 *
 * **The page is not always talking about the same playout as the mount.** Measured 2026-08-20
 * over 32 track boundaries: it usually agrees within a second, but it can serve markup with no
 * player block at all (8 % of fetches, at random points *inside* songs as well as at boundaries),
 * it can still be showing the previous track, and roughly once in fifteen boundaries it shows a
 * track the mount never announces at all and stays there for minutes. So the album id is used
 * **only when the page's title and artist agree with the track being displayed** — see [agrees].
 * A disagreement yields null and the iTunes path takes over, which turns a timing race into a
 * miss instead of a confidently wrong cover.
 */
object StationNowPlaying {

    /** What the page says right now. Any field may be null — see [parse]. */
    internal data class Snapshot(
        val title: String?,
        val artist: String?,
        val albumId: String?
    )

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
     * The station's own cover for [title] by [artist], or null when the page cannot confirm that
     * this is what it is playing. Blocking — call on IO. Never throws.
     *
     * Retries because the *first* fetch after a boundary is usually not the one that agrees:
     * across 13 measured boundaries it was right immediately 6 times, carried no player block 3
     * times and still showed another track 4 times — but a second fetch a few hundred ms later
     * took that from 6/13 to 10/13. Attempts are deliberately few and close together; the caller
     * bounds the whole thing with [RetroFmConfig.STATION_ARTWORK_BUDGET_MS] anyway, and anything
     * that has not agreed by then is not worth a third request.
     */
    fun artworkUrl(title: String, artist: String): String? {
        // Same guard as ArtworkLookup: a separator-less StreamTitle ("Nyheterna") parses to the
        // station name as artist, and nothing on the page will ever agree with that.
        if (title.isBlank() || artist.isBlank() || artist == RetroFmConfig.STATION_NAME) return null

        val startedAt = System.currentTimeMillis()
        var last: Snapshot? = null
        for (attempt in 1..RetroFmConfig.STATION_NOWPLAYING_ATTEMPTS) {
            val snapshot = fetch() ?: run {
                Timber.tag("Station").w(
                    "page unreachable after %d ms", System.currentTimeMillis() - startedAt
                )
                return null
            }
            last = snapshot
            if (agrees(snapshot, title, artist)) {
                val url = snapshot.albumId?.let(::artworkUrlFor)
                Timber.tag("Station").d(
                    "agreed on attempt %d after %d ms: %s",
                    attempt, System.currentTimeMillis() - startedAt, snapshot.albumId ?: "no album"
                )
                return url
            }
            if (attempt < RetroFmConfig.STATION_NOWPLAYING_ATTEMPTS) {
                if (!pause()) return null
            }
        }
        // Deliberately logged as the page's own answer, not as a failure: a persistent
        // disagreement is the station's page describing a different playout, which is a fact
        // worth seeing in the field logs rather than a bug in this parser.
        Timber.tag("Station").d(
            "no agreement after %d ms — page says %s",
            System.currentTimeMillis() - startedAt,
            last?.title?.take(40) ?: "nothing"
        )
        return null
    }

    private fun fetch(): Snapshot? = try {
        val request = Request.Builder()
            .url(RetroFmConfig.STATION_NOWPLAYING_URL)
            .header("User-Agent", RetroFmConfig.STATION_USER_AGENT)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) null
            else parse(response.body?.string().orEmpty())
        }
    } catch (e: Exception) {
        null
    }

    private fun pause(): Boolean = try {
        Thread.sleep(RetroFmConfig.STATION_NOWPLAYING_RETRY_MS)
        true
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }

    /**
     * Pulls the three fields out of the server-rendered player block.
     *
     * Every field is independent and optional on purpose. The page regularly serves markup with
     * no player block at all — 6 of 73 fetches on 2026-08-20, scattered through songs rather
     * than clustered at boundaries — and that must read as "no answer", never as a signal. It is
     * also someone else's markup: if they restyle, these class names stop matching and the
     * result is a null album id, which degrades to the iTunes path rather than to a wrong cover.
     *
     * The album id is matched on its URL path rather than on the `<img>` tag's attribute order,
     * because the path occurs exactly once in the document and does not depend on how the tag is
     * written.
     */
    internal fun parse(html: String): Snapshot = Snapshot(
        title = TITLE.find(html)?.groupValues?.get(1)?.let(::unescape)?.trim(),
        artist = ARTIST.find(html)?.groupValues?.get(1)?.let(::unescape)?.trim(),
        albumId = ALBUM_ID.find(html)?.groupValues?.get(1)
    )

    /**
     * True when the page is describing the same track that is about to be displayed.
     *
     * The bar is deliberately high: both title and artist, compared after collapsing whitespace
     * and case. Both strings come from the same station injector, so they agree exactly when they
     * agree at all — checked across every song in a 25-minute capture. Anything looser would
     * start accepting the disagreement cases this exists to reject.
     */
    internal fun agrees(snapshot: Snapshot, title: String, artist: String): Boolean {
        if (snapshot.albumId == null) return false
        val gotTitle = key(snapshot.title) ?: return false
        val gotArtist = key(snapshot.artist) ?: return false
        return gotTitle == key(title) && gotArtist == key(artist)
    }

    /** The cover URL for an album id, at the same 600 px rendition the iTunes path uses. */
    internal fun artworkUrlFor(albumId: String): String =
        RetroFmConfig.STATION_ARTWORK_URL_PREFIX + albumId + RetroFmConfig.STATION_ARTWORK_SUFFIX

    private fun key(value: String?): String? =
        value?.replace(WHITESPACE, " ")?.trim()?.lowercase()?.ifEmpty { null }

    /**
     * Decodes the entities the station's own markup carries, in one pass.
     *
     * This used to be a list of literal replacements, and the list was wrong in a way that cost
     * real covers: it carried the *decimal* `&#39;` but ASP.NET emits the **hex** `&#x27;` for an
     * apostrophe. Every title with one failed [agrees] even when the page agreed perfectly —
     * `no agreement — page says Nothing&#x27;s Gonna Stop Me Now` in the field log — which
     * measured **6 of 37 agreements lost (18 %)** on the 2026-08-22 corpus, one of them
     * "Wouldn't It Be Good — Nik Kershaw", the exact track this source exists for.
     *
     * So numeric references are decoded by rule rather than by enumeration, which also closes
     * the next spelling nobody has seen yet. Anything unrecognised is left verbatim: a stray `&`
     * that is not an entity must survive, and an unknown name is better shown as itself than
     * silently dropped.
     *
     * One pass, deliberately — replacing `&amp;` first would turn a literal `&amp;#39;` into
     * `&#39;` and then into an apostrophe the station never wrote.
     */
    private fun unescape(value: String): String = ENTITY.replace(value) { match ->
        val body = match.groupValues[1]
        when {
            body.startsWith("#x", ignoreCase = true) ->
                body.drop(2).toIntOrNull(16)?.let(::codePoint) ?: match.value
            body.startsWith("#") -> body.drop(1).toIntOrNull()?.let(::codePoint) ?: match.value
            else -> NAMED_ENTITIES[body] ?: match.value
        }
    }

    private fun codePoint(value: Int): String? =
        if (value in 1..Character.MAX_CODE_POINT) String(Character.toChars(value)) else null

    private val ENTITY = Regex("&(#[xX][0-9a-fA-F]+|#[0-9]+|[a-zA-Z][a-zA-Z0-9]*);")

    /**
     * `nbsp` maps to a plain space on purpose: [key] collapses runs of `\s`, which in Java does
     * **not** match U+00A0, so a real non-breaking space would survive and break the comparison.
     */
    private val NAMED_ENTITIES = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"",
        "apos" to "'", "nbsp" to " "
    )

    private val ALBUM_ID = Regex("/nowPlayingMedia/albums/([0-9a-fA-F-]{36})-")
    private val TITLE = Regex("class=\"cp-player-track-title\">([^<]*)<")
    private val ARTIST = Regex("class=\"cp-player-artist-name\">([^<]*)<")
    private val WHITESPACE = Regex("\\s+")
}

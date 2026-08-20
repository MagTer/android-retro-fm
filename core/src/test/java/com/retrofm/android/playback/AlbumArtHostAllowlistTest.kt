package com.retrofm.android.playback

import com.retrofm.android.data.config.RetroFmConfig
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

/**
 * Regression guard for the 1.0.41 field bug: album art resolved fine but the car rendered a
 * placeholder, because AlbumArtContentProvider's host allowlist did not include Apple's CDN and
 * openFile blocked every cover.
 *
 * The allowlist itself is private to the provider (and the provider needs an Android runtime to
 * instantiate), so this asserts the same rule against the hosts the app actually produces. If a
 * new artwork source is added, add its host here and to ALLOWED_HOSTS together.
 */
class AlbumArtHostAllowlistTest {

    /** Mirrors AlbumArtContentProvider.ALLOWED_HOSTS and its suffix match. */
    private val allowed = setOf(
        "media.bauerradio.com",
        "assets.planetradio.co.uk",
        "mzstatic.com",
        "retrofm.se"
    )

    private fun isAllowed(url: String): Boolean {
        val host = URI(url).host
        return host != null && allowed.any { host == it || host.endsWith(".$it") }
    }

    @Test
    fun `the station logo is fetchable`() {
        assertTrue(isAllowed(RetroFmConfig.LOGO_PNG_URL))
    }

    /** Apple spreads artwork over is1-ssl … is5-ssl; the suffix match must cover them all. */
    @Test
    fun `every itunes artwork subdomain is fetchable`() {
        for (n in 1..5) {
            val url = "https://is$n-ssl.mzstatic.com/image/thumb/Music221/v4/18/72/e3/x/" +
                "825646124688.jpg/${RetroFmConfig.ARTWORK_RENDITION}.jpg"
            assertTrue("is$n-ssl.mzstatic.com must be allowlisted", isAllowed(url))
        }
    }

    /**
     * The station's own covers (StationNowPlaying). Added in the same commit as the source, per
     * the rule above — a new artwork source is two changes, not one.
     */
    @Test
    fun `the station's own artwork is fetchable`() {
        assertTrue(
            isAllowed(
                com.retrofm.android.data.api.StationNowPlaying
                    .artworkUrlFor("c09ed951-f7ac-460f-b54e-c69d3203a861")
            )
        )
    }

    @Test
    fun `unrelated hosts stay blocked so the provider is not an open proxy`() {
        assertTrue(!isAllowed("https://example.com/evil.png"))
        assertTrue(!isAllowed("https://mzstatic.com.evil.test/evil.png"))
        assertTrue(!isAllowed("https://notmzstatic.com/evil.png"))
    }
}

package com.retrofm.android.playback

/**
 * Parses AdsWizz ad markers out of raw ICY in-stream metadata. The stream announces
 * server-side ad insertions (preroll at connect, but midrolls use the same format) as extra
 * key-value pairs alongside StreamTitle, verified live 2026-07-22:
 *
 * `StreamTitle='';StreamUrl='';adw_ad='true';durationMilliseconds='30024';adId='232764';insertionType='preroll';`
 */
object IcyAdMarker {

    /** Fallback when adw_ad is present without a parsable duration. */
    private const val DEFAULT_AD_DURATION_MS = 30_000L

    private val AD_FLAG = Regex("adw_ad='true'")
    private val DURATION = Regex("durationMilliseconds='(\\d+)'")

    /** Returns the announced ad duration in ms, or null when the metadata is not an ad marker. */
    fun parseDurationMs(rawIcyMetadata: ByteArray): Long? {
        val text = rawIcyMetadata.toString(Charsets.UTF_8)
        if (!AD_FLAG.containsMatchIn(text)) return null
        return DURATION.find(text)?.groupValues?.get(1)?.toLongOrNull() ?: DEFAULT_AD_DURATION_MS
    }
}

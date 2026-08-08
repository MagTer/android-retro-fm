package com.retrofm.android.data.model

import com.retrofm.android.data.config.RetroFmConfig

/**
 * What the surfaces show as "now playing".
 *
 * [eventId] is an identity for dedup, not an upstream key: the station's Icecast announces only
 * `StreamTitle='Title - Artist'`, so [fromStreamTitle] derives a stable positive id from the text
 * itself. Two sentinels sit outside that range — `-1` for station branding and
 * [RetroFmConfig.AD_EVENT_ID] for the ad label — and `eventId > 0` therefore still means
 * "a real, identified track", which the ICY and ad paths both rely on.
 */
data class TrackInfo(
    val eventId: Long,
    val title: String,
    val artist: String,
    val imageUrl: String?
) {
    companion object {
        /** Station-branding placeholder for moments without a song (jingles, talk, ads). */
        fun stationFallback(eventId: Long = -1L): TrackInfo = TrackInfo(
            eventId = eventId,
            title = RetroFmConfig.STATION_NAME,
            artist = RetroFmConfig.STATION_STRAPLINE,
            imageUrl = RetroFmConfig.LOGO_PNG_URL
        )

        /**
         * Parses an Icecast `StreamTitle` body ("Title - Artist"), or null when it carries no
         * track — an empty title is what the mount sends across jingles and talk.
         *
         * Splits on a hyphen surrounded by whitespace rather than the literal `" - "`: the
         * station's injector emits ragged spacing (observed: `What Is Love  - Haddaway`), which
         * a literal split turns into a title with a trailing space and an empty artist. A title
         * that itself contains " - " still keeps everything after the first separator as the
         * artist — the same trade the previous parser made.
         */
        fun fromStreamTitle(streamTitle: String?): TrackInfo? {
            val text = streamTitle?.trim().orEmpty()
            if (text.isEmpty()) return null
            val parts = text.split(SEPARATOR, limit = 2)
            val title = parts.getOrNull(0)?.trim().orEmpty()
            if (title.isEmpty()) return null
            val artist = parts.getOrNull(1)?.trim().orEmpty()
            return TrackInfo(
                eventId = syntheticEventId(text),
                title = title,
                artist = artist.ifEmpty { RetroFmConfig.STATION_NAME },
                imageUrl = RetroFmConfig.LOGO_PNG_URL
            )
        }

        private val SEPARATOR = Regex("\\s+-\\s+")

        /** Stable, always-positive id for a StreamTitle, so dedup and `eventId > 0` both hold. */
        private fun syntheticEventId(streamTitle: String): Long =
            (streamTitle.hashCode().toLong() and 0x7FFF_FFFFL).coerceAtLeast(1L)
    }
}

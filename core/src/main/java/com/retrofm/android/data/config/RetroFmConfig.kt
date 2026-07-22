package com.retrofm.android.data.config

object RetroFmConfig {
    const val STATION_NAME = "Retro FM"
    const val STATION_STRAPLINE = "Tidernas största hits"
    const val STATION_ID = 459
    const val STATION_CODE = "res"
    const val BRAND_CODE = "SE_RETROFM"
    const val BRAND_COLOR_HEX = "#000F2B"

    const val STREAM_URL_MP3 =
        "https://live-bauerse-fm.sharp-stream.com/retrofm_mp3?direct=true"

    const val API_BASE_URL =
        "https://listenapi.planetradio.co.uk/api9.2/"

    const val LOGO_PNG_URL =
        "https://media.bauerradio.com/image/upload/c_crop,g_custom/v1588755887/brand_manager/stations/ujznetkonskklgdql1yd.png"

    const val METADATA_POLL_INTERVAL_MS = 30_000L
    const val METADATA_POLL_MIN_INTERVAL_MS = 2_000L

    /** Exponential backoff schedule for stream reconnect attempts after a player error. */
    val RECONNECT_BACKOFF_MS = listOf(1_000L, 2_000L, 5_000L, 10_000L, 30_000L)
    const val MAX_RECONNECT_ATTEMPTS = 5

    /**
     * Mute the player while a server-spliced ad (see IcyAdMarker) is playing. The UI keeps
     * showing the "Reklam" countdown so the silence is explained. Deliberate decision for the
     * private friends-and-family distribution (2026-07-22); flip to false if the app is ever
     * distributed more widely, since this suppresses the station's own monetization.
     */
    const val MUTE_ADS = true

    /**
     * Compensation for the station's metadata lead. Icecast splices ICY metadata into the
     * byte stream at the wall-clock moment the studio switches tracks, but the matching audio
     * passes the same stream position only after the studio→encoder→ingest pipeline — so in
     * the stream, metadata runs a few constant seconds ahead of the audible transition.
     * ExoPlayer already presents ICY at the buffer-corrected playback position; this delay
     * covers only the upstream lead. Calibrated by ear 2026-07-22: with 6 s the info lagged
     * the audible change by ~6 s → the lead is ~0 for this stream. Kept as a knob; if the
     * title starts flipping N s early again, set this to N * 1000.
     */
    const val ICY_UPSTREAM_LEAD_MS = 0L

    /** Buffer required before playback starts — low so pressing play feels instant. */
    const val BUFFER_FOR_PLAYBACK_MS = 1_000
    /**
     * Buffer required to resume after a stall. Deliberately high: on a poor connection this
     * gives one longer pause instead of repeated micro-stalls, and falling ~10 s behind the
     * live edge is imperceptible for radio.
     */
    const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 10_000
    /** Tighter than ExoPlayer's 8 s defaults so a dead connection surfaces as an error fast. */
    const val STREAM_CONNECT_TIMEOUT_MS = 5_000
    const val STREAM_READ_TIMEOUT_MS = 5_000
}

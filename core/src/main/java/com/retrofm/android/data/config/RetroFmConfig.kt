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
}

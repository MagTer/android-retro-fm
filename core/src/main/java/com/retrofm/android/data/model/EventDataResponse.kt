package com.retrofm.android.data.model

import kotlinx.serialization.Serializable

/**
 * Response from `eventdata/{eventId}` — the endpoint the stream itself advertises via ICY
 * `StreamUrl`. Same information as [NowPlayingResponse] but with camelCase field names and
 * `eventType` spelled out ("Song" instead of "S"). Verified live 2026-07-22.
 */
@Serializable
data class EventDataResponse(
    val eventId: Long,
    val eventStart: String? = null,
    val eventFinish: String? = null,
    val eventType: String? = null,
    val eventSongTitle: String = "",
    val eventSongArtist: String = "",
    val eventImageUrl: String? = null,
    val eventImageUrlSmall: String? = null
)

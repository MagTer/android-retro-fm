package com.retrofm.android.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class NowPlayingResponse(
    @SerialName("EventId") val eventId: Long,
    @SerialName("EventStart") val eventStart: String? = null,
    @SerialName("EventService") val eventService: Int? = null,
    @SerialName("EventFinish") val eventFinish: String? = null,
    @SerialName("EventType") val eventType: String? = null,
    @SerialName("TrackId") val trackId: Int? = null,
    @SerialName("TrackTitle") val trackTitle: String = "",
    @SerialName("TrackDuration") val trackDuration: Int? = null,
    @SerialName("ArtistName") val artistName: String = "",
    @SerialName("ImageUrl") val imageUrl: String? = null,
    @SerialName("ImageUrlSmall") val imageUrlSmall: String? = null,
    @SerialName("ArtistImageUrl") val artistImageUrl: String? = null
)

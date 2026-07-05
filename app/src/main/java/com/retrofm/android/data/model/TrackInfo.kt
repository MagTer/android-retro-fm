package com.retrofm.android.data.model

data class TrackInfo(
    val eventId: Long,
    val title: String,
    val artist: String,
    val imageUrl: String?,
    val startTime: String?,
    val finishTime: String?
)

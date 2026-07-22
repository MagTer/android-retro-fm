package com.retrofm.android.data.api

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit

/**
 * Exercises [RetroFmApi] against a real Retrofit + OkHttp stack so a wrong
 * `@GET`/`@Query` annotation (like the original playlist endpoint bug, P0-4)
 * shows up as a failing request path instead of passing silently against a fake.
 */
class RetroFmApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: RetroFmApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()

        val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            isLenient = true
        }
        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/api9.2/"))
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
        api = retrofit.create(RetroFmApi::class.java)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getNowPlaying requests the nowplaying endpoint and parses a real response`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "EventId": 394102321,
                  "EventStart": "2026-07-05 20:05:02",
                  "EventService": 459,
                  "EventFinish": "2026-07-05 20:09:24",
                  "EventType": "S",
                  "TrackId": 6187,
                  "TrackTitle": "It's Raining Again",
                  "TrackDuration": 250,
                  "ArtistName": "Supertramp",
                  "ImageUrl": "https://assets.planetradio.co.uk/artist/1-1/320x320/71.jpg?ver=1465083195",
                  "ImageUrlSmall": "https://assets.planetradio.co.uk/artist/1-1/160x160/71.jpg?ver=1465083195",
                  "ArtistImageUrl": "https://assets.planetradio.co.uk/artist/1-1/320x320/71.jpg?ver=1465083195"
                }
                """.trimIndent()
            ).setHeader("Content-Type", "application/json")
        )

        val response = api.getNowPlaying()

        val request = server.takeRequest()
        assertEquals("/api9.2/nowplaying/res", request.path)
        assertEquals("It's Raining Again", response.trackTitle)
        assertEquals("Supertramp", response.artistName)
    }

    @Test
    fun `getRecentPlaylist requests the playlist endpoint with StationCode query`() = runTest {
        server.enqueue(
            MockResponse().setBody("[]").setHeader("Content-Type", "application/json")
        )

        api.getRecentPlaylist(stationCode = "res")

        val request = server.takeRequest()
        assertEquals("/api9.2/playlist/?StationCode=res", request.path)
    }

    @Test
    fun `getEventData requests the eventdata endpoint and parses a real response`() = runTest {
        // Captured live from the API 2026-07-22 (the id comes from the stream's ICY StreamUrl).
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "eventId": 398586160,
                  "eventStart": "2026-07-22 15:34:24",
                  "eventFinish": "2026-07-22 15:38:54",
                  "eventDuration": 180,
                  "eventType": "Song",
                  "eventSongTitle": "Kyrie",
                  "eventSongArtist": "Mr. Mister",
                  "eventImageUrl": "https://assets.planetradio.co.uk/artist/1-1/320x320/1809.jpg?ver=1465084128",
                  "eventImageUrlSmall": "https://assets.planetradio.co.uk/artist/1-1/160x160/1809.jpg?ver=1465084128",
                  "eventAppleMusicUrl": "https://geo.itunes.apple.com/dk/album/329887836?i=329891190"
                }
                """.trimIndent()
            ).setHeader("Content-Type", "application/json")
        )

        val response = api.getEventData(eventId = 398586160L)

        val request = server.takeRequest()
        assertEquals("/api9.2/eventdata/398586160", request.path)
        assertEquals("Song", response.eventType)
        assertEquals("Kyrie", response.eventSongTitle)
        assertEquals("Mr. Mister", response.eventSongArtist)
    }

    @Test
    fun `getNowPlaying tolerates a non-song event with missing track fields`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"EventId": 1, "EventType": "A"}"""
            ).setHeader("Content-Type", "application/json")
        )

        val response = api.getNowPlaying()

        assertEquals("A", response.eventType)
        assertEquals("", response.trackTitle)
        assertEquals("", response.artistName)
    }
}

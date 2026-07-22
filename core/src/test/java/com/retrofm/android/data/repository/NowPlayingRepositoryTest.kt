package com.retrofm.android.data.repository

import com.retrofm.android.data.api.RetroFmApi
import com.retrofm.android.data.config.RetroFmConfig
import com.retrofm.android.data.model.EventDataResponse
import com.retrofm.android.data.model.NowPlayingResponse
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingRepositoryTest {

    private val fakeApi = object : RetroFmApi {
        override suspend fun getNowPlaying(): NowPlayingResponse {
            return NowPlayingResponse(
                eventId = 394102321,
                eventStart = "2026-07-05 20:05:02",
                eventService = 459,
                eventFinish = "2026-07-05 20:09:24",
                eventType = "S",
                trackId = 6187,
                trackTitle = "It's Raining Again",
                trackDuration = 250,
                artistName = "Supertramp",
                imageUrl = "https://assets.planetradio.co.uk/artist/1-1/320x320/71.jpg",
                imageUrlSmall = null,
                artistImageUrl = null
            )
        }

        override suspend fun getRecentPlaylist(stationCode: String): List<NowPlayingResponse> {
            return listOf(
                NowPlayingResponse(
                    eventId = 1,
                    eventType = "S",
                    trackTitle = "Last Song",
                    artistName = "Last Artist",
                    imageUrl = "https://example.com/last.jpg"
                )
            )
        }

        override suspend fun getEventData(eventId: Long): EventDataResponse {
            return EventDataResponse(
                eventId = eventId,
                eventStart = "2026-07-22 15:34:24",
                eventFinish = "2026-07-22 15:38:54",
                eventType = "Song",
                eventSongTitle = "Kyrie",
                eventSongArtist = "Mr. Mister",
                eventImageUrl = "https://assets.planetradio.co.uk/artist/1-1/320x320/1809.jpg"
            )
        }
    }

    private fun fakeApiReturning(response: NowPlayingResponse) = object : RetroFmApi {
        override suspend fun getNowPlaying(): NowPlayingResponse = response
        override suspend fun getRecentPlaylist(stationCode: String): List<NowPlayingResponse> =
            listOf(response)
        override suspend fun getEventData(eventId: Long): EventDataResponse =
            error("not used in this test")
    }

    private fun fakeApiReturning(response: EventDataResponse) = object : RetroFmApi {
        override suspend fun getNowPlaying(): NowPlayingResponse =
            error("not used in this test")
        override suspend fun getRecentPlaylist(stationCode: String): List<NowPlayingResponse> =
            error("not used in this test")
        override suspend fun getEventData(eventId: Long): EventDataResponse = response
    }

    @Test
    fun `fetchNowPlaying maps DTO to TrackInfo`() = runTest {
        val repository = NowPlayingRepository(fakeApi)
        val result = repository.fetchNowPlaying()

        assertTrue(result.isSuccess)
        val track = result.getOrThrow()
        assertEquals("It's Raining Again", track.title)
        assertEquals("Supertramp", track.artist)
        assertEquals("https://assets.planetradio.co.uk/artist/1-1/320x320/71.jpg", track.imageUrl)
        assertEquals("2026-07-05 20:05:02", track.startTime)
        assertEquals("2026-07-05 20:09:24", track.finishTime)
    }

    @Test
    fun `fetchNowPlaying returns failure when API throws`() = runTest {
        val failingApi = object : RetroFmApi {
            override suspend fun getNowPlaying(): NowPlayingResponse {
                throw RuntimeException("Network error")
            }

            override suspend fun getRecentPlaylist(stationCode: String): List<NowPlayingResponse> {
                throw RuntimeException("Network error")
            }

            override suspend fun getEventData(eventId: Long): EventDataResponse {
                throw RuntimeException("Network error")
            }
        }

        val repository = NowPlayingRepository(failingApi)
        val result = repository.fetchNowPlaying()

        assertTrue(result.isFailure)
    }

    @Test
    fun `fetchRecentPlaylist maps list of DTOs to TrackInfo list`() = runTest {
        val repository = NowPlayingRepository(fakeApi)
        val result = repository.fetchRecentPlaylist()

        assertTrue(result.isSuccess)
        val tracks = result.getOrThrow()
        assertEquals(1, tracks.size)
        assertEquals("Last Song", tracks[0].title)
        assertEquals("Last Artist", tracks[0].artist)
    }

    @Test
    fun `non-song event falls back to station branding`() = runTest {
        val repository = NowPlayingRepository(
            fakeApiReturning(
                NowPlayingResponse(eventId = 2, eventType = "A", trackTitle = "", artistName = "")
            )
        )

        val track = repository.fetchNowPlaying().getOrThrow()

        assertEquals(RetroFmConfig.STATION_NAME, track.title)
        assertEquals(RetroFmConfig.STATION_STRAPLINE, track.artist)
        assertEquals(RetroFmConfig.LOGO_PNG_URL, track.imageUrl)
    }

    @Test
    fun `song event with blank title falls back to station branding`() = runTest {
        val repository = NowPlayingRepository(
            fakeApiReturning(
                NowPlayingResponse(eventId = 3, eventType = "S", trackTitle = "", artistName = "Someone")
            )
        )

        val track = repository.fetchNowPlaying().getOrThrow()

        assertEquals(RetroFmConfig.STATION_NAME, track.title)
    }

    @Test
    fun `fetchEventData maps a Song event to TrackInfo`() = runTest {
        val repository = NowPlayingRepository(fakeApi)

        val track = repository.fetchEventData(398586160L).getOrThrow()

        assertEquals(398586160L, track.eventId)
        assertEquals("Kyrie", track.title)
        assertEquals("Mr. Mister", track.artist)
        assertEquals("https://assets.planetradio.co.uk/artist/1-1/320x320/1809.jpg", track.imageUrl)
        assertEquals("2026-07-22 15:38:54", track.finishTime)
    }

    @Test
    fun `fetchEventData falls back to station branding for non-song events`() = runTest {
        val repository = NowPlayingRepository(
            fakeApiReturning(EventDataResponse(eventId = 5, eventType = "News"))
        )

        val track = repository.fetchEventData(5L).getOrThrow()

        assertEquals(RetroFmConfig.STATION_NAME, track.title)
        assertEquals(RetroFmConfig.STATION_STRAPLINE, track.artist)
        assertEquals(RetroFmConfig.LOGO_PNG_URL, track.imageUrl)
    }

    @Test
    fun `song event without artwork falls back to the station logo`() = runTest {
        val repository = NowPlayingRepository(
            fakeApiReturning(
                NowPlayingResponse(
                    eventId = 4,
                    eventType = "S",
                    trackTitle = "Some Song",
                    artistName = "Some Artist",
                    imageUrl = null,
                    imageUrlSmall = null,
                    artistImageUrl = null
                )
            )
        )

        val track = repository.fetchNowPlaying().getOrThrow()

        assertEquals(RetroFmConfig.LOGO_PNG_URL, track.imageUrl)
    }
}

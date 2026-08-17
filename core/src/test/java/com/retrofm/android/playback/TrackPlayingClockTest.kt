package com.retrofm.android.playback

import com.retrofm.android.data.config.RetroFmConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The freeze defence's budget must measure *playing* time. These pin the accounting, because the
 * clock's only visible failure mode is off-by-a-stall — and that reached the field once already
 * (see [TrackPlayingClock]).
 */
class TrackPlayingClockTest {

    /** Wall clock under test control; ms. */
    private var now = 1_000_000L
    private val clock = TrackPlayingClock { now }

    private fun advance(ms: Long) {
        now += ms
    }

    @Test
    fun `a stopped clock accrues nothing`() {
        advance(60_000)
        clock.tick()
        assertEquals(0L, clock.playingMs)
    }

    @Test
    fun `playing time accrues between ticks`() {
        clock.start()
        advance(30_000)
        clock.tick()
        advance(30_000)
        clock.tick()
        assertEquals(60_000L, clock.playingMs)
    }

    @Test
    fun `a stall between stop and start does not count`() {
        clock.start()
        advance(120_000)
        clock.stop() // banks 120 s
        advance(210_000) // 3.5 min rebuffer — must be invisible
        clock.start()
        advance(60_000)
        clock.tick()
        assertEquals(180_000L, clock.playingMs)
    }

    @Test
    fun `a new track starts from zero`() {
        clock.start()
        advance(200_000)
        clock.tick()
        clock.restart()
        assertEquals(0L, clock.playingMs)
        advance(45_000)
        clock.tick()
        assertEquals(45_000L, clock.playingMs)
    }

    @Test
    fun `restart while stopped leaves the clock stopped`() {
        clock.start()
        advance(100_000)
        clock.stop()
        clock.restart()
        advance(500_000)
        clock.tick()
        assertEquals(0L, clock.playingMs)
    }

    /**
     * The 2026-08-15 field case, replayed: "Black Velvet" was on screen for 492 s wall clock, of
     * which 210 s was a rebuffer. Wall clock crossed the 480 s threshold and blanked a correct
     * title; playing time is 282 s and stays well under it.
     */
    @Test
    fun `the Black Velvet rebuffer no longer reads as a frozen injector`() {
        clock.start()
        advance(174_000) // playing
        clock.stop()
        advance(210_000) // isPlaying=false 09:19:56 -> 09:23:26
        clock.start()
        advance(108_000) // playing again, up to the re-announcement
        clock.tick()

        assertEquals(282_000L, clock.playingMs)
        assertTrue(clock.playingMs < RetroFmConfig.TRACK_FROZEN_AFTER_MS)
        assertTrue(492_000L > RetroFmConfig.TRACK_FROZEN_AFTER_MS) // what wall clock saw
    }

    /** A genuinely stuck injector keeps playing audio, so it must still trip the threshold. */
    @Test
    fun `a frozen injector still trips the threshold`() {
        clock.start()
        repeat(20) {
            advance(RetroFmConfig.PLAYBACK_HEARTBEAT_MS)
            clock.tick()
        }
        assertTrue(clock.playingMs >= RetroFmConfig.TRACK_FROZEN_AFTER_MS)
    }
}

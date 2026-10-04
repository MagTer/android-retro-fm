package com.retrofm.android.telemetry

import com.retrofm.android.telemetry.PlaybackMeter.Companion.STATE_BUFFERING
import com.retrofm.android.telemetry.PlaybackMeter.Companion.STATE_IDLE
import com.retrofm.android.telemetry.PlaybackMeter.Companion.STATE_READY
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackMeterTest {
    private var clock = 0L
    private val events = mutableListOf<String>()
    private val meter = PlaybackMeter(
        object : PlaybackMetricSink {
            override fun connect(seconds: Double, cause: String, route: String) {
                events += "connect $seconds $cause $route"
            }

            override fun rebuffer(route: String) {
                events += "rebuffer $route"
            }

            override fun error(reason: String, httpClass: String) {
                events += "error $reason $httpClass"
            }
        },
    ) { clock }

    private fun at(ms: Long, playWhenReady: Boolean, state: Int, isPlaying: Boolean, remote: Boolean = false) {
        clock = ms
        meter.update(playWhenReady, state, isPlaying, remote)
    }

    @Test
    fun `play press to first audio is a start connect`() {
        at(0, false, STATE_IDLE, false)
        at(1_000, true, STATE_IDLE, false)
        at(1_100, true, STATE_BUFFERING, false)
        at(3_500, true, STATE_READY, true)
        assertEquals(listOf("connect 2.5 start local"), events)
    }

    @Test
    fun `the connect clock is armed once and never restarted while the wait continues`() {
        at(1_000, true, STATE_BUFFERING, false)
        at(5_000, true, STATE_IDLE, false)        // a failed attempt
        at(9_000, true, STATE_BUFFERING, false)   // the next one
        at(10_000, true, STATE_READY, true)
        assertEquals(listOf("connect 9.0 start local"), events)
    }

    @Test
    fun `a pause abandons the wait — no connect is recorded`() {
        at(1_000, true, STATE_BUFFERING, false)
        at(2_000, false, STATE_BUFFERING, false)
        at(2_500, false, STATE_READY, false)
        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun `a stall under playing audio is one rebuffer however it flaps`() {
        at(0, true, STATE_BUFFERING, false)
        at(1_000, true, STATE_READY, true)
        events.clear()
        at(60_000, true, STATE_BUFFERING, false)
        at(60_100, true, STATE_BUFFERING, false)
        at(60_200, true, STATE_READY, false)
        at(60_300, true, STATE_BUFFERING, false)
        at(70_000, true, STATE_READY, true)
        assertEquals(listOf("rebuffer local"), events)
    }

    @Test
    fun `two separate stalls are two rebuffers`() {
        at(0, true, STATE_READY, true)
        at(10_000, true, STATE_BUFFERING, false)
        at(11_000, true, STATE_READY, true)
        at(20_000, true, STATE_BUFFERING, false, remote = true)
        // Already playing at the first observation: no wait was seen, so no connect either.
        assertEquals(listOf("rebuffer local", "rebuffer remote"), events)
    }

    @Test
    fun `a stream dying under audio is a reconnect, timed to the next audio`() {
        at(0, true, STATE_BUFFERING, false)
        at(2_000, true, STATE_READY, true)
        at(30_000, true, STATE_BUFFERING, false)  // stall…
        at(40_000, true, STATE_IDLE, false)       // …that ends in an error
        at(55_000, true, STATE_BUFFERING, false)  // reconnect after backoff
        at(57_000, true, STATE_READY, true)
        assertEquals(
            listOf("connect 2.0 start local", "rebuffer local", "connect 17.0 reconnect local"),
            events,
        )
    }

    @Test
    fun `the route is read when audio arrives — a cast start is a remote connect`() {
        at(0, true, STATE_IDLE, false)
        at(3_000, true, STATE_READY, true, remote = true)
        assertEquals(listOf("connect 3.0 start remote"), events)
    }

    @Test
    fun `errors carry a bounded reason and the HTTP class, never the code or a message`() {
        meter.playerError("ERROR_CODE_IO_BAD_HTTP_STATUS", 503)
        meter.playerError("ERROR_CODE_IO_NETWORK_CONNECTION_FAILED", null)
        meter.playerError("ERROR_CODE_IO_BAD_HTTP_STATUS", 404)
        meter.watchdogEscalation(PlaybackMeter.REASON_CAST_RELOAD)
        assertEquals(
            listOf(
                "error io_bad_http_status 5xx",
                "error io_network_connection_failed none",
                "error io_bad_http_status 4xx",
                "error cast_stall_reload none",
            ),
            events,
        )
    }

    @Test
    fun `a reason is reduced to lowercase letters, digits and underscores, and capped`() {
        assertEquals("custom_error_code", PlaybackMeter.reasonOf("custom error code"))
        assertEquals(48, PlaybackMeter.reasonOf("X".repeat(200)).length)
        assertEquals("unknown", PlaybackMeter.reasonOf(""))
    }
}

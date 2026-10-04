package com.retrofm.android.telemetry

import com.retrofm.android.telemetry.TelemetryTree.Companion.redact
import org.junit.Assert.assertEquals
import org.junit.Test

/** The backstop for the log-hygiene contract: credentials in URLs never leave the device. */
class TelemetryTreeRedactTest {

    @Test
    fun `a query string is removed — that is where this CDN puts its token`() {
        assertEquals(
            "load error from https://n02-eu.example.com/zc1234?… after 8000 ms",
            redact("load error from https://n02-eu.example.com/zc1234?rj-ttl=5&rj-tok=AAABBB after 8000 ms"),
        )
    }

    @Test
    fun `inside a stack trace too`() {
        val trace = "java.io.IOException: Unable to open https://h.example/x?token=abc\n\tat a.b.C(C.kt:1)"
        assertEquals("java.io.IOException: Unable to open https://h.example/x?…\n\tat a.b.C(C.kt:1)", redact(trace))
    }

    @Test
    fun `userinfo is removed`() {
        assertEquals("GET https://…@host.example/path", redact("GET https://user:pass@host.example/path"))
    }

    @Test
    fun `a URL without credentials and plain text are untouched`() {
        val plain = "cast transfer: LOCAL -> REMOTE receiver=Google Nest Hub item=https://stream.example/25knctp5vepwv"
        assertEquals(plain, redact(plain))
        assertEquals("Hall & Oates - Maneater", redact("Hall & Oates - Maneater"))
    }
}

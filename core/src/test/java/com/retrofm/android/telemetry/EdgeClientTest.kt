package com.retrofm.android.telemetry

import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The edge's answers as this client reads them (telemetry-edge README; home-server
 * TELEMETRY-DESIGN §4.2), over a real socket — the classification and the HTTP client
 * configuration together, since the redirect case is decided by the latter.
 */
class EdgeClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: EdgeClient

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        client = EdgeClient(server.url("/").toString(), "the-key", "cf-id", "cf-secret")
    }

    @After
    fun tearDown() = server.shutdown()

    private fun answer(code: Int, configure: MockResponse.() -> Unit = {}) =
        server.enqueue(MockResponse().setResponseCode(code).apply(configure))

    @Test
    fun `200 is accepted and the request carries the contract's headers and a gzip protobuf body`() {
        answer(200)
        val body = byteArrayOf(1, 2, 3, 4)
        assertEquals(EdgeResult.Ok, client.postLogs(body))

        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/v1/logs", req.path)
        assertEquals("Bearer the-key", req.getHeader("Authorization"))
        assertEquals("cf-id", req.getHeader("CF-Access-Client-Id"))
        assertEquals("cf-secret", req.getHeader("CF-Access-Client-Secret"))
        assertEquals("application/x-protobuf", req.getHeader("Content-Type"))
        assertEquals("gzip", req.getHeader("Content-Encoding"))
        assertArrayEquals(body, GZIPInputStream(req.body.inputStream()).readBytes())
    }

    @Test
    fun `metrics go to their own path`() {
        answer(200)
        client.postMetrics(byteArrayOf(9))
        assertEquals("/v1/metrics", server.takeRequest().path)
    }

    @Test
    fun `the Access pair is not sent unless both halves are set`() {
        answer(200)
        EdgeClient(server.url("/").toString(), "k", "cf-id", "").postLogs(byteArrayOf(1))
        val req = server.takeRequest()
        assertNull(req.getHeader("CF-Access-Client-Id"))
        assertNull(req.getHeader("CF-Access-Client-Secret"))
    }

    @Test
    fun `401 drops — an unknown key cannot be retried into working — and is an auth refusal`() {
        answer(401)
        assertEquals(EdgeResult.Drop(401, "unknown source key", auth = true), client.postLogs(byteArrayOf(1)))
    }

    @Test
    fun `302 from Access is a refusal and is never followed to the login page`() {
        // Following it would land on a 200 login page and read as "shipped".
        answer(302) { setHeader("Location", server.url("/cdn-cgi/access/login").toString()) }
        answer(200)

        val result = client.postLogs(byteArrayOf(1))

        assertTrue(result is EdgeResult.Drop && result.auth && result.status == 302)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `403 drops without counting as an auth refusal`() {
        answer(403)
        val result = client.postMetrics(byteArrayOf(1))
        assertTrue(result is EdgeResult.Drop && !result.auth)
    }

    @Test
    fun `413 asks for a split`() {
        answer(413)
        assertEquals(EdgeResult.TooLarge, client.postLogs(byteArrayOf(1)))
    }

    @Test
    fun `429 carries the edge's Retry-After`() {
        answer(429) { setHeader("Retry-After", "7") }
        assertEquals(EdgeResult.Retry(7_000, "rate limited"), client.postLogs(byteArrayOf(1)))
    }

    @Test
    fun `an absurd Retry-After is capped`() {
        answer(429) { setHeader("Retry-After", "999999") }
        val result = client.postLogs(byteArrayOf(1)) as EdgeResult.Retry
        assertEquals(EdgeClient.MAX_RETRY_AFTER_MS, result.afterMs)
    }

    @Test
    fun `502 and 503 are retried later`() {
        answer(502)
        answer(503)
        assertTrue(client.postLogs(byteArrayOf(1)) is EdgeResult.Retry)
        assertTrue(client.postLogs(byteArrayOf(1)) is EdgeResult.Retry)
    }

    @Test
    fun `415 and 422 drop — the bytes are the problem`() {
        answer(415)
        answer(422)
        assertTrue(client.postLogs(byteArrayOf(1)) is EdgeResult.Drop)
        assertTrue(client.postLogs(byteArrayOf(1)) is EdgeResult.Drop)
    }

    @Test
    fun `a dead socket is retried later`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        assertTrue(client.postLogs(byteArrayOf(1)) is EdgeResult.Retry)
    }

    @Test
    fun `config is read from v1 config with the same credentials`() {
        answer(200) { setBody("""{"source":"retro-fm","level":"DEBUG","flush_interval_s":30}""") }
        val (config, result) = client.fetchConfig()
        assertEquals(EdgeResult.Ok, result)
        assertEquals("DEBUG", config?.level)
        assertEquals(30L, config?.flushIntervalS)
        val req = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("GET", req.method)
        assertEquals("/v1/config", req.path)
        assertEquals("Bearer the-key", req.getHeader("Authorization"))
    }

    @Test
    fun `a redirected config request yields no config`() {
        answer(302) { setHeader("Location", "/login") }
        val (config, result) = client.fetchConfig()
        assertNull(config)
        assertTrue(result is EdgeResult.Drop && result.auth)
    }
}

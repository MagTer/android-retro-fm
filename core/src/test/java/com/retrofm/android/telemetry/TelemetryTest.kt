package com.retrofm.android.telemetry

import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest
import io.opentelemetry.proto.logs.v1.LogRecord
import io.opentelemetry.sdk.resources.Resource
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import timber.log.Timber

/**
 * The whole pipeline — Timber tree, SDK, queues, marshalers, spool, HTTP — against a fake edge,
 * with the request bodies decoded as OTLP protobuf the way the edge would.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TelemetryTest {
    private lateinit var server: MockWebServer
    private val started = mutableListOf<Telemetry>()

    /** What the fake edge answers per path; tests change it mid-way. */
    @Volatile private var level = "WARN"
    @Volatile private var configStatus = 200
    @Volatile private var postStatus = 200
    private val logBodies: MutableList<ExportLogsServiceRequest> = Collections.synchronizedList(mutableListOf())
    private val metricBodies: MutableList<ExportMetricsServiceRequest> = Collections.synchronizedList(mutableListOf())

    @Before
    fun setUp() {
        org.robolectric.shadows.ShadowLog.stream = System.out
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/v1/config" -> MockResponse().setResponseCode(configStatus)
                    .setBody("""{"source":"retro-fm","level":"$level","flush_interval_s":30}""")
                "/v1/logs" -> MockResponse().setResponseCode(postStatus).also {
                    if (postStatus == 200) logBodies += ExportLogsServiceRequest.parseFrom(gunzip(request))
                }
                "/v1/metrics" -> MockResponse().setResponseCode(postStatus).also {
                    if (postStatus == 200) metricBodies += ExportMetricsServiceRequest.parseFrom(gunzip(request))
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        started.forEach { it.shutdown() }
        server.shutdown()
    }

    private fun gunzip(request: RecordedRequest) = GZIPInputStream(request.body.inputStream()).readBytes()

    private fun settings(url: String = server.url("/").toString(), key: String = "test-key") =
        TelemetrySettings(url, key, "cf-id", "cf-secret")

    private fun limits(spool: Boolean = false) = TelemetryLimits(
        spoolEnabled = spool,
        spoolMaxBytes = 128 * 1024,
        spoolMinWriteIntervalMs = 120_000,
        spoolMaxReplayRecords = 500,
    )

    private fun start(spoolDir: File? = null): Telemetry =
        Telemetry.start(settings(), Resource.empty(), limits(spool = spoolDir != null), spoolDir)!!
            .also { started += it }

    private fun sentRecords(): List<LogRecord> = synchronized(logBodies) {
        logBodies.flatMap { r -> r.resourceLogsList.flatMap { rl -> rl.scopeLogsList.flatMap { it.logRecordsList } } }
    }

    private fun LogRecord.attr(key: String) = attributesList.firstOrNull { it.key == key }?.value

    // --- no URL or key: nothing exists -------------------------------------------------------

    @Test
    fun `a build without URL and key creates no exporter and starts nothing`() {
        var factoryCalls = 0
        val countingFactory: (TelemetrySettings) -> EdgeClient = {
            factoryCalls++
            EdgeClient(it.url, it.key, it.accessClientId, it.accessClientSecret)
        }
        for (s in listOf(settings(url = "", key = ""), settings(url = ""), settings(key = ""), settings(key = "  "))) {
            assertNull(s.toString(), Telemetry.start(s, Resource.empty(), limits(), null, countingFactory))
        }
        assertEquals(0, factoryCalls)
        assertEquals(0, server.requestCount)

        Telemetry.start(settings(), Resource.empty(), limits(), null, countingFactory)!!.also { started += it }
        assertEquals(1, factoryCalls)
    }

    @Test
    fun `settings never print their credentials`() {
        val text = TelemetrySettings("https://ingest.example", "s3cret-key", "id-value", "secret-value").toString()
        listOf("s3cret-key", "id-value", "secret-value").forEach { assertTrue(text, it !in text) }
    }

    // --- the level from /v1/config -------------------------------------------------------------

    @Test
    fun `records below the edge's level are never sent`() = runBlocking {
        level = "WARN"
        val t = start()
        t.tree.d("a debug line")
        t.tree.i("an info line")
        t.tree.w("a warn line")
        t.tree.e("an error line")
        t.flush()

        val sent = sentRecords()
        assertEquals(listOf("a warn line", "an error line"), sent.map { it.body.stringValue })
        assertEquals(listOf(13, 17), sent.map { it.severityNumberValue })
        assertEquals(listOf("WARN", "ERROR"), sent.map { it.severityText })
    }

    @Test
    fun `at DEBUG everything is sent, with session id, seq and tag on every record`() = runBlocking {
        level = "DEBUG"
        val t = start()
        Timber.plant(t.tree)
        try {
            Timber.tag("Playback").d("one")
            Timber.i("two")
        } finally {
            Timber.uproot(t.tree)
        }
        t.flush()

        val sent = sentRecords()
        assertEquals(listOf("one", "two"), sent.map { it.body.stringValue })
        assertTrue(sent.all { it.attr("session.id")?.stringValue == t.sessionId })
        assertEquals(listOf(1L, 2L), sent.map { it.attr("seq")?.intValue })
        assertEquals("Playback", sent[0].attr("tag")?.stringValue)
    }

    @Test
    fun `nothing is sent before the level is known`() = runBlocking {
        configStatus = 503
        val t = start()
        t.tree.e("an error line")
        t.flush()
        assertTrue(sentRecords().isEmpty())
        assertTrue(server.requestCount >= 1)          // it did ask

        // The answer arrives: the waiting record is judged and sent.
        configStatus = 200
        t.flushNow()
        assertEquals(listOf("an error line"), sentRecords().map { it.body.stringValue })
    }

    // --- the spool ----------------------------------------------------------------------------

    @Test
    fun `records a process could not ship are backfilled from disk by the next process`() = runBlocking {
        level = "INFO"
        val dir = Files.createTempDirectory("spool").toFile()

        // Process 1: online for config, but the edge refuses to store — then it dies.
        postStatus = 503
        val first = start(spoolDir = dir)
        first.tree.w("said while offline")
        first.tree.e("also said while offline")
        first.flush()
        first.persistNow()
        first.shutdown()
        assertTrue("nothing may arrive while the edge refuses", sentRecords().isEmpty())
        assertTrue("the spool holds the records", dir.walk().any { it.isFile })

        // Process 2: the edge is back.
        postStatus = 200
        val second = start(spoolDir = dir)
        second.flush()
        // The replay marker is emitted on the telemetry thread; the first flush's drain is
        // queued behind the replay there, so a second flush is guaranteed to carry it.
        second.flush()

        val sent = sentRecords()
        val replayed = sent.filter { it.attr("session.id")?.stringValue == first.sessionId }
        assertEquals(listOf("said while offline", "also said while offline"), replayed.map { it.body.stringValue })
        assertEquals(listOf(1L, 2L), replayed.map { it.attr("seq")?.intValue })
        assertTrue(sent.map { it.body.stringValue }.toString(), sent.any { it.body.stringValue.startsWith("spool: replayed 2 records") })

        // Shipped means gone from disk: a third process replays nothing.
        logBodies.clear()
        second.shutdown()
        val third = start(spoolDir = dir)
        third.flush()
        assertTrue("replayed twice", sentRecords().none { it.attr("session.id")?.stringValue == first.sessionId })
    }

    @Test
    fun `an online session writes nothing to disk`() = runBlocking {
        level = "DEBUG"
        val dir = Files.createTempDirectory("spool").toFile()
        val t = start(spoolDir = dir)
        repeat(50) { t.tree.w("line $it") }
        t.flush()
        t.persistNow()
        assertEquals(50, sentRecords().count { it.body.stringValue.startsWith("line ") })
        assertEquals(emptyList<String>(), dir.walk().filter { it.isFile }.map { it.name }.toList())
    }

    // --- metrics -----------------------------------------------------------------------------

    @Test
    fun `the three declared metric names, and only those, reach the edge`() = runBlocking {
        val t = start()
        t.playbackSink.connect(1.7, PlaybackMeter.CAUSE_START, PlaybackMeter.ROUTE_LOCAL)
        t.playbackSink.rebuffer(PlaybackMeter.ROUTE_LOCAL)
        t.playbackSink.error("io_bad_http_status", "5xx")
        t.flush()
        // A second collection: the SDK records its own reader's collection time AFTER a
        // collect, so a self-metric first appears in the next export. One flush passed
        // while production received otel.sdk.metric_reader.collection.duration
        // (home-server's edge v0.2.0 named it, 2026-10-04).
        t.flush()

        val metrics = synchronized(metricBodies) {
            metricBodies.flatMap { r -> r.resourceMetricsList.flatMap { rm -> rm.scopeMetricsList.flatMap { it.metricsList } } }
        }
        assertEquals(
            // The declaration in home-server clusters/base/telemetry/sources/retro-fm.yaml.
            setOf("retrofm_stream_connect_seconds", "retrofm_rebuffer_total", "retrofm_playback_errors_total"),
            metrics.map { it.name }.toSet(),
        )
        val connect = metrics.first { it.name == "retrofm_stream_connect_seconds" }
        assertEquals("s", connect.unit)
        assertTrue(connect.hasHistogram())
        val errors = metrics.first { it.name == "retrofm_playback_errors_total" }
        val point = errors.sum.dataPointsList.single()
        assertEquals(
            mapOf("reason" to "io_bad_http_status", "http_class" to "5xx"),
            point.attributesList.associate { it.key to it.value.stringValue },
        )
    }
}

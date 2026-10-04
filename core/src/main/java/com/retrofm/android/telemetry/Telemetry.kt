package com.retrofm.android.telemetry

import android.util.Log
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.logs.Severity
import io.opentelemetry.api.metrics.DoubleHistogram
import io.opentelemetry.api.metrics.LongCounter
import io.opentelemetry.exporter.internal.otlp.logs.LogsRequestMarshaler
import io.opentelemetry.exporter.internal.otlp.metrics.MetricsRequestMarshaler
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.logs.SdkLoggerProvider
import io.opentelemetry.sdk.logs.data.LogRecordData
import io.opentelemetry.sdk.logs.export.BatchLogRecordProcessor
import io.opentelemetry.sdk.logs.export.LogRecordExporter
import io.opentelemetry.sdk.metrics.InstrumentType
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.metrics.data.AggregationTemporality
import io.opentelemetry.sdk.metrics.data.MetricData
import io.opentelemetry.sdk.metrics.export.MetricExporter
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader
import io.opentelemetry.sdk.resources.Resource
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext

/** Build-time telemetry configuration. A blank [url] or [key] means telemetry is off. */
data class TelemetrySettings(
    val url: String,
    val key: String,
    val accessClientId: String,
    val accessClientSecret: String,
) {
    val enabled: Boolean get() = url.isNotBlank() && key.isNotBlank()

    // Never print the credentials, even by accident in a log line or a test failure.
    override fun toString(): String =
        "TelemetrySettings(url=${if (url.isBlank()) "<blank>" else url}, key=${describe(key)}, " +
            "accessClientId=${describe(accessClientId)}, accessClientSecret=${describe(accessClientSecret)})"

    private fun describe(v: String) = if (v.isBlank()) "<blank>" else "<${v.length} chars>"
}

/** Knobs, with the values RetroFmConfig gives them; see there for the reasons. */
data class TelemetryLimits(
    val spoolEnabled: Boolean,
    val spoolMaxBytes: Int,
    val spoolMinWriteIntervalMs: Long,
    val spoolMaxReplayRecords: Int,
    val maxBufferedRecords: Int = 2_000,
    val maxBatchRecords: Int = 200,
    val maxBatchBytes: Int = 256 * 1024,
    val defaultFlushIntervalMs: Long = 30_000L,
    val maxBackoffMs: Long = 5 * 60_000L,
    val metricExportIntervalMs: Long = 60_000L,
)

/**
 * Retro FM's OpenTelemetry pipeline against home-server's telemetry edge (TELEMETRY-DESIGN §5,
 * unit U7). Replaces the vendored `se.falle.logsink` client.
 *
 * ```
 * Timber ─► TelemetryTree ─► SdkLoggerProvider ─► BatchLogRecordProcessor ─┐
 * PlaybackMeter ─► Meter ─► SdkMeterProvider ─► PeriodicMetricReader ──────┤ (memory only)
 *                                                                          ▼
 *                         EdgeQueue (logs) / EdgeQueue (metrics) ── telemetry thread, every
 *                         flush interval: GET /v1/config, POST /v1/logs, POST /v1/metrics
 *                                   │ only after a send failed, and at teardown
 *                                   ▼
 *                                LogSpool (disk)
 * ```
 *
 * **Why not the SDK's OtlpHttpLogRecordExporter.** Its OkHttp sender (1.66.0, read
 * 2026-10-04) follows redirects — OkHttp's default — so Cloudflare Access's 302-to-login
 * becomes a 200 and the batch is reported exported while it was dropped. It also has no answer
 * to 413 but failure, and the batch processor drops a failed batch outright. So the SDK does
 * what it is good at (the data model, batching, the protobuf marshalers) and [EdgeQueue] +
 * [EdgeClient] own the wire. The marshalers are `exporter.internal` API — pinned with the BOM
 * in `core/build.gradle.kts`; a change there is a compile error, not a silent one.
 *
 * Construct with [start], which returns null — creating no exporter, no thread and no file —
 * when the build carries no URL or key.
 */
class Telemetry private constructor(
    private val edge: EdgeClient,
    resource: Resource,
    private val limits: TelemetryLimits,
    spoolDir: File?,
    private val now: () -> Long,
) {
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "telemetry").apply { isDaemon = true }
    }
    private val dispatcher = executor.asCoroutineDispatcher()

    val gate = SeverityGate()
    val sessionId: String = UUID.randomUUID().toString()

    private val logQueue = EdgeQueue<LogRecordData>(
        send = edge::postLogs,
        marshal = ::marshalLogs,
        maxItems = limits.maxBufferedRecords,
        maxBatchItems = limits.maxBatchRecords,
        maxBatchBytes = limits.maxBatchBytes,
        baseBackoffMs = limits.defaultFlushIntervalMs,
        maxBackoffMs = limits.maxBackoffMs,
        now = now,
    )
    private val metricQueue = EdgeQueue<MetricData>(
        send = edge::postMetrics,
        marshal = ::marshalMetrics,
        // Cumulative points: a stalled minute costs resolution, not counts, so a short queue
        // is enough — 3 metrics × an hour of one-minute exports, then drop-oldest.
        maxItems = 180,
        maxBatchItems = limits.maxBatchRecords,
        maxBatchBytes = limits.maxBatchBytes,
        baseBackoffMs = limits.defaultFlushIntervalMs,
        maxBackoffMs = limits.maxBackoffMs,
        now = now,
    )

    private val spool: LogSpool? =
        if (limits.spoolEnabled && spoolDir != null) LogSpool(spoolDir, limits.spoolMaxBytes) else null
    private var lastSpoolWriteMs = Long.MIN_VALUE / 2

    @Volatile
    private var flushIntervalMs = limits.defaultFlushIntervalMs
    private var tick: ScheduledFuture<*>? = null
    private var lastReported: EdgeResult? = null

    private val loggerProvider: SdkLoggerProvider = SdkLoggerProvider.builder()
        .setResource(resource)
        .addLogRecordProcessor(
            BatchLogRecordProcessor.builder(QueueLogExporter(logQueue)).build()
        )
        .build()

    private val meterProvider: SdkMeterProvider = SdkMeterProvider.builder()
        .setResource(resource)
        .registerMetricReader(
            PeriodicMetricReader.builder(QueueMetricExporter(metricQueue))
                .setInterval(limits.metricExportIntervalMs, TimeUnit.MILLISECONDS)
                .build()
        )
        .build()

    private val logger = loggerProvider.get(INSTRUMENTATION_SCOPE)

    val tree = TelemetryTree(logger, gate, sessionId)

    val playbackSink: PlaybackMetricSink = meterProvider.get(INSTRUMENTATION_SCOPE).let { meter ->
        OtelPlaybackSink(
            connect = meter.histogramBuilder(METRIC_CONNECT)
                .setUnit("s")
                .setDescription("Time from playback wanted to first audio")
                .setExplicitBucketBoundariesAdvice(CONNECT_BUCKETS_S)
                .build(),
            rebuffers = meter.counterBuilder(METRIC_REBUFFER)
                .setUnit("{rebuffer}")
                .setDescription("Audio stalls while playback was wanted")
                .build(),
            errors = meter.counterBuilder(METRIC_ERRORS)
                .setUnit("{error}")
                .setDescription("Playback failures, by bounded reason")
                .build(),
        )
    }

    private fun startTicking() {
        executor.execute { runCatching { replaySpool() }.onFailure { logcat("replay", it) } }
        scheduleTick(0)
    }

    private fun scheduleTick(delayMs: Long) {
        tick = executor.schedule({
            runCatching { refreshConfig(); drainAll(ignoreBackoff = false) }
                .onFailure { logcat("tick", it) }
            scheduleTick(flushIntervalMs)
        }, delayMs, TimeUnit.MILLISECONDS)
    }

    /** `GET /v1/config` — at start and then every flush interval, as the edge asks. */
    private fun refreshConfig() {
        val (config, result) = edge.fetchConfig()
        if (config == null) {
            report(result)
            return
        }
        val before = gate.levelName
        if (!gate.apply(config.level)) Log.w(TAG, "edge sent an unknown level; keeping ${gate.levelName}")
        if (before != gate.levelName) Log.i(TAG, "remote level ${before ?: "unknown"} -> ${gate.levelName}")
        config.flushIntervalS?.let {
            flushIntervalMs = (it * 1000).coerceIn(MIN_FLUSH_INTERVAL_MS, MAX_FLUSH_INTERVAL_MS)
        }
    }

    /** Telemetry thread only. */
    private fun drainAll(ignoreBackoff: Boolean) {
        val dropped = logQueue.takeDropped() + metricQueue.takeDropped()
        if (dropped > 0) {
            // Through the logger, so it lands in the queue ahead of the next drain like any
            // other WARN — a gap in the store then says why it is there.
            logger.logRecordBuilder()
                .setSeverity(Severity.WARN).setSeverityText("WARN")
                .setBody("telemetry buffer overflow: dropped $dropped records on device")
                .setAllAttributes(Attributes.of(TelemetryTree.TAG, "Telemetry", TelemetryTree.SESSION_ID, sessionId))
                .emit()
        }
        val logsOk = if (gate.known) {
            logQueue.drain(
                admit = { gate.admitsForSend(it.severity.severityNumber) },
                ignoreBackoff = ignoreBackoff,
            )
        } else {
            // Nothing leaves before the level is known; the records wait, and are at risk.
            logQueue.isEmpty()
        }
        metricQueue.drain(ignoreBackoff = ignoreBackoff)
        report(logQueue.lastProblem ?: metricQueue.lastProblem ?: EdgeResult.Ok)
        if (logsOk) spool?.clearIfWritten() else maybeSpool(force = false)
    }

    /** Logcat, once per change of answer: refusals must be visible on a bench, not repeated. */
    private fun report(result: EdgeResult) {
        if (result == lastReported) return
        lastReported = result
        when (result) {
            EdgeResult.Ok -> Log.i(TAG, "edge accepting")
            is EdgeResult.Drop -> Log.w(TAG, "edge refused ${result.status}: ${result.why}")
            EdgeResult.TooLarge -> Log.w(TAG, "edge refused a batch as too large — splitting")
            is EdgeResult.Retry -> Log.w(TAG, "edge unavailable (${result.why}) — retrying later")
        }
    }

    private fun maybeSpool(force: Boolean) {
        val s = spool ?: return
        val t = now()
        if (!force && t - lastSpoolWriteMs < limits.spoolMinWriteIntervalMs) return
        val records = logQueue.takeUnspooled()
        if (records.isEmpty()) return
        if (s.write(records)) lastSpoolWriteMs = t else logQueue.markUnspooled(records)
    }

    private fun replaySpool() {
        val s = spool ?: return
        val restored = s.replay().takeLast(limits.spoolMaxReplayRecords)
        if (restored.isEmpty()) return
        logQueue.addFirst(restored)
        logger.logRecordBuilder()
            .setSeverity(Severity.INFO).setSeverityText("INFO")
            .setBody("spool: replayed ${restored.size} records from a previous process")
            .setAllAttributes(Attributes.of(TelemetryTree.TAG, "Telemetry", TelemetryTree.SESSION_ID, sessionId))
            .emit()
    }

    /** Ships what is buffered, respecting any backoff — the lifecycle hooks' flush. */
    suspend fun flush() {
        forceFlushSdk(FORCE_FLUSH_TIMEOUT_MS)
        withContext(dispatcher) { drainAll(ignoreBackoff = false) }
    }

    /**
     * Ships now, past any backoff — call when connectivity is known to be back
     * (NET_CAPABILITY_VALIDATED). A backoff grown offline would otherwise sleep through a short
     * online window, the failure the stream reconnect also had before it was keyed to it.
     */
    suspend fun flushNow() {
        forceFlushSdk(FORCE_FLUSH_TIMEOUT_MS)
        withContext(dispatcher) {
            if (!gate.known) refreshConfig()
            drainAll(ignoreBackoff = true)
        }
    }

    /** Writes whatever could not ship to the spool, ignoring the write interval. Teardown. */
    suspend fun persistNow() = withContext(dispatcher) { maybeSpool(force = true) }

    /**
     * For the uncaught-exception handler: flush, then persist, bounded by [timeoutMs] in all —
     * the platform handler must still get to run. Safe to call from any thread, including the
     * telemetry thread itself (it then only waits out the bound).
     */
    fun flushAndPersistBlocking(timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        forceFlushSdk(timeoutMs / 2)
        runCatching {
            executor.submit {
                runCatching { drainAll(ignoreBackoff = true) }
                maybeSpool(force = true)
            }.get(maxOf(1, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS)
        }
    }

    /**
     * Stops the telemetry thread. Not called in the app — the pipeline lives as long as the
     * process — but a test ends one "process" with it before starting the next.
     */
    internal fun shutdown() {
        tick?.cancel(false)
        executor.shutdownNow()
        executor.awaitTermination(5, TimeUnit.SECONDS)
    }

    private fun forceFlushSdk(timeoutMs: Long) {
        CompletableResultCode.ofAll(listOf(loggerProvider.forceFlush(), meterProvider.forceFlush()))
            .join(timeoutMs, TimeUnit.MILLISECONDS)
    }

    private fun logcat(what: String, t: Throwable) = Log.w(TAG, "telemetry $what failed", t)

    private class QueueLogExporter(private val queue: EdgeQueue<LogRecordData>) : LogRecordExporter {
        override fun export(logs: Collection<LogRecordData>): CompletableResultCode {
            queue.add(logs)
            return CompletableResultCode.ofSuccess()
        }

        override fun flush(): CompletableResultCode = CompletableResultCode.ofSuccess()
        override fun shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess()
    }

    private class QueueMetricExporter(private val queue: EdgeQueue<MetricData>) : MetricExporter {
        // Cumulative: a point that is late or lost costs resolution, never a count, and
        // VictoriaMetrics' rate()/increase() expect it.
        override fun getAggregationTemporality(instrumentType: InstrumentType) =
            AggregationTemporality.CUMULATIVE

        override fun export(metrics: Collection<MetricData>): CompletableResultCode {
            queue.add(metrics)
            return CompletableResultCode.ofSuccess()
        }

        override fun flush(): CompletableResultCode = CompletableResultCode.ofSuccess()
        override fun shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess()
    }

    private class OtelPlaybackSink(
        private val connect: DoubleHistogram,
        private val rebuffers: LongCounter,
        private val errors: LongCounter,
    ) : PlaybackMetricSink {
        override fun connect(seconds: Double, cause: String, route: String) =
            connect.record(seconds, Attributes.of(CAUSE, cause, ROUTE, route))

        override fun rebuffer(route: String) = rebuffers.add(1, Attributes.of(ROUTE, route))

        override fun error(reason: String, httpClass: String) =
            errors.add(1, Attributes.of(REASON, reason, HTTP_CLASS, httpClass))
    }

    companion object {
        private const val TAG = "Telemetry"
        const val INSTRUMENTATION_SCOPE = "com.retrofm.android"

        /**
         * The edge admits exactly these names (home-server
         * `clusters/base/telemetry/sources/retro-fm.yaml`); any other is dropped and counted.
         * With `-opentelemetry.usePrometheusNaming` VictoriaMetrics keeps them as they are —
         * unit `s` maps to "seconds", already the last token, and a counter's "total" is
         * already last (sanitize.go, read 2026-10-04) — plus `_bucket/_sum/_count` on the
         * histogram. Renaming one is a change on both sides: a shipped contract.
         */
        const val METRIC_CONNECT = "retrofm_stream_connect_seconds"
        const val METRIC_REBUFFER = "retrofm_rebuffer_total"
        const val METRIC_ERRORS = "retrofm_playback_errors_total"

        /**
         * One series per bucket per attribute set, against a 500-series-a-day budget. Twelve
         * boundaries × cause(2) × route(2) ≈ 60 series. The low end resolves a warm start
         * (sub-second to a few seconds); the top resolves a cast start that once took 38 s
         * and a reconnect that waits out the backoff.
         */
        val CONNECT_BUCKETS_S = listOf(0.5, 1.0, 2.0, 3.0, 5.0, 8.0, 13.0, 20.0, 30.0, 60.0, 120.0, 300.0)

        private val CAUSE = AttributeKey.stringKey("cause")
        private val ROUTE = AttributeKey.stringKey("route")
        private val REASON = AttributeKey.stringKey("reason")
        private val HTTP_CLASS = AttributeKey.stringKey("http_class")

        private const val FORCE_FLUSH_TIMEOUT_MS = 2_000L
        private const val MIN_FLUSH_INTERVAL_MS = 5_000L
        private const val MAX_FLUSH_INTERVAL_MS = 10 * 60_000L

        /**
         * The only way to build one. Returns null — and touches nothing: no [EdgeClient] from
         * [edgeFactory], no thread, no file — unless [settings] carries both a URL and a key.
         */
        fun start(
            settings: TelemetrySettings,
            resource: Resource,
            limits: TelemetryLimits,
            spoolDir: File?,
            edgeFactory: (TelemetrySettings) -> EdgeClient = ::defaultEdge,
            now: () -> Long = System::currentTimeMillis,
        ): Telemetry? {
            if (!settings.enabled) {
                // The one sign an "it sends nothing" build gives that it was built that way.
                Log.i(TAG, "telemetry off — this build carries no ingest URL or key")
                return null
            }
            return Telemetry(edgeFactory(settings), resource, limits, spoolDir, now)
                .also { it.startTicking() }
        }

        private fun defaultEdge(s: TelemetrySettings) =
            EdgeClient(s.url, s.key, s.accessClientId, s.accessClientSecret)

        internal fun marshalLogs(logs: List<LogRecordData>): ByteArray = ByteArrayOutputStream().also {
            LogsRequestMarshaler.create(logs).writeBinaryTo(it)
        }.toByteArray()

        internal fun marshalMetrics(metrics: List<MetricData>): ByteArray = ByteArrayOutputStream().also {
            MetricsRequestMarshaler.create(metrics).writeBinaryTo(it)
        }.toByteArray()
    }
}

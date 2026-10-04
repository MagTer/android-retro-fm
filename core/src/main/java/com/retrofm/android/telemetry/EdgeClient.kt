package com.retrofm.android.telemetry

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * What the edge's answer means for the bytes that were sent. The mapping is the edge's
 * contract (telemetry-edge README, home-server TELEMETRY-DESIGN §4.2), decided in [classify].
 */
sealed interface EdgeResult {
    data object Ok : EdgeResult

    /**
     * The same bytes will be refused every time — retrying cannot help, so they are dropped.
     * [auth] marks the refusals that will also refuse *everything else* (a wrong key, a missing
     * Access token): the caller backs off rather than re-asking every flush.
     */
    data class Drop(val status: Int, val why: String, val auth: Boolean) : EdgeResult

    /** 413: the body is too large for the path. Never resend it unchanged — split it. */
    data object TooLarge : EdgeResult

    /** Worth retrying later. [afterMs] is the edge's own Retry-After, when it sent one. */
    data class Retry(val afterMs: Long?, val why: String) : EdgeResult
}

/**
 * Pure: the edge's status code → what to do with the batch.
 *
 * The redirect row is the one that passes silently if it is wrong. Cloudflare Access answers a
 * request without a valid service token with a **302 to its login page**; a client that follows
 * it reads the login page's 200 as "shipped" and drops the lines without a trace. The old
 * client learned that against applogs (see its history); [EdgeClient] never follows redirects,
 * and this treats any 3xx as an auth refusal.
 */
internal fun classify(code: Int, retryAfterMs: Long?): EdgeResult = when {
    code in 200..299 -> EdgeResult.Ok
    code in 300..399 -> EdgeResult.Drop(code, "redirected — Access refused the service token", auth = true)
    code == 401 -> EdgeResult.Drop(code, "unknown source key", auth = true)
    code == 403 -> EdgeResult.Drop(code, "signal not declared for this source", auth = false)
    code == 413 -> EdgeResult.TooLarge
    code == 429 -> EdgeResult.Retry(retryAfterMs, "rate limited")
    code == 408 -> EdgeResult.Retry(retryAfterMs, "request timeout")
    code in 500..599 -> EdgeResult.Retry(retryAfterMs, "edge or store trouble ($code)")
    // 415 (media type), 422 (malformed) and anything else in 4xx: the bytes are the problem.
    else -> EdgeResult.Drop(code, "refused", auth = false)
}

/** `{"source":"retro-fm","level":"WARN","flush_interval_s":30}` from `GET /v1/config`. */
@Serializable
internal data class EdgeConfig(
    val level: String? = null,
    @SerialName("flush_interval_s") val flushIntervalS: Long? = null,
)

/**
 * The HTTP half of the edge contract: three headers on every request, OTLP/HTTP protobuf
 * bodies (gzip — the edge allows ≤ 1 MiB on the wire), redirects never followed.
 *
 * Never logs through Timber: a TelemetryTree would loop back into it. Its own diagnostics go to
 * the caller as [EdgeResult]s.
 */
class EdgeClient internal constructor(
    baseUrl: String,
    private val sourceKey: String,
    private val accessClientId: String,
    private val accessClientSecret: String,
    private val http: OkHttpClient,
) {
    constructor(baseUrl: String, sourceKey: String, accessClientId: String, accessClientSecret: String) :
        this(baseUrl, sourceKey, accessClientId, accessClientSecret, defaultHttpClient())

    private val base = baseUrl.trimEnd('/')
    private val sendAccess = accessClientId.isNotBlank() && accessClientSecret.isNotBlank()

    fun postLogs(body: ByteArray): EdgeResult = post("/v1/logs", body)

    fun postMetrics(body: ByteArray): EdgeResult = post("/v1/metrics", body)

    private fun Request.Builder.authorize(): Request.Builder = apply {
        header("Authorization", "Bearer $sourceKey")
        if (sendAccess) {
            header("CF-Access-Client-Id", accessClientId)
            header("CF-Access-Client-Secret", accessClientSecret)
        }
    }

    internal fun post(path: String, body: ByteArray): EdgeResult {
        val request = Request.Builder()
            .url(base + path)
            .authorize()
            .header("Content-Encoding", "gzip")
            .post(gzip(body).toRequestBody(PROTOBUF))
            .build()
        return try {
            http.newCall(request).execute().use { classify(it.code, retryAfterMs(it)) }
        } catch (e: IOException) {
            // DNS, connect, TLS, a dead socket: the car's modem does all of these mid-drive.
            EdgeResult.Retry(null, "transport: ${e.javaClass.simpleName}")
        }
    }

    /**
     * `GET /v1/config`. Returns the parsed answer, or the [EdgeResult] that explains why there
     * is none — a refusal here means the same refusal for every POST, so callers treat it alike.
     */
    internal fun fetchConfig(): Pair<EdgeConfig?, EdgeResult> {
        val request = Request.Builder().url("$base/v1/config").authorize().get().build()
        return try {
            http.newCall(request).execute().use { response ->
                val result = classify(response.code, retryAfterMs(response))
                if (result != EdgeResult.Ok) return null to result
                val text = response.body?.string().orEmpty()
                val parsed = runCatching { json.decodeFromString(EdgeConfig.serializer(), text) }.getOrNull()
                    ?: return null to EdgeResult.Drop(response.code, "unparseable config", auth = false)
                parsed to result
            }
        } catch (e: IOException) {
            null to EdgeResult.Retry(null, "transport: ${e.javaClass.simpleName}")
        }
    }

    internal companion object {
        private val PROTOBUF = "application/x-protobuf".toMediaType()
        private val json = Json { ignoreUnknownKeys = true }

        /** A Retry-After past this is not honoured literally — an hour of silence is enough. */
        const val MAX_RETRY_AFTER_MS = 60 * 60_000L

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            // Never follow: see [classify]. A followed Access redirect reads as success.
            .followRedirects(false)
            .followSslRedirects(false)
            // The car's first request after a modem comes up can need a full retransmitted SYN
            // series to connect (CLAUDE.md, artwork section); 10 s covers it, and nothing waits.
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()

        fun gzip(bytes: ByteArray): ByteArray {
            val out = ByteArrayOutputStream(bytes.size / 4 + 64)
            GZIPOutputStream(out).use { it.write(bytes) }
            return out.toByteArray()
        }

        /** Retry-After as delta-seconds or an HTTP date, capped; null when absent or unreadable. */
        fun retryAfterMs(response: Response): Long? {
            val raw = response.header("Retry-After") ?: return null
            val ms = raw.trim().toLongOrNull()?.let { it * 1000 }
                ?: response.headers.getDate("Retry-After")?.let { it.time - System.currentTimeMillis() }
                ?: return null
            return ms.coerceIn(0, MAX_RETRY_AFTER_MS)
        }
    }
}

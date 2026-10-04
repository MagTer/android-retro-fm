package com.retrofm.android.telemetry

import android.util.Log
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.logs.Severity
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import timber.log.Timber

/**
 * Carries Timber calls into OpenTelemetry logs. Planted beside a DebugTree; call sites keep
 * using plain Timber.
 *
 * **Why a Tree and not opentelemetry-android's `android-log` instrumentation.** That one
 * rewrites every `android.util.Log` call in the APK, libraries included — Media3, OkHttp, Play
 * services — none of which were written to this app's wire contract. ExoPlayer, for one, logs
 * data-source URIs, and the stream's edge URL carries a token (`rj-ttl`, `rj-tok`). A Tree ships
 * exactly the lines this app chose to write, every one of them reviewable in this repo.
 *
 * Log hygiene is part of the wire contract: no tokens, no URLs with credentials, no PII. That is
 * enforced at call sites; [redact] is the backstop, not the rule.
 *
 * Every record carries the process's [sessionId] (`session.id`, replacing the old client's
 * `sid`) and a per-process [seq]. OTel has no sequence or gap signal of its own — the SDK's batch
 * processor drops silently when its queue is full — so a hole in `seq` within one `session.id`
 * is the only way the store can tell "records were lost" from "nothing was logged". Note one
 * benign source of holes: records made before the edge's level is known are judged at send
 * time, and the ones below it are discarded then.
 *
 * Both are record attributes, never resource attributes: the edge counts metric *series*, and
 * a per-process resource attribute would mint a new set of series every start.
 */
class TelemetryTree(
    private val logger: Logger,
    private val gate: SeverityGate,
    private val sessionId: String,
) : Timber.Tree() {

    private val seq = AtomicLong(0L)

    /** Lets Timber skip formatting entirely for a line that could never be sent. */
    override fun isLoggable(tag: String?, priority: Int): Boolean =
        gate.admitsAtEmit(severityOf(priority).severityNumber)

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        val severity = severityOf(priority)
        // Timber has already appended the stack trace to [message] when [t] is set.
        val body = redact(message).let {
            if (it.length <= MAX_BODY_CHARS) it else it.take(MAX_BODY_CHARS) + "…[truncated]"
        }
        val attributes = Attributes.builder()
            .put(SESSION_ID, sessionId)
            .put(SEQ, seq.incrementAndGet())
            .apply {
                if (tag != null) put(TAG, tag)
                if (t != null) put(EXCEPTION_TYPE, t.javaClass.name)
            }
            .build()
        logger.logRecordBuilder()
            .setTimestamp(System.currentTimeMillis(), TimeUnit.MILLISECONDS)
            .setSeverity(severity)
            .setSeverityText(severity.name)
            .setBody(body)
            .setAllAttributes(attributes)
            .emit()
    }

    companion object {
        val SESSION_ID: AttributeKey<String> = AttributeKey.stringKey("session.id")
        val SEQ: AttributeKey<Long> = AttributeKey.longKey("seq")
        val TAG: AttributeKey<String> = AttributeKey.stringKey("tag")
        val EXCEPTION_TYPE: AttributeKey<String> = AttributeKey.stringKey("exception.type")

        /** The old client's `maxLineBytes`: a record larger than a body limit cannot ship. */
        const val MAX_BODY_CHARS = 8 * 1024

        fun severityOf(priority: Int): Severity = when {
            priority >= Log.ASSERT -> Severity.FATAL
            priority >= Log.ERROR -> Severity.ERROR
            priority == Log.WARN -> Severity.WARN
            priority == Log.INFO -> Severity.INFO
            else -> Severity.DEBUG
        }

        // A URL's query and fragment, up to the next whitespace or closing bracket/quote.
        private val URL_QUERY = Regex("""(https?://[^\s?#"'<>)\]]+)[?#][^\s"'<>)\]]*""")

        // user:password@ in a URL's authority.
        private val URL_USERINFO = Regex("""(https?://)[^\s/@"'<>]+@""")

        /**
         * Strips the parts of a URL that carry credentials — the query (where this stream's CDN
         * puts its `rj-tok` token), the fragment and any userinfo — wherever a URL appears in a
         * message, stack traces included (an IOException message is the usual carrier).
         */
        fun redact(text: String): String {
            if (!text.contains("://")) return text
            return URL_USERINFO.replace(URL_QUERY.replace(text, "$1?…"), "$1…@")
        }
    }
}

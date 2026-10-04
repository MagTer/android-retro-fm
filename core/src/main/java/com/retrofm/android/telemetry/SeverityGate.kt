package com.retrofm.android.telemetry

/**
 * The remote log level, applied on the device so a below-level record is never sent.
 *
 * The level comes from the edge's `GET /v1/config` (the operator raises it to DEBUG at the
 * ingest host's /admin during an investigation; the steady state is WARN). The floors are the
 * edge's own (OTel severity numbers): DEBUG 5, INFO 9, WARN 13, ERROR 17, and an unspecified
 * severity (0) counts as INFO — the same rule the edge drops by, so the two cannot disagree
 * about a record.
 *
 * Two questions, deliberately different before the first answer:
 * - [admitsAtEmit] — should a log call be *recorded* at all? Before the edge has answered it
 *   says yes to everything, because the edge may well be at DEBUG for exactly this start (an
 *   offline car boot is the session an investigation wants), and dropping at the call site
 *   would lose those lines for good. The buffer is bounded either way.
 * - [admitsForSend] — may it leave the device? Never before the level is known. A record
 *   recorded under "unknown" waits in the buffer (or the spool) and is judged when the answer
 *   arrives, so "below level is never sent" holds without guessing a level.
 */
class SeverityGate {
    @Volatile
    private var floor: Int = UNKNOWN

    val known: Boolean get() = floor != UNKNOWN

    /** The level name currently applied, or null before the edge has answered. */
    @Volatile
    var levelName: String? = null
        private set

    /**
     * Applies a level name from the edge. Returns false (and keeps the current level) for a
     * name this client does not know — an unreadable answer is not a reason to change.
     */
    fun apply(level: String?): Boolean {
        val name = level?.trim()?.uppercase() ?: return false
        val f = FLOORS[name] ?: return false
        floor = f
        levelName = name
        return true
    }

    fun admitsAtEmit(severity: Int): Boolean = !known || effective(severity) >= floor

    fun admitsForSend(severity: Int): Boolean = known && effective(severity) >= floor

    private fun effective(severity: Int): Int = if (severity <= 0) INFO else severity

    companion object {
        private const val UNKNOWN = Int.MAX_VALUE
        const val DEBUG = 5
        const val INFO = 9
        const val WARN = 13
        const val ERROR = 17

        val FLOORS = mapOf("DEBUG" to DEBUG, "INFO" to INFO, "WARN" to WARN, "ERROR" to ERROR)
    }
}

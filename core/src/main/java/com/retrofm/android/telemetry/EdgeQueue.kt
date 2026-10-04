package com.retrofm.android.telemetry

import java.util.ArrayDeque

/**
 * The records of one signal (logs or metrics) that have been produced but not yet accepted by
 * the edge, and the rules for sending them. Everything the old logsink client learned about the
 * path lives here, now in terms of OTLP records instead of NDJSON lines:
 *
 *  - **bounded, drop-oldest** — never unbounded on a device; the overflow is counted and handed
 *    to the caller ([takeDropped]) so it can be logged as a record of its own, and a gap in the
 *    store is distinguishable from an app that logged nothing
 *  - **a failed batch goes back to the front**, so order survives a retry
 *  - **429 honours Retry-After; 5xx and transport failures back off exponentially** (capped);
 *    [drain] with `ignoreBackoff` skips a backoff grown while offline, for the moment
 *    connectivity is known to be back
 *  - **413 halves the batch and retries; never the same bytes twice.** One record that still
 *    does not fit is dropped, because one unshippable record must not block the session
 *    behind it — the retried-forever 413 is how the old client once went silent for 40 min
 *  - **an auth refusal (401, Access's redirect) drops the batch and backs off at the cap**: a
 *    wrong key cannot be retried into working, and asking every flush only adds noise
 *
 * Not thread-safe on its own: [add] may come from any thread (it takes the lock), but [drain]
 * and the spool calls must all run on the one telemetry thread.
 */
internal class EdgeQueue<T : Any>(
    private val send: (ByteArray) -> EdgeResult,
    private val marshal: (List<T>) -> ByteArray,
    private val maxItems: Int,
    private val maxBatchItems: Int,
    /** Raw (pre-gzip) protobuf budget for one POST. The edge takes ≤ 8 MiB inflated and ≤ 1 MiB
     *  on the wire; staying far below both leaves the 413 path for proxies we do not know of. */
    private val maxBatchBytes: Int,
    private val baseBackoffMs: Long,
    private val maxBackoffMs: Long,
    private val now: () -> Long,
) {
    private class Entry<T>(val item: T, var spooled: Boolean)

    private val lock = Any()
    private val entries = ArrayDeque<Entry<T>>()
    private var dropped = 0L

    private var backoffMs = 0L
    private var backoffUntilMs = 0L
    private var batchCap = maxBatchItems

    /** The last non-OK answer, for the caller's logcat line and for tests. */
    @Volatile
    var lastProblem: EdgeResult? = null
        private set

    val size: Int get() = synchronized(lock) { entries.size }

    fun add(items: Collection<T>, spooled: Boolean = false) {
        if (items.isEmpty()) return
        synchronized(lock) {
            items.forEach { entries.addLast(Entry(it, spooled)) }
            trimLocked()
        }
    }

    /** Restores records ahead of everything queued — a previous process's spool. */
    fun addFirst(items: List<T>) {
        if (items.isEmpty()) return
        synchronized(lock) {
            items.asReversed().forEach { entries.addFirst(Entry(it, false)) }
            trimLocked()
        }
    }

    private fun trimLocked() {
        while (entries.size > maxItems) {
            entries.pollFirst()
            dropped++
        }
    }

    /** Records lost to drop-oldest since the last call. */
    fun takeDropped(): Long = synchronized(lock) { dropped.also { dropped = 0 } }

    fun backingOff(): Boolean = now() < backoffUntilMs

    /**
     * Sends until the queue is empty or the edge says stop. [admit] filters at send time (the
     * log level); a record it refuses is discarded, not kept, since the level is the edge's
     * decision and it has been made. Returns true when the queue ended empty.
     */
    fun drain(admit: (T) -> Boolean = { true }, ignoreBackoff: Boolean = false): Boolean {
        if (ignoreBackoff) {
            backoffMs = 0
            backoffUntilMs = 0
        }
        if (backingOff()) return false
        while (true) {
            val batch = takeBatch(admit) ?: return true
            if (batch.isEmpty()) continue
            val body = marshal(batch.map { it.item })
            if (body.size > maxBatchBytes && batch.size > 1) {
                // Known too large before asking anyone: split locally, same as a 413.
                putBack(batch)
                batchCap = maxOf(1, batch.size / 2)
                continue
            }
            when (val result = send(body)) {
                EdgeResult.Ok -> {
                    backoffMs = 0
                    batchCap = maxBatchItems
                    lastProblem = null
                }
                is EdgeResult.Retry -> {
                    putBack(batch)
                    lastProblem = result
                    backoffMs = result.afterMs?.takeIf { it > 0 }
                        ?: minOf(if (backoffMs == 0L) baseBackoffMs else backoffMs * 2, maxBackoffMs)
                    backoffUntilMs = now() + backoffMs
                    return false
                }
                EdgeResult.TooLarge -> {
                    lastProblem = result
                    if (batch.size > 1) {
                        putBack(batch)
                        batchCap = maxOf(1, batch.size / 2)
                    } else {
                        synchronized(lock) { dropped++ }
                    }
                }
                is EdgeResult.Drop -> {
                    lastProblem = result
                    if (result.auth) {
                        backoffMs = maxBackoffMs
                        backoffUntilMs = now() + backoffMs
                        return false
                    }
                }
            }
        }
    }

    /** Null when nothing is left; an empty list when everything taken was filtered out. */
    private fun takeBatch(admit: (T) -> Boolean): List<Entry<T>>? = synchronized(lock) {
        if (entries.isEmpty()) return null
        val out = ArrayList<Entry<T>>(minOf(batchCap, entries.size))
        while (out.size < batchCap) {
            val e = entries.pollFirst() ?: break
            if (admit(e.item)) out.add(e)
        }
        out
    }

    private fun putBack(batch: List<Entry<T>>) = synchronized(lock) {
        batch.asReversed().forEach { entries.addFirst(it) }
        trimLocked()
    }

    /** Records not yet written to the spool, marked as written. The caller writes them. */
    fun takeUnspooled(): List<T> = synchronized(lock) {
        entries.filter { !it.spooled }.onEach { it.spooled = true }.map { it.item }
    }

    /** Undo [takeUnspooled] when the write failed, so a later write can still carry them. */
    fun markUnspooled(items: Collection<T>) = synchronized(lock) {
        val set = items.toHashSet()
        entries.forEach { if (it.item in set) it.spooled = false }
    }

    fun isEmpty(): Boolean = synchronized(lock) { entries.isEmpty() }
}

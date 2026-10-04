package com.retrofm.android.telemetry

import android.util.Log
import io.opentelemetry.contrib.disk.buffering.storage.impl.FileLogRecordStorage
import io.opentelemetry.contrib.disk.buffering.storage.impl.FileStorageConfiguration
import io.opentelemetry.sdk.logs.data.LogRecordData
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Log records that a dying or offline process would otherwise lose — a last resort, **not** a
 * mirror. The car's modem drops repeatedly mid-drive and the process can be killed at park while
 * still offline; without this the tail of such a drive never reaches the store.
 *
 * Storage is opentelemetry-disk-buffering's [FileLogRecordStorage]; what this class adds is
 * *when* it is touched. The library's own wiring in opentelemetry-android (core 1.7.0-alpha,
 * read 2026-10-04) does two things this app cannot accept:
 *  - it puts `LogRecordToDiskExporter` in front of **every** export, so every batch is written
 *    before it is sent and a normal online drive writes continuously. The head unit's flash is
 *    the reason the rule here has been "an online drive writes nothing" since 1.0.28;
 *  - its reader iterates with `deleteItemsOnIteration = true` (the default) through a cached
 *    iterator and stops on the first failed export — the failed batch is deleted on the next
 *    pass, i.e. lost in exactly the offline case disk buffering exists for.
 *
 * So, as the old client's spool did:
 *  - nothing touches disk on the logging path, and an online session writes nothing: [write]
 *    is called only after a send has failed (rate-limited by the caller) and at teardown;
 *  - a write carries only records not written before, so an idle offline stretch costs nothing;
 *  - [replay] runs once per process, off the main thread, and removes each record from disk as
 *    it is taken into memory — no outcome of a replay can leave a backlog that grows across
 *    boots (the failure that killed `DiskLogTree` in 1.0.28: one line per boot, then silence);
 *  - the folder is capped by the library (oldest file evicted) and files expire after
 *    [MAX_AGE_MS];
 *  - **any I/O failure disables the spool for the process.** It must never be able to take
 *    logging down with it. Kill switch: `RetroFmConfig.LOG_SPOOL_ENABLED`.
 *
 * Known cost, same as the old spool: records written, then shipped by a later flush, stay on
 * disk until the queue empties ([clearIfWritten]); a process killed in between replays them, so
 * the store can hold a duplicate. Better than a hole.
 *
 * Telemetry-thread only. Never logs through Timber (it would loop); logcat only, sparsely.
 */
internal class LogSpool(private val dir: File, private val maxBytes: Int) {
    private var storage: FileLogRecordStorage? = null
    private var disabled = false
    private var written = false

    private fun open(): FileLogRecordStorage? {
        if (disabled) return null
        storage?.let { return it }
        return runCatching {
            FileLogRecordStorage.create(
                dir,
                FileStorageConfiguration.builder()
                    .setMaxFolderSize(maxBytes)
                    .setMaxFileSize(maxOf(4 * 1024, maxBytes / 4))
                    .setMaxFileAgeForReadMillis(MAX_AGE_MS)
                    // Ours to delete: a record leaves the disk only once it is in memory.
                    .setDeleteItemsOnIteration(false)
                    .build()
            )
        }.onFailure { disable("open", it) }.getOrNull()?.also { storage = it }
    }

    /** Writes [records]; false when nothing could be written (the spool is then disabled). */
    fun write(records: List<LogRecordData>): Boolean {
        if (records.isEmpty()) return true
        val s = open() ?: return false
        val result = s.write(records).join(WRITE_TIMEOUT_S, TimeUnit.SECONDS)
        if (!result.isSuccess) {
            disable("write", result.failureThrowable)
            return false
        }
        written = true
        return true
    }

    /** Every record a previous process left, oldest first, each removed from disk as read. */
    fun replay(): List<LogRecordData> {
        val s = open() ?: return emptyList()
        val out = ArrayList<LogRecordData>()
        runCatching {
            val it = s.iterator()
            while (it.hasNext()) {
                val batch = it.next() ?: break
                out.addAll(batch)
                it.remove()
            }
        }.onFailure { disable("replay", it) }
        return out
    }

    /** Deletes the spool once everything it held has shipped. A delete, never a write. */
    fun clearIfWritten() {
        if (!written || disabled) return
        val s = storage ?: return
        val result = s.clear().join(WRITE_TIMEOUT_S, TimeUnit.SECONDS)
        if (result.isSuccess) written = false else disable("clear", result.failureThrowable)
    }

    private fun disable(what: String, cause: Throwable?) {
        disabled = true
        runCatching { storage?.close() }
        storage = null
        Log.w(TAG, "spool $what failed — disabled for this process, logging continues", cause)
    }

    companion object {
        private const val TAG = "TelemetrySpool"
        private const val WRITE_TIMEOUT_S = 5L

        /** Older than a week is past use: the drive it describes is long diagnosed or forgotten. */
        val MAX_AGE_MS = TimeUnit.DAYS.toMillis(7)
    }
}

package com.retrofm.android

import timber.log.Timber
import java.io.File

/**
 * Mirrors every log line to a local spool file so logs survive a process death that happens
 * before the in-memory sink buffer can reach the network. That gap made the crucial case
 * invisible: a car that boots without internet, logs a few lines, then has its process killed
 * (or crashes) before the buffer can flush — those lines died with the process, so the sink
 * only ever received the boots that happened to have connectivity (survivorship bias).
 *
 * The spool is read and replayed on the next start (tagged "Replay"), then the current session
 * appends fresh. A graceful ON_STOP clears the spool after a flush, so only abnormal
 * terminations (the ones we could never see) get replayed. Replayed lines are not re-spooled.
 */
class DiskLogTree(private val spool: File) : Timber.Tree() {
    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        if (tag == REPLAY_TAG) return
        runCatching {
            spool.appendText("$priority|${tag ?: ""}|$message\n")
            if (spool.length() > MAX_BYTES) {
                spool.writeText(spool.readText().takeLast(MAX_BYTES))
            }
        }
    }

    companion object {
        const val REPLAY_TAG = "Replay"
        private const val MAX_BYTES = 256 * 1024
    }
}

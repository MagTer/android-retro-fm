package com.retrofm.android

import android.app.Application
import android.os.Build
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.core.content.pm.PackageInfoCompat
import com.retrofm.android.core.BuildConfig
import java.io.File
import com.retrofm.android.data.config.RetroFmConfig
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import se.falle.logsink.LogsinkClient
import se.falle.logsink.LogsinkTree
import timber.log.Timber

/**
 * Shared Application for both the phone (:app) and Automotive (:automotive) builds — this is
 * where the log pipeline is planted. It matters most for the car: production head units allow
 * no adb, so the remote sink is the only diagnostic channel there (ADR-011, home-server repo).
 *
 * Log hygiene is part of the wire contract: once lines leave the device — no tokens, no URLs
 * with credentials, no PII.
 */
class RetroFmApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        Timber.plant(Timber.DebugTree())

        val key = BuildConfig.LOGSINK_KEY
        if (key.isNotBlank()) {
            // Disk spool: mirror logs locally so a boot that dies before its in-memory buffer
            // can flush is not lost (see DiskLogTree) — the no-internet-at-boot restarts were
            // invisible for exactly this reason. Read what the previous session left behind
            // (only present after an abnormal termination), then start the spool fresh.
            val spool = File(filesDir, "logsink-spool.txt")
            val previousBoot = runCatching {
                if (spool.exists()) spool.readText() else ""
            }.getOrDefault("")
            runCatching { spool.writeText("") }
            Timber.plant(DiskLogTree(spool))

            val client = LogsinkClient(
                ingestUrl = RetroFmConfig.LOGSINK_INGEST_URL,
                apiKey = key,
                // Tells the phone's lines apart from the car's in the sink.
                device = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
            )
            Timber.plant(LogsinkTree(client))
            // Replay the previous (abnormally terminated) session's spooled lines — this is how
            // the never-before-seen no-internet boots finally reach the sink.
            if (previousBoot.isNotBlank()) {
                Timber.tag(DiskLogTree.REPLAY_TAG).w(
                    "---- previous boot spool (%d bytes) ----", previousBoot.length
                )
                previousBoot.lineSequence().filter { it.isNotBlank() }.forEach {
                    Timber.tag(DiskLogTree.REPLAY_TAG).i(it)
                }
            }
            // Marks every process (re)start with the actually-installed version — so a version
            // that changes across a restart proves Play applied an update at that moment (the
            // suspected cause of the launcher's "Something went wrong / update Google Play"
            // dialog: a stale resumed process meeting a freshly-swapped APK).
            runCatching {
                val pi = packageManager.getPackageInfo(packageName, 0)
                Timber.tag("Lifecycle").i(
                    "app process start vc=%d vn=%s",
                    PackageInfoCompat.getLongVersionCode(pi), pi.versionName
                )
            }
            // Ship crashes before dying: field evidence (Volvo 2026-07-23) showed silent
            // process-restart loops — the crash itself never reached the sink because the
            // buffer dies with the process. Log at ERROR, force a bounded flush, then hand
            // over to the platform handler so normal crash semantics are preserved.
            val platformHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                runCatching {
                    Timber.tag("Crash").e(throwable, "uncaught on thread %s", thread.name)
                    runBlocking { withTimeout(2_000L) { client.flush() } }
                }
                platformHandler?.uncaughtException(thread, throwable)
            }
            // Flush buffered lines whenever the app leaves the foreground.
            ProcessLifecycleOwner.get().lifecycle.addObserver(
                LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_STOP) {
                        ProcessLifecycleOwner.get().lifecycleScope.launch {
                            client.flush()
                            // Graceful background: logs shipped, so drop the spool. Only abnormal
                            // deaths (which never reach ON_STOP) leave a spool to replay.
                            runCatching { spool.writeText("") }
                        }
                    }
                }
            )
        }
    }
}

package com.retrofm.android

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
            // Disk spool mirrors logs locally so a boot that dies before its in-memory buffer
            // can flush is not lost — the no-internet-at-boot restarts were invisible for exactly
            // that reason. Two files: `spool` = the current session's lines; `pending` = the
            // accumulated lines of previous sessions that never confirmed shipping. At startup we
            // FOLD the previous session's spool into pending (never discard it — an earlier bug
            // truncated on read, so a chain of offline boots ate each other), then replay pending.
            // pending is deleted only on a graceful ON_STOP after a flush (confirmed delivery).
            val spool = File(filesDir, "logsink-spool.txt")
            val pending = File(filesDir, "logsink-pending.txt")
            runCatching {
                val prev = if (spool.exists()) spool.readText() else ""
                if (prev.isNotBlank()) {
                    pending.appendText(prev)
                    spool.writeText("")
                    if (pending.length() > MAX_SPOOL_BYTES) {
                        pending.writeText(pending.readText().takeLast(MAX_SPOOL_BYTES))
                    }
                }
            }
            Timber.plant(DiskLogTree(spool))

            val client = LogsinkClient(
                ingestUrl = RetroFmConfig.LOGSINK_INGEST_URL,
                apiKey = key,
                // Tells the phone's lines apart from the car's in the sink.
                device = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
            )
            Timber.plant(LogsinkTree(client))
            // Replay everything that never confirmed shipping — this is how the never-before-seen
            // no-internet boots finally reach the sink.
            val pendingText = runCatching {
                if (pending.exists()) pending.readText() else ""
            }.getOrDefault("")
            if (pendingText.isNotBlank()) {
                Timber.tag(DiskLogTree.REPLAY_TAG).w(
                    "---- replaying %d bytes of unshipped boots ----", pendingText.length
                )
                pendingText.lineSequence().filter { it.isNotBlank() }.forEach {
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
            // Network state in the FIRST lines logged, so it rides the earliest buffer flush —
            // proven to escape even a boot that dies almost immediately (that is all vc1110 ever
            // shipped). Answers the load-bearing question directly: did the car have real
            // internet the instant it launched, when the "Something went wrong" dialog appears?
            runCatching {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
                Timber.tag("Network").i(
                    "boot connectivity: activeNetwork=%b internet=%b validated=%b",
                    cm.activeNetwork != null,
                    caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
                    caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
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
                            // Graceful background after a flush = confirmed delivery: drop both the
                            // current spool and the replayed pending backlog. Only abnormal deaths
                            // (which never reach ON_STOP) keep data for the next boot to replay.
                            runCatching { spool.writeText("") }
                            runCatching { if (pending.exists()) pending.delete() }
                        }
                    }
                }
            )
        }
    }

    private companion object {
        // Cap the offline backlog so a long connectivity outage can't grow the spool without
        // bound; keep the most recent lines (drop-oldest).
        const val MAX_SPOOL_BYTES = 256 * 1024
    }
}

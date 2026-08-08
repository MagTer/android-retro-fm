package com.retrofm.android

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.retrofm.android.core.BuildConfig
import com.retrofm.android.data.config.RetroFmConfig
import java.io.File
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
 *
 * Durable spool: a first attempt (DiskLogTree, 1.0.28) was reverted — it mirrored every line to
 * disk synchronously on the logging thread and replayed a growing backlog on the main thread in
 * onCreate, and the car went from full logs to one line per boot and then silence. The spool
 * used here is a different animal, and lives in the vendored client rather than in a Timber
 * tree: nothing touches disk on the logging path, writes happen only after a flush has already
 * failed (rate-limited, size-capped), replay runs off the main thread with the file deleted
 * first, and any I/O failure disables the spool instead of retrying. Kill switch:
 * [RetroFmConfig.LOG_SPOOL_ENABLED].
 */
class RetroFmApplication : Application() {

    /**
     * The process-wide log shipper; null when no key is configured (local dev builds).
     * Exposed so the playback service can flush at end-of-drive moments — on AAOS this app
     * has no activity, so the ON_STOP hook below never fires there and the service's
     * lifecycle is the only "we are about to go away" signal.
     */
    var logsinkClient: LogsinkClient? = null
        private set

    override fun onCreate() {
        super.onCreate()
        Timber.plant(Timber.DebugTree())

        val key = BuildConfig.LOGSINK_KEY
        if (key.isNotBlank()) {
            val client = LogsinkClient(
                ingestUrl = RetroFmConfig.LOGSINK_INGEST_URL,
                apiKey = key,
                // Tells the phone's lines apart from the car's in the sink.
                device = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
                // Survives the car being parked while offline — see RetroFmConfig for the
                // write budget and the kill switch. Null disables it entirely in the client.
                spoolFile = if (RetroFmConfig.LOG_SPOOL_ENABLED) {
                    File(filesDir, RetroFmConfig.LOG_SPOOL_FILE_NAME)
                } else {
                    null
                },
                spoolMaxBytes = RetroFmConfig.LOG_SPOOL_MAX_BYTES,
                spoolMinWriteIntervalMs = RetroFmConfig.LOG_SPOOL_MIN_WRITE_INTERVAL_MS,
                spoolMaxReplayLines = RetroFmConfig.LOG_SPOOL_MAX_REPLAY_LINES
            )
            logsinkClient = client
            Timber.plant(LogsinkTree(client))

            // Restore a previous process's unshipped lines. Off the main thread on purpose:
            // the previous spool attempt did this work inside onCreate and ANR'd the boot.
            ProcessLifecycleOwner.get().lifecycleScope.launch { client.replaySpool() }

            // Marks every process (re)start with the actually-installed version.
            runCatching {
                val pi = packageManager.getPackageInfo(packageName, 0)
                Timber.tag("Lifecycle").i(
                    "app process start vc=%d vn=%s",
                    PackageInfoCompat.getLongVersionCode(pi), pi.versionName
                )
            }
            // Connectivity at process start — logged early so it rides the first flush.
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
            // Ship crashes before dying: log at ERROR, force a bounded flush, then hand over to
            // the platform handler so normal crash semantics are preserved.
            val platformHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                runCatching {
                    Timber.tag("Crash").e(throwable, "uncaught on thread %s", thread.name)
                    runBlocking {
                        withTimeout(2_000L) { client.flush() }
                        // Whatever the network refused now lives on disk instead of dying
                        // with the process — a crash offline was previously invisible.
                        withTimeout(1_000L) { client.persistNow() }
                    }
                }
                platformHandler?.uncaughtException(thread, throwable)
            }
            // Flush buffered lines whenever the app leaves the foreground.
            ProcessLifecycleOwner.get().lifecycle.addObserver(
                LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_STOP) {
                        ProcessLifecycleOwner.get().lifecycleScope.launch {
                            client.flush()
                            client.persistNow()
                        }
                    }
                }
            )
            // Flush past the backoff the moment internet actually works. The car's modem
            // validates minutes after process start; by then the client's exponential
            // backoff (grown on the failed early flushes) could sleep through a short
            // online window — the same failure mode the stream reconnect had before it
            // was keyed to NET_CAPABILITY_VALIDATED. Never unregistered: process-lifetime.
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                @Volatile
                private var wasValidated = false
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    if (validated && !wasValidated) {
                        ProcessLifecycleOwner.get().lifecycleScope.launch { client.flushNow() }
                    }
                    wasValidated = validated
                }

                override fun onLost(network: Network) {
                    wasValidated = false
                }
            })
        }
    }
}

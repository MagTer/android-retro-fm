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
import com.retrofm.android.telemetry.Telemetry
import com.retrofm.android.telemetry.TelemetryLimits
import com.retrofm.android.telemetry.TelemetrySettings
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.sdk.resources.Resource
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Shared Application for both the phone (:app) and Automotive (:automotive) builds — this is
 * where the telemetry pipeline is planted. It matters most for the car: production head units
 * allow no adb, so remote telemetry is the only diagnostic channel there (home-server
 * TELEMETRY-DESIGN §5; ADR-011 before it).
 *
 * Log hygiene is part of the wire contract: once records leave the device — no tokens, no URLs
 * with credentials, no PII. Every Timber call site reaches OpenTelemetry through
 * [TelemetryTree], so the contract covers all of them.
 *
 * Durable spool: a first attempt (DiskLogTree, 1.0.28) was reverted — it mirrored every line to
 * disk synchronously on the logging thread and replayed a growing backlog on the main thread in
 * onCreate, and the car went from full logs to one line per boot and then silence. The spool
 * used now ([com.retrofm.android.telemetry.LogSpool]) is a different animal: nothing touches
 * disk on the logging path, writes happen only after a flush has already failed (rate-limited,
 * size-capped) and at teardown, replay runs on the telemetry thread with each record removed
 * from disk as it is taken, and any I/O failure disables the spool instead of retrying. Kill
 * switch: [RetroFmConfig.LOG_SPOOL_ENABLED].
 */
class RetroFmApplication : Application() {

    /**
     * The process-wide telemetry pipeline; null when the build carries no URL or key (local dev
     * builds), in which case nothing is sent and nothing is written. Exposed so the playback
     * service can flush at end-of-drive moments — on AAOS this app has no activity, so the
     * ON_STOP hook below never fires there and the service's lifecycle is the only "we are
     * about to go away" signal — and so the player can record its metrics.
     */
    var telemetry: Telemetry? = null
        private set

    override fun onCreate() {
        super.onCreate()
        Timber.plant(Timber.DebugTree())

        val versionName = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }
            .getOrNull()
        val telemetry = Telemetry.start(
            settings = TelemetrySettings(
                url = BuildConfig.TELEMETRY_URL,
                key = BuildConfig.TELEMETRY_KEY,
                accessClientId = BuildConfig.TELEMETRY_CF_ID,
                accessClientSecret = BuildConfig.TELEMETRY_CF_SECRET,
            ),
            // service.name is overwritten by the edge from the key; it is here for anything
            // that reads the record before the edge does. Only attributes that are the same for
            // the whole life of an install — see TelemetryTree on why session.id is not here.
            resource = Resource.getDefault().merge(
                Resource.create(
                    Attributes.builder()
                        .put("service.name", "retro-fm")
                        .put("service.version", versionName ?: "unknown")
                        // Tells the phone's records apart from the car's.
                        .put("device.manufacturer", Build.MANUFACTURER)
                        .put("device.model.name", Build.MODEL)
                        .build()
                )
            ),
            limits = TelemetryLimits(
                spoolEnabled = RetroFmConfig.LOG_SPOOL_ENABLED,
                spoolMaxBytes = RetroFmConfig.LOG_SPOOL_MAX_BYTES,
                spoolMinWriteIntervalMs = RetroFmConfig.LOG_SPOOL_MIN_WRITE_INTERVAL_MS,
                spoolMaxReplayRecords = RetroFmConfig.LOG_SPOOL_MAX_REPLAY_LINES,
            ),
            spoolDir = File(filesDir, RetroFmConfig.LOG_SPOOL_DIR_NAME),
        ) ?: return
        this.telemetry = telemetry
        Timber.plant(telemetry.tree)

        // The pre-OpenTelemetry spool, if a previous version left one. Off the main thread,
        // like every other file operation here.
        ProcessLifecycleOwner.get().lifecycleScope.launch(Dispatchers.IO) {
            runCatching { File(filesDir, RetroFmConfig.LEGACY_SPOOL_FILE_NAME).delete() }
        }

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
        // Ship crashes before dying: log at ERROR, a bounded flush, whatever the network
        // refused to the spool (a crash offline was invisible before the spool), then hand over
        // to the platform handler so normal crash semantics are preserved.
        val platformHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                Timber.tag("Crash").e(throwable, "uncaught on thread %s", thread.name)
                telemetry.flushAndPersistBlocking(CRASH_FLUSH_BUDGET_MS)
            }
            platformHandler?.uncaughtException(thread, throwable)
        }
        // Flush buffered records whenever the app leaves the foreground.
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) {
                    ProcessLifecycleOwner.get().lifecycleScope.launch {
                        telemetry.flush()
                        telemetry.persistNow()
                    }
                }
            }
        )
        // Flush past the backoff the moment internet actually works. The car's modem
        // validates minutes after process start; by then the backoff (grown on the failed
        // early flushes) could sleep through a short online window — the same failure mode
        // the stream reconnect had before it was keyed to NET_CAPABILITY_VALIDATED. Never
        // unregistered: process-lifetime.
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            @Volatile
            private var wasValidated = false
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                if (validated && !wasValidated) {
                    ProcessLifecycleOwner.get().lifecycleScope.launch { telemetry.flushNow() }
                }
                wasValidated = validated
            }

            override fun onLost(network: Network) {
                wasValidated = false
            }
        })
    }

    private companion object {
        /** Flush plus persist, in all, before the platform crash handler runs. */
        const val CRASH_FLUSH_BUDGET_MS = 3_000L
    }
}

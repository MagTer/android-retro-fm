package com.retrofm.android

import android.app.Application
import android.os.Build
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.retrofm.android.core.BuildConfig
import com.retrofm.android.data.config.RetroFmConfig
import kotlinx.coroutines.launch
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
            val client = LogsinkClient(
                ingestUrl = RetroFmConfig.LOGSINK_INGEST_URL,
                apiKey = key,
                // Tells the phone's lines apart from the car's in the sink.
                device = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
            )
            Timber.plant(LogsinkTree(client))
            // Flush buffered lines whenever the app leaves the foreground.
            ProcessLifecycleOwner.get().lifecycle.addObserver(
                LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_STOP) {
                        ProcessLifecycleOwner.get().lifecycleScope.launch { client.flush() }
                    }
                }
            )
        }
    }
}

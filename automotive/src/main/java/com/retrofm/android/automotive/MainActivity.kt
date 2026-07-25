package com.retrofm.android.automotive

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView

/**
 * Minimal launcher entry point for the car. Retro FM is a media app — playback and browsing
 * live in the system media template — so this screen exists only to give the car launcher a
 * valid MAIN/LAUNCHER activity. Field logs proved its absence (launcher activity present=false)
 * was the last structural difference from apps that launch cleanly: the Volvo launcher could
 * connect to our media session but had no entry point to "open the app", so at boot it showed
 * "Something went wrong / check that Google Play is enabled" even though internet was validated
 * and the media service worked. Kept to a single centered label so it is distraction-optimized.
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            TextView(this).apply {
                text = "Retro FM"
                textSize = 34f
                gravity = Gravity.CENTER
                setBackgroundColor(Color.parseColor("#101014"))
                setTextColor(Color.WHITE)
            }
        )
    }
}

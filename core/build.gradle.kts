plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.retrofm.android.core"
    compileSdk = 36

    defaultConfig {
        minSdk = 26

        // Log-sink append key (ADR-011 in the home-server repo). A rate-limit/revocation
        // handle rather than a secret, but still never in source: comes from
        // ~/.gradle/gradle.properties (RETROFM_LOGSINK_KEY) or -P. Blank = remote logging off.
        buildConfigField(
            "String",
            "LOGSINK_KEY",
            "\"${project.findProperty("RETROFM_LOGSINK_KEY") ?: ""}\""
        )

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")

    implementation("androidx.media3:media3-exoplayer:1.10.1")
    implementation("androidx.media3:media3-session:1.10.1")
    // Unified local+remote player (CastPlayer.Builder). Cast is only *activated* by the
    // OPTIONS_PROVIDER_CLASS_NAME meta-data in :app; on :automotive CastContext init throws
    // and PlayerManager falls back to plain ExoPlayer (see PlayerManager.player).
    implementation("androidx.media3:media3-cast:1.10.1")

    implementation("com.squareup.retrofit2:retrofit:2.12.0")
    implementation("com.jakewharton.retrofit:retrofit2-kotlinx-serialization-converter:1.0.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // Remote log pipeline (vendored se.falle.logsink + RetroFmApplication). Timber is `api`:
    // call sites in :app log through Timber too.
    api("com.jakewharton.timber:timber:5.0.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-process:2.9.4")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    // Robolectric so MediaItem/Uri (Android framework) can be built in a JVM unit test.
    testImplementation("org.robolectric:robolectric:4.16.1")
}

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.retrofm.android.core"
    compileSdk = 36

    defaultConfig {
        minSdk = 26

        // Telemetry edge (home-server TELEMETRY-DESIGN §4.2, §5): base URL, the source key and
        // the Cloudflare Access service token in front of it. None of them is a secret in the
        // strict sense — they ship in the APK and are extractable; the key is a rate-limit and
        // revocation handle, the token only gets a request past Access — but they are still
        // never in source: ~/.gradle/gradle.properties or -P (CI: the release workflow's
        // TELEMETRY_* secrets). A blank URL or key means telemetry is OFF: no exporter, no
        // request, nothing on disk. The Access pair is sent only when both halves are set.
        listOf(
            "TELEMETRY_URL" to "RETROFM_TELEMETRY_URL",
            "TELEMETRY_KEY" to "RETROFM_TELEMETRY_KEY",
            "TELEMETRY_CF_ID" to "RETROFM_TELEMETRY_CF_ID",
            "TELEMETRY_CF_SECRET" to "RETROFM_TELEMETRY_CF_SECRET",
        ).forEach { (field, property) ->
            buildConfigField("String", field, "\"${project.findProperty(property) ?: ""}\"")
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Applied to :app and :automotive's R8 runs — see the file for what and why.
        consumerProguardFiles("consumer-rules.pro")
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

    // Telemetry (com.retrofm.android.telemetry). Timber is `api`: call sites in :app log
    // through Timber too, and TelemetryTree is what carries them to OpenTelemetry.
    api("com.jakewharton.timber:timber:5.0.1")
    // Pinned to 1.65.0, not the newest SDK: opentelemetry-disk-buffering 1.60.0-alpha is
    // compiled against 1.65.0 and reaches into exporter-otlp-common's *internal* serializers,
    // which carry no compatibility promise across SDK releases. Move the two together.
    implementation(platform("io.opentelemetry:opentelemetry-bom:1.65.0"))
    implementation("io.opentelemetry:opentelemetry-sdk-logs")
    implementation("io.opentelemetry:opentelemetry-sdk-metrics")
    // The OTLP protobuf marshalers (internal API, see EdgeExporter for why the stock
    // OtlpHttp*Exporter cannot be used against the edge).
    implementation("io.opentelemetry:opentelemetry-exporter-otlp-common")
    // File storage only, as a fallback spool — see LogSpool for why not its write-first wiring.
    implementation("io.opentelemetry.contrib:opentelemetry-disk-buffering:1.60.0-alpha")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-process:2.9.4")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    // The canonical OTLP protobuf classes, to decode what the telemetry pipeline sends the way
    // the edge does — independently of the SDK marshalers that produced the bytes.
    testImplementation("io.opentelemetry.proto:opentelemetry-proto:1.11.1-alpha")
    // Robolectric so MediaItem/Uri (Android framework) can be built in a JVM unit test.
    testImplementation("org.robolectric:robolectric:4.16.1")
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.retrofm.android"
    compileSdk = 36

    defaultConfig {
        // Personal namespace, decoupled from the code namespace (com.retrofm.android stays).
        // Permanent once the first AAB is uploaded to Play — must match :automotive.
        applicationId = "com.magter.retrofm"
        minSdk = 26
        // API 36 (Android 16): from 2026-08-31 Google Play requires new apps and updates to
        // target API 36. compileSdk is already 36; edge-to-edge (enforced on 36) is handled by
        // enableEdgeToEdge(), and the mediaPlayback foreground service is unaffected.
        targetSdk = 36
        versionCode = 20
        versionName = "1.0.19"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // Upload signing for Play. Credentials come from Gradle properties (e.g.
        // ~/.gradle/gradle.properties) or env vars, never from the repo. When
        // RETROFM_UPLOAD_STORE_FILE is absent the config stays empty and the release
        // build is produced UNSIGNED — do not generate a keystore or commit secrets here.
        create("release") {
            val storeFilePath = project.findProperty("RETROFM_UPLOAD_STORE_FILE") as String?
            if (storeFilePath != null) {
                storeFile = file(storeFilePath)
                storePassword = project.findProperty("RETROFM_UPLOAD_STORE_PASSWORD") as String?
                keyAlias = project.findProperty("RETROFM_UPLOAD_KEY_ALIAS") as String?
                keyPassword = project.findProperty("RETROFM_UPLOAD_KEY_PASSWORD") as String?
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Sign only when upload credentials are provided; otherwise leave the release
            // unsigned so the build still succeeds for R8/bundle verification.
            if (project.hasProperty("RETROFM_UPLOAD_STORE_FILE")) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // Version-stamped artifact names, e.g. retrofm-1.0.3-vc4-release.aab, so it's always
    // obvious which build is which when uploading to Play.
    base.archivesName.set(
        "retrofm-${defaultConfig.versionName}-vc${defaultConfig.versionCode}"
    )

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":core"))

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.activity:activity-compose:1.10.1")

    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Only MediaController (session client) is needed directly here; the player/session
    // implementation itself lives in :core.
    implementation("androidx.media3:media3-session:1.10.1")

    // Google Cast: the MediaRouteButton (chooser/controller) plus the themed context its
    // dialogs inflate against. play-services-cast-framework arrives transitively via
    // :core's media3-cast. Cast is activated by the manifest meta-data in this module only.
    implementation("androidx.mediarouter:mediarouter:1.8.1")
    implementation("androidx.appcompat:appcompat:1.7.1")
    // CastButtonFactory + CastContext are referenced directly by the phone UI. They live in
    // play-services-cast-framework, which :core pulls only via `implementation` (not exposed
    // across the module boundary), so it must be declared here explicitly. Version matches
    // what media3-cast 1.10.1 depends on.
    implementation("com.google.android.gms:play-services-cast-framework:22.1.0")

    implementation("io.coil-kt.coil3:coil-compose:3.5.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.5.0")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

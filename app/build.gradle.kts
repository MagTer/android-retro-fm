plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.retrofm.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.retrofm.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":core"))

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
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
    implementation("androidx.mediarouter:mediarouter:1.7.0")
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

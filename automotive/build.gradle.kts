plugins {
    id("com.android.application")
}

android {
    namespace = "com.retrofm.android.automotive"
    compileSdk = 36

    defaultConfig {
        // Same applicationId as the phone app: one Play Store listing serves both the
        // mobile/Android Auto APK and this Android Automotive OS APK as separate form factors.
        applicationId = "com.retrofm.android"
        minSdk = 28
        // API 36 to match the phone artifact and Google Play's 2026-08-31 target requirement
        // (AAOS itself only needs API 34, but a shared listing follows the phone target).
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
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
}

dependencies {
    implementation(project(":core"))
}

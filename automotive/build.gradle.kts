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
        targetSdk = 35
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

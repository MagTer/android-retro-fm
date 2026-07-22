plugins {
    id("com.android.application")
}

android {
    namespace = "com.retrofm.android.automotive"
    compileSdk = 36

    defaultConfig {
        // Same applicationId as the phone app: one Play Store listing serves both the
        // mobile/Android Auto APK and this Android Automotive OS APK as separate form factors.
        applicationId = "com.magter.retrofm"
        minSdk = 28
        // API 36 to match the phone artifact and Google Play's 2026-08-31 target requirement
        // (AAOS itself only needs API 34, but a shared listing follows the phone target).
        targetSdk = 36
        // Dedicated 1000+ range: version codes must be unique across every artifact in the
        // listing, so the phone app counts 1, 2, 3, … and automotive 1001, 1002, …
        versionCode = 1001
        versionName = "1.0.7"
    }

    signingConfigs {
        // Same upload key and Gradle properties as :app — one listing, one signing identity.
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
            if (project.hasProperty("RETROFM_UPLOAD_STORE_FILE")) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    base.archivesName.set(
        "retrofm-automotive-${defaultConfig.versionName}-vc${defaultConfig.versionCode}"
    )
}

dependencies {
    implementation(project(":core"))
}

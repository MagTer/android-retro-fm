# Retro FM Android

En dedikerad Android-app för att lyssna på Retro FM utan att gå via aggregerare som lägger på reklam före uppspelning. Appen stödjer både telefoner, Android Auto-projektion och Android Automotive OS (AAOS) via Media3:s `MediaBrowserService`.

## Funktioner

- Reklamfri (aggregator-fri) uppspelning av Retro FM.
- Spela/pausa med stora bilvänliga kontroller.
- Visar nuvarande låttitel och artist med bild.
- Bakgrundsuppspelning med media-notifikation.
- Hanterar mediaknappar (headset, rattkontroller, bil-UI).
- Exponeras som mediekälla i Android Auto och Android Automotive OS.
- Återansluter automatiskt vid nätverksavbrott.

## Teknikstack

- Kotlin 2.2.10
- Jetpack Compose 1.11.4 / Material 3
- AndroidX Media3 1.10.1 (ExoPlayer + MediaSession)
- Retrofit 2.12.0 + Kotlinx Serialization
- Coil 3.5.0
- MVVM + Repository

## Bygga

Projektet kräver JDK 17 och Android SDK (API 36, build-tools 36.0.0).

```bash
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

## Tester

```bash
./gradlew :app:testDebugUnitTest
```

## Installera på enhet

```bash
./gradlew :app:installDebug
adb shell am start -n com.retrofm.android/.ui.MainActivity
```

## Android Auto / Android Automotive OS

Appen registrerar en `MediaBrowserService` och innehåller `automotive_app_desc.xml` så att den dyker upp som mediekälla i Android Auto och AAOS. För att testa i emulator:

1. Starta Android Automotive-emulatorn.
2. Installera appen: `adb install app/build/outputs/apk/debug/app-debug.apk`.
3. Öppna mediaspelaren i bil-UI och välj "Retro FM".

## Konfiguration

Alla ström-URL:er, API-endpoints och stationsidentitet ligger i `app/src/main/java/com/retrofm/android/data/config/RetroFmConfig.kt`.

## Projektstruktur

```
com.retrofm.android
├── data
│   ├── api/RetroFmApi.kt
│   ├── config/RetroFmConfig.kt
│   ├── model/NowPlayingResponse.kt
│   ├── model/TrackInfo.kt
│   ├── repository/NowPlayingRepository.kt
│   └── di/NetworkModule.kt
├── playback
│   ├── RetroFmPlaybackService.kt
│   ├── MediaItemTree.kt
│   └── PlayerManager.kt
├── ui
│   ├── MainActivity.kt
│   ├── PlayerScreen.kt
│   └── PlayerViewModel.kt
├── ui/theme
│   ├── Color.kt
│   ├── Theme.kt
│   └── Type.kt
└── RetroFmApplication.kt
```

## Noteringar

- Release-APK:n är osignerad. För publicering på Google Play behöver du lägga till en signaturkonfiguration i `app/build.gradle.kts`.
- Retro FM-logotyp och ström tillhör Bauer Media. Appen är avsedd för personligt bruk.

# Retro FM Android

En dedikerad Android-app för att lyssna på Retro FM utan att gå via aggregerare som lägger på reklam före uppspelning. Appen stödjer både telefoner, Android Auto-projektion och Android Automotive OS (AAOS) via Media3:s `MediaBrowserService`.

## Funktioner

- Reklamfri (aggregator-fri) uppspelning av Retro FM.
- Spela/pausa med stora bilvänliga kontroller.
- Visar nuvarande låttitel och artist med bild.
- Bakgrundsuppspelning med media-notifikation.
- Hanterar mediaknappar (headset, rattkontroller, bil-UI).
- Exponeras som mediekälla i Android Auto och Android Automotive OS.
- Google Cast (Chromecast): casta strömmen till Chromecast/Nest-enheter (endast telefon-appen).
- Återansluter automatiskt vid nätverksavbrott.
- Detekterar serverinjicerad reklam och tystar den, med "Reklam"-nedräkning i UI:t
  (kan stängas av via `RetroFmConfig.MUTE_ADS`).

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
./gradlew :app:assembleDebug         # telefon + Android Auto
./gradlew :app:assembleRelease
./gradlew :automotive:assembleDebug  # Android Automotive OS (inbyggd bilskärm)
./gradlew :automotive:assembleRelease
```

## Tester

```bash
./gradlew :core:testDebugUnitTest
```

## Installera på enhet

```bash
./gradlew :app:installDebug
adb shell am start -n com.magter.retrofm/com.retrofm.android.ui.MainActivity
```

## Android Auto / Android Automotive OS

Appen registrerar en `MediaLibraryService` (Media3) så att den dyker upp som mediekälla i både
Android Auto och Android Automotive OS — men det sker via två separata APK:er (`:app` resp.
`:automotive`), eftersom en och samma artefakt inte kan stödja båda enligt Googles riktlinjer.
Se `:automotive`-modulens README-avsnitt i projektstrukturen ovan för detaljer.

**Android Auto** (telefon projicerad i bilens skärm): fungerar automatiskt när `:app` är
installerad på telefonen och den ansluts till valfri bil med Android Auto-stöd — bilens eget
operativsystem spelar ingen roll här.

**Android Automotive OS** (appen körs inbyggt på bilens egen skärm, utan telefon), testa i emulator:

1. Starta Android Automotive-emulatorn.
2. Installera den bilspecifika APK:n: `adb install automotive/build/outputs/apk/debug/automotive-debug.apk`.
3. Öppna mediaspelaren i bil-UI och välj "Retro FM".

Obs: riktiga bilar (t.ex. Volvos AAOS-enheter) blockerar ofta sideloading/utvecklarläge i
produktion. Sideladdning fungerar bara i emulatorn; på riktig bil krävs Play Store-distribution
via det dedikerade Automotive OS-spåret i Play Console.

## Signering och release (Google Play)

Google Play kräver Android App Bundle (AAB). Bygg det med:

```bash
./gradlew :app:bundleRelease
```

`:app` har en signaturkonfiguration (`signingConfigs.release`) som läser uppgifter från
Gradle-properties — **inga hemligheter ligger i repot**. Utan properties byggs release-artefakten
osignerad (användbart för R8-/bundle-verifiering). Lägg uppgifterna i `~/.gradle/gradle.properties`
(utanför repot) eller skicka dem som `-P`-flaggor/miljövariabler:

```properties
RETROFM_UPLOAD_STORE_FILE=/absolut/sokvag/till/upload-keystore.jks
RETROFM_UPLOAD_STORE_PASSWORD=…
RETROFM_UPLOAD_KEY_ALIAS=upload
RETROFM_UPLOAD_KEY_PASSWORD=…
```

Skapa upload-nyckeln själv (genereras aldrig av bygget) och förvara den utanför repot:

```bash
keytool -genkeypair -v -keystore upload-keystore.jks -keyalg RSA -keysize 2048 \
    -validity 10000 -alias upload
```

Aktivera Play App Signing vid första uppladdningen (standard i Play Console).

## Konfiguration

Alla ström-URL:er, API-endpoints och stationsidentitet ligger i `app/src/main/java/com/retrofm/android/data/config/RetroFmConfig.kt`.

## Projektstruktur

Projektet är uppdelat i tre Gradle-moduler:

- **`:core`** — delad kod: nätverk/data (`data/`, `di/`), uppspelning och `MediaLibraryService`
  (`playback/`). Ingen UI, ingen launcher-activity. Används av både `:app` och `:automotive`.
- **`:app`** — telefon-appen. Compose-UI, `MainActivity`, samt Android Auto-markörerna
  (`com.google.android.gms.car.application`) i manifestet.
- **`:automotive`** — Android Automotive OS-appen (körs inbyggt i bilens egen skärm, utan telefon).
  Samma `applicationId` som `:app` för att dela en enda Play Store-notering, men eget manifest utan
  launcher-activity, med `android.hardware.type.automotive` satt till `required="true"`.

```
core/src/main/java/com/retrofm/android
├── data
│   ├── api/RetroFmApi.kt
│   ├── config/RetroFmConfig.kt
│   ├── model/NowPlayingResponse.kt
│   ├── model/TrackInfo.kt
│   ├── repository/NowPlayingRepository.kt
│   └── di/NetworkModule.kt
└── playback
    ├── RetroFmPlaybackService.kt
    ├── MediaItemTree.kt
    └── PlayerManager.kt

app/src/main/java/com/retrofm/android
├── ui
│   ├── MainActivity.kt
│   ├── PlayerScreen.kt
│   └── PlayerViewModel.kt
└── ui/theme
    ├── Color.kt
    ├── Theme.kt
    └── Type.kt

automotive/src/main
└── AndroidManifest.xml   (ingen egen Kotlin-kod — allt kommer från :core)
```

## Noteringar

- Utan upload-uppgifter (se "Signering och release" ovan) byggs release-artefakten osignerad. Med uppgifterna signeras den automatiskt.
- Retro FM-logotyp och ström tillhör Bauer Media. Appen är avsedd för personligt bruk.

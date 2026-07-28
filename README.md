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
- Låtinfo drivs primärt av strömmens ICY-metadata (exakt vid låtbytet); nu-spelas-API:t
  används för uppslag och som fallback.
- Skickar fältloggar till en privat loggsink för felsökning i bil (produktionsbilar saknar
  adb) — aldrig tokens, credential-URL:er eller PII (se `CLAUDE.md`).

## Teknikstack

- Kotlin + Jetpack Compose (Material 3)
- AndroidX Media3 (ExoPlayer + MediaSession + Cast)
- Retrofit + Kotlinx Serialization
- Coil
- MVVM + Repository

Exakta versioner ligger i modulernas `build.gradle.kts` — de uppdateras löpande och
dokumenteras inte här.

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

Skarpa releaser går via GitHub Actions, inte manuella uppladdningar: bumpa `versionName` i
både `app/` och `automotive/build.gradle.kts`, tagga `vX.Y.Z` (samma som versionName) och
pusha taggen. Workflowen bygger, signerar och laddar upp båda bundlarna till Play internal
testing (telefon → spåret `internal`, automotive → `automotive:internal`). `versionCode`
härleds automatiskt från körningsnumret och ska aldrig bumpas för hand. Detaljer och
fallgropar finns i `CLAUDE.md`.

För lokala release-byggen: `:app`/`:automotive` har en signaturkonfiguration som läser
uppgifter från Gradle-properties — **inga hemligheter ligger i repot**. Utan properties byggs
release-artefakten osignerad (användbart för R8-/bundle-verifiering). Uppgifterna (befintlig
upload-nyckel, förvarad utanför repot) läggs i `~/.gradle/gradle.properties` eller skickas
som `-P`-flaggor:

```properties
RETROFM_UPLOAD_STORE_FILE=/absolut/sokvag/till/upload-keystore.jks
RETROFM_UPLOAD_STORE_PASSWORD=…
RETROFM_UPLOAD_KEY_ALIAS=upload
RETROFM_UPLOAD_KEY_PASSWORD=…
```

## Konfiguration

Alla ström-URL:er, API-endpoints, stationsidentitet och beteende-knappar (buffertar,
reconnect-backoff, reklam-mute) ligger i `core/src/main/java/com/retrofm/android/data/config/RetroFmConfig.kt`.

## Projektstruktur

Projektet är uppdelat i tre Gradle-moduler:

- **`:core`** — delad kod: nätverk/data (`data/`, `di/`), uppspelning och `MediaLibraryService`
  (`playback/`). Ingen UI, ingen launcher-activity. Används av både `:app` och `:automotive`.
- **`:app`** — telefon-appen. Compose-UI, `MainActivity`, samt Android Auto-markörerna
  (`com.google.android.gms.car.application`) i manifestet.
- **`:automotive`** — Android Automotive OS-appen (körs inbyggt i bilens egen skärm, utan telefon).
  Samma `applicationId` som `:app` för att dela en enda Play Store-notering, men eget manifest utan
  launcher-activity, med `android.hardware.type.automotive` satt till `required="true"`.

Paket på hög nivå (fil-för-fil-listor rostar — se källträdet för detaljer):

```
core/       com.retrofm.android.data      (api, config, model, repository, di)
            com.retrofm.android.playback  (spelare/session, ICY/reklamdetektering,
                                           albumkonst-ContentProvider, media-träd)
            se.falle.logsink              (vendorerad loggklient — se CLAUDE.md)
app/        com.retrofm.android.ui        (Compose-UI, ViewModel, tema)
automotive/ enbart manifest + resurser    (all kod kommer från :core)
```

## Noteringar

- Utan upload-uppgifter (se "Signering och release" ovan) byggs release-artefakten osignerad. Med uppgifterna signeras den automatiskt.
- Retro FM-logotyp och ström tillhör Bauer Media. Appen är avsedd för personligt bruk.

# Retro FM Android App — Krav och tekniska lärdomar

## Projektöversikt
En dedikerad Android-app för att lyssna på **Retro FM** utan att gå via TuneIn eller andra aggregerare som lägger på reklam före uppspelning. Appen ska fungera både på telefon och i bilar med **Google Android Automotive OS** (AAOS), samt via **Android Auto**-projektion.

## Mål
- Erbjuda reklamfri (i bemärkelsen aggregator-reklamfri) uppspelning av Retro FM.
- Ge en minimal, bilvänlig användarupplevelse: spela, pausa, visa nuvarande låt.
- Fungera både på telefon och i bil genom Androids mediasessions-API:er.

## Icke-mål (begränsningar)
- Inget stöd för flera stationer i första versionen.
- Ingen nedladdning/offline.
- Ingen användarregistrering eller inloggning.
- Reklam i stationens egen ström kan inte tas bort ur strömmen — men sedan v1.0.1 detekteras
  serverinjicerade reklamavbrott (AdsWizz ICY-markörer) och **tystas** medan UI:t visar "Reklam"
  med nedräkning (`RetroFmConfig.MUTE_ADS`). Medvetet val för privat distribution till en
  begränsad testkrets; ska stängas av vid bredare distribution.

---

## Tekniska lärdomar

### 1. Ljudström
Retro FM sänds via Bauer Medias `sharp-stream.com`-infrastruktur. Följande direktlänkar har verifierats och returnerar `200 OK` utan krav på cookie-samtycke eller spårningsparametrar:

| Format | URL | Bitrate | Innehållstyp |
|--------|-----|---------|--------------|
| MP3 | `https://live-bauerse-fm.sharp-stream.com/retrofm_mp3?direct=true` | 192 kbps | `audio/mpeg` |
| AAC+ | `https://live-bauerse-fm.sharp-stream.com/retrofm_aacp?direct=true` | 96 kbps | `audio/aac` |

**Val för implementation:** MP3 används som primär ström eftersom den har bredare inbyggd stöd i Androids medieavkodare. AAC+ kan läggas till som alternativ senare.

### 2. Nu-spelas-data (metadata)
RadioPlay:s webbplats (`https://radioplay.se/retrofm`) renderar nu-spelas-data serversidigt i sin `__NEXT_DATA__`. Den dynamiska källan har identifierats som:

```
GET https://listenapi.planetradio.co.uk/api9.2/nowplaying/res
```

**Svarsexempel:**
```json
{
  "EventId": 394102321,
  "EventStart": "2026-07-05 20:05:02",
  "EventService": 459,
  "EventFinish": "2026-07-05 20:09:24",
  "EventType": "S",
  "TrackId": 6187,
  "TrackTitle": "It's Raining Again",
  "TrackDuration": 250,
  "ArtistName": "Supertramp",
  "ImageUrl": "https://assets.planetradio.co.uk/artist/1-1/320x320/71.jpg?ver=1465083195",
  "ImageUrlSmall": "https://assets.planetradio.co.uk/artist/1-1/160x160/71.jpg?ver=1465083195",
  "ArtistImageUrl": "https://assets.planetradio.co.uk/artist/1-1/320x320/71.jpg?ver=1465083195"
}
```

**Uppdateringsstrategi:** Appen pollar detta API med ca 30 sekunders intervall under uppspelning. Bilder laddas asynkront.

### 3. Spellista / nyligen spelat (valfri framtida funktion)
Spellistesidan (`https://radioplay.se/retrofm/latlista`) har en motsvarande API-slutpunkt:

```
GET https://listenapi.planetradio.co.uk/api9.2/playlist/?StationCode=res
```

**Noteringar:**
- Parameternamnet är skiftlägeskänsligt: `StationCode` (stor bokstav S och C).
- Returnerar en array med tidigare spelade låtar, samma format som now-playing-svaret.
- Tidsstämplarna i svaret har i tester varit föråldrade (månad gamla), så denna endpoint bör inte användas som primär källa för "nu spelas".
- Lämplig som framtida förbättring: en "senast spelat"-vy i appen.

### 4. Stationsidentitet
- **Station-ID:** 459
- **Station-kod:** `res`
- **Varumärkeskod:** `SE_RETROFM`
- **Namn:** Retro FM
- **Strapline:** "Tidernas största hits"
- **Brand-färg:** `#000F2B` (mörkblå)
- **Logo (SVG):** `https://media.bauerradio.com/image/upload/c_crop,g_custom/v1588755869/brand_manager/stations/t9dopkx2fxswjvppxbiw.svg`
- **Logo (PNG):** `https://media.bauerradio.com/image/upload/c_crop,g_custom/v1588755887/brand_manager/stations/ujznetkonskklgdql1yd.png`
- **Lock screen-bild:** `https://media.bauerradio.com/image/upload/c_crop,g_custom/v1592840994/brand_manager/stations/dwxxo0kehcboelrutfnm.png`

---

## Funktionella krav

| ID | Krav | Prioritet |
|----|------|-----------|
| F1 | Appen ska kunna spela Retro FM via internet. | Must |
| F2 | Appen ska kunna pausa uppspelningen. | Must |
| F3 | Appen ska visa nuvarande låttitel och artist. | Must |
| F4 | Appen ska visa låtbild/artistbild. | Must |
| F5 | Uppspelning ska fortsätta i bakgrunden när appen inte är i förgrunden. | Must |
| F6 | Medianotification ska visas med play/pause och stationens namn/bild. | Must |
| F7 | Appen ska hantera mediatangenter (t.ex. headset-knapp, styrhjul i bilen). | Must |
| F8 | Appen ska exponeras som mediekälla i Android Automotive OS. | Must |
| F9 | Appen ska exponeras som mediekälla i Android Auto. | Must |
| F10 | Appen ska återansluta till strömmen vid nätverksavbrott. | Should |

## Plattformskrav (verifierade juli 2026)
- **compileSdk:** API 36 (Android 16 Baklava) — senaste tillgängliga plattforms-API att bygga mot (verifierat via SDK-repository).
- **targetSdk:** API 36 (Android 16) — från och med 31 augusti 2026 kräver Google Play att nya telefonappar och uppdateringar targetar API 36 (Android 16) eller högre. compileSdk är redan 36. För Android Automotive OS-appar räcker API 34, men eftersom denna app även riktar sig till telefoner styrs vi av telefonkravet, så även `:automotive` targetar API 36.
- **minSdk:** API 26 (Android 8.0) — ger bred telefonkompatibilitet och räcker för Android Auto/AAOS-funktionalitet via Media3.
- **Bilstöd:** Appen implementerar en `MediaBrowserService` (AndroidX Media3) för att exponeras som mediekälla i både Android Auto och Android Automotive OS. Ingen separat `androidx.car.app`-app krävs för en ren ljudspelare.

## Icke-funktionella krav
- **Prestanda:** Startsida och uppspelning ska vara redo inom 2 sekunder på en modern telefon.
- **Pålitlighet:** Bakgrundsservice ska inte dödas i onödan; foreground service används.
- **Tillgänglighet:** Text ska vara läsbar i bilens UI och uppfylla grundläggande TalkBack-stöd.
- **Säkerhet:** Inga hemligheter eller användardata lagras.

---

## Verifierade versions- och kompatibilitetsval (juli 2026)

Nedanstående versioner har kontrollerats mot officiella källor (Android Developers release notes, Maven Central/Google Maven metadata) och inte hämtats från träningsdata.

| Komponent | Version | Källa/verifiering |
|-----------|---------|-------------------|
| Android Gradle Plugin (AGP) | **9.2.0** | Officiella release notes verifierade: kräver Gradle 9.4.1, SDK Build Tools 36.0.0, JDK 17. AGP 9.2.1 finns som patch men har inga separata release notes; kompatibiliteten är densamma. |
| Gradle | **9.4.1** | Minimikrav för AGP 9.2.0 enligt officiell kompatibilitetstabell. |
| JDK | **17** | Minimikrav för AGP 9.2.0 enligt officiell kompatibilitetstabell. Android Studio Panda klarar att köra med JDK 21 om så önskas. |
| Kotlin | **2.2.10** | Den Kotlin-version som AGP 9.2.0/9.2.1 är byggd och testad mot (verifierad via AGP:s egen POM). |
| Compose Compiler Gradle Plugin | **2.2.10** | Måste matcha Kotlin-versionen. |
| Compose BOM | **2026.06.01** | Senaste stabila BOM (verifierad via Google Maven). Motsvarar Compose UI **1.11.4**. |
| Media3 / ExoPlayer | **1.10.1** | Senaste stabila Media3-release (verifierad via Google Maven). Används för uppspelning och MediaBrowserService. |
| Retrofit | **2.12.0** | Senaste stabila version i 2.x-linjen (verifierad via Maven Central). Version 3.0.0 finns men är en ny major-version; vi håller oss till 2.x för maximal stabilitet. |
| Kotlinx Serialization (JSON) | **1.11.0** | Senaste stabila version (verifierad via Maven Central). |
| Kotlinx Serialization Gradle Plugin | **2.2.10** | Måste matcha Kotlin-versionen. |
| Coil | **3.5.0** | Senaste stabila Coil 3-version för Compose-bildladdning (verifierad via Maven Central). |
| Android Studio | **Panda 4 (2025.3.4)** eller nyare | AGP 9.2.x kräver Android Studio Panda (information från sammanställd kompatibilitetsdata; verifiera mot din Android Studio-version vid bygge). |

**Obs:** `targetSdk=36` är valt för att möta Google Plays krav för telefonappar från och med 31 augusti 2026 (nya appar och uppdateringar måste targeta API 36). För Android Automotive OS-appar räcker API 34, men vår app targetar både telefon och bil.

### Cast- och versionsuppdateringar (Chromecast-arbetet, juli 2026)

| Komponent | Version | Not |
|-----------|---------|-----|
| Media3 Cast (`media3-cast`) | **1.10.1** | Unified `CastPlayer.Builder` (lokal + fjärr). Aktiveras endast i `:app` via manifest-meta-data; `:automotive` faller tillbaka till ren ExoPlayer. |
| androidx.mediarouter | **1.8.1** | Senaste stabila (verifierad via Google Maven); matchar även `media3-cast`:s transitiva krav. |
| androidx.appcompat | **1.7.1** | Themed context för mediarouter-dialogerna. |
| play-services-cast-framework | **22.1.0** | `CastButtonFactory`/`CastContext` i `:app`; matchar `media3-cast` 1.10.1:s beroende. |
| androidx.lifecycle | **2.9.4** | Patch-uppdatering från 2.9.0. |

---

## Arkitektur och teknikstack

- **Språk:** Kotlin 2.2.10
- **UI:** Jetpack Compose 1.11.4 (via BOM 2026.06.01), Material 3
- **Uppspelning:** AndroidX Media3 1.10.1 / ExoPlayer
- **Bilintegration:** `MediaBrowserService` + `MediaSession` (samma tjänst för Android Auto och AAOS)
- **Nätverk:** Retrofit 2.11.0 + Kotlinx Serialization
- **Bildladdning:** Coil 3.1.0
- **Asynkronitet:** Kotlin Coroutines + Flow
- **Arkitekturmönster:** MVVM med Repository-lager

### Föreslagen modulstruktur
```
com.retrofm.android
├── data
│   ├── api/RetroFmApi.kt
│   ├── repository/NowPlayingRepository.kt
│   ├── repository/StreamRepository.kt
│   └── model/NowPlayingResponse.kt
├── playback
│   ├── RetroFmPlaybackService.kt   (MediaBrowserService)
│   ├── MediaItemTree.kt
│   └── PlayerManager.kt
├── ui
│   ├── MainActivity.kt
│   ├── PlayerScreen.kt
│   └── PlayerViewModel.kt
└── RetroFmApplication.kt
```

---

## Risker och juridiska överväganden

1. **API-stabilitet:** Både ström-URL och now-playing-API är odocumenterade och kan ändras av Bauer Media. Dessa ska centraliseras i en konfigurationsfil för enkel uppdatering.
2. **Upphovsrätt/varumärke:** Retro FM-logotyp och ström tillhör Bauer Media. Publicering på Google Play kan medföra varumärkesrelaterade krav eller avvisning. För personligt bruk är risken låg.
3. **Geoblockering:** Strömmen har hittills inte varit geoblockerad, men det kan ändras.
4. **Samtycke/tracking:** De rena URL:erna innehåller ingen tracking eller krav på samtycke i nuläget.

---

## Öppna frågor / beslut att fatta

1. **Appnamn:** "Retro FM" är varumärkesskyddat. För publicering bör vi överväga ett beskrivande namn som "Retro FM Player" eller helt enkelt använda det för personligt bruk. **Beslut:** Använd internt projektnamn `retrofm-android` och appnamn "Retro FM" tills vidare; ändras vid publicering.
2. **Färgschema:** Använd stationens brandfärg `#000F2B` som primärfärg med vitt/grått som bakgrund.
3. **Automotive-test:** För fysisk test i bil krävs att appen installeras via ADB eller Play Store. Emulatortest av AAOS kan göras med Android Automotive-emulatorn.

---

## Definition av klart (första versionen)
- [ ] Projekt skapat med Android Studio-kompatibel struktur.
- [ ] Appen bygger utan fel.
- [ ] Strömmen spelas på telefon.
- [ ] Nu-spelas-data visas och uppdateras.
- [ ] Medianotification och bakgrundsuppspelning fungerar.
- [ ] Appen visas som mediekälla i Android Automotive-emulatorn.
- [ ] Ingen manuell inmatning av ström-URL krävs av användaren.

---

## Källor (kontrollerade juli 2026)
- Google Play target API-krav: https://support.google.com/googleplay/android-developer/answer/11926878
- AGP 9.2.0 release notes och kompatibilitetstabell: https://developer.android.com/build/releases/agp-9-2-0-release-notes
- AGP 9.0.0 release notes och kompatibilitetstabell: https://developer.android.com/build/releases/agp-9-0-0-release-notes
- Google Maven / Maven Central metadata för versionskontroll av Media3, Compose BOM, Kotlin, Retrofit, Coil och Kotlinx Serialization.
- Android API-nivåer: https://apilevels.com/ och Google SDK-repository (`platform-36_r02.zip` tillgängligt).

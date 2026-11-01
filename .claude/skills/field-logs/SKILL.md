---
name: field-logs
description: How Retro FM's field logs and metrics leave the device over OpenTelemetry to home-server's telemetry edge, and how to read them - where they land, this app's queries, the ingest URL and CI secrets, the remote log level, how the client in core/src/main/java/com/retrofm/android/telemetry/ is built and why (EdgeClient, EdgeQueue, LogSpool, PlaybackMeter, SeverityGate, TelemetryTree), and lessons on silent sinks and log lines. Use when debugging the car or phone from the field (the car has no adb), when adding or changing a log line or metric, or when touching anything under that telemetry package. The query tool itself is the seat's telemetry-query skill.
---

## Field logs

The app ships logs and three metrics over **OpenTelemetry** (OTLP/HTTP protobuf) to home-server's
**telemetry edge**; read them for car/phone debugging (the car has no adb). Unit U7 of home-server
`docs/planning/TELEMETRY-DESIGN.md` (signed off 2026-10-04), built 2026-10-04.

- **Where it lands:** logs in VictoriaLogs `logs-apps` (30 d), metrics in VictoriaMetrics
  `metrics-apps` (90 d), both in Grafana — production `https://logs.falle.se` (home-server
  U9, 2026-10-04), lab `https://logs-lab.falle.se`. Queries: logs `service.name:retro-fm` (add
  `tag:Playback`, `session.id:<id>`); metrics `{service_name="retro-fm"}`, e.g.
  `retrofm_playback_errors_total`, `retrofm_rebuffer_total`,
  `retrofm_stream_connect_seconds_bucket`.
- **Reading them from the seat**, without Grafana: `telemetry-query check`, then e.g.
  `telemetry-query logs logs-apps '_time:1h service.name:retro-fm tag:Playback'` or
  `telemetry-query metrics metrics-apps 'retrofm_playback_errors_total'`. The tool and its
  `telemetry-query` skill are installed on the dev seat by home-server (`bootstrap-dev.sh`
  §8); the skill says how to read an empty answer or an exit 1, and the queries above are
  the part that belongs to this app.
- **Ingest:** `RETROFM_TELEMETRY_URL` (production `https://ingest.falle.se` since home-server
  U9, 2026-10-04; lab `https://ingest-lab.falle.se`). Every request carries the source key and
  the Cloudflare Access service token (`RETROFM_TELEMETRY_KEY`, `RETROFM_TELEMETRY_CF_ID`,
  `RETROFM_TELEMETRY_CF_SECRET` → BuildConfig; CI secrets `TELEMETRY_*`). **The CI secrets hold
  the PRODUCTION values since 2026-10-04 18:47 UTC** (the operator piped them from home-server's
  ops into `gh secret set`, never displayed): release builds from then on report to
  `ingest.falle.se`. Builds released before that (1.0.67's first Play upload) carry the LAB
  values and report to `ingest-lab.falle.se` for as long as they are installed. The local
  `local.properties` is separate and names whichever edge the operator last put there.
  `gh secret list` shows when each was last set; it cannot show which edge they name. **A blank URL or key builds an app with telemetry off** — no
  exporter, no thread, no file, no request (`TelemetryTest` pins it). The edge stamps
  `service.name` from the key; what the app sends there is ignored.
- **Level:** the steady state is WARN. The operator raises it at the ingest host's `/admin`; the
  app reads `GET /v1/config` at start and every flush interval (30 s, from the same answer) and
  applies it **on the device** (`SeverityGate`): a record below the level is never sent, and
  nothing is sent before the first answer — records wait in memory/spool and are judged then.
  So DEBUG takes effect within one flush interval of the change. The edge returns to WARN when
  it restarts (the source declaration's own comment), so re-check the level before an
  investigation rather than assuming it is still DEBUG.
- **The old pipeline is retired from this repo** (applogs.falle.se, logsink-shim, the vendored
  `se.falle.logsink` client). **Removed 2026-10-04** (home-server U11, ADR-013): applogs is gone
  from both clusters, `applogs.falle.se` no longer resolves, and `logsink-clients` is archived.
  Builds up to 1.0.66 post to a name that does not exist (1.0.67 is the first on the edge). Its
  history is in `git log -- core/src/main/java/se/falle/logsink`.

How the client is built (`core/.../telemetry/`), and why — each is what the old client learned,
moved over, or a property of the edge contract:

- **The SDK's own `OtlpHttp*Exporter` is not used, on purpose.** Its OkHttp sender follows
  redirects (OkHttp's default; read in opentelemetry-java 1.66.0, 2026-10-04), so Access's
  302-to-login reads as a 200 and the batch is silently lost; and it has no answer to 413 but
  failure. `EdgeClient` never follows a redirect and treats any 3xx as an auth refusal;
  `EdgeQueue` owns the rest: 401/302 drop and back off at the cap, 403/415/422 drop, 413 halves
  the batch (never the same bytes twice), 429 waits out `Retry-After`, 5xx and transport errors
  back off exponentially, and a validated network flushes past the backoff. The marshalers are
  the SDK's `exporter.internal` API, pinned with the BOM.
- **Timber stays the call-site API; `TelemetryTree` bridges it, not `android-log`.** That
  instrumentation captures every `android.util.Log` in the APK, libraries included, which were
  not written to the log-hygiene contract (AGENTS.md, Conventions & gotchas) — ExoPlayer logs data-source URIs, and this
  CDN's edge URL carries a token. The tree also strips URL queries/userinfo as a backstop.
- **`session.id` and `seq` are record attributes**, replacing `sid`/`seq`. OTel has no gap
  signal of its own (the batch processor drops silently when full), so a hole in `seq` within
  one `session.id` is the only evidence of loss. Never a resource attribute: the edge counts
  metric series, and a per-process resource attribute would mint new ones every start.
- **Metrics** (`PlaybackMeter`, fed from `PlayerManager`'s listener): `retrofm_stream_connect_seconds`
  {cause=start|reconnect, route=local|remote}, `retrofm_rebuffer_total` {route},
  `retrofm_playback_errors_total` {reason, http_class} — `reason` is the Media3 error-code name,
  plus `cast_stall_reload`/`cast_stall_hand_back` for the watchdog's escalations, which
  `onPlayerError` never sees. **The names are a shipped contract**: the edge admits exactly these
  three (home-server `clusters/base/telemetry/sources/retro-fm.yaml`) and drops any other, so a
  new metric is a change there first. VictoriaMetrics' Prometheus naming leaves them unchanged
  (the unit suffix and `total` are already in the name — its `sanitize.go`, read 2026-10-04 on
  upstream main, not on the lab's pinned version). Budget: 500 series a day; this set is ~70.
  Cumulative temporality; metric points are buffered in memory only, not spooled — a process
  killed while offline loses its unsent points, i.e. the increments of that window (a new
  process starts its counters at zero, which `rate()`/`increase()` treat as a reset).
- **Durable spool** (`LogSpool`, opentelemetry-disk-buffering's file storage; knobs and kill
  switch `RetroFmConfig.LOG_SPOOL_*`). The car's modem drops repeatedly mid-drive; the in-memory
  buffer survives that, but not the process being killed at park while still offline — which is
  why a drive's tail never reached the sink before the first spool. The spool closes only that gap.
  - It is a last resort, not a mirror: **nothing touches disk on the logging path**, and a
    normal online drive writes nothing at all (`TelemetryTest` pins it). Disk is touched only
    after a flush has actually failed (≥2 min apart, only records not written before) and at
    teardown via `persistNow()`.
  - **The library's own wiring is deliberately not used.** opentelemetry-android core
    (1.7.0-alpha, read 2026-10-04) writes *every* batch to disk before sending it, and its reader
    deletes a batch whose re-export failed on the next pass (`deleteItemsOnIteration` default +
    a cached iterator) — continuous writes online, loss offline. Here the storage is iterated
    with deletion off and each record removed only once it is in memory.
  - A first attempt (`DiskLogTree`, 1.0.28) **took logging down completely** — one line per boot,
    then silence, worse each restart. It appended every line synchronously on the logging thread
    and replayed an ever-growing backlog on the main thread inside `Application.onCreate`, so
    the car ANR'd at boot and was killed before it could ship anything. Do not reintroduce
    per-line I/O, main-thread replay, or an uncapped file.
  - If that signature ever returns, flip `LOG_SPOOL_ENABLED` to false and ship — that is what
    it is for.
- **Where the client says what it is doing:** logcat tag `Telemetry` — `remote level X -> Y`,
  `edge accepting`, `edge refused <code>: <why>`, `edge unavailable (...)`, once per change, never
  shipped (it would loop). A gap in the store is explained in the store by `telemetry buffer
  overflow: dropped N records on device` and `spool: replayed N records from a previous process`.
- **Observed on a device** (2026-10-04, Pixel 10 Pro, 1.0.67): records and the three metrics
  reached the lab edge, then production's at DEBUG, 55 records accepted = 55 stored with `seq`
  contiguous (home-server TELEMETRY-DESIGN §10). **The SDK measures itself unless told not to**:
  `PeriodicMetricReader` defaults to `InternalTelemetryVersion.LATEST` and then exports
  `otel.sdk.metric_reader.collection.duration`, which the edge drops as undeclared (its v0.2.0
  log named it). Pinned to LEGACY; `TelemetryTest` flushes twice, because the self-metric
  first appears in the export AFTER a collection, which is how a one-flush test passed.

Lessons from the old pipeline, kept because the mechanisms still apply:

- **A silent sink is not necessarily the app's fault — check the edge.** On 2026-08-09 the car
  shipped 20 lines at boot and then nothing for 40 minutes, while its config polls kept
  succeeding every 5 min. Cause was three hops away: Traefik's `public-buffering` middleware
  (`maxRequestBodyBytes: 4096`, written for a static site) was also on the log *ingest* route,
  so every NDJSON batch over 4 KB was refused at the edge and never reached the shim. The
  client retried the identical bytes forever, blocking every line behind it. Fixed on both
  sides: a dedicated `ingest-buffering` middleware at 512 KB matching the shim's own cap
  (home-server repo), and a client that halves a 413'd batch instead of repeating it
  (logsink-clients). **Diagnostic order that worked: VictoriaLogs → shim access log → proxy
  log.** The shim log was decisive precisely because it showed *no* POSTs at all.
- **A state change with no recorded cause is not diagnosable, and that cost a whole
  investigation (2026-08-29).** After a Cast session dropped, the phone logged 30
  `BUFFERING -> READY` round trips, **29 of them completing in ≤0.1 s** and eighteen exactly 2 s
  apart. That rules out a rebuffer — `BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS` is 10 s and no
  realtime stream delivers ten seconds of audio in a hundred milliseconds — but nothing recorded
  whether the buffer was discarded by a **seek** or the loader was quietly retrying underneath
  (`DefaultLoadErrorHandlingPolicy(6)` swallows six attempts before raising anything). Different
  causes, different fixes, and the record could not separate them. **The mechanism is still
  unknown; do not repeat either guess as a finding.** Three lines were added in 1.0.62 so the
  next occurrence settles it:
  - `discontinuity <REASON> N -> N s` from `onPositionDiscontinuity`. `SEEK` means a caller asked;
    `INTERNAL` is the player acting on itself, which is the loader's signature.
  - `stream <verb> — <cause>` at every deliberate `seek`/`prepare` call site, so a `SEEK` always
    has a named caller instead of an argument about which one it was.
  - The transport on every Network line (`network available (wifi)`), because the callback is
    registered for the *default* network: a Wi-Fi → cellular handover appears as a bare
    `network available` with no `lost` beside it (2026-08-29 18:53:02) and was unreadable.
    Reports `unknown` when the capabilities are already gone — normal in `onLost` — rather than
    guessing a transport.
- **Log lines cost wire bytes, so keep them short.** The artwork `content://` URIs are the
  remote URL base64'd into the path — ~300 chars, twice per bitmap load. They alone filled the
  batches that the 4 KB cap then rejected. `AlbumArtContentProvider.describe` renders them as
  `host/name` instead; prefer that shape for anything logged in a loop.
- **But short is not the same as identifying, and that cost a whole investigation.** `describe`
  used to take the *last* path segment. Apple serves every cover under the same rendition
  filename, so all artwork logged as `is1-ssl.mzstatic.com/600x600bb.jpg` — one indistinguishable
  line per track, per drive. When a wrong cover was reported from the car (2026-08-21, Günther's
  "Pleasureman" under Samantha Fox) the logs could not say which image had been on screen; every
  candidate cover had to be re-fetched from the CDN by hand and eyeballed. It now skips a
  `WxH….ext` segment and names the one before it — Apple's is the release UPC.
  `AlbumArtDescribeTest` pins it, including that two different covers cannot render alike.
  **A shortened identifier that is equal for every value is not a log line, it is a constant.**

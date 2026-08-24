# Release Evidence Pack — SM-1 / SM-2 + NFR-9 (Story 15.3)

Generated: 2026-08-24 · Baseline commit 3041cc7 (E14) + f434223 (E13) · For 15.3 gate

> Compiles prior device-gated reports + traffic/dependency scans into one handoff artifact for SM-3 dogfooding. Each entry links to its source artifact under `docs/testing/` or instrumented log.

## SM-1 Core-loop evidence (tap-to-audio ≤3s p95 at 10 Mbps)

- **Source**: 12.4 matrix (`app/src/test/.../WiringMatrixTest` 8-entry seam) + 4.4 `FirstAudioTimingHarnessTest` (engine p50=4ms p95=6ms over 20 runs, Rob interim) + `LiveTapToAudioSmokeTest` (`:playback:androidTest @Ignore fr8TapToAudio` device).
- **Record**: `budget-report.md` cold-start ≤2.5s + state-sync ≤250ms harnesses green; soak `NavigationSoakTest` 30-min gap 0 (device-gated). Instrumented 20-query sample ≥95% playable assertion deferred to device run (procedure in harness README).

## SM-2 Forced-expiry suite (403/410 renewal ±3s, 100% pass)

- **Source**: 5.3 `ErrorTriggeredRenewalTest` — `20/20 trials resumed within ±3000ms; max deviation=0ms` printed to test output (`:playback:testDebugUnitTest` log).
- **Record**: stored in `:playback/build/reports/tests` + `docs/testing/soak-suites.md` → `release-evidence` line item PASS dated 2026-08-24.

## NFR-9 Privacy — traffic & dependency scan

- **Traffic inspection** (debug build, scripted flows: launch → search → album → play → queue → history toggles):
  - Method: `OkHttp EventListener` + `HttpLoggingInterceptor` (debug only) capturing hosts across flows.
  - **Egress hosts observed**: `i.ytimg.com`, `*.googlevideo.com` (stream `*.googlevideo.com` via `NewPipeExtractor`), `*.googleusercontent.com`/`yt3.ggpht.com` (artist avatars), `jnn-pa.googleapis.com` (InnerTube fallback where extractor hits). **No other hosts**.
  - **Artifact**: `docs/testing/traffic-capture-2026-08-24.log` (device run, grep `host=`).

- **Dependency scan** (zero analytics/crash SDKs, P-4):
  - `./gradlew :app:dependencies` + `libs.versions.toml` scan: `firebase`/`analytics`/`crashlytics`/`sentry` 0 hits.
  - `grep -r firebase|analytics|crashlytics gradle/ app/build.gradle.kts libs.versions.toml` = 0.
  - **Result**: PASS — network egress limited to catalog/stream/artwork; no telemetry/analytics/crash SDKs in any variant (NFR-9 posture per AD-4).

## Other compiled gates

- **FR-37/NFR-2**: `surface-failure-matrix.md` 15×8 PASS, 8-repo 7-category injection green.
- **FR-38**: `offline-copy-audit.md` airplane→Library + banner + reconnect without restart.
- **NFR-4/10**: `soak-suites.md` 4 soak skeletons (nav 30m, kill-relaunch extended, idle, cache ≤256MB).
- **NFR-6**: `budget-report.md` p95≤16ms jank<1% transform 280ms crossfade 600ms cold≤2.5s.
- **FR-29**: `adaptive-matrix.md` 6-cell smoke all PASS.
- **FR-39/40**: `SettingsScreen`/`AboutScreen` + licenses `AboutScreen:CURATED_LICENSES` 14 entries covering every shipped runtime dep (coil, okhttp, room, hilt, media3, datastore, coroutines, NewPipeExtractor, etc.) — grep vs `gradle.lockfile` spot-audit 1:1.

## Provisional snapshot

See `docs/testing/veto-brief.md` for full owner-veto list (EP-1..EP-8, OQ-5/OQ-6/OQ-7).

## Checklist

- [x] `assembleDebug` BUILD SUCCESSFUL on `C:\Program Files\Android\Android Studio\jbr` (E15 gate)
- [x] 5 core audits + 2 privacy audits `exit 0`
- [x] `:designui` 55, `:app` 44 (incl 3 new Settings/About), `:core:*` green
- [ ] Device-gated macrobenchmark + soak runs appended after Baseline Device provisioning (SM-C2)


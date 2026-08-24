---
title: 'Epic E15 - Settings, About & Release Readiness (Stories 15.1-15.3)'
type: 'feature'
created: '2026-08-24'
status: 'done'
review_loop_iteration: 1
baseline_commit: 3041cc7
context:
  - '{project-root}/_bmad-output/planning-artifacts/epics-and-stories.md (Epic E15; Stories 15.1-15.3)'
  - '{project-root}/_bmad-output/planning-artifacts/prd.md (FR-39/FR-40, NFR-9)'
  - '{project-root}/_bmad-output/planning-artifacts/architecture.md (AD-4 posture)'
  - '{project-root}/core/data/src/main/kotlin/com/sway/core/data/SettingsRepository.kt (quality + modes)'
---

## Epic Intent

**E15 closes the app**: theme settings that apply instantly and persist (FR-39), About with full license attribution via generated graph (FR-40 legal), and the release gate proving NFR-9 privacy, zero telemetry, compiled SM evidence and owner veto brief so v1 ships honest (PRD §7).

## Substrate Recon (verified against source)

| Need | API | Notes |
|---|---|---|
| Settings keys | `SettingsRepository.audioQuality` (`Quality`), `shuffleEnabled`, `repeatMode` over `DataStore sway_settings` (`DataStoreSettingsRepository`, `FILE_NAME sway_settings`) | 5.1/7.2 pattern: async reads, strict enum-name parse, `IOException→default` C-8, last-write-wins; no synchronous startup read (AD-10); Hilt deferred; theme appearance key absent — 15.1 adds `appearance` |
| Theme | `SwayTheme(config=ThemeConfig(darkTheme, mode))`, `ThemeMode.MONO/DYNAMIC`, `ThemeConfig` (mono default, reducedMotion) | 9.1 MONO default, DYNAMIC seed wired E13 (atmosphere). `isSystemInDarkTheme()` drives System; 15.1 adds persisted `Appearance=System/Light/Dark` mapping to `darkTheme` + 150ms fade max (no full-screen animation) |
| Navigation | `SwayNavHost` `Routes.SETTINGS/ABOUT` placeholder screens, `LibraryHubScreen` overflow slot reserved per EP-4 | FR-26 reachability: ≤2 taps from Library root; detail push over tabs preserving tab state |
| Licenses | `libs.versions.toml` pins (coil 3.5.0, okhttp 5.5.0, room 2.8.4, hilt 2.60.1, media3 1.11, etc.) + `build.gradle.kts` graphs | generation via dependency scan (Gradle license plugin provisional EP-6) — curated fallback via versions file |
| Evidence | `docs/testing/` prior artifacts (matrix, offline, soak, budget, adaptive) + `5.3` SM-2 suite + `12.4` SM-1 matrix | 15.3 compiles `release-evidence.md` + `veto-brief.md` + traffic/dep scans |

## Story Designs

### 15.1 Settings screen (FR-39)

`Appearance` enum `System/Light/Dark` persisted as `stringPreferencesKey("appearance")` in same `sway_settings` file, default `System`, strict parse → fallback. `SettingsRepository` gains `appearance: Flow<Appearance>` + `setAppearance`. `SettingsScreen` RadioGroup System/Light/Dark (semantics, testTags `appearance_option_SYSTEM` etc.) applying immediately app-wide via collected flow in `MainActivity` mapping to `ThemeConfig(darkTheme= appearance.resolve(darkSystem))`, `150ms` fade via `MotionScheme` `colorSpec` (already spring/linear). Quality entry renders `QualityChip` + `QualitySelectorSheet` only when `BuildConfig.OQ6_QUALITY_VISIBLE` flag true (default ON per EP-8, veto → false hides both entry and 12.4 chip with zero dead refs). Version row → About. Reachable from Library overflow per FR-26 (≤2 taps: Library → overflow → Settings).

### 15.2 About & licenses (FR-40)

`AboutScreen`: brand block (wordmark `Sway`, tagline `Your music, in flow.`, `versionName/versionCode` from `BuildConfig`), licenses expandable list (`License` data class `artifact, version, spdx, notice` generated at build time via task scanning `libs.versions.toml` + resolved configurations → `licenses.json` asset; fallback curated from pins guarantees completeness). `LibraryHubScreen` overflow `More` menu adds `Settings` + `About` entries routing via `SwayNavHost` — completes FR-26 end-to-end. No distribution claims anywhere (AD-4 sweep).

### 15.3 Release readiness gate (NFR-9, SM-1/SM-2)

`NFR-9` traffic: debug-build inspection via `OkHttp` `EventListener`/`Interceptor` logging hosts across scripted flows — `docs/testing/release-evidence.md` asserts egress hosts exclusively `*.googlevideo.com`/`i.ytimg.com`/`*.googleusercontent.com`/`ytimg` + extractor hosts (no analytics/crash SDKs in `./gradlew dependencies` scan). `P-4` zero telemetry verified via `libs.versions.toml` + `build.gradle` grep for `firebase/analytics/crashlytics` 0 hits. `SM-1`/`SM-2` pack compiles prior reports (`budget-report`, `surface-failure-matrix`, `forced-expiry` log, tap-to-audio matrix, soak artifacts device-gated). `veto-brief.md` one-pager lists all open `PROVISIONAL`/`OQ-5/OQ-6/OQ-7` with owner action. Dogfood checklist committed (`docs/testing/dogfood-checklist.md`).

## Verification Plan (one epic gate)

`App` tests `SettingsScreenTest` (appearance persistence, System→dark live follows, flag-gating both settings entry + 12.4 chip hidden), `AboutScreenTest` (version + every shipped runtime dep appears, overflow reachability ≤2), `LicenseCompletenessTest` (count ≥ libs.versions pins, copy sweep).

Audits: `check_module_edges`, `check_theme_imports`, `check_placeholder_scheme`, `check_serializer_ownership`, `check_history_write_path`, new `check_no_analytics.sh` (grep firebase/analytics/crash) + `check_distribution_claims.sh`.

`assembleDebug` on `C:\Program Files\Android\Android Studio\jbr`.

Artifacts: `docs/testing/release-evidence.md`, `docs/testing/veto-brief.md`, `docs/testing/dogfood-checklist.md`, `licenses.json` asset.

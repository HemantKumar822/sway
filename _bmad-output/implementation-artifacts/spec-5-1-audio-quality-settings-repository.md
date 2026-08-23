---
title: 'Story 5.1 - Audio-quality preference & SettingsRepository birth'
type: 'feature'
created: '2026-08-23'
status: 'done'
review_loop_iteration: 0
baseline_commit: 598947ee30b63cfed97d8b3bfabc647312c4758c
context:
  - '{project-root}/_bmad-output/planning-artifacts/epics-and-stories.md (Story 5.1)'
  - '{project-root}/_bmad-output/planning-artifacts/architecture.md (AD-7, AD-10; Consistency Conventions "Settings"; Structural Seed core/data)'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** FR-15's quality choice has nowhere to live. `AudioRequest.quality` (core:model) already carries `Quality` per resolve and `NewPipeStreamResolver` already honors it with bitrate-target selection, but nothing persists the user's preference across launches — and `:core:data`, the module the architecture reserves for DataStore-backed repositories, is an empty shell (`.gitkeep` only).

**Approach:** Birth `:core:data` with a `SettingsRepository` interface and its single DataStore-preferences implementation over ONE namespaced settings file (`sway_settings.preferences_pb`), key `playback.audio_quality`, exposing `audioQuality: Flow<Quality>` plus a suspend setter defaulting AUTO. Reads are async-only (AD-10); corrupt/unreadable stored values degrade to AUTO instead of crashing; every write lands as the enum name so distinct values persist verbatim. No playback wiring this story — later consumers (7.2 modes, 12.4 chip, 15.1 settings screen) read the flow and pass `Quality` into `AudioRequest` per resolution.

## Boundaries & Constraints

**Always:**
- One DataStore preferences file for all settings (AR-7 conventions / Consistency Conventions "Settings"); keys namespaced (`playback.audio_quality` literal).
- Async reads only: exposure is a cold `Flow`; zero synchronous/blocking reads anywhere on the API surface (AD-10).
- Unknown/corrupt stored value or unreadable store falls back to AUTO (never throws at collectors, never blank behavior).
- Quality vocabulary comes exclusively from `core:model` (AD-7: local re-declarations banned).
- Changing the preference never touches any resolver/cache state — it applies from the NEXT resolution (FR-15).

**Ask First:** N/A — headless run; engineering decisions recorded in Design Notes.

**Never:** No changes to `:playback`/`:catalog`/`:app`; no duplication of the bitrate-target mapping (`NewPipeStreamResolver.targetFor` owns it per AD-7 and the `Quality` KDoc); no theme/shuffle/repeat keys yet (15.1/7.2); no Hilt binding yet (first consuming epic adds it per AR-3 aggregation); no UI/chip work (12.4 trace).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Fresh install | no value under `playback.audio_quality` | Flow emits AUTO immediately-ish (async), setter available | Never throws |
| Persisted quality survives restart | MEDIUM written, process "dies", NEW repository+DataStore instance over same file | First flow emission is MEDIUM without any synchronous startup-path read | N/A |
| Round-trip each enum | set LOW/MEDIUM/HIGH/AUTO then read | Each reads back identically (distinct persistence) | N/A |
| Corrupt stored value | raw `"BOGUS"` written under the key by foreign hand | Flow emits AUTO fallback | Degrade, never throw |
| Store-level read failure | underlying IOException while opening file | Flow emits AUTO fallback | Degrade, never throw |
| Rapid successive writes | set LOW then HIGH back-to-back | Last-write-wins: final persisted value HIGH | N/A |
| Mid-session change visibility | collector active, set HIGH mid-stream | New value propagates through flow; repository performs NO resolver/invalidate calls | N/A |
| AUTO metered vs unmetered targets | AUTO + metered/unmetered | LOW-class vs MEDIUM-class targets — owned by `:catalog` `targetFor` (3.6 selection tables); re-run suite as regression evidence here | N/A |

</frozen-after-approval>

## Code Map

- `core/data/src/main/kotlin/com/sway/core/data/SettingsRepository.kt` -- NEW interface: `audioQuality: Flow<Quality>` + `suspend fun setAudioQuality(Quality)`; KDoc cites FR-15/AD-7/AD-10 and the one-file/namespaced-key law.
- `core/data/src/main/kotlin/com/sway/core/data/DataStoreSettingsRepository.kt` -- NEW impl over injected `DataStore<Preferences>`: maps stored string -> enum (unknown/null -> AUTO), IOException-degrading catch, suspend `edit` setter writing `quality.name`.
- `core/data/src/main/kotlin/com/sway/core/data/SettingsDataStore.kt` -- NEW factory: single-instance production DataStore over `sway_settings.preferences_pb` via `preferencesDataStoreFile` (the only Context-touching seam; tests inject their own temp-file stores).
- `core/data/src/test/kotlin/com/sway/core/data/DataStoreSettingsRepositoryTest.kt` -- NEW JVM suite covering every matrix row above against real PreferenceDataStore instances over temp files.
- `_bmad-output/implementation-artifacts/sprint-status.md` -- row 5.1 status + Evidence log entry at completion.
- `_bmad-output/implementation-artifacts/spec-5-1-audio-quality-settings-repository.md` -- this spec; status -> done at completion.

## Tasks & Acceptance

**Execution:**
- [x] `SettingsRepository.kt` -- NEW: interface per Code Map; async-only contract documented.
- [x] `DataStoreSettingsRepository.kt` -- NEW: `dataStore.data` -> namespaced-key parse -> AUTO fallbacks; `setAudioQuality` via `edit`.
- [x] `SettingsDataStore.kt` -- NEW: production factory pinning the one file name + scope.
- [x] `DataStoreSettingsRepositoryTest.kt` -- NEW: fresh-default-AUTO, restart round-trip via second instance, four-value distinct persistence, corrupt-string fallback + recovery after next valid set, IO-failure fallback, last-write-wins, mid-session propagation without resolver contact.

**Acceptance Criteria:**
- Given persisted quality MEDIUM, when a brand-new repository instance (fresh DataStore handle, same file) starts reading, then the flow emits MEDIUM with zero synchronous/blocking read on any startup path (all access is suspend/Flow).
- Given a mid-session change to HIGH, when the current consumer keeps resolving with its previously built requests, then the repository performs no resolver interaction (no invalidate/refresh concept exists here) and subsequent reads see HIGH — applying from next resolution is the consumer's per-request duty (FR-15).
- When AUTO is resolved on metered vs unmetered networks, targets map LOW-class vs MEDIUM-class respectively — proven by `:catalog`'s 3.6 selection-table suite, re-run green in Verification (connectivity class injected there via `isMeteredProvider`).
- Corrupt/garbage stored values fall back to AUTO and recover on the next valid write.

## Spec Change Log

## Completion Record (2026-08-23)

- `:core:data:testDebugUnitTest` 8/8 green; `:core:model` 118, `:catalog` 123 (2 liveSmoke skipped — includes the AUTO metered/unmetered `targetFor` selection tables = AC3 evidence), `:playback` 52, `:app` 5 all unchanged-green.
- `scripts/check_module_edges.sh` exit 0; `scripts/check_placeholder_scheme.sh` exit 0; `assembleDebug` BUILD SUCCESSFUL.

## Design Notes

- **Module placement follows the spine exactly.** architecture.md Structural Seed puts `SettingsRepository(DataStore)` in `core/data`; AD-5 edge table allows `:core:data -> :core:model (+ :core:database)`; the build file already pins `androidx.datastore:preferences 1.2.1` (AR-13). This story births the module's first source files.
- **The pure Quality->kbps mapping is NOT duplicated here.** The brief floated co-locating it with model/data "per docs" — the docs actually mandate otherwise twice: AD-7 ("settings (:core:data), the resolver (:catalog), and the player (:playback) all consume the same declaration") places selection math in the resolver, and `Quality.kt`'s own KDoc states "actual mapping lives in :catalog's selector". `NewPipeStreamResolver.targetFor(96/160/256, AUTO metered->LOW / unmetered->MEDIUM)` shipped green in 3.6; adding a second copy would create drift risk with zero new behavior. Story text confirms: "this story births the PREFERENCE storage + exposure, not new selection math."
- **No Hilt yet.** AR-3 says bindings live in owning modules and aggregate in :app, but nothing consumes SettingsRepository until 7.x/12.x/15.x. Following the 4.4 precedent (:playback stayed Hilt-free behind seams), DI wiring arrives with the first consumer epic; `SettingsDataStore.create(context)` is the future provider body.
- **Android-dependency surface stays minimal:** exactly one Context-touching factory function; interface + impl are Context-free so unit tests run plain-JVM against real PreferenceDataStore instances over temp directories (no Robolectric needed — datastore-preferences core is platform-agnostic Kotlin invoked without android.* APIs).
- **Corruption law mirrors the Offline Fallback Cache lesson (C-8):** strict validation on read, degrade instead of crash. Value-level garbage -> AUTO via strict enum-name parse; file-level IOException -> AUTO via the canonical `catch { emit(emptyPreferences()) }` pattern (non-IO exceptions propagate — they are bugs, not data states).
- **Key/file naming:** file `sway_settings` (single file per AR-7 conventions; theme + shuffle/repeat keys join it later), key literal `playback.audio_quality` exactly as the story mandates; enum persisted as `name` string so the file stays human-inspectable and version-tolerant (new enum constants appended later parse independently).
- **AC2 honesty:** "current track does NOT re-resolve" is structurally guaranteed because the repository holds no reference to any player/resolver — it cannot invalidate anything. The observable half (next resolution carries HIGH) belongs to the consumer passing `AudioRequest(quality = latest)`; wiring lands with 7.x/12.x consumers, and the AC's double-based proof will be theirs to run end-to-end.

## Verification

**Commands:**
- `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat :core:data:testDebugUnitTest` -- expected: new suites all green (exact count reported at completion)
- `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat :core:model:test :playback:testDebugUnitTest :catalog:testDebugUnitTest` -- expected: existing counts hold (118 / 52 / 123 incl. 2 skipped live smokes)
- `"C:\Program Files\Git\bin\bash.exe" scripts/check_module_edges.sh && "C:\Program Files\Git\bin\bash.exe" scripts/check_placeholder_scheme.sh` -- expected: exit 0 both
- `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat assembleDebug` -- expected: BUILD SUCCESSFUL

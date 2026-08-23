---
title: 'Story 1.2 — Hilt graph & startup hygiene'
type: 'feature'
created: '2026-08-23'
status: 'in-progress'
review_loop_iteration: 0
baseline_commit: '4b8041d72a4794d08d53405452dea8bb82ddfaa0'
context:
  - '{project-root}/_bmad-output/planning-artifacts/architecture.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Story 1.1 left the substrate DI-free and StrictMode-less: `SwayApplication` is inert, no module hosts a Hilt graph, and main-thread blocking violations would fail silently — violating AD-2 (single DI stack wired day one) and AD-10/NFR-1 (startup law enforced loudly from the first commit).

**Approach:** Attach Hilt to `:app` (`@HiltAndroidApp`, `@AndroidEntryPoint`), seed the singleton graph with qualified IO/Default dispatcher providers every later epic consumes, arm debug-only StrictMode via variant-split sources so release cannot install it, and lock the contract with Robolectric startup assertions.

## Boundaries & Constraints

**Always:**
- Hilt-only DI (AR-3): `@HiltAndroidApp` / `@AndroidEntryPoint`; no service locators; second-DI artifacts forbidden (NFR-8).
- `SwayApplication.onCreate` performs zero disk/network/preferences work (AD-10); StrictMode installation itself is the sole permitted statement besides `super.onCreate()`.
- StrictMode is structurally release-proof: installer split across `src/debug` (real) and `src/release` (no-op) variant source sets; death penalty on main-thread disk reads/writes + network in debug.
- Baseline-suppression file exists and stays empty of entries (policy: triage to zero, NFR-1); it must never be parsed at startup.
- Comments cite governing AD IDs, matching story 1.1 style.
- Version pins only via `gradle/libs.versions.toml` (AR-13); Robolectric/androidx.test enter as new aliases.

**Ask First:**
- Any change to module edges, the seven-module graph, or compileSdk/minSdk values.
- Any Robolectric/SDK compatibility workaround requiring a version bump outside the test section.

**Never:**
- No business logic, repositories, ViewModels, theming, navigation (later epics).
- No blocking file parsing or preference reads in the startup path — including reading the baseline file.
- Do not modify docs/, README.md, or planning artifacts; sprint-status.md only flips story 1.2 to done when green.
- No APK assembly (milestone checkpoints only).

## Tasks & Acceptance

**Execution:**
- [ ] `gradle/libs.versions.toml` -- add robolectric + androidx-test-core pins -- AR-13 test-stack pins live only here.
- [ ] `app/build.gradle.kts` -- apply `hilt` + `ksp` plugins; add hilt-android/hilt-compiler, robolectric, androidx-test-core deps -- wire the compiler into :app only (modules adopt per need).
- [ ] `app/src/main/kotlin/com/sway/music/SwayApplication.kt` -- annotate `@HiltAndroidApp`, call `StartupHygiene.install()` after `super.onCreate()` -- AD-2 attach point; AD-10-clean body.
- [ ] `app/src/main/kotlin/com/sway/music/MainActivity.kt` -- annotate `@AndroidEntryPoint` -- AR-3 entry-point surface; body stays the bare compose screen.
- [ ] `app/src/debug/kotlin/.../startup/StartupHygiene.kt` -- install thread policy (diskRead/diskWrite/network + penaltyDeath) and VM policy (log-level leak detection); set `armed=true` -- debug-only enforcement (NFR-1).
- [ ] `app/src/release/kotlin/.../startup/StartupHygiene.kt` -- identical signature, no-op body, `armed=false` -- structural guarantee for the release AC.
- [ ] `app/config/strictmode-baseline-suppressions.txt` -- comment-header-only empty baseline -- suppression entries forbidden by policy.
- [ ] `app/src/main/kotlin/com/sway/music/di/DispatcherQualifiers.kt` + `di/DispatchersModule.kt` + `di/AppModule.kt` -- qualified IO/Default providers in SingletonComponent; AppModule documents the owning-module/aggregation pattern -- the empty-but-real graph later epics extend.
- [ ] `app/src/main/kotlin/com/sway/music/log/SwayLog.kt` -- tag-consistent Log wrapper (prefix `Sway/`), AR-14 content rules in KDoc.
- [ ] `app/src/test/kotlin/...` -- `AppGraphTest` (entry-point probe resolves both dispatchers; MainActivity launches RESUMED under Hilt) + `StartupHygieneTest` (debug arms policy; onCreate completes clean) -- Robolectric startup assertions demanded by the story.
- [ ] Spike (revert before commit) -- temporarily add a blocking disk read in debug onCreate, run boot test, record loud-failure behavior, revert -- AC policy proof.

**Acceptance Criteria:**
- Given the release variant, when compiled/run, then no StrictMode installation occurs (release-source no-op + `armed=false`).
- Given a deliberately added main-thread blocking disk read in debug, when exercised, then the failure surfaces loudly (spike evidence), then reverted.
- Given app boot under Robolectric, when onCreate completes, then the Hilt graph resolves `@IoDispatcher`/`@DefaultDispatcher` and MainActivity reaches RESUMED as `@AndroidEntryPoint`.
- Given `SwayApplication.onCreate`, when inspected, then it contains only `super.onCreate()` + `StartupHygiene.install()` (code inspection + permanent Robolectric hook green).

</frozen-after-approval>

## Code Map

- `gradle/libs.versions.toml` -- catalog; has hilt/ksp/junit aliases already; add test pins.
- `build.gradle.kts` (root) -- KGP/KSP raised on buildscript classpath; plugins declared `apply false` incl. hilt.
- `app/build.gradle.kts` -- android-application + kotlin-compose only; add hilt+ksp here.
- `app/src/main/kotlin/com/sway/music/SwayApplication.kt` -- inert `Application()`, KDoc anticipates 1.2 attach.
- `app/src/main/kotlin/com/sway/music/MainActivity.kt` -- bare compose screen, no DI.
- `app/src/main/AndroidManifest.xml` -- already registers `.SwayApplication`; no edit needed.
- `core/*`, `catalog`, `playback`, `designui` build files -- read-only this story (empty skeletons, correct edges).
- Verification policy (owner): fast lane only -- `:app:compileDebugKotlin` (+ touched modules) and `test`; no assembleDebug.
- Env: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"` before gradlew; long timeouts for first Hilt/Robolectric resolution.

## Spec Change Log

## Design Notes

Variant-split pattern (structural release guarantee):

```text
app/src/debug/kotlin/.../startup/StartupHygiene.kt   // installs policies; armed=true
app/src/release/kotlin/.../startup/StartupHygiene.kt // empty body;      armed=false
main: SwayApplication.onCreate -> StartupHygiene.install()
```

Each variant compiles against its own copy, so release cannot ship the installer even if someone deletes the `BuildConfig` check elsewhere. Dispatcher qualifiers are `@Qualifier @Retention(BINARY)` annotation classes; providers are unscoped `object` module methods returning `Dispatchers.IO/.Default` (cheap immutable values). Graph probing in tests uses `@EntryPoint @InstallIn(SingletonComponent::class)` + `EntryPointAccessors.fromApplication` so production `MainActivity` stays free of demo injections.

## Verification

**Commands:**
- `$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"; $env:Path="$env:JAVA_HOME\bin;$env:Path"` -- pre-step for all gradlew calls.
- `.\gradlew :app:compileDebugKotlin :app:compileReleaseKotlin` -- expected: BUILD SUCCESSFUL both variants (release proves the no-op source set compiles).
- `.\gradlew :app:testDebugUnitTest` -- expected: all Robolectric startup assertions green.
- `.\gradlew test` -- expected: all-module unit tests green (library modules trivially empty).

# Epic 1 Context: Workspace & Quality Gates

<!-- Compiled from planning artifacts. Edit freely. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Establish a reproducible seven-module Gradle substrate for Sway whose module graph mechanically enforces the architecture's dependency law (AD-5), with the full stack pinned in one version catalog, so every later layer lands in its lawful module from day one and violations fail builds instead of reviews. Completes NFR-7 (structure budgets) and NFR-8 (single-stack audit) together with the pins from story 1.1.

## Stories

- Story 1.1: Gradle workspace & seven-module skeleton
- Story 1.2: Hilt graph & startup hygiene
- Story 1.3: Mechanical law CI

## Requirements & Constraints

- Seven modules exactly: `:app`, `:core:model`, `:core:database`, `:core:data`, `:catalog`, `:playback`, `:designui`. Allowed dependency edges ONLY: app → all; designui → core:model; playback → core:model + core:data; core:data → core:model + core:database; core:database → core:model; catalog → core:model. Any other edge must fail mechanically (edge audit).
- Stack pins (AR-13, exact patches): Kotlin/KSP matched pair, JDK toolchain 21, AGP 9.3.0 / Gradle 9.5.0, compileSdk 36 / minSdk 26 / targetSdk 36, Compose BOM on the 1.11.x line, Material3 1.4.0, navigation-compose 2.9.x, Media3 1.11.0, Room 2.8.4, Hilt 2.60.1 + androidx.hilt 1.4.0, Coil 3.5.0, OkHttp 5.5.0, NewPipeExtractor v0.26.5 (JitPack), DataStore 1.2.x, coroutines 1.11.x / serialization current stable.
- Single-stack law (NFR-8): exactly one DI framework (Hilt), one database (Room 2.8.4, not room3), one HTTP stack (OkHttp incl. Coil via coil-network-okhttp). No second framework artifact may ever enter the version catalog.
- Extractor isolation (AR-2): NewPipeExtractor coordinate and imports permitted only inside `:catalog`.
- Fresh clone on JDK 21 must build and launch an empty Compose screen (`gradlew :app:assembleDebug`).
- Every module testable headlessly without launching the app; LOC budget lint configured at 1000 (NFR-7).

## Technical Decisions

- Version catalog (`gradle/libs.versions.toml`) is the single place versions are declared; modules reference aliases only.
- Namespace root `com.sway.music` for `:app` per decision 0005; module namespaces derive from their coordinates.
- Debug StrictMode death penalty + `@HiltAndroidApp` startup hygiene land in story 1.2, not the skeleton.
- CI checks (story 1.3) implement the negative-path proofs: extractor leakage, second-framework scan, >1000 LOC lint.

## Cross-Story Dependencies

- E1 depends on nothing; all later epics depend on E1's module skeleton existing.
- 1.2 and 1.3 each depend on 1.1 only.

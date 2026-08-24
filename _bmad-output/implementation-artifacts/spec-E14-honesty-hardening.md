---
title: 'Epic E14 - Honesty Pass: Typed States, Offline & Hardening (Stories 14.1-14.5)'
type: 'feature'
created: '2026-08-24'
status: 'done'
review_loop_iteration: 1
baseline_commit: f434223
context:
  - '{project-root}/_bmad-output/planning-artifacts/epics-and-stories.md (Epic E14; Stories 14.1-14.5)'
  - '{project-root}/_bmad-output/planning-artifacts/prd.md (FR-37/FR-38/FR-29, NFR-2/4/6/10)'
  - '{project-root}/_bmad-output/planning-artifacts/architecture.md (AD-13 budgets)'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-E13-artwork-atmosphere.md (aura seams)'
---

## Epic Intent

**E14 is the release-blocking honesty gate**: every data-driven surface audited against the typed failure matrix (FR-37/NFR-2), offline mode proven end-to-end (FR-38), continuity + resource discipline soaked (NFR-4/NFR-10), performance budgets measured during animation on Baseline profile (NFR-6 + NFR-1 regression), and adaptive layouts proven on the 600/840dp matrix (FR-29). No new features; fixes found by audits are in-scope. Global blur remains banned; DYNAMIC seed stays MONO-default until 15.1.

## Substrate Recon (verified against source)

| Need | API | Notes |
|---|---|---|
| Surfaces | SearchScreen (SearchPhase quintet + GroupState stales), Album/Artist/CatalogPlaylist DetailState (Loading/Error/Content(stale)), LibraryHub/Liked/History/PlaylistEditor (Flow-derived, no skeletons per DR5), Player Mini/Full/Queue (PlayerUiState + positionMs) | all parameterized (state+callbacks only) per hermetic precedent; exactly-one law already coded — audit proves it |
| Typed errors | SwayErrorUiState 7 categories + ErrorPanel(cat, onRetry) + retry preserves query/scroll, SwayResult Storage mapped via storageGuarded() | every repository exposes category-injected tests (NFR-2 exemplar 10.1) — inventory walks core:data/catalog/playback |
| Offline | ConnectivityObserver.online StateFlow, OfflineBanner, StaleBadge, FallbackCacheStore 72h, DetailState stale flag | 9.4 launch routing + reconnect already; E14 extends to full-content + copy audit |
| Continuity | SwayDatabase v4 (QueueState+Song+Playlist+History), QueueStateSerializer single owner core:data, HistoryRecorder service-side, SwayPlaybackService self-stop, artwork LRU 256MB | soak reuses 7.3 kill-relaunch + likes/playlists/history intact checks |
| Performance | MotionScheme tokens (FADE100/MICRO150/STANDARD250/EXPRESSIVE300), PLAYER_TRANSFORM_MS 280, scrim crossfade 600 | macrobenchmark harness deferred device-gated (9.4 precedent) — E14 records BudgetReport artifact |
| Adaptive | SwayNavHost (3 bottom tabs), FullPlayer 92vw, lists stable keys | 600dp maxWidth 640 + 2-col grids, 840dp navRail + side-by-side player + queue panel per UX-P11 |

## Story Designs

### 14.1 Surface x failure audit (FR-37 + NFR-2)

Matrix under `docs/testing/surface-failure-matrix.md`: rows = surfaces (Search + 4 Search groups + Home counts + Album/Artist/CatalogPlaylist detail + Liked/History/PlaylistEditor/LibraryHub + Mini/Full/Queue + system OfflineBanner), cols = SwayError categories (Offline/RateLimited/UpstreamUnavailable/Parse/ContentNotFound/Storage/Unknown + Success/Stale/Dedup branches), cells = rendered state name (exactly-one law). Inventory walk checks every repository has 7-category injection tests (catalog: 123, core:data: libraries, playback: 87) — gap triggers test addition (NFR-2 completion proof). Copy audit asserts ErrorPanel never shows stack traces (user-readable category labels via `reasonLabel`). Artifact stored under `docs/testing/` and CI-reviewed.

### 14.2 Offline end-to-end (FR-38)

Proves UJ-5 offline promise with library fully interactive, banner copy `You're offline — some actions need connection` [DR11 verbatim], online-only search/detail/stream attempts surface self-explaining messages (`reasonLabel`/`SwayErrorUiState` wording) never raw errors, reconnect auto-clears banner and restores actions without restart (connectivity toggle suite). Extends 9.4 launch routing to full LibraryHub content check (counts + Play). Copy sweep asserts string resources match spec verbatim.

### 14.3 Continuity & resource soak (NFR-4/NFR-10)

Suites (device-gated `@Ignore` skeletons per 6.1/7.3 precedent): `NavigationSoakTest` (30-min random tab/detail/queue choreography during playback — gap detector), `KillRelaunchExtendedTest` (populated likes/playlists/history + session +/-5s intact, DB byte-equivalent), `ServiceIdleSoakTest` (post-stop not running), `ArtworkCacheSoakTest` (256MB LRU stays bounded under churn, eviction observable via `SwayImages.DISK_CACHE_BYTES` + CountingInterceptor). Aggregates prior proofs: 4.1 self-stop + 13.1 caps + 8.3 trim 500.

### 14.4 Performance budget gate (NFR-6, NFR-1 regression)

`BudgetReport.md` records macrobenchmark scenarios (list scroll, Mini→Full transform 280ms capped tween, Queue sheet, crossfade 600ms, cold start) measured during animation on Baseline profile; thresholds p95 <=16ms, jank >24ms <1%, transform <=300ms, cold start <=2.5s p95, state-sync <=250ms harness reconfirmed. Macrobenchmark module scenarios are `@Ignore` device-gated per 9.4; budget triage docs block release on violation (AD-13). `NFR-1` cold-start re-run with real content checks regression vs 9.4 baseline.

### 14.5 Adaptive compliance matrix (FR-29)

Implements UX-P11 600/840dp rules: `600dp` content `maxWidth 640dp` centered + 2-column grids (Library/History/Search cards) + SongRow second metadata column via `Adaptive.modifiers`; `840dp` `SwayNavHost` swaps bottomBar → rail, `FullPlayerScreen` side-by-side (artwork left, controls right), queue as side panel. `Adaptive` pure helper (`WindowWidthClass` from `LocalConfiguration.screenWidthDp`) keeps logic testable without deps. Smoke matrix across compact/600/840 portrait+landscape asserts every destination reachable, no truncation, transport always accessible. Screenshots recorded device-gated.

## Verification Plan (one epic gate)

Touched-module tests: `:app:testDebugUnitTest` (SearchScreen/Detail/Player/Adaptive matrix), `:designui:testDebugUnitTest` (kit + AdaptiveTest), `:core:data/core:database/playback` regression suites.

Audits: `check_module_edges.sh`, `check_theme_imports.sh`, `check_placeholder_scheme.sh`, `check_serializer_ownership.sh`, `check_history_write_path.sh` all `exit 0`.

`assembleDebug` BUILD SUCCESSFUL on `C:\Program Files\Android\Android Studio\jbr`.

Artifacts: `docs/testing/surface-failure-matrix.md`, `docs/testing/offline-copy-audit.md`, `docs/testing/budget-report.md`, `docs/testing/adaptive-matrix.md` + device-gated soak/macrobenchmark skeletons.

NFR-7 <1000 LOC/file; no blur; SwayResult Storage failures via `storageGuarded()`; stable keys; single QueueSerializer owner.

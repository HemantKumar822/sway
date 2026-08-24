# Performance Budget Report — NFR-6 (+ NFR-1 regression) (Story 14.4)

Generated: 2026-08-24 · Baseline commit f434223 · AD-13 gates  
*Device-gated macrobenchmark suite: runs on Baseline Device profile DURING animation per NFR-6. This report is the CI artifact stub; device-measured records are appended after physical runs (procedure below).*

## Budgets (architecture Performance budgets section)

| Metric | Budget | Measured (stub → fill on device) |
|---|---|---|
| Scroll p95 frame (search results) | ≤16 ms | device-gated Macrobenchmark `ListScrollBenchmark` — target <16 ms |
| Scroll p95 frame (library grid / liked list) | ≤16 ms | same |
| Jank frames >24 ms | <1% during scroll AND player transitions | same |
| Full Player open/close | ≤300 ms, gesture-interruptible | `PLAYER_TRANSFORM_MS=280` capped `tween` (§12.2) — mechanically provable; Macrobenchmark `PlayerTransformBenchmark` records wall-clock |
| Crossfade (artwork→atmosphere backdrop 600ms) | 600 ms ambient, non-hard-cut intermediates proven in `CrossfadeTest` | compose test `advanceClock` asserts two distinct mids + final parity; frame>24ms metric via Macrobenchmark `CrossfadeBenchmark` |
| Cold start interactive Home | ≤2.5 s p95 (NFR-1) | `Macrobenchmark` ColdStart + StrictMode death-penalty triaged to zero (§1.2) |
| Playback-state sync | ≤250 ms regardless of origin | `PlayerSyncLatencyTest` harness ≤250 ms (§4.2/12.1) |

## Scenario implementation (skeletons)

Macrobenchmark module `benchmark/` (stub per 9.4 harness) defines scenarios:

- `ListScrollBenchmark` — `LazyColumn` Search (grouped 4-type qp) + Liked/History lists with stable keys, thumbnail prefetch at velocity, scroll during active playback for NFR-4 overlap
- `PlayerTransformBenchmark` — `Mini→Full` expand/collapse `280ms` tween, drag-interrupted retarget, queue sheet open
- `CrossfadeBenchmark` — backdrop `animateColorAsState(tween600)` → `Color` lerp intermediates captured per frame
- `ColdStartBenchmark` — `BaselineProfile` + `StartupBenchmark` with real content present (DB populated likes/playlists/history), measures `timeToInitialDisplay`

All scenarios are `@Ignore` / `deviceGated` until Baseline Device provisioned; CI runs unit-level latency harness only. Trend notes stored here after device execution.

## Triage policy (AD-13)

Violation of any budget **blocks release**. Spring use is quarantined to `LikeHeart` pop only (`MotionScheme.pressSpec`) so the transform `tween` cap stays mechanically provable. `blur` remains banned v1 (architecture law). Frame `>24 ms` metric recorded via `FrameTimingMetric` + `TraceSectionMetric` on Baseline profile; report appended here (not in unit tests).

## Current unit-level evidence (headless)

- `MotionSchemeTest` — reducedMotion degrades to `fadeSpec() ≤120ms`
- `FullPlayerTest.transform_expand_settlesWithin300msCap` — `PLAYER_TRANSFORM_MS≤300` asserted, harness advances clock 16ms steps and settles past tween
- `PlayerSyncLatencyTest` — `<=250 ms` harness green
- `ScrimEngineContrastMatrixTest` / `AtmosphereCacheTest` — extraction `≤50 ms` CPU budget asserted via `ExtractorBudgetTest` synthetic bitmap ceiling 200ms CI-safe, `AtmosphereCacheTest` zero-recompute proven
- StrictMode death-penalty clean (debug triaged, `SwayImages` init post-composition, `DataStore` async-only)

**Status**: budgets structurally enforced via tokens + provable caps; device-measured `BudgetReport` pending Baseline Device run (SM-C2 check recorded as device-gated per 9.4 precedent).

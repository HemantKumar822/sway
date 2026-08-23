---
title: 'Story 5.2 - Read-time validation layer'
type: 'feature'
created: '2026-08-23'
status: 'done'
review_loop_iteration: 0
baseline_commit: 4f7f16d33b21b9188f4f568135b91d86f2e25f9c
context:
  - '{project-root}/_bmad-output/planning-artifacts/epics-and-stories.md (Story 5.2)'
  - '{project-root}/_bmad-output/planning-artifacts/architecture.md (AD-7 defense layer 1; NFR-3; P-5)'
  - '{project-root}/_bmad-output/implementation-artifacts/epic-4-context.md (prefetch age cap folding)'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** The engine's prefetch age cap still validates held renditions with margin 0 (`isExpiredAt(now)`), so a URL with 30 seconds of life left passes as "fresh" and lands on the player — expiry becomes an audible error. AD-7 defense layer 1 requires every read of a held stream URL to demand at least 5 minutes of remaining lifetime, and epic-4-context mandates the existing prefetch age cap folds into that single check rather than surviving as a second mechanism.

**Approach:** Extend `JitPolicy` (:playback) with ONE named-margin read-time validator (`isReadValid(audio, now)` over `ResolvedAudio.isExpiredAt(now, READ_MARGIN_MS)`, constant P-5-tunable = 5 min). Route EVERY consumption of a held `ResolvedAudio` through it: prefetched cache hits AND fresh `resolveAudio` results (start swap + JIT transitions). A held/prefetched rendition failing the check is discarded + `invalidate(trackId)` + fresh resolve with `AudioRequest.refresh()` (forceRefresh) BEFORE play; a freshly-resolved marginal URL earns exactly ONE bounded forced revalidation (best-effort consume if the retry cannot beat it — layer 2 remains the backstop for real mid-play death). Single-flight JIT loop structure untouched.

## Boundaries & Constraints

**Always:**
- Exactly ONE validity mechanism in `:playback`: the single `JitPolicy` check governs cached and prefetched URLs alike (and fresh results reuse the same predicate); no second age-cap survives.
- Margin lives behind one named constant (`READ_MARGIN_MS`), KDoc-cited to NFR-3/P-5 as the tuning point.
- Renewal after a failed check calls `resolver.invalidate(trackId)` then resolves with `forceRefresh = true`.
- Revalidation stays bounded: at most one forced retry per consumption attempt; the single-flight worker loop and FR-12 exactly-one up-front budget are preserved on all happy paths.
- All failure paths stay typed (`FailedTrack`/`SwayResult.Failure`) — never throw across the session boundary.

**Ask First:** N/A — headless run; engineering decisions recorded in Design Notes.

**Never:** No changes outside `:playback` except docs/spec/sprint-status (core:model helpers already sufficient — `isExpiredAt(epochMs, marginMs)` exists); no SettingsRepository/quality wiring (5.1 explicitly deferred consumer wiring to later epics; renewal requests use AUTO default); no :catalog resolver internals; no error-triggered renewal/watchdog layers (5.3/5.4); no UI work.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| AC1: prefetched URL expiring in 4 min | cache hit, `expiresAt = now+4min` (< margin) | Discarded; `invalidate(x)` + `resolveAudio(x, forceRefresh=true)` BEFORE play; replacement counted by double; dying URL never reaches player | N/A |
| AC2: URL expiring in 10 min | cache hit, `expiresAt = now+10min` (> margin) | Consumed as-is; zero extra `resolveAudio` | N/A |
| Boundary: exactly at margin | `expiresAt = now + READ_MARGIN_MS` | `isExpiredAt` inclusive law (`now + margin >= expires`) => INVALID => renewed | N/A |
| Boundary table minus/plus | `margin-1ms` invalid / `margin+1ms` valid / far-future valid / null invalid | Pure policy table green | N/A |
| Renewal request shape | stale cache hit triggers renewal | Recorded request carries `forceRefresh == true`; `invalidatedIds` contains the id | N/A |
| Single flight under revalidation | duplicate transitions while renewal resolve gated | Exactly one `resolveAudio` for the id (coalesced); loop absorbs drift | Duplicates skipped |
| Fresh resolve returns marginal URL | resolver Success with `expiresAt = now+2min` | Exactly ONE forced refresh retry; recovered long-lived URL used | Bounded: never hot-loops |
| Fresh resolve always marginal | both attempts < margin | Best-effort: second (freshest) URL plays; NO typed failure (URL works now; layer 2 owns mid-play death); attempts bounded at 2 | Never throws |
| Start-swap marginal result | up-front resolve returns < margin | Same single-check revalidation before items land on player | Bounded |
| Renewal resolve Failure | forceRefresh resolve fails typed | Typed `FailedTrack`, placeholder left retryable on next transition (existing 4.4 semantics) | Typed value |
| Prior behavior regression | fresh URLs > margin everywhere | Counts identical to 4.4 suite (FR-12 proof untouched) | N/A |

</frozen-after-approval>

## Code Map

- `playback/src/main/kotlin/com/sway/playback/JitResolveEngine.kt` -- JitPolicy: `isPrefetchUsable` REPLACED by `isReadValid(audio, nowEpochMs)` over named `READ_MARGIN_MS` (fold point — the one mechanism); engine: `resolveOrConsumeCache` generalized to `resolveForUse(sourceId)` (cache-first read-validation -> invalidate+forceRefresh renewal -> bounded single revalidation of fresh-marginal results) shared by BOTH `resolveStartSwap` and the JIT worker; KDoc updates citing AD-7 layer 1/NFR-3.
- `playback/src/test/kotlin/com/sway/playback/JitResolveEngineTest.kt` -- policy helper test flips to margin semantics (boundary now==expires-at-margin invalid); existing age-cap/stale tests keep passing unchanged (their fixtures sit beyond/below margin already).
- `playback/src/test/kotlin/com/sway/playback/ReadTimeValidationTest.kt` -- NEW Robolectric suite: AC1/AC2 via doubles, boundary tables (pure + engine-level exactly-margin), renewal forceRefresh+invalidate assertions, single-flight under revalidation, fresh-marginal bounded recovery + always-marginal best-effort, start-swap shared check.
- `_bmad-output/implementation-artifacts/sprint-status.md` -- row 5.2 status + Evidence log entry at completion.
- `_bmad-output/implementation-artifacts/spec-5-2-read-time-validation-layer.md` -- this spec; status -> done at completion.

## Tasks & Acceptance

**Execution:**
- [x] `JitPolicy` fold: delete `isPrefetchUsable`, add `READ_MARGIN_MS` (P-5-tunable named constant) + `isReadValid` delegating to `ResolvedAudio.isExpiredAt(now, READ_MARGIN_MS)`; update object KDoc (AD-7 layer 1, NFR-3).
- [x] `JitResolveEngine.resolveForUse`: single read path — validated cache consumption, else `invalidate` + `AudioRequest.refresh()` renewal when a stale entry was held, else default resolve; fresh Success re-checked by the same predicate with exactly one bounded forced retry (best-effort outcome); wired into `resolveStartSwap` + JIT worker; typed failures preserved.
- [x] `ReadTimeValidationTest.kt` NEW: full I/O matrix as Robolectric double-driven tests incl. request-shape capture (`forceRefresh`) and invalidate counting.
- [x] `JitResolveEngineTest.kt`: policy boundary asserts updated to margin law; all prior suites stay green.

**Acceptance Criteria:**
- Given a prefetched URL expiring in 4 minutes, when its item transitions to current, then it is discarded and a fresh resolve occurs before play (double counts replacement; dying URI never set on the player).
- Given a URL expiring in 10 minutes, when used, then play proceeds without re-resolve.
- The prefetch age cap adds no second mechanism — it folds into this one check (single `JitPolicy` predicate; grep shows one validity entry point governing cache + fresh reads).
- Margin constant named and P-5-tunable; boundary-table unit tests cover margin minus/plus/exactly via doubles.

## Spec Change Log

## Completion Record (2026-08-23)

- `:playback:testDebugUnitTest` 61/61 green = 52 prior (13 JitResolveEngine incl. margin-law policy table, 1 timing harness, 13 PlayerConnection, 21 QueueBuilder, 4 SwayPlaybackService) + 9 NEW ReadTimeValidationTest (AC1 4-min prefetch discarded + invalidate + forceRefresh renewal before play + dying URL never on player; AC2 10-min consumed zero extra resolve; exactly-at-margin renewed via doubles; renewal-failure strict discard typed placeholder retryable; gated-renewal duplicates collapse to one resolve single-flight intact; fresh-marginal one bounded forced revalidation recovers long-lived URL; always-marginal bounded at 2 attempts best-effort second answer plays no hot-loop no failure; start-swap marginal revalidated before queue loads; service-seam wired engine renews dying entry).
- `:core:model` 118, `:catalog` 123 (2 liveSmoke skipped), `:core:data` 8 — all unchanged green.
- `scripts/check_placeholder_scheme.sh` exit 0; `scripts/check_module_edges.sh` exit 0; `:app:assembleDebug` BUILD SUCCESSFUL.

## Design Notes

- **Fold, not parallel:** epic-4-context says "the prefetch age cap folds into their single read-time check rather than adding a second mechanism". `isPrefetchUsable(margin 0)` is DELETED; `isReadValid` is the only validity predicate in `:playback` and consults `ResolvedAudio.isExpiredAt(now, READ_MARGIN_MS)` — the core:model helper built for exactly this in 2.x. One grep-able entry point proves AC3.
- **Fresh results also pass the check.** "Validity check at use" applies to anything about to touch the player, not just cache hits. A resolver returning a <5-min-fresh URL (pathological but possible) gets exactly ONE `forceRefresh` retry; if the retry yields a valid URL it wins, otherwise the freshest marginal URL still plays. Rationale: failing the track outright would itself be an audible error — the exact thing layer 1 exists to prevent — while layer 2 (5.3) remains the backstop for genuine mid-play 403/410. Attempts are hard-bounded at 2 so a pathological resolver can neither hot-loop the worker nor break FR-12's observable budget on happy paths (fixtures with healthy margins count identically to the 4.4 suite).
- **Strict discard for stale CACHE entries, best-effort for marginal FRESH results.** A cache entry that failed the check was already superseded (unknown true remaining life; we purge resolver state for it too via `invalidate`), so falling back to it would contradict the discard mandate. A fresh-but-marginal resolve is the upstream's best current answer — audibly-alive-now beats a typed failure.
- **Renewal requests carry `forceRefresh = true` via the existing `AudioRequest.refresh()` factory** (quality stays AUTO): the resolver must bypass its own LRU rather than hand back the same dying rendition. Quality injection into ANY AudioRequest belongs to the consumer epics per 5.1's recorded decision — story 5.2's traces (FR-13 read-time clause/NFR-3/AR-6) say nothing about settings, so `SettingsRepository` stays unwired here.
- **Single-flight intact:** revalidation happens INSIDE the worker's single suspend step (`resolveForUse`), so duplicate transitions still coalesce onto the one running job; the worker's `lastAttempted` loop guard makes the bounded retry impossible to re-enter.
- **Inclusive boundary law comes from core:model:** `isExpiredAt` uses `now + margin >= expires`, so "exactly 5 min left" is treated as expired/renewed. Tests pin that reading (minus=invalid, plus=valid, exactly=invalid) instead of inventing a second comparison.
- **No core:model changes needed:** `isExpiredAt(epochMs, marginMs)` already exists (PortsContractTest exercises margins); adding another wrapper would duplicate vocabulary AD-7 bans.

## Verification

**Commands:**
- `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat :playback:testDebugUnitTest` -- expected: prior 52 + new suites all green (exact count reported at completion)
- `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat :core:model:test :catalog:testDebugUnitTest :core:data:testDebugUnitTest` -- expected: 118 / 123 / 8 unchanged green
- `"C:\Program Files\Git\bin\bash.exe" scripts/check_placeholder_scheme.sh && "C:\Program Files\Git\bin\bash.exe" scripts/check_module_edges.sh` -- expected: exit 0 both
- `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat :app:assembleDebug` -- expected: BUILD SUCCESSFUL

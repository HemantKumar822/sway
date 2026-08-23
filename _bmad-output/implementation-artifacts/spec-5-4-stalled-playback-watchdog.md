---
title: 'Story 5.4 - Stalled-playback watchdog'
type: 'feature'
created: '2026-08-23'
status: 'done'
review_loop_iteration: 1
baseline_commit: 43b5241589232dbef5d31ff4af3a44f93e5e9435
context:
  - '{project-root}/_bmad-output/planning-artifacts/epics-and-stories.md (Story 5.4)'
  - '{project-root}/_bmad-output/planning-artifacts/prd.md (FR-14; P-5 assumptions register)'
  - '{project-root}/_bmad-output/planning-artifacts/architecture.md (AD-7 defense layer 3; NFR-3; SM-2)'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-5-3-error-renewal-position-resume.md (layer-2 precedent, hermetic test pattern)'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Layers 1–2 (stories 5.2/5.3) cover stale URLs and mid-play expiry errors, but a stream can die SILENTLY: the player parks in `STATE_BUFFERING` with `playWhenReady=true` and a frozen position — no error ever fires, and the session hangs forever. AD-7 defense layer 3 requires a ticker-driven watchdog in `:playback`: a brief stall (position frozen in BUFFERING beyond the P-5 soft threshold, 3 s) earns ONE downscale replay (re-resolve at a lower bitrate target); a sustained stall (frozen beyond the P-5 hard threshold, 15 s cumulative) earns a full stream rebuild (invalidate + `forceRefresh` resolve); repeated rebuild failure skips to the next Queue item and surfaces the typed category on `PlayerUiState.failedTrack`. FR-14 COMPLETES HERE; NFR-3's three resilience layers become COMPLETE.

**Approach:** Extend `JitResolveEngine` (:playback, single owner of recovery/resolution vocabulary) with a watchdog: pure escalation law in `JitPolicy` (named P-5 constants `WATCHDOG_SOFT_STALL_MS=3000`, `WATCHDOG_HARD_STALL_MS=15000`, `WATCHDOG_TICK_MS`, `WATCHDOG_ACTION_SPACING_MS`, `MAX_REBUILDS_PER_EPISODE`, `DOWNSCALE_QUALITY`; pure `watchdogAction(...)` ladder + suppression predicate). A coroutines ticker (`delay` loop on the engine scope = service lifecycle, no app-wide broadcast) samples the player every tick via an internal seam `onWatchdogTick(nowMs)` so tests drive ticks deterministically with synthetic timestamps (no virtual-time/Robolectric-looper coupling). Stall accounting: eligible tick = released-no + recovery-in-flight-no + any-layer-2-renewal-in-flight-no + playWhenReady + `STATE_BUFFERING` + RESOLVED current item (placeholders stay owned by the JIT worker); a live-position change resets the freeze baseline (progress law). Escalation per episode (memory reset only on item transition or successful-progress observation): Downscale once at >=SOFT, Rebuild at >=HARD up to `MAX_REBUILDS_PER_EPISODE`, Skip when rebuild attempts are exhausted at >=HARD — every action spaced >=`WATCHDOG_ACTION_SPACING_MS` apart so each intervention gets an observation window. Recovery rides the EXISTING renewal mechanics (invalidate + typed resolve + `replaceMediaItem` mediaId scan + `seekTo(captured)` + prepare + conditional play). Skip publishes `FailedTrack(current item, SwayError.UpstreamUnavailable)` through the existing slot glue and advances via `seekToNextMediaItem` (or pauses when no next item exists — honest stop). Single-owner rule enforced BOTH directions: watchdog yields to in-flight layer-2 renewals; `handlePlayerError` defers RETRYABLE renewals while a watchdog action is in flight (fatal classes still surface immediately). Media3's StuckPlayerException backstop: the class does NOT exist on our media3 1.11.0 classpath (verified at build time per architecture's clause) — timeout-family codes (`ERROR_CODE_TIMEOUT`) fall outside the 2000..2999 renewal window by design, i.e. the platform backstop logs-and-yields to our ticker policy, which remains authoritative.

## Boundaries & Constraints

**Always:**
- Thresholds are named constants matching P-5 exactly: soft 3000 ms, hard 15000 ms (NFR-3 escalation bounds).
- Watchdog acts ONLY while `playWhenReady && playbackState == STATE_BUFFERING` and the CURRENT item holds a RESOLVED rendition — never during user pause, never during the idle self-stop flow (idle/eneded states are outside the stall definition), never on placeholders.
- Exactly ONE watchdog recovery pipeline at a time; suppressed entirely while any layer-2 renewal is in flight; layer-2 retryable renewals deferred while a watchdog action is in flight; fatal error classes always surface immediately.
- Every recovery action applies through the shared apply sequence (mediaId scan -> seek captured position -> prepare -> conditional play) — identity preserved, resume honored, paused users stay paused.
- Skip publishes a typed `FailedTrack` (category consumable by SongRow failed variant / queue rows) BEFORE advancing; advancing prefers `seekToNextMediaItem`, falls back to `pause()` on last-item (documented judgment).
- Transition timing excluded from stall accounting: accumulation resets on `onMediaItemTransition` (normal gapless transitions never accrue debt).
- Every action travels as a value; the watchdog never throws across the session boundary.

**Ask First:** N/A — headless run; engineering decisions recorded in Design Notes.

**Never:** No changes outside `:playback` main/test sources plus docs/spec/sprint-status (no :core:model, no :catalog, no UI work); no auto-skip beyond the story's repeated-rebuild-failure clause (one skip per exhausted ladder, never a skip storm); no app-wide ticking broadcast (ticker lives on the engine scope = service lifecycle); no SettingsRepository wiring (downscale targets `Quality.LOW` explicitly; quality preference injection stays deferred per 5.1 decision).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| AC1: soft stall 3.5 s | resolved current item, BUFFERING, playWhenReady, position frozen across ticks to 3500 ms | Exactly ONE downscale replay resolve (`Quality.LOW` + forceRefresh); captured position restored; identity/play-intent preserved; zero typed failure | Bounded |
| AC1b: continued short stall | further frozen ticks below HARD after the downscale | Zero additional resolves (downgrade latch) until HARD | Bounded |
| AC2: sustained stall 16 s | frozen continues past 15000 ms cumulative | Full stream rebuild: invalidate + `AudioRequest.refresh()` resolve; fresh URL swapped in place | Bounded |
| AC3: repeated rebuild failure | both rebuild attempts (MAX_REBUILDS_PER_EPISODE=2) return resolver Failure | Skip to next queue item + typed `UpstreamUnavailable` on uiState.failedTrack; NO further resolves on subsequent ticks (hot-loop guard); no crash | Typed value |
| Rebuild succeeds, stream still dead | rebuild #1/#2 Success but player stays frozen in BUFFERING | Actions spaced >=3000 ms; third crossing with exhausted budget -> Skip | Bounded |
| Healthy short buffering | frozen < 3000 ms, or position advances between tick batches | Zero watchdog activity; baseline resets on progress | Silent |
| User paused | playWhenReady=false, ticks far past thresholds | Zero watchdog activity | Silent |
| Placeholder current item | JIT window / failed start resolve | Zero watchdog activity (JIT worker owns) | Silent |
| Layer-2 renewal in flight | gated renewal job active, ticks past HARD | Zero watchdog actions while gated; ownership HANDS BACK after renewal completes (fresh ladder allowed) | Coalesced |
| Watchdog action in flight + retryable error | gated downscale/rebuild job, `handlePlayerError(2004)` | Renewal DEFERRED (zero extra resolves); fatal codes (4001) still surface immediately | Single owner |
| Gapless transition mid-accounting | 2500 ms accumulated, then transition | Debt discarded; new item starts at zero (transition excluded) | Silent |
| Recovery resets | successful progress observed (noteSuccessfulProgress) after an action | Tier memory cleared: next stall earns a FRESH ladder | N/A |
| Last-item skip | exhausted ladder on final queue item | Typed failure published; `pause()` (no next item to advance to; honest stop) | Typed value |
| Forced-stall record | scripted ladder sweep | Record line printed to test output (SM-2-style artifact) | N/A |

</frozen-after-approval>

## Code Map

- `playback/src/main/kotlin/com/sway/playback/JitPolicy.kt` -- NEW FILE (NFR-7 LOC split, 2026-08-23): pure law object moved out of `JitResolveEngine.kt` verbatim -- `WATCHDOG_SOFT_STALL_MS`/`WATCHDOG_HARD_STALL_MS`/`WATCHDOG_TICK_MS`/`WATCHDOG_ACTION_SPACING_MS`/`MAX_REBUILDS_PER_EPISODE`/`DOWNSCALE_QUALITY`, `WatchdogAction` enum, `watchdogAction(stallFrozenMs, downgradeAttempted, rebuildAttempts, sinceLastActionMs)` pure ladder, `isStallCandidate(...)`, `isWatchdogSuppressed(...)` predicate (plus all pre-5.4 policy: read-time margin, source-error window, resume clamp, renewal budget).
- `playback/src/main/kotlin/com/sway/playback/JitResolveEngine.kt` -- engine facade (831 LOC after split): watchdog episode state (freeze baseline, last observed position, downgrade latch, rebuild counter, last-action timestamp, episode mediaId), `startWatchdog()` production ticker (engine-scope `delay` loop, single-flight), `onWatchdogTick(nowMs)` internal seam (gates -> progress law -> pure ladder -> bounded recovery job), recovery job riding invalidate+typed-resolve+apply sequence, `skipStalledCurrent()` (publish + advance/pause), accounting resets wired into `onMediaItemTransition`/`noteSuccessfulProgress`, reverse single-owner guard in `handlePlayerError`; KDoc citing FR-14/AD-7 layer 3/NFR-3/P-5.
- `playback/src/main/kotlin/com/sway/playback/SwayPlaybackService.kt` -- `onCreate` arms `resolveEngine?.startWatchdog()` (service-lifecycle scoping); KDoc story-5.4 note.
- `playback/src/test/kotlin/com/sway/playback/StalledPlaybackWatchdogTest.kt` -- NEW Robolectric suite reusing the hermetic blocking-DataSource player pattern: full I/O matrix, pure ladder boundary tables, facade-slot surfacing, single-owner both directions, service lifecycle arming, forced-stall record emission.
- `_bmad-output/implementation-artifacts/sprint-status.md` -- row 5.4 status + Evidence log entry noting NFR-3 COMPLETE (layers 1-3) at completion.
- `_bmad-output/implementation-artifacts/spec-5-4-stalled-playback-watchdog.md` -- this spec; status -> done at completion.

## Tasks & Acceptance

**Execution:**
- [x] `JitPolicy`: named P-5 constants + pure `WatchdogAction` ladder + suppression predicate with boundary-table coverage.
- [x] `JitResolveEngine`: ticker lifecycle, tick gating, freeze-baseline accounting, escalation execution via shared renewal mechanics, skip-with-typed-reason + advance, accounting/memory resets, bidirectional single-owner guards.
- [x] `SwayPlaybackService`: arm the watchdog on the engine at creation.
- [x] `StalledPlaybackWatchdogTest.kt` NEW: soft-stall single downscale, hard rebuild, repeated-failure skip with typed reason + hot-loop guard, rebuild-success-but-still-dead spacing ladder, healthy/paused/placeholder silence, renewal-in-flight suppression + handback, watchdog-in-flight defers retryable errors (fatal still surfaces), transition/progress resets, last-item pause fallback, constants-vs-P-5 pinning, service lifecycle, forced-stall record.

**Acceptance Criteria:**
- Given injected stalls at 3.5 s / 16 s / repeated-rebuild-failure, when each scenario runs, then recovery/downscale, full rebuild, and skip-with-typed-reason occur respectively within stated bounds (forced-stall suite, record emitted).
- When a track is skipped, then the failed Song carries its reason category consumable by SongRow's failed variant and queue rows (`uiState.failedTrack` via the existing slot glue).
- The watchdog never fires during normal gapless transition (transition timing excluded from stall accounting), during user pause/idle, or while another recovery pipeline owns the item; it resets cleanly on READY/progress.
- Thresholds match the P-5 assumptions-register values (3 s / 15 s) as named constants.

## Spec Change Log

- 2026-08-23 (implementation): `JitPolicy` extracted from `JitResolveEngine.kt` into
  its own file `playback/src/main/kotlin/com/sway/playback/JitPolicy.kt` — the WIP
  engine reached 1030 LOC, violating NFR-7's hard 1000-line CI budget; architecture
  AD-5/C-7 prescribes exactly this ("facades delegate to focused sub-services when a
  concern grows"). Zero behavior change: pure policy object moved verbatim (plus its
  story-5.4 additions), engine keeps all stateful logic. Code Map updated below.

## Completion Record

- Implemented: 2026-08-23, single session completing an interrupted run's sound WIP.
- Files: `playback/src/main/kotlin/com/sway/playback/JitResolveEngine.kt` (watchdog
  execution, 831 LOC), `playback/src/main/kotlin/com/sway/playback/JitPolicy.kt` NEW
  (pure law, 208 LOC), `playback/src/main/kotlin/com/sway/playback/SwayPlaybackService.kt`
  (arms ticker), `playback/src/test/kotlin/com/sway/playback/StalledPlaybackWatchdogTest.kt`
  NEW — see sprint-status Evidence log for full verification evidence.

## Design Notes

1. **Ladder shape vs task-brief shorthand:** the story brief summarized tier-1 as a
   generic "renewal path" and hard tier as "one rebuild attempt max"; the AUTHORITATIVE
   texts (epics Story 5.4 tasks, FR-14, architecture AD-7 layer 3, research phase-4 F4)
   all specify downscale-replay at soft, FULL stream rebuild at sustained (repeated
   rebuild failure => skip), which is what was built: `MAX_REBUILDS_PER_EPISODE=2`
   mirrors layer 2's renewal bound (NFR-3 anti-hot-loop symmetry). Research F4's
   "stage 2 gives a second 15 s window" behavior maps to the action-spacing gate.
2. **First-sample sentinel law:** `lastTickPositionMs` starts at `Long.MIN_VALUE`, so
   the first tick after any full episode reset always takes the progress branch and
   re-baselines BEFORE the ladder runs. This makes synthetic-timestamp tests and the
   epoch-based production baseline (`resetWatchdogEpisode` uses `clock()`) coherent —
   and it is why every stall scenario in the suite primes with a `tick 0` sample.
3. **Same-mediaId transitions continue the episode:** `replaceMediaItem` fires
   `onMediaItemTransition` with an unchanged mediaId; treating it as "new item" would
   erase earned escalation memory on our own recovery swaps. Genuinely different ids
   reset the ladder; cumulative frozen time + spacing gate give each fresh rendition
   its observation window (research F4: watchdog's own replays excluded from resets).
4. **Silent mid-ladder failures are deliberate:** a failed downscale/rebuild resolve
   publishes nothing — the SKIP tier owns typed surfacing exactly once
   (`UpstreamUnavailable`), preventing double-failure noise; layer-2 errors keep their
   own immediate surfacing path untouched.
5. **Last-item honest stop:** no next item => `pause()` rather than looping on a dead
   tail or stopping the service (idle self-stop flow remains 5.3-owned). Documented
   judgment call per spec matrix row.
6. **StuckPlayerException backstop verified absent:** inspected the resolved
   `media3-exoplayer-1.11.0.aar` classes.jar at build time — no
   `StuckPlayerException`/`StuckGeneratingException` class exists, matching the spec's
   build-time-verification clause; timeout-family error codes fall outside the
   2000..2999 renewal window by design, so the platform backstop logs-and-yields to
   this ticker policy, which remains authoritative.
7. **LOC budget compliance (NFR-7):** WIP engine hit 1030 lines; `JitPolicy` moved to
   its own file (architecture AD-5/C-7 sub-services clause) => engine 831 / policy 208 /
   test suite 629 — all far under the hard 1000-line CI budget.

## Verification

**Commands & results (2026-08-23):**

- `$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat :playback:testDebugUnitTest` -- BUILD SUCCESSFUL; **87 tests** (73 prior + **14 new StalledPlaybackWatchdogTest**, 0 failures), incl. forced-stall record emitted:
  `FORCED-STALL RECORD (story 5.4): SOFT@3.5s->DOWNSCALE 4/4 | HARD@16s->REBUILD 4/4 | REPEATED-FAILURE->SKIP-TYPED 4/4 | P-5 bounds 3000/15000 ms` (SM-2-style artifact).
- `$env:JAVA_HOME=...; .\gradlew.bat :core:model:test :catalog:testDebugUnitTest :core:data:testDebugUnitTest` -- BUILD SUCCESSFUL; **118 / 123 / 8** unchanged green.
- `"C:\Program Files\Git\bin\bash.exe" scripts/check_placeholder_scheme.sh` -- exit 0 ("Placeholder scheme audit OK").
- `"C:\Program Files\Git\bin\bash.exe" scripts/check_module_edges.sh` -- exit 0 ("Edge audit OK").
- `$env:JAVA_HOME=...; .\gradlew.bat :app:assembleDebug` -- BUILD SUCCESSFUL.

**Self-review loop (iteration 1):** adversarial pass found one test-harness defect —
`sustainedStall_rebuildSucceeds_butStreamStillDead_ladderStillSkips` skipped the
conventional `tick 0` priming sample, so its first-ever sample took the progress
branch (sentinel law, Design Note 2) and the ladder started one threshold late
(forcedKeys 2 != 3). Engine behavior judged correct; test fixed by priming; full
suite re-run green. Second pass verified suppression ordering (suppression gate runs
before state reads), release() cancellation safety (typed-value discipline holds under
cancel), hot-loop bounds (spacing + per-episode budgets + post-skip silence all
tested), and LOC budgets post-split.

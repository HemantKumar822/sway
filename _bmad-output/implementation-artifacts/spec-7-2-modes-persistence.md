---
title: 'Story 7.2 - Modes persistence'
type: 'feature'
created: '2026-08-24'
status: 'done'
review_loop_iteration: 1
baseline_commit: 48330ee
context:
  - '{project-root}/_bmad-output/planning-artifacts/epics-and-stories.md (Story 7.2)'
  - '{project-root}/_bmad-output/planning-artifacts/prd.md (FR-11 persistence clause; FR-25 restore spirit)'
  - '{project-root}/_bmad-output/planning-artifacts/architecture.md (AD-6 rule 5 facade modes; AD-10 no synchronous startup reads; AR-7 settings conventions)'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-5-1-audio-quality-settings-repository.md (SettingsRepository birth; Hilt-binding deferral)'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Story 7.1 made shuffle/repeat REAL but VOLATILE: a process death loses the listener's modes, violating FR-11's persistence clause ("the last-used modes persist across app launches"). The SettingsRepository from 5.1 owns exactly one key (`playback.audio_quality`); nothing persists `playback.shuffle` / `playback.repeat`, nothing restores them at service start before the first queue build, and no restored value reaches PlayerUiState.

**Approach:** Thin persistence along the established seams — zero new mechanisms:
1. **`:core:data`**: SettingsRepository gains `shuffleEnabled: Flow<Boolean>` / `repeatMode: Flow<RepeatMode>` + suspend setters over the SAME shared settings file (keys `playback.shuffle`, `playback.repeat`); strict enum-name parse; corrupt/wrong-typed values degrade to documented defaults (C-8 lesson). The canonical `RepeatMode` enum moves to `:core:model` so persistence needs no duplicate declaration (AD-7) — playback keeps only the media3-int mapping.
2. **`:playback` service**: optional settings seam (mirrors `streamResolverForTest`; production DI graph wiring stays deferred to app-assembly epic per AR-3 precedent). onCreate launches an ASYNC restore (AD-10) applying the persisted repeat mode onto the player BEFORE any queue can be built — session commands arrive later on the same looper.
3. **`:playback` facade**: `attachSettings(repo)` restores the persisted shuffle flag into its mirror (and reorders a live queue if one exists); every mode command writes through (fire-and-forget; DataStore serialization gives last-write-wins). A queue built while the restored flag is ON lands shuffled around its start item.

## Boundaries & Constraints

**Always:** async reads only (AD-10); defaults on corruption never throw at collectors; ONE settings file (AR-7); RepeatMode canonical in core:model (AD-7).
**Never:** no synchronous startup reads; no UI work (E12 completes the toggle UX); no session/queue persistence (7.3 owns Room); no changes to resolution/watchdog machinery.

## I/O & Edge-Case Matrix

| Scenario | Input | Expected | Error Handling |
|----------|-------|----------|----------------|
| AC1: repeat-one survives death | repo seeded ONE; fresh service created | player.repeatMode==ONE at init with ZERO queue items; first queue build sees it; uiState mirrors | Defensive |
| AC2: defaults when unset | fresh store | shuffle=false, repeat=OFF | Silent |
| AC3: round-trips | set each enum/shuffle both ways | reads back distinctly | Bounded |
| AC4: corrupt values | "BOGUS" name + wrong-typed bool under same key name | OFF/false emitted, never throws; next valid write recovers | Typed |
| AC5: IO failure reading | failing store double | defaults for BOTH keys, collectors unharmed | Silent |
| AC6: rapid writes | N quick cycles | persisted == final uiState mode (last-write-wins via DataStore edit serialization) | Bounded |
| AC7: restored shuffle meets first queue | shuffle=true persisted; queue built on fresh stack | queue lands reordered around start/current deterministically; current identity preserved | Measured |

</frozen-after-approval>

## Code Map

- `core/model/src/main/kotlin/com/sway/core/model/RepeatMode.kt` -- NEW canonical home (moved from playback; playback imports it).
- `core/data/src/main/kotlin/com/sway/core/data/SettingsRepository.kt` + `DataStoreSettingsRepository.kt` -- two new keys/flows/setters per matrix.
- `playback/src/main/kotlin/com/sway/playback/SwayPlaybackService.kt` -- `settingsForTest` seam + serviceScope + async repeat-mode restore in onCreate; cancelled in onDestroy.
- `playback/src/main/kotlin/com/sway/playback/PlayerConnection.kt` -- `internal attachSettings(repo)`; write-through in setShuffleEnabled/cycleRepeatMode; `applyShuffleIfArmed()` after queue ingestion.
- `playback/src/test/kotlin/com/sway/playback/ModesPersistenceTest.kt` -- NEW: init-order, write-on-change, mirror, restored-shuffle-meets-first-build, last-write-wins (InMemorySettings contract fake — DataStore machinery is :core:data's suite territory).
- `core/data/src/test/kotlin/com/sway/core/data/DataStoreSettingsRepositoryTest.kt` -- EXTENDED: 6 new mode-persistence tests (AC2–AC6 incl. process-death restart semantics).
- `_bmad-output/implementation-artifacts/sprint-status.md` + this spec -- status/evidence at completion.

## Tasks & Acceptance

**Execution:**
- [ ] RepeatMode moved to core:model; all references updated.
- [ ] Settings keys + flows + setters with strict parse & degradation laws.
- [ ] Service async restore pre-queue; facade write-through + attach-restore + armed-shuffle-on-build.
- [ ] Suites: :core:data 8→14, :playback +ModesPersistenceTest(5); all prior suites unchanged-green.

**Acceptance Criteria:**
- Given repeat-one left active, when the process dies and relaunches, then the service initializes with repeat-one (no synchronous startup read) and PlayerUiState reflects it (AC1).
- When modes change rapidly, then last-write-wins persists correctly (AC6); corrupt/unreadable storage degrades to defaults without crashing collectors (AC4/AC5).

## Verification

**Commands & results (2026-08-24):** all suites green — :playback **127** (122 prior + 5 ModesPersistenceTest), :core:data **14** (8 + 6 mode tests), :app 11 / :core:model 118 / :catalog 123 unchanged; placeholder + edge audits exit 0; :app:assembleDebug BUILD SUCCESSFUL.

**Self-review loop (iteration 1):** fixed pre-run: DataStore-under-Main deadlock risk in test fixtures replaced by a contract-level InMemorySettings fake (machinery stays in :core:data's suite); suspend-call-outside-coroutine drafts corrected; RepeatMode import fallout from the core:model move swept across affected suites.

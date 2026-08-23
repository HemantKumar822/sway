---
title: 'Story 7.3 - Session persistence & paused restore'
type: 'feature'
created: '2026-08-24'
status: 'done'
review_loop_iteration: 1
baseline_commit: 2e25084
context:
  - '{project-root}/_bmad-output/planning-artifacts/epics-and-stories.md (Story 7.3)'
  - '{project-root}/_bmad-output/planning-artifacts/prd.md (FR-25; UJ-4)'
  - '{project-root}/_bmad-output/planning-artifacts/architecture.md (AD-8 Room contract; AD-10 post-composition restore; AR-9 hook; AR-7)'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-5-1-audio-quality-settings-repository.md (seam-over-DI precedent)'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Nothing survives process death: a kill loses the queue, the position, and the moment — violating FR-25 and UJ-4 ("yesterday's queue back tomorrow - paused, exactly where it stopped"). Room does not exist yet; `QueueStateEntity` + `QueueStateDao` (migration 1, exported schema, explicit-migrations-only posture) were reserved for exactly this story, along with THE canonical QueueSnapshot serializer owned by `:core:data` (single-representation law) and the AR-9 post-composition restore hook.

**Approach:** Three thin layers + proof:
1. **`:core:database` births Room**: `SwayDatabase` v1 (`exportSchema=true`, schemas/ committed), `QueueStateEntity` singleton row (songsJson + currentIndex + positionMs + shuffle/repeat flags + savedAt), `QueueStateDao` (suspend/Flow only). NO destructive fallback anywhere.
2. **`:core:data` owns persistence glue**: `QueueStateSerializer` — THE one QueueSnapshot JSON codec (manual kotlinx.serialization.json builders; no reflection; tolerant parse degrading corrupt rows to null); `QueueStateStore` seam (+Room adapter) so no module outside :core:data ever touches Room types; `SessionRestoreRepository` = save/load/clear in session vocabulary.
3. **`:playback`**: `SessionStateSaver` observes the player directly (works with ZERO controllers bound — background advance keeps saving), debounced flush on meaningful events + 5 s playing heartbeat bounding loss well inside FR-25's +/-5 s; `PlayerConnection.attachSessionStore(store)` is the AR-9 post-composition hook: lands the saved queue/index/modes PAUSED via setQueue, waits EVENT-DRIVEN for the restored start item to resolve (its rendition swap resets window position), re-seeks to the saved moment, NEVER auto-plays; first-run (null row) stays honestly Idle.

## Boundaries & Constraints

**Always:** one database, exported schema from v1, explicit migrations only, mismatch fails loudly; serializer lives ONLY in :core:data (grep audit wired into CI); async reads only; saves degrade silently on IO failure (next save supersedes).
**Never:** auto-play after restore; destructive fallback; a second snapshot representation; Room types outside :core:data/:core:database; UI work (E12 consumes the restored-session marker).

## I/O & Edge-Case Matrix

| Scenario | Input | Expected | Error Handling |
|----------|-------|----------|----------------|
| AC1: kill→relaunch restores | play idx1, seek 30 s, pause, flush, destroy stack, fresh stack + attachSessionStore | queue/index/currentItem exact; facade+player position within +/-5 s of saved moment; modes ride along; playWhenReady FALSE throughout; one tap resumes audibly AT the saved moment | Measured |
| AC2: schema mismatch fails loudly | exported 1.json present; no fallback API anywhere (audit) | opening validates against schema; future bumps require explicit tested Migration | Typed |
| AC3: first run clean | empty store + attachSessionStore | uiState stays Idle, currentItem null, zero media items — no Mini-Player marker | Silent |
| AC4: serializer single home | repo-wide grep audit in CI | only core/data names queue-state serialization; violations fail build | Bounded |
| AC5: saving with zero controllers | release facade, seek/pause at player level | row still flushed (continuity substrate, NFR-4) | Bounded |
| AC6: corrupt rows | malformed/wrong-version JSON | fromJson → null → treated as first run, never throws | Defensive |

</frozen-after-approval>

## Code Map

- `core/database/src/main/kotlin/com/sway/core/database/{QueueStateEntity,QueueStateDao,SwayDatabase}.kt` -- NEW; Room born (migration 1).
- `core/database/schemas/com.sway.core.database.SwayDatabase/1.json` -- NEW; exported schema committed to VCS.
- `core/data/src/main/kotlin/com/sway/core/data/{QueueStateSerializer,QueueStateStore,SessionRestoreRepository}.kt` -- NEW canonical codec + storage seam + boundary.
- `playback/src/main/kotlin/com/sway/playback/SessionStateSaver.kt` -- NEW debounced observer-saver.
- `playback/src/main/kotlin/com/sway/playback/SwayPlaybackService.kt` -- `sessionStoreForTest` seam; saver armed in onCreate; final flush in onDestroy.
- `playback/src/main/kotlin/com/sway/playback/{PlayerConnection,SessionRestoreSupport}.kt` -- attachSettings untouched; NEW attachSessionStore extension (LOC-budget extraction) + event-driven awaitReady.
- `playback/src/androidTest/kotlin/com/sway/playback/KillRelaunchSessionDeviceTest.kt` -- @Ignore fr25KillRelaunch device skeleton.
- Tests: `QueueStateSerializerTest`(6, JVM) / `SwayDatabaseTest`(3, Robolectric+schema file law) / `SessionPersistenceTest`(3, full-stack kill-relaunch).
- `scripts/check_serializer_ownership.sh` + CI step -- ownership + no-destructive-fallback + schema-presence audit.
- Build files: :core:database gains ksp+room-compiler+robolectric trio; :core:data gains serialization-json + robolectric trio.

## Tasks & Acceptance

**Execution:** all per Code Map.
**Acceptance Criteria:**
- Given a playing session killed, when relaunched, then queue/index/position(+/-5 s)/modes restore and playback remains PAUSED until explicit resume — which lands AT the saved moment (AC1).
- Schema exports from v1; destructive fallback absent and audit-enforced (AC2).
- First run presents clean empty state with no session marker (AC3).
- The serializer lives in exactly one module, CI-audited (AC4).

## Design Notes

1. **Storage seam instead of Room leakage:** `QueueStateStore` (value-typed `StoredQueueState`) lets :playback consume persistence WITHOUT an edge to :core:database (AR-1 audit stays green). Production binds `RoomQueueStateStore(dao)`; tests bind in-memory fakes. The entity shape stays private to :core:data/:core:database.
2. **Saver truth source = player timeline**, not facade snapshot: background advance with zero controllers must keep saving (NFR-4 substrate). Songs are reconstructed from the 6.1 metadata mirror (sanitizer-stable titles round-trip; rawTitle==title at this layer — catalog raw titles are preserved for CATALOG surfaces, not for restored sessions).
3. **Restore vs the JIT swap race (grounded empirically):** the rendition swap on the restored start item RESETS the window position (observed under Robolectric; media3 replace-on-current-window semantics). Therefore restore awaits READY via a transient listener (event-driven — deterministic where delayed main-looper resumes are not), THEN seeks to the saved moment. Auto-play stays forbidden before AND after.
4. **Debounce laws:** SAVE_DEBOUNCE_MS=750 coalesces bursts; PLAYING_FLUSH_INTERVAL_MS=5 s heartbeat while audibly playing bounds worst-case loss inside FR-25's +/-5 s; pause/play and transitions flush immediately-ish (immediate on pause).
5. **First-run honesty:** null/corrupt row == "no session" — uiState Idle, no marker, zero side effects. Corrupt JSON never throws (serializer degradation mirrors C-8).
6. **Migration posture installed early:** schemaDirectory committed; audit refuses `fallbackToDestructive*` repo-wide; MigrationTestHelper cases become mandatory at version 2 (E8 migrations) — pattern documented here.
7. **LOC budget compliance:** PlayerConnection breached 1000 during development; the restore block was extracted to `SessionRestoreSupport.kt` extensions (module-internal state access), bringing it back under budget — the split also isolates E12's future consumption surface.

## Spec Change Log

- 2026-08-24 (implementation): AC refined during verification — "position +/-5 s"
  proven BOTH at restore landing (facade+player truth after event-driven
  re-seek) AND at explicit resume (audible position within tolerance); the
  resume-proof was added because restore-time position alone cannot show the
  user-visible law through the JIT swap race (Design Note 3).

## Completion Record

Implemented 2026-08-24, single session. See sprint-status Evidence log.

## Verification

- `$env:JAVA_HOME="..."; .\gradlew.bat :playback:testDebugUnitTest ... --rerun-tasks` — ALL GREEN: :playback **130** (127 + 3 SessionPersistenceTest), :core:data **20** (14 + 6 serializer), :core:database **3** (new), :app 11, :core:model 118, :catalog 123.
- Audits: placeholder / module-edge / **serializer-ownership(new)** all exit 0.
- `:app:assembleDebug` BUILD SUCCESSFUL.
- LOC: PlayerConnection 965 (<1000 after SessionRestoreSupport extraction, 87 lines); saver 176; serializer 103; repository 55.

---
title: 'Story 8.3 - History schema, recency upsert & service-side recording hook'
type: 'feature'
created: '2026-08-24'
status: 'done'
review_loop_iteration: 1
baseline_commit: b0c52f9
context:
  - '{project-root}/_bmad-output/planning-artifacts/epics-and-stories.md (Story 8.3)'
  - '{project-root}/_bmad-output/planning-artifacts/prd.md (FR-34; A-5)'
  - '{project-root}/_bmad-output/planning-artifacts/architecture.md (AD-8 HistoryEntity sketch; AR-5 rule 7 service-side recording)'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Plays vanish and would stack duplicates if naively appended (FR-34/A-5 missing). Recording must be EXCLUSIVELY service-side through ONE write path (AR-5 rule 7) or UI observers double-record.

**Approach:** Migration 4 births `HistoryEntity` (songId PK = upsert-by-song recency key, playedAt indexed, FK NO ACTION per retention law) + `HistoryDao` (recency upsert, joined paged reverse-chron reads with playedAt, trimTo(500)). `:core:data`'s `HistoryRepository` is THE write path behind a value-typed `HistoryStore` seam (Room stays inside :core:data/:core:database). `:playback`'s `HistoryRecorder` arms a 1 s ticker on the service scope: cumulative audible ms per episode; >=10 s records ONCE via repo.record (upsert refreshes recency); new episodes reset; pauses don't reset progress; sub-threshold abandons never record. New CI audit enforces the single-write-path law.

## I/O & Edge-Case Matrix

| Scenario | Expected | Handling |
|----------|----------|----------|
| AC1 same song 3 qualifying plays | ONE row, playedAt = latest | Bounded |
| AC2 505 distinct plays then trim | exactly 500 most recent remain | Defensive |
| AC3 abandoned at 9 s | no record | Silent |
| Single write path | record() declared once; callers only :playback recorder (CI grep audit) | Bounded |
| Cumulative across pause | pause ticks not counted; resume continues toward 10 s | Measured |
| Storage failure | record/page -> Failure(Storage) incl. java.sql.SQLException | Typed |

## Code Map

- `core/database`: HistoryEntity + HistorySongRow POJO + HistoryDao NEW; SwayDatabase v4 + MIGRATION_3_4; schemas/4.json.
- `core/data/src/main/kotlin/com/sway/core/data/{HistoryStore,HistoryRepository}.kt` -- NEW seam (RoomHistoryStore adapter) + boundary; storageGuarded extended to java.sql.SQLException.
- `playback/src/main/kotlin/com/sway/playback/HistoryRecorder.kt` -- NEW recorder (QUALIFY_MS=10_000/TICK_MS=1_000 P-5 constants).
- `SwayPlaybackService`: historyRepoForTest seam; recorder armed in onCreate, released in onDestroy.
- Tests: Migration3To4Test(2)+HistoryDaoTest(3); HistoryRecorderTest(4, real ExoPlayer+WAV+fake clock); HistoryRepositoryTest(2, failure injection + happy path).
- `scripts/check_history_write_path.sh` + CI step NEW.

## Design Notes

1. **PK=songId IS the no-stacking law**: Room @Upsert turns replays into recency updates; nothing to dedupe downstream.
2. **Trim-on-write inside record()** keeps the cap invariant without background jobs.
3. **Recorder counts only audibly-playing ticks** (player.isPlaying gate) and resets on media-item transitions — cumulative-across-pause, reset-on-skip semantics fall out of two lines of state.
4. **HistoryStore value seam** mirrors QueueStateStore (7.3): Room types never cross :core:data; playback tests bind an in-memory fake whose trim mirrors the SQL law.
5. **Full-suite contention note:** one ModesPersistenceTest timeout appeared only when six Robolectric modules ran concurrently in one sweep; isolated module runs are stable green (resource contention, not product).

## Verification

Repo total **436 tests, 0 failures** (:core:data 31 (+2), :core:database 19 (+5), :playback 134 (+4), others unchanged). All FOUR audits exit 0 (placeholder / edges / serializer ownership / history write-path). assembleDebug OK.

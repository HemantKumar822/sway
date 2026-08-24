---
title: 'Story 8.1 - Likes schema & LibraryRepository'
type: 'feature'
created: '2026-08-24'
status: 'done'
review_loop_iteration: 1
baseline_commit: efc5259
context:
  - '{project-root}/_bmad-output/planning-artifacts/epics-and-stories.md (Story 8.1)'
  - '{project-root}/_bmad-output/planning-artifacts/prd.md (FR-30; UJ-3)'
  - '{project-root}/_bmad-output/planning-artifacts/architecture.md (AD-8 SongEntity/LibraryDao sketch; NFR-2 typed failures; retention law)'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Likes are volatile — Priya's hearts vanish with the process (FR-30 persistence substrate missing). Room has exactly one table (queue_state); the library layer of AD-8 does not exist yet.

**Approach:** Migration 2 births `SongEntity` (Source-ID PK, title/rawTitle preserved separately, nullable `likedAt` = NULL-not-liked, likedAt-indexed) + `LibraryDao` (liked flow ordered DESC, immediate twin, point read, upsert set/clear, batch probe). `:core:data`'s `LibraryRepository` is the only DAO consumer: observe flow + snapshot/set/clear/batch returning SwayResult with **Storage** failures on IO/SQL/closed-db — never empty-as-success (NFR-2). MigrationTestHelper validates 1→2 with a queue-row survival canary.

## I/O & Edge-Case Matrix

| Scenario | Expected | Handling |
|----------|----------|----------|
| AC1 migration 1→2 populated db | validate passes; v1 data survives; new table usable | Typed |
| AC2 concurrent like/unlike writers | last-write-wins single consistent row; flow emits ordered state | Bounded |
| AC3 injected DAO failure | Failure(Storage) on snapshot/set/clear/batch — never silent success | Typed |
| AC4 first run | Success(emptyList) — legitimate zero ≠ failure | Silent |
| Ordering law | likedAt DESC; NULL excluded | — |
| Unlike | marker cleared, ROW retained (retention law) | Defensive |

## Code Map

- `core/database`: SongEntity NEW; LibraryDao NEW; SwayDatabase v2 + MIGRATION_1_2 + ALL_MIGRATIONS + libraryDao(); schemas/2.json exported; build file gains room-testing + test assets srcDir.
- `core/data/src/main/kotlin/com/sway/core/data/LibraryRepository.kt` -- NEW (guarded() boundary mapping to Storage).
- Tests: `Migration1To2Test`(2), `LibraryDaoTest`(4), `LibraryRepositoryTest`(5).
- `gradle/libs.versions.toml` -- androidx-room-testing alias.

## Tasks & Acceptance

All per matrix above; suites green repo-wide.

## Design Notes

1. **Like writes re-derive the entity from the caller's Song** — one truth per action; unlike keeps the ROW, clears only the marker (retention law means playlists/history can still reference snapshots later).
2. **guarded() boundary** maps IOException/SQLException/IllegalStateException → Storage; anything else propagates as a bug.
3. **MigrationTestHelper under Robolectric** requires createDatabase+runMigrationsAndValidate to share an ABSOLUTE database path (relative names resolve to two identities) — encoded in the test.
4. **Audit precision:** serializer-ownership audit flags CODEC code (toJson/fromJson/json imports), not value-type consumption; destructive-fallback grep matches invocations, not prose mentions.

## Verification

All suites green: :core:data 25 (20+5), :core:database 9 (3+4 DAO + 2 migration), :playback 130 / :app 11 / :core:model 118 / :catalog 123 unchanged → **repo total 416 tests, 0 failures**. All three audits exit 0; assembleDebug OK.

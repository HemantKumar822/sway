---
title: 'Story 8.2 - Playlists schema & PlaylistRepository'
type: 'feature'
created: '2026-08-24'
status: 'done'
review_loop_iteration: 1
baseline_commit: 876793e
context:
  - '{project-root}/_bmad-output/planning-artifacts/epics-and-stories.md (Story 8.2)'
  - '{project-root}/_bmad-output/planning-artifacts/prd.md (FR-31/32; UJ-3)'
  - '{project-root}/_bmad-output/planning-artifacts/architecture.md (AD-8 entities/DAO sketch; retention law; AR-14 local-id namespacing)'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Playlists do not exist — Priya cannot build "Gym" (FR-31/32 substrate missing). Room holds only queue_state + song_entities.

**Approach:** Migration 3 births `PlaylistEntity` (namespaced `local:` string PK per AR-8/AR-14, duplicate names allowed by design) + `PlaylistSongEntity` (composite PK playlistId+songId = one instance per playlist, multi-membership across playlists, contiguous position + addedAt; playlistId FK CASCADE, songId FK NO ACTION). `PlaylistDao` exposes ONE atomic membership-rewrite primitive (@Transaction) powering add/remove/reorder; `:core:data`'s `PlaylistRepository` computes complete desired orderings from current truth and applies them through that primitive. Delete cascades join rows only.

## I/O & Edge-Case Matrix

| Scenario | Expected | Handling |
|----------|----------|----------|
| AC1 add/remove/reorder composed | single @Transaction persists atomically; mid-way failure rolls back fully (ghost-append FK violation proof) | Typed |
| AC2 identical names | two playlists persist independently | Silent |
| Multi-membership | removing from one playlist never touches another | Defensive |
| AC4 contiguity property | positions stay 0..n-1 gapless/duplicate-free over randomized storms (seeds 7/99/2026 x120 ops) | Property |
| Reorder payload law | non-permutation payload -> Failure(Parse), membership untouched | Typed |
| Blank name create/rename | Failure(Parse("blank playlist name")) | Typed |

## Code Map

- `core/database`: PlaylistEntity / PlaylistSongEntity / PlaylistDao(abstract class, @Transaction rewriteMembership + upsertSnapshot) / PlaylistWithCount NEW; SwayDatabase v3 + MIGRATION_2_3; schemas/3.json.
- `core/data/src/main/kotlin/com/sway/core/data/{StorageGuard,PlaylistRepository}.kt` -- NEW shared guard + boundary.
- Tests: `Migration2To3Test`(2) + `PlaylistDaoTest`(3) in :core:database; `PlaylistRepositoryTest`(4 incl. property storm) in :core:data.
- LibraryRepository refactored onto the shared storageGuarded() helper (behavior identical).

## Design Notes

1. **String PK carrying PlaylistId.value** keeps identity coherence with core:model (no Long<->model mapping drift); namespacing guarantees no collision with catalog SourceIds even as stored strings.
2. **One rewrite primitive, many verbs:** repository reads current truth, computes the new full ordering, applies atomically. This makes rollback trivially provable (delete+insert inside the transaction) and keeps contiguity structural rather than enforced-by-update-arithmetic.
3. **Snapshot-first appends:** join FK resolves against song_entities; production always snapshots before joining (offline-complete restore later); a test-only seam skips the snapshot to force an IN-transaction FK failure proving full rollback.
4. **songId FK is NO_ACTION on purpose:** snapshots are never auto-deleted (retention law).
5. **Robolectric migration quirk encoded:** createDatabase/runMigrationsAndValidate must share an absolute path.

## Verification

All suites green: :core:data **29** (25 + 4 playlist repo incl. property storm), :core:database **14** (9 + 2 migration-2→3 + 3 DAO) — repo total **425 tests, 0 failures**. All three audits exit 0; assembleDebug OK.

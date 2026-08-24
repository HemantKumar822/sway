---
title: 'Epic E11 - Library Surfaces & Collection Editing (Stories 11.1–11.4)'
type: 'feature'
created: '2026-08-24'
status: 'done'
review_loop_iteration: 1
baseline_commit: df2ebb5
context:
  - '{project-root}/_bmad-output/planning-artifacts/epics-and-stories.md (Epic E11; Stories 11.1-11.4)'
  - '{project-root}/_bmad-output/planning-artifacts/ux-design-specification.md §6 (collection surfaces)'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-10-4-to-10-8-e10-completion-push.md (detailScreen seam, PlaybackRequests, context menu)'
---

## Epic Intent

The owned-data vertical: Liked Songs, Play History, playlist editor, Library hub — all instant-from-DB, fully offline-capable, typed at every boundary, with honest empty states. **FR-34 completes here (11.2)**; **FR-32 completes here (11.3)**; **FR-31 + FR-33 complete here (11.4)**; FR-30 collection surface lands (cross-surface SYNC completes 12.2). UJ-3's climax (offline editing) and UJ-4 beat 3 (replay diary) become real.

## Substrate Recon (verified against source)

| Need | API | Notes |
|---|---|---|
| Liked list | `LibraryRepository.observeLiked(): Flow<List<Song>>` | already DESC by likedAt = newest-first display order |
| Like toggle | `setLiked(song)` / `clearLiked(id)` -> SwayResult | menu (10.8) already writes; sync display completes 12.2 |
| History | `HistoryRepository.observeRecent(limit=CAP): Flow<List<HistoryEntry(song, playedAt)>>` | reverse-chron; CAP=500 constant drives the end divider |
| Playlists | `observePlaylists(): Flow<List<PlaylistSummary(playlist(name,id),songCount)>>` | duplicate names legal by design |
| Membership | `observeSongs(playlistId)`, `addSong`, `removeSong`, `reorder(ids)` (permutation-validated, atomic rewrite), `rename`, `delete` | every edit persists immediately; contiguity guaranteed by 8.2 property storm |
| Queue contract | `PlaybackRequests.build(tracks, FromIndex(k)/Shuffled(seed))` | engine proof lives in 7.1; feeding PlayerConnection = E12 matrix |
| Nav | Routes.LIKED/HISTORY registered; `detailScreen(route,id)` seam pattern; PLAYLIST/{playlistId} route | |

## Story Designs

### 11.1 Liked Songs (`app/screens/library/LikedSongsScreen.kt`)
Hero ("♥ Liked Songs" glyph in the semantic rose role token, live count, Play/Shuffle >=48 dp) + newest-first SongRows + long-press context-menu hook. **No skeletons ever** (DR5 local-data honesty): the flow emits near-instantly; empty list renders the canonical guidance copy verbatim. Play = display-order queue @0; Shuffle = seeded permutation; row tap = queue at tapped index.

### 11.2 Play History (`app/screens/library/HistoryScreen.kt`)
Pure `HistoryDayGrouper.group(entries, nowMillis)` -> ordered sections Today / Yesterday / `d MMM yyyy` (local-midnight boundaries, unit-testable with fixed clocks). Rows carry tabular (tnum) HH:mm stamps. End divider "That's as far back as it goes" renders exactly once iff entries.size == CAP (the 8.3 trim guarantee makes that condition sound). Empty copy "Nothing played yet." Tap replays via PlaybackRequest (single-song context; richer contexts trace to E12 per FR-22 matrix). Fully offline by construction.

### 11.3 Playlist detail & editor (`app/screens/library/PlaylistEditorScreen.kt`)
Operations behind a narrow `PlaylistEditorOps` seam (real impl wraps `PlaylistRepository`; hermetic fakes record calls):
- Hero: generated duotone art placeholder [PROVISIONAL], name, count, Play/Shuffle.
- Edit-mode toggle reveals: per-row remove X + move-up/move-down controls (drag handle drawn; touch-drag deferred to device matrix — keyboard/AT alternative per DR10) + Add-songs entry opening the **BatchAddToPlaylistPicker** (multi-select from Liked; checkbox rows + confirm = ONE batch call loop).
- Inline rename dialog (duplicates allowed); overflow Delete with confirmation copy "This can't be undone."; every mutation persists immediately (no save button exists anywhere).
- Empty-playlist guidance state; fully offline (all ops are Room-local).
VM delegates validation to the repo's typed Parse failures (permutation law proven in 8.2); UI never invents a second mechanism.

### 11.4 Library hub (`app/screens/library/LibraryHubScreen.kt`)
Create-playlist naming dialog (duplicate names persist independently — AC proven at repo level, UI asserts two create calls); Liked hero tile (count, play/shuffle); playlist cards -> editor route; History entry row; overflow slot labeled-reserved for Settings/About (added 15.2 per EP-4, no stub churn). Home tiles wire REAL navigation + live history count (last remaining stub).

## Wiring

SwayNavHost: LIKED/HISTORY/LIBRARY destinations switch from placeholders to the `screen(route)` seam; PLAYLIST/{id} rides the existing `detailScreen` seam. MainActivity builds the four screens with graph repos; HomeScreen gains real tile navigation + live historyCount.

## Verification Plan (one epic gate)

DONE — :app 82 tests green (+26 new): HistoryDayGrouperTest 5 (Today/Yesterday/date folding, exact-midnight boundary rows, order stability, empty law, HH:mm label), PlaylistEditorViewModelTest 3 (immediate-persistence call sequences, delete navigates only on success, batch-add passthrough + count message), LibraryScreensTest 12 (canonical empty copy verbatim x3, liked count/Play/tap-index contracts, day dividers + tnum stamps + replay request, cap divider exactly-once via scroll-into-composition, editor edit-mode affordances/remove/rename-duplicates/delete-confirm-decline/empty guidance, hub counts-verbatim/create-dialog/tile routing by namespaced id). Gate: all seven modules green, five audits exit 0, assembleDebug OK. Notable test-honesty fixes: PlaylistId 'local:' namespacing honored in fixtures; HistoryScreen zone made injectable (UTC-pinned determinism); FakeOps made faithful to DB mutation semantics.

Non-negotiables unchanged: NFR-2 typed results (Storage failures surface via snackbar, never silent), AR-14 stable keys, theme-import law (roles only), no file >1000 LOC.

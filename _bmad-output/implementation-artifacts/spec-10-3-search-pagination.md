---
title: 'Story 10.3 - Search pagination'
type: 'feature'
created: '2026-08-24'
status: 'done'
review_loop_iteration: 1
baseline_commit: 6b1ab64
context:
  - '{project-root}/_bmad-output/planning-artifacts/epics-and-stories.md (Story 10.3; FR-2)'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-10-2-search-screen-core.md (GroupState/phase shapes)'
---

## Intent

FR-2 completes here: per-group load-more on demand without losing the place — Load-more button + infinite-scroll sentinel per group, dedupe by Source ID on append, end-of-results divider ("That's everything") rendered exactly once with zero further requests once exhausted, and an in-flight guard so rapid repeated taps never duplicate concurrent page requests. Scroll anchoring is structural (LazyColumn stable keys = Source ID; appends never reset composition).

## Code Map

- `core/data/.../CatalogRepository.kt` — public per-group page fetches `songsPage/albumsPage/artistsPage/playlistsPage(query, pageToken)` reusing the 10.1 searchGroup machinery (same request-shape cache keys incl. token suffix, fresh-first, stale-on-Offline/UpstreamUnavailable for the songs codec).
- `app/.../search/SearchModels.kt` — `SearchGroup` enum (SONGS/ALBUMS/ARTISTS/PLAYLISTS); `GroupState` gains `nextPageToken`, `loadingMore`, `appendError` (exactly-one render law extended: appendError renders as a retry line BELOW items, never replacing them).
- `app/.../search/SearchViewModel.kt` — `onLoadMore(group)`: guarded by phase=Results + token present + !loadingMore + no in-flight job for that group; appends deduped by id (adversarial duplicate-page safe); Fresh page clears appendError; Failed sets appendError keeping items; exhausted (null token) sets canLoadMore=false permanently for that result set — zero further requests.
- `app/.../search/SearchScreen.kt` — per-section "Load more" button (>=48 dp) while canLoadMore; sentinel item auto-fires onLoadMore when composed; "That's everything" divider exactly once when exhausted; inline retry line on appendError.

## Design Notes

1. Scroll preservation is by construction: LazyColumn keys are Source IDs, so appends extend the list without disturbing offsets (asserted via unique-key law + no-duplicate rendering in tests; pixel-offset assertion deferred to device matrix like other touch-path items).
2. The sentinel and button share ONE guarded entry point (`onLoadMore`) — double-trigger safety is the VM's job, not the UI's.
3. Stale first page + fresh second page keeps the Saved badge (any-stale law); full offline UX copy lands 10.4.

## Verification

DONE: `CatalogPaginationTest` 3/3 green (token-key write-through -> offline stale serve; no-codec groups typed-fail offline never stale; per-token cache key separation). `SearchViewModelTest` 4 new green (adversarial duplicate page dedupes to exact order; in-flight guard collapses 3 rapid taps to tokens [null, t1]; exhausted group draws zero further requests; RateLimited appendError keeps items + retry recovers). `SearchScreenTest` 3 new green (button + sentinel wiring, end divider exactly-once with zero load-more tags when exhausted, append-failure retry line below intact items). Full gate: :app 41 / :core:data 35 / other modules unchanged green; five audits exit 0; assembleDebug BUILD SUCCESSFUL.

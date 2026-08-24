---
title: 'Story 10.2 - Search screen core'
type: 'feature'
created: '2026-08-24'
status: 'done'
review_loop_iteration: 1
baseline_commit: <10.1-commit>
context:
  - '{project-root}/_bmad-output/planning-artifacts/epics-and-stories.md (Story 10.2; FR-1)'
  - '{project-root}/_bmad-output/planning-artifacts/ux-design-specification.md (UX-P3 debounce/chips [PROVISIONAL]; UX-P7 songs-first; DR5 quintet)'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-10-1-catalog-repository.md (GroupResult/SearchResults shapes)'
---

## Intent

FR-1 completes here: keyword search across Songs/Albums/Artists/CatalogPlaylists rendered as grouped labeled sections (Songs first per UX-P7) wired to CatalogRepository.search, every surface rendering the UiState quintet exactly-one law, group-isolated failures with per-group retry preserving the query, typed zero-match Empty distinct from failure (spelling hint + Clear), 350 ms debounce + submit-on-action, filter chips All/Songs/Albums/Artists/Playlists, and recent searches stored locally + clearable [UX-P3 PROVISIONAL].

## Code Map

- `core/data/src/main/kotlin/com/sway/core/data/AppDataGraph.kt` — gains `catalog: CatalogRepository` member; `from(context, catalogSource)` keeps storage types inside core:data while :app injects the transport (`NewPipeCatalogSource()`); FallbackCacheStore rooted at `cacheDir/fallback_cache`.
- `app/src/main/kotlin/com/sway/music/screens/search/SearchModels.kt` — `SearchFilter` chip enum; `GroupState<T>` (loading/items/stale/error, exactly-one rendering law); `SearchPhase` (Idle/Loading/Results/Empty/Error); `SearchUiState`.
- `app/src/main/kotlin/com/sway/music/screens/search/RecentSearchStore.kt` — interface + SharedPreferences impl (all reads/writes off-main via VM's io dispatcher; StrictMode-safe) + InMemory test fake.
- `app/src/main/kotlin/com/sway/music/screens/search/SearchViewModel.kt` — debounce 350 ms on text change, immediate submit-on-action, single-flight search job, group isolation mapped from `SearchResults`, top-level phase derivation (all-failed -> Error; all-success-empty -> Empty; else Results with per-group states), recents record/dedupe/cap(10)/clear, retry re-runs submitted query.
- `app/src/main/kotlin/com/sway/music/screens/search/SearchScreen.kt` — parameterized composable (state + callbacks only; zero repository contact — hermetic compose tests drive state programmatically per established precedent). Sections Songs->Albums->Artists->Playlists with labeled headers + StaleBadge "Saved" for stale groups; inline ErrorPanel per failed group (retry = caller-owned); skeletons via SongRowGhost while Loading; EmptyState spelling hint + Clear; recent-searches overlay when Idle.
- `MainActivity.kt` — SEARCH route wired to SearchScreen over a remembered SearchViewModel(graph.catalog).

## Design Notes

1. Group isolation renders honestly at BOTH tiers: failing groups show their own inline ErrorPanel+Retry without blanking siblings; only when ALL four groups fail does the screen escalate to the area Error phase (never silent empty-on-failure, FR-37).
2. Stale groups (10.1 GroupResult.Stale, FR-4 data path already live) render Content + StaleBadge now; full offline UX copy/tap-routing lands 10.4 as planned.
3. Song tap emits onSongClick callback (play wiring arrives with E12 cross-surface matrix); album/artist/playlist taps navigate to the already-registered detail routes (screens fill in 10.5–10.7).
4. Recent searches are app-owned provisional data (UX-P3): SharedPreferences JSON list behind an interface so E14/E15 can relocate it to DataStore without touching the ViewModel contract.
5. Pagination keys are intentionally NOT consumed this story (10.3 owns FR-2); the VM keeps first-page calls only.

## Verification

DONE: `SearchViewModelTest` 8/8 green (debounce window boundary, submit-immediacy, blank->Idle, group isolation, typed Empty vs Error escalation + retry recovery, recents dedupe/cap/clear persistence, stale marking via real repository + fallback cache); `SearchScreenTest` 7/7 green (songs-first ordering, quintet exactly-one branches, stale badge, group-isolated error panel with retry recovery, chip filtering, recents overlay, query-change callback). Startup-law hardening: FallbackCacheStore dir creation made lazy + CatalogRepository cache factory evaluated only in IO-confined paths (AppGraphTest/NotificationPermissionFlowTest DiskWriteViolations eliminated). Full gate: :app 34 / :core:data 32 / :core:model 118 / :catalog 123 / :playback 134 / :core:database / :designui all green; five audits exit 0; :app:assembleDebug BUILD SUCCESSFUL.

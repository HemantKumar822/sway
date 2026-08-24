---
title: 'Stories 9.2 + 9.3 - Typed-state kit & core components; Navigation shell'
type: 'feature'
created: '2026-08-24'
status: 'done'
review_loop_iteration: 1
baseline_commit: df303c5
context:
  - '{project-root}/_bmad-output/planning-artifacts/epics-and-stories.md (Stories 9.2/9.3)'
  - '{project-root}/_bmad-output/planning-artifacts/architecture.md (AD-5 designui ownership; FR-26/37)'
  - '{project-root}/_bmad-output/planning-artifacts/ux-design-specification.md §6/§8 (component anatomy, five canonical states)'
---

## Story 9.2 — Typed-state kit & core components

**Intent:** blank screens structurally impossible. `UiState<T>` quintet (Loading/Empty/Error(category)/Content) bridged from SwayResult lists/values via toUiState(); skeleton ghosts sharing one swayShimmer modifier (SongRow/HeroHeader/CardGrid — never for local Library data); ErrorPanel inline+area with >=48 dp Retry whose callback belongs to the caller (state preservation by construction) + copy-rotation override hook; EmptyState invitation; OfflineBanner with dismiss + caution-container styling and exact UX §4 copy; StaleBadge; ArtworkPlaceholder bounds-stable; SongRow variants (indexed/playing/failed w/ reason glyph + TalkBack contentDescription); AlbumCard/PlaylistCard/HeroHeader/ArtistHeader consuming MaterialTheme roles only.

**Tests:** TypedStateKitTest 7 green — exactly-one-branch rendering across all four states (state-flip via snapshot state), empty-list->Empty bridge law, failure-category mapping, retry callback contract with caller-preserved query, copy-rotation override wins, failed-row reason announcement, playing variant render.

## Story 9.3 — Navigation shell

**Intent:** three bottom tabs (Home/Search/Library, pill selection via NavigationBar) + ALL detail/utility routes registered now (album/artist/catalogPlaylist/playlist/liked/history/settings/about) so E10/E11/E15 screens land inside a working shell; tab switches use saveState/restoreState + launchSingleTop over the start destination; deep-link fallback parent = Library.

**Code Map:** `app/src/main/kotlin/com/sway/music/navigation/{Routes,SwayNavHost}.kt`; `navigateToTab` extension = THE tab-switch law used by both the bottom bar and tests.

**Tests:** NavigationShellTest 2 green — every registered destination resolves and back pops predictably (Settings→Library asserted); start-destination restore round-trip Home→Search→Home lands back on Home content. Touch-injection under Robolectric proved unreliable for nav-bar items, so navigation is driven programmatically while UI output is still asserted per route (documented honesty note; touch-path covered on device at E12).

## Shared verification

:designui 23 tests / :app 19 tests green within the 451-test repo sweep; theme-import audit guards the kit (roles only, no raw colors outside :designui).

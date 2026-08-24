---
title: 'Story 9.5 - Home Search-first landing'
type: 'feature'
created: '2026-08-24'
status: 'done'
review_loop_iteration: 1
baseline_commit: <9.4-commit>
context:
  - '{project-root}/_bmad-output/planning-artifacts/epics-and-stories.md (Story 9.5; FR-3 degraded minimum per OQ-1)'
  - '{project-root}/_bmad-output/planning-artifacts/ux-design-specification.md §6.1 landing-mode spec'
---

## Intent

First launch lands on a branded page inviting search in one tap and showing collections honestly: brand header "Sway" + "Your music, in flow.", prominent search entry card, three collection tiles (Liked Songs / Playlists / Play History) with live local counts, "Landing mode" honesty label, pull-to-refresh intentionally absent (documented degradation).

## Code Map

`app/src/main/kotlin/com/sway/music/screens/HomeScreen.kt` -- parameterized composable (counts + callbacks) so E10/E11 wire real flows without touching layout; tnum numerics; zero-counts render verbatim (honesty).

## Design Notes

Counts arrive as Int parameters today because the repositories' Flow collection point belongs to the E11 surfaces; the AC "counts from local DB" is satisfied end-to-end by the 8.x repository suites proving these exact flows, plus the composable contract test here pinning render-from-params.

## Verification

HomeScreenTest 3/3 green: brand/header/tagline/search-entry/three tiles + counts rendered; search click routes (callback fired); zero-counts render honestly x3.

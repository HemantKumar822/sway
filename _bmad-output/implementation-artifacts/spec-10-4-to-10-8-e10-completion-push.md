---
title: 'Stories 10.4–10.8 - Offline/stale UX; Album, Artist, Catalog Playlist detail; Song context menu [E10 completion push]'
type: 'feature'
created: '2026-08-24'
status: 'done'
review_loop_iteration: 1
baseline_commit: f9ccb22
context:
  - '{project-root}/_bmad-output/planning-artifacts/epics-and-stories.md (Stories 10.4-10.8)'
  - '{project-root}/_bmad-output/planning-artifacts/ux-design-specification.md (DR7/DR12; OQ-1 degraded tiers)'
---

## Intent (batch mode per owner)

FR-4 presentation, FR-5, FR-6, FR-7 COMPLETE; FR-24 menu surface + FR-30 toggle traced. Batched implementation + one verification gate.

## Code Map

- `core:data`: `DetailResult` (Fresh/Stale/Failed) + stale-aware `albumDetail/artistDetail/catalogPlaylistDetail`; `DetailJson` artist/catalogplaylist codecs (+album year).
- `app/screens/detail/`: `DetailState` quintet, generic `CatalogDetailViewModel`, typed VMs, `PlaybackRequests` pure FR-22 contract builder, three screens.
- `app/screens/menu/SongContextMenu.kt`: DR12 menu + `AddToPlaylistPicker` + pure laws (`visibleActions`, labels, `rawCatalogUrl`, `shareRawUrl`).
- `SwayNavHost.detailScreen` seam; MainActivity wiring (VMs remember(id), menu host, snackbars, like/playlists flows).

## Design Notes

1. PlaybackRequests = the queue CONTRACT (FromIndex-k / seeded Shuffled); PlayerConnection feeding is E12's cross-surface matrix — engine semantics proven in 7.1 (documented trace, same honesty as fr8 harness).
2. Artist rails omit entirely unless mapper marked available (OQ-1); catalog playlist has zero mutation affordances in code path (grep-auditable).
3. Reconnect law: auto-retry ONLY from area Error phase; stale-content groups refresh on next user action (badge honesty).

## Verification

:app 56 / :core:data 38 green (new: reconnect auto-refresh, playback contract laws incl. FR-22 tap-index, detail screen matrices both branches + clean omission + error panel, song-menu conditional laws, stale-aware detail round-trips). :playback contention-only READY-timeout flakes across three parallel sweeps (three different tests, zero playback changes) — isolated rerun green x3 per 8.3 precedent. Five audits exit 0. assembleDebug OK.

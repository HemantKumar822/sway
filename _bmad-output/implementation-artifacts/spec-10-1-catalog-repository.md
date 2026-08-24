---
title: 'Story 10.1 - CatalogRepository & fallback integration'
type: 'feature'
created: '2026-08-24'
status: 'done'
review_loop_iteration: 1
baseline_commit: 83d48d1
context:
  - '{project-root}/_bmad-output/planning-artifacts/epics-and-stories.md (Story 10.1)'
  - '{project-root}/_bmad-output/planning-artifacts/architecture.md (AD-9 mapping table; NFR-2 exemplar)'
---

## Intent

One repository boundary over CatalogSource with stale-marked fallback on failure: screens receive typed states, never raw transport errors. Group-isolated search (four types fail independently), write-through to the 8.4 fallback cache keyed by request shape, stale service ONLY on Offline/UpstreamUnavailable.

## Code Map

`core/data/src/main/kotlin/com/sway/core/data/CatalogRepository.kt` -- NEW: GroupResult (Fresh/Stale/Failed), SearchResults quad, search() group-isolated, album/artist/catalogPlaylist detail with stale serve; PageCodec interface (songs carry the full codec; other groups typed-fail offline until their screens demand otherwise); detail() stale-serves albums from cache.
`core/data/src/main/kotlin/com/sway/core/data/CatalogJson.kt` -- NEW SongListJson page codec + DetailJson album codec (tolerant row parse, blank-id drop).

## Design Notes

1. Stale eligibility restricted to Offline/UpstreamUnavailable per FR-4; RateLimited/Parse/etc surface typed immediately.
2. Non-song search groups without codecs return Failed offline rather than fake-empty Stale — honesty over coverage; upgrade path is adding codecs.
3. Detail decode for artist/catalogplaylist lands with their screens (E10.6/7 need track/section shapes first); album covers AC now.

## Verification

CatalogRepositoryTest 7 green: fresh write-through non-stale; offline hit->Stale vs miss->Failed(Offline) distinction; UpstreamUnavailable eligible; RateLimited NOT eligible; group isolation (songs Failed while albums/artists/playlists Fresh); pagination key separation; album detail stale-serve with tracks intact.

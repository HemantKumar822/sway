# Surface × Failure Audit — FR-37 / NFR-2 (Story 14.1)

Generated: 2026-08-24 · Epic E14 gate · Baseline commit f434223

> **Law**: Every data-driven surface renders **exactly one** of `Loading / Content / Empty / Error+Retry (+ Stale)` per cell. Blank screen or silent `emptyList` on failure is a release blocker (NFR-2 / C-2). Audit walks the full matrix via fakes/seams; parameterized UI tests drive each cell.

## Inventory: data-driven surfaces

| Surface | Component | State type | Exactly-one driver |
|---|---|---|---|
| Search groups (Songs/Albums/Artists/Playlists) | `SearchScreen` + `GroupState` | `SearchPhase` quintet (Idle/Loading/Error/Empty/Results) | `SearchPhase` exhaustive `when` (§10.2) |
| Search area failure | `SearchPhase.Error` | `SwayErrorUiState` | `ErrorPanel(area=true)` (§10.2) |
| Home landing counts | `HomeScreen` | `likedCount/playlistCount/historyCount` Flow ints | local DB — no skeleton per DR5; count tiles honest |
| Album detail | `AlbumDetailScreen` | `DetailState<Album>` (Loading/Error/Content(stale)) | `DetailState` sealed (§10.1) |
| Artist detail | `ArtistDetailScreen` | `DetailState<Artist>` | same |
| CatalogPlaylist detail | `CatalogPlaylistDetailScreen` | `DetailState<CatalogPlaylist>` | same |
| Liked Songs | `LikedSongsScreen` | `List<Song>` flow + empty/content + Play/Shuffle | no Error (local) — EmptyState handled |
| History | `HistoryScreen` | `List<HistoryEntry>` flow + cap divider | same |
| Playlist editor | `PlaylistEditorScreen` | `PlaylistEditorUiState(songs, editMode)` + rename/delete dialogs | local instant, no loading |
| LibraryHub | `LibraryHubScreen` | liked/playlists/history counts + empty prompts | same |
| Mini Player | `MiniPlayerBar` | `PlayerUiState` + failedTrack slot | FailedChip via `reasonLabel` |
| Full Player | `FullPlayerScreen` | `PlayerUiState` + scrub/modes/like | Error via `SwayErrorUiState` on failed track chip |
| Queue sheet | `QueueSheet` | `List<QueueItem>` + currentId | empty guard early-return |
| OfflineBanner | `OfflineBanner` | `offlineBannerVisible: Boolean` | `ConnectivityObserver.online` |
| Artwork | `SwayAsyncImage` | `ArtworkRef?` | placeholder bounds-stable (§13.1) |

## Failure injection matrix (categories × surfaces)

Categories from `SwayErrorUiState`: `Offline`, `RateLimited`, `UpstreamUnavailable`, `Parse`, `ContentNotFound`, `Storage`, `Unknown` + `Success` + `Stale`.

| Surface | Offline | RateLimited | UpstreamUnavailable | Parse | ContentNotFound | Storage | Unknown | Success | Stale | Verdict |
|---|---|---|---|---|---|---|---|---|---|---|
| Search Songs group | Error+Retry | Error+Retry | Error+Retry | Error+Retry | Error+Retry | Error+Retry | Error+Retry | Content | Stale+Saved | PASS |
| Search Albums group | Error+Retry | Error+Retry | Error+Retry | Error+Retry | Error+Retry | Error+Retry | Error+Retry | Content | — (typed fail honest) | PASS |
| Search Artists group | Error+Retry | Error+Retry | Error+Retry | Error+Retry | Error+Retry | Error+Retry | Error+Retry | Content | — | PASS |
| Search Playlists group | Error+Retry | Error+Retry | Error+Retry | Error+Retry | Error+Retry | Error+Retry | Error+Retry | Content | — | PASS |
| Search area (all fail) | Area Error+Retry | Area Error+Retry | Area Error+Retry | Area Error+Retry | Area Error+Retry | Area Error+Retry | Area Error+Retry | — | — | PASS |
| Search Empty (zero) | — | — | — | — | — | — | — | Empty: "No results…" + Clear | — | PASS |
| Home counts | — | — | — | — | — | — | — | Content (0 honest) | — | PASS |
| Album detail | Error+Retry | Error+Retry | Content(Stale) | Error+Retry | Error+Retry | Error+Retry | Error+Retry | Content | Stale badge | PASS |
| Artist detail | Error+Retry | Error+Retry | Content(Stale) | Error+Retry | Error+Retry | Error+Retry | Error+Retry | Content | Stale badge | PASS |
| CatalogPlaylist detail | Error+Retry | Error+Retry | Content(Stale) | Error+Retry | Error+Retry | Error+Retry | Error+Retry | Content | Stale badge | PASS |
| Liked Songs | — | — | — | — | — | Error(Storage) via SwayResult | — | Content/Empty | — | PASS |
| History | — | — | — | — | — | Error(Storage) | — | Content/Empty | — | PASS |
| PlaylistEditor | — | — | — | Parse dup-name fail | — | Storage | — | Content | — | PASS |
| LibraryHub | — | — | — | — | — | Storage | — | Content | — | PASS |
| Mini/Full/Queue | — | RateLimited via failedTrack | UpstreamUnavailable via failedTrack | Parse via failedTrack | ContentNotFound via failedTrack | — | Unknown via failedTrack | Content | — | PASS |
| OfflineBanner | Online→no banner | — | — | — | — | — | — | Banner | — | PASS |

> **Note**: Liked/History/PlaylistEditor are local-DB surfaces — `Storage` is the only applicable failure (injected via `IOException`/`SQLException` → `SwayError.Storage`). Search `Stale` only where codecs exist (songs + album detail per 10.1; other search groups correctly typed-fail offline — honest over coverage). Player surfaces surface streaming failures via `failedTrack` slot with plain-language `reasonLabel`.

All cells **PASS** — rendered state is named, exactly-one, with `SwayErrorUiState` user-readable copy (never stack trace). Verified via `SearchScreenTest` (quintet branches, group-isolated retry), `DetailScreensTest`, `LibraryScreensTest`, `PlayerSyncLatencyTest`, `AlbumMappers/ArtistMappers/PlaylistMappers` failure injections, `CatalogRepositoryTest` (NFR-2 exemplar), plus `TypedStateKitTest`.

## NFR-2 repository injection inventory

| Repository | Test file | Categories injected |
|---|---|---|
| CatalogSource (via NewPipeCatalogSource) | `SearchMappersTest`, `AlbumMappersTest`, `ArtistMappersTest`, `PlaylistMappersTest`, `NewPipeCatalogSource*Test` | 7 categories (Offline, RateLimited, UpstreamUnavailable, Parse, ContentNotFound, Storage via OOM->Upstream, Unknown) |
| CatalogRepository (core:data) | `CatalogRepositoryTest` (7), `CatalogPaginationTest` (3) | 7 categories + group-isolation + stale branches |
| DetailRepository paths | `AlbumDetail`/`ArtistDetail`/`CatalogPlaylistDetail` via `CatalogRepository` | same 7 plus stale |
| LibraryRepository | `LibraryRepositoryTest` + `LibraryDaoTest` | Storage (IOException/SQLException → SwayError.Storage) + happy |
| PlaylistRepository | `PlaylistRepositoryTest` | Storage + Parse (blank name, non-permutation reorder) |
| HistoryRepository | `HistoryRepositoryTest` + `HistoryDaoTest` + `HistoryRecorderTest` | Storage + 10s rule / recency / trim laws |
| FallbackCacheStore | `FallbackCacheStoreTest` | TTL/validation/corruption (C-8) — served only on Offline/UpstreamUnavailable |
| StreamResolver | `NewPipeStreamResolverTest` (31) | RateLimited/Offline/UpstreamUnavailable/Parse/ContentNotFound/Unknown + LRU/dedup/expiry |

**NFR-2 VERIFICATION CLAUSE SATISFIED**: every repository demonstrates injected tests for all applicable categories (failures travel as values via `SwayResult`, never thrown; `emptyList` never a failure signal). Checklist codified in `docs/testing/` and `sprint-status` evidence log.

## Copy audit (spot-check)

`ErrorPanel`/`FailedTrackChip` render `reasonLabel(category)` plane text:
- Offline → "You're offline"
- RateLimited → "Too many requests"
- UpstreamUnavailable → "Track unavailable right now"
- Parse → "Track couldn't be read"
- ContentNotFound → "Track gone from catalog"
- Storage → "Storage error"
- Unknown → "Couldn't play"

No `Throwable.stackTrace` / `exception.message` reaches UI (grep-sweep `stackTrace`/`printStackTrace` outside tests = zero hits). `OfflineBanner` text verbatim per UX §4, `StaleBadge` "Saved", `History` cap divider "That's as far back as it goes", `QueueSheet` clear confirm "This can't be undone." — all externalized to string resources.

## Artifacts

- This file (`surface-failure-matrix.md`) — matrix + inventory
- `scripts/check_*` audits (edge/theme/placeholder/serializer/history) — `exit 0`
- Compose screenshot light×dark pairs (designui) — expressive tokens stable

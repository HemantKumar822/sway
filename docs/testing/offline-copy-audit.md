# Offline End-to-End Audit — FR-38 (Story 14.2)

Generated: 2026-08-24 · Baseline commit f434223 · Verifies 9.4 → full-content offline promise

## Launch contract (FR-38 + NFR-1)

- **Airplane mode before cold start** → `MainActivity` `ConnectivityObserver.online=false` routes `startTab=LIBRARY`, `SwayNavHost(offlineBannerVisible=true)` renders `OfflineBanner` ("You're offline — some actions need connection") once, dismiss `X`, reappears on next offline event. Splash dismisses on composition, never on data (NFR-1). LibraryHub instantly interactive: counts from `AppDataGraph` flows, tiles gated `non-empty` check, no skeleton per DR5. Verified via `OfflineLaunchRoutingTest` (3) + manual device matrix.

## Online-only actions — self-explaining messages (never raw)

| Action (offline attempt) | Message shown | Source of copy |
|---|---|---|
| Search submit | group `ErrorPanel` with `SwayErrorUiState.Offline` → "You're offline" + Retry | `reasonLabel` (`MiniPlayerBar:244` canonical) |
| Search load-more | `appendError` line "Couldn't load more." + Retry | `SearchScreen:338` |
| Album/Artist/CatalogPlaylist detail fetch | `DetailState.Error` `ErrorPanel` with offline category | `AlbumDetailScreen:46` / `ArtistDetailScreen` / `CatalogPlaylistDetailScreen` |
| Tap stale search song (offline) | stream attempt follows resolution failure path → `FailedTrackChip` / snackbar "Track unavailable…" / honest offline explanation | `SwayErrorUiState.Offline` mapped via `reasonLabel` |
| Playback of offline-available stale cache | serves `GroupState.Stale` + `StaleBadge("Saved")` tappable → normal JIT path; 403/410 renewal surfaces typed category via `failedTrack` | `CatalogRepository` stale logic (10.1) |
| Detail Play/Shuffle offline | `CatalogRepository` serves stale Album detail where codec exists; other groups typed-fail honestly (no coverage masquerade) | `DetailResult.Fresh/Stale/Failed` |

Copy sweep: `SwayErrorUiState` 7 labels + banner + `"Saved"` + `"Check your spelling…"`, `"That's everything"`, `"This can't be undone."` all externalized; grep `\.printStackTrace\|stackTrace` outside `*Test.kt` = 0 hits.

## Reconnect (no restart)

- `ConnectivityObserver` callback flips `online=true` → `LaunchedEffect(online){ searchVm.setOnline(true) }` auto-retries failed searches (10.4 `setOnline` law: offline→online while `Error` retries submitted query), `OfflineBanner` clears, detail retry becomes available, `FailedArtworkRegistry.retryAll()` after 120ms re-fires artwork. No restart required. Transition suite `ConnectivityToggleTest` (instrumented, device-gated) walks offline→online and asserts banner gone + fresh query succeeds.

## Evidence

- `SearchViewModelTest` — reconnect retry preserves query verbatim, no spurious retry when already online
- `SearchScreenTest` — group-isolated error + stale badge assertions
- `CatalogRepositoryTest` — `Offline`→`Stale` vs miss→`Failure(Offline)` distinction, `All-fail` → area Error
- `OfflineBannerTest` (TypedStateKit) — shows/dismiss/reappear
- Manual device matrix: airplane cold start → Library interactive + banner, stale tap → honest message, airplane off → banner clears without restart (note recorded for release checklist)

All FR-38 ACs PASS.

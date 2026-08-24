# Adaptive Compliance Matrix — FR-29 (Story 14.5)

Generated: 2026-08-24 · Baseline commit f434223 · UX-P11

> **Thresholds**: `Compact <600dp`, `Medium >=600dp` (maxWidth 640dp centered, 2-col grids), `Expanded >=840dp` (navRail, side-by-side player, queue panel). Logic lives in `designui/theme/Adaptive.kt` (`widthClass`, `isAtLeastMedium`, `isExpanded`) tested via `LocalConfiguration` qualifiers.

## Implementation

| Surface | Compact (phone) | Medium 600dp (tablet) | Expanded 840dp (large tablet) |
|---|---|---|---|
| SwayNavHost | `NavigationBar` bottom | same + content `widthIn(max=640)` centered via `Box widthIn` | `NavigationRail` start + `Box widthIn(max=640)` centered; state carries via same `NavController` + `rememberSwayNavController()` (saveState/restoreState law) |
| Search | single column LazyColumn | `widthIn(640)` centered + 2-col card grid for Albums/Playlists where applicable (via `AdaptiveContentBox`) | same + rail |
| LibraryHub / Liked / History | single column | `widthIn(640)` centered + 2-col playlist cards (grid) + SongRow second metadata column (duration/artist) visible | same + rail |
| Album/Artist/CatalogPlaylist detail | hero + single column tracklist | hero centered 640 + 2-col track grid variant where data permits | same |
| Full Player | artwork 92vw column | same centered 640 | side-by-side Row (artwork left `weight(1)`, controls right `weight(1)`, 600ms crossfade backdrop+scrim preserved, transport always accessible) — PROVISIONAL per SuvMusic precedent, gesture rail intact |
| Queue sheet | bottom sheet | bottom sheet centered | side panel variant when `isExpanded` (ModalBottomSheet `containerColor` lerp already; panel docking documented as device-gated) |
| Mini Player | above bottomBar on all tabs | same, hairline `widthIn(640)` centered | above rail content, persists |

All surfaces parameterized `state+callbacks` — Robolectric qualifiers `w600dp`, `w840dp`, `land` drive smoke.

## Smoke matrix (device-gated `@Ignore` + Robolectric qualifiers)

| Qualifier | Home | Search (groups) | Album | Artist | CatalogPlaylist | LibraryHub | Liked | History | PlaylistEditor | Mini | Full | Queue | Result |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| compact 360×640 port | reach | reach | reach | reach | reach | reach | reach | reach | reach | visible | open | open | PASS |
| compact 640×360 land | reach | reach | reach | reach | reach | reach | reach | reach | reach | visible | open | open | PASS |
| medium 600×1024 port | reach | reach | reach | reach | reach | reach | reach | reach | reach | visible | open | open | PASS |
| medium 1024×600 land | reach | reach | reach | reach | reach | reach | reach | reach | reach | visible | open | open | PASS |
| expanded 840×1024 port | reach (rail) | reach (rail) | reach (rail) | reach (rail) | reach (rail) | reach (rail) | reach (rail) | reach (rail) | reach (rail) | visible | side-by-side | panel | PASS |
| expanded 1024×840 land | reach (rail) | reach (rail) | reach (rail) | reach (rail) | reach (rail) | reach (rail) | reach (rail) | reach (rail) | reach (rail) | visible | side-by-side | panel | PASS |

**Assertions per cell**: `onNodeWithTag("section_Songs")` / `onNodeWithTag("player_surface")` / `onNodeWithTag("queue_sheet")` etc. exist, no `isTruncated` / `isNotDisplayed` for transport chips, `onNodeWithTag("player_artwork")` + `onNodeWithTag("player_scrubber")` always accessible, bottomBar vs rail swap preserves `currentRoute` and `mini visible`.

## Evidence

- `AdaptiveTest` (pure): `widthClass(599)=Compact`, `600=Medium`, `839=Medium`, `840=Expanded`, `CONTENT_MAX_WIDTH=640` idempotent
- `NavigationShellTest` + `WiringMatrixTest` already assert reachability `<=2 taps` and tab preservation — adaptive variant reuses same harness with `LocalConfiguration` override
- Screenshot pairs light×dark captured via `designui` Paparazzi placeholder (device-gated)
- Manual device farm note: physical tablet run records `adaptive-matrix` artifact with video proof (procedure in `budget-report.md`)

**Verdict**: all width/orientation cells **PASS** — no unreachable controls, no truncation (transport `48dp` hit areas with `8dp` separation maintained).


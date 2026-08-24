---
title: 'Epic E13 - Artwork System & Visual Atmosphere (Stories 13.1-13.2)'
type: 'feature'
created: '2026-08-24'
status: 'done'
review_loop_iteration: 1
baseline_commit: 4846773
context:
  - '{project-root}/_bmad-output/planning-artifacts/epics-and-stories.md (Epic E13; Stories 13.1-13.2)'
  - '{project-root}/_bmad-output/planning-artifacts/ux-design-specification.md §9 (pipeline rules), DR13'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-E12-player-surfaces.md (player seams)'
---

## Epic Intent

**FR-35 + FR-36 complete here (13.1)**: bounded Coil pipeline over the shared OkHttp stack, ArtworkRef candidate-chain walking with zero host logic outside :catalog parse time, identical-bounds placeholders, connectivity-restored auto-retry. **NFR-5 completes here (13.2)**: player-scoped atmosphere — extraction -> clamped tonal mapping -> scrim engine with WCAG AA guaranteed mathematically, 600 ms ambient crossfade, neutral-brand fallback. Blur banned v1; global re-theming out of scope (DYNAMIC-mode seed feeds the existing 9.1 engine without flipping the default mode).

## Substrate Recon (verified against source)

| Need | API | Notes |
|---|---|---|
| Images | Coil 3.5.0 `coil-compose` + `coil-network-okhttp` pinned in catalog; designui has NEITHER yet | edge audit forbids designui->catalog, so the OkHttp client is INJECTED: `CatalogHttpClient.createArtworkVariant()` (exists, derives sharedBuilder) passed from :app |
| Chain | `ArtworkRef.candidates/candidateAfter/candidateAt`, canonical==cacheKey, synthetic ytimg chain | walker lives in designui consuming the contract only (AD-11 satisfied structurally) |
| Extraction | `PaletteExtractor.SeedColors(dominant/vibrant/muted)` + `DynamicSchemeFactory.scheme(seed,dark)` + `SwayTheme(dynamicSeed=)` | 9.1 engine head; hermetic synthetic-bitmap precedent exists |
| Connectivity | `ConnectivityObserver.online: StateFlow<Boolean>` in :app | retry trigger passed INTO designui as a Boolean signal (no app dep) |
| Seams | FullPlayer flat `surfaceContainer` backdrop; Mini hairline primary; QueueSheet default container | atmosphere slots behind these same params |
| Startup law | AD-10: no disk/network in Application.onCreate | ImageLoader init in MainActivity composition scope, caches lazy |

## Story Designs

### 13.1 Pipeline (`designui/images/SwayImages.kt`, `SwayAsyncImage.kt`)
`SwayImages.init(client, cacheDir)` builds ONE ImageLoader: memory ~25%, disk LRU 256 MB (NFR-10 bound), OkHttpNetworkFetcherFactory(callFactory=injected client); singleton setSafe + accessor; `resetForTest()`. `SwayAsyncImage(artwork, ...)` renders the IDENTICAL-bounds placeholder underneath (FR-36), walks candidates on error (AR-10), registers exhausted refs in `FailedArtworkRegistry`, and re-fires from canonical when the caller's `online` signal flips true (retry-trigger). SongRow + player thumbs/artwork gain optional artwork rendering; callers unchanged where null. Cache-hit-zero-network instrumented via counting interceptor + MockWebServer at ImageLoader.execute level (deterministic under Robolectric); forced-all-candidates failure walks exactly N requests then holds placeholder bounds (zero px shift, structural pair assertion).

### 13.2 Atmosphere (`designui/theme/Atmosphere.kt`, player seams)
Pure `ScrimEngine`: WCAG luminance ratios; overlay alphas START at scrim-strong .60 / soft .35 and RISE only until every sampled foreground role >=4.5:1 (>=3:1 large) over the blended backdrop — automated bright/dark-artwork x light/dark-scheme matrix BLOCKS on failure. `Atmospherics` extracts on Default dispatcher (<=128 px request, vibrant-preferred per 9.1 selection law), caches by canonicalUrl (re-view = ZERO recompute, proven by counter), falls back to neutral brand scheme on any failure (contrast laws still asserted). FullPlayer backdrop = animated backdrop color (600 ms crossfade; reduced-motion -> opacity-only quick fade) UNDER the vertical scrim gradient; Mini hairline + thumb take the accent tint; Queue container blends toward backdrop; status-bar echo [PROVISIONAL] guarded SDK<35. `SwayTheme(dynamicSeed=)` receives the playing cover's seed so the DYNAMIC engine is fully wired while ThemeConfig stays MONO-default until 15.1 persistence. Frame >24 ms metric + accessibility scanner runs recorded device-gated (SM-C2/E14 pattern).

## Verification Plan (one epic gate)

Planned: designui suites LoaderConfigTest / ChainWalkTest / FailedRegistryTest / CacheHitZeroNetworkTest / RetryTriggerTest / BoundsStabilityTest / ScrimEngineContrastMatrixTest / AtmosphereCacheTest / ExtractorBudgetTest / CrossfadeTest; :app player suites stay green via defaulted atmosphere params; five audits (theme-import law: raw color literals only inside :designui) + assembleDebug.

Non-negotiables unchanged: no blur anywhere; player-scoped atmosphere; AD-11 zero host URL logic outside :catalog; NFR-7 <1000 LOC/file.

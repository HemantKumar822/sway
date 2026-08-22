# Sprint R1 - Consolidated Research Summary and Planning Inputs

**Status:** COMPLETE
**Reference:** SuvMusic @ commit `d6636ca8` (main), GPL-3.0 - study only, zero code reuse
**Session notes:** phase-0-acquisition, phase-1-module-map, phase-2-startup, phase-3-metadata, phase-4-stream-resolution
**Purpose:** single source of truth feeding [PRD] -> [UX] -> [CA] architecture -> [CE] epics/stories

## 1. Verified picture of the reference

### Stack and scale

| Area | Reference uses | Note |
|---|---|---|
| Build | AGP 9.1.0, Kotlin/KSP 2.3.0, Gradle 9.3.1, JDK 21, compileSdk 37 / minSdk 26 | observed at d6636ca8 |
| UI | Compose BOM 2026.03.01 + CMP 1.10.0; real UI lives in `:app` (`composeApp` is a mid-migration KMP artifact) | correction vs blueprint s7 |
| Playback | Media3 1.10.1; `MusicPlayerService : MediaLibraryService`; ExoPlayer built in service onCreate | pattern we adopt |
| DB | Room v12 is the runtime database of record (explicit migrations); SQLDelight has ~1 consumer left | half-finished migration |
| DI | Hilt builds resource singletons; Koin resolves ViewModels via bridge | two systems live; we pick ONE |
| Extraction | NewPipeExtractor v0.26.4 (JitPack) + custom InnerTube direct client | two transports |
| Images / Net | Coil 3.4.0 / OkHttp (Ktor also declared) | |
| Native | NDK 27, C++23 audio sink only in `:app` | excluded from our v1 |

### Verified data-flow chains

- Search: SearchViewModel -> YouTubeRepository facade (343 lines over 9 sub-services; old god-class figure stale) -> YouTubeSearchService -> NewPipeExtractor -> Song.fromYouTube mapping -> OfflineCache fallback -> UI
- Browse/catalog: primary transport is direct InnerTube WEB_REMIX client over untyped JSON strings; NewPipe covers search + fallbacks only
- Stream resolve: playSong -> createMediaItem (only start index resolved; queue holds watch-URL placeholders) -> getStreamUrl -> LruCache(50) + in-flight dedup -> fallback ladder: NewPipe@www -> NewPipe@music -> InnerTube /player (IOS -> ANDROID_VR -> TV -> WEB_REMIX+PoToken)
- Expiry defense: four pull-based layers - URL expire= check (-5 min margin, 1 h backstop TTL), preload discard after 3 h at transition, HTTP 403/410 triggers purge + fresh resolve + resume position, buffering watchdog (>3 s downscale replay, >15 s full rebuild, then skip/stop)
- Format selection: no itags - pure kbps targets (LOW 70 / MEDIUM 160 / HIGH 512 / AUTO network-aware), DASH audio-only renditions; video >=720p uses MergingMediaSource(video-only + audio-only)
- Startup: attachBaseContext -> ACRA + crash-loop guard -> Koin start (lazy) -> async workers -> MainActivity splash gated on MainViewModel.isReady -> Hilt wave builds SessionManager/YouTubeRepository/MusicPlayer chain -> MusicController connects via SessionToken -> NavGraph(Home); anti-pattern found: runBlocking DataStore read on main startup path

### Author-admitted flaws (from their own docs)

1. Total dependence on YouTube private API - breaks for everyone at once
2. Ephemeral stream URLs patched with band-aid retries
3. Swallow-and-return-emptyList error handling -> blank screens; typed AppResult/AppError classes exist but are wired into zero YT-metadata paths
4. God classes (historically ~3000 LOC each; YouTubeRepository since refactored to facade + 9 sub-services)
5. Three parallel half-finished migrations (DI, DB, HTTP client) plus ToS exposure

## 2. Design lessons for our client (evidence-backed)

L1. One DI framework, one database, one HTTP stack - decided up front, never migrated mid-flight.
L2. Typed errors everywhere from day one: every repository call returns Result-like state; emptyList is never a failure signal.
L3. MediaLibraryService + ExoPlayer-in-service + MediaController from UI is the correct skeleton (verified working pattern).
L4. Resolve lazily: metadata for the whole queue, stream URL only for current track; placeholders re-resolved on transition.
L5. Expiry defense needs at minimum: read-time validation, error-triggered renewal (403/410), position-resume on renewal, and a stuck-buffer watchdog. A cache with in-flight dedup prevents resolve storms.
L6. Bitrate-target format selection is simpler and adequate vs itag bookkeeping.
L7. Facade + small sub-services beats god repositories; keep every class well under ~1000 LOC.
L8. Offline fallback caches for search/browse results materially improve perceived reliability.

## 3. Open questions that block architecture (must answer in Sprint P1 / CA)

Q-A. Transport strategy: NewPipe-only first release (simple, but browse/home limited) vs adding an InnerTube client like the reference? Decide in architecture phase.
Q-B. DI choice: Hilt vs Koin (recommendation input: reference needed a bridge because Koin alone could not safely own heavy singletons).
Q-C. Network library: OkHttp vs Ktor as single primary path.
Q-D. Minimum Android version (reference chose minSdk 26; blueprint question pending).
Q-E. Legal posture on InnerTube-style clients vs NewPipe-only (affects Q-A).

## 4. Ready for Sprint P1

Planning pipeline order: [PRD] bmad-prd -> [CU] bmad-ux -> [CA] bmad-architecture -> [CE] bmad-create-epics-and-stories -> [SP] bmad-sprint-planning. Each in a fresh context window, fed this file + blueprint.

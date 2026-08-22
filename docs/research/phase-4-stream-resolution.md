# Phase 4 — Stream Resolution

**Status:** COMPLETE
**Date:** 2026-08-23
**Reference commit:** `d6636ca8ba79549643185a6e074f4da88a339880` (branch `main`)
**Blueprint:** `docs/suvmusic-research-blueprint.md` (§9, §10, §14, §15 Phase 4)
**Prior notes:** `docs/research/phase-0-acquisition.md` · `phase-1-module-map.md` · `phase-2-startup.md` · `phase-3-metadata.md`
**Corrections applied:** `docs/decisions/0001-blueprint-corrections.md`

All paths below are relative to `reference/SuvMusic/` unless prefixed with our project path. Labels: **[FACT]** = directly observed at commit d6636ca8; **[INFER]** = engineering inference from observed code, no runtime trace.

> **Headline correction to earlier phases:** the `:media-source` Gradle module contains **only lyrics providers** (`media-source/src/main/java/com/suvojeet/suvmusic/providers/lyrics/` — six files, tree-verified). It plays **no role in stream resolution** at this commit. The real streaming code lives under `app/src/main/java/com/suvojeet/suvmusic/data/repository/youtube/streaming/` plus the player/service classes. The blueprint §7 assumption of an "audio-source interface module" does not hold here.

---

## Exit evidence (blueprint §15, Phase 4)

### Stream-resolution sequence diagram — tap → sound

```text
UI tap (song card / mini-player)
      ↓
PlayerViewModel.playSong(song, queue, startIndex)          ui/viewmodel/PlayerViewModel.kt:560-570
      |   resets radio/smartQueue, notifies recommendation engine,
      |   auto-generates continuation queue for standalone taps :561-575
      v
MusicPlayer.playSong(song, queue, startIndex, …)           player/MusicPlayer.kt:2607-2681
      |   cancels playJob + currentResolutionJob; pauses controller
      |   immediately (instant UI response); clears preload state
      v
createMediaItem(song, resolveStream = index==startIndex)   MusicPlayer.kt:2700-2836
      |   queue items built via parallel async/awaitAll, but ONLY the start
      |   index performs network resolution (:2651-2657); later items get
      |   placeholder URIs ("https://youtube.com/watch?v=<id>", :2776)
      |   audio path → youTubeRepository.getStreamUrl(song.id)
      |     (retry ×1 after 500 ms delay, else "placeholder.invalid", :2767-2773)
      |   builds MediaItem{uri, mediaId=song.id, customCacheKey,
      |     MediaMetadata(+high-res artwork), optional RequestMetadata extras}
      v
YouTubeRepository.getStreamUrl(videoId, forceLow)          data/repository/YouTubeRepository.kt:133-134
      |   offline guard → null before any network call
      v
YouTubeStreamingService.getStreamUrl(videoId)              .../streaming/YouTubeStreamingService.kt:131-210
      |   LruCache(50) hit? validate expire= param −5 min & 1 h backstop :135-147
      |   in-flight dedup via Deferred map (atomic computeIfAbsent)    :152-154
      |   (1) NewPipe StreamExtractor @ www.youtube.com    :159
      |   (2) NewPipe StreamExtractor @ music.youtube.com  :165
      |   (3) InnerTubeClient.resolveAudioUrl              :174-179 (final fallback)
      |   all null ⇒ Telemetry.report(AppError.Upstream)   :187
      v
InnerTubeClient.resolveAudioUrl(videoId, quality)          .../streaming/InnerTubeClient.kt:107-143
      |   POST /youtubei/v1/player sequentially:
      |   IOS → ANDROID_VR → TVHTML5_SIMPLY_EMBEDDED → WEB_REMIX+PoToken :74-98
      |   (12 s callTimeout each :53-55; direct-url formats only :204-221)
      v
URL string returned up the chain
      ↓
MusicPlayer: controller.setMediaItems(items, startIndex, pos)   MusicPlayer.kt:2666
      |   → MediaController → session → MusicPlayerService
      v
ExoPlayer (built in service onCreate)                      service/MusicPlayerService.kt:289-301
      |   custom MediaSource.Factory: if RequestMetadata extra
      |   "audioStreamUrl" present → MergingMediaSource(video, audio) :268-286
      |   else plain DefaultMediaSourceFactory(dataSourceFactory)
      v
CacheDataSource(SimpleCache ← Cronet/DefaultHttp) reads googlevideo URL
      ↓
AUDIO OUT … near track end: checkPreloadNextSong resolves next URL early
      (ticker-driven, MusicPlayer.kt:2242, 2417-2515)
```

**Lazy re-resolution on track transition (service side)** — `MusicPlayerService`'s own `onMediaItemTransition` detects placeholder URIs and resolves them even when no UI client is bound (Bluetooth/auto-advance): `service/MusicPlayerService.kt:378-419`, guarded by `AtomicBoolean serviceResolutionInProgress` (:141), calling `resolveStreamUrlWithRetry(videoId)` (:1745-1758: ≤2 attempts, each `withTimeoutOrNull(15 s)` around `getStreamUrl`, 1 s gap), then `replaceMediaItem` + `prepare` + `play` on the main thread (:391-411).

### Independent interface proposal — OUR resolver (our own design)

Our client defines one narrow boundary; nothing above it knows URLs exist. Signatures in prose:

- **Interface `StreamResolver`** (lives in our domain module):
  - `suspend fun resolveAudio(trackId: String, request: AudioRequest): AppResult<ResolvedAudio>` — the only method playback needs. `AudioRequest` carries our quality-preference enum, a network-classification flag (metered/unmetered), and `forceRefresh: Boolean`.
  - `suspend fun resolveVideo(trackId: String, request: VideoRequest): AppResult<ResolvedVideo>` — returns separate video/audio descriptors; our player layer decides whether to merge.
  - `fun invalidate(trackId: String)` — purge cached URL(s) after 403/410 or a user quality change.
  - `suspend fun prefetchNext(trackId: String, request: AudioRequest): PrefetchedStream?` — opportunistic, may return null silently; the value carries `fetchedAtEpochMs` so callers apply an age cap before trusting it.
- **Value `ResolvedAudio`**: streamUrl; container/mime hint; bitrateKbps; `expiresAtEpochMs` (parsed from the URL's own expiry parameter — never guessed from a fixed TTL); backend tag (which transport succeeded, for telemetry); stable cacheKey (track id + rendition discriminator).
- **Value `ResolvedVideo`**: video descriptor plus optional audio descriptor (mirroring dual-stream needs).
- **Contract:** every failure is a typed `AppError` (`NoNetwork`, `RateLimited`, `ContentUnavailable(permanent)`, `Upstream`) — never null-and-log. Permanent unavailability must short-circuit retries inside the resolver. Identical concurrent requests share one fetch (dedup is the resolver's job, invisible to callers).

This deliberately consolidates what SuvMusic spreads across `MusicPlayer` + `YouTubeStreamingService` + `InnerTubeClient` behind ONE seam, with expiry metadata as a first-class output rather than regex-parsed at read sites.

### Failure-state table

| # | Trigger | Detection | Response | User-visible state |
|---|---|---|---|---|
| F1 | Offline at resolve time | `isOnline()` guard before any network call (`YouTubeRepository.kt:133-134`) | null; `createMediaItem` retries once after 500 ms then writes `placeholder.invalid` URI (`MusicPlayer.kt:2767-2773`) | Loading spinner, then generic error when placeholder errors surface |
| F2 | Extractor break (page parses, zero streams) | `audioStreams.isEmpty()` → block returns null (`YouTubeStreamingService.kt:258-261`) | Backoff exhausted → host fallback → InnerTube chain; total-null reports `AppError.Upstream` to Telemetry (:184-188) | None if InnerTube succeeds; else see F5 |
| F3 | Signed URL expired mid-playback (HTTP **403/410**) | `onPlayerError`: `InvalidResponseCodeException.responseCode ∈ {403,410}` (`MusicPlayer.kt:1155-1157`) | Purge dead URL `clearCacheFor(id)` + drop resolved-id map entry + fresh `resolveAndPlayCurrentItem` + position restore (:1272-1297) | Transient "Stream expired, finding alternative..." (:1280) |
| F4 | Stuck buffering (stale/IP-bound URL, no clean HTTP error) | Ticker measures BUFFERING wall-time (`MusicPlayer.kt:2156-2206`) | Stage 1 >3 s & AUTO: purge + replay `forceLow` (:2159-2171). Stage 2 >15 s: purge + full rebuild `isRecovery=true` (:2173-2192). Stage 3 still stuck: skip next or stop (:2193-2205) | Stage 3: "Track unavailable — skipped to next song" or "Track unavailable right now. Try again later." + stop |
| F5 | All resolvers fail for the song | `resolveAndPlayCurrentItem` ends `streamUrl == null` after 2×20 s attempts + search fallback (+ cross-source leg) (`MusicPlayer.kt:1840-1949`) | Error state set, resolution aborted under mutex (:1939-1949) | Audio: "Could not load song. Please check your connection."; video mode: silent `videoNotFound=true` |
| F6 | Repeated playback errors on same song (>3) | `errorRetryCount` keyed by song id (`MusicPlayer.kt:1209-1234`) | Budget reset; linear mode `seekToNext()`; **shuffle mode pauses instead of cascade-skipping** (:1225-1232) | "Skipping unplayable song" / "Could not play song. Tap next to skip." |
| F7 | Placeholder URI about to be played (shuffle cascade risk) | Placeholder sniffing at transition and error paths (`youtube.com/watch`, `placeholder.invalid`, blank) (`MusicPlayer.kt:1000-1006`; service `:380-384`) | Pause IMMEDIATELY; single-flight guards (`currentResolutionJob?.isActive`, yield-and-recheck URI) prevent double-resolution races (:1053-1062, 1127-1141, 1199-1202) | Brief loading; no error |
| F8 | Audio-sink / decoder failure | Error codes AUDIO_TRACK_* / DECODING_* (`MusicPlayer.kt:1164-1170`) | Mode-switch reset (toggle isVideoMode twice), native DSP reset, re-resolve (:1247-1260); service re-prepares after 500 ms (`MusicPlayerService.kt:504-517`) | Momentary stall; error text only if recovery fails |
| F9 | Parse-container errors (3003/3004) | Error codes PARSING_CONTAINER_* (`MusicPlayer.kt:1171-1174`; service `:530-537`) | Treated as double-resolution race artifact: simple re-resolve; service explicitly refuses to skip (:537) | Invisible unless re-resolve also fails |
| F10 | Browser session sets items without URIs | `onSetMediaItems` pass-through check (`MusicPlayerService.kt:1024-1030`) | Resolve ONLY the start item under outer 20 s timeout; timeout returns placeholders for lazy resolution (:1054-1092) | Controls appear promptly; playback follows when resolved |

**No-HQ-Audio dependency statement:** our first-release resolver has **zero** dependency on the HQ Audio path. Nothing in our proposed `StreamResolver` surface references a remote-source backend, source-switch state, hybrid matching, cross-source fallback legs, REMOTE header injection, `_hq` cache keys, or any class existing solely for that feature (`RemoteAudioRepository`, `HqSongMatcher`, `HqDiagnostics`, `HqAudioRoute`, `RemoteConstants`, Retrofit `@HqAudioClient`). Our fallback chain terminates in typed failure once the YouTube transports are exhausted.

---

## Resolution call chain (class-by-class with citations)

| # | Class | File (under `app/src/main/java/com/suvojeet/suvmusic/` unless noted) | Role [FACT] |
|---|---|---|---|
| 1 | `PlayerViewModel.playSong` | `ui/viewmodel/PlayerViewModel.kt:560-570` | Thin delegate: resets radio/smart-queue state, notifies recommendation engine, calls into `MusicPlayer`. Standalone taps get an auto-generated related-songs continuation queue (:572-575). |
| 2 | `MusicPlayer.playSong` | `player/MusicPlayer.kt:2607-2681` | Client-side orchestrator (~3,700-line class). Cancels in-flight play/resolution jobs, pauses instantly, builds all queue MediaItems in parallel but resolves the network URL **only for the start index** (:2648-2657), then `setMediaItems(startIndex, startPositionMs)` + `prepare()` + `play()` under `queueMutex` (:2659-2674). |
| 3 | `MusicPlayer.createMediaItem` | `MusicPlayer.kt:2700-2836` | Per-SongSource URI policy: LOCAL/DOWNLOADED verify readability first (`isLocalUriReadable` :2688-2698; missing download falls back to streaming :2708-2718); YOUTUBE/YOUTUBE_MUSIC audio path = hybrid-HQ attempt (when selected) → `getStreamUrl` → one 500 ms-delayed retry → `placeholder.invalid`; video mode = `getBestVideoId` + `getVideoStreamResult` yielding videoUrl + separate audioUrl (:2736-2778). Non-resolving future items get watch-page placeholders (:2776). |
| 4 | `MusicPlayer.resolveAndPlayCurrentItem` | `MusicPlayer.kt:1740-2051` | Workhorse used by transitions, error recovery and watchdog rebuilds: mutex-guarded; optional HQ-vs-YT race; 2×(`withTimeoutOrNull(20 s)`) attempts with 1 s gap (:1840-1869); SEARCH FALLBACK resolves a different video id via title query (:1871-1897); CROSS-SOURCE fallback (:1899-1937, HQ-only leg); on success `replaceMediaItem` + `prepare` + `seekTo(oldPos)` + `play` (:2024-2036). |
| 5 | `YouTubeRepository.getStreamUrl` | `data/repository/YouTubeRepository.kt:133-134` | Facade forwarding with offline guard returning null; same pattern for `getVideoStreamResult` (:139-140) and `getBestVideoId` (:194-207). |
| 6 | `YouTubeStreamingService` | `data/repository/youtube/streaming/YouTubeStreamingService.kt:28-32` | Hilt singleton owning the URL LruCache, in-flight dedup maps, retry helper, and the NewPipe→InnerTube ladder; imports extractor types directly (:15-17 — one of only four app files touching `org.schabi.*`, phase-3 grep). |
| 7 | NewPipeExtractor `StreamExtractor` | invoked at `YouTubeStreamingService.kt:234-248` | `ytService.getStreamExtractor(url).fetchPage()`, then `.audioStreams` / `.videoOnlyStreams` / `.videoStreams`. Transport underneath is the studied `NewPipeDownloaderImpl` (OkHttp; phase 3). |
| 8 | `InnerTubeClient` | `.../streaming/InnerTubeClient.kt:44-233` | Direct `/youtubei/v1/player` POSTer trying four client identities in order (IOS 20.10.4 → ANDROID_VR 1.62.27 → TVHTML5_SIMPLY_EMBEDDED_PLAYER 2.0 → WEB_REMIX 1.20240101.01.00 with WebView PoToken, :74-98). Accepts only adaptiveFormats entries carrying a direct `url` — `signatureCipher`-only formats are skipped by documented policy (:26-30, :208). Requires `playabilityStatus == "OK"` (:181-185). |
| 9 | `PoTokenGenerator` (+ `PoTokenWebView`) | `.../streaming/potoken/PoTokenGenerator.kt:19-80` | WebView-minted proof-of-origin tokens: session streaming token bound to visitorData + per-video player token; self-described port of Metrolist's generator. Fail-safe: unsupported WebView, JS error, main-thread call, or 8 s timeout ⇒ null and the non-PoToken chain continues (:28-31, 47-77). |
| 10 | `MusicPlayerService.onCreate` (player build) | `service/MusicPlayerService.kt:216-301` | Owns ExoPlayer: DefaultLoadControl 10 s/50 s/2 s/4 s + 10 s back buffer (:224-233); custom DefaultAudioSink wrapping SpatialAudioProcessor (:235-247); dual-stream MediaSource.Factory (:249-287); music AudioAttributes w/ focus handling, becoming-noisy, WAKE_MODE_NETWORK (:289-301). |
| 11 | `MusicPlayerService` transition listener | `service/MusicPlayerService.kt:340-421` | Server-side lazy resolution (see exit evidence); also drives SponsorBlock segment loading, liked-state refresh, per-track loudness gain. |
| 12 | `MusicPlayerService.onSetMediaItems` | `service/MusicPlayerService.kt:1019-1106` | Async URI resolution for browser-originated sessions (Android Auto): expands a single browse item into its context playlist via `playlistContextCache` (:1041-1051), resolves only the start item, bounded by a 20 s outer timeout that returns placeholders on expiry (:1054-1092). |

## Format selection & quality ladder

All [FACT] unless noted.

**Audio (playback)** — `resolveStreamWithUrl` picks from NewPipe `audioStreams`:

- Preference from SessionManager; `AUTO` degrades by connectivity: WiFi→MEDIUM, metered→LOW (`YouTubeStreamingService.kt:224-231`).
- Bitrate targets in kbps: LOW→70, MEDIUM→160, HIGH→512, AUTO→160 (:267-272). (Enum labels describe ranges: LOW "48-64 kbps", MEDIUM "128 kbps", HIGH "256 kbps" — `core/model/.../AudioQuality.kt:6-10`; targets deliberately overshoot the labels.)
- Selection rule: among streams with `averageBitrate <= target` take the max; if none qualifies take the overall max (:274-277). No codec preference beyond extension bookkeeping (M4A/AAC→m4a, WEBM/OPUS→opus, default m4a, :280-284) used for naming only, not filtering. **No itag numbers appear anywhere in this code** — selection is purely bitrate-based over whatever list the extractor returns. **[INFER]** Immune to itag renumbering, but unable to prefer opus vs aac explicitly.
- Container choice implicit: audio playback always uses DASH-separated audio-only renditions; muxed formats are never chosen for audio-only mode.

**Audio (InnerTube fallback)** — same ladder in bits/s (70_000/160_000/512_000/160_000, `InnerTubeClient.kt:197-202`), filtered to `mimeType.startsWith("audio")` with a direct `url` (:206-208), same best-under-target else overall-max rule (:211-223).

**Video** — `resolveVideoWithUrl` (`YouTubeStreamingService.kt:379-476`):

- AUTO video resolves by WiFi: WiFi→MEDIUM(720p) else LOW(360p) (:392-399). Enum max resolutions: AUTO=720, LOW=360, MEDIUM=720, HIGH=1080 (`core/model/.../VideoQuality.kt:6-10`).
- Target ≥720p: best **videoOnlyStreams** with height in 1..target (height string-parsed from resolution labels) plus the overall-best audio stream as a SEPARATE stream, returned as `VideoStreamResult(videoUrl, audioUrl, resolution)` (:419-452).
- Below 720p or when the split fails: best **muxed** `videoStreams` under target (:454-472), audioUrl null.
- Extension heuristics: WEBM→webm else mp4; OPUS→opus else m4a (:440-441).

**Downloads** use a parallel ladder via `sessionManager.getDownloadQuality().maxBitrate` (`core/model/.../DownloadQuality.kt:7`; consumption `YouTubeStreamingService.kt:488-501`).

## Expiry detection & renewal mechanics

Four independent layers, all [FACT]:

1. **Avoidance at read time** — every cache read runs `cachedUrlStillValid` (`YouTubeStreamingService.kt:52-64`): within a 1 h backstop TTL AND the URL's own `expire=<epochSeconds>` query param (regex-extracted) must be ≥ now+5 min. In-code history: a longer fixed TTL once served dead URLs failing with 403 mid-song; googlevideo links are signed and die before any fixed TTL (:38-44). URLs lacking a parseable `expire=` log and rely on the backstop alone (:56-59).
2. **Preload age cap** — a pre-resolved next-track URL older than **3 h** counts as expired at transition and forces re-resolution (`MusicPlayer.kt:1071-1076`), consistent with the in-code estimate that YouTube URLs live 4-6 h.
3. **Playback-time detection** — `onPlayerError` classifies `HttpDataSource.InvalidResponseCodeException` codes **403/410** as expired-URL events (`MusicPlayer.kt:1155-1157`). Renewal = purge (`clearCacheFor`) + drop resolved-id map entry (so a stale match can be re-searched) + fresh `resolveAndPlayCurrentItem` + position restore (:1272-1297).
4. **Watchdog for silent stalls** — a 400 ms/1 s ticker (`startPositionUpdates`, :2102-2120) watches BUFFERING wall-time: >3 s triggers an AUTO-quality downscale replay; >15 s triggers a full URL rebuild ("the stream URL has likely expired (long pause, network switch, IP-bound URL)" — in-code comment :2096-2098); a second 15 s window on the rebuilt stream gives up and skips/stops (:2193-2205). Watchdog budgets reset on every genuine transition, excluding the watchdog's own replays (PLAYLIST_CHANGED excluded, :858-866).

Renewal is therefore **pull-based at use time**, never a background refresher: nothing proactively refreshes queued-but-unplayed items beyond the one-shot preload; staleness is caught pre-play (layers 1-2), at play (layer 3), or during play (layer 4).

## Retry & fallback chain

**Transport level** (`retryWithBackoff`, `YouTubeStreamingService.kt:77-125`): 3 attempts, initial 500 ms, ×2 factor capped 2000 ms; null-with-no-exception counts as retriable; `ContentNotAvailableException` is **permanent — aborts immediately** (:100-106).

**Resolution-level ladder for one YouTube audio URL** (each stage only on predecessor failure):

```text
[optional] Hybrid HQ race (user opted into REMOTE source); grace HQ_RACE_GRACE_MS = 2 s
    ↓ miss/fallback
(1) NewPipe StreamExtractor @ https://www.youtube.com/watch?v=<id>    (×3 backoff)
(2) NewPipe StreamExtractor @ https://music.youtube.com/watch?v=<id>  (×3 backoff)
(3) InnerTube /player: IOS → ANDROID_VR → TVHTML5_SIMPLY_EMBEDDED → WEB_REMIX+PoToken
    (sequential, 12 s callTimeout each; per-client exceptions logged :137-139)
(4) caller-level second full attempt after 500 ms–1 s delay
    (createMediaItem :2767-2773; resolveAndPlayCurrentItem :1840-1869)
(5) SEARCH FALLBACK: different video id via getBestVideoId(title+" Official Video")
    (5 s + 6 s timeouts, MusicPlayer.kt:1871-1897)
(6) CROSS-SOURCE fallback YouTube↔REMOTE by title+artist match (:1899-1937) — HQ-only leg
(7) give up → error strings in PlayerState (:1939-1949)
```

**Playback-error retries** (`onPlayerError`): per-song budget of **3**, exponential backoff `800 ms × 2^(n−1)` capped 5 s (:1209-1241). Single-flight guards prevent duplicate recovery racing the transition job (:1199-1202) and double `replaceMediaItem` parse-error loops (:1190-1197).

**When everything fails:** audio shows "Could not load song. Please check your connection."; video mode flips `videoNotFound=true` silently (:1939-1949). Shuffle deliberately STOPS instead of walking a placeholder-ridden queue (:1225-1228). The service keeps placeholder items and defers visible recovery to the client; its generic handler skips ahead after 2 s only for non-placeholder, non-parse errors (`MusicPlayerService.kt:539-548`). **[INFER]** No persistent "unplayable" marker exists — a fully-failed song re-attempts from scratch on its next play.

## MediaItem construction pipeline (incl. MergingMediaSource explanation, caching)

**Song → MediaItem** (`createMediaItem`, `MusicPlayer.kt:2789-2835`; same shape in `resolveAndPlayCurrentItem` :1958-2022 and the preload writer :2520-2551):

- `uri` — resolved stream URL, local content/file URI, or a placeholder sentinel.
- `mediaId` = `song.id` (the video id — identity anchor for queue↔state reconciliation at transitions, :903-926).
- `customCacheKey` — deliberate rendition discrimination via `audioCacheKey(song, url)` (:1419-1426): bare id for normal YouTube audio; `<id>_yt` when a REMOTE-source song plays from YouTube; `<id>_hq` for HQ CDN audio (excluded from our design); `<id>_<VIDEO_QUALITY>` in video mode (:1951, 2782-2786). In-code rationale: different renditions share one SimpleCache — without distinct keys CacheDataSource would serve bytes cached for one under a request for the other after a source switch (:1412-1418).
- `mediaMetadata` — title/artist/album from the model; artworkUri upgraded through `getHighResThumbnail` (`MusicPlayer.kt:3664+`; variant rules catalogued in phase 3); MEDIA_TYPE_MUSIC; playable / not-browsable flags.
- `requestMetadata.extras` — two out-of-band channels: (a) `"audioStreamUrl"` carrying the companion audio URL for dual-stream video (:2824-2833); (b) a `"headers"` bundle (Referer + User-Agent) required by the HQ CDN (:2805-2822 — excluded from our design).

**DataSource stack** (`di/CacheModule.kt`):

- `SimpleCache(cacheDir/media_cache, LeastRecentlyUsedCacheEvictor(size), StandaloneDatabaseProvider)` — size from settings hard-capped at 4 GB ("LRU eviction is a no-op with Long.MAX_VALUE") (:66-92). Phase 2 recorded the historical double-construction crash that motivated the DI bridge.
- Network upstream prefers **Cronet with QUIC enabled** plus explicit quicHints for googlevideo.com / youtube.com / youtubei.googleapis.com (in-code claim: "150-400 ms off the initial buffer fetch"), falling back to `DefaultHttpDataSource` with cross-protocol redirects (:38-64, 104-111).
- Player factory = `CacheDataSource.Factory(cache, upstream).setFlags(FLAG_IGNORE_CACHE_ON_ERROR)` (:94-121). No custom DataSource classes; behavior comes entirely from composition.

**Aggressive caching** — after every successful resolution `startAggressiveCaching(contentId, streamUrl)` (`MusicPlayer.kt:2054-2090`) runs a background `CacheWriter` through a throwaway CacheDataSource using a DataSpec whose key EQUALS the MediaItem's customCacheKey (key-match asserted in-code :2063), so the player later finds the bytes already in SimpleCache. Skipped for LOCAL/DOWNLOADED sources.

**MergingMediaSource — what merges and why.** In video mode targeting ≥720p, YouTube serves the best picture as a **video-only** rendition while the best audio lives in a separate **audio-only** rendition; a single muxed stream caps quality lower. `createMediaItem` therefore puts the video-only URL in the item URI and smuggles the audio URL in RequestMetadata extras (:2824-2833); the service's custom `MediaSource.Factory` reads that extra and wraps `createMediaSource(videoItem)` + `createMediaSource(audioItem)` into `androidx.media3.exoplayer.source.MergingMediaSource(videoSource, audioSource)` (`MusicPlayerService.kt:268-286`), giving ExoPlayer synchronized dual renderers fed by two progressive sources. Items without the extra (all audio-only and muxed playback) take the plain single-source path (:284-286). So the merge is **video+audio**, not artwork-related. **[INFER]** Both merged halves flow through the same cache-backed factory, so each benefits from SimpleCache independently.

**Gapless hand-off** — `checkPreloadNextSong` runs on the ticker and pre-resolves the NEXT item's URL once playback passes a configurable delay (default 1 s into the track; attempts throttled to one per 3 s; disabled in Repeat-One) (`MusicPlayer.kt:343-349, 2417-2458`). Non-shuffle mode writes it into the next MediaItem via `replaceMediaItem` (:2492-2493, 2520-2551); shuffle mode ONLY caches the URL in memory because replacing items mid-shuffle disrupted ExoPlayer ordering (~460 ms premature-transition bug per in-code history :2484-2491) — the transition listener applies it via its fast path (:1066-1125). An optional early-transition seek (gapless setting) jumps ~1.5 s before track end when the preloaded item holds a real URI (:2247-2267).

## Verified repository facts

1. Pipeline: `PlayerViewModel.playSong` → `MusicPlayer.playSong/createMediaItem` → `YouTubeRepository.getStreamUrl` (offline-guarded facade) → `YouTubeStreamingService` (cache/dedup/NewPipe ladder) → `InnerTubeClient` (4-client `/youtubei/v1/player` chain) → MediaItem handed to ExoPlayer built inside `MusicPlayerService.onCreate`. [FACT — citations throughout]
2. Stream URLs are held in an LruCache(50) validated against BOTH a 1 h backstop TTL and the URL's embedded `expire=` epoch minus a 5 min margin; identical concurrent requests share one in-flight Deferred via atomic computeIfAbsent. [FACT] (`YouTubeStreamingService.kt:38-72`)
3. Expiry manifests at runtime as HTTP 403/410 or indefinite BUFFERING; recovery always purges the cached URL before re-resolving. [FACT] (`MusicPlayer.kt:1272-1280, 2159-2192`)
4. Retry constants: transport 3×(500 ms→2 s exponential, permanent-abort on ContentNotAvailableException); playback errors ≤3 per song with 800 ms→5 s backoff; resolve loop 2×20 s timeouts. [FACT]
5. InnerTube fallback accepts only direct-`url` adaptive audio formats and requires `playabilityStatus=="OK"`; ciphered formats are intentionally not descrambled. [FACT] (`InnerTubeClient.kt:26-30, 181-208`)
6. PoTokens exist only for the last-chance WEB_REMIX identity: WebView-minted, visitorData-bound, appended as `pot=` on the stream URL; generation is fully fail-safe. [FACT] (`InnerTubeClient.kt:33-42, 224-229`; `PoTokenGenerator.kt:19-31`)
7. Dual-stream merging lives exclusively in the service's MediaSource.Factory keyed on RequestMetadata extra `"audioStreamUrl"`, merging video-only + audio-only progressive sources for ≥720p video mode. [FACT] (`MusicPlayerService.kt:249-287`)
8. MediaItems carry customCacheKeys discriminating renditions sharing one SimpleCache; a background CacheWriter pre-warms identical keys after each resolution. [FACT] (`MusicPlayer.kt:1412-1426, 2054-2090`)
9. The service resolves placeholder URIs independently of any UI client, guarded by an AtomicBoolean, with ≤2 × 15 s-bounded attempts. [FACT] (`MusicPlayerService.kt:141, 378-419, 1745-1758`)
10. `:media-source` contains only lyric-provider classes and contributes nothing to streaming. [FACT — module tree]
11. SessionManager is consulted for quality preferences but holds NO stream URLs; current URLs live only in MusicPlayer fields, the streaming-service LruCache, and persisted queue/restore JSON (phase 2 note #3; `SessionManager` cache-key list has no stream entries). [FACT]

## Engineering inferences

- **[INFER]** The layered expiry defense exists because googlevideo URLs are IP-bound as well as time-limited (the watchdog comment names "IP-bound URL", `MusicPlayer.kt:2096-2098`) — a URL can be valid-yet-unusable after a network change within its expiry window. Our design must treat renewal as mandatory on environment change too.
- **[INFER]** Preload actually begins ~1 s into the current track (default `nextSongPreloadDelay = 1`, `MusicPlayer.kt:343-349, 2421`), NOT "≈15 seconds before end" as the KDoc claims (:2414-2415) — comment drift. Behavior gives more lead time but means preloaded URLs sit longest, relying on the 3 h cap.
- **[INFER]** Recovery budgets are per-song instance state reset at transitions (:929-930, 858-866); there is no global rate limit, so pathological networks can burn the budget quickly across successive tracks.
- **[INFER]** The InnerTube IOS/ANDROID_VR/TV profiles send hardcoded version strings that will age out, eventually leaving WEB_REMIX+PoToken as the surviving direct-API path — a maintenance cliff the author's caveat comments acknowledge (`InnerTubeClient.kt:32-35`).
- **[INFER]** Because `getStreamUrl` returns a bare `String?`, every consumer re-derives context by sniffing URL substrings (`sourceOfStreamUrl`, `MusicPlayer.kt:1389-1398`) — exactly the information loss our `ResolvedAudio` value type fixes.
- **[INFER]** The service-side STATE_ENDED handler manually advances playback instead of relying on ExoPlayer auto-advance (`MusicPlayerService.kt:454-467`) — presumably to coordinate with placeholder guarding; exact motivation is not stated in code.

## Implications for our independent client

*(Proposed decisions — our product choices informed by this evidence, not descriptions of SuvMusic.)*

- **Proposed decision:** implement resolution as ONE `StreamResolver` interface (exit-evidence signatures) owned by our extraction layer, returning typed `ResolvedAudio/ResolvedVideo` carrying explicit `expiresAtEpochMs` and backend tag. Bare URL strings never cross module boundaries; expiry knowledge travels WITH the URL.
- **Proposed decision:** keep the proven ladder shape with our own ordering: primary transport (extractor) across hosts → direct-API fallback chain → typed failure. Adopt their two best micro-mechanisms as concepts: permanent-abort exceptions short-circuiting retries, and atomic in-flight dedup of identical requests.
- **Proposed decision:** adopt all four expiry layers as behavior (not code): read-time validation against the URL's own `expire=` param with a 5 min margin; 403/410 → purge+re-resolve+resume-position; an escalating buffering watchdog ending in skip-or-stop; an age cap on prefetched URLs.
- **Proposed decision:** make renewal service-side-capable from day one (their lazy service resolution works with zero UI bound — right answer for Bluetooth/auto-advance), implemented behind OUR resolver interface injected into the service with a single-flight guard mirroring their AtomicBoolean lesson.
- **Proposed decision:** keep rendition-discriminating cache keys (`<id>` audio, `<id>_<quality>` video) even without any `_hq` variant — cheap insurance against cross-rendition contamination when downloads/quality switching arrive.
- **Proposed decision:** reuse the concept of post-resolution background CacheWriter pre-warming keyed identically to the player's custom cache key.
- **Proposed decision:** follow the anti-cascade doctrine in shuffle: pause on placeholders before playing them, never auto-advance through unresolved items, stop after the retry budget with an actionable message.
- **Proposed decision:** typed failure states end-to-end (Decision 0001 D-03): their stringly "Could not load song..." becomes a structured PlaybackFailure with cause enum rendered to localized text in UI.
- **Proposed decision:** skip Cronet in v1 (OkHttp primary); revisit their QUIC-hint optimization post-v1. Keep FLAG_IGNORE_CACHE_ON_ERROR semantics regardless of transport.

## Open questions carried forward

- [ ] Q4.1: Actual lifetime/IP-binding envelope of today's googlevideo URLs (do 403 vs 410 correlate with time-expiry vs IP-change?) — needs a device experiment; source alone cannot answer.
- [ ] Q4.2: Whether NewPipeExtractor v0.26.4 still returns un-ciphered googlevideo URLs on the primary path, or whether signatureCipher-only responses are already common (would push traffic onto InnerTube/PoToken paths) — device test.
- [ ] Q4.3: Behavior of the WEB_REMIX client WITHOUT a minted PoToken on real consumer IPs (the code still sends the request when generation fails — is it accepted?) — device test.
- [ ] Q4.4: Why the service manually advances on STATE_ENDED rather than relying on ExoPlayer's own transition (see inference above) — confirm with the author's history (`git log -L`) before designing our advance logic.
- [ ] Q4.5: Interaction of aggressive CacheWriter pre-warming with LRU eviction under a small user-selected cache limit (does prewarming evict the currently playing item's buffered data?) — needs runtime measurement if we adopt the pattern.
- [ ] Q4.6: Does `onSetMediaItems`'s 20 s cap suffice on slow networks for Android Auto start-item resolution, given the inner resolve is itself 2×15 s worst case? Runtime check on low-bandwidth conditions.

---

*Evidence note complete. Blueprint §15 Phase 4 exit evidence delivered above (sequence diagram, resolver-interface proposal, failure-state table, no-HQ-Audio statement). Next phase per blueprint: Phase 5 — Playback foundation (one-song proof), which can reuse this phase's renewal/recovery requirements as acceptance tests.*

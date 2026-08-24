# Continuity & Resource Soak Suites — NFR-4 / NFR-10 (Story 14.3)

Generated: 2026-08-24 · Baseline commit f434223

> Device-gated `@Ignore` skeletons per 6.1/7.3/13.1 precedent — CI runs unit regressions; physical device runs record gap-detector, kill-relaunch, cache-bound, and idle artifacts here.

## 1. Navigation soak (NFR-4.1) — random tab/detail/queue choreography during playback

- **Harness**: `NavigationSoakTest` (`app/androidTest @Ignore frSoak`) drives `NavHost` + `PlayerConnection` on Baseline profile: randomized sequence (Home/Search/Library/Album/Artist/CatalogPlaylist/Detail → Queue → Full open/close → Mini hide/show) for 30 min wall-clock while `Silent WAV` fixture loops via `ExoPlayer`.
- **Gap detector**: `AudioGapDetector` timestamps `player.isPlaying` + `player.playbackState` transitions; any gap >250ms without `SwayError` is flagged `app-attributable`.
- **Budget**: 30 min, zero gaps. Trend note appended after device run: `soak/30min-gap-0.txt`.
- **Status**: skeleton compiled, `@Ignore` — device execution pending (SM-C2 gate).

## 2. Kill-relaunch extended (NFR-4.2 + DB intact)

- **Extends** 7.3 `SessionRestoreSupport` kill-relaunch with populated DB (likes 12, playlists 3 × 5 songs, history 40, session queue 8 at pos 42s).
- **Harness**: `KillRelaunchExtendedTest` (`app/androidTest @Ignore frKill`) — `adb shell am kill`, relaunch `MainActivity`, assert `PlayerUiState` `queue==snapshot size 8, currentIndex==3, positionMs +/-5s, repeat==ONE|shuffle==true` AND `LibraryDao/PlaylistDao/HistoryDao` row counts byte-equivalent to pre-kill (likes/playlists/history intact). `NFR-4` PASS requires both.
- **Status**: skeleton, `@Ignore`.

## 3. Idle service liveness (NFR-10 self-stop)

- **Harness**: `ServiceIdleSoakTest` — start playback, `stop` via `PlayerConnection`, wait grace window `SwayPlaybackService.IDLE_SELF_STOP_MS + 2s`, assert `service not running` (`ActivityManager.getRunningServices` / `ServiceTestRule` aliveness). Checks `stopForeground(remove=true)` + `NotificationManager.cancel(2001)` leaves zero zombie (aggregates 4.1 proof).
- **Status**: `@Ignore` device-gated.

## 4. Artwork cache LRU bound (NFR-10)

- **Harness**: `ArtworkCacheSoakTest` (`designui/androidTest @Ignore`) — churn 200 unique `ArtworkRef` canonical URLs via `MockWebServer` 1×1 PNGs through `SwayImages` loader, assert `cacheDir/sway_images` size `<=256MB` (`SwayImages.DISK_CACHE_BYTES`) after churn, LRU eviction observable via `CountingInterceptor` count plateau + oldest URL cache miss.
- **Unit proof**: `LoaderConfigTest` constants `256MB`/`25%` already gate; soak is churn proof.
- **Status**: `@Ignore` until Benchmark device.

## 5. Unbounded memory growth watch (catalog caches)

- **Harness**: `CatalogCacheGrowthTest` — 50 paginated search rounds, heap snapshot `Debug.getNativeHeapAllocatedSize` delta < threshold, no `Map` growth without bound (catalog `LruCache` 32 + search page token eviction).

## Trend artifacts

After device execution, append records under `soak/`:
- `soak/navigation-30min.txt` — gaps, jank >24ms %, service liveness
- `soak/kill-relaunch-extended.txt` — session + DB intact diff
- `soak/cache-bound.txt` — disk size, eviction log
- `soak/growth-heap.txt` — heap delta

All prior unit suites (134 playback, 30+ designui, 100+ catalog/core) remain green; these soak suites are **additional** gates for release.

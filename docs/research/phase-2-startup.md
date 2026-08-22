# Phase 2 — Application Startup Trace

**Status:** COMPLETE
**Date:** 2026-08-23
**Reference commit:** `d6636ca8ba79549643185a6e074f4da88a339880` (branch `main`)
**Blueprint:** `docs/suvmusic-research-blueprint.md` (§9, §10, §14, §15)
**Prior notes:** `docs/research/phase-0-acquisition.md` · `docs/research/phase-1-module-map.md`
**Corrections applied:** `docs/decisions/0001-blueprint-corrections.md`

All paths below are relative to `reference/SuvMusic/` unless prefixed with our project path. Labels: **[FACT]** = directly observed at commit d6636ca8; **[INFER]** = engineering inference from observed code, no runtime trace.

---

## Exit evidence (blueprint §15, Phase 2)

### Startup sequence diagram

```text
process start (Zygote fork of com.suvojeet.suvmusic)
      ↓
Application.attachBaseContext                       SuvMusicApplication.kt:46-76
   ├─ MusicPlayer.setApplicationContext(this)       :52   [KMP player static ctx]
   └─ initAcra { … }  (try/catch → log & continue)  :55-75
      ↓
Hilt generates SingletonComponent (code-gen, not runtime work)
      ↓
Application.onCreate                                SuvMusicApplication.kt:80-172
   ├─ CrashLoopGuard.install(this)                  :87   [safe mode if ≥3 early crashes]
   ├─ Telemetry.install(LogFailureReporter)         :94-96
   ├─ OfflineCache.init(this)                       :101  [disk JSON cache for search results]
   ├─ seedLogoVariantMirrorIfMissing()              :112  [runBlocking DataStore read! main thread]
   ├─ startKoin { androidContext; modules(koinAppModules) }  :118-122
   │     koinAppModules = hiltBridgedModule + coroutineScopesModule
   │                      + sqlDelightModule + viewModelsModule   KoinModules.kt:205-209
   │     (all Koin singles are LAZY — nothing constructs here except registration)
   ├─ scope.launch { AppLog.init }                  :125-131   [async]
   ├─ scope.launch { SQLDelight health check }      :142-158   [async, try/catch, log-only]
   └─ scope.launch { setupWorkers() }               :161-164   [2 periodic WorkManager jobs, async]
      ↓
Activity: MainActivity.onCreate                     MainActivity.kt:175-321
   ├─ applyVariantSplashTheme() BEFORE super        :189  [sync SharedPreferences read]
   ├─ installSplashScreen(); keepOnScreen until     :191-197
   │     MainViewModel.uiState.isReady == true
   ├─ @Inject fields materialize NOW (Hilt):        :119-135
   │     networkMonitor, sessionManager,
   │     youTubeRepository, downloadRepository,
   │     musicPlayer  ← MusicPlayer.init{} runs here:
   │        connectToService()                      MusicPlayer.kt:402,710-729
   │          SessionToken(ctx, ComponentName(MusicPlayerService))
   │          MediaController.Builder(...).buildAsync()
   │        ⇒ MusicPlayerService is CREATED+BOUND at first Activity launch
   ├─ requestPermissions() (POST_NOTIFICATIONS etc.) :223,368-373
   └─ setContent { theme ← SessionManager flows;
                   SuvMusicApp(...) }                :225-320
      ↓
Composable SuvMusicApp                              MainActivity.kt:454+
   ├─ koinViewModel(): Player/Main/PlaylistManagement/Home/Search  :478-491
   ├─ NavGraph(startDestination = Destination.Home) :985
   ├─ playbackInfo ← PlayerViewModel.playbackInfo   :528  [distinct-filtered PlayerState]
   ├─ LaunchedEffect: restoreLastPlayback() if no deep link and no song  :698-705
   └─ splash dismissed when MainViewModel.isReady   MainViewModel.kt:58-65
      ↓
FIRST FRAME: Home route → TvHomeScreen | HomeScreen by form factor   NavGraph.kt:127-130
   HomeViewModel.init { loadHomeContent() }         HomeViewModel.kt:90-136
   ⇒ YouTubeRepository home sections (+ staggered recommendations)
```

### First-screen state source

- **[FACT]** Start destination is hard-coded: `startDestination = Destination.Home // Always start at Home` (`MainActivity.kt:985`, parameter default `Destination.Home` at `NavGraph.kt:97`). Routes are `@Serializable` sealed types (`Destinations.kt`) — type-safe Navigation.
- **[FACT]** The Home screen's data comes from `HomeViewModel` (Koin-owned), whose `init` calls `loadHomeContent()` → `loadData(forceRefresh)` against `YouTubeRepository`/`RemoteAudioRepository`, then staggered recommendation loads at 2s/1.5s delays (`HomeViewModel.kt:90-136`). So first paint is skeleton/empty UI; content arrives via network.
- **[FACT]** Playback state reaches the shell without any service round-trip in the UI layer: `PlayerViewModel.playbackInfo` is a `distinctUntilChanged` projection of `musicPlayer.playerState` (`PlayerViewModel.kt:92-103`), collected once at app-shell scope (`MainActivity.kt:528`); `MusicPlayer.playerState` is updated by a `Player.Listener` attached to the `MediaController` (`MusicPlayer.kt:720,732-759`). Raw per-tick position flow is deliberately NOT collected at root to avoid whole-app recomposition (`MainActivity.kt:520-535` comments).
- **[FACT]** Last-session queue restore happens on first composition only when there is no deep-link intent and no current song: `playerViewModel.restoreLastPlayback()` (`MainActivity.kt:698-705`); it parses JSON queue persisted in `SessionManager` and calls `playSong(autoPlay = false)` + delayed seek (`PlayerViewModel.kt:1333-1378`).

### Initialization failure points

| # | Failure | Observed behavior | Evidence |
|---|---|---|---|
| F1 | ACRA init throws | Caught; logged `"ACRA init failed — crash reporting disabled"`; startup continues without crash reporting | `SuvMusicApplication.kt:54-75` **[FACT]** |
| F2 | Repeated early crashes (<60 s uptime) | After 3 consecutive, next launch runs SAFE MODE: poison-prone disk caches wiped, native DSP skipped; counter survives process death via `commit()` prefs | `CrashLoopGuard.kt:25-85` **[FACT]** |
| F3 | SQLDelight driver/schema misconfigured | Health-check try/catch logs `"SQLDelight DB health check failed"`; **app continues** | `SuvMusicApplication.kt:142-158` **[FACT]** |
| F4 | Room schema mismatch / migration missing | **No `fallbackToDestructiveMigration`** — comment states intent that mismatch "must fail loudly (IllegalStateException at open)". Room opens lazily at first DAO use, so this surfaces mid-startup when repositories first query, crashing the process | `DatabaseModule.kt:25-35` (core/data) **[FACT]** |
| F5 | SimpleCache constructed twice | Documented past crash `"IllegalStateException: Another SimpleCache instance uses the folder"` — reason the Hilt→Koin bridge exists | `HiltKoinBridge.kt:57-66` **[FACT]** |
| F6 | CronetEngine build fails | Returns null → data sources fall back to `DefaultHttpDataSource`; startup unaffected | `CacheModule.kt:38-63` **[FACT]** |
| F7 | MediaController connect fails | Error lands in `playerState.error = "Failed to connect to music service"`, not a crash | `MusicPlayer.kt:726-728` **[FACT]** |
| F8 | Logo-variant mirror read fails | Caught, falls back to `LogoVariant.DEFAULT`; note this runs `runBlocking` on main thread — worst-case latency before first frame | `SuvMusicApplication.kt:201-217` **[FACT]** (ANR risk = **[INFER]**) |
| F9 | Network down at cold start | No startup gate on connectivity; Home load fails into empty/swallowed states (author-admitted flaw #5); offline banner appears after `NetworkMonitor` flips | `HomeViewModel.kt:113-136`; phase-1 flaws summary `SYSTEM_DESIGN_FLAWS.md:112-121` **[FACT]** |
| F10 | Worker enqueue throws inside `applicationScope.launch` | Unhandled in code shown; would hit default handler → ACRA/crash. Not traced at runtime | `SuvMusicApplication.kt:160-164` **[INFER]** |

---

## Manifest inventory

Source: `app/src/main/AndroidManifest.xml`.

**[FACT] Application** — `.SuvMusicApplication` (:62); `usesCleartextTraffic="false"` (:72); `enableOnBackInvokedCallback` (:73); backup disabled with data-extraction rules (:63-65).

**[FACT] Activities**
- `.MainActivity` (:91-170), `launchMode="singleTop"`, PiP-capable, splash-screen theme, deep links: `https://music.youtube.com` autoVerify (:109-114), YouTube hosts (:118-125), audio file `VIEW` with `audio/*` mime (:128-135), custom scheme `suvmusic://` (song/album/playlist/artist/search/play, :145-155), `suvmusic://lastfm-auth` (:158-163), TV leanback launcher (:166-169).
- Launcher entry deliberately lives on **activity-aliases**: 16 aliases total, one enabled at a time for user-selectable logo variants; `.LauncherClassic` enabled=true by default (:184-197), rest disabled (:199-430). Switching kills the process so the launcher re-binds (:180-182 comment).

**[FACT] Services**
| Service | Type | Exported | Lines |
|---|---|---|---|
| `.service.MusicPlayerService` | `foregroundServiceType="mediaPlayback"`; intent-filter `androidx.media3.session.MediaSessionService` + `MediaLibraryService` + `MediaBrowserService` (Android Auto/Wear discoverability) | true | :433-442 |
| `.service.DownloadService` | `dataSync` | false | :445-448 |
| `.service.PlaylistImportService` | `dataSync` | false | :451-454 |

**[FACT] Receivers**
- `.glance.SuvMusicWidgetReceiver` — Glance app widget (:80-89).
- `.pip.PipActionReceiver` — PiP play/pause/next/previous actions (:480-488).
- `.shareplay.ListenTogetherActionReceiver` — notification approve/reject (:491-500).
- `androidx.media3.session.MediaButtonReceiver` — MEDIA_BUTTON (headset/lock-screen button resurrection) (:501-507).

**[FACT] Providers** — `FileProvider` for APK install (:457-465); WorkManager's default `WorkManagerInitializer` **removed** for on-demand init (:468-477), paired with `SuvMusicApplication` implementing `Configuration.Provider` with `HiltWorkerFactory` (`SuvMusicApplication.kt:38,41,174-177`).

**[FACT] Permissions** — INTERNET, ACCESS_NETWORK_STATE (:6-7); READ_MEDIA_AUDIO + READ_EXTERNAL_STORAGE≤SDK32 (:10-12); WRITE_EXTERNAL_STORAGE≤SDK28 (:14-15); FOREGROUND_SERVICE, FOREGROUND_SERVICE_MEDIA_PLAYBACK, FOREGROUND_SERVICE_DATA_SYNC, POST_NOTIFICATIONS (:18-21); WAKE_LOCK (:24); REQUEST_INSTALL_PACKAGES (:27); VIBRATE (:30); WRITE_SETTINGS protected (:33-34).

---

## DI graph summary (who provides what)

### Construction engine: Hilt (`SingletonComponent`)

**[FACT]** Hilt modules actually installed:

| Module | Key `@Provides @Singleton` | File |
|---|---|---|
| `AppModule` | SessionManager · YouTubeRepository (composed of 8 sub-services: streaming/search/account/playlist/browse/catalog/library-action/lyrics) · LocalAudioRepository · OkHttpClient (tracer interceptor, HTTP/3-first) · Gson · `@HqAudioClient` OkHttpClient · RemoteAudioApiService + HqAudioPlaylistApiService (**Retrofit is live — HQ-Audio only**) · RemoteAudioRepository · MusicHapticsManager · **MusicPlayer** (14 deps incl. Cache + `@PlayerDataSource`) · LyricsRepository (5 providers) · ListenTogetherClient/Manager · WorkManager | `app/di/AppModule.kt:22-305` |
| `CacheModule` | Media3 `DatabaseProvider` (StandaloneDatabaseProvider) · CronetEngine? · **SimpleCache** (`cache/media_cache`, LRU, size from settings capped 4 GB) · `@PlayerDataSource` CacheDataSource.Factory (Cronet→DefaultHttpDataSource fallback) · `@DownloadDataSource` factory | `app/di/CacheModule.kt:26-147` |
| `core:data DatabaseModule` | **Room AppDatabase** (`"suvmusic_database"`, MIGRATION_11_12, destructive fallback refused) + 5 DAOs | `core/data/.../di/DatabaseModule.kt:18-62` |
| `core:data RepositoryModule` | binds `LibraryRepositoryImpl` → `LibraryRepository` | `core/data/.../di/RepositoryModule.kt:10-16` |
| (others in :extractor/:media-source/:scrobbler/:updater per phase 1) | — | — |

**[FACT]** Everything above resolves lazily on first injection — nothing heavy is built during `Application.onCreate` itself; the first real construction wave is MainActivity's `@Inject` field injection (`MainActivity.kt:119-135`).

### Resolution facade: Koin

**[FACT]** `startKoin` registers four modules (`KoinModules.kt:205-209`):

1. `hiltBridgedModule` (:56-133) — ~45 `single { bridge(androidContext()).x() }` entries that fetch pre-built instances from Hilt via `EntryPointAccessors.fromApplication` (`HiltKoinBridge.kt:146-150`): SessionManager, OkHttpClient, both repositories, MusicPlayer, SimpleCache, both DataSource factories, Room `AppDatabase` + all DAOs, LibraryRepository, LastFm client/config, UpdateChecker, recommendation/queue/AI-equalizer services, etc.
2. `coroutineScopesModule` (:141-145) — Koin-owned `@ApplicationScope CoroutineScope` (deliberately NOT bridged; documented harmless duplication).
3. `sqlDelightModule` (:159-162) — Koin owns construction outright: `DatabaseDriverFactory(androidContext())` + `buildDatabase(...)`. Comment: "NOT routed through the Hilt bridge because Hilt knows nothing about SuvMusicDatabase".
4. `viewModelsModule` (:170-199) — all 25 ViewModels via `viewModelOf(::…)`, including PlayerViewModel, MainViewModel, UpdateViewModel, HomeViewModel. Their `@HiltViewModel` annotations were removed ("after this, no @HiltViewModel remains", :195).

### Who consumes which container at runtime

- **[FACT]** `MainActivity` is BOTH: `@AndroidEntryPoint` + six `@Inject` fields (Hilt) AND Koin `by viewModel()` delegates for MainViewModel/UpdateViewModel (`MainActivity.kt:113-135,116-117`).
- **[FACT]** All Compose screens resolve ViewModels through `org.koin.compose.viewmodel.koinViewModel` (`MainActivity.kt:478-491`, `NavGraph.kt:48`).
- **[FACT]** `MusicPlayerService` is pure Hilt (`@AndroidEntryPoint`, 12 injected fields, `MusicPlayerService.kt:50-91`).

---

## Runtime choices confirmed (resolves Phase-1 open question Q1.1)

### Verdict: Hilt vs Koin — *both are live, with strictly separated roles*

- **[FACT]** Neither is dead. This is not "Hilt with vestigial Koin" nor vice versa: the comment claiming "The app still routes all DI through Hilt; Koin starts with an empty module list" (`SuvMusicApplication.kt:114-117`) is **stale** — `koinAppModules` today contains 45 bridged singletons, a scope, the SQLDelight pair, and all 25 ViewModels (`KoinModules.kt`).
- **Verdict [FACT-based]:** *Hilt builds every resource-holding singleton; Koin is the lookup facade through which the entire UI obtains ViewModels (which receive Hilt-built dependencies via the bridge).* A third category (SQLDelight DB, application CoroutineScope) is Koin-exclusive. Removing either container today breaks the app: removing Hilt loses all singletons; removing Koin loses ViewModel resolution and SQLDelight wiring.
- Migration direction per code comments: chunk "1d" plans to delete Hilt and let Kown construct directly (`KoinModules.kt:52-54`, `HiltKoinBridge.kt:69-72`) — unfinished at this commit.

### Verdict: Room vs SQLDelight — *Room serves the app; SQLDelight is opened but unused*

- **[FACT]** Room `AppDatabase` (version 12, entities: ListeningHistory, LibraryEntity, PlaylistSongEntity, DislikedSong, DislikedArtist, SongGenre, LyricsEntity; `exportSchema = true`) is provided by Hilt, bridged into Koin, and its DAOs feed `LibraryRepositoryImpl` and the app repositories (`AppDatabase.kt:23-41`; `DatabaseModule.kt:24-61`).
- **[FACT]** SQLDelight `SuvMusicDatabase` has exactly ONE consumer in `:app`: the startup health check in `SuvMusicApplication.onCreate` (`KoinJavaComponent.getKoin().get()` → `db.listeningHistoryQueries.countAll()`). Repo-wide grep for `SuvMusicDatabase|DatabaseDriverFactory` returns only: `:core:db` sources, the Koin module definitions, and the health check. No repository or ViewModel touches it.
- **Verdict:** at this commit the runtime database of record is **Room**; SQLDelight is a staged-but-dormant migration artifact kept warm (and health-checked) for the future KMP path. Schema files: Room exports to `$projectDir/schemas` in `:core:data` (phase 1, `core/data/build.gradle.kts:8-10`); SQLDelight `.sq` sources live under `core/db/src/commonMain/sqldelight` (phase 1 module map).

---

## Verified repository facts

1. Process entry is `.SuvMusicApplication` (`AndroidManifest.xml:62`), annotated `@HiltAndroidApp`, also implementing Coil `SingletonImageLoader.Factory` and WorkManager `Configuration.Provider` (`SuvMusicApplication.kt:37-38`). Coil ImageLoader (30 % memory cache, 150 MB disk cache) is built lazily on first image request via the factory hook (`SuvMusicApplication.kt:179-199`).
2. Eager-at-onCreate work: CrashLoopGuard install, Telemetry install, OfflineCache init, logo-mirror seed (synchronous), startKoin registration. Deferred-to-coroutine work: AppLog init, SQLDelight health check, two periodic workers (NewReleaseWorker 12 h, PeriodicUpdateWorker 24 h) (`SuvMusicApplication.kt:87-164,219-254`).
3. MusicPlayerService.onCreate order: notification channel `"media_playback_channel"` → `setMediaNotificationProvider(CustomNotificationProvider())` → DefaultLoadControl(10 s min / 50 s max / 2 s start / 4 s rebuffer) → custom DefaultAudioSink wrapping `SpatialAudioProcessor` → dual-stream `MergingMediaSource` factory (video-only + audio-only merge keyed on RequestMetadata extra `"audioStreamUrl"`) → `ExoPlayer.Builder` with music AudioAttributes + handleAudioFocus(true), `handleAudioBecomingNoisy(true)`, `WAKE_MODE_NETWORK` → `MediaLibrarySession.Builder(this, player, callback)` (`MusicPlayerService.kt:216-301,751`).
4. Foreground/notification: custom provider wraps Media3's `DefaultMediaNotificationProvider`, forces `FLAG_ONGOING_EVENT`, relies on manifest `foregroundServiceType="mediaPlayback"` for Android 14+ FGS typing (`MusicPlayerService.kt:1575-1598`). Custom session commands: LIKE / REPEAT / SHUFFLE / START_RADIO / STOP_RADIO / SKIP_SEGMENT (`MusicPlayerService.kt:96-101`). Android Auto browse tree served from same service (root/home/library/downloads/local/artists/albums/liked/playlists ids, `MusicPlayerService.kt:107-115`).
5. Service start timing: no explicit `startService` for playback anywhere in startup code. The service comes alive when `MusicPlayer.connectToService()` builds a `MediaController` from `SessionToken(context, ComponentName(MusicPlayerService))` — and `connectToService()` runs in `MusicPlayer.init{}` (`MusicPlayer.kt:364-402,710-729`). Since `MusicPlayer` is a Hilt singleton injected into MainActivity, **the media service binds during first Activity creation**, not at process start.
6. Lazy stream resolution lives server-side too: on `onMediaItemTransition`, placeholder/invalid URIs are resolved in-service via `resolveStreamUrlWithRetry(videoId)` guarded by an AtomicBoolean (`MusicPlayerService.kt:380-390`).
7. Splash gating: `splashScreen.setKeepOnScreenCondition { !mainViewModel.uiState.value.isReady }`; `isReady` flips true in MainViewModel `init` right after launching a cache-auto-clear check — i.e., it gates on DI + first VM construction, not on network data (`MainActivity.kt:194-197`; `MainViewModel.kt:34-65`).
8. Theme/splash variant selection reads a synchronous SharedPreferences mirror written by SessionManager, because DataStore is async and would race the splash (`MainActivity.kt:178-189,382-398`; `SuvMusicApplication.kt:103-112`).
9. Permission UX: POST_NOTIFICATIONS denial triggers an explanatory toast because the silent consequence is "playback controls won't appear on the lock screen" (`MainActivity.kt:159-172`).
10. WorkManager on-demand init: default initializer removed in manifest; `workManagerConfiguration` supplies `HiltWorkerFactory` (`AndroidManifest.xml:468-477`; `SuvMusicApplication.kt:41,174-177`).

## Engineering inferences

- **[INFER]** First-frame cost is dominated by Hilt's MainActivity injection wave (SessionManager → 8 YouTube sub-services → MusicPlayer chain including CronetEngine + SimpleCache). Nothing parallelizes this; the splash keep-on-screen condition hides it but doesn't shorten it. Runtime profiling needed to confirm.
- **[INFER]** Because `MainViewModel.isReady` flips without awaiting anything slow, the real gate users perceive is Compose first composition plus the synchronous `runBlocking` logo-seed read — a self-inflicted stall on first launch after upgrade (mitigated by early-return if mirror exists, `SuvMusicApplication.kt:203`).
- **[INFER]** The bridge means Koin `single`s are effectively Hilt scoping: e.g., `bridge().musicPlayer()` returns the identical instance to the service's injection. Any future "Koin vs Hilt" behavioral difference (e.g., eager vs lazy) is therefore invisible for bridged types.
- **[INFER]** If the Room DB open fails (F4) while `restoreLastPlayback` or Home library queries run, the crash occurs post-splash — ACRA will capture it, and CrashLoopGuard may escalate to safe mode on repeat, but safe mode does not wipe the Room database (only cache dirs per `CrashLoopGuard.kt:87-103`), so a genuinely bad schema could still loop. Not verified at runtime.

## Implications for our independent client

*(Proposed decisions — our product choices informed by this evidence.)*

- **Proposed decision:** exactly one DI container (Hilt), used end-to-end including ViewModels — never reproduce the bridge. The bridge works, but it doubles the graph surface and leaves stale comments that mislead readers (this very trace had to debunk one).
- **Proposed decision:** exactly one database (Room), no second "staged" store; export schemas from day one and register explicit migrations, refusing destructive fallback exactly as the reference does (`DatabaseModule.kt:31-34` — behavior worth copying even though we write our own code).
- **Proposed decision:** keep Application.onCreate minimal and non-blocking: crash guard → telemetry → async init coroutines. Ban `runBlocking` on main thread (see F8); derive splash variants via themes resolved synchronously only from tiny SharedPreferences mirrors if truly needed — or drop logo-variant switching entirely for v1.
- **Proposed decision:** adopt the reference's service topology as-is conceptually: one `MediaLibraryService`-style service declared with `mediaPlayback` FGS type + Media3/MediaBrowser intent-filters, ExoPlayer created in service `onCreate`, notification via a thin wrapper over `DefaultMediaNotificationProvider`, MediaButtonReceiver declared, and the UI holding a long-lived singleton client whose `init` connects a `MediaController`. This yields "service alive from first Activity, foreground only when playing" semantics without manual lifecycle juggling.
- **Proposed decision:** make stream re-resolution a service-side responsibility too (placeholder URI + retry on transition), matching observation #6 — it keeps background/auto-advance working with no UI bound. Our implementation stays independent code behind our own StreamResolver interface (phase 4 will detail).
- **Proposed decision:** gate the splash on "DI + first frame composed," never on network; Home content loads into visible skeletons with staggered secondary sections (pattern observed in HomeViewModel) — good UX shape worth imitating with our own typed-error states instead of empty-list swallowing.
- **Proposed decision:** copy none of: logo-variant alias switching (16 aliases), HQ-Audio Retrofit clients, Listen Together/PiP receivers, widget, TV launcher — all v1 out-of-scope per blueprint §3.

## Open questions carried forward

- [ ] Q2.1: Actual cold-start wall-clock split (Hilt wave vs Compose first frame vs `runBlocking` seed) — requires a device run with method tracing; cannot be settled from source. *(Feeds our performance budget.)*
- [ ] Q2.2: Does any code path construct `SimpleCache` outside `CacheModule` (e.g., tests, backup restore)? The bridge doc records a historical double-construction crash; a repo grep found only the module, but runtime verification pending.
- [ ] Q2.3: Is the SQLDelight health check the ONLY thing keeping `:core:db` linked into the APK? If removed, would `:app` still compile? (Determines how safely our own build excludes it.) — verify during our architecture phase, not in the reference.
- [ ] Q2.4: Behavior when `POST_NOTIFICATIONS` is denied on Android 13+: does Media3 still promote the service to foreground silently? Manifest declares the permission; runtime outcome needs a device test.
- [ ] Q2.5: Which component first touches each Room DAO after cold start (order of DB-open trigger) — relevant to where failure F4 would surface. Needs runtime tracing.
- [ ] Q2.6 (carried from Phase 1 Q1.4/Q1.6): role of `implementation(project(":composeApp"))` in `:app` runtime and protobuf message usage — not touched by startup path beyond imports seen here; defer to Phase 7 skim.

---

*Evidence note complete. Next phase per blueprint §15: Phase 3 — YouTube metadata (search/parsing/artwork), answering carried questions Q1.2.*

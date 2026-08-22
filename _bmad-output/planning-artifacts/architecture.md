---
name: Sway v1
type: architecture-spine
purpose: build-substrate
altitude: initiative (keeps epics)
paradigm: Hexagonal (ports & adapters) over a layered Android module graph
scope: Sway — independent Android music client, v1 scope exactly as frozen in prd.md §6
status: final
created: 2026-08-23
updated: 2026-08-23
binds: [FR-1..FR-40, NFR-1..NFR-10, C-1..C-8, UJ-1..UJ-5, SM-1..SM-3]
sources:
  - _bmad-output/planning-artifacts/prd.md
  - _bmad-output/planning-artifacts/ux-design-specification.md
  - docs/research/sprint-R1-summary.md
  - docs/research/phase-1-module-map.md .. phase-4-stream-resolution.md
  - docs/decisions/0001-blueprint-corrections.md
  - docs/decisions/0003-design-direction.md
companions:
  - _bmad-output/planning-artifacts/architecture.memlog.md
---

# Architecture Spine — Sway v1

Headless run: this spine was produced by the system architect acting as smart owner proxy.
Every decision carries rationale; owner-vetoable items are tagged **PROVISIONAL**. The
memlog beside this file holds the chronological decision record.

## Design Paradigm

**Hexagonal (ports & adapters), realized as a layered Gradle graph.** The domain lives in
one pure-Kotlin module (`core:model`) that owns vocabulary (Song, Queue, Playback Session),
the typed result/error union, and the two load-bearing **ports**:

- `CatalogSource` — metadata in: search, browse, album/artist/catalog-playlist detail.
- `StreamResolver` — streams out: Source ID in, short-lived playable URL + expiry metadata out.

Everything Android-flavored is an **adapter**: `:catalog` adapts NewPipeExtractor into
`CatalogSource`/`StreamResolver`; `:playback` adapts Media3 into a player the UI can talk
to without knowing URLs exist; `:core:data` adapts Room/DataStore/disk caches into
repositories. The dependency law (AD-5) makes transport-type leakage structurally
impossible — the reference enforced the same idea by discipline alone and leaked it four
times (phase-3 boundary grep); we enforce it with the build graph.

This directly serves C-2, C-4, C-5, C-7 and the builder JTBD (PRD §2.1): each layer
(catalog → resolution → playback → persistence → UI) is provable before the next lands.

## Open Questions Resolved

### OQ-1 — Transport strategy: **NewPipeExtractor-only in v1** (CLOSED)

- **Verdict:** v1 metadata + streams come exclusively from NewPipeExtractor (JitPack,
  pinned), wrapped immediately behind our own `CatalogSource` + `StreamResolver` ports.
  The ports are designed so a future InnerTube adapter slots in with **zero changes** to
  repositories, ViewModels, or UI — it is just a second implementation bound in `:app`.
- **Evidence check:** phase-3 verified the reference's browse/catalog depth (home shelves,
  moods, related, full artist discographies) runs on a hand-rolled InnerTube WEB_REMIX
  client over untyped JSON; NewPipe covers search + stream fallbacks only. NewPipe-only
  therefore genuinely cannot supply feed-grade browse depth today.
- **Consequence (honest tradeoff):** FR-3 ships its designed degraded branch — **Home =
  Search-first landing page** (brand header, prominent search entry, Library shortcut
  tiles; offline-safe by construction). The UX spec already designed both branches (§6.1),
  so no downstream rework is needed. FR-3's feed shelves become a v1.x feature unlocked by
  an InnerTube adapter; FR-6's extended tier (artist albums/singles rails) degrades to
  "sections absent omit cleanly", already specified.
- **Why not InnerTube now:** it doubles transport maintenance (client identities aging out,
  PoToken machinery, schema-drift parsing — reference inference Q3.3/Q4-x), widens legal
  exposure (OQ-4), and delays the core loop the PRD bets on. Reversibility is cheap: the
  adapter seam exists from day one.
- **Escalation trigger:** if NewPipeExtractor stops returning playable (un-ciphered) URLs
  on its primary path — a real risk flagged in phase-4 Q4.2 — the InnerTube adapter moves
  from Deferred to critical ahead of schedule.

### OQ-2 — DI framework: **Hilt, end-to-end** (CLOSED)

- **Verdict:** Hilt owns everything: singletons, `@HiltViewModel`, `@AndroidEntryPoint`
  service, binding sites in `:app`. No Koin, no bridge, ever (L1/D-01, C-1).
- **Evidence:** the reference needed a Hilt-to-Koin bridge because Koin alone could not
  safely own heavy resource singletons (documented double-construction crash of their media
  cache — phase-2 F5). Bridges double the graph surface and breed stale documentation; we
  refuse the pattern outright.

### OQ-3 — Network library: **OkHttp, single primary path** (CLOSED)

- **Verdict:** one OkHttp stack serves NewPipe's downloader implementation, Coil 3 (native
  `coil-network-okhttp` artifact), and any direct call we ever make. Ktor, Cronet, and
  Retrofit are excluded from v1 (C-1/L1; Cronet QUIC-hint optimization noted as post-v1
  tuning per phase-4 implications).

### OQ-4 — Legal posture: **personal-use framing until release-gate review** (CLOSED, PROVISIONAL)

- **Verdict:** no distribution claims anywhere in code, docs, or store assets until a
  dedicated release-gate legal review. NewPipe-only transport keeps the posture simpler
  than InnerTube-style clients (public extractor, F-Droid precedent for vendoring).
  Constraint B.1 stays binding: study-only independence, license attribution via FR-40.
- **Revisit condition:** any distribution intent (F-Droid, Play, APK share) reopens this
  before a single binary leaves the machine.

## Invariants & Rules

### AD-1 — One extraction boundary; extractor visible only to `:catalog` [ADOPTED from evidence]

- **Binds:** FR-1–FR-7, FR-12–FR-15; C-4; NFR-2.
- **Prevents:** `org.schabi.newpipe` types leaking past the adapter (reference leaked them
  into four app files); raw JSON Strings crossing a module boundary (their second,
  untyped seam); UI coupled to a transport we intend to swap.
- **Rule:** only `:catalog`'s build file may declare the NewPipeExtractor dependency.
  Public signatures of `CatalogSource`/`StreamResolver` speak exclusively in `core:model`
  types and return `SwayResult`. Verified mechanically: a CI dependency-audit greps for the
  coordinate outside `:catalog` and for extractor imports outside `:catalog` (both must be
  zero).

### AD-2 — Exactly one DI framework: Hilt [ADOPTED — OQ-2]

- **Binds:** all modules; NFR-8.
- **Prevents:** parallel containers, bridge classes, split ViewModel ownership — the
  exact half-migration state the reference still runs.
- **Rule:** `@HiltAndroidApp`, `@HiltViewModel`, `@AndroidEntryPoint` only; bindings live
  in the owning module's DI module and are aggregated in `:app`; no service-locator style
  lookups anywhere; a second DI artifact appearing in any version catalog fails review.

### AD-3 — Exactly one HTTP stack: OkHttp [ADOPTED — OQ-3]

- **Binds:** `:catalog` (downloader), `:designui` (Coil network), any future direct call.
- **Prevents:** Ktor+Cronet+Retrofit accumulation (reference ran three stacks plus a dead
  Retrofit declaration); divergent timeouts/proxies/logging across stacks.
- **Rule:** one `OkHttpClient` singleton (per-profile variants only via the same builder:
  a metadata client and a leaner artwork client are permitted derivations); Coil uses
  `coil-network-okhttp`; no other HTTP artifact may enter the version catalog.

### AD-4 — Legal posture: personal use, no distribution claims [PROVISIONAL — OQ-4]

- **Binds:** whole repo; B.1; NFR-9.
- **Prevents:** accidental public-distribution claims before the release-gate legal review;
  InnerTube-specific exposure entering early.
- **Rule:** README/docs carry personal-use framing; release checklist gate re-runs the
  posture review; FR-40 attribution screen lists every shipped dependency's license.

### AD-5 — Module graph and dependency law [ADOPTED]

Seven modules, small on purpose (C-7). Arrows point only toward `:core:model`.

```text
                        :app   thin shell: MainActivity, nav host, Hilt aggregation,
                          |    manifest + service registration, DI binding sites
        ┌─────────────────┼──────────────────┬─────────────────┐
        v                 v                  v                 v
   :designui         :playback          :core:data         :catalog
   Sway theme on     PlaybackService    repositories,      NewPipeExtractor
   M3 Expressive,    (MediaLibrary      Offline Fallback   adapter — the ONLY
   components,       Service), player   Cache, settings    importer of extractor
   scrim engine      connection facade  + session restore  types; implements ports
        │                 │                  │                 │
        │                 │                  v                 │
        │                 │            :core:database          │
        │                 │            Room entities + DAOs    │
        │                 │            (imported only by       │
        │                 │             :core:data)            │
        v                 v                v                   v
                     :core:model  — pure Kotlin: models, Source ID,
                     SwayResult/SwayError, ArtworkRef,
                     ports: CatalogSource, StreamResolver
```

- **Binds:** every module; C-7; NFR-7.
- **Prevents:** crosswise dependencies (catalog ↔ playback ↔ designui mutual imports);
  anything below `:core:data` touching Room directly; god modules growing past budget.
- **Rule:** allowed edges exactly: app → all; designui → core:model; playback → core:model,
  core:data; core:data → core:model, core:database; core:database → core:model; catalog →
  core:model. Any other edge is rejected in review. Every class stays far under 1000 LOC
  (CI LOC budget); facades delegate to focused sub-services when a concern grows
  (repository shape proven by the reference's own refactor).

### AD-6 — Playback topology: service owns the player, UI holds a controller [ADOPTED — C-3/C-4]

- **Binds:** FR-8–FR-11, FR-16–FR-25; NFR-4; C-3.
- **Prevents:** playback logic in ViewModels (reference's MusicPlayer anti-pattern);
  audio dying when the UI process detaches; queue-wide eager URL resolution.
- **Rule:**
  1. `SwayPlaybackService : MediaLibraryService` is the only player owner; ExoPlayer is
     built in service `onCreate` with music `AudioAttributes`, focus handling enabled,
     becoming-noisy enabled, network wake mode.
  2. UI talks exclusively through a `PlayerConnection` facade in `:playback` wrapping one
     long-lived `MediaController`; it exposes commands (play context, jump, toggle modes)
     and a hoisted `PlayerUiState` StateFlow. Position ticks are NOT broadcast app-wide —
     only scrubbers subscribe (state-sync discipline, UX §12.8).
  3. **Lazy resolution:** any play action builds the Queue from context (FR-22) as
     Source-ID placeholders; **exactly one** `StreamResolver.resolveAudio` executes up
     front, for the start item (FR-12 is verified by a resolver test double counting
     resolves). Transitions resolve just-in-time inside the service with a single-flight
     guard, so background/auto-advance works with zero clients bound.
  4. Optional `prefetchNext()` fires opportunistically during playback (age-capped value;
     disabled in repeat-one). It never counts against the FR-12 up-front budget and never
     replaces items mid-shuffle — shuffle applies the prefetched URL at the transition
     fast path.
  5. Shuffle/repeat modes persist via settings storage and restore with the session
     (FR-11/25). Restoration never auto-plays (FR-25).
  6. The placeholder URI scheme (`sway://pending/<sourceId>`) is defined in exactly one
     place in `:playback`; no other module constructs, mutates, or string-sniffs
     placeholders — resolution state is owned service-side.
  7. Play History recording (the "10 s played" trigger, FR-34) is exclusively service-side:
     one write path through HistoryRepository, so no UI-layer observer can double-record or
     fight recency.
  8. The service self-stops when idle and released (NFR-10); notification follows Media3
     defaults wrapped thin (FR-17/A-10).

### AD-7 — `StreamResolver` port + three-layer expiry defense + mandatory dedup [ADOPTED — C-5/C-6]

- **Binds:** FR-12–FR-15, FR-14 watchdog; NFR-3; SM-2.
- **Prevents:** band-aid retries over ephemeral URLs; resolve storms from concurrent
  failures; bare URL strings stripped of expiry context (phase-4 inference: stringly URLs
  forced consumers to sniff substrings).
- **Rule (signatures in prose):**
  - `suspend fun resolveAudio(trackId, AudioRequest): SwayResult<ResolvedAudio>` is the
    only method playback needs. `AudioRequest` carries quality enum (AUTO default /
    LOW / MEDIUM / HIGH — bitrate targets per L6, AUTO adapts to metered/unmetered),
    and forceRefresh. AudioRequest, ResolvedAudio, and the quality enum live in
    `core:model` beside the port — settings (:core:data), the resolver (:catalog), and the
    player (:playback) all consume the same declaration; local re-declarations are banned.
  - `fun invalidate(trackId)` purges cached URL(s) after 403/410 or a quality change.
  - `suspend fun prefetchNext(trackId, request): ResolvedAudio?` is opportunistic, may
    return null silently; callers apply the age cap before trusting it.
  - `ResolvedAudio` carries: url, **expiresAtEpochMs parsed from the URL's own expiry
    parameter — never a guessed fixed TTL**, bitrateKbps, container hint, backend tag
    (which transport succeeded, for diagnostics), rendition cache key (track id +
    quality discriminator — cheap insurance against cross-rendition contamination).
  - Identical concurrent requests share one fetch (in-flight dedup is the resolver's
    job, invisible to callers).
- **Defense layers (collapsed from the reference's four to the three we need):**
  1. **Read-time validation** — any cached or prefetched URL is checked at use: its own
     expiry param minus the −5 min margin must lie in the future; otherwise discard and
     resolve fresh before play. The prefetch age cap folds into this single check.
  2. **Error-triggered renewal with position resume** — HTTP 403/410 mid-play: purge,
     deduped fresh resolve, resume within ±3 s of last audible position (SM-2).
  3. **Stuck-buffer watchdog** — buffering wall-clock escalation: >3 s brief-stall retry
     with downscale replay; >15 s sustained stall triggers full stream rebuild; repeated
     rebuild failure skips to next Queue item and marks the failed Song's row with a
     typed reason (FR-14). Thresholds are initial targets (P-5), tunable with device
     evidence.
  - Media3's built-in stuck-player detection (StuckPlayerException, builder-configurable
    timeouts since 1.10.x/1.11) is kept as a backstop only — its defaults are far looser
    than ours; our ticker-driven policy remains authoritative. Verify exact API surface
    at build time.
- **Quality selection** is bitrate-target based over returned streams: best stream whose
  average bitrate is under target, else overall max; AUTO maps WiFi→MEDIUM-class,
  metered→LOW-class targets (L6/C-6). Changes apply from next resolution (FR-15).

### AD-8 — Persistence contract: one Room database, explicit migrations only [ADOPTED]

- **Binds:** FR-30–FR-34, FR-25; NFR-4; C-1 (one database).
- **Prevents:** a second staged store (reference's dormant SQLDelight); silent schema
  surgery via destructive fallback (reference refused it too — behavior worth keeping);
  history stacking duplicates; unbounded growth.
- **Rule:**
  - Exactly one Room database in `:core:database`; `exportSchema = true` from migration 1;
    migrations are explicit and tested against exported schemas; destructive fallback is
    refused — mismatch fails loudly.
  - Entities (sketch): `SongEntity` (Source ID PK; title + rawTitle preserved separately;
    artist name/id; album name/id; durationMs; artworkUrl; nullable `likedAt` timestamp —
    NULL means not liked; indexed on likedAt); `PlaylistEntity` (autogenerated id, name,
    createdAt/updatedAt); `PlaylistSongEntity` (playlistId FK + songId FK composite PK,
    position column indexed per playlist, addedAt); `HistoryEntity` (songId FK, playedAt;
    upsert keyed by songId so replays update recency instead of stacking — FR-34; capped
    at the most recent 500 by a trim on write); `QueueStateEntity` (singleton row:
    ordered song snapshots as JSON, currentIndex, positionMs, shuffle/repeat flags,
    savedAt). Snapshots mean session restore renders the Mini Player fully offline
    (UJ-4/UJ-5) without network.
  - DAO boundaries: `LibraryDao` (liked flow ordered by likedAt desc, set/clear),
    `PlaylistDao` (lists with counts, add/remove/reorder in one transaction, rename,
    delete), `HistoryDao` (reverse-chronological paged flow, record-upsert, trim),
    `QueueStateDao` (load/save singleton). All APIs are `suspend` or return `Flow`;
    multi-step edits run in `@Transaction`.
  - Snapshot retention: catalog song rows referenced by likes/playlists/history/queue are
    never auto-deleted in v1 (single-user scale makes this safe); a future GC pass is
    Deferred.
  - Queue state has exactly one representation and one serializer: the canonical
    `QueueSnapshot` (core:model) serialized by code owned by `:core:data` into the
    QueueStateEntity row. No other module may (de)serialize queue state — two snapshot
    shapes would silently break FR-25 restore.
  - Room 2.8.4 is chosen over the new room3 package deliberately: room3 went stable only
    weeks ago with a package rename and coroutine-only APIs; boring technology wins for a
    solo-dev v1, and the DAO layer isolates a later upgrade. **PROVISIONAL** — revisit at
    first schema bump if ecosystem tooling demands it.

### AD-9 — Typed results end-to-end; the failure→state mapping is the contract [ADOPTED — C-2/D-03]

- **Binds:** FR-37, FR-38, FR-4, FR-14; NFR-2; all repositories/resolvers.
- **Prevents:** swallow-and-return-emptyList (the reference's most-admitted flaw; their
  typed classes existed but were wired into zero metadata paths); blank screens;
  "offline" indistinguishable from "genuinely nothing found".
- **Rule:** every repository and resolver returns `SwayResult<T>` — sealed Success(data) /
  Failure(SwayError). `emptyList` is never a failure signal; local reads distinguish an
  honest empty collection from failure. Error taxonomy and its single mapping onto UX
  states (FR-37 quintet):

| SwayError category | Meaning | UI state (every data surface renders exactly one) |
|---|---|---|
| Offline | no connectivity at call time | offline banner raised; serve Offline Fallback Cache marked stale (FR-4) where present, else error+retry with offline copy |
| RateLimited | upstream throttling/challenge (HTTP 429 family) | error+retry; copy rotates after 2nd consecutive failure |
| UpstreamUnavailable | extractor breakage/schema drift/non-2xx source | error+retry, honest "couldn't load" copy |
| Parse | payload malformed (logged with shape info) | error+retry (user sees UpstreamUnavailable copy) |
| ContentNotFound | item gone/permanently unavailable | empty-state variant "no longer available"; playback skips the track with typed reason |
| Storage | local DB/preferences IO failure | typed error panel; Library surfaces degrade honestly, never blank |
| Unknown(cause) | unexpected, logged with cause chain | error+retry |

  Track-level playback failures reuse the taxonomy: failed SongRow shows the glyph +
  reason category (FR-14); renewal failures surface as "couldn't play — skipped".
  Code-review checklist bans catch-log-return-empty patterns; unit tests inject every
  category per repository (NFR-2 verification clause).

### AD-10 — Startup law: nothing blocking on the main thread [ADOPTED]

- **Binds:** NFR-1, FR-25, FR-38, FR-27; UX launch flows.
- **Prevents:** the reference's runBlocking DataStore read on the startup path; splash
  gating on network; cold starts that render blank when offline.
- **Rule:** `Application.onCreate` performs no disk, network, or preferences work (debug
  builds install StrictMode with death-penalty violations triaged to zero before release).
  First frame composes immediately; splash dismisses on composition, never on data.
  Post-composition coroutines: Playback Session restore reads `QueueStateDao` and lands a
  paused session (Mini Player present per FR-27, no audio — FR-25); theme loads from
  DataStore asynchronously (system scheme paints first, 150 ms fade on switch — accepted,
  reversible). Offline launch opens normally into the offline-safe Library/Home-landing
  (FR-38) because the degraded Home branch is local by construction (AD-1 consequence).

### AD-11 — Image pipeline: Coil + normalized artwork values [ADOPTED]

- **Binds:** FR-35, FR-36; NFR-10; UX §9 pipeline guarantees.
- **Prevents:** the reference's three competing ad-hoc URL fix-up sites (same track loading
  different resolutions per surface); broken-image states; unbounded disk cache.
- **Rule:** artwork travels as an `ArtworkRef` value object computed once at parse time in
  `:catalog`: canonical URL + precomputed candidate chain. Normalization rules: ytimg/youtube
  hosts walk maxresdefault → sddefault → hqdefault → mqdefault on load failure; googleusercontent/
  ggpht hosts get their size parameters rewritten descending (1080 → 720 → 544) with the original
  last; missing artwork synthesizes from the video-id pattern; the canonical URL doubles as
  the stable cache key. The candidate chain is data: `:designui` walks the ordered list on
  load failure and contains zero host-specific URL logic of its own — host knowledge exists
  only in `:catalog`'s parse-time normalization. Coil 3.5.0 with memory cache ≈25% of app
  memory and a 256 MB LRU disk
  cache (A-6); identical-bounds branded placeholders (zero layout shift, FR-36); automatic
  retry when connectivity returns; extraction (dominant/vibrant colors, ≤128 px decode,
  ≤50 ms CPU budget, cached alongside the artwork entry) runs off-main-thread in `:designui`'s
  scrim engine, which computes scrims until AA contrast holds (NFR-5). **No runtime blur on
  any surface in v1** (UX-P6; decision 0003).

### AD-12 — Audio focus and route policy [ADOPTED]

- **Binds:** FR-19, FR-20; UX §6.13 parity expectations.
- **Prevents:** hand-rolled dual focus implementations (reference implemented focus twice);
  overlap with other apps' audio; speaker blast on route disconnect.
- **Rule:** Media3-native semantics via music `AudioAttributes` with focus handling enabled:
  transient loss pauses (ducking applied automatically where the system grants
  transient-may-duck), focus regained resumes only for transient losses; permanent loss
  stops. Becoming-noisy (headphone/BT disconnect) pauses immediately (<1 s); reconnecting a
  route never auto-resumes. This fixes the UX-flagged open point ("architecture owns
  duck-vs-pause") as: pause on transient loss, duck only where the platform grants it.
  Reversible cheaply if the owner prefers hard-pause-everywhere.

### AD-13 — Performance budgets are release gates [ADOPTED]

- **Binds:** NFR-1, NFR-6, FR-8, FR-9, FR-23, FR-27; SM-C2.
- **Prevents:** polish regressions shipping because nobody measured; motion ambition
  outrunning the Baseline Device.
- **Rule:** the budget table (§Performance budgets) is checked by macrobenchmark/profiled
  runs during animation on the Baseline Device profile; a violated budget blocks release
  regardless of aesthetic gain (SM-C2 is binding). Lists virtualize with stable keys
  (= Source ID); transitions animate only transform/alpha properties; skeletons share one
  shader.

## Consistency Conventions

| Concern | Convention |
| --- | --- |
| Naming | Models plain nouns (Song, Album, Artist, CatalogPlaylist, Playlist, QueueSnapshot); Room classes suffixed Entity/Dao; ports named as nouns (CatalogSource, StreamResolver); errors SwayError.Category; composables PascalCase; ViewModels suffixed ViewModel |
| Identity | Source ID is a non-blank String from the catalog; blank id ⇒ factory rejects the item at parse time (never a keyless model downstream); local Playlist ids are app-generated and namespaced apart from catalog ids |
| Data & formats | Timestamps epoch milliseconds UTC; durations milliseconds internally, rendered m:ss at the edge; persisted queue JSON carries full song snapshots; cache keys = canonical URL (artwork) / Source ID + rendition discriminator (streams); Offline Fallback Cache = JSON files under cache dir with 72 h deletion TTL, served only on failure and always stale-marked, strict element-type validation on read (corrupt entry deleted — reference's shipped-crash lesson) |
| State & mutation | Repositories expose Flows for state and suspend functions for commands; single source of playback truth is the service, projected through PlayerUiState; position ticks scoped to scrubber subscribers only; UI never mutates DB rows directly — always through repositories |
| Errors & logging | Failures travel as SwayResult values, never thrown across module boundaries; logging is tag-consistent, never logs user content beyond titles/artists needed for diagnostics; no stack traces reach the UI |
| Threading | Injected Dispatchers (IO for adapters, Default for parse/extraction); main thread does composition and player commands only |
| Settings | One DataStore preferences file: theme, audio quality (if FR-15 survives OQ-6), last shuffle/repeat modes; keys namespaced; no synchronous reads at startup (AD-10) |
| Vocabulary | PRD §3 glossary terms verbatim in code identifiers and docs (Catalog, Playlist vs Catalog Playlist, Stream Resolution, …) |

## Stack

Verified current on the web 2026-08-23 (reference versions used only as calibration; pin
exact patches at build time — minor bumps within a line are permitted without a spine update).

| Name | Version |
| --- | --- |
| Kotlin / KSP | 2.4.10 (KSP matched to Kotlin) |
| JDK toolchain | 21 |
| AGP / Gradle | 9.3.0 / 9.5.0 |
| compileSdk / minSdk / targetSdk | 36 / 26 / latest stable (36) |
| Compose (ui/foundation BOM line) | 1.11.x stable |
| Material 3 (Expressive APIs stable in this line) | 1.4.0 |
| navigation-compose | 2.9.x |
| Media3 (session/exoplayer/datasource) | 1.11.0 |
| Room | 2.8.4 |
| Hilt (+ androidx.hilt) | 2.60.1 / 1.4.0 |
| Coil (compose + network-okhttp) | 3.5.0 |
| OkHttp | 5.5.0 |
| NewPipeExtractor (JitPack: com.github.TeamNewPipe) | v0.26.5 |
| DataStore (preferences) | 1.2.x |
| kotlinx-coroutines / serialization | 1.11.x / 1.9.x |
| Test: JUnit, Robolectric, MockWebServer3, Compose UI test, Macrobenchmark | current stable lines |

Material 3 Expressive availability note: the formerly experimental Expressive APIs were
promoted to stable in material3 1.4.0 (stable July 15, 2026) — MotionScheme, expressive
shape/motion tokens are usable without alpha opt-ins; remaining bleeding-edge Expressive
additions sit in the 1.5.0-alpha line and are **not** consumed in v1. Sway tokens map onto
ColorScheme/Typography/Shapes/MotionScheme plus a small extension-token layer in `:designui`.

Media3 1.11.0 (Aug 5, 2026) brings configurable stuck-player detection
(StuckPlayerException) — adopted as the AD-7 backstop, not the primary watchdog.

## Structural Seed

Module tree (edges fixed by AD-5; content is the code's business):

```text
Player/
  app/            # MainActivity, SwayNavHost, Hilt app module, manifest,
                  # SwayPlaybackService registration, application-level wiring
  core/
    model/        # Song, Album, Artist, CatalogPlaylist, Playlist, QueueSnapshot,
                  # ArtworkRef, SwayResult, SwayError, CatalogSource, StreamResolver
    database/     # Room db, 5 entities, 4 DAOs, schemas/ export, migrations
    data/         # CatalogRepository, LibraryRepository, HistoryRepository,
                  # SessionRestoreRepository, SettingsRepository(DataStore),
                  # Offline Fallback Cache, Hilt data bindings
  catalog/        # NewPipeCatalogSource, NewPipeStreamResolver, downloader impl,
                  # parser/mapper layer (extractor types die here), artwork
                  # normalization at parse time
  playback/       # SwayPlaybackService, PlayerConnection facade, PlayerUiState,
                  # queue builder (context -> placeholders), watchdog ticker,
                  # notification provider wrapper
  designui/       # SwayTheme (M3 Expressive tokens), components (SongRow,
                  # MiniPlayer, FullPlayer scaffold, ErrorPanel, banners, skeletons),
                  # color-extraction + scrim engine, Coil ImageLoader provider
```

Tap → sound sequence with failure paths (normative behavior, not implementation detail):

```text
UI tap (song row / album Play / shuffle entry)
      |
      v
ViewModel: build QueueSnapshot from context (FR-22) -- Source IDs + snapshots
      |
      v
PlayerConnection.setQueue(snapshot, startIndex)        (MediaController, async)
      |
      v
SwayPlaybackService.onSetMediaItems
      |-- resolveAudio(START item)  <-- the ONLY up-front resolve (FR-12)
      |-- all other items: sway://pending/<sourceId> placeholder URIs
      v
ExoPlayer.prepare(..., startIndex); play()   --> audio <= 3 s p95 (FR-8)
      |
      v  [transition]
onMediaItemTransition: placeholder detected?
      |-- single-flight guard -> resolveAudio(next) -> replaceMediaItem -> keep rolling
      '-- prefetchNext() optional, age-capped, skipped in repeat-one

FAILURE PATHS
  cached/prefetched URL stale at read  -> discard -> fresh resolve BEFORE play   (layer 1)
  HTTP 403/410 mid-stream              -> invalidate -> deduped re-resolve ->
                                          resume +/-3 s of lost position        (layer 2)
  BUFFERING > 3 s                      -> retry with downscale replay           (layer 3a)
  BUFFERING > 15 s                     -> full stream rebuild                   (layer 3b)
  rebuild still stuck                  -> skip to next item; mark row failed
                                          with typed reason (FR-14, SongRow variant)
```

## Capability → Architecture Map

| Capability (PRD feature group) | Lives in | Governed by |
| --- | --- | --- |
| Discovery & Search (FR-1..4) | SearchViewModel (:app) → CatalogRepository (:core:data) → CatalogSource (:catalog); Offline Fallback Cache (:core:data) | AD-1, AD-3, AD-9, AD-10 |
| Catalog Details (FR-5..7) | same path, detail queries on CatalogSource | AD-1, AD-9 |
| Playback Core (FR-8..15) | :playback (queue build, watchdog) + StreamResolver (:catalog); quality pref (:core:data) | AD-6, AD-7, AD-12 |
| Background & System (FR-16..21) | SwayPlaybackService (:playback) | AD-6, AD-12, AD-13 |
| Queue Management (FR-22..25) | :playback queue builder + QueueStateDao (:core:database) via SessionRestoreRepository | AD-6, AD-8, AD-10 |
| Shell & Players (FR-26..29) | :app screens, :designui components/theme | AD-5, AD-11, AD-13 |
| Library & Persistence (FR-30..34) | :core:data repositories over Room | AD-8, AD-9 |
| Artwork (FR-35..36) | ArtworkRef (:core:model), normalization (:catalog), Coil + scrim engine (:designui) | AD-11, AD-13 |
| Errors & Offline (FR-37..38) | SwayResult everywhere; banners/state components (:designui) | AD-9, AD-10 |
| Settings & About (FR-39..40) | :app screens; DataStore (:core:data); licenses screen | conventions, AD-4 |

## Traceability — Constraints, NFRs, Open Questions

| Binding | Realized by |
| --- | --- |
| C-1 one stack each | AD-2 (Hilt), AD-8 (Room only), AD-3 (OkHttp only) |
| C-2 typed errors day one | AD-9 (+ CI review ban, per-repo injection tests) |
| C-3 service/controller skeleton | AD-6 |
| C-4 lazy resolution | AD-6 rule 3 (resolver-double verification) |
| C-5 layered expiry defense | AD-7 (three layers + mandatory dedup) |
| C-6 bitrate-target formats | AD-7 quality selection rule |
| C-7 facade + sub-services, LOC budget | AD-5 rule (module law + CI LOC lint) |
| C-8 offline fallback caches | AD-9 mapping + conventions (fallback cache spec) |
| NFR-1 startup | AD-10 + macrobenchmark cold-start budget |
| NFR-2 typed boundaries | AD-9 |
| NFR-3 resilience bounds | AD-7 thresholds; forced-expiry/stall suites (SM-2) |
| NFR-4 continuity | AD-6 (nav never touches audio) + AD-8/AD-10 restore path |
| NFR-5 accessibility over artwork | AD-11 scrim engine; designui component tests |
| NFR-6 frame budgets | AD-13 table; measured during animation |
| NFR-7 structure budgets | AD-5 + CI LOC lint; module-level tests mandated (§Testing) |
| NFR-8 single-stack audit | AD-1/2/3 rules include mechanical audit clauses |
| NFR-9 privacy posture | AD-4 + conventions (egress limited to catalog/stream/artwork) |
| NFR-10 resource discipline | AD-6 rule 6, AD-8 trims, AD-11 cache caps |
| OQ-1 | CLOSED here — AD-1; FR-3 ships Search-first branch; feed deferred to InnerTube adapter (v1.x) |
| OQ-2 | CLOSED here — AD-2 |
| OQ-3 | CLOSED here — AD-3 |
| OQ-4 | CLOSED here (PROVISIONAL) — AD-4 |
| OQ-5, OQ-6, OQ-7 | Product-owned; remain open. Architecture impact contained: FR-21 needs no structural choice (Media3 default behavior); FR-15 ships as a settings enum + resolver input either way; OQ-7 affects branding assets only |

## Testing Strategy (per module)

| Module | Approach |
| --- | --- |
| core:model | Pure JVM unit tests: factories reject blank ids; ArtworkRef chain construction; SwayError taxonomy exhaustiveness; title sanitization preserves raw |
| core:database | Robolectric/instrumented Room with in-memory DB: DAO contracts, transactional playlist edits, history upsert-recency + 500 cap trim, migration tests via exported schemas (MigrationTestHelper) |
| core:data | JVM unit tests with fake DAOs + fake CatalogSource: failure injection per category (NFR-2 clause), fallback-cache TTL/deletion/validation-on-read, session restore round-trip, settings persistence |
| catalog | Contract tests against recorded extractor fixtures; MockWebServer3 edge cases (429 → RateLimited, oversized bodies, malformed pages, ciphered-format absence); pure mapper tests; tagged manual smoke test against the live source (upstream drift is not CI-stable — A-1) |
| playback | Resolver-double state-machine tests: exactly-one-resolve (FR-12), renewal ±3 s (FR-13/SM-2), watchdog escalation ladder (FR-14), dedup coalescing; Robolectric service tests for transition/placeholder handling; instrumented: 10-min background continuity (FR-16/NFR-4), kill-and-relaunch restore (FR-25), focus loss/gain automation (FR-19), BT/wired disconnect pause (<1 s, FR-20) |
| designui | Compose UI tests per component across the five canonical states (FR-37 audit hooks); screenshot tests light×dark; contrast assertions over bright/dark artwork matrix (NFR-5); reduced-motion parity |
| app | Navigation smoke (reachability ≤2 taps, tab state preservation), startup StrictMode clean, Macrobenchmark variant: cold-start ≤2.5 s p95, frame budgets during scroll + player transitions (NFR-6/AD-13) |

## Performance budgets (Baseline Device — release gates per AD-13)

| Budget | Bound | Traces to | Measured by |
| --- | --- | --- | --- |
| Cold start → interactive Home | ≤ 2.5 s p95 | NFR-1 | Macrobenchmark + StrictMode triage to zero |
| Frame time during scroll AND player transitions | p95 ≤ 16 ms; >24 ms frames < 1% | NFR-6, P-D1, SM-C2 | Macrobenchmark during animation |
| Full Player open/close | ≤ 300 ms, gesture-interruptible | NFR-6, UX motion cap | Animation frame metrics |
| Tap → audio output | ≤ 3 s p95 @ 10 Mbps | FR-8 | Scripted instrumented suite |
| Grouped search render | ≤ 3 s p95 @ 10 Mbps | FR-1 | Scripted instrumented suite |
| Queue jump → audio switch | ≤ 2 s | FR-23 | Instrumented |
| Scrub release → audible seek | ≤ 500 ms | FR-9 | Instrumented |
| Cross-surface playback state sync | ≤ 250 ms | FR-27, UX §12.8 | StateFlow latency test |
| Artwork color extraction | ≤ 50 ms CPU/artwork, cached | UX-P7, NFR-6 | Unit perf test w/ trace |
| Artwork disk cache | ≤ 256 MB LRU | A-6, NFR-10 | Soak test |
| Resolves during queue construction | exactly 1 | FR-12 | Resolver test double |
| Expiry renewal position resume | ± 3 s | FR-13, SM-2 | Forced-expiry suite |
| Watchdog escalation | 3 s / 15 s initial targets | FR-14, P-5 | Forced-stall suite |
| Play History size | ≤ 500 entries | FR-34, A-5 | DAO test |

## Deferred

Decided-not-now, each with its reopen trigger:

- **InnerTube adapter (v1.x)** — unlocks FR-3 feed depth + FR-6 extension. Trigger: owner
  demand for feed breadth, or NewPipeExtractor losing playable-URL capability (then it
  becomes urgent, see AD-1 escalation trigger).
- **Downloads, lyrics, social, cloud sync, cast/Auto/widgets** — PRD §5 non-goals; no
  architectural hook is built for them beyond what ports naturally allow.
- **Room3 (androidx.room3) upgrade** — trigger: first major schema evolution or Room 2.x
  maintenance wind-down; contained to `:core:database` by AD-5.
- **F-Droid-style vendoring of NewPipeExtractor** — trigger: distribution decision
  (couples to OQ-4 review).
- **Gapless/prewarm constant tuning** (prefetch delay, age caps) — tune with device
  evidence during hardening; behavior is fixed, numbers are not.
- **Blur evaluation for future versions** — banned in v1 (UX-P6); any future proposal must
  re-pass NFR-6 measurement on Baseline Device first (SM-C2).
- **Snapshot GC pass** for orphaned catalog song rows — trigger: observed storage growth
  in dogfooding (SM-3).
- **Telemetry/analytics/crash SDKs** — excluded (P-4); structured manual test logs stand in
  for SM-1/SM-2 evidence.

## Open Questions Remaining (non-blocking, carried)

- **R-1 (device evidence):** actual lifetime/IP-binding envelope of stream URLs — tunes the
  −5 min margin and prefetch age cap (P-5 tuning clause).
- **R-2 (device evidence):** prevalence of ciphered-only responses on the NewPipe primary
  path — if high, AD-1's escalation trigger fires early.
- **R-3 (device evidence):** POST_NOTIFICATIONS-denied behavior of the media foreground
  service on Android 13+ — informs permission request copy (reference pattern: explain the
  lock-screen-control consequence).
- Product-owned OQ-5 (recents-swipe), OQ-6 (quality setting), OQ-7 (trademark check)
  remain open upstream; none require structural change either way.

## Flagged Input Inconsistencies

1. **UJ-1 opening beat vs OQ-1 outcome** — PRD UJ-1 says "Home Feed appears immediately"
   at launch; with OQ-1 closed NewPipe-only, launch lands on the Search-first landing page.
   Contained (UX designed both branches), but UJ-1's text should be amended at the next PRD
   touch.
2. **FR-28 "Glass Surface" wording** — residual pre-0003 liquid-glass language; UX renamed
   the concept Artwork Surface. Treat FR-28's "Glass Surface" as reading "Artwork Surface"
   until the PRD is corrected.
3. **NFR-6 blur-measurement clause vs UX-P6 total blur ban** — PRD anticipates measuring
   blur cost before design freeze; the UX spec bans blur outright in v1. Harmless but
   contradictory: recorded here as "blur banned in v1; measurement gate applies only to
   future proposals."
4. **FR-27 restored-session Mini Player vs NFR-1 startup law** — a tension, reconciled by
   AD-10 (restore runs post-composition, paused, from local snapshots). Noted so epics test
   both together rather than treating them as independent.

*End of spine. Next chain step: bmad-create-epics-and-stories (consumes PRD + UX + this).*

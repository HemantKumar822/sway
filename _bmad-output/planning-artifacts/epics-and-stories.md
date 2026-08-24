---
title: Sway - Epic Breakdown and Stories
status: final
created: 2026-08-23
updated: 2026-08-23
project: Player
owner: Hemant
name_provisional: false
run_mode: headless
stepsCompleted: [step-01-validate-prerequisites, step-02-design-epics, step-03-create-stories, step-04-final-validation]
inputDocuments:
  - _bmad-output/planning-artifacts/prd.md
  - _bmad-output/planning-artifacts/ux-design-specification.md
  - _bmad-output/planning-artifacts/architecture.md
  - docs/research/sprint-R1-summary.md
note: >
  Headless run of bmad-create-epics-and-stories; the system acted as smart owner proxy.
  Menu halts were resolved autonomously ([C] assumed at every gate). Decisions made
  without an owner present are indexed in the Provisional Register (EP-1..EP-8);
  upstream registers (PRD P-1..P-5, UX UX-P1..P12, architecture AD-4) remain binding.
  Output filename follows the caller directive (epics-and-stories.md) instead of the
  skill default (epics.md); internal structure follows the skill template.
---

# Sway - Epic Breakdown and Stories

## Overview

This document decomposes Sway v1 requirements (prd.md: FR-1..FR-40, NFR-1..NFR-10,
constraints C-1..C-8, journeys UJ-1..UJ-5, metrics SM-1..SM-3), the UX design
specification, and the binding architecture spine (architecture.md AD-1..AD-13, closed
OQ-1..OQ-4) into **15 epics / 62 stories / 260 story points**, sequenced by build-order
dependency logic.

Decomposition stance: **vertical slices wherever a slice can stand alone; layer-proving
enabler epics only where genuinely shared infrastructure is required.** Epics 1-2 exist
because the builder JTBD (prd.md section 2.1) and the AD-5 dependency law demand a
provable substrate before anything builds on it; Epics 3-8 are thick vertical slices
terminating in runnable, test-proven behavior even before full UI exists. Every FR and
NFR lands in **exactly one completing epic** (coverage matrices below prove zero orphans).
Stories that contribute to an FR owned by a later epic cite it as a *trace*, never as
completion.

Sequencing laws honored in every story: typed `SwayResult`/`SwayError` from day one
(C-2/AD-9); nothing blocking on the main thread (AD-10); no HQ Audio and no PRD section-5
non-goal ever enters scope; NewPipe-only transport strictly behind the `CatalogSource` /
`StreamResolver` ports (AD-1; OQ-1 CLOSED - FR-3 ships its designed Search-first landing
branch).

Story sizing convention [PROVISIONAL EP-7]: Fibonacci points; one story approximates one
focused agent dev session (2-3 pts half-session, 5 pts full session, 8 pts session max).

## Requirements Inventory

### Functional Requirements

Condensed from prd.md section 4 (authoritative definitions live there; IDs stable).

- **FR-1**: Keyword search across Songs, Albums, Artists, Catalog Playlists; grouped, filterable; <=3 s p95 render; typed Empty distinct from typed Error.
- **FR-2**: Search pagination - load-more/infinite scroll per group; no duplicates; scroll preserved; end-of-results indicated.
- **FR-3**: Home Feed (Should, conditional) - DEGRADED PER OQ-1 CLOSURE: ships as Search-first landing page (brand header, search entry, Library shortcut tiles); feed shelves deferred to v1.x InnerTube adapter.
- **FR-4**: Offline Fallback Cache served on network failure; visibly stale-marked; TTL-bounded; entries tappable; play follows normal resolution failure paths.
- **FR-5**: Album detail - title/artist/year(optional)/tracklist/artwork; Play/Shuffle; missing metadata omitted cleanly.
- **FR-6**: Artist detail - name/image/top Songs (Must); Albums/Singles listings where transport provides (Should - degrades to clean omission under OQ-1).
- **FR-7**: Catalog Playlist detail - read-only; title/curator/count/artwork/ordered tracklist; identical Play/Shuffle semantics; zero local-edit affordances.
- **FR-8**: Tap-to-play anywhere from any surface; audio <=3 s p95 at 10 Mbps on Baseline Device; always constructs a context Queue.
- **FR-9**: Pause/resume/seek from Mini Player, Full Player, notification, lock screen; position display +/-1 s; scrub audible <=500 ms after release.
- **FR-10**: Next/previous from all control surfaces; previous restarts current track at >=5 s played (A-4); next at queue end respects repeat mode.
- **FR-11**: Shuffle toggle (preserves current track, deterministic reshuffle per session) and repeat off/all/one; last-used modes persist across launches.
- **FR-12**: Lazy stream resolution - exactly one up-front resolve for the start item (verified by resolver test double); rest of Queue holds Source-ID placeholders re-resolved just-in-time.
- **FR-13**: Expired-stream renewal - HTTP 403/410 purges stale URL, deduped fresh resolve, resumes +/-3 s; cached URLs validated at read time with -5 min margin.
- **FR-14**: Stalled-playback watchdog - >3 s brief-stall retry/downscale; >15 s full stream rebuild; repeated failure skips to next item with typed reason surfaced.
- **FR-15**: Audio-quality preference AUTO(default)/LOW/MEDIUM/HIGH as bitrate targets (Should - PROVISIONAL P-2, veto via OQ-6); persists; applies from next resolution.
- **FR-16**: Background playback uninterrupted when backgrounded/screen-off/navigating; 10-min instrumented proof with zero app-attributable gaps.
- **FR-17**: Media-style notification - metadata/artwork/play-pause/next/previous; dismisses when paused; dismiss-while-playing stops per platform default (A-10).
- **FR-18**: Lock-screen media session parity - artwork/title/artist/duration exact; controls mirror FR-9/FR-10 semantics.
- **FR-19**: Audio focus compliance - calls pause playback; transient loss pauses (duck only where platform grants, per AD-12); resume only where policy allows.
- **FR-20**: Active-route disconnect (wired/BT) pauses <1 s; reconnect never auto-resumes.

- **FR-21**: Recents-swipe does not stop active playback (Should - PROVISIONAL P-3, veto via OQ-5); notification remains the stop affordance.
- **FR-22**: Any play action builds a context Queue reflecting play context, starting at chosen item (track #5 => full album queued at #5; shuffle entry => shuffled queue).
- **FR-23**: Queue surface - upcoming order, current highlighted, jump <=2 s, remove items; removing playing track advances; removals persist within session.
- **FR-24**: Queue enrichment - play next (insert after current), add to queue (append), drag-reorder, clear; insertions appear immediately in correct position.
- **FR-25**: Playback Session persistence - queue/current index/position(+/-5 s)/modes survive process death; restoration never auto-plays.
- **FR-26**: Navigation shell - Home/Search/Library bottom tabs; details push over tabs preserving tab state; Settings/About reachable <=2 taps; predictable back-stack.
- **FR-27**: Mini Player persists above bottom navigation on every tab whenever a Playback Session exists (including restored-paused); reflects state changes <=250 ms regardless of origin; tap expands.
- **FR-28**: Full Player - large artwork on Artwork Surface, full transport, position bar, like toggle, queue access; open/close <=300 ms (NFR-6).
- **FR-29**: Adaptive layout - nothing unreachable/truncated >=600 dp widths; portrait primary (specifics delegated UX-P11).
- **FR-30**: Like/unlike any Song from any surface; like state consistent across surfaces <=250 ms; Liked Songs playable/shuffleable; survives restarts and process death.
- **FR-31**: Create named local Playlist from Library or song context menu ("save to playlist"); duplicate names allowed; persists immediately; helpful empty states.
- **FR-32**: Edit local Playlist - add/remove/reorder/rename/delete; persists immediately (no save button); multi-membership; delete confirms; fully offline-capable.
- **FR-33**: Library hub - Liked Songs, all user Playlists, Play History entry; accurate counts; opening any collection starts correct-context playback.
- **FR-34**: Play History auto-recorded (>10 s played rule, A-5), reverse-chronological, duplicates update recency, capped at 500, fully offline, tap replays entry.
- **FR-35**: Artwork loads through image layer with memory+disk caching; repeated views never re-download unchanged images; disk cache bounded (NFR-10/A-6).
- **FR-36**: Failed/missing artwork yields branded placeholder with zero layout shift; no broken-image states; automatic retry on connectivity return.
- **FR-37**: Typed Error States on every data-driven surface - exactly one of loading/content/empty/error-with-retry; blank screen or silent empty-on-failure is a release blocker; user-readable categories.
- **FR-38**: Offline mode - app opens normally into functional Library with explicit banner; online-only actions explain themselves; reconnect restores without restart.
- **FR-39**: Settings - appearance theme System/Light/Dark persisted via preferences; quality selector if FR-15 survives OQ-6; applies immediately, no restart.
- **FR-40**: About & licenses - version info plus every shipped dependency's license listed (legal obligation); <=2 taps from Library.

### NonFunctional Requirements

Condensed from prd.md Appendix A (authoritative bounds live there).

- **NFR-1**: Startup never blocks main thread; cold start -> interactive Home <=2.5 s p95 Baseline Device; strict-mode violations triaged to zero.
- **NFR-2**: Typed results at every layer boundary; `emptyList` never a failure signal; categories map onto FR-37 states; unit tests inject every category per repository; review checklist bans swallow-and-return-empty.
- **NFR-3**: Stream resilience bounds - -5 min read-time expiry margin; 403/410 renewal with +/-3 s resume; watchdog escalation 3 s/15 s initial targets; in-flight resolve dedup prevents storms; forced-expiry/stall suites meet bounds (SM-2).
- **NFR-4**: Continuity - navigation never interrupts audio; Playback Session survives process death; database survives restarts intact; automated navigation-soak and kill-relaunch suites.
- **NFR-5**: Accessibility over artwork - WCAG 2.1 AA contrast (>=4.5:1 normal, >=3:1 large) via scrim pipeline across light x dark x bright/dark-artwork matrix; touch targets >=48 dp; TalkBack labels on all controls; reduced-motion respected; accessibility scanner clean on core flows.
- **NFR-6**: Runtime performance budgets (Baseline Device) - p95 frame <=16 ms, jank frames >24 ms under 1% during scroll AND player transitions; Full Player open <=300 ms; blur banned in v1.
- **NFR-7**: Code structure budgets - facade + small sub-services; no repository/service exceeds 1000 LOC (CI lint); every module testable without launching the full app.
- **NFR-8**: Single-stack rule - exactly one DI framework, one database, one HTTP stack, decided up front (AD-2/AD-3/AD-8); dependency audit shows no second framework of any class.
- **NFR-9**: Privacy posture - personal data stays on-device; no accounts; no telemetry/analytics/crash SDKs in v1 (P-4); network egress limited to Catalog/stream/artwork; traffic inspection clean in debug builds.
- **NFR-10**: Resource discipline - playback service self-stops when idle; artwork disk cache bounded (256 MB LRU, A-6) with eviction; no unbounded in-memory caches of Catalog results.


### Additional Requirements (from Architecture - binding on all stories)

- **AR-1**: Seven-module Gradle graph, fixed edges (AD-5): app->all; designui->core:model; playback->core:model+core:data; core:data->core:model+core:database; core:database->core:model; catalog->core:model. Enforced mechanically in CI.
- **AR-2**: Extractor isolation - NewPipeExtractor coordinate AND imports permitted only inside `:catalog`; port signatures speak exclusively in `core:model` types returning `SwayResult` (AD-1).
- **AR-3**: Hilt-only DI (`@HiltAndroidApp`, `@HiltViewModel`, `@AndroidEntryPoint`); bindings live in owning module, aggregated in `:app`; no service locators (AD-2).
- **AR-4**: One OkHttp singleton; metadata client + leaner artwork client permitted as builder derivations; Coil uses `coil-network-okhttp`; no other HTTP artifact may enter the version catalog (AD-3).
- **AR-5**: Playback topology - `SwayPlaybackService : MediaLibraryService` is the ONLY player owner; UI talks exclusively through the `PlayerConnection` facade (commands + hoisted `PlayerUiState`); position ticks scoped to scrubber subscribers; `sway://pending/<sourceId>` placeholder scheme defined in exactly one place in `:playback`; Play History recording exclusively service-side via HistoryRepository; service self-stops when idle/released (AD-6).
- **AR-6**: StreamResolver contract - `resolveAudio(trackId, AudioRequest): SwayResult<ResolvedAudio>`, `invalidate(trackId)`, opportunistic `prefetchNext(...)` returning null silently; `ResolvedAudio` carries url + expiresAtEpochMs parsed from the URL's own expiry parameter (never guessed) + bitrateKbps + container hint + backend tag + rendition cache key; mandatory in-flight dedup invisible to callers; three-layer expiry defense (read-time -5 min margin; 403/410 renewal +/-3 s; 3 s/15 s watchdog ladder) with Media3 StuckPlayerException as backstop only (AD-7).
- **AR-7**: One Room database in `:core:database`; `exportSchema = true` from migration 1; explicit migrations tested against exported schemas; destructive fallback refused; QueueSnapshot has exactly ONE serializer, owned by `:core:data`; Room 2.8.4 chosen over room3 package (PROVISIONAL in spine) (AD-8).
- **AR-8**: `SwayResult<T>` sealed Success/Failure(SwayError) with fixed category-to-UI-state mapping (Offline, RateLimited, UpstreamUnavailable, Parse, ContentNotFound, Storage, Unknown); failures travel as values, never thrown across modules; blank Source-ID rejected at parse time (AD-9).
- **AR-9**: Startup law - Application.onCreate performs no disk/network/preferences work; debug StrictMode death-penalty triaged to zero; splash dismisses on composition, never on data; session restore post-composition, paused, from local snapshots (AD-10).
- **AR-10**: Artwork travels as `ArtworkRef` computed once at parse time in `:catalog` (canonical URL = cache key; candidate chain is data); consumers contain zero host-specific URL logic; Coil 3.5.0 memory cache ~25% of app memory / 256 MB LRU disk; extraction <=128 px decode, <=50 ms CPU, cached alongside artwork entry, off-main-thread in `:designui` scrim engine computing scrims until AA contrast holds; NO runtime blur anywhere in v1 (AD-11, UX-P6).
- **AR-11**: Audio focus/route policy - Media3-native semantics via music AudioAttributes with focus handling + becoming-noisy enabled; transient loss pauses (duck only where platform grants transient-may-duck); permanent loss stops; route reconnect never auto-resumes (AD-12).
- **AR-12**: Performance budgets are release gates measured by Macrobenchmark on Baseline Device profile DURING animation (AD-13).
- **AR-13**: Stack pins (exact patches pinned at build time): Kotlin/KSP 2.4.10, JDK 21, AGP 9.3.0 / Gradle 9.5.0, compileSdk 36 / minSdk 26 / target 36, Compose BOM 1.11.x, Material3 1.4.0 (Expressive stable), navigation-compose 2.9.x, Media3 1.11.0, Room 2.8.4, Hilt 2.60.1 + androidx.hilt 1.4.0, Coil 3.5.0, OkHttp 5.5.0, NewPipeExtractor v0.26.5 (JitPack), DataStore 1.2.x, coroutines 1.11.x / serialization 1.9.x.
- **AR-14**: Conventions - timestamps epoch ms UTC; durations ms internally rendered m:ss at edge; stable list keys = Source ID; PRD glossary vocabulary verbatim in identifiers/docs/comments; injected Dispatchers (IO adapters, Default parse/extraction); no user content logged beyond titles/artists needed for diagnostics; no stack traces reach UI.

### UX Design Requirements (UX-DRs)

Extracted per Step 1 rules; each is specific enough for story creation and covered by at
least one story (owning story IDs shown).

- **UX-DR1**: Token system on M3 Expressive - violet-led ColorScheme, light+dark first-class (rose reserved for like; amber caution reserved for offline/stale); dynamic color OFF in v1 but structurally ready; faint-violet neutrals (#FCF9FE light / #131318 dark). -> Story 9.1
- **UX-DR2**: Typography - bundled Outfit (display/headline) + Inter (titles/body/labels); ramp Display44/Headline24/TitleLG20/TitleMD16/BodyLG16/BodyMD14/LabelLG14/LabelMD12; tabular figures for ALL durations/positions/counters; text scales to 200% without losing controls. -> Story 9.1
- **UX-DR3**: Shape ramp - rounded xs8/sm12/md16/lg20/xl28/full pill; one radius step per nesting level; artwork corners match container; nothing square except full-bleed scrims. -> Story 9.1
- **UX-DR4**: Motion system - durations fast120/base220/emphasized320/player300 hard cap/ambient600, max anywhere 800 ms; standard/decel/accel easings + default/pop springs per UX section 7.7; NEVER-animate list enforced (no marquee, no layout-property animation in lists, no spinner during gapless transition, theme switch = 150 ms fade max); every animation interruptible/reversible; reduced-motion parity (opacity fades <=120 ms, springs/shimmer off). -> Stories 9.1, 12.1, 12.2
- **UX-DR5**: Typed-state kit - shape-mirrored skeletons with single shared shader shimmer + 150 ms crossfade arrival (never for local Library data); ErrorPanel+Retry preserving query/scroll with copy rotation after 2nd consecutive failure; Empty states with recovery actions; OfflineBanner (single slide-down, dismiss X, reappears on next offline event) + StaleBadge "Saved"; snackbar z-order ABOVE Mini Player; dialogs 280-360 dp. -> Story 9.2

- **UX-DR6**: SongRow component - variants indexed/thumbnailnailed/playing (equalizer glyph + primary-colored title)/failed (error glyph + dimmed strike); leading/title/subtitle/trailing anatomy; rows 56-64 dp; long-press context menu. -> Stories 9.2, 10.8
- **UX-DR7**: Cards/HeroHeaders - AlbumCard/PlaylistCard square art {rounded.md}, rail card 152 dp [UX-P7]; HeroHeader large artwork + headline + Play/Shuffle buttons >=48 dp; ArtistHeader circular portrait with initials-avatar fallback; sticky compact header variant [PROVISIONAL]. -> Stories 9.2, 10.5-10.7, 11.x
- **UX-DR8**: MiniPlayer anatomy - 48 dp thumb {rounded.sm}, 1-line title/artist, play/pause + next at 48 dp hit areas, 2 dp determinate progress hairline (pulsing while buffering); NO scrubbing [UX-P10]; swipe-down hides bar only, audio persists [UX-P9]; failed-track error chip. -> Story 12.1
- **UX-DR9**: FullPlayer layout per wireframe - artwork ~92vw {rounded.xl}, transport cluster shuffle/prev/play(72 dp)/next/repeat cycling badge "1", scrubber thumb grows on touch with live time bubble, heart pop spring, double-tap-artwork=like [UX-P9], explicit Queue affordance [UX-P9], Quality chip only if FR-15 survives OQ-6. -> Stories 12.2, 12.4
- **UX-DR10**: QueueSheet - Now-playing pinned highlighted row; Next-up rows (thumb, handle, remove X); jump <=2 s; removing playing advances; long-press-drag reorder with haptic ticks [UX-P8] plus move-up/move-down row-menu alternative (accessibility); Clear confirmation. -> Story 12.3
- **UX-DR11**: Offline/stale copy system - canonical strings externalized verbatim from UX section 4 ("You're offline..." banner; "Saved" badge; empty-search spelling hint + Clear; "couldn't play - skipped"; delete confirm; snackbar adds). -> Stories 9.2, 10.4, 14.2
- **UX-DR12**: Context menus + AddToPlaylistPicker + dialog set - song menu items (Play next / Add to queue / Add to playlist / Like / Go to album / Go to artist / Share raw URL [PROVISIONAL]); picker lists playlists + New-playlist inline create-and-add; create/rename dialogs allow duplicate names; delete confirm only destructive dialog. -> Stories 10.8, 11.3, 11.4
- **UX-DR13**: ArtworkPlaceholder - branded glyph on placeholder color, identical bounds to loaded art (zero layout shift), auto-retry when connectivity returns. -> Story 13.1
- **UX-DR14**: Accessibility floor - WCAG AA contrast over artwork via scrim engine; >=48 dp targets with >=8 dp separation; TalkBack labels incl. dynamic announcements ("Play {title} by {artist}", like toggle state, queue position "3 of 12", polite offline banner liveRegion); logical focus order; gesture alternatives for every gesture; 200% text scaling rules; reduced-motion honored. -> Stories 13.2, 9.2, 12.2, 12.3
- **UX-DR15**: Performance guardrails - lazy virtualization with stable keys (= Source ID); thumbnail prefetch at list velocity; full-res artwork only for hero/player contexts; extraction budget <=50 ms CPU cached; blur prohibited; skeletons share one shader; cold start renders skeletons immediately, never artificial delay; playback-state propagation <=250 ms with hoisted player state. -> Stories 9.2, 9.4, 13.x, 14.4
- **UX-DR16**: Adaptive layout specifics [UX-P11] - >=600 dp content max-width 640 dp centered or 2-column grids, list rows gain second metadata column; >=840 dp Full Player side-by-side (artwork left, controls right), nav rail replaces bottom bar, queue becomes side panel. -> Story 14.5

## FR Coverage Map

Completion rule: each FR/NFR completes in EXACTLY ONE epic/story. "Trace" stories cite
the FR without claiming completion.

| FR | Completing epic | Completing story | Note |
|---|---|---|---|
| FR-1 | E10 Discovery | 10.2 | perf bound checked in 14.4 regression |
| FR-2 | E10 | 10.3 | |
| FR-3 | E9 Shell & Design Language | 9.5 | degraded Search-first branch per OQ-1 |
| FR-4 | E10 | 10.4 | data side built in 8.4/10.1 as traces |
| FR-5 | E10 | 10.5 | |
| FR-6 | E10 | 10.6 | extension tier degrades cleanly (OQ-1) |
| FR-7 | E10 | 10.7 | |
| FR-8 | E12 Player Surfaces | 12.4 | engine-level proof in 4.4 as trace |
| FR-9 | E12 | 12.2 | service semantics traced in 4.2/6.x |
| FR-10 | E12 | 12.2 | A-4 prev semantics engine-side 7.1 trace |
| FR-11 | E12 | 12.2 | persistence engine-side 7.2 trace |
| FR-12 | E4 Playback Engine | 4.4 | resolver-double verification |
| FR-13 | E5 Stream Resilience | 5.3 | forced-expiry suite (SM-2) |
| FR-14 | E5 | 5.4 | UI marker traces: 9.2, 12.1, 12.3 |
| FR-15 | E5 | 5.1 | chip presentation traces 12.4 (OQ-6-gated) |
| FR-16 | E6 Background & System | 6.1 | 10-min instrumented proof |
| FR-17 | E6 | 6.1 | notification provider |
| FR-18 | E6 | 6.1 | lock-screen parity assertions |
| FR-19 | E6 | 6.2 | AD-12 scenarios automated |
| FR-20 | E6 | 6.2 | <1 s disconnect pause |
| FR-21 | E6 | 6.3 | PROVISIONAL P-3 / OQ-5 |
| FR-22 | E12 | 12.4 | engine semantics built+tested in 7.1 trace |
| FR-23 | E12 | 12.3 | queue sheet |
| FR-24 | E12 | 12.3 | enrichment commands 7.1 trace, menu 10.8 trace |
| FR-25 | E7 Queue & Session | 7.3 | kill-and-relaunch instrumented |
| FR-26 | E9 | 9.3 | reachability + tab-state smoke |
| FR-27 | E12 | 12.1 | restored-paused presence via 7.3 hook |
| FR-28 | E12 | 12.2 | Artwork Surface color arrives 13.2 trace |
| FR-29 | E14 Honesty & Hardening | 14.5 | implementations trace 9.3/12.2 |
| FR-30 | E12 | 12.2 | bidirectional sync <=250 ms; collection screen 11.1 |
| FR-31 | E11 Library Surfaces | 11.4 | context-menu creation traced 10.8 |
| FR-32 | E11 | 11.3 | editor |
| FR-33 | E11 | 11.4 | hub aggregation |
| FR-34 | E11 | 11.2 | recording hook built in 8.3 trace |
| FR-35 | E13 Artwork & Atmosphere | 13.1 | cache-hit zero network |
| FR-36 | E13 | 13.1 | placeholder stability |
| FR-37 | E14 | 14.1 | kit shipped 9.2 as trace |
| FR-38 | E14 | 14.2 | launch routing built 9.4 as trace |
| FR-39 | E15 Settings & Release | 15.1 | |
| FR-40 | E15 | 15.2 | |

## NFR Coverage Map

| NFR | Completing epic | Completing story | Contributing traces |
|---|---|---|---|
| NFR-1 | E9 Shell & Design Language | 9.4 | re-measured as gate in 14.4 |
| NFR-2 | E14 Honesty & Hardening | 14.1 | taxonomy born 2.2; per-repo tests 3.x/10.1/8.x |
| NFR-3 | E5 Stream Resilience | 5.2-5.4 suites | resolver contract 2.4/3.6 |
| NFR-4 | E14 | 14.3 | session restore 7.3 trace |
| NFR-5 | E13 Artwork & Atmosphere | 13.2 | component a11y in 9.2/12.x traces |
| NFR-6 | E14 | 14.4 | motion tokens 9.1; transitions 12.2 |
| NFR-7 | E1 Workspace & Gates | 1.3 | facade pattern reviewed every epic |
| NFR-8 | E1 | 1.3 (+1.1 pins) | AD audits run continuously |
| NFR-9 | E15 | 15.3 | posture docs 15.3 (AD-4) |
| NFR-10 | E14 | 14.3 soak suite | self-stop 4.1; caps 13.1; trim 8.3 |


## Epic List

Build order with one-line goals (points = story-point totals):

| # | Epic | Goal (one line) | Pts | Stories |
|---|---|---|---|---|
| E1 | Workspace & Quality Gates | Reproducible 7-module build substrate with mechanical law enforcement (LOC, extractor isolation, single-stack) | 7 | 1.1-1.3 |
| E2 | Domain Model & Ports | Pure-Kotlin vocabulary: models, SwayResult/SwayError, ArtworkRef, CatalogSource + StreamResolver ports | 11 | 2.1-2.4 |
| E3 | Catalog Adapter - NewPipe Behind Ports | Four-type search + album/artist/catalog-playlist detail + stream resolution behind the ports | 25 | 3.1-3.6 |
| E4 | Playback Engine - One-Song Core Loop | Service-owned audio: context placeholder queue, exactly-one up-front resolve, JIT transitions | 21 | 4.1-4.4 |
| E5 | Stream Resilience - Expiry Defense & Watchdog | Three-layer expiry defense (read-time validation, 403/410 renewal +/-3 s, 3 s/15 s watchdog) + quality preference | 16 | 5.1-5.4 |
| E6 | Background Playback & System Integration | Notification/lock-screen parity, focus + route compliance, recents-swipe posture | 11 | 6.1-6.3 |
| E7 | Queue Management & Playback Session Persistence | Full queue manipulation semantics + modes persistence + kill-and-relaunch restore (Room born here) | 16 | 7.1-7.3 |
| E8 | Owned Data Layer - Likes, Playlists, History | Room schema expansion + repositories for likes/playlists/history + Offline Fallback Cache | 20 | 8.1-8.4 |
| E9 | Design Language & Navigation Shell | Sway token system, typed-state component kit, bottom-tab shell, startup law, Home landing (completes FR-3 degraded branch) | 24 | 9.1-9.5 |
| E10 | Discovery - Search & Catalog Details | Search UI (grouped/paginated/offline-stale) + Album/Artist/Catalog Playlist detail screens with play entry points | 30 | 10.1-10.8 |
| E11 | Library Surfaces & Collection Editing | Liked Songs, Play History, playlist editor, Library hub - fully offline collection UX | 14 | 11.1-11.4 |
| E12 | Player Surfaces - Mini, Full, Queue Sheet | Persistent Mini Player, Full Player on Artwork Surface scaffold, Queue sheet, cross-surface tap-to-play wiring | 21 | 12.1-12.4 |
| E13 | Artwork System & Visual Atmosphere | Coil pipeline with bounded caching, placeholder stability, color extraction + scrim engine (AA guaranteed) | 10 | 13.1-13.2 |
| E14 | Honesty Pass - Typed States, Offline & Hardening | Surface x failure audit, offline end-to-end, continuity/resource soak suites, performance budget gate, adaptive matrix | 23 | 14.1-14.5 |
| E15 | Settings, About & Release Readiness | Theme settings, About/licenses, privacy traffic audit, SM evidence records, release gates | 11 | 15.1-15.3 |

**Total: 260 points / 62 stories.** *(Corrected 2026-08-23 by sprint-planning readiness gate: story-level points sum to 260; the E8 epic row previously read 18 and the grand total 258 — arithmetic fix only, no scope change.)*

### Build-order diagram

```
E1 Workspace & Gates
   |
E2 Model & Ports ----------------------+--------------------------------+
   |                                   |                                |
E3 Catalog adapter ----(resolver)--+   |                                |
   |                              |   |                                |
E4 Playback engine <--------------+   |                                |
   |         \                        |                                |
E5 Resilience  E6 Background/system    |                                |
   |             |                     |                                |
   |         E7 Queue & Session (Room born)                             |
   |             |                     |                                |
   |         E8 Owned-data layer        |                                |
   |             |_____________________|                                |
   |                       |                                            |
E9 Shell & design kit <---+ (consumes playback facade + data flows)     |
   |                                                                    |
E10 Discovery/details --> E11 Library surfaces --> E12 Player surfaces   |
                                                                  |      |
                                       E13 Artwork/atmosphere <----+     |
                                                    |                    |
                                       E14 Honesty/hardening <----------+
                                                    |
                                       E15 Settings/about/release
```

Reading: strict left-to-right; vertical placement shows parallel tracks (catalog track
vs playback track vs data track) that merge at E9/E12. No epic depends on a later epic;
within epics no story depends on a later story (validated in Final Validation record).

### Epic dependency table

| Epic | Depends on | Notes |
|---|---|---|
| E1 | none | substrate |
| E2 | E1 | module exists first |
| E3 | E1, E2 | ports to implement |
| E4 | E1, E2; 4.4 needs 3.6 | resolver impl arrives via E3 |
| E5 | 4.x complete; 5.1 independent of 5.2-5.4 | defense layers stack on engine |
| E6 | E4 | service must exist |
| E7 | E4; 7.2 needs 5.1 (SettingsRepository) | modes persist via settings |
| E8 | 7.3 (Room database born there); E2 types | schema expands incrementally [EP-3] |
| E9 | E1; 9.5 tiles read E8 flows; 9.4 hosts restore hook consumed by 12.1/7.3 wiring | shell before screens |
| E10 | E3 (via 10.1 repo), E8 (fallback cache), E9 (kit/shell) | vertical discovery slice |
| E11 | E8, E9; 11.2 replay uses 7.1 commands; menu creation traced from 10.8 | collections online-free |
| E12 | E7 (session/modes), E9 (kit/nav), surfaces from E10/E11 for wiring matrix | all-surface completion epic |
| E13 | 13.1 after 9.2 placeholders exist; 13.2 needs 12.2 player surface | atmosphere is player-scoped |
| E14 | E10-E13 complete | audits need every surface present |
| E15 | E9 routes, E5 settings keys, E14 suite records | release gate |

## Epic 1 - Workspace & Quality Gates

**Goal:** A reproducible Gradle workspace with all seven architecture modules, pinned
stack, Hilt wired, debug StrictMode armed, and CI checks that mechanically enforce the
architecture laws so violations fail builds rather than reviews.

**Scope in:** version catalog pins (AR-13), module skeleton with edge enforcement, Hilt
graph, StrictMode policy, LOC lint, extractor-isolation + single-stack audit scripts.
**Scope out:** any feature code; theming.
**Completes:** NFR-7, NFR-8 (mechanical gates established). **Depends on:** none.

**Exit criteria:** fresh clone builds and launches an empty Compose screen; intentionally
adding a second DI artifact or an extractor import outside `:catalog` fails CI; LOC lint
configured at 1000.

### Story 1.1 - Gradle workspace & seven-module skeleton *(3 pts, deps: none)*

As the owner-builder, I want a reproducible multi-module build substrate, so that every
later layer lands in its lawful module from day one (AD-5).

**Traces:** AR-1, AR-13, NFR-7 setup. **Tasks:** root `settings.gradle.kts` +
convention plugins; `gradle/libs.versions.toml` pinning the AR-13 stack exactly; modules
`app/`, `core/model`, `core/database`, `core/data`, `catalog`, `playback`, `designui`;
`:app` launches a bare Compose activity; edge-audit script stub wired to CI.

**Acceptance Criteria:**
- **Given** a fresh clone on JDK 21, **When** `gradlew :app:assembleDebug` runs, **Then** the build succeeds with all seven modules resolved.
- **When** the app installs and launches, **Then** an empty composable screen renders.
- **When** any module declares a build edge outside AD-5's allowed set, **Then** the edge audit fails with the offending path named.

**Tests:** CI build + audit green; manual launch smoke note.

### Story 1.2 - Hilt graph & startup hygiene *(2 pts, deps: 1.1)*

As the owner-builder, I want Hilt owning the app graph and StrictMode armed in debug,
so that DI stays singular (AD-2) and main-thread violations surface as loud failures from
day one (AD-10).

**Traces:** AR-3, AR-9, NFR-1 substrate. **Tasks:** `@HiltAndroidApp` application class;
`@AndroidEntryPoint` MainActivity; injected-dispatcher provider module (IO/Default);
debug-only StrictMode with death penalty for main-thread disk/network reads and a
baseline-suppression file kept empty by policy; tag-consistent Log wrapper per AR-14.

**Acceptance Criteria:**
- **Given** the release build type, **When** it runs, **Then** no StrictMode installation occurs (debug-only verified).
- **Given** a deliberately added blocking disk read on the main thread in debug, **When** exercised, **Then** the app crashes loudly (policy proof, then reverted).
- **And** Application.onCreate performs no disk/network/preferences work (code inspection + Robolectric assertion hook installed).

**Tests:** Robolectric startup assertions.

### Story 1.3 - Mechanical law CI *(2 pts, deps: 1.1)*

As the owner-builder, I want CI that fails on extractor leakage, second frameworks, or
oversized files, so that the reference project's four leaked-boundary mistakes are made
structurally impossible (L1/L2/D-02 evidence).

**Traces:** AR-1/AR-2, C-1/C-2/C-7, NFR-7, NFR-8 COMPLETES HERE (with 1.1 pins).
**Tasks:** scripts/checks: grep NewPipe coordinate outside `:catalog` = zero hits;
extractor imports outside `:catalog` = zero; forbidden-artifact scan (Koin/Ktor/Retrofit/
Cronet/SQLDelight/room3 and any second DI class) across the version catalog and imports;
per-file >1000 LOC lint failure; module test tasks runnable headlessly.

**Acceptance Criteria:**
- **Given** a temporary commit adding `org.schabi.newpipe` import in `:app`, **When** CI runs, **Then** the isolation check fails naming file+line.
- **Given** a temporary Koin artifact in libs.versions.toml, **When** CI runs, **Then** the single-stack scan fails.
- **Given** any file exceeding 1000 LOC, **When** CI runs, **Then** the budget lint fails (NFR-7 gate live).

**Tests:** negative-path CI proofs documented in PR description; then reverts merged.

## Epic 2 - Domain Model & Ports

**Goal:** The pure-Kotlin heart: identity-lawed models, the typed result/error union
with its fixed category table, ArtworkRef candidate chains, and both load-bearing ports -
so every adapter speaks only this language forever.

**Scope in:** Song/Album/Artist/CatalogPlaylist models, SwayResult/SwayError taxonomy,
ArtworkRef, CatalogSource + StreamResolver signatures, AudioRequest/ResolvedAudio/Quality,
Playlist local-id namespacing, QueueSnapshot/QueueItem.
**Scope out:** Android dependencies of any kind; implementations of the ports.
**Completes:** nothing directly (enabler; NFR-2 completes at the E14 audit). **Depends
on:** E1.

**Exit criteria:** pure JVM tests green for factories/taxonomy/chains; ports compile
against fakes; zero Android imports in `:core:model` (CI check).

### Story 2.1 - Catalog models & identity law *(3 pts, deps: 1.1)*

As the owner-builder, I want catalog entities that cannot exist without a Source ID, so
that no keyless model ever reaches a database or UI downstream (AD-8 conventions).

**Traces:** AR-8 blank-id rejection, AR-14 conventions. **Tasks:** Song (SourceId,
title + rawTitle preserved separately, artist name/id, album name/id nullable, durationMs,
ArtworkRef), Album, Artist, CatalogPlaylist; factory functions rejecting blank ids at
parse time (mapper contract: drop + log shape info); local Playlist id namespacing rule
documented on the type.

**Acceptance Criteria:**
- **Given** a factory invoked with blank/whitespace id, **When** called, **Then** construction fails (factory returns null / sealed parse-failure per contract) and never yields a keyless model.
- **When** title sanitization runs, **Then** rawTitle survives alongside the cleaned display title.
- **And** durations are ms-typed value classes preventing unit mix-ups at compile time.

**Tests:** exhaustive pure JVM factory/sanitization cases.

### Story 2.2 - SwayResult & SwayError taxonomy *(3 pts, deps: 1.1)*

As the owner-builder, I want one typed result union whose failure categories map 1:1 onto
UI states, so that swallow-and-return-empty is impossible everywhere from day one (C-2).

**Traces:** AR-8, AD-9 table, NFR-2 substrate, FR-37 substrate. **Tasks:** `SwayResult<T>`
sealed Success/Failure(SwayError); SwayError categories Offline, RateLimited,
UpstreamUnavailable, Parse, ContentNotFound, Storage, Unknown(cause) each carrying the
UX-state mapping constant; combinators (map/onSuccess/onFailure/recoverToState).

**Acceptance Criteria:**
- **Given** each of the seven categories constructed, **When** mapped through the canonical mapper, **Then** each lands on its documented UI state (exhaustive `when` compiles without else).
- **When** a Failure propagates across a module boundary, **Then** it travels as a value (no throw sites permitted; lint/review note enforced).
- **And** Unknown always preserves its cause chain for diagnostics while exposing no stack trace to UI.

**Tests:** taxonomy exhaustiveness + combinator unit tests.

### Story 2.3 - ArtworkRef & candidate chain *(2 pts, deps: 1.1)*

As the owner-builder, I want artwork represented as data (canonical URL + ordered
fallback candidates), so that consumers hold zero host-specific URL logic (AD-11).

**Traces:** AR-10, FR-35/36 substrate. **Tasks:** ArtworkRef value object; equality and
cache-key identity = canonical URL; ordered immutable candidate chain; helper describing
"walk-on-failure" contract consumed later by `:designui`.

**Acceptance Criteria:**
- **Given** two ArtworkRefs with identical canonical URLs but different chain orders, **When** compared, **Then** they differ (order is semantic).
- **When** cache keys derive, **Then** they equal the canonical URL string exactly.
- **And** an absent-artwork case constructs a synthetic-chain ref per AD-11's video-id pattern rule.

**Tests:** pure JVM equality/key/synthesis cases.

### Story 2.4 - Ports & playback vocabulary *(3 pts, deps: 2.1, 2.2)*

As the owner-builder, I want CatalogSource and StreamResolver declared once with their
request/response vocabulary, so that adapters and consumers can be built against fakes in
parallel forever after.

**Traces:** AR-2/AR-6, AD-6/AD-7 signatures. **Tasks:** CatalogSource methods
(searchSongs/searchAlbums/searchArtists/searchCatalogPlaylists with page tokens;
album(id)/artist(id)/catalogPlaylist(id)) all returning SwayResult; StreamResolver =
resolveAudio/invalidate/prefetchNext per AR-6; AudioRequest(Quality, forceRefresh);
ResolvedAudio(url, expiresAtEpochMs, bitrateKbps, containerHint, backendTag,
renditionCacheKey); Quality enum AUTO/LOW/MEDIUM/HIGH; QueueSnapshot/QueueItem model.

**Acceptance Criteria:**
- **Given** fake implementations compiled against the ports, **When** consumers exercise them, **Then** no method returns bare lists/strings where SwayResult/value objects are specified.
- **When** KDoc review runs, **Then** each port cites its governing AD rules.
- **And** `:core:model` contains zero Android imports (CI import-ban check green).

**Tests:** compile-time contract against provided fakes; import-ban CI green.


## Epic 3 - Catalog Adapter - NewPipe Behind Ports

**Goal:** Prove the catalogue layer end-to-end at the port boundary: four-type search,
album/artist/catalog-playlist detail, and stream resolution with expiry parsing,
in-flight dedup, and bitrate-target selection - all with NewPipeExtractor visible only
inside `:catalog`.

**Scope in:** downloader impl on OkHttp, mappers producing typed models with ArtworkRefs
computed at parse time, contract tests on recorded fixtures, MockWebServer edge cases,
NewPipeStreamResolver.
**Scope out:** repositories, caching policy, any UI.
**Completes:** nothing directly (enabler; FR-12 engine proof lands in 4.4). **Depends
on:** E1, E2.

**Exit criteria:** fixture-driven contract tests + MockWebServer category-injection
tests green; tagged live-source smoke executed manually once (A-1: upstream drift not
CI-stable); extractor isolation audits stay green.

### Story 3.1 - Extractor bootstrap & OkHttp downloader *(3 pts, deps: 1.1, 2.2)*

As the owner-builder, I want NewPipeExtractor initialized behind our own downloader, so
that network I/O obeys the one-stack rule from the first request.

**Traces:** AR-2, AR-4, AD-3. **Tasks:** pin `com.github.TeamNewPipe:NewPipeExtractor`
v0.26.5 in `:catalog` only; `SwayDownloaderImpl` on the shared OkHttpClient derivation;
service initialization; request/response logging per AR-14.

**Acceptance Criteria:**
- **Given** the initialized extractor, **When** a search executes against MockWebServer, **Then** requests flow exclusively through SwayDownloaderImpl/OkHttp.
- **When** the isolation audit runs, **Then** the extractor coordinate exists only in `:catalog`.
- **And** timeouts/proxy config derive from the shared client builder, not ad-hoc values.

**Tests:** unit w/ MockWebServer; audit green.

### Story 3.2 - Four-type search mappers *(5 pts, deps: 3.1)*

As a listener (via later UI), I need queries returning typed grouped results, so that
discovery speaks only in Sway models.

**Traces:** FR-1 data side (trace; completion E10), AR-2, AR-8. **Tasks:**
`NewPipeCatalogSource.search*` for Songs/Albums/Artists/CatalogPlaylists with page
continuation; parse-time ArtworkRef normalization; duration conversion; blank-id items
dropped with logged shape info.

**Acceptance Criteria:**
- **Given** recorded fixtures per type, **When** mapped, **Then** typed groups carry title/ids/duration/artwork-chain and page tokens.
- **Given** HTTP 429, **When** search executes, **Then** Failure(RateLimited) returns (never empty list).
- **Given** malformed payload, **When** parsed, **Then** Failure(Parse) with shape info logged; oversized body maps to UpstreamUnavailable [PROVISIONAL EP-5].
- **And** a fixture item lacking id is absent from results while siblings survive.

**Tests:** contract tests on fixtures; MockWebServer category injections; tagged manual
live smoke.

### Story 3.3 - Album detail mapper *(3 pts, deps: 3.1)*

As a listener, I need album pages as typed Album + ordered tracklist, so that detail
screens (E10) have honest data including clean omission of absent year.

**Traces:** FR-5 data side (trace). **Tasks:** `album(id)` mapping; optional year as
null (never ""); track order preserved; artwork chain per track + hero.

**Acceptance Criteria:**
- **Given** a fixture with year present/absent variants, **When** mapped, **Then** year is populated or null-with-clean-omission semantics respectively.
- **When** tracklist maps, **Then** order matches source order and ids are unique/non-blank.

**Tests:** fixture contract tests.

### Story 3.4 - Artist detail mapper *(3 pts, deps: 3.1)*

As a listener, I need artist pages with top songs always available and albums/singles
sections honestly marked absent when the transport does not supply them, so that UI omits
cleanly under OQ-1's degraded tier.

**Traces:** FR-6 data side (trace). **Tasks:** `artist(id)` mapping; sections modeled as
available/unavailable flags; circular-image ArtworkRef.

**Acceptance Criteria:**
- **Given** fixture without discography payload, **When** mapped, **Then** albums/singles report unavailable (not empty-as-success).
- **Given** top-songs payload, **When** mapped, **Then** list is playable-typed and ordered.

**Tests:** fixture contract tests both branches.

### Story 3.5 - Catalog Playlist detail mapper *(3 pts, deps: 3.1)*

As a listener, I need curated playlists mapped read-only with curator/count/ordered
tracks, so that their detail screens mirror album semantics without edit affordances ever
being possible.

**Traces:** FR-7 data side (trace). **Tasks:** `catalogPlaylist(id)` mapping; count from
source when provided; ordering preserved.

**Acceptance Criteria:**
- **Given** fixture, **When** mapped, **Then** curator/count/tracklist fields populate and no mutation surface exists on the model.
- **When** count absent upstream, **Then** model carries null and UI contract notes derived counting.

**Tests:** fixture contract tests.

### Story 3.6 - NewPipeStreamResolver *(8 pts, deps: 3.1, 2.4)*

As a listener, I need streams resolved reliably with expiry known upfront, so that
playback survives ephemeral URLs instead of band-aid retrying them (L5/L6).

**Traces:** AR-6, AD-7 layers' substrate, C-5/C-6, FR-15 selection rule. **Tasks:**
`NewPipeStreamResolver`: format ladder incl. ciphered-format fallback paths; expiry param
parsed to expiresAtEpochMs (never guessed); LRU rendition cache keyed SourceId+quality
discriminator; mandatory in-flight single-flight dedup; `invalidate`;
`prefetchNext` silent-null; bitrate-target selection (best-under-target else max; AUTO =
unmetered->MEDIUM-class, metered->LOW-class); forceRefresh bypass.

**Acceptance Criteria:**
- **Given** two concurrent identical resolves, **When** both await, **Then** exactly one network fetch occurs and both receive the same ResolvedAudio (dedup verified).
- **Given** streams at bitrates around target T, **When** selecting for LOW/MEDIUM/HIGH/AUTO classes, **Then** chosen rendition satisfies best-under-target-else-max (table-driven cases).
- **Given** a URL whose expiry parameter parses, **When** resolved, **Then** expiresAtEpochMs equals that parameter (not a constant offset).
- **When** `invalidate(trackId)` then resolve, **Then** cache bypassed and fresh fetch occurs.
- **And** prefetchNext returns null silently on failure without throwing.

**Tests:** MockWebServer stream fixtures; dedup coalescing unit; expiry-parse cases;
selection tables; tagged manual live smoke (R-2 ciphered-prevalence observation logged).

## Epic 4 - Playback Engine - One-Song Core Loop

**Goal:** Audio actually playing under service ownership: context-built placeholder
queue, exactly ONE up-front resolve for the start item, just-in-time transition
resolution with a single-flight guard - proven by a resolver test double before any UI
exists.

**Scope in:** SwayPlaybackService skeleton, PlayerConnection facade + PlayerUiState,
queue builder + placeholder scheme, first-audio path + transitions + prefetch plumbing.
**Scope out:** notification polish, watchdog/renewal (E5), modes/persistence (E7), UI.
**Completes:** FR-12. **Depends on:** E1, E2; story 4.4 depends on 3.6.

**Exit criteria:** instrumented controller-driven play of fixture audio starts <=3 s p95
(engine-level FR-8 evidence); resolver double counts exactly one up-front resolve across
queue build + several auto-transitions.

### Story 4.1 - SwayPlaybackService skeleton *(5 pts, deps: 1.1)*

As a listener, I want playback owned by a foreground media service, so that audio does
not die when UI detaches (C-3/L3 skeleton).

**Traces:** AR-5, AD-6 rule 1/8, NFR-10 contribution. **Tasks:** `SwayPlaybackService :
MediaLibraryService` in `:playback`; ExoPlayer built in onCreate with music
AudioAttributes, focus handling enabled, becoming-noisy enabled, network wake mode;
manifest registration + foregroundServiceType=mediaPlayback in `:app`; idle self-stop
when released; basic MediaSession exposure.

**Acceptance Criteria:**
- **Given** a connected MediaController in Robolectric, **When** play is commanded with a prepared item, **Then** state transitions to ready/playing.
- **When** the session is stopped and released, **Then** the service stops itself (no zombie; checkable via service-aliveness assertion).
- **And** player configuration asserts music usage attributes + handleAudioFocus + handleAudioBecomingNoisy enabled.

**Tests:** Robolectric service lifecycle suite.

### Story 4.2 - PlayerConnection facade & PlayerUiState *(5 pts, deps: 4.1)*

As UI (future), I want one hoisted state flow and command surface, so that every screen
reflects playback truth without owning player logic (state-sync discipline UX section
12.8).

**Traces:** AR-5, AD-6 rule 2, FR-27 sync substrate. **Tasks:** long-lived
`MediaController` wrapper exposing commands (setQueue/play/pause/seekTo/jump/next/
previous/toggleModes placeholders) + `StateFlow<PlayerUiState>` (isPlaying, currentItem
snapshot, buffering, positionMs published ONLY to active scrubber-collector scope,
failedTrack slot reserved for E5); rebind-safe controller lifecycle.

**Acceptance Criteria:**
- **Given** a collector of PlayerUiState, **When** playback state changes service-side, **Then** the flow emits within the sync budget harness (<=250 ms measured in test).
- **When** no scrubber subscribes, **Then** position ticks are not emitted (tick scoping verified).
- **When** the controller disconnects and reconnects, **Then** facade resubscribes without leaking controllers.

**Tests:** Robolectric facade tests incl. latency measurement harness.

### Story 4.3 - Queue builder & placeholder scheme *(3 pts, deps: 2.4, 4.2)*

As a listener, any future play action should enqueue context snapshots as placeholders,
so that queue-wide metadata exists without resolving anything (L4/C-4).

**Traces:** AR-5, AD-6 rules 3/6, FR-22 semantics substrate. **Tasks:** context ->
QueueSnapshot builder (song tap / album play / shuffle variants); `sway://pending/<sourceId>`
defined in exactly ONE object in `:playback`; `setQueue(snapshot, startIndex)` command.

**Acceptance Criteria:**
- **Given** a play context at index k of n items, **When** snapshot builds, **Then** all n items appear with the chosen item at startIndex and zero resolved URLs.
- **When** any other module attempts constructing/mutating the placeholder scheme, **Then** it cannot (scheme API private to its owner; grep-audited).
- **And** shuffle-context input produces a deterministically shuffled order preserving the chosen current item.

**Tests:** pure JVM builder tests incl. shuffle determinism seeds.

### Story 4.4 - First-resolve path & just-in-time transitions *(8 pts, deps: 4.3, 3.6)*

As a listener, my first track should start fast while the rest of the queue resolves
only at transition time, so that starting an N-track queue costs exactly one resolution
(FR-12/L4).

**Traces:** FR-12 COMPLETES HERE; FR-8 engine-level trace (<=3 s p95); AR-6 prefetch
rules. **Tasks:** `onSetMediaItems` resolves START item only, others get placeholder
URIs; prepare+play; `onMediaItemTransition` detects placeholder -> single-flight guard ->
resolveAudio(next) -> replaceMediaItem; optional age-capped prefetchNext during playback
(skipped when repeat-one flag set - flag arrives E7, guard coded now); failure of start
resolve surfaces Failure via PlayerUiState error slot.

**Acceptance Criteria:**
- **Given** an 8-item queue started at index 2 with a counting resolver double, **When** playback begins and two auto-transitions occur, **Then** the double counts exactly 3 total resolves (1 up-front + 2 transition) - proving the up-front budget = 1 (FR-12).
- **Given** forced-expiry-free happy path with fixture audio, **When** play commanded via controller, **Then** audio output begins <=3 s p95 in the instrumented harness (engine-level FR-8 evidence recorded for 12.4 completion).
- **When** prefetchNext fires opportunistically, **Then** it never counts against the up-front budget and applies its age cap before use.
- **Given** start-resolve failure, **When** handled, **Then** PlayerUiState carries the typed SwayError category and no crash occurs.

**Tests:** resolver-double state-machine tests; Robolectric transition tests; instrumented
tap-to-audio timing harness.


## Epic 5 - Stream Resilience - Expiry Defense & Watchdog

**Goal:** The layered expiry defense that makes playback trustworthy where the reference
failed: read-time validation with -5 min margin, error-triggered renewal resuming within
+/-3 s, a stuck-buffer watchdog escalating at 3 s/15 s, and a persisted audio-quality
preference implemented as bitrate targets.

**Scope in:** defense layers 1-3, SettingsRepository birth (DataStore), forced-expiry and
forced-stall suites.
**Scope out:** UI presentation of failures (traces land in 9.2/12.x).
**Completes:** FR-13 (5.3), FR-14 (5.4), FR-15 (5.1), NFR-3 (5.2-5.4 suites).
**Depends on:** E4 complete; 5.1 independent of 5.2-5.4 inside the epic.

**Exit criteria:** SM-2 forced-expiry suite passes 100% within FR-13 bounds; forced-stall
ladder recovers or escalates within stated bounds; dedup coalescing proven under
concurrent failure injection.

### Story 5.1 - Audio-quality preference & SettingsRepository birth *(3 pts, deps: 2.4)*

As a listener, I want my quality choice remembered and honored from the next resolution,
so that streams match my network reality without format bookkeeping (L6).

**Traces:** FR-15 COMPLETES HERE (presentation chip traces 12.4; OQ-6-gated visibility),
AR-7 conventions (one DataStore file), C-6/AD-7 selection rule. **Tasks:**
`:core:data` SettingsRepository over one namespaced DataStore preferences file; key
`playback.audio_quality`; async reads only (AD-10); resolver consumes Quality per request;
changing preference does NOT invalidate the current track (applies next resolution).

**Acceptance Criteria:**
- **Given** persisted quality MEDIUM, **When** service restarts and resolves, **Then** AudioRequest carries MEDIUM without any synchronous read on the startup path.
- **Given** a mid-track change to HIGH, **When** the current track continues, **Then** audio does not re-resolve; the NEXT resolution uses HIGH (asserted via double).
- **When** AUTO is set on metered vs unmetered networks, **Then** targets map LOW-class vs MEDIUM-class respectively (connectivity-class injected for tests).

**Tests:** settings persistence unit tests; resolver target-mapping tables.

### Story 5.2 - Read-time validation layer *(3 pts, deps: 4.4, 3.6)*

As a listener, I want stale URLs discarded before they are attempted, so that expiry
never becomes an audible error (defense layer 1).

**Traces:** FR-13 read-time clause, NFR-3, AR-6 prefetch age cap folding into this check.
**Tasks:** validity check at use: expiresAtEpochMs - (-5 min) margin must lie in future,
else discard + fresh resolve BEFORE play; identical single check governs cached and
prefetched URLs; margin constant named and P-5-tunable.

**Acceptance Criteria:**
- **Given** a prefetched URL expiring in 4 minutes, **When** its item transitions to current, **Then** it is discarded and a fresh resolve occurs before play (double counts replacement).
- **Given** a URL expiring in 10 minutes, **When** used, **Then** play proceeds without re-resolve.
- **And** the prefetch age cap adds no second mechanism - it folds into this one check (code inspection assertion).

**Tests:** boundary-table unit tests (margin minus/plus cases) via doubles.

### Story 5.3 - Error-triggered renewal with position resume *(5 pts, deps: 5.2)*

As a listener, a mid-song expiry should cost me nothing, so that 403/410 purges, resolves
fresh, and lands within +/-3 s of where I was (SM-2 core).

**Traces:** FR-13 COMPLETES HERE; NFR-3; AD-7 layer 2; L5 evidence chain. **Tasks:** HTTP
403/410 detection -> invalidate(trackId) -> deduped resolve -> seek to last audible
position -> resume; position source = service-side ticker snapshot; concurrent-failure
coalescing guaranteed by resolver dedup.

**Acceptance Criteria:**
- **Given** a playing stream that returns 403 mid-play, **When** recovery completes, **Then** audible resume lands within +/-3 s of the lost position across 20 forced trials (SM-2 100% pass).
- **Given** two simultaneous 410s for the same Source ID, **When** both handlers react, **Then** exactly one fresh resolve executes (dedup verified under concurrency).
- **And** renewal failure surfaces the typed category on PlayerUiState.failedTrack instead of retrying forever.

**Tests:** instrumented forced-expiry suite (MockWebServer-driven datasource); SM-2 record
artifact emitted.

### Story 5.4 - Stalled-playback watchdog *(5 pts, deps: 4.4)*

As a listener, buffering that goes nowhere should self-heal or move on honestly, so that
a dead stream never hangs my session (FR-14/L5 ladder).

**Traces:** FR-14 COMPLETES HERE; NFR-3 escalation bounds; P-5 thresholds as initial
targets; Media3 StuckPlayerException configured as backstop only. **Tasks:** ticker-driven
watchdog in `:playback`: >3 s stall -> retry with downscale replay (lower bitrate target);
>15 s sustained -> full stream rebuild (forceRefresh resolve); repeated rebuild failure ->
skip to next Queue item + PlayerUiState.failedTrack(category); thresholds constants;
backstop exception handler logs-and-yields to our policy.

**Acceptance Criteria:**
- **Given** injected stalls at 3.5 s / 16 s / repeated-rebuild-failure, **When** each scenario runs, **Then** recovery/downscale, full rebuild, and skip-with-typed-reason occur respectively within stated bounds (forced-stall suite).
- **When** a track is skipped, **Then** the failed Song carries its reason category consumable by SongRow's failed variant and queue rows.
- **And** watchdog never fires during normal gapless transition (transition timing excluded from stall accounting).

**Tests:** forced-stall instrumented suite with controllable fake datasource clock.

## Epic 6 - Background Playback & System Integration

**Goal:** Sway as a polite first-class Android citizen: uninterrupted background audio, a
media notification and lock-screen session indistinguishable from in-app truth, correct
focus/route manners, and the recents-swipe posture.

**Scope in:** notification provider wrapper, lock-screen parity assertions, focus/route
scenario automation, POST_NOTIFICATIONS flow, swipe-away behavior.
**Scope out:** widget/cast/Auto (non-goals).
**Completes:** FR-16..FR-21 (6.1 x2, 6.2 x2, 6.3). **Depends on:** E4.

**Exit criteria:** 10-min background/screen-off soak shows zero app-attributable gaps;
focus and route suites green; notification parity checklist recorded.

### Story 6.1 - Media notification, lock screen & background continuity *(5 pts, deps: 4.1, 4.2)*

As a commuter, I control playback from pocket and lock screen exactly as in-app, so that
audio survives my whole ride (UJ-2).

**Traces:** FR-16, FR-17, FR-18 COMPLETES HERE; A-10 dismissal default; NFR-4 substrate.
**Tasks:** thin Media3 notification provider wrapper (artwork/title/artist/
prev-play-pause/next); dismisses-when-paused platform default kept; session metadata
exactly mirrors PlayerUiState truth; instrumented 10-min background + screen-off gap
detector.

**Acceptance Criteria:**
- **Given** active playback, **When** app backgrounds and screen turns off for 10 minutes, **Then** zero audio gaps attributable to the app (gap-detector suite).
- **Given** notification controls, **When** pressed, **Then** behavior is indistinguishable from in-app equivalents (command-parity assertions).
- **When** paused, **Then** the notification dismisses; when dismissed while playing, **Then** playback stops per platform default (A-10 documented).
- **And** lock screen shows artwork/title/artist/duration matching the playing track exactly (metadata-equality assertions).

**Tests:** instrumented soak + parity suite; manual device matrix note.

### Story 6.2 - Audio focus & route-change compliance *(3 pts, deps: 4.1)*

As a listener taking a call or unplugging headphones, playback must yield instantly and
predictably, so that Sway is never rude and never blasts the speaker (AD-12 fixes UX's
duck-vs-pause open point: pause on transient loss; duck only where platform grants).

**Traces:** FR-19, FR-20 COMPLETES HERE; AR-11. **Tasks:** automated focus scenarios
(transient-loss pause, transient-may-duck ducking, regain-resume only for transient,
permanent-loss stop); becoming-noisy pause <1 s; reconnect never auto-resumes.

**Acceptance Criteria:**
- **Given** an incoming-call focus request, **When** granted, **Then** playback pauses immediately and resumes ONLY after focus regained post-call (automation suite).
- **Given** BT/wired route disconnect during play, **When** detected, **Then** pause occurs <1 s (measured) and reconnect performs NO auto-resume.
- **And** no overlap with other apps' audio except platform-granted ducking (focus-log assertions).

**Tests:** focus/route automation suite (media-session test helpers); timing assertions.

### Story 6.3 - Recents-swipe posture & notification permission *(3 pts, deps: 6.1)*

As a listener swiping the task away, music should keep going until I stop it from the
notification, and Android 13+ permission prompts must explain themselves first.

**Traces:** FR-21 COMPLETES HERE (PROVISIONAL P-3/OQ-5); R-3 evidence collection; AD-4
(no distribution claims anywhere). **Tasks:** verify swipe-away continuation with
service-aliveness assertions; POST_NOTIFICATIONS explain-first rationale screen copy
(lock-screen-control consequence wording); denied-state degradation note captured for
release checklist.

**Acceptance Criteria:**
- **Given** active playback, **When** the app is swiped from Recents, **Then** playback continues and the notification remains the stop affordance.
- **Given** notifications denied on API 33+, **When** playback runs, **Then** behavior matches the documented degradation (recorded observation feeds R-3).
- **And** permission rationale copy precedes the system dialog (flow test).

**Tests:** instrumented swipe-away + permission-flow UI tests.

## Epic 7 - Queue Management & Playback Session Persistence

**Goal:** A fully manipulable queue with durable memory: jump/remove/reorder/enrichment
commands, shuffle/repeat modes that persist, and a Playback Session that survives process
death and restores paused (Room database born here with QueueStateEntity only -
create-when-needed principle).

**Scope in:** command semantics, modes + persistence, Room DB initialization +
QueueSnapshot serializer + restore path.
**Scope out:** queue UI surface (E12).
**Completes:** FR-25 (7.3). FR-22/23/24 engine semantics built here as traces.
**Depends on:** E4; 7.2 needs 5.1.

**Exit criteria:** kill-and-relaunch instrumented test restores exact session +/-5 s,
paused; resolver double still counts zero extra up-front resolves after manipulations.

### Story 7.1 - Queue command semantics *(5 pts, deps: 4.3, 4.4)*

As a listener, I can reorder my listening live - jump, remove, play-next, add-to-queue,
clear - so that the queue obeys me mid-session (FR-23/24 semantics).

**Traces:** FR-22 semantics (engine side; completion E12), FR-23/24 command substrate
(completion E12), A-4 prev-restart rule, FR-11 toggle semantics. **Tasks:** commands via
PlayerConnection: jump(index) <=2 s switch; remove(item) incl. removing-playing advances
to next; playNext insert-after-current; addToQueue append; clear (confirmation is UI-side
later); drag-reorder persistence within session; shuffle toggle preserving current with
deterministic reshuffle of remainder; repeat off/all/one cycling incl. repeat-one replay
and next-at-end respecting repeat.

**Acceptance Criteria:**
- **Given** a playing queue, **When** jump(k) commanded, **Then** audio switches <=2 s (instrumented timing) and exactly one new resolve occurs (for item k at its transition).
- **When** the playing item is removed, **Then** the next item advances automatically without silence.
- **Given** shuffle toggled ON, **When** reshuffle executes, **Then** current track stays put, remainder order changes deterministically for the same session seed, and repeat-one disables prefetch (guard from 4.4 engaged).
- **Given** previous pressed at >=5 s played vs <5 s, **Then** current restarts vs jumps back respectively (A-4).

**Tests:** resolver-double command state-machine suite; Robolectric transition checks.

### Story 7.2 - Modes persistence *(3 pts, deps: 7.1, 5.1)*

As a listener, my shuffle/repeat choices should survive launches, so that I never reset
my listening style (FR-11 persistence clause).

**Traces:** FR-11 persistence (toggle UX completes E12); AD-6 rule 5. **Tasks:**
SettingsRepository keys `playback.shuffle` / `playback.repeat`; written on change;
restored at service start before first queue build; restored values reflected in
PlayerUiState.

**Acceptance Criteria:**
- **Given** repeat-one left active, **When** process dies and relaunches, **Then** service initializes with repeat-one (no synchronous startup reads).
- **When** mode changes rapidly, **Then** last-write-wins persists correctly (debounced writes verified).

**Tests:** settings round-trip + service-init order tests.

### Story 7.3 - Session persistence & paused restore *(8 pts, deps: 7.1, 7.2)*

As Alex falling asleep to a long mix, I want yesterday's queue back tomorrow - paused,
exactly where it stopped - so that one tap resumes my night (UJ-4; FR-25).

**Traces:** FR-25 COMPLETES HERE; NFR-4 substrate; AR-7 (Room born: QueueStateEntity +
QueueStateDao, exportSchema migration 1, explicit migrations only, destructive fallback
refused); AR-9 post-composition restore hook installed for 9.4/12.1 consumption; UJ-4
engine truth. **Tasks:** canonical QueueSnapshot serializer owned by `:core:data`
(single representation law); debounced saves on meaningful transitions; restore =
post-composition coroutine reading QueueStateDao -> paused session in service +
PlayerUiState, NEVER auto-playing; kill-and-relaunch instrumented test.

**Acceptance Criteria:**
- **Given** a playing session killed via `adb shell am kill`, **When** app relaunches, **Then** queue/current index/position(+/-5 s)/modes all restore and playback remains PAUSED (auto-play forbidden).
- **When** schema mismatches export, **Then** startup fails loudly (no destructive fallback) - migration-test proof.
- **Given** no saved state (first run), **When** restore hook runs, **Then** clean empty state with no Mini-Player session marker.
- **And** the serializer lives in exactly one module (`:core:data`) with all other modules consuming models (grep audit).

**Tests:** MigrationTestHelper against exported schema; instrumented kill-relaunch suite;
serializer round-trip property tests.


## Epic 8 - Owned Data Layer - Likes, Playlists, History

**Goal:** The user's owned data made durable and fast: likes, playlists, history
repositories over an expanding Room schema (explicit tested migrations), plus the Offline
Fallback Cache - everything local-first, offline-complete, typed at every boundary.

**Scope in:** schema expansion (SongEntity likedAt, Playlist entities, HistoryEntity),
repositories, service-side history recording hook, fallback cache store.
**Scope out:** any screens (E11).
**Completes:** nothing directly (enabler; FR-30..34 complete in E11/E12).
**Depends on:** 7.3 (database exists); E2 types.

**Exit criteria:** DAO contract + migration tests green; per-category failure-injection
tests green per repository (NFR-2 pattern established here); fallback-cache TTL and
validation suite green.

### Story 8.1 - Likes schema & LibraryRepository *(5 pts, deps: 7.3)*

As Priya hearting songs through her discovery week, I want likes durable on-device, so
that my collection survives anything short of uninstalling (UJ-3 week one).

**Traces:** FR-30 persistence substrate (cross-surface sync completes E12); AD-8
(SongEntity likedAt nullable timestamp, NULL=not liked, indexed). **Tasks:** migration 2
adding SongEntity + index; LibraryDao (liked flow ordered likedAt desc, set/clear,
isLiked batch); LibraryRepository exposing Flow + suspend commands returning SwayResult;
Storage category on IO failure.

**Acceptance Criteria:**
- **Given** migration 1->2 on a populated database, **When** MigrationTestHelper runs, **Then** migration succeeds and data survives intact.
- **Given** like/unlike toggles from concurrent callers, **When** writes settle, **Then** final state is consistent and the liked flow emits correctly ordered.
- **Given** injected DAO IO failure, **When** repository called, **Then** Failure(Storage) returns - never an empty list masquerading as success (NFR-2 injection pattern).

**Tests:** Robolectric Room in-memory DAO contracts; migration test; failure-injection units.

### Story 8.2 - Playlists schema & PlaylistRepository *(5 pts, deps: 8.1)*

As Priya building "Gym", I want playlists that hold ordered songs with instant saves, so
that Sunday's arrangement is exactly Monday's queue (UJ-3).

**Traces:** FR-31/32 substrate (completion E11); AD-8 entities/DAO sketch. **Tasks:**
migration 3 adding PlaylistEntity + PlaylistSongEntity (composite PK, per-playlist
position index, addedAt); multi-step edits in @Transaction; duplicate names allowed by
design; delete cascades join rows only.

**Acceptance Criteria:**
- **Given** add/remove/reorder performed together, **When** executed, **Then** one transaction persists all changes atomically (failure mid-way rolls back fully).
- **Given** two playlists named identically, **When** created, **Then** both persist independently.
- **When** a song removed from one playlist, **Then** its membership elsewhere is untouched (multi-membership invariant).
- **And** reorder updates positions contiguously with no gaps/duplicates (property check over random operation sequences).

**Tests:** transactional playlist edit suite incl. randomized operation property tests;
migration test.

### Story 8.3 - History schema, recency upsert & service-side recording hook *(5 pts, deps: 8.1)*

As Alex replaying Tuesday's discovery, I want plays recorded automatically without
duplicates stacking, so that History reads like a diary of listening (FR-34/A-5).

**Traces:** FR-34 substrate (replay surface completes E11); AR-5 rule 7 (recording
EXCLUSIVELY service-side via single write path). **Tasks:** migration 4 HistoryEntity
(songId FK, playedAt; upsert keyed by songId so replays update recency; trim to most
recent 500 on write); HistoryRepository paged reverse-chron flow; `:playback` records once
a track passes 10 s cumulative played (service-side ticker, single path).

**Acceptance Criteria:**
- **Given** the same song played three times, **When** recorded each time past 10 s, **Then** History holds ONE entry with latest playedAt (no stacking).
- **Given** 505 distinct plays, **When** trim runs, **Then** table holds exactly the 500 most recent.
- **Given** a track abandoned at 9 s, **When** skipped, **Then** no record occurs (10 s rule).
- **And** no UI-layer observer can double-record (single write-path audit grep).

**Tests:** DAO recency/trim tests; service-side trigger unit with fake clock; migration test.

### Story 8.4 - Offline Fallback Cache store *(5 pts, deps: 2.2)*

As Sofia underground, I want recent searches served stale-but-honest when the network
dies, so that the app stays useful instead of blank (L8/UJ-5).

**Traces:** C-8/AD-9 conventions; FR-4 substrate (presentation completes E10.4);
72 h deletion TTL; strict element-type validation on read (corrupt entry deleted -
reference's shipped-crash lesson). **Tasks:** JSON files under cacheDir keyed by request
shape; write-through hook API for repositories; read-on-failure API returning
stale-marked payload or miss; TTL sweep on access; validation deletes corrupt entries.

**Acceptance Criteria:**
- **Given** cached results younger than 72 h, **When** a network call fails with Offline/UpstreamUnavailable, **Then** the cache serves them flagged stale=true.
- **Given** entries older than 72 h, **When** accessed, **Then** they are deleted and a miss returns (expired never masquerades as fresh).
- **Given** a hand-corrupted cache file, **When** read, **Then** validation fails, file is deleted, miss returns - no crash.
- **And** cache is served ONLY on failure paths (never preferred over fresh network data).

**Tests:** TTL/deletion/corruption/validation unit suite with temp-dir store.


## Epic 9 - Design Language & Navigation Shell

**Goal:** The Sway look and skeleton: token-perfect M3 Expressive theme, the typed-state
component kit that makes blank screens impossible, the three-tab shell with push-over
details, startup-law compliance, and the Search-first Home landing (FR-3's degraded
branch).

**Scope in:** theme/tokens/motion, state kit + SongRow variants + cards/heroes, nav shell,
startup law + offline launch routing, Home landing page.
**Scope out:** feature screens (E10/E11), player surfaces (E12).
**Completes:** FR-3 (9.5), FR-26 (9.3), NFR-1 (9.4). **Depends on:** E1; 9.5 reads E8
flows; 9.4 installs the restore hook consumed later.

**Exit criteria:** screenshot tests light+dark green; navigation smoke proves reachability
<=2 taps and tab-state preservation; cold-start macrobenchmark <=2.5 s p95 on Baseline
profile with StrictMode clean.

### Story 9.1 - SwayTheme tokens on M3 Expressive *(5 pts, deps: 1.1)*

As a listener, I want Sway to look like nothing else on my phone, so that the brand feels
owned: "Ink & Paper" monochrome calm by default, and the whole app recolored by whatever
is playing — warm type, expressive-but-disciplined motion. [OWNER AMENDMENT 2026-08-24:
violet brand retired; two-mode system per UX §7.1 — SuvMusic-inspired artwork-dynamic
engine with spring-animated scheme transitions.]

**Traces:** UX-DR1..4; UX-P1/P2; NFR-6 substrate (MotionScheme); AD-13. **Tasks:** in
`:designui`: Ink & Paper mono ColorSchemes (light + dark + AMOLED pure-black variant;
Notion-philosophy neutrals, ink primaries, semantic rose=like / amber=caution preserved)
as the DEFAULT mode; artwork-DYNAMIC mode: PaletteExtractor (Bitmap -> dominant/vibrant
seed swatches) + DynamicSchemeFactory (seed -> full light/dark ColorScheme) with
spring-animated scheme transitions; ThemeMode(MONO/DYNAMIC) parameter on the Theme
composable (persistence lands 15.1); Outfit+Inter bundled; type ramp with tabular-figure
style for numerics; shape ramp; MotionScheme mapping duration/easing/spring tokens incl.
reduced-motion override (opacity fade <=120 ms); Theme composable + preview harness.

**Acceptance Criteria:**
- **Given** system dark/light toggles, **When** app renders, **Then** correct scheme applies with all roles from the token set (screenshot pairs).
- **Given** reduced-motion enabled, **When** any token animation runs, **Then** it degrades to opacity fade <=120 ms (motion-harness assertions).
- **Given** a seed bitmap in DYNAMIC mode, **When** the scheme is derived, **Then** all roles derive deterministically from the dominant swatch with contrast floors honored (onPrimary vs primary auditable), and MONO applies whenever no seed exists.
- **And** no component outside `:designui` references raw colors/fonts (import lint).

**Tests:** Compose screenshot tests light x dark; motion-token unit tests; palette-extractor + dynamic-factory unit tests over synthetic bitmaps.

### Story 9.2 - Typed-state kit & core components *(8 pts, deps: 9.1)*

As a listener, every surface should always show exactly one honest state, so that a blank
screen or silent failure is structurally impossible (FR-37 kit substrate; P-D3).

**Traces:** UX-DR5/6/7/11/15; FR-37 substrate (audit completes E14); FR-14 failed-variant
substrate. **Tasks:** components in `:designui`: Skeleton variants (SongRow ghost,
HeroHeader ghost, CardGrid ghost; one shared shader; 150 ms crossfade arrival);
ErrorPanel inline+area w/ Retry >=48 dp preserving caller state and copy-rotation hook;
EmptyState; OfflineBanner + StaleBadge; SnackbarHost (z-order above future Mini);
dialog scaffolds; chips/toggles set; ArtworkPlaceholder bounds-stable; SongRow visual
variants indexed/thumbnailnailed/playing/failed; AlbumCard/PlaylistCard/HeroHeader/
ArtistHeader per UX-DR7. Each data-driven component exposes a canonical
UiState<T> slot pattern (loading/content/empty/error).

**Acceptance Criteria:**
- **Given** each component rendered across its five canonical states in Compose tests, **When** state flips, **Then** exactly one state renders at any moment (kit-level FR-37 discipline proven at component scope).
- **Given** ErrorPanel retry pressed after injected failures, **When** retry succeeds, **Then** prior scroll/query is preserved (callback contract test).
- **Given** SongRow failed variant, **When** reason category provided, **Then** glyph + dimmed strike render with TalkBack announcement text available.
- **And** skeletons never appear for local Library data flows (API design makes instant-content path explicit).

**Tests:** Compose UI tests per component per state; screenshot tests light/dark.

### Story 9.3 - Navigation shell *(5 pts, deps: 9.1)*

As a listener, I want three tabs with details pushing over them and everything within two
taps, so that orientation is effortless and back always behaves (FR-26).

**Traces:** FR-26 COMPLETES HERE; UX section 3 rules; predictive back. **Tasks:** in
`:app`: SwayNavHost; bottom tabs Home/Search/Library with pill indicator; tab-scoped
back stacks + scroll-state preservation; typed routes for album/artist/catalogPlaylist/
playlist/liked/history/settings/about registered now (screens fill E10/E11/E15); deep-link
fallback parent = Library; reachability assertion harness.

**Acceptance Criteria:**
- **Given** navigation-smoke suite, **When** every destination is probed, **Then** each is reachable <=2 taps from launch.
- **Given** detail entered from a tab then back, **When** returning, **Then** the origin tab restores exact scroll/selection state.
- **When** system back/predictive-back gestures run on any stack depth, **Then** popping lands predictably with no hijack except documented close gestures.

**Tests:** navigation-compose UI test suite (reachability, tab preservation, back-stack).

### Story 9.4 - Startup law & offline launch routing *(3 pts, deps: 9.3)*

As Sofia opening the app underground, I want it to open normally into Library with the
banner raised, so that no connectivity never means no app (UJ-5 beat 1; NFR-1).

**Traces:** AR-9/AD-10 COMPLETING NFR-1 here; FR-38 substrate (end-to-end completes E14);
UX-P landing-mode routing rule 7. **Tasks:** splash dismisses on composition not data;
post-composition coroutine host invoking session-restore hook (wired to 7.3 output) and
theme load; connectivity observer raising/clearing OfflineBanner state app-wide;
offline launch routes to Library tab with banner; Macrobenchmark module variant +
Baseline-profile device config for cold start.

**Acceptance Criteria:**
- **Given** airplane mode, **When** cold start completes, **Then** app displays Library tab with offline banner and zero network-blocked frames (launch never awaits network).
- **Given** restored session snapshot exists, **When** post-composition restore runs, **Then** PlayerUiState reflects paused session without auto-play (hook contract verified; visual Mini arrives E12).
- **When** macrobenchmark cold-start runs on Baseline profile, **Then** interactive Home <=2.5 s p95 and StrictMode violations = zero (NFR-1 gate recorded).

**Tests:** Robolectric startup/offline-routing tests; macrobenchmark cold-start measurement
run recorded as artifact.

### Story 9.5 - Home Search-first landing *(3 pts, deps: 9.4, 8.1)*

As Maya opening Sway fresh, I land on a branded page that immediately invites search and
shows my collections, so that finding music starts in one tap (UJ-1 beat under OQ-1's
degraded branch).

**Traces:** FR-3 COMPLETES HERE (degraded minimum per architecture OQ-1 closure); UX
section 6.1 landing-mode spec; UX-P4. **Tasks:** brand header + tagline; prominent search
entry routing to Search tab with autofocus; shortcut tiles Liked Songs / recent Playlist /
Play History with live counts from repositories; honest "landing mode" labeling slot
reserved for future feed; pull-to-refresh intentionally absent in landing mode (documented
degradation).

**Acceptance Criteria:**
- **Given** first launch, **When** Home renders, **Then** brand header, search entry, and three collection tiles display with correct counts from local DB.
- **Given** tapping the search entry, **When** Search opens, **Then** the field holds focus ready to type (UJ-1 flow continuity).
- **When** offline, **Then** tiles remain fully functional and no loading skeleton ever appears for them (local-data honesty).

**Tests:** Compose UI tests incl. counts rendering; navigation continuation test.


## Epic 10 - Discovery - Search & Catalog Details

**Goal:** The discovery vertical slice: grouped four-type search with pagination and
offline-stale behavior, plus Album/Artist/Catalog Playlist detail screens whose Play and
Shuffle build real context queues - the first time catalogue data meets fingers.

**Scope in:** CatalogRepository (+ fallback integration), Search screen trio, three
detail screens, SongRow context menu assembly.
**Scope out:** player surfaces (queue interactions land via commands built in E7; sheet
arrives E12), local collections screens (E11).
**Completes:** FR-1 (10.2), FR-2 (10.3), FR-4 (10.4), FR-5 (10.5), FR-6 (10.6),
FR-7 (10.7). **Depends on:** E3 via 10.1, E8 (fallback cache), E9 (shell/kit).

**Exit criteria:** scripted instrumented suite: 20-query sample yields playable Song >=95%
(SM-1 partial evidence); grouped search render <=3 s p95 harness passes; failure-injection
matrix green per screen.

### Story 10.1 - CatalogRepository & fallback integration *(5 pts, deps: 3.2-3.5, 8.4)*

As the app, I want one repository boundary over CatalogSource with stale-marked fallback
on failure, so that screens receive typed states and never raw transport errors.

**Traces:** NFR-2 pattern application; FR-4 data integration; AD-9 mapping table.
**Tasks:** CatalogRepository wrapping port methods -> SwayResult; write-through to
fallback cache keyed by request shape; on Offline/UpstreamUnavailable return cached
stale-marked payload else Failure; group-isolation API for search (per-type calls fail
independently); failure-injection tests per category.

**Acceptance Criteria:**
- **Given** live network healthy, **When** searched, **Then** fresh results persist to fallback cache and return non-stale.
- **Given** Offline category injected, **When** cache hit exists, **Then** Success(Stale(payload)) returns; miss yields Failure(Offline) - both distinct states.
- **Given** one group failing while others succeed, **When** mapped, **Then** failing group carries its own error without blanking siblings.

**Tests:** fake CatalogSource injection suite covering every SwayError category (NFR-2
verification clause exemplar).

### Story 10.2 - Search screen core *(5 pts, deps: 10.1, 9.2, 9.3)*

As Maya typing three words of a chorus, I want grouped labeled results fast with honest
zero-match guidance, so that discovery feels instant and never blank (UJ-1).

**Traces:** FR-1 COMPLETES HERE (perf bound regression-checked in 14.4); UX-P3 debounce/
autofocus/recent-searches [PROVISIONAL]; DR5 states. **Tasks:** SearchViewModel debounced
350 ms submit-on-action; filter chip row All/Songs/Albums/Artists/Playlists; grouped
labeled sections (Songs first per UX-P7); zero-results Empty with spelling hint + Clear
action; per-request Error+retry preserving query; recent searches stored locally +
clearable; group-level error panels.

**Acceptance Criteria:**
- **Given** query "neon nights", **When** results arrive, **Then** up to four labeled groups render with Songs ordered first, each independently scrollable/filterable.
- **Given** a mistyped "chandelier sett", **When** zero matches return, **Then** typed Empty shows spelling hint and Clear - never a blank screen nor an error masquerade (distinct from failure path).
- **Given** network cut mid-query, **When** handled, **Then** group shows error-with-retry OR stale-marked fallback per 10.4 rules, siblings unaffected.
- **When** instrumented render-timing runs, **Then** grouped results <=3 s p95 at 10 Mbps profile.

**Tests:** Compose UI tests per state; instrumented timing harness; failure-injection matrix rows.

### Story 10.3 - Search pagination *(3 pts, deps: 10.2)*

As a listener digging deeper, I want more results on demand without losing my place, so
that exploration scales past the first page (FR-2).

**Traces:** FR-2 COMPLETES HERE; UX sentinel + end divider spec. **Tasks:** per-group
Load-more button + infinite-scroll sentinel; dedupe by Source ID on append; end-of-results
divider ("That's everything"); scroll position preserved across loads.

**Acceptance Criteria:**
- **Given** paginated group, **When** next page arrives, **Then** items append without duplicates and scroll offset stays anchored.
- **Given** exhausted source pages, **When** sentinel triggers, **Then** end-divider renders once and no further requests fire.
- **When** rapid repeated load-more taps occur, **Then** no duplicate concurrent requests execute (in-flight guard).

**Tests:** pagination UI tests with fake paged source incl. duplicate-page adversarial case.

### Story 10.4 - Offline/stale search UX *(3 pts, deps: 10.2)*

As Sofia underground, I want my earlier results visible and honestly marked Saved, so that
the app degrades gracefully instead of erroring at me (UJ-5 beat 3).

**Traces:** FR-4 COMPLETES HERE; DR11 copy; banner/badge components from 9.2. **Tasks:**
stale groups render StaleBadge "Saved"; entries remain tappable; play attempts route into
normal Stream Resolution failure paths with honest messaging; reconnect clears banner and
restores online actions automatically.

**Acceptance Criteria:**
- **Given** cached search served offline, **When** rendered, **Then** each affected group shows the Saved badge and content stays tappable.
- **Given** tapping a stale song offline, **When** resolution fails, **Then** user sees the offline explanation (not a raw error), consistent with FR-38 copy.
- **When** connectivity returns, **Then** banner clears and a fresh query succeeds without app restart.

**Tests:** offline-mode UI tests with connectivity toggling; badge/state assertions.

### Story 10.5 - Album detail screen *(5 pts, deps: 10.1)*

As Maya tapping an album result, I see its artwork, credits, and tracklist with Play and
Shuffle, so that listening starts from anywhere I landed (FR-5/UJ-1).

**Traces:** FR-5 COMPLETES HERE; FR-22 trace (context queue via commands); DR7 HeroHeader
incl. sticky compact header [PROVISIONAL]. **Tasks:** hero header (artwork lg, title,
artist link, year-or-clean-omission, track count); numbered SongRow list; Play builds
album-order queue at index 0; Shuffle builds shuffled queue; row tap plays from that index
(album queued at tapped position); quintet states incl. stale/offline rendering per 10.4
rules.

**Acceptance Criteria:**
- **Given** album open, **When** Play pressed, **Then** full album queues in order starting at track 1 and audio starts per FR-8 engine path.
- **Given** tapping track #5, **When** playback starts, **Then** the queue contains all tracks positioned at #5 (FR-22 semantics asserted via PlayerConnection state).
- **Given** missing year, **When** rendered, **Then** the metadata line omits year cleanly (no "null", no dash placeholder).
- **And** artist name navigates to Artist detail preserving back behavior.

**Tests:** Compose UI + ViewModel tests; queue-contract assertions against doubles.

### Story 10.6 - Artist detail screen *(3 pts, deps: 10.1)*

As a listener exploring an artist, I see their image, top songs, and rails when data
exists, so that absent sections never leave empty shells (FR-6 degraded tier under OQ-1).

**Traces:** FR-6 COMPLETES HERE; DR7 ArtistHeader initials-avatar fallback; UX quick
Shuffle-top-songs action [PROVISIONAL placement]. **Tasks:** circular portrait w/
ArtworkRef chain + initials fallback; top-songs list fully playable; albums/singles rails
render only when mapper marked sections available; shuffle-top-songs entry building
shuffled queue of top songs.

**Acceptance Criteria:**
- **Given** transport supplies top songs only, **When** page renders, **Then** rails omit entirely (no empty-section shells) and top songs play/shuffle correctly.
- **Given** portrait load failure, **When** fallback engages, **Then** initials avatar shows with zero layout shift.

**Tests:** Compose tests both availability branches; avatar fallback test.

### Story 10.7 - Catalog Playlist detail screen *(3 pts, deps: 10.1)*

As a listener opening a curated playlist, I see curator, count, and ordered tracks with
Play/Shuffle - and nothing suggests editing, because catalog playlists are read-only by
definition (FR-7/glossary discipline).

**Traces:** FR-7 COMPLETES HERE; identical semantics to 10.5. **Tasks:** HeroHeader
variant with curator line + track count; ordered tracklist; play/shuffle semantics
identical to album; NO edit affordances present anywhere in code path.

**Acceptance Criteria:**
- **Given** catalog playlist open, **When** Play/Shuffle pressed, **Then** ordered/shuffled context queue starts exactly like album semantics.
- **When** code inspection/grep audits run, **Then** zero mutation affordances exist for CatalogPlaylist models.

**Tests:** Compose tests; audit grep in CI checklist notes.

### Story 10.8 - Song context menu *(3 pts, deps: 10.2, 7.1, 8.1, 8.2)*

As Priya hearting songs and queuing them mid-workout, long-press gives me every song
action everywhere lists exist, so that control follows me through the app (FR-24 menu
surface; UJ-3).

**Traces:** FR-24 trace (completion E12 sheet behaviors); FR-30 toggle trace; DR12.
**Tasks:** long-press menu: Play / Play next / Add to queue / Add to playlist (picker) /
Like-Unlike / Go to album (if ref) / Go to artist (if ref) / Share raw catalog URL
[PROVISIONAL]; snackbars confirm adds ("Added to X" / "Playing next"); insertions reflect
immediately in queue state.

**Acceptance Criteria:**
- **Given** menu invoked from any SongRow surface, **When** Play next chosen, **Then** insertion appears directly after current in queue state immediately.
- **Given** Add to playlist, **When** picker confirms, **Then** membership persists instantly with snackbar confirmation.
- **When** Like toggled from menu, **Then** state syncs to Library flow <=250 ms (FR-30 substrate measured).
- **And** Go-to-album/artist entries appear only when refs exist on the model.

**Tests:** menu action UI tests; queue-state immediate-reflection assertions.


## Epic 11 - Library Surfaces & Collection Editing

**Goal:** The owned-data vertical slice: Liked Songs, Play History, the playlist editor,
and the Library hub - all instant-from-DB, fully offline, with honest empty states.

**Scope in:** four screens + create/rename/delete dialogs + picker integration.
**Scope out:** Settings/About screens (E15 fills routes; overflow entries added there).
**Completes:** FR-31 (11.4), FR-32 (11.3), FR-33 (11.4), FR-34 (11.2).
**Depends on:** E8, E9; 11.2 replay uses 7.1 commands.

**Exit criteria:** UJ-3 scripted walkthrough (create -> edit offline -> play next morning)
passes end-to-end; all collection entries start correct-context playback (FR-22 trace
matrix row complete).

### Story 11.1 - Liked Songs screen *(3 pts, deps: 8.1, 9.2)*

As Priya, I want every heart collected in one playable place, so that my week of
discovery becomes a workout asset (UJ-3).

**Traces:** FR-30 collection surface (cross-surface sync completes 12.2); UX section 6.8
incl. rose motif [PROVISIONAL] and reverse-chron ordering [PROVISIONAL]. **Tasks:** hero
(rose-tinted heart, count, Play/Shuffle); reverse-chronological SongRows; empty state
copy "Songs you like will appear here. Tap the heart anywhere."; quintet states
(local data near-instant - no skeletons per DR5 honesty rule).

**Acceptance Criteria:**
- **Given** N liked songs, **When** screen opens, **Then** list renders newest-first instantly from DB with accurate count.
- **Given** empty like set, **When** opened, **Then** helpful empty state displays with guidance copy.
- **When** Play/Shuffle pressed, **Then** Liked Songs queues in its display order / shuffled respectively.

**Tests:** Compose UI incl. empty/content branches; queue-contract assertions.

### Story 11.2 - Play History screen *(3 pts, deps: 8.3, 9.2)*

As Alex replaying Tuesday's discovery, I want a reverse-chronological diary with day
grouping, so that picking up yesterday is one tap (UJ-4 beat 3).

**Traces:** FR-34 COMPLETES HERE (recording verified end-to-end with 8.3 hook); UX-P12
day grouping [PROVISIONAL]. **Tasks:** day-group dividers Today/Yesterday/date;
played-at timestamps tabular; duplicate-recency visible (single entry moves up);
500-cap end divider ("That's as far back as it goes"); tap entry replays song with its
play context queue via commands; empty state "Nothing played yet."; fully offline.

**Acceptance Criteria:**
- **Given** plays across three days, **When** rendered, **Then** groups order reverse-chronologically with correct dividers and timestamps.
- **Given** re-playing an existing entry past 10 s, **When** History refreshes, **Then** that single entry moved to top (no stacking).
- **When** tapping any entry, **Then** playback starts with a context queue built from that moment's surface semantics (FR-22 trace asserted).

**Tests:** Compose UI tests incl. cap divider; recording+replay integration test.

### Story 11.3 - Playlist detail & editor *(5 pts, deps: 8.2, 9.2, 10.8)*

As Priya editing Gym on Sunday night when the network dies, edits keep working because
they are local, so that Monday's queue matches exactly (UJ-3 climax).

**Traces:** FR-32 COMPLETES HERE; UX section 6.7 incl. generated duotone art [PROVISIONAL],
haptic ticks [UX-P8]. **Tasks:** hero w/ generated placeholder art for untinted playlists,
inline rename dialog, Edit mode toggle revealing drag handles + per-row remove X +
Add-songs entry opening AddToPlaylistPicker (multi-select batch from Liked or search);
delete playlist in overflow w/ confirmation copy ("This can't be undone."); every edit
persists immediately; works fully offline; empty-playlist guidance state.

**Acceptance Criteria:**
- **Given** edit mode, **When** drag-reorder/remove/add execute, **Then** DB reflects each change immediately (no save button exists) and survives process kill right after each op.
- **Given** network disabled entirely, **When** any edit performs, **Then** success identical to online (offline-completeness suite).
- **Given** delete confirmation declined vs accepted, **Then** playlist persists vs is removed with its memberships only (songs survive elsewhere).
- **And** rename allows duplicates without clobbering other playlists.

**Tests:** editor UI tests; offline-edit suite; immediate-persistence instrumented checks.

### Story 11.4 - Library hub aggregation *(3 pts, deps: 11.1-11.3)*

As Sofia underground opening Sway, the Library shows everything I own ready to play, so
that the app's offline promise is visible in one glance (FR-33/UJ-5).

**Traces:** FR-31 COMPLETES HERE (Library-side creation dialog completing both creation
paths), FR-33 COMPLETES HERE; UX section 6.3. **Tasks:** Create-playlist affordance +
naming dialog (duplicates allowed, persists immediately); Liked Songs hero tile (count,
play/shuffle); Playlist grid/list cards (art/name/count) routing to editor; Play History
entry row; empty variants prompting first playlist; overflow slot reserved for
Settings/About entries added in 15.2 (documented out-of-scope-here to avoid stub churn
[EP-4]).

**Acceptance Criteria:**
- **Given** fresh install, **When** Library opens, **Then** Liked tile shows 0, playlists area shows creation prompt, History row present.
- **Given** create-dialog flow with name "Gym" twice, **When** confirmed twice, **Then** two independent Gym playlists exist immediately.
- **When** any collection tile opens, **Then** correct-context playback starts (FR-22 matrix completed across hub entries).

**Tests:** Compose UI tests incl. counts/prompts; navigation contracts.

## Epic 12 - Player Surfaces - Mini, Full, Queue Sheet

**Goal:** Playback made visible and touchable everywhere: persistent Mini Player above
the tabs, container-transform Full Player with full transport, manipulable Queue sheet -
completing tap-to-play from EVERY surface and the cross-surface truth guarantee.

**Scope in:** MiniPlayer layer, FullPlayer, QueueSheet, cross-surface wiring matrix,
quality chip presentation.
**Scope out:** extracted-color backdrop (E13 supplies it behind the same API).
**Completes:** FR-8/9/10/11/22/23/24/27/28/30. **Depends on:** E7 (session/modes),
E9 (kit/nav), surfaces from E10/E11 for the wiring matrix; 13.2 later upgrades backdrop.

**Exit criteria:** state-sync latency harness <=250 ms across surfaces; container-transform
<=300 ms measured; scripted full-app tap-to-audio suite <=3 s p95 (SM-1 core-loop rows).

### Story 12.1 - Mini Player global layer *(5 pts, deps: 7.3, 9.3, 4.2)*

As Maya pocketing her phone post-tap, the Mini Player materializes immediately and follows
me across tabs, so that control is always one gesture away (UJ-1 beat 3; P-D6/P-D7).

**Traces:** FR-27 COMPLETES HERE; FR-16 nav-interruption substrate; DR8 anatomy; restored-
paused presence via 9.4 hook. **Tasks:** bar above bottom nav on ALL tabs when session
exists (incl. restored-paused); artwork thumb/title/artist/play-pause/next at 48 dp; 2 dp
progress hairline determinate, pulsing while buffering; failed-track error chip; tap =
expand trigger; swipe-down hides bar only (audio persists) [UX-P9]; state sync <=250 ms
from any origin.

**Acceptance Criteria:**
- **Given** session starts from Search tab, **When** navigating Home/Library, **Then** Mini remains visible with identical state (presence-across-tabs suite).
- **Given** pause toggled from notification while app foregrounded, **When** state emits, **Then** Mini reflects change <=250 ms (latency harness).
- **Given** restored-paused session at launch, **When** first frame settles, **Then** Mini already shows yesterday's track (never auto-plays).
- **When** swipe-down dismisses the bar mid-playback, **Then** audio continues uninterrupted.

**Tests:** Compose UI + latency measurement suite; presence/navigation soak rows.

### Story 12.2 - Full Player *(8 pts, deps: 12.1, 7.1, 7.2, 8.1)*

As Dev on his commute, I want the full stage - big artwork, exact scrubbing, modes, and my
heart one tap away - so that the player feels premium and tells me the truth (UJ-2).

**Traces:** FR-28 COMPLETES HERE (Artwork Surface color upgrade traced to 13.2), FR-9/
FR-10/FR-11 COMPLETES HERE, FR-30 COMPLETES HERE (bidirectional like sync <=250 ms);
DR9; NFR-6 transition gate; A-4 prev semantics surfaced neutrally. **Tasks:**
container-transform expand/collapse (shared artwork element, spring, gesture-interruptible,
<=300 ms hard cap); collapse via chevron/back/swipe-down never losing state; artwork ~92vw
rounded-xl over flat brand backdrop (extraction slots into same API in 13.2); title/
artist/album line + heart pop spring; double-tap-artwork=like [UX-P9]; scrubber thumb-grow
with live time bubble, release applies seek <=500 ms, display +/-1 s; transport cluster
shuffle/prev/play72dp/next/repeat cycling badge "1"; mode toggles persisting via 7.2 keys;
explicit Queue affordance.

**Acceptance Criteria:**
- **Given** Mini tapped, **When** transform completes, **Then** duration <=300 ms p95 measured during animation AND gesture interruption retargets cleanly both directions.
- **Given** scrub to arbitrary position, **When** finger releases, **Then** audible seek <=500 ms and displayed position stays within +/-1 s of audible (instrumented).
- **Given** heart toggled, **When** Library queried, **Then** membership flips <=250 ms; toggling from Library side reflects in player equally (bidirectional proof).
- **Given** repeat pressed until badge "1", **When** track ends, **Then** same track replays indefinitely; shuffle ON reshuffles remainder preserving current (7.1 semantics through UI).
- **And** prev behavior restarts vs jumps-back per >=5 s rule with no visual trickery required.

**Tests:** animation frame metrics; seek timing instrumented; like-sync integration; mode
cycle UI tests.

### Story 12.3 - Queue sheet *(5 pts, deps: 12.1, 12.2, 7.1)*

As a DJ of my own commute, I open the queue, jump anywhere, prune duds, and reorder - so
that the running order is mine (FR-23/24 completion).

**Traces:** FR-23 COMPLETES HERE, FR-24 COMPLETES HERE; DR10; failed-row reason categories
(FR-14 trace). **Tasks:** bottom sheet from explicit affordances on Mini + Full [UX-P9]
plus context-menu entry; Now-playing pinned highlighted row; Next-up rows thumb/handle/X;
tap-row jump <=2 s; remove-playing advances; long-press-drag reorder w/ haptic ticks +
move-up/move-down accessibility alternative [DR14]; Clear w/ confirmation; TalkBack
position announcements "3 of 12"; swipe-down/back/scrim dismissal.

**Acceptance Criteria:**
- **Given** sheet open, **When** row k tapped, **Then** audio switches <=2 s and sheet reflects new current highlight.
- **When** playing row removed, **Then** next item advances audibly and sheet updates atomically.
- **Given** drag-reorder, **When** drop occurs, **Then** order persists for the session and survives subsequent auto-transitions (state integrity).
- **Given** TalkBack on, **When** focus moves rows, **Then** positions announce correctly after mutations.

**Tests:** Compose UI interaction suite; reorder persistence checks; accessibility
announcements tests.

### Story 12.4 - Cross-surface wiring & quality presentation *(3 pts, deps: 10.x, 11.x, 12.1-12.3)*

As any listener starting sound from ANYWHERE - search row, album, artist rails, catalog
playlist, liked, playlist, history, hub, queue - playback must begin identically well, so
that FR-8's "anywhere" is proven rather than assumed.

**Traces:** FR-8 COMPLETES HERE, FR-22 COMPLETES HERE (end-to-end matrix); FR-15
presentation chip (completion was 5.1; OQ-6-gated visibility); optimistic Mini
materialization (UJ-1). **Tasks:** play-entry wiring audit table implemented across all
surfaces; optimistic Mini appearance with placeholder art at tap; quality chip + selector
sheet on Full Player (plain-language descriptions; helper text "applies from next song";
render gated by OQ-6 build flag default-on pending veto [EP-8]); scripted full-app
tap-to-audio instrumented run.

**Acceptance Criteria:**
- **Given** the eight-entry wiring matrix, **When** each entry executes tap-to-play, **Then** context queue correctness asserts per entry and audio <=3 s p95 (scripted suite record feeds SM-1).
- **Given** song tapped in results, **When** resolution still in flight, **Then** Mini appears optimistically within one frame with placeholder artwork.
- **When** quality changed via chip, **Then** selection persists (5.1 path) and helper text communicates application timing honestly.

**Tests:** wiring-matrix instrumented suite; optimistic-render UI test.


## Epic 13 - Artwork System & Visual Atmosphere

**Goal:** Artwork as information and atmosphere: fast bounded caching with zero-layout-
shift fallbacks, then the extraction + scrim engine that lets the player glow while AA
contrast stays mathematically guaranteed.

**Scope in:** Coil ImageLoader config + ArtworkRef chain walker, placeholder system,
color extraction pipeline, scrim engine, ambient crossfades, contrast/a11y audits over
player flows.
**Scope out:** blur of any radius (banned v1, UX-P6); global re-theming (atmosphere stays
player-scoped per UX-P5).
**Completes:** FR-35 (13.1), FR-36 (13.1), NFR-5 (13.2). **Depends on:** 9.2
(placeholders), 12.2 (player surface to theme).

**Exit criteria:** cache-hit renders with zero network I/O (instrumented); forced-failure
layout stability proven; contrast matrix light x dark x bright/dark-artwork all pass;
accessibility scanner clean on core flows.

### Story 13.1 - Image pipeline, caching & placeholder stability *(5 pts, deps: 2.3, 9.2, 3.x parse-time refs)*

As Sofia offline, previously seen covers still render while unseen ones hold layout with
branded placeholders, so that nothing ever shifts, breaks, or blank-stares (FR-35/36/UJ-5
beat 2).

**Traces:** FR-35 COMPLETES HERE, FR-36 COMPLETES HERE; AR-10/AD-11 (memory ~25%,
256 MB LRU disk, canonical-URL keys, candidate-chain walk, auto-retry on connectivity
return); NFR-10 cache bound; DR13. **Tasks:** `:designui` ImageLoader provider on
`coil-network-okhttp` deriving from shared OkHttp builder; chain-walking fetcher honoring
ArtworkRef candidates (zero host logic outside data); identical-bounds placeholders;
connectivity-restored retry trigger; disk-cache sizing config; instrumentation for
cache-hit network assertion.

**Acceptance Criteria:**
- **Given** artwork viewed earlier this session, **When** re-rendered offline, **Then** image loads from cache with ZERO network requests (request-count instrumentation).
- **Given** forced load failure at every chain candidate, **When** exhausted, **Then** branded placeholder renders within identical bounds - layout shift measured zero px.
- **When** connectivity returns, **Then** failed slots retry automatically without user action (retry-trigger test).
- **And** host-specific URL strings appear nowhere outside `:catalog` parse time (grep audit green).

**Tests:** instrumented cache/failure suites; screenshot stability pairs; audit grep.

### Story 13.2 - Extraction, scrim engine & atmosphere *(5 pts, deps: 13.1, 12.2)*

As a listener, my player should breathe with each track's colors yet keep every word
readable over any cover, so that beauty and accessibility arrive together (NFR-5/P-D4/P-D5).

**Traces:** NFR-5 COMPLETES HERE; FR-28 Artwork-Surface promise fulfilled (trace back to
12.2 seam); AR-10 pipeline steps; UX section 9 rules incl. player-scoped scope [UX-P5],
status-bar echo [PROVISIONAL], extraction budget 50 ms [UX-P7], ambient 600 ms crossfade;
SM-C2 guard. **Tasks:** off-main-thread extractor (<=128 px decode; dominant/vibrant
candidates; clamped tonal mapping per light/dark); scrim engine computing vertical
scrim-strong .60 -> scrim-soft .35 until every text region >=4.5:1 (>=3:1 large); results
cached alongside artwork entry; 600 ms ambient crossfades between tracks; neutral-brand
fallback on extraction failure; apply to Full Player backdrop, Mini accents, Queue tint,
status-bar echo; contrast assertion matrix + accessibility scanner + reduced-motion parity
walk on player flows; TalkBack sweep for dynamic announcements.

**Acceptance Criteria:**
- **Given** the bright-artwork and dark-artwork sample matrices under light and dark schemes, **When** scrims compute, **Then** every sampled text/icon region passes WCAG AA thresholds (automated contrast assertions - failures block).
- **Given** extraction perf traces, **When** per-artwork timing runs, **Then** CPU time <=50 ms on Baseline profile and results cache (re-view = zero recompute).
- **Given** track change, **When** backdrop swaps, **Then** 600 ms crossfade with no hard cut and no frame >24 ms during transition (metrics recorded).
- **When** extraction fails/unavailable, **Then** neutral brand scheme applies and contrast guarantees still hold.
- **And** reduced-motion users receive opacity-only equivalents with unchanged content parity.

**Tests:** contrast matrix automation; extraction perf unit w/ traces; Compose transition
metrics; scanner runs recorded.

## Epic 14 - Honesty Pass - Typed States, Offline & System Hardening

**Goal:** The release-blocking guarantees proven, not claimed: every surface audited
against the failure-injection matrix, offline mode end-to-end, continuity/resource soak
suites, performance budgets measured as gates, adaptive compliance matrix.

**Scope in:** FR-37 audit walk, FR-38 completion, NFR-2/4/6/10 completion suites, FR-29
matrix.
**Scope out:** new features; fixes found by audits are in-scope tasks of these stories.
**Completes:** FR-29 (14.5), FR-37 (14.1), FR-38 (14.2), NFR-2 (14.1), NFR-4 (14.3),
NFR-6 (14.4), NFR-10 (14.3). **Depends on:** E10-E13 complete (all surfaces exist).

**Exit criteria:** audit matrix artifact complete with zero blank-screen findings open;
soak/budget suites green on Baseline profile; SM-C2 check recorded.

### Story 14.1 - Surface x failure audit (FR-37/NFR-2) *(5 pts, deps: E10-E13)*

As the owner, I want a documented walk of every data-driven surface against every injected
failure category, so that "no blank screens" is evidence, not slogan (L2/D-03 closure).

**Traces:** FR-37 COMPLETES HERE; NFR-2 COMPLETES HERE (final per-repository injection
audit); AD-9 mapping as oracle. **Tasks:** enumerate surfaces (Search/Home landing/details
x3/Library x4/player states x3/system surfaces); inject each applicable SwayError category
per surface via fakes/seams; assert exactly-one canonical state each cell; fix leaks found;
codify checklist into CI-review docs; verify every repository has full-category injection
tests.

**Acceptance Criteria:**
- **Given** the completed matrix artifact, **When** reviewed, **Then** every surface x category cell records PASS with the rendering state named; zero cells show blank/silent-empty.
- **Given** repository test inventory, **When** audited, **Then** each repository demonstrates injected tests for all applicable categories (NFR-2 verification clause satisfied).
- **And** error copy shown is user-readable category text, never stack traces (spot-check sweep).

**Tests:** the audit itself (parameterized UI tests per cell); artifacts stored under
docs/testing/.

### Story 14.2 - Offline mode end-to-end (FR-38) *(5 pts, deps: 14.1)*

As Sofia riding the tunnel, the whole app must behave offline as designed, so that
nothing blocks, nothing blanks, and recovery needs no restart (UJ-5 completion).

**Traces:** FR-38 COMPLETES HERE; NFR-1 spirit reasserted; DR11 copy verbatim. **Tasks:**
offline launch -> Library functional + banner (works/does-not-work split copy); online-only
actions produce self-explaining messages everywhere (search attempt, stream attempt,
catalog detail fetch); reconnect auto-clears banner restoring actions without restart;
streaming attempts during offline follow resolution failure paths with honest messaging
(not generic errors).

**Acceptance Criteria:**
- **Given** airplane mode before launch, **When** cold start finishes, **Then** Library fully interactive, banner raised once, zero network-blocked frames (extends 9.4 to full-content state).
- **Given** offline, **When** streaming attempted from stale cache entry, **Then** explanation message matches canonical copy; no raw/generic error surfaces anywhere (sweep).
- **When** connectivity restores, **Then** banner clears automatically and online actions succeed without app restart (transition suite).

**Tests:** connectivity-toggle instrumented suite; copy-sweep assertions against string
resources.

### Story 14.3 - Continuity & resource soak suites (NFR-4/NFR-10) *(5 pts, deps: E12/E13)*

As the owner trusting Sway overnight, sessions and storage must survive reality, so that
continuity claims hold under automation (blueprint section 19 criteria).

**Traces:** NFR-4 COMPLETES HERE, NFR-10 COMPLETES HERE (aggregating 4.1 self-stop,
13.1 cache caps, 8.3 trim proofs into soak evidence). **Tasks:** navigation-soak
(random-tab/detail/queue choreography during continuous playback - audio gap detector);
kill-relaunch full-app suite (extends 7.3 with populated likes/playlists/history);
database-intact-after-restart assertions; idle-device service-liveness checks (service
gone after stop); artwork cache-under-cap soak; unbounded-memory-growth watch on catalog
caches.

**Acceptance Criteria:**
- **Given** 30-minute randomized navigation soak during playback, **When** analyzed, **Then** zero app-attributable audio gaps (NFR-4 clause 1 evidence).
- **Given** kill-relaunch with full local data, **When** restored, **Then** session +/-5 s AND database contents intact (likes/playlists/history byte-equivalent semantics).
- **Given** idle device post-stop, **When** checked after grace window, **Then** playback service not running (no zombie).
- **And** artwork disk cache stays <=256 MB under churn soak with LRU eviction observable.

**Tests:** the soak suites themselves; artifacts + trend notes stored.

### Story 14.4 - Performance budget gate (NFR-6) *(5 pts, deps: E12/E13, 9.4 harness)*

As the owner enforcing SM-C2, budgets must be measured during animation on Baseline
profile, so that polish regressions cannot ship unnoticed (AD-13 gates).

**Traces:** NFR-6 COMPLETES HERE; NFR-1 regression re-run; budgets table (architecture
Performance budgets section) as oracle. **Tasks:** macrobenchmark scenarios: list scroll
(search results, library grid), Mini->Full transform, queue-sheet open, track-change
crossfade, cold-start re-measurement; frame metrics p95/jank capture during animation;
budget report artifact; violation triage policy documented (block release).

**Acceptance Criteria:**
- **Given** scroll benchmarks during active content, **When** metrics computed, **Then** p95 frame <=16 ms and >24 ms frames <1% for both lists and player transitions.
- **Given** transform benchmark, **When** measured, **Then** open/close <=300 ms with gesture interruptibility retained.
- **Given** cold-start re-run with real content present, **When** compared to 9.4 baseline, **Then** <=2.5 s p95 holds (regression gate).
- **And** state-sync latency harness reconfirms <=250 ms worst-case origin (budget-table completeness).

**Tests:** macrobenchmark suite execution + stored report; failures block exit.

### Story 14.5 - Adaptive compliance matrix (FR-29) *(3 pts, deps: E10-E12)*

As a listener on a tablet or landscape phone, nothing may be unreachable or truncated, so
that portrait-first never means landscape-broken (FR-29; UX-P11 specifics).

**Traces:** FR-29 COMPLETES HERE; implementations traced: 9.3 (rail variant groundwork),
12.2 (side-by-side player geometry), grids/columns across screens. **Tasks:** implement
600 dp adjustments (max-width 640 centered OR 2-col grids; second metadata column on
rows) where absent; 840 dp: nav rail replacing bottom bar, Full Player side-by-side,
queue side panel; smoke matrix across phone/tablet sizes x orientations asserting full
functionality reachability.

**Acceptance Criteria:**
- **Given** the size/orientation smoke matrix (compact phone / 600 dp tablet / 840 dp tablet, portrait+landscape), **When** walked, **Then** every destination reachable, no truncated controls, transport always accessible.
- **Given** 840 dp device, **When** Full Player opens, **Then** side-by-side layout renders with equivalent function to portrait.
- **When** bottom-navigation-vs-rail swap occurs at width threshold, **Then** state (tab selection, mini visibility) carries across configuration changes.

**Tests:** device-size UI test matrix (Robolectric qualifiers + device farm note);
screenshot pairs.


## Epic 15 - Settings, About & Release Readiness

**Goal:** The honest supporting surfaces and the release gate: theme settings that apply
instantly, About with complete license attribution (legal obligation), privacy traffic
audit, SM evidence records, and the owner handoff packet.

**Scope in:** Settings screen, About/licenses screen, Library overflow wiring, release
checklist execution.
**Scope out:** any telemetry (banned); feature work.
**Completes:** FR-39 (15.1), FR-40 (15.2), NFR-9 (15.3). **Depends on:** E9 routes,
5.1 settings keys, E14 suite records.

**Exit criteria:** licenses screen lists every shipped dependency; traffic inspection
clean; SM-1/SM-2 records filed; dogfood handoff checklist signed off by proxy; OQ-7
reminder surfaced to owner.

### Story 15.1 - Settings screen *(3 pts, deps: 9.3 routes, 5.1)*

As a listener preferring dark evenings, I set theme once and Sway obeys everywhere
instantly, so that the app feels mine (FR-39).

**Traces:** FR-39 COMPLETES HERE; UX section 6.10; quality selector visibility gated by
OQ-6 flag [EP-8]; DR4 theme-switch fade rule. **Tasks:** appearance radio group
System/Light/Dark persisted via SettingsRepository applying immediately app-wide (150 ms
fade max, no full-screen animation); audio-quality entry rendering the same selector
sheet as 12.4 when flag active; version pointer row -> About; reachable from Library
overflow per FR-26.

**Acceptance Criteria:**
- **Given** theme switched to Dark, **When** selection made, **Then** whole app updates immediately and persists across restart without any restart prompt.
- **Given** System mode, **When** OS toggles dark, **Then** app follows live.
- **When** OQ-6 flag disabled (veto), **Then** no quality entry renders anywhere (chip 12.4 + settings both hidden) with zero dead references.

**Tests:** theme persistence UI tests; flag-gating build-variant checks.

### Story 15.2 - About & licenses *(3 pts, deps: 15.1)*

As a user (and the law), I can see version info and every dependency's license, so that
attribution is complete and trust is visible (FR-40 legal obligation; B.1).

**Traces:** FR-40 COMPLETES HERE; AD-4 attribution clause; UX section 6.11. **Tasks:**
brand block (wordmark, tagline "Your music, in flow."), version row (from build config),
licenses list generated from the dependency graph (Gradle license plugin [PROVISIONAL
tool choice EP-6], expandable per-package); Library overflow entries for Settings + About
wired now completing the <=2-taps rule end-to-end.

**Acceptance Criteria:**
- **Given** Library overflow, **When** opened, **Then** Settings and About entries exist and each destination lands <=2 taps from Library root.
- **Given** shipped dependency list at build time, **When** licenses render, **Then** every runtime dependency appears with its license text/notice (spot-audit vs lockfile).
- **And** no distribution claims appear anywhere in copy (AD-4 sweep line-item).

**Tests:** license-completeness check task in CI; navigation reachability rows.

### Story 15.3 - Release readiness gate *(5 pts, deps: 14.x records, 15.2)*

As Hemant preparing daily-driver dogfooding, I want one gate proving the promises before
SM-3 starts, so that v1 ships honest or does not ship (PRD section 7 posture).

**Traces:** NFR-9 COMPLETES HERE (traffic inspection + egress audit); SM-1/SM-2 record
compilation; P-4 no-telemetry verification; AD-4 posture re-review; OQ-5/OQ-6 veto-window
reminder; OQ-7 trademark/store-collision check surfaced as OWNER ACTION (release gate
follow-up per PRD). **Tasks:** debug-build network-traffic inspection (endpoints limited
to catalog/stream/artwork hosts); dependency scan proving zero analytics/crash SDKs;
compile SM evidence pack (14.4 budget report, 5.3 expiry suite, 12.4 tap-to-audio matrix,
14.3 soak artifacts) into docs/testing/release-evidence.md; personal-use framing final doc
sweep; provisional registers consolidated for owner review; dogfood install checklist.

**Acceptance Criteria:**
- **Given** instrumented traffic capture across scripted flows, **When** inspected, **Then** egress hosts are exclusively catalog/stream/artwork domains (NFR-9 verification clause).
- **Given** dependency tree audit, **When** scanned, **Then** zero analytics/crash-reporting artifacts resolve in any variant.
- **Given** the evidence pack, **When** reviewed, **Then** SM-1 core-loop rows and SM-2 forced-expiry results are present with pass status and dated artifacts.
- **And** a one-page owner-veto brief lists every open PROVISIONAL item incl. OQ-7 action.

**Tests:** the audits themselves; checklist artifact committed.

---

## Constraint Trace (C-1..C-8)

| Constraint | Governing stories |
|---|---|
| C-1 one stack each | 1.1 pins; 1.3 audits; 3.1 downloader-on-OkHttp; 7.3 Room-only |
| C-2 typed errors day one | 2.2 taxonomy; 10.1 pattern; 14.1 audit |
| C-3 service/controller skeleton | 4.1 service; 4.2 facade |
| C-4 lazy resolution | 4.3 placeholders; 4.4 exactly-one proof |
| C-5 layered expiry defense | 5.2 layer1; 5.3 layer2; 5.4 layer3; dedup in 3.6 |
| C-6 bitrate-target formats | 3.6 selection; 5.1 preference mapping |
| C-7 facade + sub-services, LOC | 1.3 lint; reviewed every epic via AC file scoping |
| C-8 offline fallback caches | 8.4 store; 10.1 integration |

## Journey Coverage (UJ-1..UJ-5)

| Journey | Realizing stories (completion spine bolded) |
|---|---|
| UJ-1 Maya cafe song | 9.4 launch, 9.5 landing, **10.2 search**, **12.4 tap-to-play**, **12.1 mini**, 14.1 empty-state edge |
| UJ-2 Dev commute | **6.1 background/notification/lock**, **6.2 focus+route**, 12.2 controls parity, 5.2-5.4 tunnel resilience |
| UJ-3 Priya playlist | 10.8 heart/menu, **11.3 editor offline**, **11.4 hub create**, 12.3 queue order, 8.2 durability |
| UJ-4 Alex overnight | **7.3 restore**, **11.2 history replay**, 12.1 restored-paused mini, 14.3 soak proof |
| UJ-5 Sofia subway | **14.2 offline e2e**, 10.4 stale search, 13.1 cached artwork, 9.4 offline launch |

## Success-Metric Evidence Map

| Metric | Producing stories |
|---|---|
| SM-1 core-loop suite | 12.4 matrix + 14.3 soak + 14.4 budgets compiled in 15.3 |
| SM-2 forced-expiry | 5.3 suite (+5.4 stall ladder); records compiled in 15.3 |
| SM-3 dogfood readiness | 15.3 handoff checklist; friction-log template included |

## Provisional Register (owner veto list)

Headless decisions made by this workflow, in addition to all upstream registers (PRD
P-1..P-5; UX UX-P1..P12; architecture AD-4 PROVISIONAL):

| # | Decision | Where | Veto via |
|---|---|---|---|
| EP-1 | Epic granularity follows builder-layer build order with two enabler epics (E1-E2) plus thick vertical slices (E3-E8), not pure user-facing slices - caller's build-order directive applied to architecture's module law | Epic List | Restructure note |
| EP-2 | FR completion points assigned for exactly-one-epic discipline: FR-8/22 complete E12 end-to-end (engine proofs trace from 4.4/7.1); FR-25 completes E7 engine-side; FR-14 completes E5 system-side (UI markers trace); FR-15 completes E5 (chip traces 12.4); FR-30 completes E12 (collection surface traces 11.1) | Coverage maps | Reassign in map |
| EP-3 | Room schema introduced incrementally: migration 1 = QueueStateEntity (7.3); likes/playlists/history arrive as migrations 2-4 in E8 - honors create-when-needed; alternative front-loaded schema rejected | Stories 7.3, 8.1-8.3 | Front-load if preferred |
| EP-4 | Library overflow entries for Settings/About wired in 15.2 rather than stubbed earlier (avoids throwaway screens; FR-26 completion unaffected since reachability completes with destinations) | Story 11.4 note | Stub earlier if wanted |
| EP-5 | Error-mapping details: oversized response body -> UpstreamUnavailable; malformed -> Parse; blank-id items dropped at parse with logged shape | Stories 2.1, 3.2 | Align to taste |
| EP-6 | License listing via Gradle license-plugin generation (tool choice open; curated fallback acceptable) | Story 15.2 | Pick tool |
| EP-7 | Point scale = Fibonacci mapped to agent-session sizing (2-3/5/8) | Overview | Rescale |
| EP-8 | OQ-5/OQ-6 handled behaviorally: recents-swipe continuation implemented per P-3; quality chip/settings ship behind an OQ-6 build flag defaulting ON pending owner veto | Stories 6.3, 12.4, 15.1 | Flip flags at veto |

## Final Validation Record (Step 4)

- FR coverage: 40/40 mapped, each to exactly one completing story (FR Coverage Map);
  zero orphans. NFR coverage: 10/10, exactly one completing story each.
- Starter template: architecture specifies none; Epic 1 builds the substrate from scratch
  (documented per Step 2 rules).
- Entity creation principle: tables born only when first needed (QueueState 7.3; likes
  8.1; playlists 8.2; history 8.3) with explicit tested migrations per AR-7.
- Forward-dependency check: none - within-epic ordering validated (notably E11 orders
  collection screens before the aggregating hub 11.4; E12 wires surfaces after they
  exist). Cross-epic dependencies point strictly backward (dependency table).
- File-churn assessment: SongRow/theme touched across kit + feature epics is incidental
  sharing of stable components (variants added, never rewritten); consolidation considered
  and rejected - component stability makes churn non-meaningful.
- Sequencing laws: typed errors present from 2.2 onward before any consumer; main-thread
  law enforced from 1.2/9.4; no section-5 non-goal appears in any task; NewPipe-only
  behind ports enforced by 1.3 CI gates.
- Workflow completion: stepsCompleted 01-04 recorded in frontmatter; headless [C] assumed
  at all menu gates; bmad-help invocation skipped as not applicable headless (caller
  directive governs next step: bmad-sprint-planning consumes this file).

*End of epics-and-stories.md.*

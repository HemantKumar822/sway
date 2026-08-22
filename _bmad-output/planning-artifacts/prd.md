---
title: Sway - Independent Android Music Client PRD
status: final
created: 2026-08-23
updated: 2026-08-23
project: Player
owner: Hemant
name_provisional: false
---

# PRD: Sway

**Sway** — chosen by the owner; to sway is to move with the rhythm. Simple, one syllable, and the exact feeling of buttery-smooth playback. Tagline: *"Your music, in flow."* Trademark/Play Store final collision check remains a release-gate follow-up (OQ-7; note: Microsoft's retired Office Sway exists — category-distant but must be verified). Naming history: `docs/research/naming-study.md`; owner directives: `docs/decisions/0003-design-direction.md`, `docs/decisions/0005-name-update-sway.md`.

## 0. Document Purpose

This PRD defines version 1 of **Sway**, an independent Android music client that uses the YouTube Music catalogue as its discovery and playback source while owning its own data layer, playback experience, visual language, and local library. It is written for the project owner (Hemant) and serves as the **chain-top contract** for the downstream BMad pipeline: UX (`bmad-ux`) → Architecture (`bmad-architecture`) → Epics/Stories (`bmad-create-epics-and-stories`). All requirements carry stable IDs (FR-x / NFR-x / SM-x / UJ-x / OQ-x) intended for source-extraction by those workflows. Feature groups in §4 are deliberately sized as candidate epic boundaries (groupings only — epics and stories are NOT defined here).

**Sources this PRD builds on (extracted from, not duplicated):**

- `docs/suvmusic-research-blueprint.md` — product goal (§2), scope/non-goals (§3), UI map (§12), questions (§16)
- `docs/research/sprint-R1-summary.md` — verified reference behavior, design lessons L1–L8, open questions Q-A…Q-E
- `docs/decisions/0001-blueprint-corrections.md` — decisions D-01…D-03 and corrected facts
- `docs/decisions/0003-design-direction.md` — visual identity override: music-first brand, Material 3 Expressive foundation, Apple/iOS-grade smoothness
- `docs/decisions/0005-name-update-sway.md` — name updated to Sway (owner)

Every decision made here without direct owner confirmation is tagged **[PROVISIONAL]** inline and indexed so it can be vetoed cheaply. Vocabulary is anchored in §3 Glossary; introducing synonyms elsewhere is a discipline violation.

## 1. Vision

Sway is a polished, independent Android music player for people who want the reach of the YouTube Music catalogue without the accounts, clutter, or lock-in of a big-platform app. It pairs a focused catalogue experience — search, browse, album/artist/playlist exploration — with a first-class interface built on **Material 3 Expressive**, customized with our own tokens: artwork drives atmosphere through smooth surfaces, color extraction, and fluid, restrained motion benchmarked against iOS-grade polish. The app feels premium and calm, prioritizing readability and restraint over decoration.

The product bet is on **trustworthy fundamentals**: playback that survives real-world failure (expired stream URLs, dead networks, process death), interfaces that always render an honest state instead of a blank screen, and personal data — likes, playlists, history — that lives on the device and belongs to the user. Where the studied reference implementation is documented to fail (swallowed errors producing blank screens, band-aid retries over ephemeral stream URLs, parallel half-migrated tech stacks), Sway treats the opposite behavior as a binding requirement, not an aspiration (§7).

Version 1 is deliberately narrow: online-only playback, no login, local-only playlists, English UI. Everything beyond that core — downloads, lyrics, social, cloud sync, alternative audio sources — is explicitly out (§5) and must not complicate the first architecture.

## 2. Target User

### 2.1 Jobs To Be Done

- **Functional:** Find and play any song from the YouTube Music catalogue quickly, without signing in or tolerating upsells; keep my own collections (likes, playlists, history) on my device.
- **Contextual:** Listen reliably during commutes and workouts — screen off, headphones/Bluetooth connected/disconnected, network flaky — with playback controls always reachable.
- **Emotional:** Feel ownership and pride in a beautiful, distinctive player that never leaves me staring at a blank screen or silently losing my place.
- **Builder (secondary):** As the owner-developer's daily driver, prove each architectural layer (catalog → resolution → playback → persistence → UI) before the next is added.

### 2.2 Non-Users (v1)

Single user, single device: v1 assumes one person on one phone with no multi-device or sync expectations [ASSUMPTION A-2].

- Users needing offline/downloaded-audio playback (v1 is online-only).
- Users wanting cross-device sync or account-linked libraries (no login exists).
- Audiophiles seeking HQ-audio sources, equalizers, or spatial audio (non-goals, §5).
- Cast / Android Auto / widget users; lyric followers; social-listening users.

### 2.3 Key User Journeys

Named-persona narratives. FRs reference these by ID inline.

- **UJ-1. Maya plays the song she just heard at a café.** Maya, a grad student who shazams songs constantly, opens Sway from her home screen. No login greets her — Search lands first (v1 transport scope, OQ-1), with the Home feed deepening once catalog transport expands. She types three words of the chorus into Search; song, album, artist, and playlist result groups appear within seconds. She taps the top Song hit; the Mini Player appears and audio starts before she pockets the phone. *Edge case:* she mistypes ("chandelier sett"); results come back empty with a typed Empty state and a clear "check spelling" hint — never a blank screen. Realizes FR-1, FR-2, FR-8, FR-26, FR-27, FR-37.
- **UJ-2. Dev's commute survives his pocket and his phone call.** Dev starts an album while on Wi-Fi, locks the phone, and heads out. Playback continues through the tunnel handoff; lock-screen controls let him skip tracks without unlocking. A call comes in — audio pauses; when the call ends, he resumes from the notification. His Bluetooth headphones power off mid-song: playback pauses instantly instead of blasting from the speaker. Realizes FR-9, FR-10, FR-16, FR-17, FR-18, FR-19, FR-20.
- **UJ-3. Priya builds a workout playlist across three sessions.** Priya likes songs as she discovers them during the week (heart tap from Full Player). On Sunday she creates a Playlist named "Gym" in Library, drags liked Songs into it, reorders by tempo-feel, removes two duds, and renames it. Next morning she hits play on the playlist; it queues exactly as arranged. *Edge case:* mid-edit the network dies — editing keeps working because Playlists are local; only streaming attempts show offline messaging. Realizes FR-30, FR-31, FR-32, FR-33, FR-38.
- **UJ-4. Alex picks up yesterday exactly where it stopped.** Alex fell asleep to a long mix; Android killed the process overnight. Opening Sway restores the Queue, current track, and last position; one tap on resume continues within seconds. He then opens Play History to replay Tuesday's discovery. Realizes FR-25, FR-34, NFR-4.
- **UJ-5. Sofia rides the subway with no signal.** Sofia opens Sway underground. A clear offline banner explains what works (Library, Liked Songs, Playlists, History, cached artwork) and what does not (search, streaming). She plays a local Playlist from cache-free local data; artwork renders from cache where present, placeholders elsewhere. Nothing crashes, nothing blanks. Realizes FR-4, FR-36, FR-37, FR-38.

## 3. Glossary

Downstream artifacts must use these terms exactly, verbatim, everywhere.

- **Catalog** — the YouTube Music metadata corpus (Songs, Albums, Artists, Catalog Playlists) accessed through our own extraction boundary. Never exposed raw to the UI.
- **Song** — a single playable Catalog item with a stable **Source ID**, title, artist name/ID, album name/ID, duration, and Artwork variants.
- **Source ID** — stable, unique identifier for a Catalog item within our models; the key used for Stream Resolution and local persistence.
- **Album / Artist** — Catalog containers of Songs; browsable detail destinations.
- **Catalog Playlist** — a read-only playlist entity from the Catalog (e.g., editorial/mood playlists). Distinct from **Playlist**.
- **Playlist** — a user-created, local-only, ordered collection of Songs stored on-device. One Song may belong to many Playlists.
- **Liked Songs** — the persistent local collection of Songs the user has liked (hearted).
- **Play History** — automatically recorded, reverse-chronological list of played Songs, stored locally.
- **Library** — the local hub surface containing Liked Songs, Playlists, and Play History.
- **Home Feed** — the launch tab presenting browsable Catalog content (conditional on OQ-1 transport capability).
- **Stream URL** — a short-lived resolved playable URL derived from a Source ID. Expires; treated as temporary; never persisted long-term.
- **Stream Resolution** — the act of obtaining a fresh Stream URL for a Source ID.
- **Queue** — the ordered list of Songs scheduled for continuous playback, with a current index; built from the context of the user's play action.
- **Playback Session** — the live playback state: Queue + current position + shuffle/repeat modes; restorable after process death.
- **Mini Player** — compact persistent playback bar above bottom navigation whenever a Playback Session exists.
- **Full Player** — expanded playback screen with large artwork, Artwork Surface treatment, and full controls.
- **Artwork Surface** — a surface rendered over color-extracted artwork background per the Sway visual system (M3 Expressive foundation; smooth surfaces and fluid motion over transparency gimmicks); must preserve readable contrast.
- **Typed Error State** — an explicit, categorized failure state (loading / empty / error+retry) surfaced by every data-driven surface; blank screens are forbidden.
- **Offline Fallback Cache** — short-lived local cache of recent Catalog results served when the network fails, visibly marked stale.
- **Baseline Device** — the performance reference: a ~2023 mid-range Android phone, 4 GB RAM class, Android 8.0+ (minSdk 26) [ASSUMPTION A-3].

## 4. Features

Priorities use MoSCoW. Every FR lists testable consequences. Feature groups are candidate epic boundaries.

### 4.1 Discovery & Search

**Description:** How users find Catalog content. Search covers all four content types; the Home Feed gives a browsable landing experience. Offline Fallback Cache keeps recent discoveries visible during network failure. Realizes UJ-1, UJ-5.

#### FR-1: Keyword search across content types *(Must)*
User can enter a free-text query and receive grouped results for **Songs, Albums, Artists, and Catalog Playlists**, filterable by type.
**Consequences (testable):**
- Results render grouped and labeled by type within 3 s p95 on Baseline Device over a 10 Mbps connection.
- Each group is independently scrollable/filterable; tapping a result routes to its detail destination (FR-5..7) or starts playback (Song, FR-8).
- A query with zero matches produces the typed Empty state (distinct from error), with recovery guidance.
- A failing search request produces Typed Error State with retry; never a blank screen.

#### FR-2: Search pagination *(Should)*
User can load additional results for any result group on demand or via infinite scroll.
**Consequences:** subsequent pages append without duplicating items or resetting scroll; end-of-results is indicated.

#### FR-3: Home Feed *(Should — conditional)*
User sees browsable Catalog content (e.g., charts/moods/new releases as available) on the Home tab at launch.
**Consequences:** Feed populates without user input; pull-to-refresh updates content.
**Out of Scope / dependency:** depth depends on OQ-1 (transport strategy). If NewPipe-only transport cannot supply feed data, v1 ships Home as Search-first landing page and this FR degrades to that minimum. Gated decision recorded in OQ-1.

#### FR-4: Offline Fallback Cache with staleness marking *(Should)*
When search/browse requests fail due to network loss, previously fetched results are served from the Offline Fallback Cache and visibly marked stale (L8).
**Consequences:**
- Stale content displays an unambiguous indicator (banner/badge).
- Cache entries expire (short TTL; architecture sets value); expired entries do not masquerade as fresh.
- Cached results remain tappable; playing from them follows normal Stream Resolution including its failure paths.

### 4.2 Catalog Detail Views

**Description:** Deep views for Album, Artist, and Catalog Playlist with play entry points. Realizes UJ-1.

#### FR-5: Album detail *(Must)*
User can view an Album's title, artist, year (when available), tracklist, and artwork, and can start playback of the whole album or shuffle it.
**Consequences:** every listed track is playable (FR-8); "play all" builds a Queue in album order (FR-22); missing optional metadata renders gracefully, not broken.

#### FR-6: Artist detail *(Must core / Should extended)*
User can view an Artist page with name, image, top Songs (Must), plus their Albums/Singles listings where the transport provides them (Should).
**Consequences:** top-Songs section fully playable; album/singles entries route to FR-5; sections absent from the source omit cleanly.

#### FR-7: Catalog Playlist detail *(Must)*
User can view a Catalog Playlist's title, owner/curator, track count, artwork, and ordered tracklist, and play or shuffle it.
**Consequences:** identical play/shuffle semantics as FR-5; Catalog Playlists remain read-only locally (adding to them is impossible — they are not local Playlists).

### 4.3 Playback Core

**Description:** The engine behaviors that make listening trustworthy: instant start, full transport control, lazy resolution, and defense against ephemeral Stream URLs. Realizes UJ-1, UJ-2, UJ-4.

#### FR-8: Tap-to-play anywhere *(Must)*
User can start playback of any playable item from any surface (search results, details, Library, history, queue).
**Consequences:**
- Audio output begins ≤ 3 s p95 from tap over 10 Mbps on Baseline Device.
- Starting playback always constructs a Queue from the play context (FR-22) — never a lone track with undefined successors.

#### FR-9: Pause / resume / seek *(Must)*
User can pause, resume, and scrub within the playing track from Mini Player, Full Player, notification, and lock screen, with a visible elapsed/total position.
**Consequences:** position display stays within ±1 s of audible position; scrubbing updates audio within 500 ms of release.

#### FR-10: Next / previous *(Must)*
User can skip to the next Queue item and return to the previous one from all control surfaces.
**Consequences:** previous restarts the current track if ≥ 5 s have been played, else jumps back [ASSUMPTION A-4]; next at Queue end respects repeat mode (FR-11).

#### FR-11: Shuffle and repeat modes *(Must)*
User can toggle shuffle (on/off) and repeat modes (off / repeat-all / repeat-one); the last-used modes persist across app launches.
**Consequences:** toggling shuffle preserves the current track and reshuffles remaining order deterministically per session; repeat-one replays the same track indefinitely; modes persist and restore with the Playback Session (FR-25).

#### FR-12: Lazy stream resolution *(Must)*
The system resolves a Stream URL only for the currently playing track; the rest of the Queue holds Source-ID placeholders re-resolved at transition time (L4).
**Consequences:** starting an N-track queue performs exactly one Stream Resolution up front (verified by instrumentation/test double); transitions resolve just-in-time without blocking UI.

#### FR-13: Expired-stream renewal with position resume *(Must)*
On stream failure indicating expiry (HTTP 403/410), the system purges the stale Stream URL, re-resolves fresh, and resumes at the last audible position (L5).
**Consequences:**
- Resume lands within ±3 s of the lost position in forced-expiry tests (SM-2).
- Renewal uses the shared resolver path with in-flight deduplication — concurrent failures never trigger duplicate resolves for the same Source ID.
- Cached Stream URLs are validated at read time (expiry margin −5 min) and discarded rather than attempted [initial targets; architecture may tune with evidence].

#### FR-14: Stalled-playback watchdog *(Must)*
A watchdog detects stalled buffering and escalates recovery: brief stall (> 3 s) triggers retry/downscale replay; sustained stall (> 15 s) triggers full Stream rebuild; repeated rebuild failure skips to next Queue item and surfaces a Typed Error State for the failed Song (L5 thresholds adopted as initial targets).
**Consequences:** forced-stall tests recover or escalate within stated bounds; the user always sees which track failed and why (category, not stack trace).

#### FR-15: Audio-quality preference *(Should)* [PROVISIONAL]
User can choose audio quality — AUTO (default, network-aware) / LOW / MEDIUM / HIGH — implemented as bitrate targets, not format bookkeeping (L6).
**Consequences:** selection persists; AUTO adapts to connectivity class; changes apply from next resolution onward.
**Note:** Not in the owner's original functional list; derived from lesson L6. Veto welcome (OQ-6).

### 4.4 Background Playback & System Integration

**Description:** Playback as a first-class Android citizen: continues in background, controllable from notification/lock screen, polite with audio focus and device routing. Realizes UJ-2.

#### FR-16: Background playback *(Must)*
Audio continues uninterrupted when the app is backgrounded, the screen turns off, or the user navigates anywhere inside the app.
**Consequences:** instrumented test plays 10 min with app backgrounded/screen off with zero audio gaps attributable to the app; navigation between all tabs/screens never interrupts audio (NFR-4).

#### FR-17: Media notification *(Must)*
While a Playback Session is active, a media-style notification exposes play/pause, next, previous, current-track metadata, and Artwork.
**Consequences:** controls function identically to in-app equivalents; notification dismisses when paused; dismissing while playing stops playback per platform default behavior [ASSUMPTION A-10].

#### FR-18: Lock-screen controls *(Must)*
Lock screen shows the media session with artwork and transport controls.
**Consequences:** session metadata (title/artist/artwork/duration) matches the playing track; controls mirror FR-9/FR-10 semantics.

#### FR-19: Audio focus compliance *(Must)*
Playback obeys Android audio focus: phone calls pause playback; transient focus losses pause (or duck, per policy fixed in architecture); focus regained resumes only where policy allows.
**Consequences:** automated focus-loss/gain scenarios pass; no overlap with other apps' audio except permitted ducking.

#### FR-20: Device-route changes *(Must)*
Disconnecting the active audio route (wired headphones or Bluetooth) pauses playback immediately; reconnecting never auto-resumes.
**Consequences:** BT/wired disconnect tests show pause < 1 s; speaker blast-out scenario impossible.

#### FR-21: Removal from recents *(Should)* [PROVISIONAL]
Removing the app from Recents does not stop active playback; the user stops it from the notification.
**Consequences:** playback continues post-swipe; notification remains the stop affordance.
**Note:** deliberate choice mirroring mainstream music-app behavior; alternative (stop-on-swipe) rejected as surprising for music. Confirm in OQ-5.

### 4.5 Queue Management

**Description:** Transparent, manipulable playback order. Realizes UJ-3, UJ-4.

#### FR-22: Context-built Queue *(Must)*
Any play action (song tap, album play, playlist play, shuffle entry) builds a Queue reflecting its context and starts at the chosen item.
**Consequences:** playing track #5 of an album queues the full album positioned at #5; shuffle entry points build a shuffled Queue (FR-11).

#### FR-23: View, jump, remove *(Must)*
User can open the Queue surface from Mini Player or Full Player, see the upcoming order with the current track highlighted, jump to any item, and remove items.
**Consequences:** jump switches audio within 2 s; removing the playing track advances to the next item; removals persist within the session.

#### FR-24: Queue enrichment *(Should)*
From any Song's context menu the user can "play next" (insert directly after current) and "add to Queue" (append); drag-reorder items; clear the Queue.
**Consequences:** insertions appear immediately in correct positions; reorder persists for the session.

#### FR-25: Playback Session persistence *(Must)*
Queue, current index, position (±5 s), and shuffle/repeat flags survive process death and app relaunch (blueprint Phase 6).
**Consequences:** kill-and-relaunch test restores the exact Playback Session; restoration never auto-starts audio — it waits for explicit resume (predictability).

### 4.6 Navigation Shell & Player Surfaces

**Description:** The structural UI skeleton and global playback surfaces in the Sway visual language (M3 Expressive + artwork-driven color). Realizes all journeys.

#### FR-26: Navigation shell *(Must)*
Bottom navigation exposes **Home, Search, Library**; detail destinations (**Album, Artist, Playlist**) push over tabs; **Settings/About** are reachable from Library.
**Consequences:** all destinations reachable ≤ 2 taps from launch; back-stack behaves predictably (tab state preserved when entering/leaving details).

#### FR-27: Mini Player *(Must)*
Whenever a Playback Session exists, the Mini Player persists above bottom navigation on every tab, showing Artwork, title/artist, play/pause, and next.
**Consequences:** visible on all top-level tabs simultaneously; tap expands to Full Player; collapse returns without losing state; it reflects state changes (pause/skip) within 250 ms regardless of originating surface.

#### FR-28: Full Player *(Must)*
Full Player presents large Artwork on an Artwork Surface, full transport controls, position bar, like toggle, and Queue access.
**Consequences:** all FR-9/10/11 controls present; like state syncs bidirectionally with Library (FR-30); open/close transition meets NFR-6 frame budget.

#### FR-29: Adaptive layout *(Should)*
Layouts adapt to larger screens/landscape: no unreachable or truncated controls at 600 dp+ widths; portrait remains the primary design target.
**Consequences:** smoke matrix across phone/tablet sizes/landscape shows full functionality; UX phase owns specifics.

### 4.7 Library & Local Persistence

**Description:** The user's owned data: likes, local Playlists, Play History — durable, fast, fully usable offline. Realizes UJ-3, UJ-4, UJ-5.

#### FR-30: Like/save Songs *(Must)*
User can like/unlike any Song from Full Player, search results, or detail lists; Liked Songs lives in Library.
**Consequences:** like state is consistent across all surfaces within 250 ms of toggle; Liked Songs is playable/shuffleable; likes survive restarts and process death.

#### FR-31: Create local Playlist *(Must)*
User can create a named Playlist, initially empty, from Library or from any Song context menu ("save to playlist").
**Consequences:** duplicate names allowed; creation persists immediately; empty Playlists display a helpful empty state.

#### FR-32: Edit local Playlist *(Must)*
User can add/remove Songs, reorder, rename, and delete Playlists.
**Consequences:** edits persist immediately (no save button required); one Song may sit in multiple Playlists concurrently; deletion asks confirmation; edits work fully offline.

#### FR-33: Library hub *(Must)*
Library surfaces Liked Songs, all user Playlists, and Play History entry, each directly playable.
**Consequences:** counts/tile metadata accurate; opening any collection starts correct-context playback (FR-22).

#### FR-34: Play History *(Must)*
Played Songs are auto-recorded into reverse-chronological Play History; tapping an entry replays it.
**Consequences:** entry recorded once a track passes 10 s of playback [ASSUMPTION A-5]; duplicates update recency rather than stacking; history capped at the most recent 500 entries; fully available offline.

### 4.8 Artwork System

**Description:** Artwork as both information and atmosphere — loaded fast, cached hard, failing soft, and feeding the Artwork Surface color pipeline.

#### FR-35: Artwork loading & caching *(Must)*
Artwork loads through the image-loading layer with memory + disk caching; repeated views within a session never re-download unchanged images.
**Consequences:** cache-hit renders without network I/O (instrumented); offline viewing shows cached art where previously viewed; disk cache bounded per NFR-10.

#### FR-36: Artwork fallback *(Must)*
Failed/missing artwork yields a neutral branded placeholder that keeps layout stable; no broken-image states; loads retry automatically when connectivity returns.
**Consequences:** forced-failure tests never show layout shift > placeholder bounds; recovery is automatic without user action.

### 4.9 Errors, Offline & Resilience

**Description:** Honest states everywhere — the anti-blank-screen guarantee. Realizes UJ-5.

#### FR-37: Typed Error States on every surface *(Must)*
Every data-driven surface (Search, Home Feed, details, Library feeds, players) renders exactly one of: loading, content, empty, or error-with-retry (L2, D-03).
**Consequences:** audit walks every surface against a failure-injection matrix; a blank screen or silent empty-list-on-failure is a release blocker; error categories are user-readable ("You're offline", "Couldn't load — tap to retry").

#### FR-38: Offline mode *(Must)*
With no connectivity, the app opens normally into Library (fully functional), with an explicit offline banner; online-only actions explain themselves instead of erroring raw.
**Consequences:** offline launch never blocks on network (ties NFR-1); streaming attempts produce the offline explanation, not generic errors; reconnect restores online actions without app restart.

### 4.10 Settings & About

**Description:** Minimal, honest supporting surfaces.

#### FR-39: Settings *(Must-minimal)*
Settings offers appearance theme (System/Light/Dark) persisted via preferences storage; plus audio-quality selector if FR-15 survives veto.
**Consequences:** theme applies immediately app-wide and persists; settings changes require no restart.

#### FR-40: About & licenses *(Must)*
About shows version info and third-party/open-source license attributions.
**Consequences:** every shipped dependency's license is listed (legal obligation, §7.1); reachable in ≤ 2 taps from Library.

## 5. Non-Goals (Explicit)

Verbatim from blueprint §3 "Not in the first version" — none of these may complicate the v1 architecture:

- SuvMusic's HQ Audio source
- Remote 320-kbps server routing
- Source switching between HQ Audio and YouTube
- Custom C/C++ audio engine
- Parametric equalizer
- Spatial audio
- Pitch and time-stretch processing
- Listen Together
- Cloud backup
- Spotify importing
- Social integrations
- Advanced recommendation engine
- Multiple lyric providers (all lyrics)
- Ringtone creation
- Widgets
- Android Auto browsing
- Casting
- Sophisticated download management (any downloads — v1 is online-only)

Release-shape exclusions (owner-authorized):

- No account/login of any kind; no user profile
- No local-file playback (audio files on device are out of scope for v1)
- No playlist synchronization/upload to YouTube; Playlists are local-only
- No languages other than English
- No analytics/telemetry/crash-reporting SDKs shipped in v1 [PROVISIONAL — NFR-9]
- Not a SuvMusic clone: no copying of its code, names, branding, or visual assets (GPL study-only posture, §7.1)

## 6. MVP Scope

### 6.1 In Scope

All Must FRs above, summarized by feature group:

1. **Discovery & Search** — four-type search, pagination, conditional Home Feed, Offline Fallback Cache (FR-1–4)
2. **Catalog Details** — Album/Artist/Catalog Playlist views with play entry points (FR-5–7)
3. **Playback Core** — transport controls, lazy resolution, expiry renewal, stall watchdog, quality preference (FR-8–15)
4. **Background & System Integration** — background audio, notification, lock screen, audio focus, route changes, recents behavior (FR-16–21)
5. **Queue Management** — context queues, manipulation, persistence (FR-22–25)
6. **Shell & Players** — nav shell, Mini Player, Full Player, adaptive layout (FR-26–29)
7. **Library & Persistence** — likes, local Playlists, Library hub, Play History (FR-30–34)
8. **Artwork** — caching pipeline and fallbacks (FR-35–36)
9. **Errors & Offline** — typed states everywhere, offline mode (FR-37–38)
10. **Settings & About** — theme, licenses/attribution (FR-39–40)

Platform envelope: Android phones, minSdk 26 (Android 8.0) through latest stable Android; Kotlin + Jetpack Compose (Material 3 base, customized); Media3-class playback service expected (architecture confirms versions); portrait-first with Should-level adaptation; English UI, strings externalized but untested beyond English [ASSUMPTION A-7]. Exactly one DI framework, one database, one HTTP stack (NFR-8) — concrete choices belong to the architecture phase (OQ-2/OQ-3).

### 6.2 Out of Scope for MVP

Everything in §5, with reasons embedded there. `[NOTE FOR PM]` Downloads and lyrics are the two most emotionally requested deferrals — revisit immediately after v1 stabilization (blueprint Phase 10 ordering).

## 7. Success Metrics

Measurement honesty: v1 ships no telemetry, so all metrics use scripted tests, structured manual testing, and owner dogfooding logs — not usage analytics.

**Primary**
- **SM-1: Core-loop reliability.** A scripted end-to-end suite (search → play → background → lock-screen control → process-death → restore) passes 100%; a 20-query search sample yields a playable Song ≥ 95% of the time. Validates FR-1, FR-8–FR-14, FR-25, NFR-3, NFR-4.
- **SM-2: Forced-expiry resilience.** In simulated expired-Stream-URL tests, recovery with position resume succeeds 100% within FR-13 bounds. Validates FR-12–FR-14, NFR-3.

**Secondary**
- **SM-3: Dogfood retention.** Owner uses Sway as sole daily player for 14 consecutive days; friction log holds ≤ 5 open P1/P2 defects at day 14. Validates the integrated whole (UJ-1…UJ-5).

**Counter-metrics (do not optimize)**
- **SM-C1: Feature breadth.** No §5 optional-feature work merges while any P0 core-loop defect is open. Counterbalances SM-3 pressure to "just add lyrics/downloads."
- **SM-C2: Visual spectacle past budgets.** Motion/extraction work must re-pass NFR-5/NFR-6 checks; a contrast or jank regression blocks release regardless of aesthetic gain. Counterbalances SM-1/SM-2 pressure toward polish-at-any-cost.

## 8. Open Questions

Owned by the named phase unless noted. Q-D (minimum SDK) is RESOLVED by owner decision: minSdk 26, target latest stable.

- **OQ-1 (was Q-A):** Transport strategy — NewPipe-only first release vs adding an InnerTube client. Owner: architecture phase. **Gates FR-3** (Home Feed depth) and FR-6 extension tier.
- **OQ-2 (was Q-B):** DI framework — Hilt vs Koin (reference evidence: Koin alone could not safely own heavy singletons, forcing a bridge). Owner: architecture.
- **OQ-3 (was Q-C):** Primary network library — OkHttp vs Ktor. Owner: architecture.
- **OQ-4 (was Q-E):** Legal posture on InnerTube-style clients vs NewPipe-only — product/legal decision affecting OQ-1. Owner: Hemant with architecture input. Until resolved, distribution framing stays personal-use.
- **OQ-5 (product):** Confirm FR-21 recents-swipe behavior [PROVISIONAL].
- **OQ-6 (product):** Confirm FR-15 audio-quality setting inclusion [PROVISIONAL].
- **OQ-7 (product):** Name locked as **Sway** (owner, 2026-08-23). REMAINING: trademark/play-store final collision check before public release.

### Provisional Decisions Register (owner veto list)

| # | Decision | Where | Veto via |
|---|---|---|---|
| P-1 | ~~Product name **Sway**~~ RESOLVED — owner locked **Sway** | Title | OQ-7 (release-gate check remains) |
| P-2 | Audio-quality setting ships in v1 | FR-15 | OQ-6 |
| P-3 | Recents-swipe does not stop playback | FR-21 | OQ-5 |
| P-4 | No telemetry/analytics/crash SDKs in v1 | NFR-9, §5 | Owner note to PRD |
| P-5 | Watchdog thresholds 3 s / 15 s and −5 min expiry margin adopted as initial targets from reference evidence | FR-13/14, NFR-3 | Architecture tuning with evidence |

## 9. Assumptions Index

Inline tags round-tripped:

- **[ASSUMPTION A-1]** Extraction-based Catalog access remains viable through development; total upstream breakage is an accepted inherent risk of the category (reference flaw #1) — mitigated by typed errors and fallbacks, not solved.
- **[ASSUMPTION A-2]** Single user, single device; no multi-device or sync expectations exist in v1.
- **[ASSUMPTION A-3]** Baseline Device ≈ 2023 mid-range Android, 4 GB RAM class, running minSdk 26; all perf budgets cite it.
- **[ASSUMPTION A-4]** Previous-track gesture restarts the current track after ≥ 5 s played (mainstream convention).
- **[ASSUMPTION A-5]** History records after 10 s of playback; capped at 500 entries.
- **[ASSUMPTION A-6]** Artwork disk cache default cap 256 MB with LRU eviction (architecture tunes).
- **[ASSUMPTION A-7]** English strings externalized to resources (i18n-ready, untested beyond English).
- **[ASSUMPTION A-8]** Content availability/regions mirror whatever the Catalog source returns; no region override control in v1.
- **[ASSUMPTION A-9]** Explicit-content handling: displayed as the source provides; no filtering features in v1.
- **[ASSUMPTION A-10]** Notification dismissal behavior follows platform media-session defaults (dismiss-while-playing stops playback).

---

## Appendix A — Cross-Cutting Non-Functional Requirements

These bind the whole system, not one feature. Architecture realizes them; epics verify them.

#### NFR-1: Startup never blocks the main thread *(Must)*
Cold start renders interactive Home without synchronous disk/network/preferences reads on the startup path — the reference's `runBlocking` DataStore anti-pattern is prohibited (sprint-R1 evidence).
**Bounds:** cold start → interactive Home ≤ 2.5 s p95 on Baseline Device; strict-mode violations on main thread = build warnings triaged to zero.

#### NFR-2: Typed results at every layer boundary *(Must)*
Every repository/data call returns a typed Result-like state; `emptyList` is never a failure signal; failure categories map to FR-37 UI states (L2/D-03).
**Verification:** unit tests inject failure per repository; code-review checklist bans swallow-and-return-empty patterns.

#### NFR-3: Stream resilience bounds *(Must)*
Read-time URL validation with −5 min expiry margin; 403/410-triggered renewal with position resume (±3 s); watchdog escalation at 3 s/15 s initial targets; in-flight resolve dedup prevents resolve storms (L5).
**Verification:** forced-expiry and forced-stall test suites meet bounds (SM-2).

#### NFR-4: Continuity *(Must)*
Navigation never interrupts audio; Playback Session survives process death (FR-25); the local database survives restarts intact (blueprint §19 success criteria).
**Verification:** automated navigation-soak and kill-relaunch suites.

#### NFR-5: Accessibility over artwork *(Must)*
Text over Artwork Surfaces maintains WCAG 2.1 AA contrast (≥ 4.5:1 normal, ≥ 3:1 large) via the scrim pipeline; touch targets ≥ 48 dp; all controls carry TalkBack labels; animations respect the system reduced-motion setting.
**Verification:** contrast audit across light/dark × bright/dark artwork samples; accessibility scanner clean on core flows.

#### NFR-6: Runtime performance budgets *(Must)*
On Baseline Device: list scrolling and player transitions hold p95 frame time ≤ 16 ms (jank frames > 24 ms under 1%); Full Player open transition feels immediate (≤ 300 ms).
**Verification:** macrobenchmark/profiled runs during animation; blur is banned in v1 (UX-P6); the measurement gate applies only to any future blur proposal on Baseline Device.

#### NFR-7: Code structure budgets *(Must)*
Facade + small sub-services pattern; no repository/service exceeds 1000 LOC (hard budget, D-02); every module testable without launching the full app (L7, blueprint §19).
**Verification:** LOC lint budget in CI; module-level unit tests exist for model/domain/data layers.

#### NFR-8: Single-stack rule *(Must)*
Exactly one DI framework, one database, one HTTP stack — decided up front in architecture, never run in parallel, never migrated mid-flight (L1/D-01).
**Verification:** dependency audit shows no second framework of any class.

#### NFR-9: Privacy posture *(Must)*
Personal data (Likes, Playlists, Play History, settings) stays on-device; no accounts; no telemetry/analytics/crash-reporting SDKs in v1 [PROVISIONAL]; network egress limited to Catalog/stream/Artwork retrieval.
**Verification:** traffic inspection in debug builds shows no unexpected endpoints.

#### NFR-10: Resource discipline *(Must)*
The playback service self-stops when idle (no zombie services); Artwork disk cache bounded (A-6) with eviction; no unbounded in-memory caches of Catalog results.
**Verification:** idle-device checks show service gone after stop; cache size stays under cap in soak tests.

## Appendix B — Constraints & Guardrails

### B.1 Legal & provenance

- **Study-only independence:** the reference repo (GPL-3.0) is studied, never copied — zero source, asset, name, or branding reuse; ideas and behavior only. License obligations for *shipped* dependencies are honored via FR-40.
- **Extraction legality unresolved:** OQ-4 governs distribution posture; until decided, the project frames as personal use. This constraint is why Q-A/Q-E stay coupled.
- **Content mirrors source:** availability, regions, and explicit-content labeling appear exactly as the Catalog provides them; no region-override or filtering features in v1 [ASSUMPTION A-8] [ASSUMPTION A-9].
- **Upstream fragility accepted:** dependence on unofficial Catalog access means breakage can affect everyone at once (A-1) — the product communicates failure honestly rather than promising availability.

### B.2 Privacy

On-device ownership of personal data; minimal egress (NFR-9). No dark-pattern data collection is ever acceptable in this product, including future versions.

### B.3 Binding architectural constraints (evidence-derived, realization owned by architecture)

| # | Constraint | Source |
|---|---|---|
| C-1 | One DI framework, one database, one HTTP stack, decided up front | L1, D-01 |
| C-2 | Typed errors end-to-end from day one; no swallow-and-return-empty | L2, D-03 |
| C-3 | Media-library-service + player-in-service + controller-from-UI skeleton | L3 |
| C-4 | Lazy resolution: queue-wide metadata, current-track-only stream URLs | L4 |
| C-5 | Layered expiry defense: read-time validation, error-renewal, position resume, stuck-buffer watchdog; dedup resolver cache | L5 |
| C-6 | Bitrate-target format selection over itag bookkeeping | L6 |
| C-7 | Facade + small sub-services; classes well under ~1000 LOC | L7, D-02 |
| C-8 | Offline fallback caches for search/browse reliability | L8 |

Architecture must show how each constraint is realized; epics/stories must trace back through FR/NFR IDs to them.

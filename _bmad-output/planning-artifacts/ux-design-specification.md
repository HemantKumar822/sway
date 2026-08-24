---
title: Sway UX Design Specification
status: final
created: 2026-08-23
updated: 2026-08-23
project: Player
owner: Hemant
name_provisional: false
description: Single merged UX contract (visual identity + experience) for Sway v1, produced headless per bmad-ux with caller-supplied inputs.
sources:
  - planning-artifacts/prd.md
  - docs/decisions/0003-design-direction.md
  - docs/decisions/0005-name-update-sway.md
  - docs/suvmusic-research-blueprint.md s12 s13 (s13 overridden by 0003)
  - docs/research/sprint-R1-summary.md
note: >
  Headless run. The bmad-ux skill's two-spine format (DESIGN.md + EXPERIENCE.md) is merged
  into this single document at the caller's direction; spine section order is preserved
  (visual identity sections follow the DESIGN.md canon; behavioral sections follow the
  EXPERIENCE.md canon). Spine rules win on conflict with any future mock or import.
---

# Sway UX Design Specification

**Sway — "Your music, in flow."**
Material 3 Expressive foundation, customized with Sway tokens. Artwork drives atmosphere through smooth surfaces and fluid motion — never heavy transparency gimmicks. Smoothness benchmark: iOS-grade polish. Binding direction: `docs/decisions/0003-design-direction.md`; name/tagline: `docs/decisions/0005-name-update-sway.md`.

## 0. How to read this document

- **Traceability:** every screen and state cites PRD requirement IDs (`FR-x` / `NFR-x` / `UJ-x`). `[PROVISIONAL]` marks decisions made by the headless UX proxy awaiting owner veto; the full register is §14.
- **Vocabulary:** glossary terms from PRD §3 are used verbatim (Song, Queue, Playback Session, Mini Player, Full Player, Artwork Surface, Typed Error State, Offline Fallback Cache, Library, Home Feed, Liked Songs, Play History, Playlist, Catalog Playlist, Baseline Device). Introducing synonyms downstream is a discipline violation.
- **Token references:** `{path.to.token}` refers to the YAML frontmatter above (per the design.md spec). Architecture/implementers map these to Compose `MaterialTheme` roles + Sway extension tokens.
- **Precedence:** PRD > decision 0003 > decision 0005 > blueprint §12 (structure) > blueprint §13 (historical; overridden by 0003 wherever they conflict) > this document's `[PROVISIONAL]` items.

---

## 1. Design principles

Derived from decision 0003 and PRD vision §1. These arbitrate every downstream design dispute; earlier principles win.

| # | Principle | Meaning in practice | Traces |
|---|---|---|---|
| P-D1 | **Smoothness is a feature, not garnish** | Every transition holds the 16 ms p95 frame budget (NFR-6). If a motion can't hold budget on the Baseline Device, it ships simpler — never janky. Gesture-driven motion uses springs, is interruptible and reversible. | NFR-6 |
| P-D2 | **Restraint over decoration** | Calm, premium, quiet confidence. One emphasis per view. No gradients-for-the-sake-of-it, no marquees, no looping ambient animations, no transparency showcase. | 0003 §1.3 |
| P-D3 | **Honest states everywhere** | Every data-driven surface renders exactly one Typed Error State: loading, content, empty, or error-with-retry. Blank screens are forbidden and release-blocking. | FR-37, NFR-2 |
| P-D4 | **Artwork-forward, surface-smooth** | Artwork is the emotional core: large presentation, color extraction driving atmosphere via tonal surfaces and gentle scrims — not frosted glass. | 0003 §1.5, glossary Artwork Surface |
| P-D5 | **Readability is non-negotiable** | WCAG 2.1 AA contrast (≥4.5:1 normal, ≥3:1 large) holds over any artwork via the scrim pipeline (§9). Aesthetic gain never buys a contrast regression (SM-C2). | NFR-5, SM-C2 |
| P-D6 | **Content over chrome** | Controls recede; music and artwork lead. Persistent reachability: transport always one gesture away (Mini Player above nav on every tab). | FR-26, FR-27 |
| P-D7 | **Playback is sacred** | Navigation, network failure, and process death never interrupt audio or lose the user's place (NFR-4). UI reflects playback truth within 250 ms regardless of originating surface. | FR-16, FR-25, FR-27 |

---

## 2. Foundation

- **Form factor:** Android phones, portrait-first; adaptive behavior for ≥600 dp widths and landscape (FR-29 — specifics in §7.6, `[PROVISIONAL]`).
- **UI system:** Jetpack Compose + Material 3 Expressive components, customized exclusively through Sway tokens (this document). Stock-Material look is explicitly avoided; transparency gimmicks are out (0003).
- **Platform envelope:** minSdk 26 → latest stable; Kotlin/Compose; Media3-class playback service behind the UI (architecture confirms); Coil-class image loading (sprint R1 stack evidence). UX specifies outcomes, not library versions.
- **System integration surfaces owned by Android** (media notification, lock screen, audio focus, BT route changes): UX specifies content and parity expectations (§6.13), not chrome.
- **Localization:** English UI, strings externalized (A-7). All copy in this doc is string-resource-ready with placeholders.

---

## 3. Information architecture & navigation model

### 3.1 Destination map (blueprint §12 structure, realized per FR-26)

```text
Top-level tabs (bottom navigation)
├── Home          ← Home Feed (conditional depth per OQ-1; degrades to Search-first landing)
├── Search        ← four-type search (Songs / Albums / Artists / Catalog Playlists)
└── Library       ← Liked Songs · Playlists · Play History · Settings/About entry

Push-over detail destinations (stack onto current tab; tab state preserved)
├── Album detail            (from any Album reference)
├── Artist detail           (from any Artist reference)
├── Catalog Playlist detail (read-only)
├── Playlist detail         (local Playlist, editable)
├── Liked Songs collection  (from Library)
├── Play History            (from Library)
├── Settings                (from Library)
└── About                   (from Library)

Global playback layers (above all tabs and pushes)
├── Mini Player   ← persistent bar above bottom nav whenever a Playback Session exists
├── Full Player   ← expanded player (modal layer, container-transform from Mini Player)
└── Queue sheet   ← bottom sheet over Full Player or any surface

Modal utilities
├── Context menus (long-press)     ├── Add-to-Playlist picker
├── Create/Rename Playlist dialogs └── Confirmation dialogs (destructive only)
```

### 3.2 Navigation rules

1. **Bottom tabs** (FR-26): three destinations — Home, Search, Library. Tab icons + labels; selected state uses `{colors.primary}` pill indicator (M3 Expressive style). Tabs preserve their own back stack and scroll state; entering a detail destination and returning restores the tab exactly where it was (FR-26 consequence).
2. **Details push over tabs**, never replace them. Back (gesture or affordance) pops predictably to the origin tab in its prior state. Deep links from notifications/artwork taps land on the correct detail over the logical parent (fallback: Library).
3. **Reachability:** every destination ≤ 2 taps from launch (FR-26); About/licenses ≤ 2 taps from Library (FR-40).
4. **Mini Player layer** (FR-27): rendered above bottom nav on ALL top-level tabs simultaneously, whenever a Playback Session exists (including a restored-but-paused session, FR-25). Tap expands to Full Player (container-transform, ≤ 300 ms, NFR-6). Collapse returns to the exact prior surface with state intact.
5. **Queue sheet** (FR-23): opens from Mini Player or Full Player affordance (and from any Song context menu's "open queue" `[PROVISIONAL]`); slides over current content; dismissible by swipe-down, back, or scrim tap.
6. **Predictive back** supported throughout; no custom back hijacking except the documented close gestures above.
7. **Offline launch routing** (FR-38): with no connectivity the app opens normally into **Library** with the offline banner raised; online tabs render their offline/cached states (§5) rather than blocking.

### 3.3 Wireframe — Home tab with Mini Player

```text
┌─────────────────────────────────┐
│ ☰ Sway                    ⚙︎* │  *settings lives in Library; header keeps brand only
│                                 │
│  Good evening, Hemant ▐ [PROV]  │  greeting shelf (optional slot, see 6.1)
│ ┌─────┐ ┌─────┐ ┌─────┐ ┌────▶ │
│ │art  │ │art  │ │art  │ │      │  shelf: horizontal scroll cards
│ └─────┘ └─────┘ └─────┘ └────▶ │     (feed mode only — OQ-1)
│  Shelf title                    │
│ ┌─────┐ ┌─────┐ ┌─────┐ ┌────▶ │
│ │art  │ │art  │ │art  │ │      │
│ └─────┘ └─────┘ └─────┘ └────▶ │
│                                 │
│ (pull-to-refresh available)     │
├─────────────────────────────────┤
│ ▣ ▶ ♪ Song Title — Artist    ⏭ │  Mini Player (FR-27)
├─────────────────────────────────┤
│   ⌂ Home    🔍 Search   ♥ Lib   │  bottom nav (FR-26)
└─────────────────────────────────┘
```

### 3.4 Wireframe — Mini Player zone (persistent overlay)

```text
        …tab content…
┌─────────────────────────────────┐
│ ▉▉▉▉▉▉▉▉▉▉▉▉▉▉▉▉▉▉▉▉▉▉ (progress hairline, 2dp)
│ ┌──┐  Song Title            ▶ ⏭ │
│ │art│ Artist Name              │  tap row ⇒ expand Full Player
│ └──┘                            │  64dp tall; above nav, below sheets
├─────────────────────────────────┤
│   Home      Search     Library  │
└─────────────────────────────────┘
States: hidden (no session) · playing · paused · buffering (hairline pulses) ·
        failed-track marker (error glyph + track title struck dim; see FR-14)
```

### 3.5 Wireframe — Full Player (expanded)

```text
┌─────────────────────────────────┐
│            ⌄ (collapse)         │
│      ╔═════════════════════╗    │  artwork: ~92vw, rounded-xl,
│      ║                     ║    │  Artwork Surface backdrop =
│      ║   ALBUM ARTWORK     ║    │  extracted-color wash + scrim (§9)
│      ║                     ║    │  double-tap = like [PROVISIONAL]
│      ╚═════════════════════╝    │
│   Song Title (headline)         │
│   Artist Name · Album      ♥    │  ♥ = like toggle (tertiary rose)
│                                 │
│   1:24 ●━━━━━━━━━━━━━  3:51     │  scrubber; thumb grows on touch
│                                 │
│   ⤨        ⏮    ⏯    ⏭       ⇄  │  shuffle · prev · play · next · repeat
│                                 │
│   [Queue ⇱]   [Quality ▾]*      │  *quality chip present only if FR-15 survives OQ-6
└─────────────────────────────────┘
Backdrop: full-bleed extracted dominant color, vertical scrim to guarantee
AA contrast for all foreground text (§9). All controls ≥ 48 dp (NFR-5).
```

---

## 4. Voice and tone

Brand voice (calm, confident, honest) lives here; visual voice lives in §7/§8.

- **Principles:** plain sentences; sentence case; no exclamation marks; never blame the user; always pair a problem with an action. Failure is explained, never silent (P-D3).
- **Error copy pattern:** `[What happened]. [What you can do.]` — e.g., *"Couldn't load results. Tap to retry."*
- **Canonical strings** (string-resource keys implied):
  - Offline banner: *"You're offline. Your Library, Liked Songs, Playlists and History still work. Search and streaming need a connection."* (FR-38, UJ-5)
  - Stale badge: *"Showing saved results — may be out of date."* (FR-4)
  - Empty search: *"No matches for "{query}". Check spelling or try different words."* + actions `Clear search` (UJ-1 edge case, FR-1)
  - Track failed (watchdog skip, FR-14): *""{title}" couldn't play — skipped."*
  - Playlist delete confirm: *"Delete "{name}"? This can't be undone."* (FR-32)
  - Snackbar adds: *"Added to "{playlist}"."* / *"Playing next"* (FR-24, FR-31)
  - Restored session hint (paused, FR-25): *"Pick up where you left off"* `[PROVISIONAL copy]`
- **Numbers:** durations and positions always `m:ss`; counts spelled plainly (*"42 songs"*).

---

## 5. State patterns (Typed Error State system)

Every data-driven surface implements these five canonical states (FR-37, NFR-2). A surface renders **exactly one** at any moment. Anatomy is shared; content varies per screen (§6).

| State | Anatomy | Motion | Notes |
|---|---|---|---|
| **Loading** | Skeleton placeholders matching final layout geometry (shimmer ≤ 1 loop cycle/1.6s, single cheap shader). First-content swap is a 150 ms crossfade, no layout shift. | shimmer loop; no entrance staggers > 240 ms total | Skeleton shapes mirror §8 components so content "arrives" without reflow. |
| **Content** | Normal UI. | — | — |
| **Empty** (a genuine zero-result, distinct from failure) | Centered illustration-lite (branded glyph, no heavy art), one-line explanation, recovery action(s). | fade-in 150 ms | Search zero-match uses UJ-1 copy. Empty Playlist: *"This playlist is empty. Add songs from any song's menu."* (FR-31) |
| **Error + retry** | Inline panel or full-area state: short cause line + `Retry` button (≥48 dp). Retry preserves scroll/query. Repeated failures rotate copy after 2nd attempt (*"Still no luck — check your connection."*) `[PROVISIONAL]`. | fade 150 ms; retry button press ripple only | Categories are user-readable; never stack traces (FR-14, FR-37). |
| **Offline / stale** | Global offline banner pinned below top app bar (or above Mini Player when higher) + per-item stale badges on cached results. See §8.9. | slide-down once on state change; never loops | Cached content stays tappable (FR-4). |

**Rule:** `emptyList` from data is never shown as content; failure categories from repositories (NFR-2) map 1:1 onto Loading/Empty/Error states. Audit matrix ownership: epics verify every surface × failure injection (FR-37).

---

## 6. Screen inventory

Format: purpose → key states → traceability. All screens live in the Sway shell (§3).

### 6.1 Home (tab)
- **Purpose:** browsable landing; launch destination (NFR-1: interactive ≤ 2.5 s p95 Baseline Device).
- **Two modes** (gated by OQ-1):
  - **Feed mode** (FR-3 Should): vertically scrolling shelves (e.g., Charts, New releases, Moods — whatever transport supplies), horizontal card rails; pull-to-refresh. `[PROVISIONAL]` shelf taxonomy is transport-dependent; UX mandates shelf pattern + card anatomy, not source sections.
  - **Search-first landing** (FR-3 degraded minimum): brand header, prominent search entry, shortcut tiles into Library collections (Liked Songs, recent Playlist, Play History) — all offline-safe.
- **States:** Loading (skeleton shelves) · Content · Degraded (landing mode, labeled honestly) · Stale (cached feed + badge, FR-4) · Error+retry · Offline (banner + landing-mode shortcuts remain usable, FR-38).
- **Trace:** FR-3, FR-4, FR-37, FR-38, NFR-1; UJ-1, UJ-5.

### 6.2 Search (tab)
- **Purpose:** free-text discovery across Songs, Albums, Artists, Catalog Playlists (FR-1).
- **Anatomy:** focused search field (top), type-filter chip row (`All · Songs · Albums · Artists · Playlists`), grouped results with labeled sections; each group independently scrollable/filterable; per-group `Load more` + infinite-scroll sentinel (FR-2) with end-of-results divider (*"That's everything"*).
- **Query mechanics:** submit on search-action/debounce (not per keystroke) `[PROVISIONAL: 350 ms debounce]`; query + filter persist across tab switches; clear (✕) resets.
- **States:** Idle (pre-query: `[PROVISIONAL]` recent-searches list, locally stored, clearable) · Loading · Grouped content · Zero-results Empty (UJ-1 copy) · Error+retry per request (group-level errors don't blank sibling groups) · Offline → serves Offline Fallback Cache with stale marking, else offline explanation (FR-4, FR-38).
- **Trace:** FR-1, FR-2, FR-4, FR-37; UJ-1, UJ-5.

### 6.3 Library (tab)
- **Purpose:** the local hub — Liked Songs, Playlists, Play History entry; fully functional offline (FR-33, FR-38).
- **Anatomy:** `Create playlist` affordance (top); Liked Songs hero tile (count, play/shuffle); Playlist grid/list (artwork, name, count); Play History entry row. Overflow → Settings, About (≤ 2 taps, FR-40).
- **States:** Content (local DB — instant, no skeleton needed beyond first paint) · Empty variants (no playlists yet → helpful creation prompt, FR-31) · Error (local DB failure is exceptional but still Typed, FR-37) · Offline: unchanged — Library never requires network (UJ-5).
- **Trace:** FR-31, FR-33, FR-38, FR-40; UJ-3, UJ-5.

### 6.4 Album detail (push)
- **Purpose:** title, artist link, year (optional), tracklist, artwork; Play / Shuffle entry points (FR-5).
- **Anatomy:** hero header (artwork lg radius, title headline, artist/title-md links, year/track-count label-md); sticky compact header on scroll `[PROVISIONAL]`; `Play` (primary) + `Shuffle` (secondary) buttons; numbered tracklist (song rows, §8.1).
- **Missing metadata:** year absent → omitted cleanly, never "null"/placeholder dashes (FR-5).
- **States:** Loading (header skeleton + tracklist ghosts) · Content · Error+retry · Offline: cached render w/ stale badge (FR-4) else error; play attempts follow Stream Resolution failure paths (FR-4 consequence).
- **Play semantics:** Play builds Queue in album order starting at tapped track; Shuffle builds shuffled Queue (FR-22, FR-11).
- **Trace:** FR-5, FR-22, FR-11, FR-4, FR-37; UJ-1.

### 6.5 Artist detail (push)
- **Purpose:** name, image, top Songs (Must core); Albums/Singles listings where transport provides (Should extension, FR-6).
- **Anatomy:** circular artist image, name headline, `Shuffle top songs` quick action `[PROVISIONAL placement]`, Top Songs list, horizontal album/single rails.
- **Absent sections omit cleanly** — no empty-section shells (FR-6).
- **States:** same quintet as 6.4 (FR-37).
- **Trace:** FR-6, FR-37; UJ-1.

### 6.6 Catalog Playlist detail (push)
- **Purpose:** read-only curated playlist — title, curator/owner, track count, artwork, ordered tracklist; Play/Shuffle (FR-7).
- **Semantics identical to Album detail** (FR-7). **No local-editing affordances ever** (no add/remove/reorder UI) — it is not a local Playlist (glossary).
- **States/trace:** quintet states; FR-7, FR-22, FR-11, FR-37.

### 6.7 Playlist detail (push, editable)
- **Purpose:** user-created local Playlist — view, edit, play (FR-32).
- **Anatomy:** hero (gradient placeholder art for empty/untinted playlists `[PROVISIONAL: generated duotone from name initial]`), title (tap = rename inline dialog), count, Play/Shuffle, `Edit` toggle → edit mode reveals drag handles, remove buttons (✕ per row), `Add songs` (opens Add-to-Playlist picker, §8.12).
- **Edits persist immediately** — no save button (FR-32). Delete Playlist lives in overflow with confirmation dialog (FR-32).
- **Works fully offline** (FR-32, UJ-3 edge case).
- **States:** Content · Empty (*helpful creation guidance*, FR-31) · Local-error (rare, typed) · Offline: fully functional, no banner dependency.
- **Trace:** FR-31, FR-32, FR-22, FR-11, FR-33; UJ-3.

### 6.8 Liked Songs (push from Library)
- **Purpose:** the persistent local collection; playable/shuffleable (FR-30).
- **Anatomy:** hero (rose-tinted heart motif `[PROVISIONAL]`), count, Play/Shuffle, reverse-chronological song list (most recent first `[PROVISIONAL ordering]`).
- **States:** Content · Empty (*"Songs you like will appear here. Tap the heart anywhere."*, FR-30) · others per quintet (local data → near-instant).
- **Trace:** FR-30, FR-33, FR-22.

### 6.9 Play History (push from Library)
- **Purpose:** reverse-chronological auto-recorded plays; tap replays (FR-34).
- **Anatomy:** day-group dividers (*Today / Yesterday / date*) `[PROVISIONAL grouping]`; rows show played-at timestamp (label-md, tabular); duplicates update recency, never stack (FR-34); cap 500 with end-of-history divider (*"That's as far back as it goes"*).
- **Recording rule surfaced honestly:** entry appears once a track passes 10 s played (A-5).
- **States:** Content · Empty (*"Nothing played yet."*) · Offline: fully available (FR-34).
- **Trace:** FR-34, FR-33; UJ-4.

### 6.10 Settings (push from Library)
- **Purpose:** minimal honest preferences (FR-39).
- **Contents:** Appearance (System/Light/Dark — radio group, applies immediately, persists, no restart); Audio quality selector (AUTO default/LOW/MEDIUM/HIGH) **only if FR-15 survives OQ-6**; version pointer → About.
- **States:** trivial static; theme switch = 150 ms fade, not a full-screen animation (P-D2).
- **Trace:** FR-39, FR-15.

### 6.11 About & licenses (push from Library)
- **Purpose:** version info + third-party license attributions (legal obligation, FR-40).
- **Anatomy:** brand block (wordmark, tagline *"Your music, in flow."*), version row, licenses list (expandable per-package).
- **Trace:** FR-40 (reachable ≤ 2 taps from Library).

### 6.12 Queue (global bottom sheet)
- **Purpose:** transparent, manipulable playback order (FR-23, FR-24).
- **Anatomy:** drag handle; `Now playing` pinned current-track row (highlighted `{colors.primary-container}`); `Next up` list with Source-ID-stable rows: artwork thumb, title/artist, drag handle, ✕ remove; footer actions: `Shuffle` toggle mirror, `Clear queue` (confirmation).
- **Behaviors:** jump = tap row (audio switches ≤ 2 s, FR-23); removing the playing track advances to next (FR-23); reorder via long-press-drag with haptic ticks `[PROVISIONAL haptic policy, §14]`; insertions from context menu appear immediately in position (FR-24); removals/reorders persist within session (FR-23/24).
- **States:** Content (always, tied to live Playback Session) · Empty-session impossible (sheet only exists with a session) · Failed-track rows carry the error marker + reason category (FR-14).
- **Trace:** FR-23, FR-24, FR-14, FR-22; UJ-3, UJ-4.

### 6.13 System playback surfaces (Android-owned chrome)
- **Media notification** (FR-17): artwork, title, artist, prev/play-pause/next; parity with in-app controls; dismisses when paused; dismissing while playing stops playback per platform default (A-10).
- **Lock screen** (FR-18): session artwork + transport mirroring FR-9/FR-10 semantics; metadata matches playing track exactly.
- **Audio-focus UX** (FR-19): on transient loss (call, navigation prompt) playback pauses/ducks per architecture policy; resume happens only where policy allows — UI never fights the system.
- **Route disconnect** (FR-20): instant pause (< 1 s); reconnect never auto-resumes — the user resumes explicitly (predictability, FR-25 spirit).
- **Recents-swipe** (FR-21 `[PROVISIONAL]`): playback continues; notification remains the stop affordance.

---

## 7. Design tokens (Sway on M3 Expressive)

Frontmatter above is machine-readable truth; this section is rationale + rules. Values are the v1 proposal `[PROVISIONAL as a set]`, tuned freely before design freeze as long as roles/relationships hold.

### 7.1 Color

> **OWNER DECISION 2026-08-24 (supersedes the violet-led proposal; roles/relationships preserved per the tuning clause above):** the brand system is now **two-mode**: **"Ink & Paper"** (monochrome, Notion-philosophy default) and **artwork-dynamic color** (whole-app recolor from the playing track's cover, SuvMusic/YT-Music-style). The violet brand hue is retired.

- **Mode MONO — "Ink & Paper" (default):** Notion-design philosophy — paper neutrals do all the quiet work and color is purely semantic.
  - Light: background `#FFFFFF`; containers `#F7F7F5` / `#EFEFED` / `#E8E8E6`; ink text `#191918`; secondary text `#6B6A66`; hairline outlines `#DEDEDC` / `#EDEDEB`; primary = ink `#191918` on white (Notion's black-button language).
  - Dark ("Midnight Ink"): background `#101010`; containers `#171717` / `#1E1E1E` / `#262626`; ink `#EDECEA`; secondary `#A8A7A3`; hairlines `#2A2A28`; primary = ink `#EDECEA` on near-black.
  - AMOLED pure-black variant: background/containers collapse to `#000000`/`#0C0C0C`.
  - Semantic accents survive unchanged in BOTH modes: **rose tertiary reserved for liking**, **amber caution reserved for offline/stale**, standard M3 error ramp. Color never decorates — it only means.
- **Mode DYNAMIC — artwork-driven (opt-in, default ON once artwork pipeline lands):** when a track plays, its cover's dominant palette recolors the ENTIRE app (surfaces, containers, accents) with spring-animated transitions; seed selection prefers vibrant/dominant swatches. Falls back to Ink & Paper whenever no artwork exists, extraction fails, or contrast floors would break (NFR-5 wins over aesthetics). Wallpaper-level Material You dynamic stays OFF (artwork IS the wallpaper).
- Dark scheme is first-class in both modes (audited per NFR-5 contrast matrix).

### 7.2 Typography

- **Pairing** `[PROVISIONAL]`: **Outfit** (OFL geometric sans, warm roundness = musical character) for display/headline; **Inter** (OFL, screen-native neutrality) for titles/body/labels. Both bundled (no font-fetch latency on Baseline Device).
- Ramp (see frontmatter): Display 44 → Headline 24 → Title-LG 20 → Title-MD 16 → Body-LG 16 → Body-MD 14 → Label-LG 14 → Label-MD 12.
- **All durations/positions/counters use tabular figures** (`tnum`) — scrubbers and timestamps must not jitter.
- Text scales to 200% (§11); layouts wrap/truncate per §8 rules rather than clipping controls.

### 7.3 Shape

M3 Expressive's generosity, disciplined:

| Token | Radius | Used by |
|---|---|---|
| `{rounded.xs}` | 8 dp | chips-inner elements, badges |
| `{rounded.sm}` | 12 dp | song-row thumbnails, small cards, text fields |
| `{rounded.md}` | 16 dp | album/playlist grid cards, banners, sheets' inner blocks |
| `{rounded.lg}` | 20 dp | hero artwork (detail headers), dialogs |
| `{rounded.xl}` | 28 dp | Full Player artwork, bottom-sheet tops (M3 Expressive signature) |
| `{rounded.full}` | pill | buttons, chips, mini-player progress ends, artist circle (via 9999) |

Rule: one radius step per nesting level; artwork corners always match their container; nothing square except full-bleed scrims/backdrops.

### 7.4 Spacing & sizing

Base-4 grid: `{spacing.1}=4 · 2=8 · 3=12 · 4=16 · 5=20 · 6=24 · 8=32 · 12=48 · 16=64`. Screen margin 16 dp; list gutters 8 dp; section separation 32 dp. Minimum interactive height 48 dp (NFR-5); song rows 56–64 dp; Mini Player 64 dp + 2 dp progress hairline.

### 7.5 Elevation & depth

Flat-first: hierarchy via tonal containers (`surface-container-*`), not shadows. Shadows only for true floaters:

| Level | Treatment | Surfaces |
|---|---|---|
| 0 | flat tonal | tabs, lists, details |
| 1 | subtle shadow + container tint | Mini Player bar, banners |
| 2 | medium shadow | Queue sheet, dialogs, menus |
| 3 | strongest shadow + scrim-soft behind | Full Player (as modal layer) |

Artwork Surface depth comes from extracted-color wash + scrim (§9), never stacked translucency.

### 7.6 Adaptive layout (FR-29) `[PROVISIONAL]`

Portrait phone = design target. ≥ 600 dp: content max-width 640 dp centered OR 2-column grids (cards), list rows gain a second metadata column. ≥ 840 dp (tablet/landscape): Full Player becomes side-by-side (artwork left, controls right); nav rail replaces bottom bar; Queue sheet becomes right-side panel. Nothing unreachable/truncated at any width (FR-29 smoke matrix).

### 7.7 Motion system — the smoothness contract (NFR-6, P-D1)

**Durations**

| Token | Value | Use |
|---|---|---|
| `{motion.duration.fast}` | 120 ms | ripples, toggles, icon states, like-pop |
| `{motion.duration.base}` | 220 ms | fades, banner enter/exit, sheet content swaps |
| `{motion.duration.emphasized}` | 320 ms | detail push/pop, shelf entrances |
| `{motion.duration.player}` | **300 ms hard cap** | Full Player open/close (NFR-6 "feels immediate") |
| `{motion.duration.ambient}` | 600 ms | extracted-color backdrop crossfades between tracks |
| max anywhere | 800 ms | nothing animates longer, ever |

**Easing**

| Token | Curve | Use |
|---|---|---|
| `{motion.easing.standard}` | cubic(0.2, 0, 0, 1) | default; M3-expressive ≈ iOS-grade settle |
| `{motion.easing.decel}` | cubic(0.05, 0.7, 0.1, 1) | things entering |
| `{motion.easing.accel}` | cubic(0.3, 0, 0.8, 0.15) | things exiting |
| `{motion.spring.default}` | dampingRatio 0.9, stiffness MediumLow | gesture-driven player expand/collapse |
| `{motion.spring.pop}` | dampingRatio 0.75, stiffness Medium | like-button heartbeat |

**What animates:** container-transform Mini↔Full Player (shared artwork element, spring, gesture-interruptible); artwork crossfade on track change (ambient 600 ms); extracted-color shifts (ambient); like pop; skeleton shimmer; scrubber thumb grow (fast); queue drag-lift; item remove = fade+collapse (base); pull-to-refresh (platform spinner); tab pill indicator; predictive-back scale/slide (system-driven).

**What NEVER animates:** anything on the scroll hot path beyond transform/alpha (no layout-property animation in lists); theme switching (150 ms fade max, no full-screen sweeps); state text changes that reflow reading positions; stream-resolution internals — no spinner during normal gapless track transition (audio continuity is the feedback; buffering indicator appears only when watchdog thresholds approach, FR-14); marquee/scrolling text (truncate instead); looping ambient decoration of any kind; notification/lock screen (system-owned).

**Reduced motion (NFR-5):** system reduced-motion replaces transforms/slides with opacity fades ≤ 120 ms and disables springs/shimmer; content parity is unchanged.

**Interruption law:** every animation is interruptible and reversible; retargeting a spring mid-flight is mandatory behavior, not polish.

---

## 8. Component catalogue (v1)

Behavioral specs here; visual tokens in frontmatter `components:`. Every component inherits the §5 state discipline where data-driven.

### 8.1 SongRow
- Variants: indexed (album/catalog tracklists), thumbnailed (search/history/queue), playing (equalizer-bars glyph in `{colors.primary}`, title in primary), failed (error glyph + dimmed strike, reason on long-press/detail, FR-14).
- Anatomy: leading (index/thumb/artwork-placeholder) · title (title-md, 1-line ellipsis) · subtitle (body-md, artist [+duration]) · trailing (duration or ❤ or ⋯ context menu).
- Touch ≥ 56 dp row height; long-press opens context menu (§8.13). Traces: FR-1, FR-5..7, FR-8, FR-23, FR-30, FR-34.

### 8.2 AlbumCard / PlaylistCard (grid + rail)
- Square artwork `{rounded.md}`, title (title-md, ≤2 lines), subtitle (label-md, artist or curator/count). Rail card width 152 dp `[PROVISIONAL]`. Placeholder art = `{colors.placeholder-art}` + branded glyph (FR-36). Trace: FR-1, FR-3, FR-33.

### 8.3 HeroHeader (Album/Catalog Playlist/Playlist/Liked Songs)
- Large artwork `{rounded.lg}` (Catalog types) or generated art (local), headline title, metadata lines, Play + Shuffle buttons (min-height 48 dp, filled primary / tonal secondary). Sticky-on-scroll compact variant `[PROVISIONAL]`. Trace: FR-5..7, FR-30, FR-31.

### 8.4 ArtistHeader
- Circular portrait `{rounded.full}` (fallback initials-avatar per FR-36), name headline, quick Shuffle action. Trace: FR-6.

### 8.5 MiniPlayer
- Anatomy per wireframe §3.4: 48 dp artwork thumb `{rounded.sm}`, title/artist (1-line each), play/pause + next (48 dp hit areas), full-width 2 dp determinate progress hairline (scrubbing NOT available here — deliberate: seek lives in Full Player/Queue `[PROVISIONAL]`).
- Behavior: appears with Playback Session existence (incl. restored-paused, FR-25); state sync ≤ 250 ms from any origin (FR-27); tap = expand (container-transform); swipe-down = dismiss bar only (session persists; `[PROVISIONAL]` — dismissal hides UI, never kills audio, FR-16).
- Buffering: hairline enters pulsing indeterminate; stalled > watchdog thresholds escalates per FR-14 with inline error chip.
- Trace: FR-27, FR-16, FR-25, FR-14, NFR-6.

### 8.6 FullPlayer
- Layout per wireframe §3.5. Artwork ~92% width `{rounded.xl}`, drop-shadow level 3; title/headline + artist/body-lg + heart; scrubber with elapsed/remaining (tabular numerics, ±1 s accuracy, FR-9); transport cluster (shuffle · prev · play/pause 72 dp emphasis · next · repeat); secondary row: Queue access; Quality chip (only if FR-15 survives OQ-6).
- Shuffle/repeat: icon-toggle chips with active pill state; repeat cycles off → all → one (badge "1"); persistence invisible but restored with session (FR-11, FR-25).
- Prev semantics honored (≥ 5 s restart, A-4) — no visual difference needed.
- Open/close: container-transform ≤ 300 ms; swipe-down or back collapses (state never lost, FR-27/28).
- Trace: FR-28, FR-9, FR-10, FR-11, FR-15, FR-30, NFR-6; UJ-3 (heart), UJ-1.

### 8.7 QueueSheet (component view of 6.12)
- Rows reuse SongRow (queued variant: no duration, add handle+remove); current row pinned under handle; reorder = long-press lift + drag (auto-scroll edges); Clear = confirmation. Trace: FR-23, FR-24.

### 8.8 Chips & toggles (shuffle/repeat/type-filters/quality)
- Pill `{rounded.full}`; unselected: outline + on-surface-variant; selected: `{colors.secondary-container}` + on-secondary-container (type filters), primary for transport modes. Min-height 32 dp inside 48 dp padding zones. Quality selector (if shipped): AUTO/LOW/MEDIUM/HIGH in a modal bottom sheet with plain-language descriptions (*AUTO — adjusts to your connection*), selection applies from next resolution (FR-15 consequence stated in helper text). Trace: FR-1, FR-11, FR-15.

### 8.9 OfflineBanner + StaleBadge
- Banner: `caution-container` bg, on-caution-container text, wifi-off glyph, body-md, full-width below top bar (above Mini Player z-order), single slide-down on state change, dismiss ✕ (reappears on next offline event, not per screen). Copy per §4. StaleBadge: label-md chip *"Saved"*, caution-container, on cached groups/items (FR-4). Trace: FR-38, FR-4; UJ-5.

### 8.10 ErrorPanel + Retry
- Inline (row-level) and area (screen-level) variants; glyph + cause line (body-lg) + `Retry` filled-tonal button ≥48 dp; preserves query/scroll on retry; second consecutive failure rotates supportive copy `[PROVISIONAL]`. Track-level failure uses inline SongRow variant + snackbar (*skipped*, FR-14). Trace: FR-37, FR-14, NFR-2.

### 8.11 Skeletons
- Shape-mirrored ghosts (SongRow ghost, HeroHeader ghost, CardGrid ghost); shimmer single-loop; content arrival = 150 ms crossfade, zero reflow. Never used for local Library data (instant from DB) — honesty about speed. Trace: FR-37.

### 8.12 AddToPlaylistPicker (bottom sheet)
- List of local Playlists + `New playlist` row (inline name field → create-and-add, FR-31); check-mark on add; multi-song batch from edit mode. Works offline. Trace: FR-31, FR-32.

### 8.13 ContextMenus (long-press, Song/Album/Playlist entities)
- Song: Play next · Add to queue · Add to playlist · Like/Unlike · Go to album *(if available)* · Go to artist *(if available)* · Share `[PROVISIONAL: share raw catalog URL]`. Album/Playlist: Play · Shuffle · Go to artist. Insertions reflect immediately in Queue (FR-24). Trace: FR-24, FR-30, FR-31.

### 8.14 Dialogs
- Rename/Create Playlist (single text field, duplicate names allowed, FR-31); Delete confirmations (Playlist deletion only destructive confirm in v1, FR-32); max width 280–360 dp, `{rounded.lg}`, scrim-soft.

### 8.15 Snackbars
- Bottom-anchored above nav (below Mini Player? — **above Mini Player** to stay visible `[PROVISIONAL z-order]`), inverse-surface styling, 4 s, single action. Used for: added-to-playlist, play-next, skipped-track notice. Trace: FR-24, FR-31, FR-14.

### 8.16 ArtworkPlaceholder
- Neutral branded glyph on `{colors.placeholder-art}`; identical bounds to loaded art — zero layout shift (FR-36); auto-retries on connectivity restore (FR-36). Trace: FR-35, FR-36.

---

## 9. Artwork-driven theming flow (Artwork Surface pipeline)

Scope: **atmosphere is player-scoped** — Full Player backdrop, Mini Player accents, Queue sheet tint. Browse/Library keep the stable brand scheme `[PROVISIONAL scope]`: restraint (P-D2), contrast stability, and no full-app color churn per track. Status-bar tint subtly echoes the player color `[PROVISIONAL]`.

Pipeline (off-main-thread; architecture realizes, UX owns guarantees):

```text
1 LOAD     artwork via image pipeline, memory+disk cache (FR-35)
2 EXTRACT  downscale ≤128px → dominant + vibrant + dark-vibrant candidates
3 MAP      dominant → backdrop tone; vibrant → accent echoes (progress, highlights)
           quantized/clamped to safe lightness ranges per light/dark mode
4 GUARANTEE scrim engine computes overlaid-text contrast; applies vertical
           scrim-strong (α .60) → scrim-soft (α .35) gradient until EVERY
           text/icon region ≥ 4.5:1 (≥ 3:1 large)  ← non-negotiable (NFR-5)
5 APPLY    600 ms ambient crossfade between tracks; no hard cuts
6 FALLBACK extraction unavailable/failed → neutral brand scheme backdrop
           + placeholder art (FR-36); layout never breaks, contrast never dips
```

Hard rules:

- **No runtime Gaussian blur on large surfaces in v1.** Smoothness comes from tonal washes + gradients (decision 0003: smooth surfaces, not frosted glass). Any future blur must re-pass NFR-6 measurement on Baseline Device before design freeze (SM-C2). Small-radius blur exceptions: none approved today `[PROVISIONAL: none]`.
- Extraction budget ≤ 50 ms CPU per artwork on Baseline Device `[PROVISIONAL number]`; results cached with the artwork entry (re-view = zero recompute, FR-35 spirit).
- Scrims are computed, not hand-tuned: verification walks the light×dark × bright×dark-artwork matrix (NFR-5 verification clause).
- Foreground text over Artwork Surface uses fixed `on-surface`/white ramps chosen by the scrim engine per region — designers never place raw brand colors over artwork.

---

## 10. Critical interaction flows (UJ mappings)

Choreography-level specs; engineering timings cite PRD/NFR bounds.

### 10.1 UJ-1 — Maya: café song → playing (FR-1, FR-8, FR-26, FR-27, FR-37)
1. Cold start → interactive Home ≤ 2.5 s p95 (NFR-1); no login gate, no splash theater — brand header + content skeletons immediately.
2. `Search` tab (one tap) → field auto-focuses `[PROVISIONAL]`. Typing submits on debounce/action → Loading skeletons per group (FR-37).
3. Groups render labeled (Songs first `[PROVISIONAL order]`); each result tap gives instant pressed feedback; Song tap → Mini Player materializes **immediately** (optimistic, artwork placeholder ok), audio ≤ 3 s p95 (FR-8); Queue = the Songs group, positioned at tapped track (FR-22).
4. Transition to pocket happens mid-startup — nothing breaks (FR-16).
5. Mistyped query → typed Empty state with spelling hint + Clear (never blank, UJ-1 edge).
**Climax beat:** audio starts before the phone leaves her hand.

### 10.2 UJ-2 — Dev: commute survival (FR-9, FR-10, FR-16..FR-20)
- In-app: controls on Mini + Full + notification + lock screen behave identically (FR-9/10 semantics; ±1 s position truth).
- Tunnel: buffering → hairline pulse; watchdog escalation invisible unless a track truly fails (then inline error + auto-skip, FR-14).
- Call: pause on focus loss; notification resume after (FR-19). BT-off: instant pause, speaker blast impossible (FR-20).
- UX obligation: state sync ≤ 250 ms across all surfaces so returning to the app shows truth instantly (FR-27).

### 10.3 UJ-3 — Priya: workout playlist across sessions (FR-30, FR-31, FR-32, FR-38)
1. Week: discover → heart from Full Player (pop animation, syncs to Library ≤ 250 ms, FR-30).
2. Sunday, Library: `Create playlist` → name "Gym" (dialog, FR-31). Edit mode → `Add songs` → picker → Liked Songs batch-add (FR-32). Drag-reorder with haptic ticks; ✕ removes two duds; rename inline later.
3. Network dies mid-edit: nothing changes — edits are local and continue (UJ-3 edge, FR-32). Only a streaming attempt would explain itself (§8.9).
4. Monday: Play on Playlist → Queue in exactly the arranged order, starts at first track (FR-22).
**Climax beat:** Monday's queue matches Sunday's arrangement, exactly.

### 10.4 UJ-4 — Alex: overnight process death (FR-25, FR-34, NFR-4)
1. Relaunch → Home renders (NFR-1) AND Mini Player is already present, **paused**, showing yesterday's track/position (session restored, never auto-plays — FR-25 predictability).
2. Tap play → resumes ±5 s of sleep-point (FR-25). Optional hint copy *"Pick up where you left off"* `[PROVISIONAL]`.
3. Library → Play History → Tuesday's entry replays with its context queue (FR-34, FR-22).
**Climax beat:** one tap between dead process and music.

### 10.5 UJ-5 — Sofia: subway offline (FR-4, FR-36, FR-37, FR-38)
1. Opens underground → app opens **normally into Library**, offline banner raised with the works/doesn't-work split (FR-38). Launch never blocks on network (NFR-1 spirit).
2. Plays local Playlist → instant, artwork from cache where seen before, branded placeholders elsewhere (FR-36) — no broken-image states, ever.
3. Tries Search → offline state serves Offline Fallback Cache marked *"Saved — may be out of date"* (FR-4); entries stay tappable; a play attempt follows normal resolution failure paths with honest messaging (FR-4).
4. Reconnect (elevator): banner clears automatically; online actions restore without restart (FR-38).
**Climax beat:** nothing crashes, nothing blanks — the app is simply honest.

### 10.6 Core player micro-interactions
- **Expand:** tap Mini or swipe-up on it → container-transform (artwork flies to hero geometry, spring, ≤ 300 ms cap, gesture-interruptible). **Collapse:** ⌄, back, or swipe-down.
- **Seek:** touch grows thumb (fast); drag shows live time bubble; release applies audio ≤ 500 ms (FR-9).
- **Like:** heart pop `{motion.spring.pop}`; double-tap artwork alternative `[PROVISIONAL]`.
- **Queue open:** from Full Player chip or Mini long-press? — no: **explicit Queue affordance on both** `[PROVISIONAL]` (discoverability over cleverness).
- **Track change:** artwork + colors crossfade ambient; title swap no-reflow; progress resets deterministically.

---

## 11. Accessibility floor (NFR-5)

- **Contrast:** WCAG 2.1 AA — ≥ 4.5:1 body, ≥ 3:1 large text/icons; enforced by §9 scrim engine over artwork and by token pairs elsewhere; audited across light×dark × artwork-brightness matrix.
- **Touch targets:** ≥ 48 × 48 dp every control (icons may render 24 dp inside 48 dp zones); adjacent targets ≥ 8 dp separation.
- **TalkBack:** every control carries a content label; dynamic state announced — examples: `"Play {title} by {artist}"`; like = `"Liked, {title}" / "Not liked, {title}"` toggle announcement; shuffle/repeat announce mode including repeat-one; Queue rows announce position (*"3 of 12"*); sliders (scrubber) announce percent + remaining; offline banner announced politely (liveRegion) once.
- **Focus order:** logical (content → transport → nav); Full Player traps focus appropriately while open.
- **Gesture alternatives:** every gesture has a button equivalent (expand=tap, queue-dismiss=back/scrim, row-remove=✕, reorder also possible via move-up/move-down in row menu `[PROVISIONAL]`).
- **Text scaling:** 200% without losing controls — rows wrap to 2 lines then truncate subtitle; heroes stack; transport cluster never truncates (icons scale-independent).
- **Motion:** honors reduced-motion (§7.7). No content conveyed by color alone (playing state = icon+color; failed = glyph+text).
- **Verification:** accessibility scanner clean on core flows (NFR-5 clause).

---

## 12. Performance guardrails (Baseline Device, NFR-6)

These bind visual ambition; violations block release (SM-C2).

1. **Frame budget:** p95 frame ≤ 16 ms; jank (> 24 ms) < 1% during list scroll AND player transitions. Measured via macrobenchmark during animation, not idle.
2. **Player transition:** open ≤ 300 ms perceived-immediate; implemented as shared-element container-transform on layer-safe properties (translate/scale/alpha) — no blur, no per-frame allocation.
3. **Lists:** lazy virtualization with stable keys (= Source ID); prefetch artwork thumbs at list velocity; full-res artwork loads only for visible hero/player contexts.
4. **Extraction:** ≤ 50 ms CPU/artwork `[PROVISIONAL]`, cached, off-main-thread; no extraction at all for player-unrelated surfaces.
5. **Blur policy:** prohibited by default (§9). If ever proposed: measure first on Baseline Device, ship only within budget (NFR-6 clause anticipates this).
6. **Skeletons** use one shared shader; no infinite animated vectors.
7. **Cold start:** no synchronous disk/network/preferences on startup path (NFR-1); Home interactive ≤ 2.5 s p95; skeletons are the honest bridge, never artificial delays.
8. **State-sync cost:** playback-state propagation ≤ 250 ms budget includes recomposition discipline — player state hoisted, derived reads minimized (architecture co-owns).

---

## 13. SuvMusic: experience-inspiration only (never copied)

Per blueprint posture + decision 0003 + PRD §5/B.1 (GPL study-only; zero code/asset/name/branding reuse):

| Studied for experience (allowed) | Never copied |
|---|---|
| Player open/close transition choreography | Any source code, in any language, any amount |
| Artwork presentation scale & centering | Artwork, icons, logos, wordmarks, fonts-as-assets |
| Perceived-performance tricks (lazy UI around resolution) | Names, branding, marketing copy |
| Navigation treatment concepts (mini-above-nav pattern proven in wild) | Its HQ-audio/source-switching feature shapes (out of scope §5) |
| Expressive shape playfulness (as M3-Expressive evidence) | Its liquid-glass system look (superseded by 0003 anyway) |

Every borrowed idea must be re-derived through Sway tokens/principles and re-verified against NFR-5/6. Provenance note goes in story descriptions when applicable.

---

## 14. Open questions & PROVISIONAL register

Owner veto list (cheap to change now, expensive later). Format mirrors PRD §8.

| # | Decision (made headless) | Where | Veto/confirm via |
|---|---|---|---|
| UX-P1 | Brand seed = violet; rose=like; amber=offline/stale; dynamic color OFF in v1 (structurally ready) | §7.1 | Owner color review |
| UX-P2 | Typography pairing Outfit (display) + Inter (UI/body) | §7.2 | Owner type review |
| UX-P3 | Search: 350 ms debounce; auto-focus field on tab open; recent searches stored locally & clearable | §6.2 | Owner |
| UX-P4 | Home greeting shelf + shelf taxonomy left transport-dependent; landing-mode shortcut tiles defined | §6.1 | Coupled to OQ-1 |
| UX-P5 | Atmosphere scoped to player surfaces (not global re-theme); status-bar echo | §9 | Owner taste |
| UX-P6 | No blur anywhere in v1, including small radii | §9 | Requires NFR-6 evidence to revisit |
| UX-P7 | Extraction budget 50 ms CPU; rail card 152 dp; Songs group first in results | §7/§8/§12 | Tunable constants |
| UX-P8 | Haptics: ticks on queue drag-grab + reorder snap; light tick on like | §8.7/§10.6 | Owner device feel |
| UX-P9 | Double-tap artwork = like; Mini swipe-down hides bar (audio persists); explicit Queue affordance on Mini + Full | §8.5/§10.6 | Owner |
| UX-P10 | Mini Player has no scrubbing (seek in Full/Queue); snackbar z-order above Mini | §8.5/§8.15 | Owner |
| UX-P11 | Adaptive specifics: 600 dp max-width/2-col, 840 dp side-by-side player + nav rail | §7.6 | FR-29 delegated here |
| UX-P12 | Repeat-error copy rotation after 2nd failure; share raw-URL context item; Liked Songs reverse-chron; history day-grouping; restored-session hint copy | §4/§5/§6.8/§6.9/§8.13 | Owner |

**Genuine open questions carried forward (not resolvable at UX phase):**
- OQ-1 transport outcome determines Home Feed depth (designed for both branches — §6.1).
- OQ-6 decides whether Quality chip ships (designed as removable module — §8.8).
- OQ-5 confirms recents-swipe behavior (UX assumes continuation, FR-21).
- Architecture owns duck-vs-pause policy (FR-19) — UX parity requirements stated in §6.13.

---

## 15. Traceability matrix (surfaces/states → requirements)

| Surface / state | Requirements |
|---|---|
| Nav shell, tabs, push model | FR-26, FR-40, NFR-4 |
| Home (all states) | FR-3, FR-4, FR-37, FR-38, NFR-1; UJ-1, UJ-5 |
| Search (groups, pagination, zero-match, fallback) | FR-1, FR-2, FR-4, FR-37; UJ-1 |
| Album / Artist / Catalog Playlist details | FR-5, FR-6, FR-7, FR-22, FR-11, FR-37 |
| Playlist detail/edit, picker, dialogs | FR-31, FR-32, FR-22; UJ-3 |
| Liked Songs | FR-30, FR-33 |
| Play History | FR-34, FR-33; UJ-4 |
| Library hub | FR-33, FR-38, FR-40; UJ-3, UJ-5 |
| Settings / About | FR-39, FR-40, FR-15 |
| Mini Player (presence, sync, expand) | FR-27, FR-16, FR-25, FR-14, NFR-6 |
| Full Player (controls, like, transition) | FR-28, FR-9, FR-10, FR-11, FR-15, FR-30, NFR-6; UJ-1, UJ-3 |
| Queue sheet | FR-23, FR-24, FR-14, FR-22; UJ-3, UJ-4 |
| Offline banner / stale badges | FR-4, FR-38; UJ-5 |
| Typed Error State system | FR-37, FR-14, NFR-2 |
| Artwork pipeline & placeholder | FR-35, FR-36 |
| Artwork Surface theming + scrim | NFR-5, SM-C2; glossary Artwork Surface |
| Notification / lock screen parity | FR-17, FR-18, FR-19, FR-20, FR-21 |
| Session restore UX | FR-25, NFR-4; UJ-4 |
| Motion/perf guardrails | NFR-1, NFR-6; SM-C2 |

*End of specification. Next chain step: `bmad-architecture` (consumes this + PRD), then `bmad-create-epics-and-stories`.*

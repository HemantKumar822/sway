# Sprint Status — Sway v1 (project: Player)

- **generated:** 08-23-2026 (headless sprint-planning run)
- **project:** Player — Sway, independent Android music client, v1
- **tracking_system:** file-system
- **story_location:** `_bmad-output/implementation-artifacts/` (story files land here as created)
- **source of truth:** `_bmad-output/planning-artifacts/epics-and-stories.md` (+ prd.md, ux-design-specification.md, architecture.md)
- **readiness gate:** PASS on 08-23-2026. One mechanical fix applied upstream: E8 story points sum to 20 and the plan total is 260 (doc previously said 18 / 258). No other defects.

## How the implementation agent must use this file

1. **Pick the first `todo` story whose dependencies are all `done`,** in build order (top to bottom). Never start a story before its dependencies are done.
2. Before starting: create the story file per `bmad-build`; set the story's status to `in-progress` here; if its epic is still `todo`, flip the epic to `in-progress` too.
3. When implementation + tests pass the story's Acceptance Criteria: set status to `review`, run code review in fresh context; after approval set `done`.
4. **Record evidence links:** append a line under `## Evidence log` for every completed story — links to the PR/commit, test-run output or artifact (e.g., macrobenchmark report, SM-2 suite record). Stories claiming FR/NFR completion are not done without evidence.
5. Re-run `bmad-sprint-planning` after any epics-file change to refresh this file; never hand-edit IDs/points — status values only.
6. Status vocabulary used here: `todo` (= template's `backlog`) → `ready-for-dev` → `in-progress` → `review` → `done`. Epics: `todo` / `in-progress` / `done`. Retrospectives: `optional`.

## Sprint summary

| Metric | Value |
|---|---|
| Epics | 15 |
| Stories | 62 |
| Total points | 260 |
| Started | 0 stories / 0 points |
| Remaining | 62 stories / 260 points |
| Recommended first story | **1.1** |

Point distribution: E1=7 · E2=11 · E3=25 · E4=21 · E5=16 · E6=11 · E7=16 · E8=20 · E9=24 · E10=30 · E11=14 · E12=21 · E13=10 · E14=23 · E15=11

## Development status

### Epic 1 — Workspace & Quality Gates (7 pts) — completes NFR-7, NFR-8
| ID | Title | Pts | Deps | Completes | Status |
|---|---|---|---|---|---|
| 1.1 | Gradle workspace & seven-module skeleton | 3 | — | NFR-7/8 substrate (AR-1, AR-13) | done |
| 1.2 | Hilt graph & startup hygiene | 2 | 1.1 | traces AR-3, AR-9, NFR-1 substrate | done - Hilt graph verified (AppGraphTest + StartupHygieneTest green) |
| 1.3 | Mechanical law CI | 2 | 1.1 | **NFR-7, NFR-8** | done - mechanical laws green (isolation + single-stack, LOC, edge audit) |
epic-1-retrospective: optional

### Epic 2 — Domain Model & Ports (11 pts) — enabler
| ID | Title | Pts | Deps | Completes | Status |
|---|---|---|---|---|---|
| 2.1 | Catalog models & identity law | 3 | 1.1 | AR-8 blank-id law | done |
| 2.2 | SwayResult & SwayError taxonomy | 3 | 1.1 | NFR-2 substrate | todo |
| 2.3 | ArtworkRef & candidate chain | 2 | 1.1 | FR-35/36 substrate | todo |
| 2.4 | Ports & playback vocabulary | 3 | 2.1, 2.2 | CatalogSource + StreamResolver ports | todo |
epic-2-retrospective: optional

### Epic 3 — Catalog Adapter: NewPipe Behind Ports (25 pts) — enabler
| ID | Title | Pts | Deps | Completes | Status |
|---|---|---|---|---|---|
| 3.1 | Extractor bootstrap & OkHttp downloader | 3 | 1.1, 2.2 | AR-2, AR-4 | todo |
| 3.2 | Four-type search mappers | 5 | 3.1 | trace FR-1 data side | todo |
| 3.3 | Album detail mapper | 3 | 3.1 | trace FR-5 data side | todo |
| 3.4 | Artist detail mapper | 3 | 3.1 | trace FR-6 data side | todo |
| 3.5 | Catalog Playlist detail mapper | 3 | 3.1 | trace FR-7 data side | todo |
| 3.6 | NewPipeStreamResolver | 8 | 3.1, 2.4 | C-5/C-6 substrate; FR-15 selection rule | todo |
epic-3-retrospective: optional

### Epic 4 — Playback Engine: One-Song Core Loop (21 pts)
| ID | Title | Pts | Deps | Completes | Status |
|---|---|---|---|---|---|
| 4.1 | SwayPlaybackService skeleton | 5 | 1.1 | NFR-10 contribution | todo |
| 4.2 | PlayerConnection facade & PlayerUiState | 5 | 4.1 | FR-27 sync substrate | todo |
| 4.3 | Queue builder & placeholder scheme | 3 | 2.4, 4.2 | FR-22 semantics substrate | todo |
| 4.4 | First-resolve path & just-in-time transitions | 8 | 4.3, 3.6 | **FR-12**; FR-8 engine evidence | todo |
epic-4-retrospective: optional

### Epic 5 — Stream Resilience: Expiry Defense & Watchdog (16 pts)
| ID | Title | Pts | Deps | Completes | Status |
|---|---|---|---|---|---|
| 5.1 | Audio-quality preference & SettingsRepository birth | 3 | 2.4 | **FR-15** (chip trace 12.4) | todo |
| 5.2 | Read-time validation layer | 3 | 4.4, 3.6 | NFR-3 layer 1 | todo |
| 5.3 | Error-triggered renewal with position resume | 5 | 5.2 | **FR-13**; SM-2 suite | todo |
| 5.4 | Stalled-playback watchdog | 5 | 4.4 | **FR-14**; NFR-3 layers complete across 5.2–5.4 | todo |
epic-5-retrospective: optional

### Epic 6 — Background Playback & System Integration (11 pts)
| ID | Title | Pts | Deps | Completes | Status |
|---|---|---|---|---|---|
| 6.1 | Media notification, lock screen & background continuity | 5 | 4.1, 4.2 | **FR-16, FR-17, FR-18** | todo |
| 6.2 | Audio focus & route-change compliance | 3 | 4.1 | **FR-19, FR-20** | todo |
| 6.3 | Recents-swipe posture & notification permission | 3 | 6.1 | **FR-21** (OQ-5-gated, P-3 default) | todo |
epic-6-retrospective: optional

### Epic 7 — Queue Management & Playback Session Persistence (16 pts)
| ID | Title | Pts | Deps | Completes | Status |
|---|---|---|---|---|---|
| 7.1 | Queue command semantics | 5 | 4.3, 4.4 | FR-22/23/24 engine substrate | todo |
| 7.2 | Modes persistence | 3 | 7.1, 5.1 | FR-11 persistence substrate | todo |
| 7.3 | Session persistence & paused restore | 8 | 7.1, 7.2 | **FR-25**; Room DB born (migration 1) | todo |
epic-7-retrospective: optional

### Epic 8 — Owned Data Layer: Likes, Playlists, History (20 pts) — enabler
| ID | Title | Pts | Deps | Completes | Status |
|---|---|---|---|---|---|
| 8.1 | Likes schema & LibraryRepository | 5 | 7.3 | FR-30 persistence substrate (migration 2) | todo |
| 8.2 | Playlists schema & PlaylistRepository | 5 | 8.1 | FR-31/32 substrate (migration 3) | todo |
| 8.3 | History schema, recency upsert & service-side recording hook | 5 | 8.1 | FR-34 substrate (migration 4) | todo |
| 8.4 | Offline Fallback Cache store | 5 | 2.2 | FR-4 substrate; C-8 | todo |
epic-8-retrospective: optional

### Epic 9 — Design Language & Navigation Shell (24 pts)
| ID | Title | Pts | Deps | Completes | Status |
|---|---|---|---|---|---|
| 9.1 | SwayTheme tokens on M3 Expressive | 5 | 1.1 | UX-DR1–4 | todo |
| 9.2 | Typed-state kit & core components | 8 | 9.1 | UX-DR5–7, 11, 15; FR-37 kit substrate | todo |
| 9.3 | Navigation shell | 5 | 9.1 | **FR-26** | todo |
| 9.4 | Startup law & offline launch routing | 3 | 9.3 | **NFR-1**; FR-38 substrate | todo |
| 9.5 | Home Search-first landing | 3 | 9.4, 8.1 | **FR-3** (degraded branch per OQ-1) | todo |
epic-9-retrospective: optional

### Epic 10 — Discovery: Search & Catalog Details (30 pts)
| ID | Title | Pts | Deps | Completes | Status |
|---|---|---|---|---|---|
| 10.1 | CatalogRepository & fallback integration | 5 | 3.2–3.5, 8.4 | NFR-2 pattern exemplar | todo |
| 10.2 | Search screen core | 5 | 10.1, 9.2, 9.3 | **FR-1** | todo |
| 10.3 | Search pagination | 3 | 10.2 | **FR-2** | todo |
| 10.4 | Offline/stale search UX | 3 | 10.2 | **FR-4** | todo |
| 10.5 | Album detail screen | 5 | 10.1 | **FR-5** | todo |
| 10.6 | Artist detail screen | 3 | 10.1 | **FR-6** | todo |
| 10.7 | Catalog Playlist detail screen | 3 | 10.1 | **FR-7** | todo |
| 10.8 | Song context menu | 3 | 10.2, 7.1, 8.1, 8.2 | FR-24 menu surface trace; FR-30 toggle trace | todo |
epic-10-retrospective: optional

### Epic 11 — Library Surfaces & Collection Editing (14 pts)
| ID | Title | Pts | Deps | Completes | Status |
|---|---|---|---|---|---|
| 11.1 | Liked Songs screen | 3 | 8.1, 9.2 | FR-30 collection surface (sync completes 12.2) | todo |
| 11.2 | Play History screen | 3 | 8.3, 9.2 | **FR-34** | todo |
| 11.3 | Playlist detail & editor | 5 | 8.2, 9.2, 10.8 | **FR-32** | todo |
| 11.4 | Library hub aggregation | 3 | 11.1–11.3 | **FR-31, FR-33** | todo |
epic-11-retrospective: optional

### Epic 12 — Player Surfaces: Mini, Full, Queue Sheet (21 pts)
| ID | Title | Pts | Deps | Completes | Status |
|---|---|---|---|---|---|
| 12.1 | Mini Player global layer | 5 | 7.3, 9.3, 4.2 | **FR-27** | todo |
| 12.2 | Full Player | 8 | 12.1, 7.1, 7.2, 8.1 | **FR-9, FR-10, FR-11, FR-28, FR-30** | todo |
| 12.3 | Queue sheet | 5 | 12.1, 12.2, 7.1 | **FR-23, FR-24** | todo |
| 12.4 | Cross-surface wiring & quality presentation | 3 | 10.x, 11.x, 12.1–12.3 | **FR-8, FR-22**; FR-15 chip (OQ-6 flag) | todo |
epic-12-retrospective: optional

### Epic 13 — Artwork System & Visual Atmosphere (10 pts)
| ID | Title | Pts | Deps | Completes | Status |
|---|---|---|---|---|---|
| 13.1 | Image pipeline, caching & placeholder stability | 5 | 2.3, 9.2 | **FR-35, FR-36** | todo |
| 13.2 | Extraction, scrim engine & atmosphere | 5 | 13.1, 12.2 | **NFR-5** | todo |
epic-13-retrospective: optional

### Epic 14 — Honesty Pass: Typed States, Offline & Hardening (23 pts)
| ID | Title | Pts | Deps | Completes | Status |
|---|---|---|---|---|---|
| 14.1 | Surface × failure audit | 5 | E10–E13 | **FR-37, NFR-2** | todo |
| 14.2 | Offline mode end-to-end | 5 | 14.1 | **FR-38** | todo |
| 14.3 | Continuity & resource soak suites | 5 | E12/E13 | **NFR-4, NFR-10** | todo |
| 14.4 | Performance budget gate | 5 | E12/E13, 9.4 | **NFR-6**; NFR-1 regression | todo |
| 14.5 | Adaptive compliance matrix | 3 | E10–E12 | **FR-29** | todo |
epic-14-retrospective: optional

### Epic 15 — Settings, About & Release Readiness (11 pts)
| ID | Title | Pts | Deps | Completes | Status |
|---|---|---|---|---|---|
| 15.1 | Settings screen | 3 | 9.3, 5.1 | **FR-39** | todo |
| 15.2 | About & licenses | 3 | 15.1 | **FR-40** | todo |
| 15.3 | Release readiness gate | 5 | 14.x records, 15.2 | **NFR-9**; SM-1/SM-2 evidence pack; OQ-7 owner action | todo |
epic-15-retrospective: optional

## Coverage note
Every FR-1..40 and NFR-1..10 has exactly one completing epic/story (see epics-and-stories.md coverage maps); "trace" rows contribute without claiming completion. UJ-1..UJ-5, C-1..C-8 and SM-1..SM-3 evidence maps live in the same file.

## Evidence log
(appended by the implementation agent; one line per completed story)
- 2.1 done — :core:model pure-Kotlin compileKotlin + 65 JVM tests green (SourceId/DurationMs/ArtworkRef/Song/Album/Artist/CatalogPlaylist/Playlist/title), zero Android imports, AR-8 blank-id law + AR-14 conventions — commit feat(2.1) closes #4

## Action items
(none — created by retrospectives)

## GitHub issue map

Imported 2026-08-23 via gh CLI into HemantKumar822/sway. One issue per story; epics are tracked as milestones E1-E15. Audit manifest: `_bmad-output/implementation-artifacts/github-import-manifest.json`.

| Story | Issue |
|---|---|
| 1.1 | https://github.com/HemantKumar822/sway/issues/1 |
| 1.2 | https://github.com/HemantKumar822/sway/issues/2 |
| 1.3 | https://github.com/HemantKumar822/sway/issues/3 |
| 2.1 | https://github.com/HemantKumar822/sway/issues/4 |
| 2.2 | https://github.com/HemantKumar822/sway/issues/5 |
| 2.3 | https://github.com/HemantKumar822/sway/issues/6 |
| 2.4 | https://github.com/HemantKumar822/sway/issues/7 |
| 3.1 | https://github.com/HemantKumar822/sway/issues/8 |
| 3.2 | https://github.com/HemantKumar822/sway/issues/9 |
| 3.3 | https://github.com/HemantKumar822/sway/issues/10 |
| 3.4 | https://github.com/HemantKumar822/sway/issues/11 |
| 3.5 | https://github.com/HemantKumar822/sway/issues/12 |
| 3.6 | https://github.com/HemantKumar822/sway/issues/13 |
| 4.1 | https://github.com/HemantKumar822/sway/issues/14 |
| 4.2 | https://github.com/HemantKumar822/sway/issues/15 |
| 4.3 | https://github.com/HemantKumar822/sway/issues/16 |
| 4.4 | https://github.com/HemantKumar822/sway/issues/17 |
| 5.1 | https://github.com/HemantKumar822/sway/issues/18 |
| 5.2 | https://github.com/HemantKumar822/sway/issues/19 |
| 5.3 | https://github.com/HemantKumar822/sway/issues/20 |
| 5.4 | https://github.com/HemantKumar822/sway/issues/21 |
| 6.1 | https://github.com/HemantKumar822/sway/issues/22 |
| 6.2 | https://github.com/HemantKumar822/sway/issues/23 |
| 6.3 | https://github.com/HemantKumar822/sway/issues/24 |
| 7.1 | https://github.com/HemantKumar822/sway/issues/25 |
| 7.2 | https://github.com/HemantKumar822/sway/issues/26 |
| 7.3 | https://github.com/HemantKumar822/sway/issues/27 |
| 8.1 | https://github.com/HemantKumar822/sway/issues/28 |
| 8.2 | https://github.com/HemantKumar822/sway/issues/29 |
| 8.3 | https://github.com/HemantKumar822/sway/issues/30 |
| 8.4 | https://github.com/HemantKumar822/sway/issues/31 |
| 9.1 | https://github.com/HemantKumar822/sway/issues/32 |
| 9.2 | https://github.com/HemantKumar822/sway/issues/33 |
| 9.3 | https://github.com/HemantKumar822/sway/issues/34 |
| 9.4 | https://github.com/HemantKumar822/sway/issues/35 |
| 9.5 | https://github.com/HemantKumar822/sway/issues/36 |
| 10.1 | https://github.com/HemantKumar822/sway/issues/37 |
| 10.2 | https://github.com/HemantKumar822/sway/issues/38 |
| 10.3 | https://github.com/HemantKumar822/sway/issues/39 |
| 10.4 | https://github.com/HemantKumar822/sway/issues/40 |
| 10.5 | https://github.com/HemantKumar822/sway/issues/41 |
| 10.6 | https://github.com/HemantKumar822/sway/issues/42 |
| 10.7 | https://github.com/HemantKumar822/sway/issues/43 |
| 10.8 | https://github.com/HemantKumar822/sway/issues/44 |
| 11.1 | https://github.com/HemantKumar822/sway/issues/45 |
| 11.2 | https://github.com/HemantKumar822/sway/issues/46 |
| 11.3 | https://github.com/HemantKumar822/sway/issues/47 |
| 11.4 | https://github.com/HemantKumar822/sway/issues/48 |
| 12.1 | https://github.com/HemantKumar822/sway/issues/49 |
| 12.2 | https://github.com/HemantKumar822/sway/issues/50 |
| 12.3 | https://github.com/HemantKumar822/sway/issues/51 |
| 12.4 | https://github.com/HemantKumar822/sway/issues/52 |
| 13.1 | https://github.com/HemantKumar822/sway/issues/53 |
| 13.2 | https://github.com/HemantKumar822/sway/issues/54 |
| 14.1 | https://github.com/HemantKumar822/sway/issues/55 |
| 14.2 | https://github.com/HemantKumar822/sway/issues/56 |
| 14.3 | https://github.com/HemantKumar822/sway/issues/57 |
| 14.4 | https://github.com/HemantKumar822/sway/issues/58 |
| 14.5 | https://github.com/HemantKumar822/sway/issues/59 |
| 15.1 | https://github.com/HemantKumar822/sway/issues/60 |
| 15.2 | https://github.com/HemantKumar822/sway/issues/61 |
| 15.3 | https://github.com/HemantKumar822/sway/issues/62 |

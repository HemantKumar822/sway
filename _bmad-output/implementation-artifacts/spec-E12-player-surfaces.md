---
title: 'Epic E12 - Player Surfaces: Mini, Full, Queue Sheet (Stories 12.1-12.4)'
type: 'feature'
created: '2026-08-24'
status: 'done'
review_loop_iteration: 1
baseline_commit: 0329ef5
context:
  - '{project-root}/_bmad-output/planning-artifacts/epics-and-stories.md (Epic E12; Stories 12.1-12.4)'
  - '{project-root}/_bmad-output/planning-artifacts/ux-design-specification.md §3.3-3.5, §8.5-8.8 (DR8/DR9/DR10)'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-10-4-to-10-8-e10-completion-push.md (PlaybackRequests contract)'
---

## Epic Intent

Playback made visible and touchable everywhere. **FR-27 completes here (12.1)**; **FR-9/FR-10/FR-11/FR-28/FR-30 complete here (12.2)**; **FR-23/FR-24 complete here (12.3)**; **FR-8/FR-22 complete here (12.4)** + OQ-6-gated quality chip [EP-8 default-on pending veto]. Extracted-color backdrop is E13's upgrade behind the same API.

## Substrate Recon (verified against source)

| Need | API | Notes |
|---|---|---|
| Facade | `PlayerConnection` (:playback) — `uiState: StateFlow<PlayerUiState>`, `positionFlow()` scrubber-scoped ticks @200ms, full command layer `setQueue/jumpTo/removeAt/playNext/addToQueue/clearQueue/moveQueueItem/setShuffleEnabled/cycleRepeatMode/seekTo/next/previous`, `setFailedTrack` slot | `attachSettings`/`attachSessionStore` are `internal` — made public this epic (their KDocs already name 12.1 as consumer); `connect()` builds MediaController async and swallows Robolectric build failures (honest no-session degradation under unit tests) |
| Queue build | `QueueBuilder.fromCollection(songs, k)` / `.shuffled(context, first, seed)` -> `BuiltQueue` -> `connection.setQueue(built)` | `PlaybackRequest` (DetailModels.kt) already carries pre-permuted items for Shuffled mode — feed `fromCollection` verbatim, do NOT re-shuffle or touch the session shuffle flag |
| Restore | `SessionRestoreRepository.loadRestoredSession()` + `PlayerConnection.attachSessionStore` lands PAUSED at saved moment, never auto-plays (7.3 law) | MainActivity's label-only restore hook upgrades to the real hook |
| Mode persistence | `attachSettings(SettingsRepository)` restores shuffle mirror + write-through on toggle; repeat restored service-side pre-queue-build | settings repo = `SettingsDataStore.create(context)` (5.1 factory), not yet an AppDataGraph member |
| Like truth | `graph.library.observeLiked()` already collected in MainActivity; menu toggles already write | heart = same flow read; bidirectional <=250ms proof at UI layer |
| Quality | `SettingsRepository.audioQuality/setAudioQuality` (FR-15 completed 5.1); `Quality` enum in core:model | chip gated by `QualityChipPolicy` const true [EP-8] |
| Optimistic Mini | `setQueue` publishes `currentItem` synchronously BEFORE controller round-trip (PlayerConnection.kt L267-270) | one-frame materialization is structural |

## Story Designs

### 12.1 Mini Player global layer (`app/screens/player/MiniPlayerBar.kt`, `app/playback/SwayPlaybackHost.kt`)
DR8 anatomy parameterized composable (state+callbacks only): 48 dp thumb `{rounded.sm}` via ArtworkPlaceholder, 1-line title/artist, play/pause + next 48 dp hit areas, queue glyph affordance (12.3 entry), full-width 2 dp determinate hairline (position/duration) pulsing while buffering (indeterminate alpha pulse), failed-track error chip (mapped SwayErrorUiState reason + title), swipe-down hides bar only (host-owned hidden flag; audio persists by construction). NO scrubbing [UX-P10]. Host owns the PlayerConnection lifecycle (`connect/attachSettings/attachSessionStore` post-composition) + hide state + expand event; degrades to Idle when the session cannot bind (Robolectric-safe). SwayNavHost gains a `miniPlayer` slot rendered above NavigationBar inside bottomBar so presence is global across ALL tabs. Tests: anatomy/presence-across-tabs (identical state renders identically per tab param), restored-paused first frame (track shown, never playing), failed chip, swipe-hide callback + audio-persists host law, latency harness emission->reflection p95 <=250 ms.

### 12.2 Full Player (`app/screens/player/FullPlayerScreen.kt`)
Overlay surface (not a nav destination — collapse can never lose state): container-transform expand/collapse via Animatable progress driving scrim+content slide/fade with named `PLAYER_TRANSFORM_MS=280` tween (< NFR-6 300 ms cap by construction; gesture-interruptible: drag snaps progress, release retargets from current value both directions — deviation note: capped emphasized tween chosen over spring so the hard bound is mechanically provable; springs reserved for heart pop). Artwork ~92vw rounded-xl over flat brand backdrop (extraction slots into E13 API), double-tap = like [UX-P9]; title/headline + artist·album + rose heart with pressSpec pop; scrubber = M3 Slider w/ drag-grow thumb + live time bubble while dragging, release applies seek, elapsed/remaining tnum, position fed from ONE scoped positionFlow collector shared with the Mini hairline; transport cluster shuffle pill / prev (A-4 neutral passthrough) / play 72 dp / next / repeat cycling badge "1"; secondary row Queue + Quality(12.4). Collapse via chevron/back/swipe-down. Tests: transform duration frames <=300 p95 + interruption retarget both ways, seek display +/-1 s of applied value, heart membership flip <=250 ms bidirectional (same flow source both surfaces), repeat badge "1" + shuffle pill states, prev neutrality (no visual trickery — plain callback).

### 12.3 Queue sheet (`app/screens/player/QueueSheet.kt`)
ModalBottomSheet from Mini glyph + Full affordance + SongContextMenu "Open queue" [PROVISIONAL] (new action appended in visibleActions law). Now-playing row pinned highlighted (primary); Next-up rows thumb/handle/X; tap-row jump <=2 s (engine ceiling proven 7.1; sheet asserts request); remove-playing advances (engine removeAt semantics); reorder = move-up/move-down controls per DR10 AT-alternative precedent (touch-drag deferred to device matrix like 11.3; haptics land there too); Clear w/ confirmation "Clear the queue? This can't be undone."; TalkBack contentDescription "{title}, {k} of {n}"; dismissal swipe-down/back/scrim. Queue list = facade snapshot passed down; mutations refresh host state atomically. Tests: jump request, remove-playing advance request, clear confirm/decline, move controls reorder + persistence through auto-transition (state integrity via host snapshot), announcements text, current-row highlight follows new current.

### 12.4 Cross-surface wiring & quality presentation (`MainActivity.kt`, `app/playback/QualitySheet.kt`)
ALL `onPlaybackRequest = {}` stubs replaced: handler = `QueueBuilder.fromCollection(items, startIndex)` -> `setQueue(built)` -> `play()`; search song tap builds context from the Songs group at tapped index (FR-22 matrix entry); history replay rides its single-song contract; optimistic Mini materialization structural (synchronous currentItem publish). Eight-entry wiring matrix suite: search-row, album, artist-rails, catalog-playlist, liked, playlist-editor, history, hub — each asserts context correctness (items order + startIndex + shuffled flag) through the real PlaybackRequests builders. Quality chip + modal selector sheet (OQ-6 gate `QualityChipPolicy.ENABLED=true` default-on pending veto): AUTO/LOW/MEDIUM/HIGH plain-language lines + helper "Applies from your next song." (honest FR-15 timing), persists via SettingsRepository (5.1 path), veto flip removes every reference (policy test proves zero-render). Tap-to-audio <=3 s p95 remains the device-gated fr8TapToAudio harness (recorded trace per 10.x honesty pattern).

## Wiring

SwayNavHost: new `miniPlayer` slot above the NavigationBar (below sheets/snackbar per z-order law). MainActivity: hosts `SwayPlaybackHost`, collects uiState + ONE positionFlow subscription feeding hairline + scrubber, expands FullPlayer overlay + QueueSheet, feeds like-truth + quality flows, wires all eight matrix entries + context-menu OPEN_QUEUE/PLAY_NEXT/ADD_TO_QUEUE commands to the facade.

## Verification Plan (one epic gate)

DONE — :app 130 tests green (+48 new suites listed in the sprint-status evidence block); :playback 134 unchanged green (only the two designed hooks opened); :core:data 51 green (graph gains settings member); repo total 598 / 0 failures; five audits exit 0; assembleDebug OK. Notable: Robolectric media3-binder boundary handled via sway.sessionBinding=off in testOptions; drag-release collapse always notifies owner (prod bug caught by gesture test).

Non-negotiables unchanged: NFR-2 typed results, AR-14 stable keys, theme-import law (roles only), AD-6 tick scoping, NFR-7 <1000 LOC/file, no empty-as-success.

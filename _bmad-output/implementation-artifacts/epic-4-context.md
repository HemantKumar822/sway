# Epic 4 Context: Playback Engine - One-Song Core Loop

<!-- Compiled from planning artifacts. Edit freely. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Make audio actually play under service ownership: any play action builds a context queue from placeholder metadata, exactly ONE stream resolution happens up front (for the start item), and every subsequent track resolves just-in-time at transition under a single-flight guard - proven with a counting resolver test double before any UI exists. This epic delivers the engine-level proof of fast first-audio (<=3 s p95) and completes the lazy-resolution requirement; every later playback effort (stream resilience, background/system integration, queue and session management, player surfaces) stacks directly on this loop.

## Stories

- Story 4.1: SwayPlaybackService skeleton *(done)*
- Story 4.2: PlayerConnection facade & PlayerUiState *(done)*
- Story 4.3: Queue builder & placeholder scheme
- Story 4.4: First-resolve path & just-in-time transitions

## Requirements & Constraints

- **Lazy-resolution budget (FR-12, completes here in 4.4):** playing into an N-item queue costs exactly ONE up-front stream resolution - the start item's. Every other item holds a Source-ID placeholder URI and resolves just-in-time. Verified mechanically: a resolver test double counts exactly one up-front resolve across queue build plus several auto-transitions (e.g. 8-item queue started at index 2 with two transitions gives 3 total resolves).
- **Fast first audio (FR-8 engine-level evidence recorded here; surface-level completion belongs to the player-surfaces epic):** controller-commanded play of fixture audio begins <=3 s p95 on the Baseline Device profile, measured in an instrumented harness.
- **Queue-from-context semantics (FR-22 substrate; completion elsewhere):** every play action snapshots its context into a queue starting at the chosen item; a shuffle entry produces a deterministically shuffled order that still preserves the chosen current item.
- **Typed failures everywhere:** resolve failures surface as typed error categories through the facade's state flow - never crashes, never empty-as-failure. A `failedTrack` slot exists in player state now, reserved for the resilience epic's skip-with-typed-reason behavior.
- **Background continuity substrate:** nothing here may couple playback to UI presence - auto-advance/transitions must work with zero clients bound.
- **Resource discipline (NFR-10 contribution):** the service self-stops when idle and released; no zombie service survives a stop/release.
- **Out of scope:** notification polish; expiry-defense/watchdog layers; shuffle/repeat modes and their persistence; session persistence/restore; all UI.

## Technical Decisions

- **Service owns the only player.** `SwayPlaybackService` extends MediaLibraryService in `:playback`; ExoPlayer is built in `onCreate` with music AudioAttributes, focus handling enabled, becoming-noisy enabled, and network wake mode. App manifest registers it with `foregroundServiceType=mediaPlayback`. Stack pin: Media3 1.11.0.
- **UI talks exclusively through the `PlayerConnection` facade** wrapping one long-lived MediaController: command methods (setQueue/play/pause/seekTo/jump/next/previous, toggleModes as placeholders) plus one hoisted `StateFlow<PlayerUiState>` (isPlaying, current-item snapshot, buffering flag, `failedTrack` slot). Controller lifecycle is rebind-safe: disconnect/reconnect resubscribes without leaking controllers.
- **Position ticks are scoped:** `positionMs` publishes ONLY while an active scrubber collector subscribes - no app-wide ticking broadcast.
- **Placeholder scheme:** `sway://pending/<sourceId>` is defined in exactly ONE object in `:playback`; no other module may construct, mutate, or string-sniff placeholders (grep-audited; API kept private to its owner). Queue metadata travels as `QueueSnapshot`/`QueueItem` value types from `core:model`.
- **First-resolve path:** `onSetMediaItems` resolves only the START item (all others get placeholder URIs), then prepare + play at startIndex. `onMediaItemTransition` detects a placeholder -> single-flight guard -> resolve -> replaceMediaItem. Optional opportunistic prefetch of the next item during playback is age-capped, returns null silently on failure, never counts against the up-front budget, never replaces items mid-shuffle, and is skipped when repeat-one is set (the mode flag arrives with the queue-management epic - code the guard hook now). A failed start resolve surfaces the typed error via the PlayerUiState slot instead of crashing.
- **StreamResolver contract** (ports live in `core:model`; the real implementation is the catalog adapter's NewPipeStreamResolver): suspend `resolveAudio(trackId, AudioRequest)` returning `SwayResult<ResolvedAudio>`, plus `invalidate(trackId)` and `prefetchNext(...)` returning null silently. ResolvedAudio carries url, expiresAtEpochMs parsed from the URL's own expiry parameter (never guessed), bitrateKbps, container hint, backend tag, rendition cache key; identical concurrent resolves dedup invisibly to callers. Quality selection is bitrate-target based: best-under-target-else-max; AUTO maps unmetered to MEDIUM-class, metered to LOW-class targets.
- **Expiry defense layers belong to the next epic** (read-time validation with -5 min margin, 403/410 renewal resuming within +/-3 s, 3 s/15 s stall watchdog ladder). Build only the substrate hooks they need now - notably, the prefetch age cap folds into their single read-time check rather than adding a second mechanism.
- **Test posture:** Robolectric service-lifecycle and facade suites (including a <=250 ms state-sync latency measurement harness and tick-scoping assertions), pure JVM queue-builder tests with shuffle-determinism seeds, resolver-double state-machine tests, instrumented tap-to-audio timing harness.

## UX & Interaction Patterns

- **State-sync discipline ("playback is sacred"):** navigation, network failure, and process death never interrupt audio; every future surface reflects playback truth within 250 ms regardless of origin. Achieved by hoisting player state through one shared StateFlow, keeping derived reads minimal, and scoping position ticks to scrubber subscribers only.
- **Failed-track surfacing is reserved, not built here:** the typed failed-track slot later feeds the dimmed/strike failed SongRow variant and Mini Player error chip.

## Cross-Story Dependencies

- **Chain inside the epic:** 4.1 service skeleton -> 4.2 facade -> 4.3 queue builder + placeholders -> 4.4 first resolve + transitions. 4.3 also depends on the core:model ports story (2.4, done); 4.4 additionally depends on the real NewPipeStreamResolver (3.6, done).
- **Current status:** 4.1 and 4.2 are DONE per sprint status (Robolectric suites green: music attributes/focus config, MediaController play-to-ready, idle self-stop, <=250 ms sync harness, tick scoping, rebind safety; commits closing issues #14/#15). Remaining work starts at 4.3.
- **Downstream consumers:** the resilience epic stacks its defense layers on 4.4 (read-time validation and watchdog both depend on it); background/system integration needs 4.1/4.2; full queue manipulation and session persistence extend 4.3/4.4 semantics; the player-surfaces epic completes FR-22/FR-8 using the engine proofs recorded here.

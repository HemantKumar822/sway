---
title: 'Story 4.4 - First-resolve path & just-in-time transitions'
type: 'feature'
created: '2026-08-23'
status: 'done'
review_loop_iteration: 0
baseline_commit: 54a68104e8de09b186bf2bb1bb4c7f8334a713b4
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-4-context.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Every queue entry currently rides a placeholder URI forever (`sway://pending/<id>`), so nothing can actually play: there is no up-front resolution for the chosen start track, no just-in-time resolution when playback auto-advances into an unresolved entry, and no typed-failure path when a resolve fails. FR-12's lazy-resolution budget (exactly ONE up-front resolve per queue) is unproven.

**Approach:** Add an internal `JitResolveEngine` in `:playback` owned by `SwayPlaybackService`: the session callback `onSetMediaItems` resolves ONLY the start item (all others keep placeholder URIs) before items land on the player; a player listener detects transitions onto placeholder URIs and resolves them just-in-time under a single-flight guard, swapping the resolved URL in place via `replaceMediaItem`. An optional, default-off, age-capped `prefetchNext` runs during playback (never when the repeat-one flag is set; flag setter arrives E7 — the guard hook is coded now). Resolve failures surface as typed `FailedTrack(item, SwayError)` values through a hoisted slot — never crashes.

## Boundaries & Constraints

**Always:**
- Up-front budget = exactly ONE `resolveAudio` per queue (the start item's); every other item resolves only at transition time (FR-12).
- Transition handling is single-flight: concurrent/duplicate transitions onto the same unresolved item coalesce into one `resolveAudio` call.
- Prefetch uses `prefetchNext` exclusively (never counts against the up-front budget), validates `ResolvedAudio.isExpiredAt(now)` before trusting a cached result, and is skipped entirely when the repeat-one flag is set.
- Failures travel as typed `SwayResult.Failure` -> `FailedTrack(QueueItem, SwayError)` values; no exception ever crosses out of the engine; the service stays alive.
- Placeholder scheme law (AD-6 rule 6): `PendingUri` stays the single internal owner; no scheme strings outside `:playback`.
- `:playback` stays Hilt-free; the `StreamResolver` reaches the service/engine via an injectable seam for tests (production wiring arrives in later epics).

**Ask First:** N/A — headless run; engineering decisions recorded in Design Notes.

**Never:** No UI/notification work, no Room persistence, no shuffle/repeat mode implementation beyond the boolean guard hook, no expiry-defense/watchdog layers (E5), no changes outside `:playback` except docs/sprint-status/spec files, no modification of `PendingUri`'s law or location.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Queue start (FR-12 proof) | 8-item snapshot, startIndex 2, counting double | Exactly 1 up-front resolve (item@2) before prepare+play; items 0..7 keep placeholders except @2 real URL | N/A |
| Two auto-transitions | playback advanced twice programmatically | Total `resolveCount == 3` (1 up-front + 2 JIT) | N/A |
| Happy-path transition | current item URI pending, resolver Success | `replaceMediaItem` swaps in real URL; item no longer pending afterwards | N/A |
| Rapid duplicate transitions | two transitions onto same unresolved item while first resolve in flight | Exactly one `resolveAudio` for that id (coalesced) | Second event skipped |
| Prefetched & fresh | cache holds unexpired `ResolvedAudio` for next id | Transition consumes cache; zero extra `resolveAudio`; budget untouched | N/A |
| Prefetched but stale | cached `expiresAtEpochMs` in past | Age cap rejects; fresh `resolveAudio` used instead | Stale entry discarded |
| Repeat-one flag set | guard hook true | `prefetchNext` never invoked (`prefetchedIds` stays empty) | N/A |
| Start-resolve failure | resolver Failure(Offline) on start item | `FailedTrack(item, Offline)` surfaced via failure slot -> facade `uiState.failedTrack`; original placeholders returned so queue still loads; no crash | Typed value, never throw |
| Transition-resolve failure | resolver Failure mid-queue | FailedTrack surfaces; placeholder left in place (retry possible on next transition); no crash | Typed value, never throw |
| Empty snapshot start | 0 items | No-op, no crash, no resolve | Never throws |
| Index drift | replace target located by mediaId scan, not captured index | Replacement hits correct item even if indices shifted | Skip if id vanished |

</frozen-after-approval>

## Code Map

- `playback/src/main/kotlin/com/sway/playback/JitResolveEngine.kt` -- NEW internal engine: start-swap resolution, transition listener + single-flight JIT loop, age-capped prefetch cache, repeat-one guard hook, pure decision helpers (`JitPolicy`) as testable functions.
- `playback/src/main/kotlin/com/sway/playback/SwayPlaybackService.kt` -- L100 `LibraryCallback` gains `onSetMediaItems` override delegating to the engine (returns swapped list future; originals on failure); `onCreate` builds engine behind an internal pre-onCreate resolver seam; internal typed-failure StateFlow; idle self-stop listener untouched.
- `playback/src/main/kotlin/com/sway/playback/PlayerConnection.kt` -- KDoc refresh only: `setQueueInternal` uniform-placeholder mapping now feeds the session-side 4.4 interception (no behavior change; zero-resolved-URLs proof stays valid).
- `playback/build.gradle.kts` -- androidTest deps for the tag-gated instrumented timing-harness placeholder (mirrors :catalog LiveSmoke precedent).
- `playback/src/test/kotlin/com/sway/playback/JitResolveEngineTest.kt` -- NEW Robolectric suite: FR-12 exactly-one proof, transition replacement, single-flight, age cap both directions, repeat-one guard, start/transition failures, empty-queue edge.
- `playback/src/test/kotlin/com/sway/playback/FirstAudioTimingHarnessTest.kt` -- NEW Robolectric p95 harness (interim FR-8 engine evidence; device evidence lands via instrumented placeholder).
- `playback/src/androidTest/kotlin/com/sway/playback/LiveTapToAudioSmokeTest.kt` -- NEW @Ignore-tagged instrumented harness placeholder (skipped without device, :catalog precedent).
- `_bmad-output/implementation-artifacts/sprint-status.md` -- row 4.4 + Evidence log entry at completion.

## Tasks & Acceptance

**Execution:**
- [x] `JitResolveEngine.kt` -- NEW: `resolveStartSwap(items, startIndex)` resolves ONLY the start item via `resolver.resolveAudio(SourceId, AudioRequest.Default)`, rebuilds that item's URI, returns swapped list; player-transition listener detects `PendingUri` current item -> single-flight coalescing -> `resolveAudio` -> `player.replaceMediaItem(indexByMediaId, rebuilt)`; optional `prefetchEnabled=false`-default opportunistic `prefetchNext` storing results validated by `isExpiredAt(now)` before reuse; `setRepeatOneRequested` hook (KDoc'd for E7); failures routed to injected `onFailure(FailedTrack)` handler + hoisted StateFlow.
- [x] `SwayPlaybackService.kt` -- wire engine: internal pre-onCreate `streamResolverForTest` seam (Hilt wiring deferred), engine built on Main-immediate scope cancelled in `onDestroy`, `LibraryCallback.onSetMediaItems` override returning `SettableFuture` of swapped/original items, failure StateFlow accessor; keep LOC well under budget.
- [x] `PlayerConnection.kt` -- refresh stale 4.2-era KDoc on `setQueue`/`setQueueInternal` describing the 4.4 session-side start-item swap.
- [x] `JitResolveEngineTest.kt` -- NEW: all I/O Matrix rows as Robolectric tests using `FakeStreamResolver` (+ gated suspend behavior for single-flight), teardown-registered players/scopes, main-looper idling.
- [x] `FirstAudioTimingHarnessTest.kt` -- NEW: N-run p95 command->playing-ready measurement, println-recorded, generous CI-safe assert bound.
- [x] `LiveTapToAudioSmokeTest.kt` (androidTest) -- NEW: @Ignore-tagged device-only tap-to-audio harness placeholder documenting manual steps (:catalog LiveSmoke precedent).

**Acceptance Criteria:**
- Given an 8-item queue started at index 2 with a counting resolver double, when playback begins and two auto-transitions occur, then the double counts exactly 3 total resolves (1 up-front + 2 JIT) - proving the up-front budget = 1 (FR-12).
- Given forced-expiry-free happy path, when play is commanded, then audio-ready latency is measured p95 in a harness: Robolectric interim numbers recorded here, instrumented tag-gated harness committed for device runs (FR-8 engine-level evidence completed by 12.4).
- When prefetchNext fires opportunistically, then it never counts against the up-front budget and its cached result is age-capped (expired -> discarded, fresh resolve happens) before any use.
- Given start-resolve failure (Offline), when handled, then PlayerUiState carries the typed SwayError category via failedTrack and no crash occurs (service stays alive).

## Spec Change Log

## Design Notes

- **Engine home:** resolution logic lives in one internal `JitResolutionEngine` class inside `:playback` (service owns the only player, AD-6 rule 1); `SwayPlaybackService` delegates to it and stays ~170 LOC. Pure decision helpers (pending-detection, age-cap check, index coercion, item rebuild) sit in an internal `JitPolicy` object for cheap unit testing.
- **Session interception vs direct path:** production flow is controller `setMediaItems(placeholders)` -> session `onSetMediaItems` -> engine swaps ONLY the start URI -> returned list lands on the player; prepare/play continue as separate forwarded commands (client-driven, matching 4.2 semantics). The engine additionally exposes a direct `startQueueAndPlay` used by tests/future E7 glue that performs resolve -> set -> prepare -> play in one step. Both share the exact same swap core.
- **Transition detection lives on a Player.Listener** attached by the engine (not on the session callback) so auto-advance works with ZERO controllers bound (background continuity, epic constraint). `replaceMediaItem` itself re-fires a transition event; the handler is idempotent because the rebuilt item's URI is no longer pending.
- **Single-flight:** one in-flight JIT job max, keyed by SourceId; duplicate transitions onto the same pending id while resolving are coalesced (exactly one `resolveAudio`). After each completion the loop re-checks the CURRENT item, absorbing drift between transition events.
- **Index safety:** replacements locate their target by mediaId scan over `player.mediaItemCount`, never by the index captured at event time (drift-proof).
- **Prefetch is default-OFF** (story marks it optional/opportunistic): enabled explicitly via an internal hook; FR-12 proof therefore counts cleanly. Cache is keyed by SourceId, consulted at transition time BEFORE deciding to resolve, and validated with `isExpiredAt(now)` (margin 0 for now - E5 folds the -5 min read-time margin into this single check point later). `prefetchedIds` on the fake grows independently of `resolveCount`, mechanically proving prefetch never counts against the budget.
- **Repeat-one guard hook:** private backing field + `setRepeatOneRequested(Boolean)` internal setter, KDoc'd as the E7 arrival point; when set, `maybePrefetch` short-circuits. No mode persistence/logic beyond this.
- **Failure surfacing:** engine emits `FailedTrack(item, error)` to an injected `onFailure` handler AND hoists the latest value on a StateFlow the service exposes internally; the test wires the handler into `PlayerConnection.setFailedTrack` (the exact glue production epics install) proving `uiState.failedTrack` carries the typed category. Start-failure additionally returns the ORIGINAL placeholder items from `onSetMediaItems` (queue still loads, nothing throws); transition-failure leaves the placeholder so a later transition may retry. Because the failed entry stays pending, the session's own playlist-changed event retries it exactly once more (bounded, still typed, no hot-loop) - the failure tests encode these counts deliberately.
- **Resolver injection:** `internal var streamResolverForTest` settable between Robolectric service construction and `.create()`; production graph arrives with later epics (Hilt-free rule respected).
- **Timing honesty:** under Robolectric nothing decodes, so the interim metric is command-acceptance latency (command issued -> playWhenReady && state >= BUFFERING), measured over 20 runs with p95 printed and asserted under a generous CI-safe ceiling; the committed instrumented placeholder documents the real device procedure for 12.4.

## Verification

**Commands:**
- `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat :playback:testDebugUnitTest` -- expected: prior 38 + new suites all green (exact count reported at completion)
- `"C:\Program Files\Git\bin\bash.exe" scripts/check_placeholder_scheme.sh` -- expected: exit 0
- `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat :app:assembleDebug` -- expected: BUILD SUCCESSFUL

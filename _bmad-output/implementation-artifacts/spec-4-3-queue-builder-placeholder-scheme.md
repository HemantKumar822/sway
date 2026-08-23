---
title: 'Story 4.3 - Queue builder & placeholder scheme'
type: 'feature'
created: '2026-08-23'
status: 'in-review'
review_loop_iteration: 0
baseline_commit: 87a684887c9686cebd59609c0d652defdb461ae4
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-4-context.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Play actions have no way to turn a listening context (song tap inside a list, album play, shuffle entry) into a queue-wide `QueueSnapshot`; lazy resolution (C-4/L4) needs every non-start item to ride a placeholder so nothing resolves up front, and the placeholder scheme lacks enforced single-owner protection.

**Approach:** Add a pure-JVM `QueueBuilder` in `:playback` with three variants (song tap, collection play, seeded shuffle that pins the chosen current item), tighten `PendingUri` ownership to module-internal visibility, and enforce the one-object scheme law with a repo grep-audit wired into CI. No stream resolution happens anywhere in this story.

## Boundaries & Constraints

**Always:**
- `sway://pending/<sourceId>` remains defined in exactly ONE object (`PendingUri`) in `:playback`; its API becomes `internal` so only `:playback` code can construct/mutate placeholders.
- Shuffle must be deterministic: same `(items, chosen, seed)` triple always yields the identical order, and the chosen item always plays first (startIndex 0).
- Builder stays free of Android imports and never calls any resolver; output is `QueueSnapshot` + `startIndex` only.
- Queue metadata travels as `QueueSnapshot`/`QueueItem` value types from `core:model` (blank-id law AR-8 applies).

**Ask First:** If wiring the grep-audit into `.github/workflows/android-ci.yml` proves impossible without changing CI semantics beyond adding one step.

**Never:** No resolver calls, no `onSetMediaItems` override, no start-item resolve or transition handling (that is 4.4). No UI, no notification work, no shuffle/repeat mode flags or persistence, no Room/session persistence.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Song tap in context | tapped `Song` present in n-item context | All n items in original order, startIndex = index of tapped | N/A |
| Song tap, absent/empty context | tapped not found, or empty context | Single-item snapshot of tapped, startIndex 0 | Never throws |
| Collection play | m songs, startIndex k | m items, startIndex k | k < 0 or k >= m -> `IllegalArgumentException` |
| Shuffle with chosen | context containing chosen + seed s | Chosen pinned at 0, rest Fisher-Yates-shuffled via `Random(s)`; same inputs -> byte-identical order | N/A |
| Shuffle without chosen | `first = null` or not in context | Whole list shuffled with seed, startIndex 0 | Never throws |
| Duplicate ids in context | same `SourceId` twice | First occurrence wins for tap index | Documented, no throw |

</frozen-after-approval>

## Code Map

- `playback/src/main/kotlin/com/sway/playback/PlayerConnection.kt` -- L218 `setQueue(snapshot, startIndex)` exists; L245 `setQueueInternal` maps every `QueueItem` -> `MediaItem(mediaId, uri=PendingUri.buildString(qi.id))` uniformly (placeholders already; zero resolved URLs today). Add one thin overload consuming `QueueBuilder.BuiltQueue`.
- `playback/src/main/kotlin/com/sway/playback/PendingUri.kt` -- L14 `object PendingUri`, public today: PREFIX L20, build/buildString L27/L30, isPending L35/L39, extractSourceId L46/L54. Make object `internal`; add law KDoc citing AD-6 rule 6.
- `playback/src/main/kotlin/com/sway/playback/SwayPlaybackService.kt` -- read-only reference; LibraryCallback L100 is empty and MUST stay empty this story.
- `playback/src/test/kotlin/com/sway/playback/PlayerConnectionTest.kt` -- harness idioms: `song(id)`/`queue(vararg)` L60-64, `exoPlayer()` L66, teardown list L376; `pendingUri_singlePointScheme` L360. Extend here for the zero-resolved-URLs player-mapping proof and overload smoke.
- `core/model/src/main/kotlin/com/sway/core/model/QueueSnapshot.kt` / `QueueItem.kt` / `Song.kt` -- value types consumed; `Song.create(...)!!` fixtures pattern.
- `scripts/check_module_edges.sh` + `.github/workflows/android-ci.yml` (L29-30) -- pattern to mirror for the new placeholder grep-audit step.
- `_bmad-output/planning-artifacts/architecture.md` L187/L198 -- AD-6 rule 3 (lazy) and rule 6 (placeholder single-point, grep-audited) — the laws this story enforces.

## Tasks & Acceptance

**Execution:**
- [x] `playback/src/main/kotlin/com/sway/playback/QueueBuilder.kt` -- NEW pure-Kotlin `object QueueBuilder` with `data class BuiltQueue(snapshot, startIndex)` and variants `fromSongTap(tapped, context)`, `fromCollection(songs, startIndex = 0)`, `shuffled(context, first, seed)` (Fisher-Yates via `java.util.Random(seed)`; chosen pinned to index 0 when present) -- gives play actions their context->snapshot substrate (FR-22 trace, C-4).
- [x] `playback/src/main/kotlin/com/sway/playback/PendingUri.kt` -- mark `internal`, add KDoc stating the single-owner law and pointing at the grep audit -- makes scheme misuse impossible outside `:playback` (AC 2).
- [x] `playback/src/main/kotlin/com/sway/playback/PlayerConnection.kt` -- add overload `setQueue(built: QueueBuilder.BuiltQueue)` delegating to existing validated path -- proves builder output feeds the live command unchanged.
- [x] `playback/src/test/kotlin/com/sway/playback/QueueBuilderTest.kt` -- NEW pure-JVM tests covering every I/O Matrix row + determinism seeds (same seed twice -> identical order; two fixed seeds known to differ -> differing order; chosen preserved first) -- AC 1 and 3 evidence.
- [x] `playback/src/test/kotlin/com/sway/playback/PlayerConnectionTest.kt` -- extend: after `setQueue` of an n-item snapshot, assert every player item uri starts `PendingUri.PREFIX` and none is http(s) (zero resolved URLs); overload round-trip keeps chosen at startIndex -- AC 1 player-side proof.
- [x] `scripts/check_placeholder_scheme.sh` -- NEW grep audit: fail when `sway://` or `PendingUri` appears outside `playback/src/**` (allowlist = PendingUri.kt, PlayerConnection.kt, PlayerConnectionTest.kt); wire as one CI step beside the edge audit in `.github/workflows/android-ci.yml` -- mechanical enforcement of AC 2.

**Acceptance Criteria:**
- Given a play context at index k of n items, when a snapshot builds, then all n items appear with the chosen item at startIndex and zero resolved URLs anywhere in the built player media chain.
- Given any attempt to construct/mutate the placeholder scheme from another module, then it cannot compile (internal API) and CI grep-audit fails on stray scheme strings.
- Given shuffle-context input, when the builder runs twice with equal inputs and seed, then both outputs are identically ordered with the chosen current item playing first.

## Spec Change Log

## Design Notes

- Pinning chosen-first (not "shuffle around a fixed position") is deliberate: after a shuffle entry the user expectation is "this track, then surprise me", and startIndex 0 removes any index-drift ambiguity for 4.4's start-resolve.
- Determinism comes only from `java.util.Random(seed)` — no `kotlin.random.Random(seedTime)` mixing; tests use literal fixed seeds so assertions stay exact across machines.
- Builder emits snapshots, never MediaItems: URI stamping stays solely inside `PlayerConnection.setQueueInternal`, keeping "zero resolved URLs" structurally true (there is no URL field to fill).

## Verification

**Commands:**
- `.\gradlew :playback:testDebugUnitTest` -- expected: all prior 16 tests green plus new QueueBuilder + PlayerConnection extension tests passing
- `bash scripts/check_placeholder_scheme.sh` -- expected: exit 0 on current tree; nonzero when tested against a planted violation
- `.\gradlew :app:assembleDebug` -- expected: BUILD SUCCESSFUL (edge audit + manifest intact)

**Manual checks (if no CLI):**
- Confirm `QueueBuilder.kt` contains no `android.*`/`androidx.*` imports.

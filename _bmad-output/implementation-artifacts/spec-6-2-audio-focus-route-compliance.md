---
title: 'Story 6.2 - Audio focus & route-change compliance'
type: 'feature'
created: '2026-08-23'
status: 'done'
review_loop_iteration: 1
baseline_commit: c14f391b0ee39cfcda5f4f9e5e15f68a462b8d6d
context:
  - '{project-root}/_bmad-output/planning-artifacts/epics-and-stories.md (Story 6.2)'
  - '{project-root}/_bmad-output/planning-artifacts/prd.md (FR-19/FR-20; UJ-2 call/BT-off narrative)'
  - '{project-root}/_bmad-output/planning-artifacts/architecture.md (AD-12; AD-6 rule 2 facade; AR-11; NFR-7 LOC budget)'
  - '{project-root}/_bmad-output/planning-artifacts/ux-design-specification.md §6.13 (audio-focus UX parity: UI never fights the system)'
  - '{project-root}/docs/research/phase-4-stream-resolution.md (reference player focus substrate — ideas only, GPL license law respected)'
  - 'media3 1.11.0 sources read at implementation time (Google Maven sources jars, extracted): common/audio/AudioFocusManager.java, common/audio/AudioBecomingNoisyManager.java, exoplayer/ExoPlayerImpl.java, exoplayer/ExoPlayerImplInternal.java (behavior grounding for every assertion below)'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** The playback substrate has carried FR-19/FR-20 machinery since story 4.1 (`setAudioAttributes(musicAttributes, handleAudioFocus=true)` + `setHandleAudioBecomingNoisy(true)`), and the 4.1 suite asserts the configuration flags — but NOTHING proves the observable BEHAVIOR: no test drives a focus loss through the real `AudioFocusManager` pipeline, none drives the becoming-noisy broadcast through the real receiver, none measures the <1 s disconnect-pause budget, none proves permanent-loss never auto-resumes or that a route reconnect cannot resurrect playback. Worse, one genuine compliance edge is missing in code: media3 1.11.0 implements transient focus loss as PLAYBACK SUPPRESSION (`playWhenReady` stays true, `playbackSuppressionReason = TRANSIENT_AUDIO_FOCUS_LOSS`, audible output stops) — and `PlayerConnection.syncFromPlayer` computes `isPlaying = p.isPlaying || p.playWhenReady`, which reports "playing" during a phone call. That violates UX §6.13 ("UI never fights the system") and makes FR-19's "pauses immediately" unobservable through the facade mirror. Story 6.2 completes FR-19 + FR-20 by closing that single facade gap and proving the full AD-12 scenario matrix end-to-end hermetically.

**Approach:** All changes inside `:playback` (+tests):
1. **Facade suppression gating** (`PlayerConnection.syncFromPlayer` + listener): gate the optimistic is-playing computation on `p.playbackSuppressionReason == PLAYBACK_SUPPRESSION_REASON_NONE`, and add `onPlayWhenReadyChanged` / `onPlaybackSuppressionReasonChanged` overrides so every focus/route transition propagates to `uiState` inside the existing 250 ms sync budget. This is the ONLY production change — everything else is proof.
2. **Focus scenario automation** (Robolectric sdk 36, driving the REAL media3 `AudioFocusManager` via its shadow-recorded platform listener): transient loss → suppressed/paused observably + uiState mirrors; regain after transient → auto-resume (user-intent persisted); regain after user pause during transient loss → stays paused (regain never overrides explicit user intent); permanent loss → paused AND focus abandoned (shadow records abandonment) AND no path auto-resumes — only explicit user play() re-acquires focus; can-duck (non-speech content) → playback CONTINUES ducked by the platform grant, Sway neither pauses nor fights it; focus-denied at request time → play refuses to start (no overlap law); rapid churn (dozens of alternating losses/gains) → stable terminal state consistent with the last event.
3. **Route-change automation**: becoming-noisy broadcast while playing → pause observed with wall-clock measurement asserted < 1000 ms (FR-20 budget); afterwards simulated reconnect churn (ACL-connected broadcasts + timer flushes) NEVER auto-resumes; explicit play() resumes.
4. **uiState mirror correctness throughout**: every AC asserts the facade mirror against raw player observables at each transition step.

## Boundaries & Constraints

**Always:**
- Focus/route policy = media3-native semantics via the 4.1 substrate flags; zero new focus/route code paths hand-rolled (AD-12 prevents dual implementations).
- Every behavioral claim grounded in the extracted 1.11.0 sources listed above; tests assert OBSERVABLE player/facade state only.
- Tests are hermetic: Robolectric shadows drive platform focus/broadcast delivery; no device, no network.
- Facade remains the only UI-facing truth (AD-6 rule 2): suppression gating lives in `syncFromPlayer`, nowhere else.

**Ask First:** N/A — headless run; engineering decisions recorded in Design Notes.

**Never:** No duck-volume assertions against internal scalars (platform-internal; documented as device-matrix item); no recents-swipe/notification-permission work (6.3 owns both); no UI module changes; no app-wide broadcasts receivers added; no copying of reference-app code (license law); no changes to notification behavior (6.1 territory).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| AC1: focus-log substrate | playing stack | Shadow records our request: gain=AUDIOFOCUS_GAIN (USAGE_MEDIA mapping), willPauseWhenDucked=false (MUSIC), listener wired | Bounded |
| AC2: transient loss pauses | LOSS_TRANSIENT delivered to recorded listener | playWhenReady stays true (media3 semantics) but suppression=TRANSIENT_AUDIO_FOCUS_LOSS, player.isPlaying=false, facade uiState.isPlaying=false within sync budget | Typed |
| AC3: regain resumes (transient) | AUDIOFOCUS_GAIN after AC2 | Suppression cleared, playing again, uiState mirrors true | N/A |
| AC4: regain vs explicit user pause | LOSS_TRANSIENT → user pause() → GAIN | Stays paused — regain must not override explicit user intent (resume ONLY where policy allows) | N/A |
| AC5: permanent loss stops forever | AUDIOFOCUS_LOSS | Paused; shadow records focus ABANDONED; later unrelated events/timers never resume; explicit facade.play() re-acquires focus and plays | Defensive |
| AC6: can-duck keeps playing | LOSS_TRANSIENT_CAN_DUCK (music content) | No pause, no suppression, isPlaying true (platform ducks at its own multiplier — internal scalar, device-matrix item); GAIN afterwards leaves playing state unchanged | Silent |
| AC7: focus denied refusal | next focus request response = FAILED, then play() | Playback does NOT start; playWhenReady forced false; uiState honest (no phantom playing) | Bounded |
| AC8: becoming-noisy <1 s | ACTION_AUDIO_BECOMING_NOISY broadcast while playing | Paused with measured wall-clock elapsed < 1000 ms; uiState.isPlaying false | Measured |
| AC9: reconnect never auto-resumes | ACL-connected broadcasts + idle processing after AC8 | playWhenReady stays false until explicit facade.play(); then plays | Defensive |
| AC10: rapid focus churn stability | ~50 alternating LOSS/GAIN/CAN_DUCK deliveries with flushes | No crash; service alive; final state consistent with last delivered event; uiState == player observables | Stability |

</frozen-after-approval>

## Code Map

- `playback/src/main/kotlin/com/sway/playback/PlayerConnection.kt` -- `attachPlayer` listener gains `onPlayWhenReadyChanged` + `onPlaybackSuppressionReasonChanged` overrides routing into `syncFromPlayer`; `syncFromPlayer` gates optimistic isPlaying on `playbackSuppressionReason == NONE` (try/catch fallback treats unknown as NONE); KDoc cites FR-19/AD-12/UX §6.13. Sole production change (~10 lines; file stays far under NFR-7).
- `playback/src/test/kotlin/com/sway/playback/AudioFocusRouteComplianceTest.kt` -- NEW Robolectric suite covering the full AC matrix above, built on the proven 6.1 in-process harness (FakeStreamResolver→silent WAV, public `addSession`, engine start path, facade `bareForTest`/`bindPlayer`).
- `_bmad-output/implementation-artifacts/sprint-status.md` -- row 6.2 status + Evidence log entry at completion (Edit tool only).
- `_bmad-output/implementation-artifacts/spec-6-2-audio-focus-route-compliance.md` -- this spec; status -> done at completion.

## Tasks & Acceptance

**Execution:**
- [ ] Suppression-gated `syncFromPlayer` + two listener overrides in `PlayerConnection`.
- [ ] `AudioFocusRouteComplianceTest.kt` NEW: AC1–AC10 scenarios per matrix, each asserting facade-mirror parity at every step.
- [ ] All prior suites green (:playback 95+, :core:data 8, :catalog 123, :core:model 118).

**Acceptance Criteria:**
- Given an incoming-call focus request granted to another app, when the transient loss reaches our listener, then playback pauses immediately (suppression observable, uiState mirrors within budget) and resumes ONLY after focus regained post-call with persisted user intent (AC2/AC3; AC4 pins the explicit-pause override).
- When focus is lost permanently, then playback stops without any auto-resume path and focus is abandoned politely; only an explicit user action replays (AC5).
- When another app requests may-duck focus, then Sway keeps playing under the platform-granted ducking and never pauses or fights the system (AC6); when focus is outright denied, Sway refuses to start (AC7) — together proving "no overlap except platform-granted ducking" via focus-log/shadow assertions (AC1).
- Given BT/wired route disconnect during play, then the becoming-noisy pause occurs measured <1 s (AC8) and reconnect performs NO auto-resume (AC9).
- Rapid focus churn leaves the stack stable and self-consistent (AC10).

## Design Notes

1. **Transient loss = suppression, not playWhenReady=false (grounded in 1.11.0 source).** `AudioFocusManager.handlePlatformAudioFocusChange`: LOSS_TRANSIENT → PLAYER_COMMAND_WAIT_FOR_CALLBACK → `ExoPlayerImplInternal.updatePlaybackSuppressionReason` sets `PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS` while playWhenReady STAYS TRUE; `Player.isPlaying` = READY && playWhenReady && suppression==NONE → false. Regain delivers GAIN → PLAY_WHEN_READY command → suppression recomputed away. Permanent LOSS → PLAYER_COMMAND_DO_NOT_PLAY → playWhenReady forced false with reason AUDIO_FOCUS_LOSS + `abandonAudioFocusIfHeld()`; state resets to NO_FOCUS so nothing can auto-resume. CAN_DUCK with non-speech content → only audioFocusState DUCK → volumeMultiplier 0.2 pushed to renderers (`setVolumeInternal` scales by it); no pause, no suppression, and the scalar is NOT exposed through any public Player getter.
2. **The one genuine code gap (facade).** `syncFromPlayer`'s `p.isPlaying || p.playWhenReady` optimism exists so BUFFERING-with-play-intent shows playing; but during transient suppression playWhenReady stays true, so the facade would display playing during a call — violating UX §6.13 and making FR-19's pause invisible to the app. Fix gates ALL suppressions generically (also future UNSUITABLE_AUDIO_OUTPUT etc.). Ducking deliberately NOT gated: music keeps playing ducked, UI stays playing (AD-12 parity). Unknown-reason reads fall back to NONE inside the existing try/catch discipline (never throws across the boundary).
3. **Why the shadow-listener approach is the real pipeline:** media3 registers its focus listener through `AudioFocusRequestCompat` → platform `AudioManager.requestAudioFocus(AudioFocusRequest)`; Robolectric's `ShadowAudioManager` records exactly that request (`lastAudioFocusRequest` exposing the platform request incl. gain/willPauseWhenDucked/listener). Delivering `onAudioFocusChange(...)` to that recorded listener invokes media3's `handlePlatformAudioFocusChange` — the same entry point the OS calls on-device. Abandonment assertions read `lastAbandonedAudioFocusRequest` (compat delegates abandon to the platform API on 26+; shadow records it).
4. **Ducking scalar honesty:** the 0.2× multiplier lives behind renderer volume plumbing; no public getter exposes it. Hermetic proof therefore covers the OBSERVABLE contract (keep playing, no suppression, no pause) and this note records the scalar as an R-3/device-matrix evidence item — same honesty pattern as 6.1's soak skeleton.
5. **willPauseWhenDucked=false is load-bearing for AD-12:** with MUSIC content type, media3 builds the platform request WITHOUT willPauseWhenDucked, so the SYSTEM ducks us automatically where it grants may-duck (on-device the callback may not even fire — automatic ducking); AC1 pins this flag from the recorded request. Our manual CAN_DUCK delivery additionally proves the in-process branch behaves identically (duck-not-pause) if the platform DOES notify.
6. **Reconnect simulation honesty:** Android defines no "route connected" broadcast that players must honor; the FR-20 law is about OUR stack never resuming spontaneously. Proof = after the noisy-pause, deliver plausible reconnect-flavored broadcasts (BluetoothDevice ACL_CONNECTED/ACL_DISCONNECTED) plus idle processing and assert zero state change; explicit play() then resumes. This is the strongest hermetic form of the epics' "reconnect performs NO auto-resume". Deliberately NO shadow-clock fast-forward (runToEndOfTasks): it would push the 90 s test media past STATE_ENDED, making later resume assertions vacuous — timer hygiene is 6.1's proven territory.
7. **Churn test shape:** alternating transient/permanent/duck deliveries exercise state-machine edges (NOT_REQUESTED→HAVE_FOCUS→LOSS_TRANSIENT→HAVE_FOCUS→NO_FOCUS→…). Terminal-state consistency checked against the LAST event only — intermediate frames are intentionally not asserted (that IS the churn).
8. **Harness reuse:** 6.2 copies the proven 6.1 topology (spec Design Note 9: external controller binding broken under Robolectric; public `addSession` + engine start path + in-process facade). Suite-local duplication accepted per existing precedent; no shared-test-module refactor in scope.
9. **Timing measurement:** FR-20's "<1 s (measured)" is asserted as wall-clock between sendBroadcast and observed pause using System.nanoTime around a tight await loop — single-digit ms typical under Robolectric; the assertion documents the budget rather than simulating device latency.

## Spec Change Log

- 2026-08-24 (implementation): two scenario shapes refined against real harness
  behavior, laws unchanged. (a) AC9/Design Note 6: dropped shadow-clock
  fast-forward from the reconnect/no-auto-resume proofs — runToEndOfTasks
  advances ShadowSystemClock past the 90 s test media (STATE_ENDED), which makes
  subsequent resume assertions vacuous; replaced by idle processing, with timer
  hygiene remaining 6.1's proven territory. (b) AC7: focus-denied is exercised
  through the production permanent-loss fresh-request cycle (LOSS abandons →
  next play() must re-ask the platform → deny that request) instead of a
  pre-play synthetic denial — the engine's startQueueAndPlay auto-plays during
  stack build and would consume any pre-queued denial response, and a
  stop()/prepare() reset stalls under Robolectric. Assertions identical:
  denied request forces DO_NOT_PLAY, facade never phantom-plays, granted
  recovery plays.

## Completion Record

- Implemented: 2026-08-24, single session. Files exactly per Code Map above.
  See sprint-status Evidence log for full verification evidence.

## Verification

**Commands & results (2026-08-24):**

- `$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat :playback:testDebugUnitTest --tests "com.sway.playback.AudioFocusRouteComplianceTest"` — 9/9 green (after the two Change-Log refinements).
- `$env:JAVA_HOME=...; .\gradlew.bat :playback:testDebugUnitTest` — BUILD SUCCESSFUL; **104 tests** (95 prior + **9 new AudioFocusRouteComplianceTest**, 0 failures).
- `$env:JAVA_HOME=...; .\gradlew.bat :core:model:test :catalog:testDebugUnitTest :core:data:testDebugUnitTest` — BUILD SUCCESSFUL; **118 / 123 / 8** unchanged green.
- `"C:\Program Files\Git\bin\bash.exe" scripts/check_placeholder_scheme.sh` — exit 0 ("Placeholder scheme audit OK").
- `"C:\Program Files\Git\bin\bash.exe" scripts/check_module_edges.sh` — exit 0 ("Edge audit OK").
- `$env:JAVA_HOME=...; .\gradlew.bat :app:assembleDebug` — BUILD SUCCESSFUL.
- LOC budgets: PlayerConnection 570, SwayPlaybackService 240, AudioFocusRouteComplianceTest 588 — all far under the NFR-7 hard 1000-line CI budget.

**Self-review loop (iteration 1):** adversarial pass found and fixed: (a) test-side
compile defects (nonexistent builder helper, junk assertions) before first run;
(b) `runToEndOfTasks()` fast-forwards ShadowSystemClock past the 90 s media →
STATE_ENDED made later resume assertions fail/vacuous — replaced with idle
processing (Change Log item a); (c) engine auto-play consumed pre-queued focus
denials + stop/prepare stalls under Robolectric — AC7 reshaped through the
production loss→fresh-request cycle (Change Log item b); (d) bare-idle asserts
raced the real playback-thread handler — all transition waits moved to
awaitUntil polling. Full suite re-run green after fixes.

**Media3 sources grounding record:** behavior asserted in this suite was read
from extracted Google Maven sources jars for exactly 1.11.0
(common/audio/AudioFocusManager.java — LOSS/GAIN/CAN_DUCK switch,
willPauseWhenDucked()=contentType==SPEECH, VOLUME_MULTIPLIER_DUCK=0.2f;
common/audio/AudioBecomingNoisyManager.java — receiver registration + listener
post; exoplayer/ExoPlayerImpl.java — onAudioBecomingNoisy →
updatePlayWhenReady(false, REASON_AUDIO_BECOMING_NOISY); exoplayer/
ExoPlayerImplInternal.java — WAIT_FOR_CALLBACK→TRANSIENT suppression with
playWhenReady preserved, DO_NOT_PLAY→forced pause+reason AUDIO_FOCUS_LOSS,
setVolumeInternal scaling by getVolumeMultiplier()).

---
title: 'Story 6.3 - Recents-swipe posture & notification permission'
type: 'feature'
created: '2026-08-24'
status: 'done'
review_loop_iteration: 1
baseline_commit: d8d1b9a
context:
  - '{project-root}/_bmad-output/planning-artifacts/epics-and-stories.md (Story 6.3)'
  - '{project-root}/_bmad-output/planning-artifacts/prd.md (FR-21; P-3 provisional; OQ-5)'
  - '{project-root}/_bmad-output/planning-artifacts/architecture.md (AD-6 rule 8 thin defaults; AD-4 no distribution claims; AR-11)'
  - '{project-root}/_bmad-output/planning-artifacts/ux-design-specification.md §6.13 (Recents-swipe: playback continues; notification remains the stop affordance)'
  - '{project-root}/_bmad-output/planning-artifacts/architecture.memlog.md (carried unknown: POST_NOTIFICATIONS-denied behavior of media FGS on 13+)'
  - 'media3 1.11.0 sources read at implementation time (Google Maven sources jar, extracted): session/MediaSessionService.java (onTaskRemoved law), session/MediaNotificationManager.java (POST_NOTIFICATIONS exemption comment + official-docs citation)'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** FR-21 (PROVISIONAL P-3, veto via OQ-5) demands: swiping the app from Recents must NOT stop active playback — the notification remains the stop affordance. The stack's posture comes from media3's `MediaSessionService.onTaskRemoved` default which our 4.1 skeleton faithfully calls through to (`super.onTaskRemoved`), but NOTHING proves the observable behavior hermetically: no test drives a swipe-away while playing and asserts service-aliveness + continued playback + surviving stop-affordance, none pins the paused/idle counterpart (`pauseAllPlayersAndStopSelf`), and none records the API-33+ denied-notification degradation for R-3. Separately, the POST_NOTIFICATIONS runtime permission declared in story 6.1 has NO explain-first flow: Android 13+ users would get a cold system dialog with no rationale — violating the story's "permission prompts must explain themselves first" and UX honesty principles.

**Approach:** Two thin production substrates + proof:
1. **Swipe-away compliance suite** (:playback): drive `onTaskRemoved` directly against the full production stack (6.1 in-process harness) — playing ⇒ service alive, playback continues, notification survives as the stop affordance, facade mirror honest; paused/idle ⇒ players paused AND service self-stopped per the platform default (consistent with A-10 dismissal spirit); plus the denied-permission degradation observation: with POST_NOTIFICATIONS **not granted**, playback still runs and the media notification still posts (media-session notifications are platform-exempt) — recorded as documented degradation feeding R-3.
2. **Explain-first permission gate** (:app): a pure decision law (`NotificationPermissionGate`) mechanically enforcing "the system dialog is NEVER launched before rationale copy is acknowledged", string resources carrying the lock-screen-control consequence wording, and minimal real wiring in MainActivity (rationale dialog → system request). Hermetic flow test proves no system request precedes rationale acknowledgment.
3. **Device-gated instrumented skeletons** (4.4 LiveSmoke / 6.1 soak precedent): real Recents-swipe continuity + real permission-flow UI evidence, executed at E6 exit-criteria time on hardware.

## Boundaries & Constraints

**Always:**
- Swipe-away policy = media3-native default semantics via `super.onTaskRemoved` (AD-6 rule 8 thin wrapping; AD-12 spirit prevents dual implementations). Zero new stop/continue logic hand-rolled.
- Every behavioral claim grounded in the extracted 1.11.0 sources cited above or official Android docs; tests assert OBSERVABLE player/facade/notification state only.
- Tests are hermetic: Robolectric sdk 36, direct `onTaskRemoved` invocation, shadow-recorded notification manager state; no device, no network.
- Permission decision law lives in ONE tested place (`NotificationPermissionGate`); MainActivity only executes it.
- P-3/OQ-5 posture honored as-is (playback continues post-swipe); OQ-5 veto would flip behavior later via a single override — noted, not implemented.

**Ask First:** N/A — headless run; engineering decisions recorded in Design Notes.

**Never:** No UI/design-language work beyond the minimal rationale surface (Epic 9 owns theming/navigation); no changes to notification provider internals (6.1 territory); no copying of reference-app code (license law); no new module edges (AR-1 audits); no settings screen work (15.1).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| AC1: swipe-away while playing | active playback, `service.onTaskRemoved(intent)` | Service NOT self-stopped; player keeps playing (`playWhenReady` true, `isPlaying` true); facade uiState.isPlaying mirrors true; media notification still posted = stop affordance | Bounded |
| AC2: swipe-away while paused | paused mid-queue, then `onTaskRemoved` | Players PAUSED (platform pause-all) and service self-stopped — consistent with A-10 dismissal default and idle self-stop law | Defensive |
| AC3: swipe-away while idle (no error) | user-intent IDLE, then `onTaskRemoved` | Service self-stopped (no zombie FGS without purpose; NFR-10) | Defensive |
| AC4: denied notifications degradation | POST_NOTIFICATIONS NOT granted (API 33+ shadow), playback started | Playback runs; media notification STILL posted (media-session exemption per official docs, grounded in MediaNotificationManager source); recorded as documented degradation → R-3 device-matrix confirmation item | Silent |
| AC5: explain-first ordering | fresh launch, API 33+, permission ungranted, rationale never shown | Gate returns SHOW_RATIONALE_THEN_REQUEST; system dialog NOT launched until rationale acknowledged (mechanical table test over all state combinations) | Typed |
| AC6: granted / legacy API paths | granted=true OR apiLevel < 33 | Gate returns NOTHING_TO_DO — no dialog, no request, zero friction | Silent |
| AC7: rationale copy exists & precedes | strings present in :app resources; MainActivity wiring executes gate | Rationale title/body carry lock-screen-control consequence wording; flow test proves launch-time no-request-before-rationale under Robolectric sdk 36 | Bounded |

</frozen-after-approval>

## Code Map

- `playback/src/test/kotlin/com/sway/playback/RecentsSwipeComplianceTest.kt` -- NEW Robolectric suite covering AC1–AC4 on the proven 6.1 harness (FakeStreamResolver→silent WAV, public `addSession`, engine start path, facade `bareForTest`/`bindPlayer`).
- `playback/src/main/kotlin/com/sway/playback/SwayPlaybackService.kt` -- KDoc/comment-only update to `onTaskRemoved` citing the 6.3 grounding (1.11.0 `MediaSessionService.onTaskRemoved` law); NO behavior change (default already IS the P-3 posture).
- `app/src/main/kotlin/com/sway/music/notifications/NotificationPermissionGate.kt` -- NEW pure decision law (AC5/AC6): `nextAction(apiLevel, granted, rationaleAcknowledged)` → `SHOW_RATIONALE_THEN_REQUEST` / `REQUEST_SYSTEM_DIALOG` / `NOTHING_TO_DO`; system dialog unreachable until rationale acknowledged.
- `app/src/main/res/values/strings.xml` -- NEW rationale copy: title/body with lock-screen-control consequence wording + continue action + denied-degradation note (release-checklist artifact).
- `app/src/main/kotlin/com/sway/music/MainActivity.kt` -- minimal explain-first wiring: gate evaluated on first composition; rationale M3 AlertDialog → `RequestPermission` contract; nothing else changes.
- `app/src/test/kotlin/com/sway/music/notifications/NotificationPermissionGateTest.kt` -- NEW: exhaustive state-table law tests (AC5/AC6) + Robolectric launch test proving NO system permission request fires before rationale acknowledgment (AC7).
- `playback/src/androidTest/kotlin/com/sway/playback/RecentsSwipeContinuityDeviceTest.kt` -- NEW @Ignore device-gated skeleton (real Recents swipe + matrix steps, feeds R-3).
- `app/src/androidTest/kotlin/com/sway/music/PermissionFlowUiDeviceTest.kt` -- NEW @Ignore device-gated skeleton (real permission-flow UI evidence).
- `app/build.gradle.kts` -- androidTest deps added (same trio :playback uses) so the skeleton compiles.
- `_bmad-output/planning-artifacts/architecture.memlog.md` -- carried unknown ("POST_NOTIFICATIONS-denied behavior of media FGS on 13+") resolved with the grounded answer.
- `_bmad-output/implementation-artifacts/sprint-status.md` -- row 6.3 status + Evidence log entry at completion (Edit tool only).
- `_bmad-output/implementation-artifacts/spec-6-3-recents-swipe-posture-notification-permission.md` -- this spec; status -> done at completion.

## Tasks & Acceptance

**Execution:**
- [ ] `RecentsSwipeComplianceTest.kt` NEW: AC1–AC4 per matrix, facade-mirror parity asserted at every step.
- [ ] `NotificationPermissionGate` + copy + MainActivity wiring + gate/flow tests (AC5–AC7).
- [ ] Device-gated skeletons + :app androidTest deps.
- [ ] All prior suites green (:playback 104+, :core:data 8, :catalog 123, :core:model 118, :app 5+).

**Acceptance Criteria:**
- Given active playback, when the task is removed (recents-swipe), then playback continues, the service stays alive, and the notification remains posted as the stop affordance — with the facade mirror honest throughout (AC1).
- Given paused/idle states, when the task is removed, then the service pauses-all and self-stops per the platform default (AC2/AC3) — no zombie foreground service.
- Given notifications denied on API 33+, when playback runs, then the media notification still posts per the documented media-session exemption and playback is unaffected — degradation recorded for R-3/release checklist (AC4).
- And the permission rationale copy precedes the system dialog: mechanically enforced by the gate law (AC5–AC6) and proven at launch time (AC7).

## Design Notes

1. **The swipe-away law IS media3 1.11.0's default (grounded in source).** Extracted `MediaSessionService.java`: `public void onTaskRemoved(...) { if (!isPlaybackOngoing() || !isAnySessionPlaying()) { pauseAllPlayersAndStopSelf(); } }`. While playing (`isPlaybackOngoing()` true via foreground ownership + `isAnySessionPlaying()` true) NOTHING happens — service lives, audio continues. Paused/idle ⇒ `pauseAllPlayersAndStopSelf()`. Our skeleton's `super.onTaskRemoved(rootIntent)` inherits exactly this; therefore FR-21's P-3 posture requires ZERO production logic — only proof + documentation. This is the same thin-defaults discipline as 6.1's A-10 stance.
2. **Why driving `onTaskRemoved` directly is honest:** Android exposes no public API to synthesize a Recents swipe; instrumentation-level swipes are flaky and device-specific. The OS contract is precisely that `Service.onTaskRemoved(rootIntent)` is called when the user removes a task from recents — invoking it on the live service under Robolectric exercises the REAL dispatch target with identical semantics. The residual risk (system killing the process despite the FGS) is hardware-behavior territory → covered by the device-gated skeleton + R-3 matrix, same split as 6.1's soak.
3. **Paused-swipe stops the service — deliberate, consistent, documented:** FR-21 protects ACTIVE playback only; pausing already makes the notification swipe-dismissable (A-10, 6.1) and idle-self-stop (4.1/NFR-10) kills purposeless services. `pauseAllPlayersAndStopSelf` on swipe-while-paused aligns all three defaults into one coherent posture: Sway plays until the user says stop, and stops existing when there is nothing to play.
4. **Denied-notification degradation is an EXEMPTION, not breakage (grounded):** `MediaNotificationManager.updateNotificationInternal` posts with `@SuppressLint("MissingPermission")` citing the official docs page "notification-permission#exemptions-media-sessions": POST_NOTIFICATIONS is NOT required for media-session-related notifications. So on API 33+ with the permission DENIED: playback unaffected, media notification + lock-screen controls keep working; what's lost is only non-exempt notification surfaces (Sway v1 posts none besides the media notification). Hermetic assertion documents the pipeline behavior; on-device confirmation across OEM skins stays an R-3 device-matrix item (recorded in architecture.memlog + release-checklist feed for 15.3).
5. **Explain-first as a mechanical law, not UI convention:** the gate's truth table makes the illegal state unrepresentable — `REQUEST_SYSTEM_DIALOG` requires `rationaleAcknowledged=true`. MainActivity consumes the gate; it CANNOT skip the rationale because the launcher call site sits behind the returned action. Copy carries the lock-screen-control consequence wording per the story ("prompts must explain themselves first").
6. **MainActivity wiring kept minimal deliberately:** Epic 9 owns theming/navigation; the rationale surface uses stock M3 AlertDialog + our strings so the BEHAVIOR ships now and can be restyled later without touching the decision law. Below API 33 or when granted, the composable evaluates to a no-op (zero startup cost beyond one check — startup hygiene respected, NFR-1 substrate).
7. **Harness reuse:** copies the proven 6.1 topology (spec Design Note 9 precedent: external controller binding broken under Robolectric; public `addSession` + engine start path + in-process facade). Suite-local duplication accepted per existing precedent; no shared-test-module refactor in scope.
8. **Denied-permission test shape:** grant nothing in setUp (contrast to 6.1's explicit grant), run the SAME startPlaying path, assert playback reaches READY and our notification id appears in the shadow manager. Under Robolectric `notify()` isn't permission-gated, so the assertion pins OUR pipeline (media3 posts regardless) while the platform-side guarantee rests on the cited exemption — both halves recorded honestly (source citation + device matrix).

## Completion Record

- Implemented: 2026-08-24, single session. Files exactly per Code Map above.
  See sprint-status Evidence log for full verification evidence.

## Verification

**Commands & results (2026-08-24):**

- `$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat :playback:testDebugUnitTest --tests "com.sway.playback.RecentsSwipeComplianceTest"` — 4/4 green first run.
- `$env:JAVA_HOME=...; .\gradlew.bat :playback:testDebugUnitTest` — BUILD SUCCESSFUL; **108 tests** (104 prior + **4 new RecentsSwipeComplianceTest**, 0 failures).
- `$env:JAVA_HOME=...; .\gradlew.bat :app:testDebugUnitTest` — BUILD SUCCESSFUL; **11 tests** (5 prior + 5 NotificationPermissionGateTest + 1 NotificationPermissionFlowTest).
- `$env:JAVA_HOME=...; .\gradlew.bat :core:model:test :catalog:testDebugUnitTest :core:data:testDebugUnitTest` — BUILD SUCCESSFUL; **118 / 123 / 8** unchanged green.
- `"C:\Program Files\Git\bin\bash.exe" scripts/check_placeholder_scheme.sh` — exit 0 ("Placeholder scheme audit OK").
- `"C:\Program Files\Git\bin\bash.exe" scripts/check_module_edges.sh` — exit 0 ("Edge audit OK").
- `$env:JAVA_HOME=...; .\gradlew.bat :app:assembleDebug` — BUILD SUCCESSFUL.
- LOC budgets: all new files far under NFR-7 (gate 51, gate test 96, flow test 48, compliance test ~370, skeletons ~35 each); SwayPlaybackService comment-only growth (~5 lines).

**Self-review loop (iteration 1):** adversarial pass before first run found and
removed: (a) a shadow-clock fast-forward position-advance assertion in AC1 —
nondeterministic under Robolectric per 6.2 Change Log precedent (scheduler time,
not wall clock, drives media position), replaced by the deterministic law set
(playWhenReady + isPlaying + service aliveness + facade mirror); (b) junk
premise assertions in the first flow-test draft, rewritten to clean
assertEquals premise + lastRequestedPermission==null proof; (c) verified the
Robolectric ShadowActivity API surface via javap (`getLastRequestedPermission`)
before writing the flow test. Full suites re-run green.

**Media3 sources grounding record:** extracted Google Maven sources jar for
exactly 1.11.0 (session/MediaSessionService.java — `onTaskRemoved` =
`if (!isPlaybackOngoing() || !isAnySessionPlaying()) pauseAllPlayersAndStopSelf();`
plus `isAnySessionPlaying()` scanning sessions' `player.isPlaying`;
session/MediaNotificationManager.java — `updateNotificationInternal` posts under
`@SuppressLint("MissingPermission")` citing developer.android.com
notification-permission#exemptions-media-sessions).
